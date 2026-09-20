package dev.dwhipstock.pos.tools

import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import java.awt.GraphicsEnvironment
import java.io.File
import javax.imageio.ImageIO

/**
 * Standalone French-raster gate for the container image. Renders a sample receipt
 * with the PRODUCTION [ThermalReceiptRenderer] (identical bitmap to a real print,
 * just dumped to PNG instead of shipped over TCP) so we can eyeball whether French
 * glyphs land or fall back to boxes (□□□) when no printer is reachable at build
 * time. Not part of the app; invoked explicitly:
 *
 *   java -Djava.awt.headless=true -cp /app/pos-server-all.jar \
 *     dev.dwhipstock.pos.tools.RasterProbeKt /data/french-probe.png
 *
 * See docs/demo-runbook.md — this is the fallback for the "done is gated on a French
 * test print" rule when the thermal printer isn't on the LAN.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "french-probe.png")

    // Diagnostics: which real French families (if any) fontconfig exposed to the JVM.
    val families = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList()
    }.getOrDefault(emptyList())
    val frenchish = families.filter { f ->
        listOf("French", "Noto", "Roboto", "Loma", "Garuda", "Norasi", "Waree", "TLWG", "Umpush", "Kinnari")
            .any { f.contains(it, ignoreCase = true) }
    }
    println("headless=${GraphicsEnvironment.isHeadless()} fontFamilies=${families.size}")
    println("french-ish families: ${if (frenchish.isEmpty()) "(none — glyphs will rely on SansSerif fallback)" else frenchish.joinToString(", ")}")

    val lines = listOf(
        PrintLine.LogoPlaceholder("The Copper Lantern Pub"),
        PrintLine.Text("French raster probe", Align.CENTER),
        PrintLine.Divider,
        PrintLine.Header("Test de dactylographie français"),
        PrintLine.KeyValue("Cuisse de porc frite ×1", "$450"),
        PrintLine.KeyValue("Bière Maple Oat Stout (bouteille) ×2", "$240"),
        PrintLine.KeyValue("Frais de service 10%", "$69"),
        PrintLine.Divider,
        PrintLine.KeyValue("Total", "$759", emphasized = true),
        PrintLine.Text("Merci d'utiliser le service.", Align.CENTER),
    )
    val img = ThermalReceiptRenderer.renderImage(lines)
    out.absoluteFile.parentFile?.mkdirs()
    ImageIO.write(img, "png", out)
    println("wrote ${img.width}x${img.height} raster → ${out.absolutePath}")
}
