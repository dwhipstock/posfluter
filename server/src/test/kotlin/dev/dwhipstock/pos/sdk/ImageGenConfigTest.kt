package dev.dwhipstock.pos.sdk

import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageGenConfigTest {
    private val fluxKey = "bfl-secret-0123456789"
    private val openAiKey = "sk-proj-abcdefghijklmnop"

    private fun props(vararg pairs: Pair<String, String>) = Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

    @Test
    fun offByDefault() {
        val none = ImageGenConfig.fromEnv { null }
        assertFalse(none.enabled)
        assertEquals(ImageGenConfig.Disabled.GENERATION_OFF, none.disabled)
        assertEquals(ImageGenConfig.Provider.OFF, none.provider)
        assertFalse(ImageGenConfig.fromProperties(null).enabled)
        assertFalse(ImageGenConfig.fromFile(null).enabled)
    }

    @Test
    fun tabletStorePropertiesEnableTheSelectedProvider() {
        val c = ImageGenConfig.fromProperties(props(
            "image.generation" to "on", "image.provider" to "flux", "image.bfl.apiKey" to fluxKey,
            "image.openai.apiKey" to openAiKey))
        assertTrue(c.enabled)
        assertEquals(ImageGenConfig.Provider.FLUX, c.provider)
        assertEquals(fluxKey, c.apiKey)
    }

    @Test
    fun theFeatureFlagGatesEvenWithAKey() {
        val c = ImageGenConfig.fromProperties(props("image.provider" to "openai", "image.openai.apiKey" to openAiKey))
        assertFalse(c.enabled)
        assertEquals("image_generation_off", c.disabled!!.code)
        assertNull(c.apiKey, "a disabled config keeps no key")
        val off = ImageGenConfig.fromProperties(props("image.generation" to "off", "image.provider" to "openai",
            "image.openai.apiKey" to openAiKey))
        assertFalse(off.enabled)
    }

    @Test
    fun providerOffOrKeyMissingDisable() {
        assertEquals(ImageGenConfig.Disabled.PROVIDER_OFF,
            ImageGenConfig.fromProperties(props("image.generation" to "on")).disabled)
        assertEquals(ImageGenConfig.Disabled.PROVIDER_OFF,
            ImageGenConfig.fromProperties(props("image.generation" to "on", "image.provider" to "off",
                "image.bfl.apiKey" to fluxKey)).disabled)
        // a key for a different provider does not count
        assertEquals(ImageGenConfig.Disabled.KEY_MISSING,
            ImageGenConfig.fromProperties(props("image.generation" to "on", "image.provider" to "gemini",
                "image.bfl.apiKey" to fluxKey)).disabled)
    }

    @Test
    fun badValuesWarnAndStayOff() {
        val c = ImageGenConfig.fromProperties(props("image.generation" to "maybe", "image.provider" to "dalle"))
        assertFalse(c.enabled)
        assertEquals(ImageGenConfig.Provider.OFF, c.provider)
        assertTrue(c.warning!!.contains("image.generation") && c.warning!!.contains("image.provider"))
        val spaced = ImageGenConfig.fromProperties(props("image.generation" to "on", "image.provider" to "flux",
            "image.bfl.apiKey" to "has a space"))
        assertFalse(spaced.enabled)
        assertFalse(spaced.warning!!.contains("has a space"), "the warning never echoes the key")
    }

    @Test
    fun desktopEnvWinsFieldByFieldOverTheConfigFile() {
        val file = Files.createTempFile("store", ".properties").toFile()
        file.writeText("image.generation=on\nimage.provider=flux\nimage.bfl.apiKey=$fluxKey\nimage.model=flux-2-flex\n")
        // file alone
        val fromFile = ImageGenConfig.fromEnv { if (it == "POS_CONFIG_FILE") file.path else null }
        assertTrue(fromFile.enabled)
        assertEquals("flux-2-flex", fromFile.model)
        assertEquals(fluxKey, fromFile.apiKey)
        // env switches the provider; its key comes from the env, the flag stays from the file
        val env = mapOf("POS_CONFIG_FILE" to file.path, "POS_IMAGE_PROVIDER" to "openai", "OPENAI_API_KEY" to openAiKey)
        val mixed = ImageGenConfig.fromEnv(env::get)
        assertTrue(mixed.enabled)
        assertEquals(ImageGenConfig.Provider.OPENAI, mixed.provider)
        assertEquals(openAiKey, mixed.apiKey)
        assertEquals("OPENAI_API_KEY", mixed.source)
        // env key beats the file key for the same provider
        val both = ImageGenConfig.fromEnv(mapOf("POS_CONFIG_FILE" to file.path, "BFL_API_KEY" to "bfl-env-key-999")::get)
        assertEquals("bfl-env-key-999", both.apiKey)
        // env flag can switch it off
        assertFalse(ImageGenConfig.fromEnv(mapOf("POS_CONFIG_FILE" to file.path, "POS_IMAGE_GENERATION" to "off")::get).enabled)
        // env only, no file
        assertTrue(ImageGenConfig.fromEnv(mapOf("POS_IMAGE_GENERATION" to "on", "POS_IMAGE_PROVIDER" to "gemini",
            "GEMINI_API_KEY" to "AIzaSyExampleExampleExample00")::get).enabled)
    }

    @Test
    fun describeAndToStringNeverShowTheKey() {
        val c = ImageGenConfig.fromProperties(props("image.generation" to "on", "image.provider" to "flux",
            "image.bfl.apiKey" to fluxKey))
        assertFalse(c.describe().contains(fluxKey))
        assertFalse(c.toString().contains(fluxKey))
        assertTrue(c.describe().startsWith("AI photos: on (flux"))
        val missing = ImageGenConfig.fromProperties(props("image.generation" to "on", "image.provider" to "openai"))
        assertTrue(missing.describe().contains("OPENAI_API_KEY"))
    }
}
