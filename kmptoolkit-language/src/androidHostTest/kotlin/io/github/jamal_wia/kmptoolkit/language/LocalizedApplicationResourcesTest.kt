package io.github.jamal_wia.kmptoolkit.language

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList as AndroidLocaleList
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private val Russian = AppLanguage(code = "ru", isLtr = true)
private val Arabic = AppLanguage(code = "ar", isLtr = false)

/**
 * An `Application` wired the way [LocalizedApplicationResources]' KDoc tells a consumer to wire one.
 */
class LanguageCarryingApplication : Application() {

    val localizedResources: LocalizedApplicationResources = LocalizedApplicationResources()

    override fun getResources(): Resources {
        val base: Context = baseContext ?: return super.getResources()
        return localizedResources.resourcesOf(base)
    }
}

/**
 * The bug this class exists for: an app set to Russian on an English device flips to English
 * mid-session.
 *
 * Android rebuilds the process-global default locale list on every configuration delivery, from the
 * application context's resources, and Compose Multiplatform reads every string through that
 * default. These tests pin down both halves — what the application's resources report under each
 * language, and that the framework's rebuild (replicated verbatim in
 * [frameworkRebuildsDefaultLocaleList], scanning the same process configuration the framework scans)
 * now lands on the chosen language rather than the device's.
 *
 * Robolectric caveat: `RuntimeEnvironment.setQualifiers` re-applies the configuration through the
 * real `ResourcesManager`, as on a device, but then also calls `updateConfiguration` directly on
 * whatever `Application.getResources()` returns — here the localized resources — clobbering their
 * locale. So after a qualifier change only a *freshly* resolved `app.resources` is meaningful.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = LanguageCarryingApplication::class)
class LocalizedApplicationResourcesTest {

    private lateinit var savedLocale: Locale
    private lateinit var savedLocaleList: AndroidLocaleList
    private lateinit var savedProcessLocales: AndroidLocaleList
    private lateinit var app: LanguageCarryingApplication

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        savedLocaleList = AndroidLocaleList.getDefault()
        savedProcessLocales = processConfiguration.locales
        app = RuntimeEnvironment.getApplication() as LanguageCarryingApplication
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
        AndroidLocaleList.setDefault(savedLocaleList)
        processConfiguration.setLocales(savedProcessLocales)
    }

    private val systemLanguage: String
        get() = Resources.getSystem().configuration.locales[0].language

    // --- what the Application's resources report ----------------------------------------------

    @Test
    fun `serves the base resources untouched until a language source is attached`() {
        val base: Context = app.baseContext

        assertSame(base.resources, app.resources)
        assertEquals(systemLanguage, app.resources.configuration.locales[0].language)
    }

    @Test
    fun `resolves under the followed language`() {
        app.localizedResources.readLanguageFrom { Russian }

        assertEquals("ru", app.resources.configuration.locales[0].language)
    }

    @Test
    fun `serves the same resources object while the language is unchanged`() {
        app.localizedResources.readLanguageFrom { Russian }

        assertSame(app.resources, app.resources)
    }

    @Test
    fun `switches as soon as the followed language changes`() {
        var language: AppLanguage = Russian
        app.localizedResources.readLanguageFrom { language }
        assertEquals("ru", app.resources.configuration.locales[0].language)

        language = Arabic

        assertEquals("ar", app.resources.configuration.locales[0].language)
    }

    @Test
    fun `System resolves to the device locale`() {
        app.localizedResources.readLanguageFrom { AppLanguage.System }

        assertEquals(systemLanguage, app.resources.configuration.locales[0].language)
    }

    @Test
    fun `System keeps following the device locale after it changes`() {
        app.localizedResources.readLanguageFrom { AppLanguage.System }
        val before: Resources = app.resources
        assertEquals(systemLanguage, before.configuration.locales[0].language)

        RuntimeEnvironment.setQualifiers("+tr")

        assertEquals("tr", systemLanguage)
        val after: Resources = app.resources
        // A new resolution, not the cached one: the cache is keyed by the resolved locale.
        assertNotSame(before, after)
        assertEquals("tr", after.configuration.locales[0].language)
    }

    @Test
    fun `overrides only the locale, not the rest of the configuration`() {
        RuntimeEnvironment.setQualifiers("+land-night")
        RuntimeEnvironment.setFontScale(1.3f)
        app.localizedResources.readLanguageFrom { Russian }

        val base: Configuration = app.baseContext.resources.configuration
        val localized: Configuration = app.resources.configuration

        assertEquals("ru", localized.locales[0].language)
        // 1.3, not the 1.0 a default-initialised override would copy over the user's setting.
        assertEquals(1.3f, localized.fontScale)
        assertEquals(base.fontScale, localized.fontScale)
        assertEquals(base.uiMode, localized.uiMode)
        assertEquals(base.orientation, localized.orientation)
        assertEquals(base.densityDpi, localized.densityDpi)
        assertEquals(base.screenWidthDp, localized.screenWidthDp)
    }

    // --- the framework's rebuild of the process-global default ---------------------------------

    @Test
    fun `the framework's locale-list rebuild keeps the chosen language`() {
        app.localizedResources.readLanguageFrom { Russian }
        applyLanguageGlobally(Russian)

        frameworkRebuildsDefaultLocaleList(app)

        assertEquals("ru", AndroidLocaleList.getDefault()[0].language)
        assertEquals("ru", Locale.getDefault().language)
    }

    @Test
    fun `the rebuild pushes the chosen language in front of a device list that never carried it`() {
        // Three device languages, and the process default still is that list — the app never got to
        // pin its own, because the bind-time rebuild runs before Application.onCreate.
        val deviceList = AndroidLocaleList(Locale("en", "US"), Locale("ru", "RU"), Locale("tr", "TR"))
        processConfiguration.setLocales(deviceList)
        AndroidLocaleList.setDefault(deviceList)
        app.localizedResources.readLanguageFrom { Russian }

        frameworkRebuildsDefaultLocaleList(app)

        // Bare "ru" equals none of the region-qualified entries, so it is pushed to the front.
        assertEquals("ru,en-US,ru-RU,tr-TR", AndroidLocaleList.getDefault().toLanguageTags())
        assertEquals("ru", Locale.getDefault().language)
    }

    @Test
    fun `the rebuild follows the device locale under System`() {
        app.localizedResources.readLanguageFrom { AppLanguage.System }
        applyLanguageGlobally(AppLanguage.System)

        frameworkRebuildsDefaultLocaleList(app)

        assertEquals(systemLanguage, AndroidLocaleList.getDefault()[0].language)
        assertEquals(systemLanguage, Locale.getDefault().language)
    }

    @Test
    fun `residual - a device list carrying the exact app locale later on keeps the device order`() {
        // Region-less device entries do not come from the Settings picker (adb or some ROMs only).
        // The framework then finds our locale at index 1, moves only Locale.getDefault() to it, and
        // the list a string resource reads still starts with the device locale. This is the one case
        // this class cannot cover on its own — it is what AppLocale's synchronous re-pin is for.
        processConfiguration.setLocales(AndroidLocaleList(Locale("en", "US"), Locale("ru")))
        app.localizedResources.readLanguageFrom { Russian }
        applyLanguageGlobally(Russian)

        frameworkRebuildsDefaultLocaleList(app)

        assertEquals("ru", Locale.getDefault().language)
        assertEquals("en", AndroidLocaleList.getDefault()[0].language)

        applyLanguageGlobally(Russian)

        assertEquals("ru", AndroidLocaleList.getDefault()[0].language)
    }

    @Test
    fun `control - with plain application resources the rebuild flips to the device locale`() {
        // The pre-fix mechanism, kept as a control so the fix above is known to be load-bearing:
        // resources that do not carry the language hand the device locale to the rebuild.
        assertNotEquals("ru", systemLanguage)
        applyLanguageGlobally(Russian)
        assertEquals("ru", AndroidLocaleList.getDefault()[0].language)

        frameworkRebuildsDefaultLocaleList(appResources = app.baseContext.resources)

        assertEquals(systemLanguage, AndroidLocaleList.getDefault()[0].language)
        assertEquals(systemLanguage, Locale.getDefault().language)
    }

    private fun frameworkRebuildsDefaultLocaleList(context: Context) =
        frameworkRebuildsDefaultLocaleList(context.applicationContext.resources)

    /**
     * `ActivityThread` / `ConfigurationController.updateLocaleListFromAppContext`, verbatim: the
     * framework runs this on every configuration delivery to the process, and once at bind time. It
     * reads the "best" locale from the application context's resources and looks it up in the
     * process configuration's locale list (the device languages); found at `i` → the hidden
     * `LocaleList.setDefault(list, i)`, otherwise pushed to the front of that list.
     */
    private fun frameworkRebuildsDefaultLocaleList(appResources: Resources) {
        val bestLocale: Locale = appResources.configuration.locales[0]
        val deviceList: AndroidLocaleList = processConfiguration.locales
        for (index in 0 until deviceList.size()) {
            if (bestLocale == deviceList[index]) {
                setDefaultLocaleListAt(deviceList, index)
                return
            }
        }
        // The framework's hidden LocaleList(topLocale, otherLocales): topLocale first, then the rest.
        val rest: List<Locale> =
            (0 until deviceList.size()).map { deviceList[it] }.filter { it != bestLocale }
        AndroidLocaleList.setDefault(AndroidLocaleList(bestLocale, *rest.toTypedArray()))
    }

    /** The live process configuration held by `ResourcesManager` — the list the framework scans. */
    private val processConfiguration: Configuration
        get() {
            val managerClass: Class<*> = Class.forName("android.app.ResourcesManager")
            val manager: Any = checkNotNull(managerClass.getMethod("getInstance").invoke(null)) {
                "ResourcesManager.getInstance() returned null"
            }
            return managerClass.getMethod("getConfiguration").invoke(manager) as Configuration
        }

    /** The hidden `LocaleList.setDefault(LocaleList, int)`; index 0 is the public overload. */
    private fun setDefaultLocaleListAt(locales: AndroidLocaleList, index: Int) {
        if (index == 0) {
            AndroidLocaleList.setDefault(locales)
        } else {
            AndroidLocaleList::class.java
                .getMethod("setDefault", AndroidLocaleList::class.java, Int::class.javaPrimitiveType)
                .invoke(null, locales, index)
        }
    }
}
