package dev.dwhipstock.pos

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Android app compiles these same store sources into the tablet's APK
 * (client/android/app/build.gradle.kts, `stageEmbeddedStoreSources`), but CI
 * builds them only for the JVM. An API the JVM has and Android lacks compiles
 * here and breaks the APK: the forecourt adapter once used java.net.http and
 * CI stayed green. This fails when an embedded source uses one of those APIs.
 * Desktop-only files are the ones the Android build excludes; the list is read
 * from that build file, so the two cannot drift.
 */
class AndroidCompatTest {

    /** JVM APIs (and JVM-only libraries) that do not exist on Android. */
    private val banned = listOf(
        "java.net.http" to "use HttpURLConnection",
        "java.awt" to "Java2D is desktop-only; keep it in an excluded file (sdk/Images.kt, EscPos.kt)",
        "javax.imageio" to "desktop-only",
        "javax.swing" to "desktop-only",
        "java.lang.management" to "desktop-only",
        "javax.management" to "desktop-only",
        "java.lang.ProcessHandle" to "desktop-only",
        "io.ktor.server.netty" to "the tablet runs Ktor on CIO",
        "ch.qos.logback" to "the tablet logs through slf4j-simple",
        "com.google.zxing.client.j2se" to "zxing javase is not in the Android build",
    )

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "client/android/app/build.gradle.kts").exists() }

    /** The `exclude(...)` globs of stageEmbeddedStoreSources in the Android build. */
    private fun androidExcludes(): List<Regex> {
        val gradle = File(repo, "client/android/app/build.gradle.kts").readText()
        val block = gradle.substringAfter("stageEmbeddedStoreSources").substringBefore("into(")
        val globs = Regex("\"([^\"]+)\"").findAll(block.substringAfter("exclude(").substringBefore(")"))
            .map { it.groupValues[1] }.toList()
        assertTrue(globs.isNotEmpty(), "could not read the embedded-store excludes from the Android build")
        return globs.map { glob ->
            Regex(glob.replace(".", "\\.").replace("**/", "\u0001").replace("**", "\u0002").replace("*", "[^/]*")
                .replace("\u0001", "(.*/)?").replace("\u0002", ".*"))
        }
    }

    /** Code only: comments and string literals removed, so a note about an API is not a use of it. */
    private fun code(text: String): List<String> {
        val noBlocks = Regex("/\\*[\\s\\S]*?\\*/").replace(text) { m -> "\n".repeat(m.value.count { it == '\n' }) }
        return noBlocks.lines().map { line ->
            Regex("\"(\\\\.|[^\"\\\\])*\"").replace(line.substringBefore("//"), "\"\"")
        }
    }

    private fun violations(relPath: String, text: String): List<String> =
        code(text).flatMapIndexed { i, line ->
            banned.filter { (api, _) -> line.contains(api) }.map { (api, why) -> "$relPath:${i + 1} uses $api ($why)" }
        }

    @Test
    fun `embedded store sources use no JVM-only APIs`() {
        val root = File(repo, "server/src/main/kotlin")
        val excludes = androidExcludes()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 50, "found only ${files.size} store sources under $root")
        val embedded = files.map { it.relativeTo(root).invariantSeparatorsPath to it }
            .filter { (rel, _) -> excludes.none { it.matches(rel) } }
        assertTrue(embedded.size < files.size, "the Android excludes matched nothing")
        val found = embedded.flatMap { (rel, f) -> violations(rel, f.readText()) }
        if (found.isNotEmpty()) fail("the tablet's APK would not build:\n" + found.joinToString("\n"))
    }

    @Test
    fun `the check catches what broke the APK before`() {
        val before = """
            package dev.dwhipstock.pos.forecourt
            // HttpURLConnection, not java.net.http: a comment is fine
            import java.net.http.HttpClient
            val s = "java.awt in a string is fine"
        """.trimIndent()
        assertEquals(listOf("SimulatorAdapter.kt:3 uses java.net.http (use HttpURLConnection)"),
            violations("SimulatorAdapter.kt", before))
    }
}
