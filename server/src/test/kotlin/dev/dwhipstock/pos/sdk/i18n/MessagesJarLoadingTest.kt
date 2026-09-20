package dev.dwhipstock.pos.sdk.i18n

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The jar branch of the catalog loader is the one PRODUCTION runs (buildFatJar
 * → java -jar), yet the normal suite only ever exercises the exploded-classpath
 * file branch. These tests feed loadBundles synthetic jars so a packaging or
 * scanning regression fails here instead of shipping raw key ids to a venue.
 */
class MessagesJarLoadingTest {

    private fun jarOf(entries: Map<String, String>, includeDirEntry: Boolean): File {
        val f = Files.createTempFile("i18n-jar-test", ".jar").toFile()
        JarOutputStream(f.outputStream()).use { jar ->
            if (includeDirEntry) {
                jar.putNextEntry(JarEntry("i18n/")); jar.closeEntry()
            }
            entries.forEach { (name, content) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(content.toByteArray(Charsets.UTF_8))
                jar.closeEntry()
            }
        }
        return f
    }

    private fun load(vararg jars: File) =
        Messages.loadBundles(URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), null))

    @Test
    fun fatJarWithoutDirectoryEntriesStillLoadsViaTheAnchorFile() {
        val jar = jarOf(mapOf("i18n/messages_en.properties" to "receipt.total=Total\n"), includeDirEntry = false)
        val bundles = load(jar)
        assertEquals("Total", bundles["en"]?.get("receipt.total"))
    }

    @Test
    fun jarWithDirectoryEntriesLoadsTheSameCatalogExactlyOnce() {
        // discovered via BOTH the directory entry and the anchor URL — dedupe must hold
        val jar = jarOf(
            mapOf(
                "i18n/messages_en.properties" to "receipt.total=Total\n",
                "i18n/messages_zz.properties" to "receipt.total=ZZ\n",
            ),
            includeDirEntry = true,
        )
        val bundles = load(jar)
        assertEquals("Total", bundles["en"]?.get("receipt.total"))
        assertEquals("ZZ", bundles["zz"]?.get("receipt.total"))
    }

    @Test
    fun rootLevelMessagesFilesFromMergedDependenciesAreIgnored() {
        val jar = jarOf(
            mapOf(
                "i18n/messages_en.properties" to "receipt.total=Total\n",
                // a fat jar merges dependency resources — these must NOT become catalogs
                "messages_fr.properties" to "receipt.total=Totale\n",
                "messages_fr.properties" to "receipt.total=POISONED\n",
                "com/example/messages_de.properties" to "receipt.total=Gesamt\n",
            ),
            includeDirEntry = true,
        )
        val bundles = load(jar)
        assertFalse("fr" in bundles, "root-level dependency file must not register a locale")
        assertFalse("de" in bundles, "nested dependency file must not register a locale")
        assertEquals("Total", bundles["en"]?.get("receipt.total"))
    }

    @Test
    fun corruptOptionalCatalogIsSkippedWithoutKillingTheRest() {
        val jar = jarOf(
            mapOf(
                "i18n/messages_de.properties" to "bad=\\u20A\n", // malformed \\uxxxx escape
                "i18n/messages_en.properties" to "receipt.total=Total\n",
            ),
            includeDirEntry = false,
        )
        val bundles = load(jar)
        assertEquals("Total", bundles["en"]?.get("receipt.total"), "good catalog must survive a bad sibling")
        assertFalse("de" in bundles, "corrupt catalog must be dropped, not partially loaded")
    }

    @Test
    fun firstClasspathRootWinsPerKeyAndNonCollidingKeysStillMerge() {
        val first = jarOf(mapOf("i18n/messages_en.properties" to "receipt.total=FIRST\n"), includeDirEntry = true)
        val second = jarOf(
            mapOf("i18n/messages_en.properties" to "receipt.total=SECOND\nreceipt.open=SECOND-ONLY\n"),
            includeDirEntry = true,
        )
        val bundles = load(first, second)
        assertEquals("FIRST", bundles["en"]?.get("receipt.total"), "classpath order must win, matching getResource")
        assertEquals("SECOND-ONLY", bundles["en"]?.get("receipt.open"))
    }

    @Test
    fun nearMissFilenamesAreIgnoredNotLoaded() {
        val jar = jarOf(
            mapOf(
                "i18n/messages_en.properties" to "receipt.total=Total\n",
                "i18n/messages_ja_JP.properties" to "receipt.total=合計\n", // ResourceBundle convention, wrong here
            ),
            includeDirEntry = true,
        )
        val bundles = load(jar)
        assertTrue(bundles.keys.none { it.contains("ja") }, "uppercase/underscore names must not load (warned instead)")
    }
}
