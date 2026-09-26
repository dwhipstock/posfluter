package dev.dwhipstock.pos

import java.io.InputStream

/** Resource seam: classpath on desktop, APK assets when embedded on Android. */
object StoreAssets {
    interface Source {
        fun open(path: String): InputStream
        fun list(path: String): List<String>
    }

    @Volatile private var source: Source? = null

    fun install(value: Source) {
        source = value
    }

    fun list(path: String): List<String>? = source?.list(path)

    /** Null when the resource is missing (a font the page asked for by name). */
    fun readBytesOrNull(path: String): ByteArray? =
        (source?.let { runCatching { it.open(path) }.getOrNull() }
            ?: StoreAssets::class.java.classLoader.getResourceAsStream(path))?.use { it.readBytes() }

    fun readText(path: String): String =
        (source?.open(path) ?: checkNotNull(StoreAssets::class.java.classLoader.getResourceAsStream(path)) {
            "missing store resource: $path"
        }).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
