package io.github.jamal_wia.kmptoolkit.language

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList as AndroidLocaleList
import android.view.View
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** `ar_EG` is read as right-to-left, so it must also be applied as `ar-EG`, not as the root locale. */
@RunWith(RobolectricTestRunner::class)
class UnderscoreTagAndroidTest {

    private lateinit var savedLocale: Locale
    private lateinit var savedLocaleList: AndroidLocaleList

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        savedLocaleList = AndroidLocaleList.getDefault()
    }

    @After
    fun restore() {
        Locale.setDefault(savedLocale)
        AndroidLocaleList.setDefault(savedLocaleList)
    }

    @Test
    fun `the platform locale of an underscore code is the hyphen locale`() {
        assertEquals(Locale.forLanguageTag("ar-EG"), AppLanguage("ar_EG").resolveLocale())
    }

    @Test
    fun `a localized context for an underscore code is Egyptian Arabic laid out right-to-left`() {
        val base: Context = RuntimeEnvironment.getApplication()

        val configuration: Configuration = localizedContext(base, AppLanguage("ar_EG").resolveLocale()).resources.configuration

        assertEquals("ar", configuration.locales[0].language)
        assertEquals("EG", configuration.locales[0].country)
        assertEquals(View.LAYOUT_DIRECTION_RTL, configuration.layoutDirection)
    }

    @Test
    fun `applyLanguageGlobally installs the hyphen locale for an underscore code`() {
        applyLanguageGlobally(AppLanguage("ar_EG"))

        assertEquals(Locale.forLanguageTag("ar-EG"), Locale.getDefault())
        assertEquals(Locale.forLanguageTag("ar-EG"), AndroidLocaleList.getDefault()[0])
    }
}
