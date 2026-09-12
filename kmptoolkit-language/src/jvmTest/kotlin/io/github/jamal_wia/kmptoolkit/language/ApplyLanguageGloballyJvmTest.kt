package io.github.jamal_wia.kmptoolkit.language

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The desktop contract, from `docs/kmptoolkit-language/05-platform-notes.md`: an explicit language
 * becomes the JVM default for every category, [AppLanguage.System] puts back the language the
 * operating system gave the process, and the system language is reported independently of anything
 * applied since.
 *
 * Every case pins the `user.*` properties itself rather than trusting the machine running the suite,
 * and restores both them and the default afterwards — both are process-global.
 */
class ApplyLanguageGloballyJvmTest {

    private val originalDefault: Locale = Locale.getDefault()
    private val originalProperties: Map<String, String?> =
        USER_LOCALE_PROPERTIES.associateWith { key -> System.getProperty(key) }

    @BeforeTest
    fun pinTheOperatingSystemLanguage() {
        setUserLocale(language = "de", script = "", country = "AT")
    }

    @AfterTest
    fun restoreProcessState() {
        originalProperties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
        Locale.setDefault(originalDefault)
    }

    @Test
    fun `an explicit language becomes the default for every category`() {
        applyLanguageGlobally(AppLanguage("ru"))

        assertEquals(Locale.forLanguageTag("ru"), Locale.getDefault())
        assertEquals(Locale.forLanguageTag("ru"), Locale.getDefault(Locale.Category.DISPLAY))
        assertEquals(Locale.forLanguageTag("ru"), Locale.getDefault(Locale.Category.FORMAT))
    }

    @Test
    fun `System puts back the operating system's language, not the last one applied`() {
        // The regression this guards: reading Locale.getDefault() as "the system language" would
        // restore Russian with Russian here, and System would silently do nothing.
        applyLanguageGlobally(AppLanguage("ru"))

        applyLanguageGlobally(AppLanguage.System)

        assertEquals(Locale.forLanguageTag("de-AT"), Locale.getDefault())
    }

    @Test
    fun `the system language is unaffected by an applied language`() {
        applyLanguageGlobally(AppLanguage("ru"))

        assertEquals("de-AT", getSystemLanguageCode())
    }

    @Test
    fun `a script subtag from the environment is carried into the system language`() {
        setUserLocale(language = "sr", script = "Latn", country = "RS")

        assertEquals("sr-Latn-RS", getSystemLanguageCode())
    }

    @Test
    fun `a malformed region in the environment does not cost the language`() {
        setUserLocale(language = "de", script = "", country = "not a region")

        assertEquals("de", getSystemLanguageCode())
    }

    @Test
    fun `no language in the environment is reported as no system language`() {
        setUserLocale(language = "", script = "", country = "")

        assertNull(getSystemLanguageCode())
    }

    private fun setUserLocale(language: String, script: String, country: String) {
        System.setProperty("user.language", language)
        System.setProperty("user.script", script)
        System.setProperty("user.country", country)
    }

    private companion object {
        val USER_LOCALE_PROPERTIES: List<String> = listOf("user.language", "user.script", "user.country")
    }
}
