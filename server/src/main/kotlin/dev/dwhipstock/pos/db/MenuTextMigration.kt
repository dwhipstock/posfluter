package dev.dwhipstock.pos.db

import dev.dwhipstock.pos.sdk.Outbox
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.Transaction

/**
 * Migration 042 (code: it writes outbox events): the Copper Lantern demo menu
 * reads as a Montréal pub's menu. Place names become Québec ones (a Montréal
 * lager, a Québec cider, Eastern Townships wines, a Québec ice cider in place
 * of the icewine), the French wording is reviewed, and the single-size variant
 * label "Régulier" becomes "Standard". Item and variant ids do not change.
 *
 * Stores own their catalog (one-way sync), so live databases are updated here;
 * a new database is seeded with the new text directly (CopperLanternSeed).
 * Each column changes only while it still holds its old seeded value — a name
 * or description a manager edited is never overwritten, and an owner-built
 * menu is untouched. Re-running finds nothing to change.
 *
 * Every changed item gets one normal item.updated event carrying the full item
 * snapshot (variants included), so the cloud portal's menu mirrors it. The
 * placeholder receipt header and footer from 006 (a Toronto phone and address,
 * an English-only thank-you) are replaced the same way, only while unedited.
 * Raw SQL and the frozen snapshot shape of 034 on purpose.
 */
object MenuTextMigration {
    const val VERSION = 42
    const val NAME = "042_quebec_menu_text (code)"

    /** One column of one item: its old seeded text and the new text. */
    data class Change(val itemId: String, val column: String, val old: String, val new: String)

    val CHANGES: List<Change> = listOf(
        Change("lantern-lager", "description_fr", "Lager vive et maltée brassée en Ontario.", "Lager désaltérante et maltée, brassée à Montréal."),
        Change("lantern-lager", "description_en", "Crisp, malty lager brewed in Ontario.", "Crisp, malty lager brewed in Montréal."),
        Change("amber-ale", "name_fr", "Ale ambrée", "Ambrée cuivrée"),
        Change("amber-ale", "description_fr", "Ale ambrée aux notes de caramel et de noix.", "Rousse aux notes de caramel et de noix grillées."),
        Change("wheat-beer", "description_fr", "Bière de blé légère, orange et coriandre.", "Blanche légère à l'orange et à la coriandre."),
        Change("porter-can", "description_fr", "Porter torréfié au cacao.", "Porter aux notes torréfiées de cacao."),
        Change("hazy-ipa", "description_fr", "IPA juteuse brassée à Toronto.", "IPA juteuse d'une microbrasserie montréalaise."),
        Change("hazy-ipa", "description_en", "Juicy IPA brewed in Toronto.", "Juicy IPA from a Montréal microbrewery."),
        Change("mexican-lager", "description_fr", "Lager légère servie avec lime.", "Lager légère, servie avec une tranche de lime."),
        Change("dry-cider", "name_fr", "Cidre sec", "Cidre sec du Québec"),
        Change("dry-cider", "name_en", "Ontario Dry Cider", "Québec Dry Cider"),
        Change("dry-cider", "description_fr", "Cidre de pommes ontariennes, vif et sec.", "Cidre vif et sec, fait de pommes du Québec."),
        Change("dry-cider", "description_en", "Crisp dry cider made with Ontario apples.", "Crisp dry cider made with Québec apples."),
        Change("na-lager", "description_fr", "Lager maltée à moins de 0,5 %.", "Lager maltée à moins de 0,5 % d'alcool."),
        Change("pinot-noir", "name_fr", "Pinot noir de Niagara", "Pinot noir des Cantons-de-l'Est"),
        Change("pinot-noir", "name_en", "Niagara Pinot Noir", "Eastern Townships Pinot Noir"),
        Change("pinot-noir", "description_fr", "Rouge léger, cerise et épices.", "Rouge léger, notes de cerise et d'épices."),
        Change("cab-merlot", "name_en", "Ontario Cabernet Merlot", "Cabernet Merlot"),
        Change("cab-merlot", "description_fr", "Rouge souple, mûre et cèdre.", "Rouge souple, notes de mûre et de cèdre."),
        Change("riesling", "name_fr", "Riesling de Niagara", "Riesling des Cantons-de-l'Est"),
        Change("riesling", "name_en", "Niagara Riesling", "Eastern Townships Riesling"),
        Change("riesling", "description_fr", "Blanc vif, pomme et agrumes.", "Blanc vif, notes de pomme et d'agrumes."),
        Change("chardonnay", "description_fr", "Blanc rond, poire et vanille.", "Blanc rond, notes de poire et de vanille."),
        Change("rose", "name_fr", "Rosé de la péninsule", "Rosé de la Montérégie"),
        Change("rose", "name_en", "Peninsula Rosé", "Montérégie Rosé"),
        Change("sparkling", "name_fr", "Brut de Niagara", "Mousseux brut du Québec"),
        Change("sparkling", "name_en", "Niagara Brut", "Québec Sparkling Brut"),
        Change("sparkling", "description_fr", "Bulles fines, pomme verte et brioche.", "Bulles fines, notes de pomme verte et de brioche."),
        Change("icewine", "name_fr", "Vin de glace", "Cidre de glace du Québec"),
        Change("icewine", "name_en", "Ontario Icewine", "Québec Ice Cider"),
        Change("icewine", "description_fr", "Vin de dessert riche aux notes d'abricot.", "Cidre de dessert riche, notes de pomme cuite et de caramel."),
        Change("icewine", "description_en", "Rich dessert wine with apricot notes.", "Rich dessert cider with baked-apple and caramel notes."),
        Change("copper-old-fashioned", "name_fr", "Old fashioned cuivré", "Old Fashioned cuivré"),
        Change("smoked-caesar", "description_fr", "Vodka, clamato épicé et sel fumé.", "Vodka, jus de tomate et de palourde épicé, sel fumé."),
        Change("smoked-caesar", "description_en", "Vodka, spiced clamato and smoked salt.", "Vodka, spiced tomato-clam juice and smoked salt."),
        Change("elderflower-gin", "name_fr", "Gin pétillant au sureau", "Gin fizz au sureau"),
        Change("dark-stormy", "description_fr", "Rhum brun, gingembre et lime.", "Rhum brun, bière de gingembre et lime."),
        Change("zero-gimlet", "description_fr", "Botanique sans alcool, lime et romarin.", "Spiritueux botanique sans alcool, lime et romarin."),
        Change("pretzel", "description_fr", "Bretzel chaud, moutarde à la bière et fromage.", "Bretzel chaud, moutarde à la bière et trempette au fromage."),
        Change("wings", "description_fr", "Une livre d'ailes avec sauce au choix.", "Une livre d'ailes, sauce au choix."),
        Change("spinach-dip", "description_fr", "Trempette chaude avec croustilles de pita.", "Trempette chaude servie avec croustilles de pita."),
        Change("mushroom-burger", "description_fr", "Bœuf, champignons, suisse et aïoli.", "Bœuf, champignons, fromage suisse et aïoli."),
        Change("fish-sandwich", "description_fr", "Aiglefin pané, salade de chou et tartare.", "Aiglefin frit, salade de chou et sauce tartare."),
        Change("fish-chips", "description_fr", "Aiglefin, frites, salade de chou et tartare.", "Aiglefin en pâte à la bière, frites, salade de chou et sauce tartare."),
        Change("mac-cheese", "name_fr", "Macaroni au fromage", "Macaroni aux trois fromages"),
        Change("mac-cheese", "description_fr", "Macaroni crémeux gratiné avec chapelure.", "Macaroni crémeux gratiné aux trois fromages."),
        Change("harvest-salad", "name_fr", "Salade des récoltes", "Salade des récoltes du Québec"),
        Change("harvest-salad", "name_en", "Ontario Harvest Salad", "Québec Harvest Salad"),
        Change("harvest-salad", "description_fr", "Verdure, pommes, courge, noix et chèvre.", "Jeunes pousses, pommes, courge, noix de Grenoble et fromage de chèvre."),
        Change("cauliflower", "name_fr", "Chou-fleur rôti", "Steak de chou-fleur rôti"),
        Change("sticky-pudding", "description_fr", "Gâteau aux dattes, caramel et crème glacée.", "Gâteau aux dattes, sauce au caramel et crème glacée."),
        Change("cheesecake", "name_fr", "Gâteau au fromage", "Gâteau au fromage à l'érable"),
        Change("cheesecake", "description_fr", "Gâteau au fromage à l'érable et noix de pacane.", "Garni de pacanes grillées."),
        Change("late-fries", "name_fr", "Frites de nuit", "Frites de fin de soirée"),
        Change("late-fries", "description_fr", "Panier de frites avec aïoli maison.", "Panier de frites, aïoli maison."),
        Change("grilled-cheese", "description_fr", "Cheddar vieilli sur pain au levain.", "Cheddar vieilli sur pain au levain grillé."),
        Change("onion-rings", "description_fr", "Rondelles croustillantes et sauce barbecue.", "Rondelles croustillantes, sauce barbecue."),
    )

    /** The French label of the demo menu's single-size ("regular") variants. */
    const val OLD_REGULAR_FR = "Régulier"
    const val NEW_REGULAR_FR = "Standard"

    /** The demo menu's single-size variant ids, as 037 froze them. */
    val REGULAR_VARIANTS: List<String> = MenuPriceMigration.PRICES.keys.filter { it.endsWith(":regular") }

    /** venue_settings column → (006's placeholder, the new value). */
    val SETTINGS: Map<String, Pair<String, String>> = linkedMapOf(
        "venue_phone" to ("+1 416 555 0142" to "+1 514 555 0142"),
        "venue_address" to ("47 Lantern Lane, Toronto, ON" to "47 Lantern Lane, Montréal, QC"),
        "receipt_footer" to ("Thank you for visiting!" to "Merci de votre visite ! · Thank you for visiting!"),
    )

    private val COLUMNS = setOf("name_fr", "name_en", "description_fr", "description_en")

    fun run(tx: Transaction) { update(tx) }

    /** Returns the ids of the items that changed, in change order. */
    fun update(tx: Transaction): List<String> {
        val changed = linkedSetOf<String>()
        for (c in CHANGES) {
            check(c.column in COLUMNS) { c.column }
            if (!exists(tx, "SELECT 1 FROM items WHERE id = ? AND ${c.column} = ?", c.itemId, c.old)) continue
            tx.exec("UPDATE items SET ${c.column} = ? WHERE id = ?", listOf(text(c.new), text(c.itemId)))
            changed += c.itemId
        }
        for (variantId in REGULAR_VARIANTS) {
            if (!exists(tx, "SELECT 1 FROM item_variants WHERE id = ? AND label_fr = ?", variantId, OLD_REGULAR_FR)) continue
            tx.exec("UPDATE item_variants SET label_fr = ? WHERE id = ?", listOf(text(NEW_REGULAR_FR), text(variantId)))
            var itemId: String? = null
            tx.exec("SELECT item_id FROM item_variants WHERE id = ?", listOf(text(variantId))) { rs ->
                if (rs.next()) itemId = rs.getString(1)
            }
            itemId?.let { changed += it }
        }
        for ((column, values) in SETTINGS) {
            val (old, new) = values
            tx.exec("UPDATE venue_settings SET $column = ? WHERE id = 1 AND $column = ?", listOf(text(new), text(old)))
        }
        for (itemId in changed) {
            val snapshot = MenuCategoryMigration.itemJson(tx, itemId)
            Outbox.write("item.updated", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                put("nameFr", snapshot["nameFr"]!!)
                put("nameEn", snapshot["nameEn"]!!)
                put("descriptionFr", snapshot["descriptionFr"]!!)
                put("descriptionEn", snapshot["descriptionEn"]!!)
                put("item", snapshot)
            })
        }
        return changed.toList()
    }

    private fun exists(tx: Transaction, sql: String, vararg args: String): Boolean {
        var hit = false
        tx.exec(sql, args.map { text(it) }) { rs -> hit = rs.next() }
        return hit
    }

    private fun text(v: String): Pair<IColumnType<*>, Any?> = TextColumnType() to v
}
