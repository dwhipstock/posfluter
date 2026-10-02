package dev.dwhipstock.poscloud.menuprint

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.CloudConfig
import dev.dwhipstock.poscloud.ConflictException
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.db.ItemPhotos
import dev.dwhipstock.poscloud.menu.menuOf
import dev.dwhipstock.poscloud.menuai.AiCaller
import dev.dwhipstock.poscloud.menuai.GeminiMenuModel
import dev.dwhipstock.poscloud.menuai.GeneratedImage
import dev.dwhipstock.poscloud.menuai.ImageGen
import dev.dwhipstock.poscloud.menuai.MenuAiException
import dev.dwhipstock.poscloud.menuai.MenuAiLimiter
import dev.dwhipstock.poscloud.menuai.MenuAiModel
import dev.dwhipstock.poscloud.menuai.MenuAiService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Printable menus, in three steps so the portal
 * can show where it is: [plan] (the AI writes the menu and picks its look —
 * or the plain plan when it can't), [art] (the AI artwork, cached), [render]
 * (the PDF, the same for phone and computer). The plan is kept here for
 * [JOB_TTL_MS] under its job id, for the user and store that made it.
 *
 * Limits: [PLANS_MAX] menus per [WINDOW_MS] per user, [ART_MAX] artwork
 * runs that make new pictures; AI calls count toward MENU_AI_DAILY_CAP and
 * are logged in menu_ai_log (kind "print"; never text, pictures or keys).
 */
class MenuPrintService(
    private val config: CloudConfig,
    private val ai: MenuAiService,
    private val model: MenuAiModel? = config.menuAiKey?.let {
        GeminiMenuModel(it.value, config.menuPrintModel, budgetMs = PrintAi.AI_BUDGET_MS, thinkingLevel = PrintAi.THINKING,
            temperature = 0.9, maxOutputTokens = PrintAi.MAX_OUTPUT_TOKENS)
    },
    private val images: ImageGen = ai.photos.images,
    private val now: () -> Long = System::currentTimeMillis,
    private val clock: () -> Instant = Instant::now,
    private val artDeadlineMs: Long = ArtMaker.DEADLINE_MS,
    private val aiTimeoutMs: Long = AI_TIMEOUT_MS,
    plansMax: Int = PLANS_MAX,
) {
    private val log = LoggerFactory.getLogger(MenuPrintService::class.java)
    private val plans = MenuAiLimiter(plansMax, WINDOW_MS, now)
    private val arts = MenuAiLimiter(ART_MAX, WINDOW_MS, now)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val maker = ArtMaker(images, now = now)
    private val aiPool = Executors.newCachedThreadPool { r -> Thread(r, "menu-print-ai").apply { isDaemon = true } }

    val aiEnabled: Boolean get() = model != null
    val artEnabled: Boolean get() = images.enabled

    companion object {
        /** The hard limit on the AI's writing: past it, the plain menu. */
        const val AI_TIMEOUT_MS = PrintAi.AI_BUDGET_MS + 3_000
        const val PLANS_MAX = 12
        const val ART_MAX = 8
        const val WINDOW_MS = 10 * 60_000L
        const val JOB_TTL_MS = 30 * 60_000L
        private const val MAX_JOBS = 40
        /** PDFs rendered at once on this server: one (each is CPU and memory heavy; the API runs in a 512 MB heap). */
        private val RENDERS = Semaphore(1)
        private const val MAX_PRINTED_ITEMS = 800
    }

    private class Job(
        val id: String, val tenantId: String, val venueId: String, val userId: Long, val at: Long,
        val req: PrintRequest, val kind: MenuKind, val lang: String, val paper: Paper, val brand: PrintBrand,
        val style: PrintStyle, val catalog: PrintCatalog, val plan: MenuPlan, val moment: TodayRules.Moment,
        val slots: List<ArtSlot>, val ai: String, val aiReason: String?, val notesIgnored: Boolean,
        /** The art slots that have a picture in the cache (the bytes stay there, not in memory here). */
        @Volatile var artReady: Set<String> = emptySet(),
    )

    // --- 1. writing the menu ---

    fun plan(who: AiCaller, req: PrintRequest): PrintPlanResult {
        val kind = MenuKind.of(req.type) ?: throw BadRequestException("type is full, today, drinks, highlights or flyer", "bad_request")
        val lang = PrintWords.lang(req.lang)
        if (req.style != null && req.style != "auto" && req.style !in PrintStyles.KEYS)
            throw BadRequestException("style is auto or one of ${PrintStyles.KEYS.joinToString(", ")}", "bad_request")
        plans.admit(listOf("print:${who.principal.tenantId}:${who.principal.userId}"))?.let { retry ->
            throw MenuAiException(429, "menu_print_too_many", "too many printed menus at once: at most $PLANS_MAX every ${WINDOW_MS / 60_000} minutes", retry)
        }
        val started = now()
        val catalog = transaction { PrintCatalog.of(menuOf(listOf(who.venue.scope)), lang) }
        val moment = TodayRules.moment(clock(), who.venue.zone)
        val candidates = PrintSelect.candidates(kind, catalog, moment.day).take(MAX_PRINTED_ITEMS)
        if (candidates.isEmpty()) throw ConflictException(
            if (kind == MenuKind.TODAY) "nothing on this store's menu is sold today" else "this store's menu has nothing to print", "menu_print_empty")
        val notes = PrintAi.notes(req.notes)
        val notesIgnored = !req.notes.isNullOrBlank() && notes == null
        val brand = PrintBrand.of(req.brand)

        var aiState = "off"
        var reason: String? = "not_setup"
        var aiPlan: AiPlan? = null
        val m = model
        if (m != null) {
            aiState = "fallback"
            reason = when {
                candidates.size > PrintAi.MAX_ITEMS_IN_PROMPT -> "too_big"
                ai.overDailyCap(who) -> "daily_limit"
                else -> null
            }
            if (reason == null) {
                val system = PrintAi.system(kind, lang, MenuAiService.LANGUAGE_NAMES[lang] ?: "English",
                    PrintWords.dayName(moment.day, "en"), PrintSelect.flyerHasSpecials(catalog), PrintAi.copyBudget(candidates.size))
                val user = PrintAi.user(PrintAi.menuData(catalog, candidates, lang, complete = kind.complete), notes)
                val t0 = now()
                val call = aiPool.submit<String> { m.complete(system, user, null) }
                val reply = try {
                    call.get(aiTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    call.cancel(true)
                    reason = "timeout"; null
                } catch (e: java.util.concurrent.ExecutionException) {
                    val code = (e.cause as? MenuAiException)?.code
                    reason = if (code == "menu_ai_timeout") "timeout" else "unavailable"
                    log.info("print menu AI via ${m.model}: ${code ?: e.cause?.javaClass?.simpleName}")
                    null
                }
                val outcome = reply?.let { PrintAi.parseOutcome(it, kind, catalog, candidates, lang, listOfNotNull(brand.name, who.venue.name)) }
                aiPlan = outcome?.plan
                if (reply != null && aiPlan == null) reason = "bad_reply"
                if (aiPlan != null) aiState = "used"
                // how the reply read (ok, repaired_truncated, not_json…), its size and time: never its content
                log.info("print menu AI via ${m.model}: ${kind.code}/$lang ${candidates.size} item(s), reply ${outcome?.reason ?: reason} " +
                    "(${reply?.length ?: 0} chars) in ${now() - t0}ms")
                ai.record(who, "print", if (aiPlan != null) "used_${outcome?.reason}" else "${reason}_${outcome?.reason ?: "none"}",
                    aiPlan?.plan?.itemIds?.size ?: 0, aiPlan?.dropped ?: 0, now() - t0)
            }
        }
        val plan = aiPlan?.plan ?: PrintSelect.plain(kind, catalog, candidates, lang)
        val (styleKey, by) = PrintStyles.choose(req.style?.takeIf { it != "auto" }, aiPlan?.style, req.notes, kind)
        val style = PrintStyles.of(styleKey, brand)
        val paper = Paper.of(req.paper)
        val slots = if (!images.enabled) emptyList() else ArtPrompts.slots(
            who.principal.tenantId, kind, style, plan, catalog, aiPlan?.artScene, notes, who.venue.retail,
            fillPhotos = req.photos && req.fillPhotos, paperRatio = paper.ratio,
        )
        sweep()
        val id = UUID.randomUUID().toString()
        jobs[id] = Job(id, who.principal.tenantId, who.venue.venueId, who.principal.userId, now(), req, kind, lang, paper, brand,
            style, catalog, plan, moment, slots, aiState, if (aiState == "used") null else reason, notesIgnored)
        log.info("print menu ${kind.code}/$lang for ${who.venue.venueId}: ai $aiState${reason?.let { " ($it)" } ?: ""}, style $styleKey (${by.code}), " +
            "${plan.itemIds.size} item(s), ${slots.size} picture(s) to make")
        return PrintPlanResult(id, styleKey, by.code, aiState, if (aiState == "used") null else reason, notesIgnored,
            plan.itemIds.size, slots.size, images.enabled, now() - started)
    }

    private fun job(who: AiCaller, id: String): Job = jobs[id]
        ?.takeIf { it.tenantId == who.principal.tenantId && it.venueId == who.venue.venueId && it.userId == who.principal.userId }
        ?.takeIf { now() - it.at < JOB_TTL_MS }
        ?: throw NotFoundException("that menu has expired; generate it again", "menu_print_expired")

    private fun sweep() {
        val cutoff = now() - JOB_TTL_MS
        jobs.entries.removeIf { it.value.at < cutoff }
        if (jobs.size >= MAX_JOBS) jobs.entries.sortedBy { it.value.at }.take(jobs.size - MAX_JOBS + 1).forEach { jobs.remove(it.key) }
    }

    // --- 2. making the artwork ---

    fun art(who: AiCaller, jobId: String, req: PrintArtRequest): PrintArtResult {
        val job = job(who, jobId)
        val started = now()
        if (job.slots.isEmpty()) return PrintArtResult(jobId, 0, 0, 0, elapsedMs = 0)
        val needed = req.fresh || job.slots.any { ArtCache.get(job.tenantId, it.key) == null }
        if (needed) arts.admit(listOf("art:${job.tenantId}:${job.userId}"))?.let { retry ->
            throw MenuAiException(429, "menu_print_art_too_many", "too much new artwork at once: try again in a few minutes", retry)
        }
        val made = maker.make(job.tenantId, job.slots, req.fresh, artDeadlineMs)
        job.artReady = made.pictures.keys
        var saved = 0
        if (job.req.savePhotos && job.req.fillPhotos) {
            for (s in job.slots.filter { it.purpose == ArtPurpose.PHOTO }) {
                val pic = made.pictures[s.id] ?: continue
                val prov = made.providers[s.id]
                if (ai.photos.saveForPrint(who, s.itemId!!, GeneratedImage(pic.bytes, pic.contentType),
                        prov?.id ?: pic.provider, prov?.model ?: "cached") != null) saved++
            }
        }
        val photos = job.slots.count { it.purpose == ArtPurpose.PHOTO && it.id in made.pictures }
        ai.record(who, "print_art", if (made.failed == 0) "made" else "partial", made.made, made.failed, now() - started, jobId)
        log.info("print art for ${job.venueId}: ${made.made} made, ${made.reused} reused, ${made.failed} built-in, $saved photo(s) kept")
        return PrintArtResult(jobId, made.made, made.reused, job.slots.size - made.made - made.reused, photos, saved, now() - started)
    }

    // --- 3. the PDF ---

    fun render(who: AiCaller, jobId: String): PrintResult {
        val job = job(who, jobId)
        val started = now()
        if (!RENDERS.tryAcquire(90, TimeUnit.SECONDS))
            throw MenuAiException(503, "menu_print_busy", "the printer is busy; try again in a moment")
        try {
            val art = job.slots.filter { it.id in job.artReady }.mapNotNull { s -> ArtCache.get(job.tenantId, s.key)?.let { s.id to it } }.toMap()
            val photos = if (!job.req.photos) emptyMap() else loadPhotos(job) +
                art.filterKeys { it.startsWith("photo:") }.mapKeys { it.key.removePrefix("photo:") }.mapValues { it.value.bytes }
            val doc = PrintDoc(job.kind, job.lang, job.paper, job.style, job.brand, who.venue.name, who.venue.currency,
                job.plan, job.catalog, job.moment, job.req.photos, photos, art)
            val r = MenuPdf.render(doc)
            val previews = MenuPdf.previews(r.pdf)
            log.info("print PDF ${job.kind.code} for ${job.venueId}: ${r.pages} page(s), ${r.pdf.size / 1024} KB in ${now() - started}ms")
            return PrintResult(Base64.getEncoder().encodeToString(r.pdf), fileName(who.venue.name, job.kind, job.moment.date), r.pages, previews,
                job.ai, job.aiReason, job.notesIgnored, job.plan.itemIds.size, now() - started)
        } finally {
            RENDERS.release()
        }
    }

    /** The store's own photos of the printed items (the cloud's copy, PhotoSync). */
    private fun loadPhotos(job: Job): Map<String, ByteArray> {
        val ids = job.plan.itemIds.filter { job.catalog.item(it)?.hasPhoto == true }
        if (ids.isEmpty()) return emptyMap()
        return transaction {
            ItemPhotos.selectAll().where {
                (ItemPhotos.tenantId eq job.tenantId) and (ItemPhotos.venueId eq job.venueId) and (ItemPhotos.itemId inList ids)
            }.associate { it[ItemPhotos.itemId] to it[ItemPhotos.content] }
        }
    }

    private fun fileName(store: String, kind: MenuKind, date: LocalDate): String {
        val slug = java.text.Normalizer.normalize(store, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "menu" }
        return "$slug-${kind.code}-$date.pdf"
    }
}
