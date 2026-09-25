package dev.dwhipstock.pos.sdk

import java.io.File
import java.util.Properties

/**
 * Where sale paperwork goes (store config file, no UI): `print.receipts=paper|digital`.
 *
 * - [PAPER] (default): closed-check receipts and provisional bills also go to the
 *   thermal printer, as always.
 * - [DIGITAL]: they are only written by the virtual/audit printer (receipts/ and
 *   bills/ spool + receipt.printed outbox). Saves paper while testing.
 *
 * Manual prints (test page, table QR slips, other ad-hoc slips) always go to paper.
 * Purely local config: never depends on the network.
 */
enum class ReceiptPrintMode {
    PAPER, DIGITAL;

    val wire: String get() = name.lowercase()

    /** The mode in effect plus where it came from, for the startup log. */
    data class Resolved(val mode: ReceiptPrintMode, val source: String, val warning: String? = null)

    companion object {
        const val KEY = "print.receipts"
        const val ENV = "POS_PRINT_RECEIPTS"
        const val ENV_CONFIG_FILE = "POS_CONFIG_FILE"

        /** `paper` / `digital` (case/space-insensitive); anything else is null. */
        fun parse(raw: String?): ReceiptPrintMode? = when (raw?.trim()?.lowercase()) {
            "paper" -> PAPER
            "digital" -> DIGITAL
            else -> null
        }

        private fun fromValue(raw: String?, source: String): Resolved =
            if (raw == null) Resolved(PAPER, "default")
            else parse(raw)?.let { Resolved(it, source) }
                ?: Resolved(PAPER, "default", "$source: invalid $KEY='$raw' (expected paper|digital)")

        /**
         * Read [KEY] from a store properties file. A missing file or key means
         * the default; an unreadable file or bad value falls back to [PAPER] with
         * a [Resolved.warning]. Never throws: startup must not fail on this.
         */
        fun fromFile(file: File?): Resolved {
            if (file == null || !file.exists()) return Resolved(PAPER, "default")
            val props = try {
                Properties().apply { file.inputStream().use(::load) }
            } catch (e: Exception) {
                return Resolved(PAPER, "default", "${file.path}: unreadable (${e.message})")
            }
            return fromValue(props.getProperty(KEY), file.path)
        }

        /** Desktop / docker: [ENV] wins, else the [ENV_CONFIG_FILE] properties file. */
        fun fromEnv(env: (String) -> String? = System::getenv): Resolved {
            env(ENV)?.takeIf { it.isNotBlank() }?.let { return fromValue(it, ENV) }
            return fromFile(env(ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File))
        }
    }
}
