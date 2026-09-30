package dev.dwhipstock.pos.customers.copperlantern

import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.base.VenueSettings
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.restaurant.KitchenConfigTable
import dev.dwhipstock.pos.sync.CloudSync
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * Moves an existing Copper Lantern store from Montréal to Raleigh, North
 * Carolina, in place. Runs on every start of a Copper Lantern store (after the
 * seed, [dev.dwhipstock.pos.module]); a new store is seeded with the Raleigh
 * text already, so this finds nothing to do there.
 *
 * What it changes, each only while it still holds an old Montréal-era value:
 * - the receipt header: the address and phone of the store's [CopperLanternVenue],
 *   the bilingual thank-you line → English;
 * - the store's zone → America/New_York (Montréal's was America/Toronto);
 * - the kitchen tickets' language "both" (French + English) → the store's
 *   default (English);
 * - the Québec-specific menu names and descriptions ([ITEM_CHANGES]) and
 *   their Spanish, German and Afrikaans names ([NAME_CHANGES]).
 *
 * What it never touches: item ids, prices, photos, categories, rooms, tables,
 * floor objects, staff, grants and every sale (old closed sales keep their
 * GST/QST as history). A name, description or setting a manager edited is
 * kept as it is. Currency, taxes, languages and the legal age are code
 * ([CopperLanternConfig]), so they switch with the new build, not here.
 *
 * Idempotent: a second run finds no old value left and changes nothing. A
 * store that has synced sends the renamed items up as one catalog snapshot.
 */
object CopperLanternRaleighMove {
    const val STATE_KEY = "copperlantern_raleigh_v1"
    const val NEW_ZONE = CopperLanternConfig.TIME_ZONE
    const val NEW_FOOTER = "Thank you for visiting!"

    private val log = LoggerFactory.getLogger(CopperLanternRaleighMove::class.java)

    /** One column of one item: its old seeded text and the new text. */
    data class Change(val itemId: String, val column: String, val old: String, val new: String)

    /** One extra-language item name: every old text it may hold (the pubs' and Express's), and the new one. */
    data class NameChange(val itemId: String, val lang: String, val old: Set<String>, val new: String)

    data class Result(
        val settings: List<String>,
        val items: List<String>,
        val names: Int,
        val kitchenLanguage: Boolean,
    ) {
        val changed: Boolean get() = settings.isNotEmpty() || items.isNotEmpty() || names > 0 || kitchenLanguage
    }

    /** Every Montréal-era (and older Toronto placeholder) receipt address the seeds and migrations wrote. */
    val OLD_ADDRESSES = setOf(
        "47, rue de la Lanterne, Montréal (Québec) H2Y 1Q7",
        "212, avenue du Lampion, Montréal (Québec) H2J 3U4",
        "9, rue du Fanal, Montréal (Québec) H2X 2Q8",
        "47 Lantern Lane, Montréal, QC",
        "47 Lantern Lane, Toronto, ON",
    )
    val OLD_PHONES = setOf("+1 514 555 0142", "+1 514 555 0187", "+1 514 555 0163", "+1 416 555 0142")
    /** 042's bilingual thank-you (a no-break space before the "!", as French writes it), and a plain-space copy. */
    val OLD_FOOTERS = setOf("Merci de votre visite\u00A0! · Thank you for visiting!", "Merci de votre visite ! · Thank you for visiting!")
    val OLD_ZONES = setOf("", "America/Toronto", "America/Montreal")

    val ITEM_CHANGES: List<Change> = listOf(
        Change("lantern-lager", "description_fr", "Lager désaltérante et maltée, brassée à Montréal.", "Lager désaltérante et maltée, brassée à Raleigh."),
        Change("lantern-lager", "description_en", "Crisp, malty lager brewed in Montréal.", "Crisp, malty lager brewed in Raleigh."),
        Change("hazy-ipa", "description_fr", "IPA juteuse d'une microbrasserie montréalaise.", "IPA juteuse d'une microbrasserie de Raleigh."),
        Change("hazy-ipa", "description_en", "Juicy IPA from a Montréal microbrewery.", "Juicy IPA from a Raleigh microbrewery."),
        Change("canadian-lager", "name_fr", "Lager canadienne", "Lager américaine"),
        Change("canadian-lager", "name_en", "Canadian Lager", "American Lager"),
        Change("dry-cider", "name_fr", "Cidre sec du Québec", "Cidre sec du verger"),
        Change("dry-cider", "name_en", "Québec Dry Cider", "Orchard Dry Cider"),
        Change("dry-cider", "description_fr", "Cidre vif et sec, fait de pommes du Québec.", "Cidre vif et sec, fait de pommes de verger."),
        Change("dry-cider", "description_en", "Crisp dry cider made with Québec apples.", "Crisp dry cider made with orchard apples."),
        Change("pinot-noir", "name_fr", "Pinot noir des Cantons-de-l'Est", "Pinot noir de l'Oregon"),
        Change("pinot-noir", "name_en", "Eastern Townships Pinot Noir", "Oregon Pinot Noir"),
        Change("riesling", "name_fr", "Riesling des Cantons-de-l'Est", "Riesling des Finger Lakes"),
        Change("riesling", "name_en", "Eastern Townships Riesling", "Finger Lakes Riesling"),
        Change("rose", "name_fr", "Rosé de la Montérégie", "Rosé de la vallée de la Yadkin"),
        Change("rose", "name_en", "Montérégie Rosé", "Yadkin Valley Rosé"),
        Change("sparkling", "name_fr", "Mousseux brut du Québec", "Mousseux brut"),
        Change("sparkling", "name_en", "Québec Sparkling Brut", "Sparkling Brut"),
        Change("icewine", "name_fr", "Cidre de glace du Québec", "Cidre de glace"),
        Change("icewine", "name_en", "Québec Ice Cider", "Ice Cider"),
        Change("copper-old-fashioned", "description_fr", "Whisky canadien, érable, amers et orange.", "Bourbon, érable, amers et orange."),
        Change("copper-old-fashioned", "description_en", "Canadian whisky, maple, bitters and orange.", "Bourbon, maple, bitters and orange."),
        Change("smoked-caesar", "name_fr", "César fumé", "Bloody Mary fumé"),
        Change("smoked-caesar", "name_en", "Smoked Caesar", "Smoked Bloody Mary"),
        Change("smoked-caesar", "description_fr", "Vodka, jus de tomate et de palourde épicé, sel fumé.", "Vodka, jus de tomate épicé, raifort et sel fumé."),
        Change("smoked-caesar", "description_en", "Vodka, spiced tomato-clam juice and smoked salt.", "Vodka, spiced tomato juice, horseradish and smoked salt."),
        Change("reuben", "name_fr", "Reuben montréalais", "Reuben classique"),
        Change("reuben", "name_en", "Montreal Reuben", "Classic Reuben"),
        Change("reuben", "description_fr", "Viande fumée, suisse, choucroute et sauce russe.", "Corned-beef, suisse, choucroute et sauce russe."),
        Change("reuben", "description_en", "Smoked meat, Swiss cheese, sauerkraut and Russian dressing.", "Corned beef, Swiss cheese, sauerkraut and Russian dressing."),
        Change("harvest-salad", "name_fr", "Salade des récoltes du Québec", "Salade de la récolte"),
        Change("harvest-salad", "name_en", "Québec Harvest Salad", "Harvest Salad"),
    )

    private fun n(itemId: String, lang: String, new: String, vararg old: String) = NameChange(itemId, lang, old.toSet(), new)

    val NAME_CHANGES: List<NameChange> = listOf(
        n("canadian-lager", "es", "Lager americana", "Lager canadiense"),
        n("canadian-lager", "de", "Amerikanisches Lager", "Kanadisches Lager"),
        n("canadian-lager", "af", "Amerikaanse lager", "Kanadese lager"),
        n("dry-cider", "es", "Sidra seca de huerto", "Sidra seca de Québec"),
        n("dry-cider", "de", "Trockener Obstgarten-Cidre", "Trockener Cidre aus Québec"),
        n("dry-cider", "af", "Droë boordsider", "Droë sider uit Québec"),
        // the pubs' names, then Express's own
        n("pinot-noir", "es", "Pinot Noir de Oregón", "Pinot Noir de los Cantons-de-l'Est", "Pinot noir de los Cantones del Este"),
        n("pinot-noir", "de", "Pinot Noir aus Oregon", "Pinot Noir aus den Cantons-de-l'Est", "Pinot Noir aus den Eastern Townships"),
        n("pinot-noir", "af", "Pinot Noir van Oregon", "Pinot Noir van die Cantons-de-l’Est", "Pinot Noir van die Oostelike Townships"),
        n("riesling", "es", "Riesling de Finger Lakes", "Riesling de los Cantons-de-l'Est", "Riesling de los Cantones del Este"),
        n("riesling", "de", "Riesling aus den Finger Lakes", "Riesling aus den Cantons-de-l'Est", "Riesling aus den Eastern Townships"),
        n("riesling", "af", "Finger Lakes-Riesling", "Riesling van die Cantons-de-l’Est", "Riesling van die Oostelike Townships"),
        n("rose", "es", "Rosado del valle de Yadkin", "Rosado de Montérégie"),
        n("rose", "de", "Rosé aus dem Yadkin Valley", "Rosé aus der Montérégie"),
        n("rose", "af", "Rosé van die Yadkin-vallei", "Rosé van Montérégie"),
        n("sparkling", "es", "Espumoso brut", "Espumoso brut de Québec"),
        n("sparkling", "de", "Schaumwein Brut", "Schaumwein Brut aus Québec"),
        n("sparkling", "af", "Brut-vonkelwyn", "Brut-vonkelwyn uit Québec"),
        n("icewine", "es", "Sidra de hielo", "Sidra de hielo de Québec"),
        n("icewine", "de", "Eiscidre", "Eiscidre aus Québec"),
        n("icewine", "af", "Yssider", "Yssider uit Québec"),
        n("smoked-caesar", "es", "Bloody Mary ahumado", "Caesar ahumado"),
        n("smoked-caesar", "de", "Geräucherte Bloody Mary", "Smoked Caesar"),
        n("smoked-caesar", "af", "Gerookte Bloody Mary", "Gerookte Caesar"),
        n("reuben", "es", "Reuben clásico", "Reuben de Montréal"),
        n("reuben", "de", "Klassisches Reuben", "Montréal Reuben"),
        n("reuben", "af", "Klassieke Reuben", "Montréal Reuben"),
        n("harvest-salad", "es", "Ensalada de la cosecha", "Ensalada de cosecha de Québec"),
        n("harvest-salad", "de", "Erntesalat", "Herbstsalat aus Québec"),
        n("harvest-salad", "af", "Oesslaai", "Québec-oesslaai"),
    )

    private val ITEM_COLUMNS: Map<String, Column<String>> = mapOf(
        "name_fr" to Items.nameFr,
        "name_en" to Items.nameEn,
        "description_fr" to Items.descriptionFr,
        "description_en" to Items.descriptionEn,
    )

    /** A receipt address from Montréal (or the older placeholders): a seeded value, or any Québec address. */
    fun isOldAddress(address: String): Boolean {
        val a = address.trim()
        if (a in OLD_ADDRESSES) return true
        return Regex("""Montr[ée]al|\(Québec\)|\bQC\b""", RegexOption.IGNORE_CASE).containsMatchIn(a)
    }

    /** A Montréal (514) or Toronto (416) demo number: the seeds' own, or any +1 514 / +1 416. */
    fun isOldPhone(phone: String): Boolean {
        val p = phone.trim()
        return p in OLD_PHONES || p.startsWith("+1 514") || p.startsWith("+1 416")
    }

    /** Run on a Copper Lantern store (Glenwood South / Plateau / Express). Safe to call on every boot. */
    fun run(venue: CopperLanternVenue): Result = transaction {
        val settings = mutableListOf<String>()
        VenueSettings.selectAll().where { VenueSettings.id eq 1 }.firstOrNull()?.let { row ->
            val address = row[VenueSettings.venueAddress]
            val phone = row[VenueSettings.venuePhone]
            val footer = row[VenueSettings.receiptFooter]
            val zone = row[VenueSettings.timezone]
            if (isOldAddress(address)) settings += "venueAddress"
            if (isOldPhone(phone)) settings += "venuePhone"
            if (footer.trim() in OLD_FOOTERS) settings += "receiptFooter"
            if (zone.trim() in OLD_ZONES) settings += "timezone"
            if (settings.isNotEmpty()) VenueSettings.update({ VenueSettings.id eq 1 }) {
                if ("venueAddress" in settings) it[venueAddress] = venue.address
                if ("venuePhone" in settings) it[venuePhone] = venue.phone
                if ("receiptFooter" in settings) it[receiptFooter] = NEW_FOOTER
                if ("timezone" in settings) it[timezone] = NEW_ZONE
            }
        }
        if ("timezone" in settings) dev.dwhipstock.pos.sdk.VenueClock.use(java.time.ZoneId.of(NEW_ZONE))
        // (no settings.updated event: it carries key names only, and a brand-new
        // store would send one on its first boot for the seeded footer)

        // kitchen tickets in French + English → the store's default language (English)
        val kitchenLanguage = KitchenConfigTable.get("language")?.trim()?.lowercase() == "both"
        if (kitchenLanguage) KitchenConfigTable.set("language", "")

        val items = linkedSetOf<String>()
        for (c in ITEM_CHANGES) {
            val col = ITEM_COLUMNS.getValue(c.column)
            val n = Items.update({ (Items.id eq c.itemId) and (col eq c.old) }) { it[col] = c.new }
            if (n > 0) items += c.itemId
        }
        var names = 0
        for (c in NAME_CHANGES) {
            val current = Translations.get(Translations.ITEM, c.itemId, c.lang) ?: continue
            if (current !in c.old) continue
            Translations.set(Translations.ITEM, c.itemId, c.lang, c.new, sync = false)
            items += c.itemId
            names++
        }

        // a store that synced sends its renamed items (names included) up once
        val synced = SyncState.get(CloudSync.CATALOG_SNAPSHOT_SEQ) != null
        if (synced && items.isNotEmpty()) {
            dev.dwhipstock.pos.api.writeChunkedCatalogSnapshot(items, reason = "raleigh")
        }
        val result = Result(settings, items.toList(), names, kitchenLanguage)
        if (result.changed || SyncState.get(STATE_KEY) == null) {
            SyncState.set(STATE_KEY, java.time.Instant.now().toString())
        }
        if (result.changed) {
            log.info("Copper Lantern → Raleigh, NC: settings ${settings.ifEmpty { listOf("none") }.joinToString(",")}; " +
                "${items.size} menu item(s) renamed, $names translated name(s); kitchen language reset: $kitchenLanguage")
        }
        result
    }
}
