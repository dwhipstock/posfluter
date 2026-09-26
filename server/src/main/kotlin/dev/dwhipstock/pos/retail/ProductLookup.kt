package dev.dwhipstock.pos.retail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional help naming an unknown product: an online lookup of its barcode.
 * Best-effort by design — offline, slow or unknown all mean "no suggestion",
 * and the manager types the name. Never on the sale's path: the sale goes on
 * without the product while the manager adds it.
 */
fun interface ProductLookup {
    /** A suggested product name for [barcode], or null. Must not throw. */
    fun suggest(barcode: String): Suggestion?

    data class Suggestion(val name: String, val source: String)

    companion object {
        val NONE = ProductLookup { null }
    }
}

/**
 * Open Food Facts (free, no key): `GET /api/v2/product/{code}.json`. A short
 * timeout keeps a dead network from ever being felt. HttpURLConnection works
 * on the desktop JVM and on Android alike.
 */
class OpenFoodFactsLookup(
    private val baseUrl: String = "https://world.openfoodfacts.org",
    private val timeoutMs: Int = 2500,
) : ProductLookup {
    private val log = LoggerFactory.getLogger(OpenFoodFactsLookup::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override fun suggest(barcode: String): ProductLookup.Suggestion? {
        if (!barcode.all(Char::isDigit) || barcode.length !in 8..14) return null
        return try {
            val conn = URL("$baseUrl/api/v2/product/$barcode.json?fields=product_name,brands,quantity")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "pos-demo-store/0.1 (retail counter)")
            try {
                if (conn.responseCode != 200) return null
                parse(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            log.info("product lookup unavailable (${e.javaClass.simpleName}); manual entry")
            null
        }
    }

    /** `{"status":1,"product":{"product_name":"…","quantity":"12 fl oz"}}` → "…, 12 fl oz". */
    internal fun parse(body: String): ProductLookup.Suggestion? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["status"]?.jsonPrimitive?.contentOrNull != "1") return null
        val product = root["product"]?.jsonObject ?: return null
        val name = product["product_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) return null
        val qty = product["quantity"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val full = if (qty.isEmpty() || qty in name) name else "$name, $qty"
        ProductLookup.Suggestion(full.take(120), "openfoodfacts")
    }.getOrNull()
}
