package dev.dwhipstock.pos.payments.jpm

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.SocketFactory

/**
 * A minimal RFC 6455 WebSocket client: text frames, ping/pong, close,
 * fragmented messages. Enough for the J.P. Morgan Payment Terminal Application,
 * which speaks JSON text messages over one persistent (TLS) WebSocket. Plain
 * JDK sockets, so it builds into the tablet APK with no extra dependency.
 *
 * Not thread-safe for concurrent reads; [send] is synchronized so a Cancel can
 * be written while another thread waits in [receive].
 */
class MiniWebSocket private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) : Closeable {
    private val random = SecureRandom()
    @Volatile var open: Boolean = true
        private set

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Open `ws(s)://host:port/path` through [factory] (an SSLSocketFactory for wss). */
        fun connect(host: String, port: Int, path: String = "/", factory: SocketFactory, connectTimeoutMs: Int = 5_000): MiniWebSocket {
            val socket = factory.createSocket()
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = connectTimeoutMs
                if (socket is javax.net.ssl.SSLSocket) socket.startHandshake()
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
                val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.flush()
                val head = readHead(input)
                val lines = head.split("\r\n")
                if (lines.firstOrNull()?.contains(" 101 ") != true) throw IOException("WebSocket upgrade refused: ${lines.firstOrNull()}")
                val accept = lines.drop(1).mapNotNull { l -> l.split(":", limit = 2).takeIf { it.size == 2 } }
                    .firstOrNull { it[0].trim().equals("Sec-WebSocket-Accept", ignoreCase = true) }?.get(1)?.trim()
                val expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))
                if (accept != expected) throw IOException("WebSocket upgrade: bad Sec-WebSocket-Accept")
                return MiniWebSocket(socket, input, output)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e as? IOException ?: IOException(e.message, e)
            }
        }

        private fun readHead(input: InputStream): String {
            val buf = ByteArrayOutputStream()
            var matched = 0
            val end = byteArrayOf(13, 10, 13, 10)
            while (matched < 4) {
                val b = input.read()
                if (b < 0) throw IOException("connection closed during WebSocket upgrade")
                buf.write(b)
                matched = if (b.toByte() == end[matched]) matched + 1 else if (b == 13) 1 else 0
                if (buf.size() > 16_384) throw IOException("WebSocket upgrade response too large")
            }
            return buf.toString(Charsets.US_ASCII.name())
        }
    }

    fun send(text: String) = synchronized(this) { writeFrame(0x1, text.toByteArray(Charsets.UTF_8)) }

    /**
     * The next text message, or null if none arrives within [timeoutMs].
     * Pings are answered; a close frame (or EOF) closes this socket and throws.
     */
    fun receive(timeoutMs: Int): String? {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        val message = ByteArrayOutputStream()
        var inText = false
        while (true) {
            val b0 = try { input.read() } catch (_: SocketTimeoutException) { return if (inText) null else null }
            if (b0 < 0) { open = false; throw IOException("WebSocket closed by the terminal") }
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            val b1 = readByte()
            val masked = b1 and 0x80 != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte().toLong() } }
            if (len > 4_000_000) throw IOException("WebSocket frame too large")
            val mask = if (masked) ByteArray(4) { readByte().toByte() } else null
            val payload = readFully(len.toInt())
            mask?.let { m -> for (i in payload.indices) payload[i] = (payload[i].toInt() xor m[i % 4].toInt()).toByte() }
            when (opcode) {
                0x1, 0x0 -> { inText = true; message.write(payload); if (fin) return message.toString(Charsets.UTF_8.name()) }
                0x8 -> { runCatching { synchronized(this) { writeFrame(0x8, ByteArray(0)) } }; close(); throw IOException("WebSocket closed by the terminal") }
                0x9 -> synchronized(this) { writeFrame(0xA, payload) }
                else -> {} // pong / binary: ignored
            }
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) { open = false; throw IOException("WebSocket closed mid-frame") }
        return b
    }

    private fun readFully(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) { open = false; throw IOException("WebSocket closed mid-frame") }
            off += r
        }
        return out
    }

    /** Client frames are always masked (RFC 6455 §5.3). */
    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val frame = ByteArrayOutputStream()
        frame.write(0x80 or opcode)
        when {
            payload.size < 126 -> frame.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> { frame.write(0x80 or 126); frame.write(payload.size shr 8); frame.write(payload.size and 0xFF) }
            else -> { frame.write(0x80 or 127); for (i in 7 downTo 0) frame.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        frame.write(mask)
        frame.write(ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() })
        output.write(frame.toByteArray())
        output.flush()
    }

    override fun close() {
        open = false
        runCatching { socket.close() }
    }
}
