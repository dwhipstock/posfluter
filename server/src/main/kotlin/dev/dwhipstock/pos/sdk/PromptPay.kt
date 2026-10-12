package dev.dwhipstock.pos.sdk

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Properties

/**
 * PromptPay dynamic QR (Thai QR, EMVCo merchant-presented format): the
 * customer scans it in any Thai banking app and the exact amount is filled in.
 * Confirm-then-record like every [TenderMethod]: there is no callback, the
 * cashier sees the money land in the store's banking app and taps Paid.
 *
 * Payload = TLV stream: 00 payload format, 01 point-of-initiation (12 = dynamic,
 * one-shot with amount), 29 merchant account info (AID A000000677010111 + proxy),
 * 53 currency 764 (THB), 54 amount, 58 country TH, 59/60 name/city, 63 CRC-16.
 */
data class PromptPayMethod(
    val proxy: PromptPayProxy,
    /** The account name shown under the QR (the store's own words, any script). */
    val accountName: String,
    val merchantCity: String = "BANGKOK",
) : TenderMethod {
    override val type = TenderType.PROMPTPAY
    // only ever offered in a THB store, whose *_fr data slot holds Thai
    override val labelFr = "พร้อมเพย์"
    override val labelEn = "PromptPay"

    override fun instructions(amount: Money) = TenderInstructions(
        type = type.name,
        amountCents = amount.cents,
        qrPayload = buildPayload(amount),
        displayFields = listOfNotNull(
            accountName.takeIf { it.isNotBlank() }?.let { DisplayField("ชื่อบัญชี", "Account name", it) },
        ),
    )

    fun buildPayload(amount: Money): String {
        require(amount.cents > 0) { "PromptPay amount must be positive" }
        val merchantInfo = tlv("00", AID) + tlv(proxy.subTag, proxy.encoded())
        val amountBaht = "%d.%02d".format(amount.cents / 100, amount.cents % 100)
        // tag lengths count characters, so a Thai account name would not scan:
        // name + city only when the name is plain ASCII (the bank app shows the
        // account holder's registered name either way)
        val qrName = accountName.trim().takeIf { it.isNotEmpty() && it.all { c -> c.code in 0x20..0x7E } }
        val body = tlv("00", "01") +          // payload format indicator
            tlv("01", "12") +                 // dynamic (amount embedded, single use)
            tlv("29", merchantInfo) +         // merchant account information
            tlv("53", "764") +                // THB
            tlv("54", amountBaht) +
            tlv("58", "TH") +
            (qrName?.let { tlv("59", it.take(25)) + tlv("60", merchantCity.take(15)) } ?: "") +
            "6304"                            // CRC tag+len, CRC computed over this too
        return body + crc16ccitt(body)
    }

    private fun tlv(tag: String, value: String) = tag + "%02d".format(value.length) + value

    companion object {
        const val AID = "A000000677010111"

        /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, uppercase hex. */
        fun crc16ccitt(data: String): String {
            var crc = 0xFFFF
            for (byte in data.toByteArray(Charsets.UTF_8)) {
                crc = crc xor (byte.toInt() and 0xFF shl 8)
                repeat(8) {
                    crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                    crc = crc and 0xFFFF
                }
            }
            return "%04X".format(crc)
        }
    }
}

/** PromptPay proxy id: phone (tag 01) or national id / tax id (02). */
sealed interface PromptPayProxy {
    val subTag: String
    fun encoded(): String

    data class Phone(val phone: String) : PromptPayProxy {
        override val subTag = "01"
        /** 0xx-xxx-xxxx → 0066 + 9 digits (leading zero dropped), 13 chars. */
        override fun encoded(): String {
            val digits = phone.filter { it.isDigit() }
            require(digits.length == 10 && digits.startsWith("0")) { "expected 10-digit Thai phone" }
            return "0066" + digits.drop(1)
        }
    }

    data class NationalId(val id: String) : PromptPayProxy {
        override val subTag = "02"
        override fun encoded(): String {
            val digits = id.filter { it.isDigit() }
            require(digits.length == 13) { "expected 13-digit national/tax id" }
            return digits
        }
    }

    companion object {
        /**
         * A configured id: a Thai mobile (0812345678, 081-234-5678, +66812345678)
         * or a 13-digit national / tax id. Anything else is null.
         */
        fun parse(raw: String?): PromptPayProxy? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty() || s.any { !it.isDigit() && it !in " -+" }) return null
            val digits = s.filter { it.isDigit() }
            return when {
                digits.length == 13 && !s.startsWith("+") -> NationalId(digits)
                digits.length == 10 && digits.startsWith("0") -> Phone(digits)
                digits.length == 11 && digits.startsWith("66") -> Phone("0" + digits.drop(2))
                else -> null
            }
        }
    }
}

/**
 * The PromptPay tender (store config): `promptpay.id` (a Thai mobile number or
 * a 13-digit national / tax id) and `promptpay.name` (the account name shown
 * under the QR). Offered only when the store's currency is THB and the id is
 * valid ([withPromptPay]); unset = no PromptPay, the store as before.
 *
 * `POS_PROMPTPAY_ID` / `POS_PROMPTPAY_NAME` (desktop / docker) win, else the
 * `POS_CONFIG_FILE` properties file; the tablet passes the keys from its
 * store.properties. Local config only; a bad id turns PromptPay off with a
 * warning and never fails startup. The id is never logged in full.
 */
object PromptPayConfig {
    const val KEY_ID = "promptpay.id"
    const val KEY_NAME = "promptpay.name"
    const val ENV_ID = "POS_PROMPTPAY_ID"
    const val ENV_NAME = "POS_PROMPTPAY_NAME"

    /** The setting in effect plus where it came from, for the startup log. */
    data class Resolved(
        val proxy: PromptPayProxy?,
        val name: String = "",
        val source: String = "default",
        val warning: String? = null,
    ) {
        val configured: Boolean get() = proxy != null
        fun method(): PromptPayMethod? = proxy?.let { PromptPayMethod(it, name) }
        fun describe(): String = when (val p = proxy) {
            null -> "PromptPay: off ($source)"
            else -> "PromptPay: on, ${if (p is PromptPayProxy.Phone) "phone" else "national/tax id"} " +
                "ending ${p.encoded().takeLast(4)} ($source)"
        }
    }

    val OFF = Resolved(null)

    /** Raw values (null = unset) as the setting in effect. */
    fun resolve(rawId: String?, rawName: String?, source: String): Resolved {
        if (rawId.isNullOrBlank()) return OFF
        val name = utf8Recovered(rawName?.trim().orEmpty())
        return PromptPayProxy.parse(rawId)?.let { Resolved(it, name, source) }
            ?: Resolved(null, source = "default",
                warning = "$source: invalid $KEY_ID (expected a 10-digit Thai mobile or a 13-digit national/tax id)")
    }

    /** Tablet: the store.properties already loaded (null = no file → off). */
    fun fromProperties(props: Properties?, source: String = "store.properties"): Resolved =
        if (props == null) OFF else resolve(props.getProperty(KEY_ID), props.getProperty(KEY_NAME), source)

    fun fromFile(file: File?): Resolved {
        if (file == null || !file.exists()) return OFF
        val props = try {
            Properties().apply { file.inputStream().use(::load) }
        } catch (e: Exception) {
            return Resolved(null, warning = "${file.path}: unreadable (${e.message})")
        }
        return fromProperties(props, file.path)
    }

    fun fromEnv(env: (String) -> String? = System::getenv): Resolved {
        env(ENV_ID)?.takeIf { it.isNotBlank() }?.let { return resolve(it, env(ENV_NAME), ENV_ID) }
        return fromFile(env(ReceiptPrintMode.ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File))
    }

    /**
     * java.util.Properties reads a file as ISO-8859-1, so a Thai account name
     * typed into store.properties arrives as UTF-8 bytes read one by one. Undo
     * that when it decodes cleanly; plain ASCII and \u escapes pass unchanged.
     */
    internal fun utf8Recovered(s: String): String {
        if (s.all { it.code < 0x80 } || s.any { it.code > 0xFF }) return s
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(s.toByteArray(Charsets.ISO_8859_1))).toString()
        } catch (_: CharacterCodingException) {
            s
        }
    }
}

/**
 * [config] with PromptPay added to its electronic tenders when [promptPay] is
 * configured and the store sells in THB; otherwise [config] itself.
 */
fun withPromptPay(config: CustomerConfig, promptPay: PromptPayConfig.Resolved): CustomerConfig {
    val method = promptPay.method() ?: return config
    if (config.profile.currency != "THB") return config
    return object : CustomerConfig by config {
        override val electronicTenders get() = config.electronicTenders.filter { it.type != TenderType.PROMPTPAY } + method
        override fun tenderMethod(type: TenderType) = electronicTenders.firstOrNull { it.type == type }
    }
}
