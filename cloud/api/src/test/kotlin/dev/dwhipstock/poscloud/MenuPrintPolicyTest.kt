package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menuprint.MenuKind
import dev.dwhipstock.poscloud.menuprint.Policies
import dev.dwhipstock.poscloud.menuprint.PrintAi
import dev.dwhipstock.poscloud.menuprint.PrintCatalog
import dev.dwhipstock.poscloud.menuprint.PrintSelect
import dev.dwhipstock.poscloud.menuprint.PrintWords
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AI never invents a rule or a policy ("Specials are available for
 * dine-in customers only"): such sentences are dropped from the tagline and
 * the footer in all five languages, and an empty footer becomes the fixed
 * friendly line (responsible drinking when alcohol is on the page).
 */
class MenuPrintPolicyTest {
    private val policies = mapOf(
        "en" to listOf("Specials are available for dine-in customers only.", "While supplies last.", "Limit one per person.",
            "Valid Monday to Friday.", "Must be 21+ with ID.", "Reservations required.", "Not valid with other discounts.", "Take-out excluded."),
        "fr" to listOf("Offre valable sur place seulement.", "Jusqu'à épuisement des stocks.", "Une par personne.", "Réservation obligatoire.",
            "Non cumulable avec d'autres rabais."),
        "es" to listOf("Solo para consumir en el local.", "Hasta agotar existencias.", "Válido de lunes a viernes.", "Límite de uno por persona.",
            "No acumulable con otros descuentos."),
        "de" to listOf("Nur vor Ort erhältlich.", "Solange der Vorrat reicht.", "Gültig von Montag bis Freitag.", "Pro Person ein Getränk.",
            "Reservierung erforderlich."),
        "af" to listOf("Slegs vir eet hier.", "Solank voorraad hou.", "Geldig Maandag tot Vrydag.", "Een per persoon.", "Bespreking vereis."),
    )
    private val friendly = mapOf(
        "en" to listOf("Cheers from the whole team!", "Please drink responsibly.", "Good food, good friends."),
        "fr" to listOf("Santé et bon appétit !", "À consommer avec modération."),
        "es" to listOf("¡Salud y buen provecho!", "Bebe con moderación."),
        "de" to listOf("Prost und guten Appetit!", "Bitte trinken Sie verantwortungsvoll."),
        "af" to listOf("Gesondheid en geniet dit!", "Drink asseblief verantwoordelik."),
    )

    @Test
    fun policiesAreSpottedInEveryLanguageAndFriendlyLinesAreNot() {
        for ((lang, list) in policies) for (p in list) assertTrue(Policies.statesPolicy(p), "$lang: $p")
        for ((lang, list) in friendly) for (f in list) assertFalse(Policies.statesPolicy(f), "$lang: $f")
        for (lang in PrintWords.LANGS) for (a in listOf(true, false)) assertFalse(Policies.statesPolicy(PrintWords.footer(lang, a)), lang)
    }

    @Test
    fun onlyThePolicySentenceGoes() {
        assertEquals("Please drink responsibly.", Policies.withoutPolicies("Please drink responsibly. Specials are available for dine-in customers only."))
        assertEquals("Prost!", Policies.withoutPolicies("Prost! Nur vor Ort erhältlich."))
        assertNull(Policies.withoutPolicies("Hasta agotar existencias."))
    }

    @Test
    fun anInventedPolicyNeverReachesThePage() {
        val reply = { tagline: String, footer: String ->
            """{"title":"Happy Hour","tagline":"$tagline","sections":[],"footer":"$footer"}"""
        }
        for (lang in PrintWords.LANGS) {
            val c = PrintCatalog.of(MenuPrintFixtures.menu, lang)
            val cands = PrintSelect.candidates(MenuKind.FLYER, c, "mon")
            val (pol1, pol2) = policies.getValue(lang).take(2)
            val p = PrintAi.parse(reply("${friendly.getValue(lang)[0]} $pol1", pol2), MenuKind.FLYER, c, cands, lang)!!.plan
            assertEquals(friendly.getValue(lang)[0], p.tagline, lang)
            // the flyer has beer on it: the fixed footer says drink responsibly
            assertEquals(PrintWords.footer(lang, alcohol = true), p.footer, lang)
        }
        // food only: the plain friendly footer
        val c = PrintCatalog.of(MenuPrintFixtures.menu, "en")
        val food = c.items.filter { !it.isAlcohol }
        val p = PrintAi.parse(reply("Hearty plates.", "Dine-in only."), MenuKind.HIGHLIGHTS, c, food, "en")!!.plan
        assertEquals("Enjoy!", p.footer)
        // the manager's notes give the policy: then it may be printed
        val noted = PrintAi.parse(reply("Hearty plates.", "Specials for dine-in only."), MenuKind.FULL, c, c.items, "en",
            notes = "happy hour specials are dine-in only")!!.plan
        assertEquals("Specials for dine-in only.", noted.footer)
        // and the prompt says so
        val system = PrintAi.system(MenuKind.FLYER, "en", "English", null, true)
        assertTrue("Never state a rule, condition or policy" in system && "a policy only when" in system)
    }
}
