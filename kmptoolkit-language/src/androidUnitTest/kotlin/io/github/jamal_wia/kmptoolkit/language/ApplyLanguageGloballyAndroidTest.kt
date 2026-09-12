package io.github.jamal_wia.kmptoolkit.language

import android.content.res.Resources
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ApplyLanguageGloballyAndroidTest {

    private val originalDefault: Locale = Locale.getDefault()
    private val originalLocaleList: LocaleList = LocaleList.getDefault()

    @AfterTest
    fun restoreDefaultLocale() {
        Locale.setDefault(originalDefault)
        LocaleList.setDefault(originalLocaleList)
    }

    @Test
    fun `applying an explicit language sets the JVM default locale`() {
        applyLanguageGlobally(AppLanguage(code = "fr", isLtr = true))

        assertEquals(Locale.forLanguageTag("fr"), Locale.getDefault())
    }

    @Test
    fun `applying an explicit language also puts it at the head of the default LocaleList`() {
        // Not a restatement of the test above: Compose Multiplatform resolves `stringResource`
        // through `LocaleList.getDefault()` on API 24+, not through `Locale.getDefault()`. Drop the
        // `LocaleList.setDefault` call from the implementation and every other test here still
        // passes while the app renders in the wrong language.
        applyLanguageGlobally(AppLanguage(code = "fr", isLtr = true))

        assertEquals("fr", LocaleList.getDefault()[0].language)
    }

    @Test
    fun `applying System resets the default locale to the device's own`() {
        applyLanguageGlobally(AppLanguage(code = "ar", isLtr = false))

        applyLanguageGlobally(AppLanguage.System)

        assertEquals(getSystemLanguageCode(), Locale.getDefault().toLanguageTag())
    }

    @Test
    fun `applying System puts the device's own language back at the head of the LocaleList`() {
        applyLanguageGlobally(AppLanguage(code = "ar", isLtr = false))

        applyLanguageGlobally(AppLanguage.System)

        assertEquals(
            Resources.getSystem().configuration.locales[0].language,
            LocaleList.getDefault()[0].language,
        )
    }

    @Test
    fun `getSystemLanguageCode is unaffected by an explicit applyLanguageGlobally call`() {
        val before = getSystemLanguageCode()

        applyLanguageGlobally(AppLanguage(code = "ja", isLtr = true))

        assertEquals(before, getSystemLanguageCode())
    }
}
