package io.github.jamal_wia.kmptoolkit.language

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ApplyLanguageGloballyAndroidTest {

    private val originalDefault: Locale = Locale.getDefault()

    @AfterTest
    fun restoreDefaultLocale() {
        Locale.setDefault(originalDefault)
    }

    @Test
    fun `applying an explicit language sets the JVM default locale`() {
        applyLanguageGlobally(AppLanguage(code = "fr", isLtr = true))

        assertEquals(Locale.forLanguageTag("fr"), Locale.getDefault())
    }

    @Test
    fun `applying System resets the default locale to the device's own`() {
        applyLanguageGlobally(AppLanguage(code = "ar", isLtr = false))

        applyLanguageGlobally(AppLanguage.System)

        assertEquals(getSystemLanguageCode(), Locale.getDefault().toLanguageTag())
    }

    @Test
    fun `getSystemLanguageCode is unaffected by an explicit applyLanguageGlobally call`() {
        val before = getSystemLanguageCode()

        applyLanguageGlobally(AppLanguage(code = "ja", isLtr = true))

        assertEquals(before, getSystemLanguageCode())
    }
}
