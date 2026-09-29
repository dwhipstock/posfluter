package dev.dwhipstock.pos.aimenu

import dev.dwhipstock.pos.aiphotos.ImageGenException
import dev.dwhipstock.pos.aiphotos.Scrub
import dev.dwhipstock.pos.db.utcTimestamp
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * The AI safety rails shared by every AI entry point (menu chat, menu from
 * photos, translate, room object from photo, AI photo prompts). Whatever the
 * model or the text in a photo says, these hold on the server:
 *
 * - off-topic / injection requests get one fixed, friendly reply ([Refusal]),
 *   never the model's own words;
 * - names and descriptions carry no code, HTML, URLs, control characters,
 *   emoji spam or blocklisted words ([checkText]);
 * - every call is rate limited per device and per manager ([RateLimiter]) and
 *   logged without keys, photos or prompts ([AiRequestLog]).
 */
object AiGuard {
    /** Why the assistant answered with the fixed reply instead of a change set. */
    enum class Refusal(val code: String) {
        /** Not about the menu: code, jokes, "your instructions", role play, "ignore previous...". */
        OFF_TOPIC("off_topic"),
        /** About the menu, but the model found nothing it could safely change. */
        NO_CHANGE("no_change"),
        /** "Set up from picture": the pictures are not a room or a floor plan (or ask something else). */
        ROOM_OFF_TOPIC("off_topic"),
        /** "Set up from picture": no table or object could be read. */
        ROOM_NO_LAYOUT("no_change"),
        /** The floor assistant: not about this room's floor plan. */
        FLOOR_OFF_TOPIC("off_topic"),
        /** The floor assistant: nothing it could safely change (or no speech heard). */
        FLOOR_NO_CHANGE("no_change"),
        /** The reply was cut off or wasn't usable JSON — a technical hiccup, not an off-topic
         *  request: worth trying again as-is. */
        INCOMPLETE("menu_ai_incomplete"),
        /** The model proposed more changes at once than the store accepts. */
        TOO_MANY_CHANGES("menu_ai_too_many_changes"),
    }

    private val REPLIES = mapOf(
        Refusal.OFF_TOPIC to mapOf(
            "en" to "I can only help set up and edit your menu. Try something like \"add a Caesar salad for 14 under Salads\".",
            "fr" to "Je peux seulement vous aider à configurer et modifier votre menu. Essayez par exemple « ajoute une salade César à 14 dans Salades ».",
            "es" to "Solo puedo ayudarte a configurar y editar tu menú. Prueba algo como «añade una ensalada César a 14 en Ensaladas».",
            "de" to "Ich kann nur beim Einrichten und Bearbeiten Ihrer Speisekarte helfen. Versuchen Sie zum Beispiel „Caesar Salad für 14 unter Salate hinzufügen“.",
        ),
        Refusal.NO_CHANGE to mapOf(
            "en" to "I couldn't find a menu change to make from that. Name the item and what to change, for example \"poutine 14\".",
            "fr" to "Je n'ai trouvé aucun changement de menu à faire. Nommez le produit et ce qu'il faut changer, par exemple « poutine 14 ».",
            "es" to "No encontré ningún cambio de menú que hacer. Indica el producto y qué cambiar, por ejemplo «poutine 14».",
            "de" to "Ich habe keine Änderung an der Speisekarte gefunden. Nennen Sie den Artikel und was sich ändern soll, zum Beispiel „Poutine 14“.",
        ),
        Refusal.ROOM_OFF_TOPIC to mapOf(
            "en" to "I can only set up a floor plan from pictures of a room, a sketch or a printed plan.",
            "fr" to "Je peux seulement créer un plan de salle à partir de photos d'une salle, d'un croquis ou d'un plan imprimé.",
            "es" to "Solo puedo crear un plano a partir de fotos de una sala, un boceto o un plano impreso.",
            "de" to "Ich kann nur aus Fotos eines Raums, einer Skizze oder eines gedruckten Plans einen Raumplan erstellen.",
        ),
        Refusal.ROOM_NO_LAYOUT to mapOf(
            "en" to "I couldn't find any tables in those pictures. Try a clearer photo taken from higher up, or a sketch.",
            "fr" to "Je n'ai trouvé aucune table sur ces images. Essayez une photo plus nette prise de plus haut, ou un croquis.",
            "es" to "No encontré ninguna mesa en esas imágenes. Prueba una foto más nítida tomada desde más arriba, o un boceto.",
            "de" to "Ich habe auf diesen Bildern keine Tische gefunden. Versuchen Sie ein schärferes Foto von weiter oben oder eine Skizze.",
        ),
        Refusal.FLOOR_OFF_TOPIC to mapOf(
            "en" to "I can only help edit this room's floor plan. Try something like \"add four 2-tops along the window\".",
            "fr" to "Je peux seulement vous aider à modifier le plan de cette salle. Essayez par exemple « ajoute quatre tables de 2 le long de la fenêtre ».",
            "es" to "Solo puedo ayudarte a editar el plano de esta sala. Prueba algo como «añade cuatro mesas de 2 junto a la ventana».",
            "de" to "Ich kann nur beim Bearbeiten des Raumplans helfen. Versuchen Sie zum Beispiel „vier Zweiertische am Fenster hinzufügen“.",
        ),
        Refusal.FLOOR_NO_CHANGE to mapOf(
            "en" to "I couldn't find a change to make to this room from that. Name the table or object and what to change, for example \"make table 5 round\".",
            "fr" to "Je n'ai trouvé aucun changement à faire dans cette salle. Nommez la table ou l'élément et ce qu'il faut changer, par exemple « rends la table 5 ronde ».",
            "es" to "No encontré ningún cambio que hacer en esta sala. Indica la mesa o el elemento y qué cambiar, por ejemplo «haz redonda la mesa 5».",
            "de" to "Ich habe keine Änderung für diesen Raum gefunden. Nennen Sie den Tisch oder das Element und was sich ändern soll, zum Beispiel „Tisch 5 rund machen“.",
        ),
        Refusal.INCOMPLETE to mapOf(
            "en" to "The AI's answer was cut off. Please try again.",
            "fr" to "La réponse de l'IA a été coupée. Veuillez réessayer.",
            "es" to "La respuesta de la IA se cortó. Vuelve a intentarlo.",
            "de" to "Die Antwort der KI wurde abgeschnitten. Bitte versuchen Sie es erneut.",
        ),
        Refusal.TOO_MANY_CHANGES to mapOf(
            "en" to "That's too many changes at once. Try asking for fewer things, or in smaller batches.",
            "fr" to "C'est trop de changements à la fois. Essayez de demander moins de choses, ou en plus petits lots.",
            "es" to "Son demasiados cambios a la vez. Pide menos cosas, o en tandas más pequeñas.",
            "de" to "Das sind zu viele Änderungen auf einmal. Fragen Sie nach weniger Dingen oder in kleineren Schritten.",
        ),
    )

    fun reply(r: Refusal, lang: String?): String = REPLIES.getValue(r)[lang?.take(2)?.lowercase()] ?: REPLIES.getValue(r).getValue("en")

    // --- the manager's request, before any model call ---

    private val OFF_TOPIC = listOf(
        // injection / prompt extraction
        """\b(ignore|forget|disregard|override|bypass)\b.{0,40}\b(previous|prior|above|earlier|all|your|the)\b.{0,20}\b(instruction|rule|prompt|direction)""",
        """\b(system|hidden|initial|original)\s+(prompt|instruction|message)""",
        """\b(your|the)\s+(instructions|prompt)\b|\bwhat are your rules\b""",
        """\bapi[\s_-]?key\b""",
        """\b(jailbreak|developer mode|DAN mode)\b""",
        """\b(pretend|act as|role[\s-]?play|you are now|from now on you)\b""",
        // code
        """\b(fibonacci|bubble\s*sort|quick\s*sort|sorting algorithm|linked list|binary tree|regex|sql query)\b""",
        """\b(write|give me|show me)\b.{0,30}\b(code|program|script|function|algorithm|python|javascript|c\+\+)\b""",
        """```|<\s*script|\bdef\s+\w+\s*\(|\bfunction\s+\w+\s*\(|console\.log|System\.out""",
        // chit-chat
        """\b(tell|say)\s+(me\s+)?(a\s+)?(joke|poem|story|riddle)\b""",
        """\b(write|compose)\s+(me\s+)?(a\s+)?(poem|song|story|essay|haiku)\b""",
        """\bwho (are|made|built|created) you\b|\bwhat (model|llm) are you\b""",
        // same in French / Spanish / German (the store's other languages)
        """\b(ignore|oublie|oubliez|olvida|ignora|vergiss|ignoriere)\b.{0,40}\b(instructions?|consignes?|instrucciones|anweisungen|regeln)\b""",
        """\b(blague|chiste|witz|poème|poema|gedicht)\b""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** True when [text] is plainly not a menu request (answered with the fixed reply, no model call). */
    fun offTopic(text: String): Boolean {
        val t = fold(text)
        return OFF_TOPIC.any { it.containsMatchIn(t) || it.containsMatchIn(text) }
    }

    /**
     * Untrusted text going into a prompt (the manager's words, a note, menu
     * names): control characters out and the prompt's own delimiter tags
     * neutralised, so it cannot close its <data> block and pose as instructions.
     */
    fun quote(text: String, max: Int): String =
        text.take(max).replace(CONTROL, " ").replace("<", "‹").replace(">", "›")

    // --- what the model proposes ---

    private val CONTROL = Regex("""[\p{Cc}\p{Cf}&&[^\n\t]]""")
    private val URL = Regex("""(?i)\b(https?://|ftp://|www\.|mailto:|javascript:|data:)|\b[\w-]+\.(com|net|org|io|ru|xyz|ly|gg|app|dev)\b""")
    private val HTML = Regex("""<\s*/?\s*[a-zA-Z!][^>]*>|&(lt|gt|amp|#\d+);""")
    private val CODE = Regex(
        """```|\{\s*"|=>|\$\{|\(\)\s*\{|&&|\|\||\b(function|def)\s+\w+\s*\(|\b(var|let|const)\s+\w+\s*=|""" +
            """\bSELECT\b.+\bFROM\b|(?i:\bdrop\s+table\b|\bdelete\s+from\b)|\bprint\s*\(|console\.""")
    private val EMOJI = Regex("""[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{1F900}-\x{1F9FF}]""")

    /** Small per-language blocklist for names (folded: lowercase, no accents); whole words only. */
    private val BLOCKED = setOf(
        // en
        "fuck", "fucking", "shit", "cunt", "bitch", "nigger", "nigga", "faggot", "retard", "whore", "slut", "nazi",
        // fr
        "merde", "putain", "salope", "connard", "encule", "pute", "nique", "tabarnak", "calisse", "osti",
        // es
        "mierda", "puta", "cabron", "pendejo", "gilipollas", "maricon", "joder",
        // de
        "scheisse", "scheiss", "fotze", "arschloch", "hurensohn", "wichser", "schlampe", "nutte",
    )

    /** Phrases of the menu system prompt: a name or summary quoting them is the model leaking its instructions. */
    private val PROMPT_MARKERS = listOf(
        "you maintain the menu", "reply with one json object", "each op is one of", "these instructions",
        "system prompt", "untrusted data", "never reveal", "you draw the floor plan", "you edit the floor plan",
    )

    /**
     * Why [text] (a name, a size label, a description, the summary) may not
     * reach the menu or the screen; null = fine. Emoji are allowed up to 2.
     */
    fun checkText(text: String): String? {
        if (CONTROL.containsMatchIn(text)) return "control characters"
        if (HTML.containsMatchIn(text)) return "HTML is not allowed"
        if (URL.containsMatchIn(text)) return "links are not allowed"
        if (CODE.containsMatchIn(text)) return "code is not allowed"
        if (EMOJI.findAll(text).count() > 2) return "too many emoji"
        val folded = fold(text)
        if (folded.split(Regex("[^a-z0-9]+")).any { it in BLOCKED }) return "blocked word"
        if (PROMPT_MARKERS.any { folded.contains(it) }) return "not a menu text"
        if (Scrub.clean(text) != text) return "not a menu text"
        return null
    }

    /** "Montréal!" → "montreal!" (lowercase, accents off). */
    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}

/** Who made an AI call: the tablet session's user and device, and the manager whose PIN approved it. */
data class AiCaller(val userId: String, val approverId: String, val deviceId: String? = null, val lang: String? = null)

/**
 * Sliding-window limit per key (device, manager): [max] AI calls per
 * [windowMs]. A call over the limit is a 429 menu_ai_too_many with Retry-After.
 */
class RateLimiter(
    private val max: Int = 20,
    private val windowMs: Long = 10 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Counts one call for every key, or throws (counting nothing) when any key is over. */
    fun admit(keys: List<String>) {
        val t = now()
        synchronized(this) {
            val queues = keys.distinct().map { k -> hits.getOrPut(k) { ArrayDeque() }.also { q ->
                while (q.isNotEmpty() && t - q.peekFirst() >= windowMs) q.pollFirst()
            } }
            queues.firstOrNull { it.size >= max }?.let { q ->
                val retry = ((windowMs - (t - q.peekFirst())) / 1000).coerceAtLeast(1)
                throw ImageGenException(429, "menu_ai_too_many",
                    "too many AI requests: at most $max every ${windowMs / 60_000} minutes", retry)
            }
            queues.forEach { it.addLast(t) }
        }
    }
}

object AiRequests : Table("ai_requests") {
    val id = integer("id").autoIncrement()
    val createdAt = utcTimestamp("created_at")
    val userId = varchar("user_id", 64)
    val approverId = varchar("approver_id", 64)
    val deviceId = varchar("device_id", 64).nullable()
    val kind = varchar("kind", 16)
    val outcome = varchar("outcome", 40)
    val changes = integer("changes")
    val rejected = integer("rejected")
    val elapsedMs = long("elapsed_ms")
    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class AiRequestDto(
    val at: String, val userId: String, val approverId: String, val deviceId: String? = null,
    /** chat | photos | translate | room_object | room_layout */
    val kind: String,
    /** proposed | off_topic | no_change | rate_limited | menu_ai_<error> */
    val outcome: String,
    val changes: Int, val rejected: Int, val elapsedMs: Long,
)

/** The manager's log of AI calls: who, when, what kind, the outcome. Never keys, photos or prompts. */
object AiRequestLog {
    fun record(who: AiCaller?, kind: String, outcome: String, changes: Int = 0, rejected: Int = 0, elapsedMs: Long = 0) {
        if (who == null) return
        runCatching {
            transaction {
                AiRequests.insert {
                    it[createdAt] = VenueClock.now()
                    it[userId] = who.userId.take(64)
                    it[approverId] = who.approverId.take(64)
                    it[deviceId] = who.deviceId?.take(64)
                    it[AiRequests.kind] = kind
                    it[AiRequests.outcome] = outcome.take(40)
                    it[AiRequests.changes] = changes
                    it[AiRequests.rejected] = rejected
                    it[AiRequests.elapsedMs] = elapsedMs
                }
            }
        }
    }

    fun recent(limit: Int = 100): List<AiRequestDto> = transaction {
        AiRequests.selectAll().orderBy(AiRequests.id, SortOrder.DESC).limit(limit).map {
            AiRequestDto(it[AiRequests.createdAt].toString(), it[AiRequests.userId], it[AiRequests.approverId],
                it[AiRequests.deviceId], it[AiRequests.kind], it[AiRequests.outcome], it[AiRequests.changes],
                it[AiRequests.rejected], it[AiRequests.elapsedMs])
        }
    }
}
