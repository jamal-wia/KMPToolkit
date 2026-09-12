package io.github.jamal_wia.kmptoolkit.language.compose

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import io.github.jamal_wia.kmptoolkit.language.AppLanguage
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

// These tests assert exactly what the lint checks warn about: that the process-global locale is in
// force before content composes, and that the localized Configuration reaches it. Reading both
// non-observably is the assertion.
@Suppress("NonObservableLocale", "LocalContextConfigurationRead")
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class AppLocaleUiTest {

    @Test
    fun `provides Ltr layout direction for an LTR language`() = runComposeUiTest {
        var observed: LayoutDirection? = null
        setContent {
            AppLocale(AppLanguage(code = "en", isLtr = true), AppLanguage(code = "en", isLtr = true)) {
                observed = LocalLayoutDirection.current
            }
        }
        waitForIdle()

        assertEquals(LayoutDirection.Ltr, observed)
    }

    @Test
    fun `provides Rtl layout direction for an RTL language`() = runComposeUiTest {
        var observed: LayoutDirection? = null
        setContent {
            AppLocale(AppLanguage(code = "ar", isLtr = false), AppLanguage(code = "ar", isLtr = false)) {
                observed = LocalLayoutDirection.current
            }
        }
        waitForIdle()

        assertEquals(LayoutDirection.Rtl, observed)
    }

    @Test
    fun `resolvedLanguage drives the layout direction, not the pinned language`() = runComposeUiTest {
        var observed: LayoutDirection? = null
        setContent {
            AppLocale(
                language = AppLanguage.System,
                resolvedLanguage = AppLanguage(code = "ar", isLtr = false),
            ) {
                observed = LocalLayoutDirection.current
            }
        }
        waitForIdle()

        // System's own isLtr is a placeholder; the resolved language is the one with an answer.
        assertEquals(LayoutDirection.Rtl, observed)
    }

    @Test
    fun `a language change keeps remembered state in content`() = runComposeUiTest {
        // Android invalidates string resources through LocalConfiguration rather than by tearing the
        // subtree down, so a language switch must not cost the user their scroll position, their
        // open sheet, or a half-filled form. iOS has no such mechanism and does tear it down — see
        // PlatformAppLocale's iOS actual.
        var language by mutableStateOf(AppLanguage(code = "en", isLtr = true))
        var nextId = 0
        var lastRememberedId = -1
        setContent {
            AppLocale(language, language) {
                lastRememberedId = remember { nextId++ }
            }
        }
        waitForIdle()
        assertEquals(0, lastRememberedId)

        language = AppLanguage(code = "ar", isLtr = false)
        waitForIdle()

        assertEquals(0, lastRememberedId)
    }

    @Test
    fun `the language is in force on the process default before content composes`() = runComposeUiTest {
        var defaultDuringContent: String? = null
        var localeListHeadDuringContent: String? = null
        setContent {
            AppLocale(AppLanguage(code = "ar", isLtr = false), AppLanguage(code = "ar", isLtr = false)) {
                defaultDuringContent = Locale.getDefault().language
                localeListHeadDuringContent = LocaleList.getDefault()[0].language
            }
        }
        waitForIdle()

        assertEquals("ar", defaultDuringContent)
        assertEquals("ar", localeListHeadDuringContent)
    }

    @Test
    fun `a clobbered process default is re-pinned on the next composition`() = runComposeUiTest {
        var language by mutableStateOf(AppLanguage(code = "ar", isLtr = false))
        var observed: String? = null
        setContent {
            AppLocale(language, language) {
                observed = LocaleList.getDefault()[0].language
            }
        }
        waitForIdle()
        assertEquals("ar", observed)

        // What the framework does on a configuration delivery.
        Locale.setDefault(Locale.forLanguageTag("en"))
        LocaleList.setDefault(LocaleList(Locale.forLanguageTag("en")))

        language = AppLanguage(code = "ru", isLtr = true)
        waitForIdle()

        assertEquals("ru", observed)
    }

    @Test
    fun `a LocaleList head left behind by the framework is re-pinned without a language change`() =
        runComposeUiTest {
            // The residual state `LocalizedApplicationResources` exists for, reproduced exactly:
            // ConfigurationController.updateLocaleListFromAppContext rebuilds LocaleList.getDefault()
            // from the Application's resources and leaves Locale.getDefault() alone. The JVM default
            // is therefore *already right* and only the list head is wrong — and Compose resolves
            // string resources through the list, not the default.
            //
            // Deliberately without a language change: the test above moves both at once, so a guard
            // that compared only Locale.getDefault(), or that only acted when the selection changed,
            // would still pass it. This one fails unless the re-pin looks at the list head itself.
            // Driven by a configuration delivery rather than an arbitrary recomposition, because
            // that is the only thing that reaches the guard: the re-pin lives in the composable body
            // and a state change read *inside* content invalidates only content's own scope. A new
            // LocalConfiguration is what the framework actually hands down when it rebuilds the
            // locale list without recreating the activity, so it is also what the app really sees.
            val arabic = AppLanguage(code = "ar", isLtr = false)
            var delivered: Configuration by mutableStateOf(Configuration())
            var observed: String? = null
            setContent {
                CompositionLocalProvider(LocalConfiguration provides delivered) {
                    AppLocale(arabic, arabic) {
                        observed = LocaleList.getDefault()[0].language
                    }
                }
            }
            waitForIdle()
            assertEquals("ar", observed)

            // Reflection because `setDefault(LocaleList, int)` is @hide — and it has to be this
            // overload: the public one-argument form sets Locale.getDefault() to the list head, and
            // calling Locale.setDefault() afterwards makes LocaleList.getDefault() re-derive itself
            // from it. The pair can only be made to disagree the way the framework itself does it.
            LocaleList::class.java
                .getMethod("setDefault", LocaleList::class.java, Int::class.javaPrimitiveType)
                .invoke(null, LocaleList(Locale.forLanguageTag("en"), Locale.forLanguageTag("ar")), 1)
            assertEquals("ar", Locale.getDefault().language, "precondition: only the head is wrong")
            assertEquals("en", LocaleList.getDefault()[0].language)

            delivered = Configuration(delivered).apply { fontScale += 1f }
            waitForIdle()

            assertEquals("ar", observed)
        }

    @Test
    fun `the localized configuration reaches content`() = runComposeUiTest {
        var observed: String? = null
        setContent {
            AppLocale(AppLanguage(code = "ru", isLtr = true), AppLanguage(code = "ru", isLtr = true)) {
                observed = LocalConfiguration.current.locales[0].language
            }
        }
        waitForIdle()

        assertEquals("ru", observed)
    }

    @Test
    fun `the localized context reaches content`() = runComposeUiTest {
        var observed: String? = null
        setContent {
            AppLocale(AppLanguage(code = "ru", isLtr = true), AppLanguage(code = "ru", isLtr = true)) {
                observed = LocalContext.current.resources.configuration.locales[0].language
            }
        }
        waitForIdle()

        assertEquals("ru", observed)
    }

    @Test
    fun `System pins the device language rather than a fixed one`() = runComposeUiTest {
        val deviceLanguage: String = Resources.getSystem().configuration.locales[0].language
        var observed: String? = null
        setContent {
            AppLocale(
                language = AppLanguage.System,
                resolvedLanguage = AppLanguage(code = deviceLanguage, isLtr = true),
            ) {
                observed = LocalConfiguration.current.locales[0].language
            }
        }
        waitForIdle()

        assertEquals(deviceLanguage, observed)
    }
}
