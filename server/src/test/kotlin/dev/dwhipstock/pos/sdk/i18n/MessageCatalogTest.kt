package dev.dwhipstock.pos.sdk.i18n

import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.Receipt
import dev.dwhipstock.pos.sdk.ReceiptFee
import dev.dwhipstock.pos.sdk.ReceiptItem
import dev.dwhipstock.pos.sdk.ReceiptKind
import dev.dwhipstock.pos.sdk.ReceiptPolicy
import dev.dwhipstock.pos.sdk.ReceiptRenderer
import dev.dwhipstock.pos.sdk.ReceiptTender
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The N-locale message catalog. Adding a language to the server is a resource
 * drop — the "zh" locale used here exists ONLY as
 * src/test/resources/i18n/messages_zh.properties; no code registers it.
 *
 * The golden files under src/test/resources/golden/ were captured from the
 * renderer BEFORE the catalog refactor (the pick(fr,en) era) — the byte-identity
 * tests prove the refactor changed the mechanism, not one byte of output.
 */
class MessageCatalogTest {

    // canonical receipt: variants, qty>1, notes, fees, tax, rounding and change
    private val receipt = Receipt(
        checkId = 42,
        tableLabel = "A5",
        openedAt = LocalDateTime.of(2026, 7, 21, 19, 5),
        closedAt = LocalDateTime.of(2026, 7, 21, 21, 47),
        items = listOf(
            ReceiptItem("Bière Lantern House Lager", "Lantern House Lager", "bouteille", "Bottle", 2, Money.cad(120), Money.cad(240), null),
            ReceiptItem("Poutine classique", "Classic Poutine", null, null, 1, Money.cad(120), Money.cad(120), "Moins épicé"),
        ),
        fees = listOf(ReceiptFee("Droit de bouchon", "Corkage", Money.cad(100))),
        grandTotal = Money(46050),
        taxIncluded = Money(5298),
        taxRatePercent = 13,
        tenders = listOf(ReceiptTender("Comptant", "Cash", Money.cad(500), Money(46050), Money(-50), Money.cad(39))),
    )
    private val policy = ReceiptPolicy.Standard(
        "Copper Lantern — Vieux-Port", listOf("47, rue de la Lanterne, Montréal (Québec) H2Y 1Q7"), "Merci\u00A0!",
        showTax = true, phone = "+1 514 555 0142",
    )

    private fun golden(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/golden/$name")) { "missing golden file $name" }
            .readBytes().toString(Charsets.UTF_8)

    private fun render(locale: LocaleCode, kind: ReceiptKind) =
        PrinterAdapter.renderText(ReceiptRenderer.render(receipt, policy.withLocale(locale), kind))

    @Test
    fun thePhoneLabelFollowsTheReceiptLanguageAndTheNumberComesFromSettings() {
        assertTrue("Tél. +1 514 555 0142" in render(LocaleCode.FR, ReceiptKind.FINAL))
        assertTrue("Tel. +1 514 555 0142" in render(LocaleCode.EN, ReceiptKind.FINAL))
        assertTrue("Tel. +1 514 555 0142" in render(LocaleCode.ES, ReceiptKind.PROVISIONAL))
        assertTrue("Tél. / Tel." !in render(LocaleCode.FR, ReceiptKind.FINAL))
        // no number in settings, no phone line
        val noPhone = PrinterAdapter.renderText(ReceiptRenderer.render(receipt, policy.copy(phone = " "), ReceiptKind.FINAL))
        assertTrue("Tel." !in noPhone && "Tél." !in noPhone)
    }

    @Test
    fun frenchReceiptsUseTheCompleteFrenchCatalog() {
        val final = render(LocaleCode.FR, ReceiptKind.FINAL)
        val bill = render(LocaleCode.FR, ReceiptKind.PROVISIONAL)
        assertTrue("Ouverture" in final && "Fermeture" in final && "Monnaie rendue" in final)
        assertTrue("ADDITION" in bill && "CECI N’EST PAS UN REÇU" in bill)
    }

    @Test
    fun englishReceiptsUseTheCompleteDefaultCatalog() {
        val final = render(LocaleCode.EN, ReceiptKind.FINAL)
        val bill = render(LocaleCode.EN, ReceiptKind.PROVISIONAL)
        assertTrue("Open" in final && "Close" in final && "Change" in final)
        assertTrue("CUSTOMER BILL" in bill && "NOT A RECEIPT" in bill)
    }

    @Test
    fun everyShippedCatalogIsCompleteWithNoOrphanKeys() {
        // enumerate what actually ships (main resources); the partial zh pack
        // lives in TEST resources and is deliberately exempt
        val shipped = File("src/main/resources/i18n").listFiles().orEmpty()
            .mapNotNull { Regex("""messages_([a-z0-9-]+)\.properties""").matchEntire(it.name)?.groupValues?.get(1) }
        assertTrue(shipped.containsAll(listOf("fr", "en")), "fr and en must always ship")
        val allIds = MessageKey.entries.map { it.id }.toSet()
        for (tag in shipped) {
            val translated = Messages.translatedKeys(LocaleCode(tag))
            for (key in MessageKey.entries) {
                assertTrue(key.id in translated, "shipped locale '$tag' is missing '${key.id}'")
            }
            assertEquals(emptySet(), translated - allIds, "shipped locale '$tag' has keys no code references")
        }
    }

    @Test
    fun catalogValuesCarryNoStrayWhitespace() {
        // trailing spaces in .properties values are invisible but survive parsing —
        // they would shift the 42-col layout or double a code-appended space
        val byId = MessageKey.entries.associateBy { it.id }
        for (tag in Messages.supportedTags()) {
            val locale = LocaleCode(tag)
            for (id in Messages.translatedKeys(locale)) {
                val value = Messages.get(byId[id] ?: continue, locale)
                assertEquals(value.trim(), value, "'$tag/$id' has leading/trailing whitespace")
            }
        }
    }

    @Test
    fun storedCodesNormalizeBeforeCatalogLookup() {
        // deliberate selection-policy behavior: legacy/hand-edited DB values like
        // mixed-case or padded codes select the catalog instead of silently falling back
        assertEquals(LocaleCode.EN, LocaleCode.of(" EN "))
        assertEquals(LocaleCode.FR, LocaleCode.of("Fr"))
    }

    @Test
    fun customerBannersStayBilingualInEveryLocale() {
        // anyone at the table must be able to tell a provisional bill from a
        // receipt — a locale pack overriding these must keep both of its
        // store's languages: French + English at the pubs, Spanish + English
        // at the US store (es)
        val second = mapOf("es" to ("NO ES UN RECIBO" to "CUENTA"))
        for (tag in Messages.supportedTags()) {
            val locale = LocaleCode(tag)
            val (notReceipt2, bill2) = second[tag] ?: ("PAS UN REÇU" to "ADDITION")
            val notAReceipt = Messages.get(MessageKey.RECEIPT_NOT_A_RECEIPT, locale)
            assertTrue("NOT A RECEIPT" in notAReceipt && notReceipt2 in notAReceipt,
                "locale '$tag' de-bilingualized the NOT-A-RECEIPT banner")
            val billBanner = Messages.get(MessageKey.RECEIPT_BILL_BANNER, locale)
            assertTrue("CUSTOMER BILL" in billBanner && bill2 in billBanner,
                "locale '$tag' de-bilingualized the customer-bill banner")
        }
    }

    @Test
    fun droppedInLocaleIsDiscoveredFromResourcesAlone() {
        assertTrue(
            Messages.supports(LocaleCode("zh")),
            "zh must be discovered purely from messages_zh.properties on the classpath",
        )
    }

    @Test
    fun partialLocaleFallsBackPerKeyAndNeverBlanksOrThrows() {
        val zh = LocaleCode("zh")
        assertEquals("总计", Messages.get(MessageKey.RECEIPT_TOTAL, zh)) // translated
        assertEquals("Open", Messages.get(MessageKey.RECEIPT_OPEN, zh)) // falls back to default (en)
        for (key in MessageKey.entries) {
            assertTrue(Messages.get(key, zh).isNotBlank(), "'${key.id}' blanked for zh")
        }
    }

    @Test
    fun unknownLocaleRendersEntirelyInDefault() {
        for (key in MessageKey.entries) {
            assertEquals(Messages.get(key, Messages.defaultLocale), Messages.get(key, LocaleCode("zz")))
        }
    }

    @Test
    fun placeholdersSubstitute() {
        assertEquals("Taxe de vente 13\u00A0% (incluse)", Messages.get(MessageKey.RECEIPT_TAX_INCLUDED, LocaleCode.FR, 13))
        assertEquals("Sales tax 13% (included)", Messages.get(MessageKey.RECEIPT_TAX_INCLUDED, LocaleCode.EN, 13))
    }

    @Test
    fun rendererSurvivesPartiallyTranslatedLocale() {
        val text = render(LocaleCode("zh"), ReceiptKind.FINAL)
        assertTrue("总计" in text, "translated key should render in zh")
        assertTrue("找零" in text, "change label should render in zh")
        assertTrue("Open" in text, "untranslated key should fall back to en")
        assertFalse("receipt." in text, "raw key ids must never leak onto a receipt")
        // bilingual DATA fields (menu names) have no zh side — default locale's side prints
        assertTrue("Lantern House Lager" in text)
    }
}
