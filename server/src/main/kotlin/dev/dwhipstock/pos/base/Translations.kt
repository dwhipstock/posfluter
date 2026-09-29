package dev.dwhipstock.pos.base

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/**
 * Names beyond the two catalog slots (name_fr / name_en), migration 053: one
 * row per thing and extra language. A store that speaks more than French and
 * English (Copper Lantern: + es, de) keeps its Spanish and German names here;
 * the API adds them to each item / category / zone / floor object as a
 * `names` map, and the screens fall back to English, then French, when a
 * language has no row ([pick]). Item, variant, category and zone names ride
 * up to the portal in the catalog snapshots (`names`, one-way); a change here
 * writes the thing's snapshot again ([set], unless the caller batches it).
 */
object Translations : Table("translations") {
    val entity = varchar("entity", 24) // item | variant | category | zone | floor_object
    val entityId = varchar("entity_id", 160)
    val lang = varchar("lang", 8)
    val text = varchar("text", 500)
    override val primaryKey = PrimaryKey(entity, entityId, lang)

    const val ITEM = "item"
    const val CATEGORY = "category"
    const val ZONE = "zone"
    const val FLOOR_OBJECT = "floor_object"
    /** A size / container label (id "item:key"), e.g. "20 oz pint" → "Pinta de 20 oz". */
    const val VARIANT = "variant"

    /** The catalog's own languages: never stored here. */
    val SLOTS = setOf("fr", "en")

    /** Every extra name of [entity]: id → (lang → text). Call inside a transaction. */
    fun of(entity: String): Map<String, Map<String, String>> =
        selectAll().where { Translations.entity eq entity }
            .groupBy({ it[entityId] }) { it[lang] to it[text] }
            .mapValues { (_, pairs) -> pairs.toMap() }

    /** One name, or null. Call inside a transaction. */
    fun get(entity: String, id: String, lang: String): String? =
        selectAll().where { (Translations.entity eq entity) and (entityId eq id) and (Translations.lang eq lang) }
            .firstOrNull()?.get(text)

    /**
     * Set (or with a blank [value], remove) one name. Call inside a
     * transaction. With [sync] (the default) the portal hears of it: the
     * item's / category's snapshot, or the zone's names, go in the outbox.
     */
    fun set(entity: String, id: String, lang: String, value: String?, sync: Boolean = true) {
        require(lang !in SLOTS) { "fr and en live on the row itself" }
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) {
            val n = deleteWhere { (Translations.entity eq entity) and (entityId eq id) and (Translations.lang eq lang) }
            if (n > 0 && sync) dev.dwhipstock.pos.api.syncNamesOf(entity, id)
            return
        }
        val n = update({ (Translations.entity eq entity) and (entityId eq id) and (Translations.lang eq lang) }) {
            it[text] = v.take(500)
        }
        if (n == 0) insert {
            it[Translations.entity] = entity; it[entityId] = id; it[Translations.lang] = lang; it[text] = v.take(500)
        }
        if (sync) dev.dwhipstock.pos.api.syncNamesOf(entity, id)
    }

    /** The extra names of one thing (lang → text). Call inside a transaction. */
    fun namesOf(entity: String, id: String): Map<String, String> =
        selectAll().where { (Translations.entity eq entity) and (entityId eq id) }
            .associate { it[lang] to it[text] }

    /**
     * Whether this database has the table yet: the older catalog migrations
     * snapshot items before migration 053 runs.
     */
    fun present(): Boolean {
        var has = false
        org.jetbrains.exposed.sql.transactions.TransactionManager.current()
            .exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'translations'") { has = it.next() }
        return has
    }

    /** Adds a name only where none is set yet (seeding never overwrites a manager's edit). */
    fun setIfMissing(entity: String, id: String, lang: String, value: String) {
        if (get(entity, id, lang) == null) set(entity, id, lang, value)
    }

    /**
     * A name in [lang]: the catalog slots for fr / en; any other language its
     * row in [extra], else English, else French.
     */
    fun pick(lang: String, fr: String, en: String, extra: Map<String, String>?): String = when (lang) {
        "fr" -> fr
        "en" -> en
        else -> extra?.get(lang)?.takeIf { it.isNotBlank() } ?: en.ifBlank { fr }
    }
}
