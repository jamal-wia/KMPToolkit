package io.github.jamal_wia.kmptoolkit.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SettingsConfigTest {

    @Test
    fun `the default key prefix is this module's own package`() {
        assertEquals(
            "io.github.jamal_wia.kmptoolkit.settings",
            SettingsConfig.DEFAULT_KEY_PREFIX,
        )
    }

    @Test
    fun `the three keys are the prefix plus a per-setting suffix`() {
        val config = SettingsConfig(keyPrefix = "com.example.app")

        assertEquals("com.example.app.font_scale", config.fontScaleKey)
        assertEquals("com.example.app.theme_mode", config.themeModeKey)
        assertEquals("com.example.app.language", config.languageKey)
    }

    @Test
    fun `the three keys are distinct`() {
        val config = SettingsConfig()
        val keys: Set<String> = setOf(config.fontScaleKey, config.themeModeKey, config.languageKey)

        assertEquals(3, keys.size)
    }

    @Test
    fun `a blank key prefix is rejected at the call site`() {
        assertFailsWith<IllegalArgumentException> { SettingsConfig(keyPrefix = "") }
        assertFailsWith<IllegalArgumentException> { SettingsConfig(keyPrefix = "   ") }
    }

    @Test
    fun `a default language outside the supported set is rejected at the call site`() {
        assertFailsWith<IllegalArgumentException> {
            SettingsConfig(
                supportedLanguages = setOf(LanguageTag("en")),
                defaultLanguage = LanguageTag("de"),
            )
        }
    }

    @Test
    fun `a default language inside the supported set is accepted`() {
        val config = SettingsConfig(
            supportedLanguages = setOf(LanguageTag("en"), LanguageTag("de")),
            defaultLanguage = LanguageTag("de"),
        )

        assertEquals(LanguageTag("de"), config.defaultLanguage)
    }

    @Test
    fun `any default language is accepted when no supported set is declared`() {
        val config = SettingsConfig(defaultLanguage = LanguageTag("de"))

        assertEquals(LanguageTag("de"), config.defaultLanguage)
    }

    @Test
    fun `following the system is a valid default whatever the supported set is`() {
        val config = SettingsConfig(supportedLanguages = setOf(LanguageTag("en")))

        assertEquals(null, config.defaultLanguage)
    }

    @Test
    fun `an empty supported set accepts every well-formed tag`() {
        val config = SettingsConfig()

        assertTrue(config.supports(LanguageTag("en")))
        assertTrue(config.supports(LanguageTag("zh-Hant-TW")))
    }

    @Test
    fun `a non-empty supported set accepts only its own members`() {
        val config = SettingsConfig(supportedLanguages = setOf(LanguageTag("en")))

        assertTrue(config.supports(LanguageTag("EN")))
        assertTrue(!config.supports(LanguageTag("en-GB")))
        assertTrue(!config.supports(LanguageTag("de")))
    }
}
