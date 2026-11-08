package io.github.jamal_wia.kmptoolkit.language

import android.app.Application
import android.os.LocaleList
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Android half of the module on every API level from the library minimum up: the language reaches the
 * platform default locale list, a localized context carries the locale and its layout direction while keeping
 * the base configuration's other fields, and the application-resources hook follows the language.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 28, 30, 34])
class LanguageApiMatrixTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var savedLocale: Locale
    private lateinit var savedList: LocaleList

    @BeforeTest
    fun save() {
        savedLocale = Locale.getDefault()
        savedList = LocaleList.getDefault()
    }

    @AfterTest
    fun restore() {
        Locale.setDefault(savedLocale)
        LocaleList.setDefault(savedList)
    }

    @Test
    fun `applyLanguageGlobally puts the language first in both defaults`() {
        applyLanguageGlobally(AppLanguage("pt-BR"))
        assertEquals(Locale.forLanguageTag("pt-BR"), Locale.getDefault())
        assertEquals(Locale.forLanguageTag("pt-BR"), LocaleList.getDefault()[0])
    }

    @Test
    fun `a localized context carries the locale and its layout direction and follows the system font scale`() {
        val localized = localizedContext(application, AppLanguage("ar"))

        val configuration = localized.resources.configuration
        assertEquals("ar", configuration.locales[0].language)
        assertEquals(View.LAYOUT_DIRECTION_RTL, configuration.layoutDirection)
        assertEquals(application.resources.configuration.fontScale, configuration.fontScale, "the override must keep following the system font scale")
    }

    @Test
    fun `the application resources hook follows the language and System follows the device`() {
        val holder = LocalizedApplicationResources()
        var language = AppLanguage("de")
        holder.readLanguageFrom { language }

        assertEquals("de", holder.resourcesOf(application).configuration.locales[0].language)
        language = AppLanguage("ja")
        assertEquals("ja", holder.resourcesOf(application).configuration.locales[0].language)
        language = AppLanguage.System
        val device: Locale = android.content.res.Resources.getSystem().configuration.locales[0]
        assertEquals(device.language, holder.resourcesOf(application).configuration.locales[0].language)
    }
}
