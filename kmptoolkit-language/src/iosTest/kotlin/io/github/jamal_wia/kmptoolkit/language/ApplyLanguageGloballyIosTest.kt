package io.github.jamal_wia.kmptoolkit.language

import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ApplyLanguageGloballyIosTest {

    @AfterTest
    fun clearOverride() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(APPLE_LANGUAGES_KEY)
    }

    @Test
    fun `applying an explicit language writes it to AppleLanguages`() {
        applyLanguageGlobally(AppLanguage(code = "pt-BR", isLtr = true))

        val stored = NSUserDefaults.standardUserDefaults.arrayForKey(APPLE_LANGUAGES_KEY)
        assertEquals(listOf("pt-BR"), stored)
    }

    @Test
    fun `applying System removes the app's own AppleLanguages override`() {
        // arrayForKey searches every NSUserDefaults domain, global included, and the global domain
        // always carries the simulator's own AppleLanguages — so it does not go back to nil here.
        // What must be gone is this app's own override, i.e. the array is no longer the one this
        // test explicitly set.
        applyLanguageGlobally(AppLanguage(code = "ar", isLtr = false))

        applyLanguageGlobally(AppLanguage.System)

        assertNotEquals(listOf("ar"), NSUserDefaults.standardUserDefaults.arrayForKey(APPLE_LANGUAGES_KEY))
    }

    private companion object {
        const val APPLE_LANGUAGES_KEY = "AppleLanguages"
    }
}
