package dev.dwhipstock.pos.sdk.i18n

import dev.dwhipstock.pos.StoreAssets
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.net.JarURLConnection
import java.util.Properties

/**
 * Open locale representation — a lowercase language tag ("fr", "en", …),
 * not a closed enum. Which locales actually exist is decided by the resource
 * files [Messages] discovers at startup, so adding a language is a file drop.
 */
@JvmInline
value class LocaleCode(val tag: String) {
    override fun toString() = tag

    companion object {
        val FR = LocaleCode("fr")
        val EN = LocaleCode("en")
        val ES = LocaleCode("es")

        /** Normalize a stored/user-supplied code ("EN ", "Fr") to a lowercase tag. */
        fun of(raw: String) = LocaleCode(raw.trim().lowercase())
    }
}

/**
 * Type-safe keys for every server-rendered message (receipts, bills, refund
 * and till slips). Code references these constants; the translations live in
 * `src/main/resources/i18n/messages_<tag>.properties` (UTF-8). Adding a
 * language is dropping in a new `messages_<tag>.properties` with these keys —
 * zero call-site changes; missing keys fall back to the default locale.
 */
enum class MessageKey(val id: String) {
    RECEIPT_TABLE("receipt.table"),
    RECEIPT_BILL("receipt.bill"),
    RECEIPT_OPEN("receipt.open"),
    RECEIPT_CLOSE("receipt.close"),
    RECEIPT_PRINTED_AT("receipt.printed_at"),
    RECEIPT_TOTAL("receipt.total"),
    /** {0} = sales-tax rate percent. */
    RECEIPT_TAX_INCLUDED("receipt.tax_included"),
    RECEIPT_SUBTOTAL("receipt.subtotal"),
    /** {0} = tax name ("GST/TPS"), {1} = rate percent ("9.975"). */
    RECEIPT_TAX_LINE("receipt.tax_line"),
    /** {0} = tax name, {1} = the venue's registration number for it. */
    RECEIPT_TAX_REGISTRATION("receipt.tax_registration"),
    RECEIPT_ROUNDING("receipt.rounding"),
    /** A cash payment's amount after nickel rounding (also on the bill). */
    RECEIPT_CASH_TOTAL("receipt.cash_total"),
    RECEIPT_CHANGE("receipt.change"),
    RECEIPT_BILL_BANNER("receipt.bill_banner"),
    RECEIPT_NOT_A_RECEIPT("receipt.not_a_receipt"),
    // retail counter receipts: "Register 1 · Sale #12" instead of table / bill
    RECEIPT_REGISTER("receipt.register"),
    RECEIPT_SALE("receipt.sale"),
    /** {0} = a bill / sale / refund number: "#12", "n° 12". */
    RECEIPT_NUMBER("receipt.number"),
    /** {0} = the legal age the customer's ID was checked against (21). */
    RECEIPT_AGE_VERIFIED("receipt.age_verified"),
    REFUND_HEADER("refund.header"),
    REFUND_REF_BILL("refund.ref_bill"),
    REFUND_NUMBER("refund.number"),
    REFUND_TOTAL("refund.total"),
    REFUND_VIA("refund.via"),
    /** Cash actually handed back on a cash refund (rounded to the nickel). */
    REFUND_CASH_BACK("refund.cash_back"),
    TENDER_CASH("tender.cash"),
    SLIP_TIME("slip.time"),
    SLIP_REASON("slip.reason"),
    CASH_IN_HEADER("cash.in_header"),
    CASH_OUT_HEADER("cash.out_header"),
    CASH_IN("cash.in"),
    CASH_OUT("cash.out"),
    // guest Wi-Fi join slip, and the two-step table slip when Wi-Fi is set up
    WIFI_FREE("wifi.free"),
    WIFI_SCAN_TO_CONNECT("wifi.scan_to_connect"),
    WIFI_NETWORK("wifi.network"),
    WIFI_PASSWORD("wifi.password"),
    WIFI_NO_PASSWORD("wifi.no_password"),
    SLIP_STEP_JOIN_WIFI("slip.step_join_wifi"),
    SLIP_STEP_SCAN_TO_ORDER("slip.step_scan_to_order"),
    /** The one-QR table slip (no guest Wi-Fi): the caption under the menu QR. */
    SLIP_SCAN_TO_ORDER("slip.scan_to_order"),
}

/**
 * The message catalog: locale tag → (key id → template). Bundles are
 * discovered on the classpath under `i18n/` at first use; [ensureLoaded] from
 * application startup makes the load happen at boot and REFUSES TO BOOT when
 * the default locale's catalog is broken — a packaging regression must die at
 * deploy time, not print raw key ids on customer receipts. A broken OPTIONAL
 * locale file, by contrast, is skipped with a warning: a bad drop-in degrades
 * that locale only.
 */
object Messages {
    private val log = LoggerFactory.getLogger(Messages::class.java)
    private val fileName = Regex("""messages_([a-z0-9-]+)\.properties""")
    private val nearMiss = Regex("""(?i)messages[_-].+\.properties""")

    /** Fallback for keys a locale hasn't translated, and for unknown locales. */
    val defaultLocale: LocaleCode = LocaleCode.EN

    private val bundles: Map<String, Map<String, String>> by lazy {
        StoreAssets.list("i18n")?.let { names ->
            names.filter { fileName.matches(it) }.associate { name ->
                val tag = fileName.matchEntire(name)!!.groupValues[1]
                val properties = Properties()
                StoreAssets.readText("i18n/$name").reader().use(properties::load)
                tag to properties.entries.associate { (key, value) -> key.toString() to value.toString() }
            }
        } ?: loadBundles(Messages::class.java.classLoader)
    }

    fun supportedTags(): Set<String> = bundles.keys

    fun supports(locale: LocaleCode) = locale.tag in bundles

    /** Key ids a locale actually translates (no fallback) — translator/QA visibility. */
    fun translatedKeys(locale: LocaleCode): Set<String> =
        bundles[locale.tag]?.keys?.filterNot { it.startsWith(DATA_PREFIX) }?.toSet() ?: emptySet()

    /** Catalog entries that translate DATA labels, under `data.` (see [dataLabel]). */
    const val DATA_PREFIX = "data."

    /**
     * An optional translation of a DATA label — a tax, fee or tender name the
     * store's config carries in French and English only — into [locale]:
     * `data.tax.US_SALES=Impuesto sobre la venta` in messages_es. Only [locale]
     * itself is consulted (no default-locale fallback), so a key in one pack
     * can never change another language's receipts. Null = not translated.
     */
    fun dataLabel(id: String, locale: LocaleCode): String? = bundles[locale.tag]?.get(DATA_PREFIX + id)

    /**
     * Force catalog discovery at startup. Throws (failing boot) when the
     * default locale is absent or incomplete — every receipt would otherwise
     * silently render raw key ids.
     */
    fun ensureLoaded(): Set<String> {
        val missing = MessageKey.entries.map { it.id } - (bundles[defaultLocale.tag] ?: emptyMap()).keys
        check(missing.isEmpty()) {
            "i18n: default locale '${defaultLocale.tag}' is missing ${missing.size} message key(s) " +
                "${missing.sorted()} — refusing to start rather than print raw key ids on receipts"
        }
        return supportedTags()
    }

    /**
     * Resolve [key] for [locale]; a locale/key without a translation falls back
     * to [defaultLocale], and as a last resort renders the key id — a partially
     * translated language never blanks a receipt line or throws.
     */
    fun get(key: MessageKey, locale: LocaleCode, vararg args: Any): String {
        val template = bundles[locale.tag]?.get(key.id)
            ?: bundles[defaultLocale.tag]?.get(key.id)
            ?: key.id
        var out = template
        args.forEachIndexed { i, arg -> out = out.replace("{$i}", arg.toString()) }
        return out
    }

    /**
     * Classpath discovery, isolated per root and per file: one unreadable
     * catalog or classpath root degrades itself, never fr/en receipts (the
     * default-locale hard gate lives in [ensureLoaded]). Internal + parameterized
     * so tests can exercise the jar branch with synthetic jars.
     */
    internal fun loadBundles(classLoader: ClassLoader): Map<String, Map<String, String>> {
        val found = sortedMapOf<String, MutableMap<String, String>>()

        // First definition of a key wins, matching ClassLoader.getResource's
        // classpath-order convention (so test resources override main in tests).
        fun add(tag: String, source: String, open: () -> InputStream) {
            val loaded = try {
                open().use { read(it) }
            } catch (e: Exception) {
                log.warn("i18n: failed to read $source — file skipped: ${e.message}")
                return
            }
            val bundle = found.getOrPut(tag) { mutableMapOf() }
            val redefined = loaded.keys.count { it in bundle }
            loaded.forEach { (k, v) -> bundle.putIfAbsent(k, v) }
            if (redefined > 0) {
                log.info("i18n: $source redefines $redefined key(s) for '$tag' — first definition wins")
            }
        }

        // Strict names load; near-misses (messages_fr_CA.properties — the Java
        // ResourceBundle convention) warn instead of vanishing without a trace.
        fun tagOf(name: String, source: String): String? {
            fileName.matchEntire(name)?.let { return it.groupValues[1] }
            if (nearMiss.matches(name)) {
                log.warn("i18n: $source looks like a catalog but is not named messages_<lowercase-tag>.properties — ignored")
            }
            return null
        }

        // getResources (plural): i18n can exist in several classpath roots (main
        // + test resources, or several jars) — merge them all. The directory URL
        // covers exploded classpaths; the anchor file covers jars whose builder
        // skipped directory entries (fat-jar prod would otherwise silently ship
        // with NO catalogs). Both may point at the same container — dedupe.
        val urls = try {
            classLoader.getResources("i18n").toList() +
                classLoader.getResources("i18n/messages_${defaultLocale.tag}.properties").toList()
        } catch (e: Exception) {
            log.error("i18n: classpath scan failed: ${e.message}")
            emptyList()
        }
        val scannedDirs = mutableSetOf<String>()
        val scannedJars = mutableSetOf<String>()
        for (url in urls) {
            try {
                when (url.protocol) {
                    "file" -> {
                        val target = File(url.toURI())
                        val dir = if (target.isDirectory) target else target.parentFile
                        if (dir != null && scannedDirs.add(dir.canonicalPath)) {
                            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                                tagOf(f.name, f.path)?.let { tag -> add(tag, f.path) { f.inputStream() } }
                            }
                        }
                    }
                    "jar" -> {
                        val conn = url.openConnection() as JarURLConnection
                        conn.useCaches = false // we own this JarFile, safe to close
                        conn.jarFile.use { jar ->
                            if (scannedJars.add(jar.name)) {
                                for (entry in jar.entries().asSequence().sortedBy { it.name }) {
                                    // only i18n/messages_*.properties: the fat jar merges every
                                    // dependency's resources, and a library's root-level
                                    // messages_*.properties must not become a receipt catalog
                                    if (!entry.name.startsWith("i18n/")) continue
                                    val source = "${jar.name}!/${entry.name}"
                                    tagOf(entry.name.removePrefix("i18n/"), source)
                                        ?.let { tag -> add(tag, source) { jar.getInputStream(entry) } }
                                }
                            }
                        }
                    }
                    else -> log.warn("i18n: unsupported classpath protocol '${url.protocol}' for $url — skipped")
                }
            } catch (e: Exception) {
                log.warn("i18n: failed to scan $url — root skipped: ${e.message}")
            }
        }
        val allIds = MessageKey.entries.map { it.id }.toSet()
        found.forEach { (tag, messages) ->
            val missing = allIds - messages.keys
            val unknown = messages.keys.filterNot { it.startsWith(DATA_PREFIX) }.toSet() - allIds
            if (missing.isNotEmpty()) {
                log.warn("i18n: locale '$tag' is missing ${missing.size} key(s) — they fall back to '${defaultLocale.tag}': ${missing.sorted()}")
            }
            if (unknown.isNotEmpty()) {
                log.warn("i18n: locale '$tag' has ${unknown.size} key(s) no code references (typo?): ${unknown.sorted()}")
            }
        }
        if (defaultLocale.tag !in found) {
            log.error("i18n: default locale '${defaultLocale.tag}' has no messages bundle — keys will render as raw ids")
        }
        log.info("i18n: loaded locales ${found.keys.toList()} (default '${defaultLocale.tag}')")
        return found
    }

    private fun read(stream: InputStream): Map<String, String> {
        val props = Properties()
        props.load(stream.reader(Charsets.UTF_8))
        return props.entries.associate { (k, v) -> k.toString() to v.toString() }
    }
}

private val LocaleCode.prefersEnglishData: Boolean
    get() = tag == LocaleCode.EN.tag || (tag != LocaleCode.FR.tag && Messages.defaultLocale.tag == LocaleCode.EN.tag)

/**
 * Bilingual DATA fields (item names, variant/fee/tender labels): the database
 * carries fr+en columns only, so a locale beyond that pair renders the default
 * locale's side. Seam: per-locale data needs a translations table, not more
 * columns — until then this is the single place that encodes the pair.
 */
fun LocaleCode.dataText(fr: String, en: String): String = if (prefersEnglishData) en else fr

/** [dataText] for nullable pairs (e.g. variant labels) — picks the side as-is. */
fun LocaleCode.dataTextOrNull(fr: String?, en: String?): String? = if (prefersEnglishData) en else fr
