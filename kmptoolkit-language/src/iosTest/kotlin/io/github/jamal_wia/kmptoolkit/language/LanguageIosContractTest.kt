package io.github.jamal_wia.kmptoolkit.language

import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The iOS sentences of `docs/kmptoolkit-language/05-platform-notes.md` against the real simulator defaults:
 * the device language `getSystemLanguageCode` reports is not changed by this app's own override ("which the
 * per-app override does not touch"), a single-element array is written, `System` removes the override, a
 * thousand switches end on the last one, and hostile codes neither throw nor survive a `System`.
 */
class LanguageIosContractTest {

    private val key: String = "AppleLanguages"

    @AfterTest
    fun clearOverride() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
    }

    @Test
    fun `the device language does not follow the app's own override`() {
        val device: String? = getSystemLanguageCode()

        applyLanguageGlobally(AppLanguage("ar"))
        assertEquals(device, getSystemLanguageCode(), "after an explicit language")
        applyLanguageGlobally(AppLanguage("pt-BR"))
        assertEquals(device, getSystemLanguageCode(), "after a second explicit language")
        applyLanguageGlobally(AppLanguage.System)
        assertEquals(device, getSystemLanguageCode(), "after System")
    }

    @Test
    fun `a thousand switches leave a single element array holding the last language`() {
        val codes: List<String> = listOf("en", "ar", "pt-BR", "zh-Hans", "he")
        repeat(1000) { applyLanguageGlobally(AppLanguage(codes[it % codes.size])) }

        assertEquals(listOf(codes[999 % codes.size]), NSUserDefaults.standardUserDefaults.arrayForKey(key))
    }

    @Test
    fun `an underscore code is written as a hyphen tag`() {
        applyLanguageGlobally(AppLanguage(code = "ar_EG", isLtr = false))

        assertEquals(listOf("ar-EG"), NSUserDefaults.standardUserDefaults.arrayForKey(key))
    }

    @Test
    fun `hostile codes never throw and System removes whatever they wrote`() {
        val hostile: List<String> = listOf(
            "", " ", "!!!", "x", "und", "zz", "-", "_", "x-private", "\u0000", "en\u0000", "\n", "😀", "a".repeat(100_000),
        )
        for (code in hostile) {
            applyLanguageGlobally(AppLanguage(code = code, isLtr = true))
            applyLanguageGlobally(AppLanguage.System)
            assertNotEquals(
                listOf(code.replace('_', '-')),
                NSUserDefaults.standardUserDefaults.arrayForKey(key),
                "override of a code of ${code.length} chars survived System",
            )
        }
    }
}
