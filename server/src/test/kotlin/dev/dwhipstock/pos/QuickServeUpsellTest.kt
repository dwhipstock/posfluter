package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternExpressSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.KioskOrderLine
import dev.dwhipstock.pos.restaurant.KioskOrderRequest
import dev.dwhipstock.pos.restaurant.KioskUpsellRequest
import dev.dwhipstock.pos.restaurant.QuickServeService
import dev.dwhipstock.pos.restaurant.QuickServeUpsell
import dev.dwhipstock.pos.restaurant.ShiftService
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.UpsellConfig
import dev.dwhipstock.pos.sdk.UpsellRule
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The kiosk's "Add a drink?" step: a main without a drink gets soft drinks,
 * without a side fries, without a dessert desserts — two rows at most, only
 * what is on sale, never alcohol.
 */
class QuickServeUpsellTest {
    private data class Store(val qs: QuickServeService, val checks: CheckService)

    private fun store(): Store {
        val dir = Files.createTempDirectory("pos-upsell").toFile()
        initDatabase(File(dir, "pos.db").path)
        CopperLanternExpressSeed.seedIfEmpty()
        val config = CopperLanternConfig(
            venue = CopperLanternVenue.EXPRESS, settings = SettingsRepository(),
            printer = PrinterAdapter.VirtualPrinter(File(dir, "r").path, File(dir, "b").path),
            publicBaseUrl = "http://127.0.0.1:8080",
        )
        val checks = CheckService(config)
        ShiftService(config).openShift("manager", 0)
        return Store(QuickServeService(config, checks).also { it.ensureCounter() }, checks)
    }

    private fun line(item: String, variant: String = "regular") = KioskOrderLine(item, "$item:$variant")
    private val burger = line("lantern-burger")
    private val soda = line("fountain-soda", "medium")
    private val fries = line("late-fries", "small")
    private val brownie = line("brownie")
    private val lager = line("lantern-lager", "16oz")

    private fun Store.rows(vararg lines: KioskOrderLine) = qs.kioskUpsell(KioskUpsellRequest(lines.toList())).rows

    private fun alcoholIds(): Set<String> = transaction {
        Items.selectAll().where { Items.isAlcohol eq true }.map { it[Items.id] }.toSet()
    }

    @Test
    fun `a main without a drink gets soft drinks first, then fries - two rows at most`() {
        val s = store()
        val rows = s.rows(burger)
        assertEquals(listOf("drink", "side"), rows.map { it.reason }, "the dessert row is the third: left out")
        assertEquals("soft-drinks", rows[0].categoryId)
        assertEquals(listOf("fountain-soda", "lemonade", "iced-tea", "sparkling-water"), rows[0].itemIds,
            "no sales yet: the menu's order, four at most")
        assertEquals("fries-sides", rows[1].categoryId)
        assertEquals(listOf("late-fries", "poutine", "onion-rings"), rows[1].itemIds)
        // chicken and salads are mains too
        assertEquals("drink", s.rows(line("wings", "6pc")).first().reason)
        assertEquals("drink", s.rows(line("caesar-salad")).first().reason)
    }

    @Test
    fun `an order with a drink gets no drinks, and one with everything gets nothing`() {
        val s = store()
        assertEquals(listOf("side", "dessert"), s.rows(burger, soda).map { it.reason })
        assertEquals(listOf("dessert"), s.rows(burger, soda, fries).map { it.reason })
        assertTrue(s.rows(burger, soda, fries, brownie).isEmpty())
        // a beer is the drink: no soft drink pushed on top of it
        assertEquals(listOf("side", "dessert"), s.rows(burger, lager).map { it.reason })
    }

    @Test
    fun `no main, no suggestions`() {
        val s = store()
        assertTrue(s.rows(fries).isEmpty())
        assertTrue(s.rows(brownie, soda).isEmpty())
        assertTrue(s.rows().isEmpty())
    }

    @Test
    fun `alcohol is never offered, even when a venue's config points at beer and wine`() {
        val s = store()
        val alcohol = alcoholIds()
        assertTrue(alcohol.isNotEmpty())
        assertTrue(s.rows(burger).flatMap { it.itemIds }.none { it in alcohol })
        // a careless config that offers the beer-and-wine category still gets none of it
        val careless = QuickServeUpsell({
            UpsellConfig(setOf("burgers"), listOf(UpsellRule("drink", offer = listOf("beer-wine", "soft-drinks"))))
        })
        val rows = careless.suggest(listOf(burger)).rows
        assertEquals(1, rows.size)
        assertTrue(rows.single().itemIds.isNotEmpty())
        assertTrue(rows.single().itemIds.none { it in alcohol }, rows.toString())
        // a category of nothing but alcohol: no row at all
        val beerOnly = QuickServeUpsell({ UpsellConfig(setOf("burgers"), listOf(UpsellRule("drink", listOf("beer-wine")))) })
        assertTrue(beerOnly.suggest(listOf(burger)).rows.isEmpty())
    }

    @Test
    fun `items off sale or deleted are left out, and an empty row is dropped`() {
        val s = store()
        transaction {
            Items.update({ Items.id eq "lemonade" }) { it[active] = false }
            Items.update({ Items.id eq "iced-tea" }) { it[deletedAt] = java.time.Instant.now() }
        }
        assertEquals(listOf("fountain-soda", "sparkling-water"), s.rows(burger).first().itemIds)
        // every side 86'ed: the dessert row moves up
        transaction {
            for (id in listOf("late-fries", "poutine", "onion-rings")) Items.update({ Items.id eq id }) { it[active] = false }
        }
        assertEquals(listOf("drink", "dessert"), s.rows(burger).map { it.reason })
    }

    @Test
    fun `the week's best sellers come first`() {
        val s = store()
        repeat(2) {
            val order = s.qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(burger, line("sparkling-water"))), "Door 1")
            s.checks.tenderCash(order.checkId, 100_000)
            s.checks.finalizeCheck(order.checkId)
        }
        assertEquals("sparkling-water", s.rows(burger).first().itemIds.first())
    }

    @Test
    fun `a venue with no rules, like the pubs, suggests nothing`() {
        val s = store()
        assertTrue(QuickServeUpsell({ UpsellConfig.NONE }).suggest(listOf(burger)).rows.isEmpty())
        assertEquals(UpsellConfig.NONE, CopperLanternConfig(venue = CopperLanternVenue.PLATEAU, settings = SettingsRepository(),
            printer = PrinterAdapter.VirtualPrinter("r", "b"), publicBaseUrl = "http://x").upsell)
        assertTrue(s.rows(burger).isNotEmpty())
    }
}
