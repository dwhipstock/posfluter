package dev.dwhipstock.poscloud.rooms

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.CloudConfig
import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.ConflictException
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.CatalogCategories
import dev.dwhipstock.poscloud.db.MenuAiLog
import dev.dwhipstock.poscloud.db.RoomAiApplies
import dev.dwhipstock.poscloud.menu.CloudHlc
import dev.dwhipstock.poscloud.menuai.AiAudio
import dev.dwhipstock.poscloud.menuai.AiCaller
import dev.dwhipstock.poscloud.menuai.AiGuard
import dev.dwhipstock.poscloud.menuai.MenuAiException
import dev.dwhipstock.poscloud.menuai.MenuAiLimiter
import dev.dwhipstock.poscloud.menuai.MenuAiReplyException
import dev.dwhipstock.poscloud.menuai.MenuAiService
import dev.dwhipstock.poscloud.menuai.Scrub
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// --- wire shapes (the store's RoomLayoutProposalDto / FloorEditProposalDto, plus the store and the room) ---

/** One line of a change's preview: number / shape / seats / position / size / rotation, before → after. */
@Serializable
data class FloorChangeDetail(val field: String, val label: String? = null, val before: String? = null, val after: String? = null)

@Serializable
data class FloorChangeDto(
    val id: String,
    /** add_table | update_table | remove_table | add_object | update_object | remove_object */
    val kind: String,
    val title: String,
    val details: List<FloorChangeDetail> = emptyList(),
)

@Serializable
data class RoomPhotoProposalDto(
    val proposalId: String,
    val venueId: String,
    val model: String,
    val roomName: String,
    val labelPrefix: String,
    val tables: List<RoomTableDto>,
    val objects: List<RoomObjectDto>,
    /** What the AI was not sure about (checked plain text, may be empty). */
    val notes: String = "",
    /** What the cloud dropped or fixed (in the manager's language). */
    val rejected: List<String> = emptyList(),
    val elapsedMs: Long = 0,
    /** off_topic | no_change: the fixed reply in [message], no layout. */
    val refusal: String? = null,
    val message: String? = null,
)

@Serializable
data class RoomPhotoApplyRequest(val proposalId: String, val name: String? = null)

@Serializable
data class FloorEditProposalDto(
    val proposalId: String,
    val venueId: String,
    val roomId: String,
    val model: String,
    val summary: String = "",
    val transcript: String? = null,
    val changes: List<FloorChangeDto> = emptyList(),
    val tables: List<RoomTableDto> = emptyList(),
    val objects: List<RoomObjectDto> = emptyList(),
    val removedTables: List<String> = emptyList(),
    val removedObjects: List<String> = emptyList(),
    val rejected: List<String> = emptyList(),
    val existingTables: Int = 0,
    val protectedTables: List<String> = emptyList(),
    val elapsedMs: Long = 0,
    val refusal: String? = null,
    val message: String? = null,
    /** More than [FloorEditAi.CONFIRM_REMOVES] removals: Apply needs confirmed = true (409 menu_ai_confirm_required). */
    val bulk: Boolean = false,
)

@Serializable
data class FloorChatRequest(val text: String, val lang: String? = null)

@Serializable
data class FloorApplyRequest(val proposalId: String, val confirmed: Boolean = false)

@Serializable
data class RoomAiApplyResult(val applyId: String, val roomId: String, val applied: Int, val summary: String)

@Serializable
data class RoomAiRevertResult(val applyId: String, val reverted: Int, val skipped: Int)

/** One step that puts the floor back: set [fields] of the thing again (a fresh stamp). */
@Serializable
internal data class RoomUndo(val entity: String, val id: String, val fields: Map<String, JsonElement>)

/** The fixed replies (the store's AiGuard texts for the floor assistants). */
internal object RoomReplies {
    private val ROOM_OFF_TOPIC = mapOf(
        "en" to "I can only set up a floor plan from pictures of a room, a sketch or a printed plan.",
        "fr" to "Je peux seulement créer un plan de salle à partir de photos d'une salle, d'un croquis ou d'un plan imprimé.",
        "es" to "Solo puedo crear un plano a partir de fotos de una sala, un boceto o un plano impreso.",
        "de" to "Ich kann nur aus Fotos eines Raums, einer Skizze oder eines gedruckten Plans einen Raumplan erstellen.",
        "af" to "Ek kan net ’n vloerplan opstel uit foto’s van ’n vertrek, ’n skets of ’n gedrukte plan.",
    )
    private val ROOM_NO_LAYOUT = mapOf(
        "en" to "I couldn't find any tables in those pictures. Try a clearer photo taken from higher up, or a sketch.",
        "fr" to "Je n'ai trouvé aucune table sur ces images. Essayez une photo plus nette prise de plus haut, ou un croquis.",
        "es" to "No encontré ninguna mesa en esas imágenes. Prueba una foto más nítida tomada desde más arriba, o un boceto.",
        "de" to "Ich habe auf diesen Bildern keine Tische gefunden. Versuchen Sie ein schärferes Foto von weiter oben oder eine Skizze.",
        "af" to "Ek kon geen tafels in daardie prente vind nie. Probeer ’n duideliker foto van hoër af geneem, of ’n skets.",
    )
    private val FLOOR_OFF_TOPIC = mapOf(
        "en" to "I can only help edit this room's floor plan. Try something like \"add four 2-tops along the window\".",
        "fr" to "Je peux seulement vous aider à modifier le plan de cette salle. Essayez par exemple « ajoute quatre tables de 2 le long de la fenêtre ».",
        "es" to "Solo puedo ayudarte a editar el plano de esta sala. Prueba algo como «añade cuatro mesas de 2 junto a la ventana».",
        "de" to "Ich kann nur beim Bearbeiten des Raumplans helfen. Versuchen Sie zum Beispiel „vier Zweiertische am Fenster hinzufügen“.",
        "af" to "Ek kan net help om hierdie vertrek se vloerplan te wysig. Probeer iets soos “voeg vier tweepersoonstafels langs die venster by”.",
    )
    private val FLOOR_NO_CHANGE = mapOf(
        "en" to "I couldn't find a change to make to this room from that. Name the table or object and what to change, for example \"give table 3 six seats\" or \"add a round table for 4\".",
        "fr" to "Je n'ai trouvé aucun changement à faire dans cette salle. Nommez la table ou l'élément et ce qu'il faut changer, par exemple « mets 6 places à la table 3 » ou « ajoute une table ronde pour 4 ».",
        "es" to "No encontré ningún cambio que hacer en esta sala. Indica la mesa o el elemento y qué cambiar, por ejemplo «pon 6 lugares en la mesa 3» o «añade una mesa redonda para 4».",
        "de" to "Ich habe keine Änderung für diesen Raum gefunden. Nennen Sie den Tisch oder das Element und was sich ändern soll, zum Beispiel „Tisch 3 mit 6 Plätzen“ oder „einen runden Vierertisch hinzufügen“.",
        "af" to "Ek kon nie ’n verandering vir hierdie vertrek daarin vind nie. Noem die tafel of voorwerp en wat moet verander, byvoorbeeld “gee tafel 3 ses sitplekke” of “voeg ’n ronde tafel vir 4 by”.",
    )
    private val FLOOR_TABLE_LOCKED = mapOf(
        "en" to "That table has an open bill, so it stays as it is. Close the bill first, then ask again.",
        "fr" to "Cette table a une addition ouverte, elle reste donc telle quelle. Fermez d'abord l'addition, puis redemandez.",
        "es" to "Esa mesa tiene una cuenta abierta, así que se queda como está. Cierra primero la cuenta y vuelve a pedirlo.",
        "de" to "Dieser Tisch hat eine offene Rechnung und bleibt deshalb, wie er ist. Schließen Sie zuerst die Rechnung und fragen Sie dann noch einmal.",
        "af" to "Daardie tafel het ’n oop rekening, so dit bly soos dit is. Sluit eers die rekening en vra dan weer.",
    )

    enum class R(val code: String) { ROOM_OFF_TOPIC("off_topic"), ROOM_NO_LAYOUT("no_change"), FLOOR_OFF_TOPIC("off_topic"),
        FLOOR_NO_CHANGE("no_change"), FLOOR_TABLE_LOCKED("table_locked"), INCOMPLETE("menu_ai_incomplete"),
        TOO_MANY("menu_ai_too_many_changes") }

    fun reply(r: R, lang: String?): String {
        val l = lang?.take(2)?.lowercase()
        val m = when (r) {
            R.ROOM_OFF_TOPIC -> ROOM_OFF_TOPIC
            R.ROOM_NO_LAYOUT -> ROOM_NO_LAYOUT
            R.FLOOR_OFF_TOPIC -> FLOOR_OFF_TOPIC
            R.FLOOR_NO_CHANGE -> FLOOR_NO_CHANGE
            R.FLOOR_TABLE_LOCKED -> FLOOR_TABLE_LOCKED
            R.INCOMPLETE -> return AiGuard.reply(AiGuard.Refusal.INCOMPLETE, l)
            R.TOO_MANY -> return AiGuard.reply(AiGuard.Refusal.TOO_MANY_CHANGES, l)
        }
        return m[l] ?: m.getValue("en")
    }
}

/**
 * The Rooms page's AI assistant: the store's "Set up from picture" and floor
 * "Ask AI" (server/.../aimenu/MenuAiService.kt roomFromPhotos / floorEdit,
 * prompts and rules PORTED in RoomAiPort.kt) for the portal, on the cloud's
 * copy of the floor. The model only proposes; [applyPhoto] / [apply] write
 * through [RoomState.write] — cloud-stamped, appended to the store's feed, so
 * the tablet gets them on its next sync — and record their undo; [revert]
 * runs it the same way. A table with an open bill at the store is never
 * moved, reshaped, renumbered or removed (the cloud refuses; the store also
 * refuses if the cloud's view was stale).
 *
 * Limits: the menu assistant's (20 calls per 10 minutes per user and per
 * store, [CloudConfig.menuAiDailyCap] per rolling day — counting the menu's
 * calls too); every call, apply and undo is logged in menu_ai_log (kinds
 * room_photo | room_chat | room_voice | room_apply | room_revert; never text,
 * audio, pictures or keys).
 */
class RoomAiService(
    private val config: CloudConfig,
    private val model: RoomAiModel? = config.menuAiKey?.let { GeminiRoomModel(it.value, config.menuAiModel) },
    private val voiceModel: RoomAiModel? = config.menuAiKey?.let { GeminiRoomModel(it.value, config.menuAiVoiceModel ?: config.menuAiModel) },
    /**
     * A new room from 1–4 photos: done once per room, so the strongest model ([CloudConfig.roomPhotoModel],
     * more thinking) — the lite one undercounted tables and seats; null = [model].
     */
    private val photoModel: RoomAiModel? = (model as? GeminiRoomModel)?.let {
        config.menuAiKey?.let { k ->
            GeminiRoomModel(k.value, config.roomPhotoModel, thinkingLevel = config.roomPhotoThinking, budgetMs = 280_000L)
        }
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val callsMax: Int = MenuAiService.CALLS_MAX,
) {
    private val log = LoggerFactory.getLogger(RoomAiService::class.java)
    private val limiter = MenuAiLimiter(callsMax, MenuAiService.CALLS_WINDOW_MS, now)

    val enabled: Boolean get() = model != null

    init { config.menuAiKey?.let { Scrub.register(it.value) } }

    private class PhotoProposal(
        val tenantId: String, val venueId: String, val userId: Long, val name: String,
        val tables: List<RoomTableDto>, val objects: List<RoomObjectDto>, val at: Long,
    )
    private class FloorProposal(
        val tenantId: String, val venueId: String, val userId: Long, val roomId: String,
        val ops: List<FloorOp>, val summary: String, val bulk: Boolean, val at: Long,
    )
    private val photos = ConcurrentHashMap<String, PhotoProposal>()
    private val floors = ConcurrentHashMap<String, FloorProposal>()
    private val applied = ConcurrentHashMap<String, Long>()

    companion object {
        private const val PROPOSAL_TTL_MS = 30 * 60_000L
        private const val MAX_PROPOSALS = 200
        /** The store's AI request kinds that count against the daily cap (the menu's and the rooms'). */
        val CAPPED_KINDS = listOf("chat", "voice", "room_photo", "room_chat", "room_voice")
        private val undoJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        private val SLUG = Regex("[^a-z0-9]+")

        private fun suffix() = UUID.randomUUID().toString().replace("-", "").take(4)

        /** "Patio Bar" → "patio-bar-x7k2": unique ids (the store's form, plus a few random letters). */
        internal fun mint(base: String): String {
            val slug = base.trim().lowercase().replace(SLUG, "-").trim('-').ifBlank { "room" }.take(48).trim('-')
            return "$slug-${suffix()}"
        }

        /** The store's label prefix rule (Zones.kt labelPrefixFor): the EN name's first letter. */
        internal fun prefixFor(name: String): String =
            name.trim().firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "Z"
    }

    // --- the shared plumbing ---

    private fun <T> tracked(who: AiCaller, kind: String, outcome: (T) -> Triple<String, Int, Int>, call: () -> T): T {
        val userKey = "user:${who.principal.tenantId}:${who.principal.userId}"
        val storeKey = "store:${who.principal.tenantId}:${who.venue.venueId}"
        if (overDailyCap(who)) {
            record(who, kind, "daily_limit")
            throw MenuAiException(429, "menu_ai_daily_limit",
                "the AI assistant's daily limit is reached for this store; try again tomorrow", 3600)
        }
        limiter.admit(listOf(userKey, storeKey))?.let { retry ->
            record(who, kind, "rate_limited")
            throw MenuAiException(429, "menu_ai_too_many",
                "too many AI requests: at most $callsMax every ${MenuAiService.CALLS_WINDOW_MS / 60_000} minutes", retry)
        }
        val started = now()
        return try {
            call().also { r -> val (o, c, rj) = outcome(r); record(who, kind, o, c, rj, now() - started) }
        } catch (e: MenuAiException) {
            record(who, kind, e.code, elapsedMs = now() - started); throw e
        }
    }

    private fun overDailyCap(who: AiCaller): Boolean = transaction {
        val since = CloudTime.now().minusHours(24)
        val counted = (MenuAiLog.tenantId eq who.principal.tenantId) and (MenuAiLog.createdAt greater since) and
            (MenuAiLog.kind inList CAPPED_KINDS) and (MenuAiLog.outcome notInList listOf("rate_limited", "daily_limit"))
        val store = MenuAiLog.selectAll().where { counted and (MenuAiLog.venueId eq who.venue.venueId) }.count()
        val user = MenuAiLog.selectAll().where { counted and (MenuAiLog.userId eq who.principal.userId) }.count()
        store >= config.menuAiDailyCap || user >= config.menuAiDailyCap
    }

    private fun record(
        who: AiCaller, kind: String, outcome: String, changes: Int = 0, rejected: Int = 0, elapsedMs: Long = 0, ref: String? = null,
    ) {
        runCatching {
            transaction {
                MenuAiLog.insert {
                    it[tenantId] = who.principal.tenantId
                    it[venueId] = who.venue.venueId
                    it[userId] = who.principal.userId
                    it[MenuAiLog.kind] = kind
                    it[MenuAiLog.outcome] = outcome.take(40)
                    it[MenuAiLog.changes] = changes
                    it[MenuAiLog.rejected] = rejected
                    it[MenuAiLog.elapsedMs] = elapsedMs
                    it[MenuAiLog.ref] = ref?.take(64)
                    it[createdAt] = CloudTime.now()
                }
            }
        }.onFailure { log.warn("AI rooms: could not write the log row (${it.javaClass.simpleName})") }
    }

    private fun sweep() {
        val cutoff = now() - PROPOSAL_TTL_MS
        photos.entries.removeIf { it.value.at < cutoff }
        floors.entries.removeIf { it.value.at < cutoff }
        applied.entries.removeIf { it.value < cutoff }
        if (photos.size > MAX_PROPOSALS) photos.entries.sortedBy { it.value.at }.take(photos.size - MAX_PROPOSALS).forEach { photos.remove(it.key) }
        if (floors.size > MAX_PROPOSALS) floors.entries.sortedBy { it.value.at }.take(floors.size - MAX_PROPOSALS).forEach { floors.remove(it.key) }
    }

    private fun expired(id: String) = NotFoundException(
        if (applied.containsKey(id)) "that was already applied" else "that AI proposal has expired; ask again",
        if (applied.containsKey(id)) "menu_ai_already_applied" else "menu_ai_expired",
    )

    private fun requireModel(voice: Boolean = false): RoomAiModel = (if (voice) voiceModel else model)
        ?: throw MenuAiException(409, "menu_ai_disabled", "the AI assistant is not set up on this portal")

    /** Bilingual = the store's menu carries real French names (the menu assistant's rule). Inside a transaction. */
    private fun bilingual(scope: Scope): Boolean =
        CatalogCategories.selectAll().where {
            (CatalogCategories.tenantId eq scope.tenantId) and (CatalogCategories.venueId eq scope.venueId) and
                (CatalogCategories.deleted eq false)
        }.any { it[CatalogCategories.nameFr].isNotBlank() && it[CatalogCategories.nameFr] != it[CatalogCategories.nameEn] }

    private fun langs(who: AiCaller) = AiLangs(MenuAiService.LANGS,
        MenuAiService.LANGS.joinToString(", ") { "$it (${MenuAiService.LANGUAGE_NAMES[it]})" },
        "${MenuAiService.LANGUAGE_NAMES[who.lang]} (${who.lang})")

    private fun call(m: RoomAiModel, system: String, user: String, audio: AiAudio?, images: List<AiImage>): String? = try {
        m.complete(system, user, audio, images)
    } catch (e: MenuAiException) {
        // the provider's own safety refusal is the fixed reply, not an error
        if (e.code == "menu_ai_refused") null else { log.info("AI rooms via ${m.id}/${m.model} failed: ${e.code}"); throw e }
    } catch (e: RuntimeException) {
        log.warn("AI rooms via ${m.id}/${m.model} failed: ${e.javaClass.simpleName}")
        throw MenuAiException(502, "menu_ai_error", "the AI request failed; try again")
    }

    // --- a new room from a photo ---

    /**
     * 1–4 phone photos of one room from different spots (or a sketch, a
     * printed plan) → ONE model call that merges the views → a validated new
     * room to preview (the store's RoomLayoutRules with the spread, numbers
     * from 1). Nothing changes; the pictures live only for this call.
     */
    fun photo(who: AiCaller, images: List<AiImage>, name: String?): RoomPhotoProposalDto {
        if (images.isEmpty()) throw BadRequestException("take a photo first", "room_ai_no_image")
        if (images.size > RoomPhoto.MAX_PHOTOS) throw BadRequestException("at most ${RoomPhoto.MAX_PHOTOS} photos at a time", "room_ai_too_many_images")
        val roomName = name?.trim()?.take(100)?.takeIf { it.isNotEmpty() } ?: defaultName(who.lang)
        if (AiGuard.checkText(roomName) != null) throw BadRequestException("pick a plain room name", "room_bad_name")
        transaction { RoomState.requireEditable(who.venue.scope) }
        val prefix = prefixFor(roomName)
        return tracked(who, "room_photo", { r -> Triple(r.refusal ?: "proposed", r.tables.size + r.objects.size, r.rejected.size) }) {
            val m = photoModel ?: requireModel()
            val started = now()
            fun refuse(r: RoomReplies.R, rejected: List<String> = emptyList()) = RoomPhotoProposalDto("", who.venue.venueId, m.model,
                roomName, prefix, emptyList(), emptyList(), rejected = AiText.skips(rejected, who.lang), elapsedMs = now() - started,
                refusal = r.code, message = RoomReplies.reply(r, who.lang))
            val bilingual = transaction { bilingual(who.venue.scope) }
            // all the views in ONE call: the model merges them into one room
            val reply = call(m, RoomLayoutAi.systemPrompt(bilingual), RoomLayoutAi.userPrompt(images.size), null, images)
                ?: return@tracked refuse(RoomReplies.R.ROOM_OFF_TOPIC)
            val parsed = try { RoomLayoutAi.parse(reply) } catch (e: MenuAiReplyException) {
                log.info("AI room photo via ${m.id}: unusable reply")
                return@tracked refuse(RoomReplies.R.ROOM_OFF_TOPIC)
            }
            if (parsed.refused) return@tracked refuse(RoomReplies.R.ROOM_OFF_TOPIC)
            // a new, empty room: nothing fixed, numbers from 1
            val plan = RoomLayoutRules.validate(parsed.tables, parsed.objects, emptyList(), emptySet(), prefix, spread = true)
            if (plan.tables.isEmpty() && plan.objects.isEmpty()) return@tracked refuse(RoomReplies.R.ROOM_NO_LAYOUT, plan.rejected)
            sweep()
            val id = UUID.randomUUID().toString()
            photos[id] = PhotoProposal(who.principal.tenantId, who.venue.venueId, who.principal.userId, roomName, plan.tables, plan.objects, now())
            log.info("AI room photo via ${m.id}/${m.model}: ${plan.tables.size} table(s), ${plan.objects.size} object(s), ${plan.rejected.size} rejected")
            RoomPhotoProposalDto(id, who.venue.venueId, m.model, roomName, prefix, plan.tables, plan.objects, parsed.notes,
                AiText.skips(plan.rejected, who.lang), now() - started)
        }
    }

    private fun defaultName(lang: String) = when (lang) {
        "fr" -> "Nouvelle salle"
        "es" -> "Sala nueva"
        "de" -> "Neuer Raum"
        "af" -> "Nuwe vertrek"
        else -> "New room"
    }

    /** Create the proposed room (its tables numbered with the final name's prefix), with its undo. */
    fun applyPhoto(who: AiCaller, req: RoomPhotoApplyRequest): RoomAiApplyResult {
        val p = photos[req.proposalId]
            ?.takeIf { it.tenantId == who.principal.tenantId && it.venueId == who.venue.venueId && it.userId == who.principal.userId }
            ?: throw expired(req.proposalId)
        val name = req.name?.trim()?.take(100)?.takeIf { it.isNotEmpty() } ?: p.name
        if (AiGuard.checkText(name) != null) throw BadRequestException("pick a plain room name", "room_bad_name")
        if (!photos.remove(req.proposalId, p)) throw expired(req.proposalId)
        val applyId = UUID.randomUUID().toString()
        val scope = who.venue.scope
        val result = try {
            transaction {
                RoomState.requireEditable(scope)
                CloudHlc.lock(who.principal.tenantId)
                val things = RoomState.load(scope)
                val stamp = { CloudHlc.now(who.principal.tenantId) }
                val undo = mutableListOf<RoomUndo>()
                val prefix = prefixFor(name)
                val roomId = mint(name)
                val sort = (things.values.filter { it.entity == RoomFields.ROOM && !it.deleted }.maxOfOrNull { it.int("sortOrder") ?: 0 } ?: -1) + 1
                RoomState.write(scope, Thing(RoomFields.ROOM, roomId), mapOf(
                    "nameEn" to JsonPrimitive(name), "nameFr" to JsonPrimitive(name), "sortOrder" to JsonPrimitive(sort),
                    "labelPrefix" to JsonPrimitive(prefix), "deleted" to JsonPrimitive(false)), stamp())
                undo += RoomUndo(RoomFields.ROOM, roomId, mapOf("deleted" to JsonPrimitive(true)))
                for (t in p.tables) {
                    val label = "$prefix-${t.number ?: RoomModel.number(t.label) ?: 1}"
                    val id = mint("$roomId-$label".take(56))
                    RoomState.write(scope, Thing(RoomFields.TABLE, id), tableFields(roomId, label, t), stamp())
                    undo += RoomUndo(RoomFields.TABLE, id, mapOf("deleted" to JsonPrimitive(true)))
                }
                for (o in p.objects) {
                    val id = mint("$roomId-${o.type.lowercase()}".take(56))
                    RoomState.write(scope, Thing(RoomFields.OBJECT, id), objectFields(roomId, o), stamp())
                    undo += RoomUndo(RoomFields.OBJECT, id, mapOf("deleted" to JsonPrimitive(true)))
                }
                val summary = "$name: ${p.tables.size} table(s), ${p.tables.sumOf { it.seats }} seat(s), ${p.objects.size} object(s) from a photo"
                saveApply(who, applyId, roomId, "photo", summary, undo)
                RoomAiApplyResult(applyId, roomId, p.tables.size + p.objects.size, summary)
            }
        } catch (e: Exception) {
            photos[req.proposalId] = p // nothing was saved: a retry may reuse it
            record(who, "room_apply", (e as? ConflictException)?.code ?: "failed", ref = req.proposalId)
            throw e
        }
        applied[req.proposalId] = now()
        record(who, "room_apply", "applied", result.applied, ref = applyId)
        log.info("AI rooms: new room ${result.roomId} at ${who.venue.venueId} as $applyId")
        return result
    }

    private fun tableFields(zoneId: String, label: String, t: RoomTableDto): Map<String, JsonElement> = mapOf(
        "zoneId" to JsonPrimitive(zoneId), "label" to JsonPrimitive(label), "parentTableId" to JsonNull,
        "x" to JsonPrimitive(t.x), "y" to JsonPrimitive(t.y), "width" to JsonPrimitive(t.width), "height" to JsonPrimitive(t.height),
        "rotation" to JsonPrimitive(t.rotation), "shape" to JsonPrimitive(t.shape), "seats" to JsonPrimitive(t.seats),
        "deleted" to JsonPrimitive(false),
    )

    private fun objectFields(zoneId: String, o: RoomObjectDto): Map<String, JsonElement> = mapOf(
        "zoneId" to JsonPrimitive(zoneId), "type" to JsonPrimitive(o.type),
        "x" to JsonPrimitive(o.x), "y" to JsonPrimitive(o.y), "width" to JsonPrimitive(o.width), "height" to JsonPrimitive(o.height),
        "rotation" to JsonPrimitive(o.rotation),
        "labelFr" to (o.labelFr?.let(::JsonPrimitive) ?: JsonNull), "labelEn" to (o.labelEn?.let(::JsonPrimitive) ?: JsonNull),
        "icon" to (o.icon?.let(::JsonPrimitive) ?: JsonNull), "shape" to (o.shape?.let(::JsonPrimitive) ?: JsonNull),
        "deleted" to JsonPrimitive(false),
    )

    private fun saveApply(who: AiCaller, applyId: String, roomId: String, kind: String, summary: String, undo: List<RoomUndo>) {
        RoomAiApplies.insert {
            it[id] = applyId
            it[tenantId] = who.principal.tenantId
            it[venueId] = who.venue.venueId
            it[userId] = who.principal.userId
            it[RoomAiApplies.roomId] = roomId
            it[RoomAiApplies.kind] = kind
            it[RoomAiApplies.summary] = summary.take(200)
            it[changes] = undo.size
            it[RoomAiApplies.undo] = undoJson.encodeToString(ListSerializer(RoomUndo.serializer()), undo)
            it[createdAt] = CloudTime.now()
        }
    }

    // --- the floor assistant: edit a room by text or voice ---

    private fun model(scope: Scope, roomId: String): RoomModel =
        RoomState.model(RoomState.load(scope), roomId) ?: throw NotFoundException("no such room at this store", "room_not_found")

    fun chat(who: AiCaller, roomId: String, text: String): FloorEditProposalDto {
        val t = text.trim()
        if (t.isEmpty()) throw BadRequestException("type what to change", "menu_ai_empty")
        if (t.length > 1000) throw BadRequestException("that request is too long", "menu_ai_too_long")
        return floorEdit(who, roomId, t, null)
    }

    fun voice(who: AiCaller, roomId: String, audio: AiAudio): FloorEditProposalDto = floorEdit(who, roomId, null, audio)

    /** The store's floorEdit: a request → ops on the room NOW, checked ([FloorEditAi.plan]), previewed. */
    private fun floorEdit(who: AiCaller, roomId: String, text: String?, audio: AiAudio?): FloorEditProposalDto {
        val scope = who.venue.scope
        val room0 = transaction { RoomState.requireEditable(scope); model(scope, roomId) }
        return tracked(who, if (audio != null) "room_voice" else "room_chat",
            { r -> Triple(r.refusal ?: "proposed", r.changes.size, r.rejected.size) }) {
            val m = requireModel(voice = audio != null)
            val started = now()
            var lang = who.lang
            fun refuse(r: RoomReplies.R, heard: String? = null, rejected: List<String> = emptyList(), plan: FloorEditAi.Plan? = null) =
                FloorEditProposalDto("", who.venue.venueId, roomId, m.model, transcript = heard, rejected = AiText.skips(rejected, lang),
                    existingTables = room0.tables.size, protectedTables = plan?.protectedTables.orEmpty(), elapsedMs = now() - started,
                    refusal = r.code, message = if (r == RoomReplies.R.FLOOR_TABLE_LOCKED && !plan?.lockedAsked.isNullOrEmpty())
                        AiText.locked(plan!!.lockedAsked, lang) else RoomReplies.reply(r, lang))
            // plainly not a floor-plan request: the fixed reply, and the model is never asked
            if (text != null && (AiGuard.offTopic(text) || AiGuard.hatefulRequest(text))) return@tracked refuse(RoomReplies.R.FLOOR_OFF_TOPIC)
            val bilingual = transaction { bilingual(scope) }
            val request = if (audio != null) AiVoice.REQUEST else AiGuard.quote(text!!, 1000)
            val reply = call(m, FloorEditAi.systemPrompt(bilingual, voice = audio != null, langs(who)),
                "<current_room>\n${FloorEditAi.context(room0)}\n</current_room>\n\n<manager_request>\n$request\n</manager_request>",
                audio, emptyList()) ?: return@tracked refuse(RoomReplies.R.FLOOR_OFF_TOPIC)
            val heard = if (audio != null) AiVoice.heard(reply) else null
            lang = AiVoice.language(reply, MenuAiService.LANGS) ?: who.lang
            if (audio != null && heard.isNullOrBlank()) return@tracked refuse(RoomReplies.R.FLOOR_NO_CHANGE)
            if (heard != null && !AiVoice.safe(heard)) return@tracked refuse(RoomReplies.R.FLOOR_OFF_TOPIC)
            val parsed = try { FloorEditAi.parse(reply) } catch (e: MenuAiReplyException) {
                log.info("AI floor edit via ${m.id}: unusable reply")
                return@tracked refuse(if (e.tooMany) RoomReplies.R.TOO_MANY else RoomReplies.R.INCOMPLETE, heard)
            }
            if (parsed.refused) return@tracked refuse(RoomReplies.R.FLOOR_OFF_TOPIC, heard)
            // the request's own words (or what was heard) too: a table with an open bill the model left out still gets its line
            val plan = FloorEditAi.plan(room0, parsed.ops, asked = heard ?: text)
            // nothing left to do: only a table with an open bill was asked for → say so, not "no change found"
            if (plan.changes.isEmpty()) return@tracked refuse(
                if (plan.lockedAsked.isNotEmpty()) RoomReplies.R.FLOOR_TABLE_LOCKED else RoomReplies.R.FLOOR_NO_CHANGE,
                heard, parsed.rejected + plan.rejected, plan)
            sweep()
            val id = UUID.randomUUID().toString()
            val removals = plan.removedTables.size + plan.removedObjects.size
            val bulk = removals > FloorEditAi.CONFIRM_REMOVES
            floors[id] = FloorProposal(who.principal.tenantId, who.venue.venueId, who.principal.userId, roomId, parsed.ops,
                parsed.summary, bulk, now())
            log.info("AI floor edit via ${m.id}/${m.model}: ${plan.changes.size} change(s), ${plan.rejected.size} rejected")
            FloorEditProposalDto(id, who.venue.venueId, roomId, m.model, parsed.summary, heard, plan.changes, plan.tables, plan.objects,
                plan.removedTables, plan.removedObjects, AiText.skips(parsed.rejected + plan.rejected, lang), plan.existingTables,
                plan.protectedTables, now() - started, bulk = bulk)
        }
    }

    /** Apply a floor proposal: the plan again against the room NOW (a bill opened since is respected), with its undo. */
    fun apply(who: AiCaller, req: FloorApplyRequest): RoomAiApplyResult {
        val p = floors[req.proposalId]
            ?.takeIf { it.tenantId == who.principal.tenantId && it.venueId == who.venue.venueId && it.userId == who.principal.userId }
            ?: throw expired(req.proposalId)
        // many removals: one more explicit yes (not claimed yet, so the confirmed resend finds it)
        if (p.bulk && !req.confirmed) throw ConflictException(
            "more than ${FloorEditAi.CONFIRM_REMOVES} removals: confirm to apply", "menu_ai_confirm_required")
        if (!floors.remove(req.proposalId, p)) throw expired(req.proposalId)
        val applyId = UUID.randomUUID().toString()
        val scope = who.venue.scope
        val result = try {
            transaction {
                RoomState.requireEditable(scope)
                CloudHlc.lock(who.principal.tenantId)
                val things = RoomState.load(scope)
                val room = RoomState.model(things, p.roomId) ?: throw NotFoundException("no such room at this store", "room_not_found")
                val plan = FloorEditAi.plan(room, p.ops)
                if (plan.changes.isEmpty()) throw ConflictException("nothing to apply: the room already looks like that", "room_no_change")
                val undo = mutableListOf<RoomUndo>()
                val stamp = { CloudHlc.now(who.principal.tenantId) }
                fun thing(entity: String, id: String) = things[entity to id] ?: throw NotFoundException("that is gone from the room", "not_found")
                for (id in plan.removedTables) {
                    RoomState.write(scope, thing(RoomFields.TABLE, id), mapOf("deleted" to JsonPrimitive(true)), stamp())
                    undo += RoomUndo(RoomFields.TABLE, id, mapOf("deleted" to JsonPrimitive(false)))
                }
                for (id in plan.removedObjects) {
                    RoomState.write(scope, thing(RoomFields.OBJECT, id), mapOf("deleted" to JsonPrimitive(true)), stamp())
                    undo += RoomUndo(RoomFields.OBJECT, id, mapOf("deleted" to JsonPrimitive(false)))
                }
                for (t in plan.updatedTables) {
                    val th = thing(RoomFields.TABLE, t.id)
                    val want = mapOf<String, JsonElement>("x" to JsonPrimitive(t.x), "y" to JsonPrimitive(t.y),
                        "width" to JsonPrimitive(t.width), "height" to JsonPrimitive(t.height), "rotation" to JsonPrimitive(t.rotation),
                        "shape" to JsonPrimitive(t.shape), "seats" to JsonPrimitive(t.seats), "label" to JsonPrimitive(t.label))
                        .filter { (f, v) -> RoomFields.canon(th.fields[f]) != RoomFields.canon(v) }
                    if (want.isEmpty()) continue
                    val before = want.keys.associateWith { th.fields[it] ?: JsonNull }
                    RoomState.write(scope, th, want, stamp())
                    undo += RoomUndo(RoomFields.TABLE, t.id, before)
                }
                for (o in plan.updatedObjects) {
                    val th = thing(RoomFields.OBJECT, o.id)
                    val want = mapOf<String, JsonElement>("x" to JsonPrimitive(o.x), "y" to JsonPrimitive(o.y),
                        "width" to JsonPrimitive(o.width), "height" to JsonPrimitive(o.height), "rotation" to JsonPrimitive(o.rotation))
                        .filter { (f, v) -> RoomFields.canon(th.fields[f]) != RoomFields.canon(v) }
                    if (want.isEmpty()) continue
                    val before = want.keys.associateWith { th.fields[it] ?: JsonNull }
                    RoomState.write(scope, th, want, stamp())
                    undo += RoomUndo(RoomFields.OBJECT, o.id, before)
                }
                for (t in plan.addedTables) {
                    val id = mint("${p.roomId}-${t.label}".take(56))
                    RoomState.write(scope, Thing(RoomFields.TABLE, id), tableFields(p.roomId, t.label, t), stamp())
                    undo += RoomUndo(RoomFields.TABLE, id, mapOf("deleted" to JsonPrimitive(true)))
                }
                for (o in plan.addedObjects) {
                    val id = mint("${p.roomId}-${o.type.lowercase()}".take(56))
                    RoomState.write(scope, Thing(RoomFields.OBJECT, id), objectFields(p.roomId, o), stamp())
                    undo += RoomUndo(RoomFields.OBJECT, id, mapOf("deleted" to JsonPrimitive(true)))
                }
                val summary = "${plan.roomName}: " + p.summary.ifBlank { "${plan.changes.size} change(s)" }.take(150) + " (AI assistant)"
                saveApply(who, applyId, p.roomId, "floor", summary, undo)
                RoomAiApplyResult(applyId, p.roomId, plan.changes.size, summary)
            }
        } catch (e: Exception) {
            floors[req.proposalId] = p
            record(who, "room_apply", (e as? ConflictException)?.code ?: (e as? NotFoundException)?.code ?: "failed", ref = req.proposalId)
            throw e
        }
        applied[req.proposalId] = now()
        record(who, "room_apply", "applied", result.applied, ref = applyId)
        log.info("AI floor edit: applied ${result.applied} change(s) to ${p.roomId} at ${who.venue.venueId} as $applyId")
        return result
    }

    /**
     * Put back everything [applyId] changed, newest first, with fresh stamps.
     * A step that no longer applies is skipped: a table that has an open bill
     * now, a room that still holds tables the undo could not remove.
     */
    fun revert(who: AiCaller, applyId: String): RoomAiRevertResult {
        val scope = who.venue.scope
        val (ok, skipped) = transaction {
            val row = RoomAiApplies.selectAll().where {
                (RoomAiApplies.id eq applyId) and (RoomAiApplies.tenantId eq who.principal.tenantId) and
                    (RoomAiApplies.venueId eq who.venue.venueId)
            }.forUpdate().firstOrNull() ?: throw NotFoundException("no such AI change", "menu_ai_apply_not_found")
            if (row[RoomAiApplies.revertedAt] != null) throw ConflictException("that was already undone", "menu_ai_already_reverted")
            RoomState.requireEditable(scope)
            CloudHlc.lock(who.principal.tenantId)
            val steps = undoJson.decodeFromString(ListSerializer(RoomUndo.serializer()), row[RoomAiApplies.undo])
            var things = RoomState.load(scope)
            var ok = 0
            var skip = 0
            for (s in steps.asReversed()) {
                val th = things[s.entity to s.id]
                val deletingRoom = s.entity == RoomFields.ROOM && s.fields["deleted"] == JsonPrimitive(true)
                val holds = deletingRoom && things.values.any {
                    it.entity != RoomFields.ROOM && !it.deleted && it.str("zoneId") == s.id
                }
                val blocked = th == null || holds ||
                    (th.entity == RoomFields.TABLE && th.locked && (s.fields.keys - RoomFields.LOCKED_TABLE_EDITABLE).isNotEmpty())
                if (blocked) { skip++; continue }
                RoomState.write(scope, th!!, s.fields, CloudHlc.now(who.principal.tenantId))
                ok++
            }
            RoomAiApplies.update({ (RoomAiApplies.id eq applyId) and (RoomAiApplies.tenantId eq who.principal.tenantId) }) {
                it[revertedAt] = CloudTime.now()
                it[revertedBy] = who.principal.userId
            }
            ok to skip
        }
        record(who, "room_revert", "reverted", ok, skipped, ref = applyId)
        log.info("AI rooms: reverted $applyId ($ok step(s), $skipped skipped)")
        return RoomAiRevertResult(applyId, ok, skipped)
    }
}
