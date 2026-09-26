package dev.dwhipstock.pos.aiphotos

import dev.dwhipstock.pos.sdk.ImageGenConfig
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Where an item's photo came from (items.photo_source; shown as an "AI" badge). */
enum class PhotoSource(val wire: String) {
    ORIGINAL("original"), AI_GENERATED("ai_generated"), AI_ENHANCED("ai_enhanced");

    val isAi: Boolean get() = this != ORIGINAL

    companion object {
        fun parse(raw: String?): PhotoSource = entries.firstOrNull { it.wire == raw } ?: ORIGINAL
    }
}

/** GET /ai-photos/status. Never carries the key. */
@Serializable
data class AiPhotoStatus(
    /** The add-on is switched on for this client (else the editor shows no AI buttons). */
    val configured: Boolean,
    /** Ready to use right now: configured, a provider + key, and online. */
    val available: Boolean,
    val provider: String,
    val model: String? = null,
    val online: Boolean = false,
    /** Why it is not available: image_generation_off | image_provider_off | image_key_missing | image_offline. */
    val reason: String? = null,
    val defaultCount: Int = AiPhotoService.DEFAULT_COUNT,
    val estimatedCostPerImageUsd: Double? = null,
)

@Serializable
data class AiPhotoCandidateDto(val id: String, val contentType: String, val dataBase64: String)

@Serializable
data class AiPhotoCandidates(
    val itemId: String,
    val provider: String,
    val model: String,
    /** ai_generated | ai_enhanced — what choosing one records. */
    val source: String,
    val elapsedMs: Long,
    val estimatedCostUsd: Double,
    val candidates: List<AiPhotoCandidateDto>,
)

/**
 * AI menu photos for the item editor: "Generate photo" (text to image in the
 * client's house style) and "Snap and enhance" (improve a real photo of the
 * dish). Candidates are kept in memory for a short while so the manager can
 * pick one; the chosen one goes through the ordinary photo pipeline.
 *
 * Online-only by design and never on a selling path: with the feature off, no
 * key, or no internet, every call answers a coded error and nothing else in
 * the store changes. Nothing here runs at startup except building the client.
 */
class AiPhotoService(
    val config: ImageGenConfig.Resolved,
    brand: String,
    private val provider: ImageProvider? = ImageProviders.from(config),
    /** "Can we reach [host]:443?" — a short TCP connect, cached. Test seam. */
    private val reachable: (String) -> Boolean = ::tcpReachable,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(AiPhotoService::class.java)
    val style: HouseStyle = HouseStyle.forBrand(brand, config.style)

    private class Candidate(val itemId: String, val batch: String, val image: GeneratedImage, val source: PhotoSource, val at: Long)
    private val candidates = ConcurrentHashMap<String, Candidate>()

    @Volatile private var probe: Pair<Boolean, Long>? = null

    companion object {
        const val DEFAULT_COUNT = 3
        const val MIN_COUNT = 2
        const val MAX_COUNT = 4
        private const val CANDIDATE_TTL_MS = 30 * 60 * 1000L
        private const val MAX_CANDIDATES = 24
        private const val PROBE_TTL_MS = 20_000L
        const val OFFLINE = "image_offline"

        fun tcpReachable(host: String): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress(host, 443), 1_500) }
            true
        }.getOrDefault(false)
    }

    init { Scrub.register(config.apiKey) }

    val enabled: Boolean get() = config.enabled && provider != null

    fun start() {
        config.warning?.let { log.warn("AI photos config ignored: $it") }
        log.info(config.describe())
    }

    private fun online(): Boolean {
        val p = provider ?: return false
        probe?.takeIf { now() - it.second < PROBE_TTL_MS }?.let { return it.first }
        val ok = runCatching { reachable(p.host) }.getOrDefault(false)
        probe = ok to now()
        return ok
    }

    private fun markOffline() { probe = false to now() }

    fun status(): AiPhotoStatus {
        val configured = config.generation
        if (!enabled) return AiPhotoStatus(configured, false, config.provider.wire,
            reason = config.disabled?.code ?: ImageGenConfig.Disabled.KEY_MISSING.code)
        val online = online()
        return AiPhotoStatus(
            configured = true, available = online, provider = provider!!.id, model = provider.model,
            online = online, reason = if (online) null else OFFLINE,
            estimatedCostPerImageUsd = provider.costPerImageUsd(edit = false),
        )
    }

    private fun requireProvider(): ImageProvider = provider?.takeIf { config.enabled }
        ?: throw ImageGenException(409, ImageGenException.DISABLED,
            "AI photos are off on this store (${config.disabled?.code ?: "image_provider_off"})")

    private fun clampCount(count: Int?) = (count ?: DEFAULT_COUNT).coerceIn(MIN_COUNT, MAX_COUNT)

    fun generate(itemId: String, item: ItemFacts, count: Int? = null): AiPhotoCandidates {
        val p = requireProvider()
        val n = clampCount(count)
        return run(itemId, p, PhotoSource.AI_GENERATED, n) { p.generate(PhotoPrompts.generate(item, style), n) }
    }

    fun enhance(itemId: String, item: ItemFacts, photo: ByteArray, contentType: String, count: Int? = null): AiPhotoCandidates {
        val p = requireProvider()
        val n = clampCount(count)
        return run(itemId, p, PhotoSource.AI_ENHANCED, n) {
            p.enhance(photo, contentType, PhotoPrompts.enhance(item, style), n)
        }
    }

    private fun run(
        itemId: String, p: ImageProvider, source: PhotoSource, n: Int, call: () -> List<GeneratedImage>,
    ): AiPhotoCandidates {
        val started = now()
        val images = try {
            call()
        } catch (e: ImageGenException) {
            if (e.code == ImageGenException.UNAVAILABLE) markOffline()
            log.info("AI photo ${source.wire} for $itemId via ${p.id} failed: ${e.code} ${e.message}")
            throw e
        }
        val elapsed = now() - started
        sweep()
        val batch = UUID.randomUUID().toString()
        val dtos = images.take(n).map { img ->
            val id = UUID.randomUUID().toString()
            candidates[id] = Candidate(itemId, batch, img, source, now())
            AiPhotoCandidateDto(id, img.contentType, Base64.getEncoder().encodeToString(img.bytes))
        }
        log.info("AI photo ${source.wire} for $itemId via ${p.id}/${p.model}: ${dtos.size} candidate(s) in ${elapsed}ms")
        return AiPhotoCandidates(itemId, p.id, p.model, source.wire, elapsed,
            estimatedCostUsd = p.costPerImageUsd(source == PhotoSource.AI_ENHANCED) * n, candidates = dtos)
    }

    /** The picked candidate for [itemId] (removes it), or null if unknown / expired / another item's. */
    fun take(itemId: String, candidateId: String): Pair<GeneratedImage, PhotoSource>? {
        sweep()
        val c = candidates[candidateId]?.takeIf { it.itemId == itemId } ?: return null
        candidates.remove(candidateId)
        // the other candidates of this item are not needed any more
        candidates.entries.removeIf { it.value.batch == c.batch }
        return c.image to c.source
    }

    private fun sweep() {
        val cutoff = now() - CANDIDATE_TTL_MS
        candidates.entries.removeIf { it.value.at < cutoff }
        if (candidates.size > MAX_CANDIDATES) {
            candidates.entries.sortedBy { it.value.at }.take(candidates.size - MAX_CANDIDATES)
                .forEach { candidates.remove(it.key) }
        }
    }

    override fun toString() = "AiPhotoService(${config.describe()})"
}
