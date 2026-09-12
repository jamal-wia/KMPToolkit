package io.github.jamal_wia.kmptoolkit.language.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import io.github.jamal_wia.kmptoolkit.language.AppLanguage
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `AppLocale` on the desktop JVM: the language is the process default before content composes, a
 * default moved behind the composition's back is put right on the next composition, and a language
 * change rebuilds the subtree — the only way a desktop string resource resolves again.
 */
@OptIn(ExperimentalTestApi::class)
class AppLocaleDesktopTest {

    private val originalDefault: Locale = Locale.getDefault()

    @AfterTest
    fun restoreDefault() {
        Locale.setDefault(originalDefault)
    }

    @Test
    fun `the language is the process default before content composes`() = runComposeUiTest {
        val russian = AppLanguage("ru")
        var defaultDuringContent: Locale? = null
        setContent {
            AppLocale(russian, russian) { defaultDuringContent = Locale.getDefault() }
        }
        waitForIdle()

        assertEquals(Locale.forLanguageTag("ru"), defaultDuringContent)
    }

    @Test
    fun `a default moved behind the composition is re-pinned on the next composition`() =
        runComposeUiTest {
            var language by mutableStateOf(AppLanguage("ru"))
            var observed: Locale? = null
            setContent {
                AppLocale(language, language) { observed = Locale.getDefault() }
            }
            waitForIdle()

            // Another library in the process calls Locale.setDefault, then the selection changes.
            Locale.setDefault(Locale.forLanguageTag("en"))
            language = AppLanguage("de")
            waitForIdle()

            assertEquals(Locale.forLanguageTag("de"), observed)
        }

    @Test
    fun `a language change rebuilds the subtree`() = runComposeUiTest {
        // Desktop has no configuration to invalidate string resources through, so the subtree is
        // keyed on the language. The cost — remembered state does not survive a switch — is part of
        // the contract, and asserted rather than left to be discovered.
        var language by mutableStateOf(AppLanguage("ru"))
        var nextId = 0
        var lastRememberedId = -1
        setContent {
            AppLocale(language, language) { lastRememberedId = remember { nextId++ } }
        }
        waitForIdle()
        assertEquals(0, lastRememberedId)

        language = AppLanguage("de")
        waitForIdle()

        assertEquals(1, lastRememberedId)
    }

    @Test
    fun `the resolved language drives layout direction`() = runComposeUiTest {
        var observed: LayoutDirection? = null
        setContent {
            AppLocale(language = AppLanguage.System, resolvedLanguage = AppLanguage("ar")) {
                observed = LocalLayoutDirection.current
            }
        }
        waitForIdle()

        assertEquals(LayoutDirection.Rtl, observed)
    }
}
