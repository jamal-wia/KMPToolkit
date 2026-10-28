package io.github.jamal_wia.kmptoolkit.language

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList as AndroidLocaleList
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private val Russian = AppLanguage(code = "ru", isLtr = true)

/**
 * What `localizedContext` overrides, and — more importantly — what it does **not**.
 *
 * The override carries the locale and nothing else, so a context built from it keeps following the
 * system for every other field. That is not a detail: a context built from a *full copy* of the base
 * configuration would freeze dark mode, font scale and orientation at whatever they were when it was
 * created, for the rest of that context's life. Harmless for an activity, which is recreated on
 * every such change anyway — permanent for an application context.
 */
@RunWith(RobolectricTestRunner::class)
class LocalizedContextTest {

    private lateinit var savedLocale: Locale
    private lateinit var savedLocaleList: AndroidLocaleList

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        savedLocaleList = AndroidLocaleList.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
        AndroidLocaleList.setDefault(savedLocaleList)
    }

    /** A configuration with every field this override must leave alone set to a distinctive value. */
    private fun baseConfiguration(): Configuration = Configuration().apply {
        fontScale = 1.3f
        uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
        orientation = Configuration.ORIENTATION_LANDSCAPE
        densityDpi = 480
        screenWidthDp = 800
        setLocale(Locale("en", "US"))
    }

    @Test
    fun `the override merged onto a base configuration changes only the locale`() {
        val merged = Configuration(baseConfiguration()).apply {
            updateFrom(localeOverrideConfiguration(Locale("ru")))
        }

        assertEquals("ru", merged.locales[0].language)
        assertEquals(1, merged.locales.size())
        assertEquals(1.3f, merged.fontScale)
        assertEquals(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL, merged.uiMode)
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, merged.orientation)
        assertEquals(480, merged.densityDpi)
        assertEquals(800, merged.screenWidthDp)
    }

    @Test
    fun `the override does not mask a later font-scale or theme change`() {
        val override: Configuration = localeOverrideConfiguration(Locale("ru"))
        val changed: Configuration = baseConfiguration().apply {
            fontScale = 1.6f
            uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL
        }

        val merged = Configuration(changed).apply { updateFrom(override) }

        assertEquals(1.6f, merged.fontScale)
        assertEquals(Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL, merged.uiMode)
        assertEquals("ru", merged.locales[0].language)
    }

    @Test
    fun `a localized context carries the chosen locale`() {
        val base: Context = RuntimeEnvironment.getApplication()

        val wrapped: Context = localizedContext(base, Russian)

        assertEquals("ru", wrapped.resources.configuration.locales[0].language)
    }

    @Test
    fun `a localized context keeps the base font scale`() {
        RuntimeEnvironment.setFontScale(1.3f)
        val base: Context = RuntimeEnvironment.getApplication()

        val wrapped: Context = localizedContext(base, Russian)

        assertEquals(1.3f, wrapped.resources.configuration.fontScale)
        assertEquals("ru", wrapped.resources.configuration.locales[0].language)
    }

    @Test
    fun `a localized context follows a later dark-mode change while keeping its locale`() {
        val base: Context = RuntimeEnvironment.getApplication()
        val wrapped: Context = localizedContext(base, Russian)
        assertNotEquals(
            Configuration.UI_MODE_NIGHT_YES,
            wrapped.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
        )

        // What a dark-mode switch does on a device: ResourcesManager re-applies the new base
        // configuration to every Resources, merging each context's own override on top. A full copy
        // of the base configuration as the override would keep the old uiMode here forever.
        RuntimeEnvironment.setQualifiers("+night")

        val after: Configuration = wrapped.resources.configuration
        assertEquals(Configuration.UI_MODE_NIGHT_YES, after.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("ru", after.locales[0].language)
    }

    @Test
    fun `a localized context follows a later font-scale change while keeping its locale`() {
        val base: Context = RuntimeEnvironment.getApplication()
        val wrapped: Context = localizedContext(base, Russian)
        assertEquals(1f, wrapped.resources.configuration.fontScale)

        RuntimeEnvironment.setFontScale(1.3f)

        assertEquals(1.3f, wrapped.resources.configuration.fontScale)
        assertEquals("ru", wrapped.resources.configuration.locales[0].language)
    }

    @Test
    fun `System returns the base context untouched`() {
        val base: Context = RuntimeEnvironment.getApplication()

        assertSame(base, localizedContext(base, AppLanguage.System))
    }
}
