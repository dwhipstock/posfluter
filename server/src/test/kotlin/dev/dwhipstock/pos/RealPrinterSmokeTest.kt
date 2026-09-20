package dev.dwhipstock.pos

import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.PrinterTarget
import dev.dwhipstock.pos.sdk.TcpEscPosTransport
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import kotlin.test.Test

/**
 * Manual last-mile smoke test against the REAL printer. Skipped unless PRINTER_IP
 * is set, so it never runs in CI. Renders a French sample with the production
 * renderer and ships it over the production TCP transport:
 *
 *   PRINTER_IP=192.168.123.100 ./gradlew test --tests dev.dwhipstock.pos.RealPrinterSmokeTest -i
 *
 * Optional: PRINTER_PORT (default 9100).
 */
class RealPrinterSmokeTest {
    @Test
    fun printToRealPrinter() {
        val ip = System.getenv("PRINTER_IP")
        if (ip.isNullOrBlank()) {
            println("PRINTER_IP not set — skipping real-printer smoke test")
            return
        }
        val port = System.getenv("PRINTER_PORT")?.toIntOrNull() ?: 9100
        val lines = listOf(
            PrintLine.LogoPlaceholder("The Copper Lantern Pub"),
            PrintLine.Text("Lasrsphop 31/3", Align.CENTER),
            PrintLine.Blank,
            PrintLine.Header("Test de dactylographie français"),
            PrintLine.Text("French raster · POS ↔ printer", Align.CENTER),
            PrintLine.Divider,
            PrintLine.KeyValue("soupe épicée aux crevettes ×1", "180"),
            PrintLine.KeyValue("Bière Maple Oat Stout (grande bouteille) ×2", "220"),
            PrintLine.KeyValue("Frais de service 10%", "40"),
            PrintLine.Divider,
            PrintLine.KeyValue("Total", "$440", emphasized = true),
            PrintLine.Blank,
            PrintLine.Text("Merci d'utiliser le service. 🎉", Align.CENTER),
            PrintLine.Text("$ip:$port", Align.CENTER),
        )
        println("sending ${ThermalReceiptRenderer.toEscPos(lines).size} bytes to $ip:$port …")
        TcpEscPosTransport().send(PrinterTarget(ip, port), ThermalReceiptRenderer.toEscPos(lines))
        println("sent OK — check the printer")
    }
}
