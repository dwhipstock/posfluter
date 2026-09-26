package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.SettingsPatch
import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.KitchenPrintJobs
import dev.dwhipstock.pos.restaurant.KitchenService
import dev.dwhipstock.pos.sdk.EscPosTransport
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.PrinterTarget
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake station printer. Tickets arrive as the 42-column text rendering (the
 * fixture's render seam), so a test can read exactly what each printer got.
 * [down] hosts refuse like an unplugged printer.
 */
class FakeKitchenTransport : EscPosTransport {
    data class Sent(val target: String, val text: String)
    val sent = CopyOnWriteArrayList<Sent>()
    val down = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val attempts = java.util.concurrent.atomic.AtomicInteger()

    override fun send(target: PrinterTarget, bytes: ByteArray) {
        attempts.incrementAndGet()
        if (target.ip in down) throw java.net.ConnectException("Connection refused")
        sent += Sent("${target.ip}:${target.port}", String(bytes, Charsets.UTF_8))
    }

    fun to(host: String) = sent.filter { it.target.startsWith("$host:") }
}

/**
 * A seeded Copper Lantern store with kitchen tickets on, driven directly
 * (no HTTP, no worker thread): the clock and the queue passes are the test's.
 */
class KitchenFixture(venue: CopperLanternVenue = CopperLanternVenue.PLATEAU) {
    val dir: File = Files.createTempDirectory("pos-kitchen").toFile()
    var nowMs = 1_780_000_000_000L
    val transport = FakeKitchenTransport()
    val settings: SettingsRepository
    val checks: CheckService
    val kitchen: KitchenService
    val config: CopperLanternConfig

    init {
        initDatabase(File(dir, "pos.db").path)
        CopperLanternSeed.seedIfEmpty(venue)
        dev.dwhipstock.pos.base.GrantsRepo.seedDefaultRoleGrantsIfEmpty()
        settings = SettingsRepository()
        settings.update(SettingsPatch(printerIp = "10.0.0.9", printerPort = 9100))
        config = CopperLanternConfig(
            venue = venue, settings = settings,
            printer = PrinterAdapter.VirtualPrinter(File(dir, "receipts").path, File(dir, "bills").path),
            publicBaseUrl = "http://127.0.0.1:8080",
        )
        checks = CheckService(config)
        kitchen = KitchenService(config, settings, transport, clockMs = { nowMs },
            render = { lines, _ -> PrinterAdapter.renderText(lines).toByteArray(Charsets.UTF_8) })
        kitchen.ensureDefaults()
        checks.kitchen = kitchen
    }

    fun open(table: String = "t3", user: String = "server1"): Int = checks.openCheck(table, user).id

    fun add(checkId: Int, item: String, variant: String = "$item:regular", qty: Int = 1, note: String? = null): Int =
        checks.addLine(checkId, item, variant, qty, note).lines.last().id

    /** Print everything due; returns the texts printed in this pass. */
    fun drain(): List<FakeKitchenTransport.Sent> {
        val before = transport.sent.size
        kitchen.queue.processDue()
        return transport.sent.drop(before)
    }

    fun jobStatuses(): List<String> = transaction { KitchenPrintJobs.selectAll().map { it[KitchenPrintJobs.status] } }
}
