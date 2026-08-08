package io.github.jamal_wia.kmptoolkit.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Android [LanguageApplier] has two implementations behind one call — the framework
 * `LocaleManager` from API 33 and the process locale defaults below it — and picking the wrong one
 * fails in a way no compiler catches: on an old device the app simply keeps speaking the system
 * language. Both branches are therefore exercised at their own SDK level.
 */
@RunWith(AndroidJUnit4::class)
class AndroidLanguageApplierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val applier: LanguageApplier = createLanguageApplier(context)

    private val originalLocale: Locale = Locale.getDefault()
    private val originalLocaleList: LocaleList = LocaleList.getDefault()

    @AfterTest
    fun restoreProcessLocale() {
        // The defaults are process-wide, so a test that changed them would leak into every later
        // test in the same JVM fork.
        Locale.setDefault(originalLocale)
        LocaleList.setDefault(originalLocaleList)
    }

    @Test
    @Config(sdk = [33])
    fun `on api 33 the language goes to the framework locale manager`() {
        applier.apply(LanguageTag("pt-BR"))

        assertEquals("pt-BR", localeManager().applicationLocales.toLanguageTags())
    }

    @Test
    @Config(sdk = [33])
    fun `on api 33 following the system clears the app-specific locale`() {
        applier.apply(LanguageTag("pt-BR"))

        applier.apply(null)

        assertTrue(
            localeManager().applicationLocales.isEmpty,
            "an empty list is what makes the app keep following the system, whereas pinning the " +
                "current system locale would freeze it at today's value",
        )
    }

    @Test
    @Config(sdk = [32])
    fun `below api 33 the language goes to the process locale defaults`() {
        applier.apply(LanguageTag("pt-BR"))

        assertEquals("pt", Locale.getDefault().language)
        assertEquals("BR", Locale.getDefault().country)
        assertEquals(
            "pt-BR",
            LocaleList.getDefault()[0].toLanguageTag(),
            "resource resolution reads LocaleList.getDefault() on API 24+, so setting only " +
                "Locale.getDefault() would leave strings in the system language",
        )
    }

    @Test
    @Config(sdk = [32])
    fun `below api 33 a language-only tag is applied without a region`() {
        applier.apply(LanguageTag("de"))

        assertEquals("de", Locale.getDefault().language)
        assertEquals("", Locale.getDefault().country)
    }

    @Test
    @Config(sdk = [32])
    fun `below api 33 following the system restores the system locale`() {
        applier.apply(LanguageTag("de"))

        applier.apply(null)

        assertEquals(
            android.content.res.Resources.getSystem().configuration.locales[0],
            Locale.getDefault(),
        )
    }

    @Test
    @Config(sdk = [32])
    fun `applying the same language twice is a no-op rather than an error`() {
        applier.apply(LanguageTag("de"))
        applier.apply(LanguageTag("de"))

        assertEquals("de", Locale.getDefault().language)
    }

    private fun localeManager(): LocaleManager =
        context.getSystemService(LocaleManager::class.java)
}
