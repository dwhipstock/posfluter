package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.Messages
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StoreProfileTest {

    private val us = StoreProfile(
        country = "US", currency = "USD",
        locales = listOf(LocaleCode.EN, LocaleCode.ES),
        timeZone = "America/Los_Angeles",
        kind = StoreProfile.Kind.RETAIL, legalAge = 21,
    )

    @Test
    fun usdFormatsTheAmericanWayInEnglishAndSpanish() {
        assertEquals("$12.99", us.format(Money(1299)))
        assertEquals("$12.99", us.format(Money(1299), LocaleCode.ES))
        assertEquals("$1,234.50", us.format(Money(123450), LocaleCode.ES))
        assertEquals("-$0.05", us.format(Money(-5)))
        assertEquals("$0.00", us.format(Money.ZERO))
    }

    @Test
    fun cadFormatsWithTheSymbolWhereEachLanguagePutsIt() {
        val pub = StoreProfile.QUEBEC_PUB
        assertEquals("fr-CA", pub.tag())
        assertEquals("12,99 $", pub.format(Money(1299)))
        assertEquals("1 234,50 $", pub.format(Money(123450)))
        assertEquals("$12.99", pub.format(Money(1299), LocaleCode.EN))
    }

    @Test
    fun unambiguousDollarsNameTheirCountry() {
        assertEquals("US$12.99", MoneyFormat.format(Money(1299), "USD", "en-US", unambiguous = true))
        assertEquals("CA$12.99", MoneyFormat.format(Money(1299), "CAD", "en-CA", unambiguous = true))
    }

    @Test
    fun theDefaultLanguageIsTheFirstListed() {
        assertEquals(LocaleCode.EN, us.defaultLocale)
        assertEquals("en-US", us.tag())
        assertEquals(LocaleCode.FR, StoreProfile.QUEBEC_PUB.defaultLocale)
    }

    @Test
    fun profilesRefuseMalformedCodes() {
        assertFailsWith<IllegalArgumentException> { us.copy(currency = "usd") }
        assertFailsWith<IllegalArgumentException> { us.copy(country = "USA") }
        assertFailsWith<IllegalArgumentException> { us.copy(locales = emptyList()) }
    }

    @Test
    fun legalAgeComesFromConfigElseTheStoreDefault() {
        assertEquals(21, LegalAge.resolve(21, null))
        assertEquals(18, LegalAge.resolve(21, "18"))
        assertEquals(21, LegalAge.resolve(21, "not-a-number"))
        assertEquals(21, LegalAge.resolve(21, "3")) // nonsense falls back, never fails startup
        assertEquals(19, LegalAge.fromEnv(21) { if (it == LegalAge.ENV) "19" else null })
        val file = Files.createTempFile("store", ".properties").toFile()
        file.writeText("print.receipts=paper\nlegal.age=18\n")
        assertEquals(18, LegalAge.fromFile(21, file))
        assertEquals(18, LegalAge.fromEnv(21) { if (it == ReceiptPrintMode.ENV_CONFIG_FILE) file.path else null })
        assertEquals(21, LegalAge.fromFile(21, null))
    }

    @Test
    fun spanishCatalogShipsCompleteAndNatural() {
        assertEquals("Cambio", Messages.get(MessageKey.RECEIPT_CHANGE, LocaleCode.ES))
        assertEquals("Efectivo", Messages.get(MessageKey.TENDER_CASH, LocaleCode.ES))
        assertEquals(emptySet(), MessageKey.entries.map { it.id }.toSet() - Messages.translatedKeys(LocaleCode.ES))
    }
}
