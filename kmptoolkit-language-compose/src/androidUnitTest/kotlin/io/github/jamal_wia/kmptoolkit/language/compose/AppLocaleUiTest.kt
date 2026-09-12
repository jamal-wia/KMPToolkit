package io.github.jamal_wia.kmptoolkit.language.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import io.github.jamal_wia.kmptoolkit.language.AppLanguage
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class AppLocaleUiTest {

    @Test
    fun `provides Ltr layout direction for an LTR language`() = runComposeUiTest {
        var observed: LayoutDirection? = null
        setContent {
            AppLocale(AppLanguage(code = "en", isLtr = true)) {
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
            AppLocale(AppLanguage(code = "ar", isLtr = false)) {
                observed = LocalLayoutDirection.current
            }
        }
        waitForIdle()

        assertEquals(LayoutDirection.Rtl, observed)
    }

    @Test
    fun `a language code change resets remembered state in content`() = runComposeUiTest {
        var language by mutableStateOf(AppLanguage(code = "en", isLtr = true))
        var nextId = 0
        var lastRememberedId = -1
        setContent {
            AppLocale(language) {
                lastRememberedId = remember { nextId++ }
            }
        }
        waitForIdle()
        assertEquals(0, lastRememberedId)

        language = AppLanguage(code = "ar", isLtr = false)
        waitForIdle()

        assertEquals(1, lastRememberedId)
    }

    @Test
    fun `the same language code does not reset remembered state`() = runComposeUiTest {
        var language by mutableStateOf(AppLanguage(code = "en", isLtr = true))
        var nextId = 0
        var lastRememberedId = -1
        setContent {
            AppLocale(language) {
                lastRememberedId = remember { nextId++ }
            }
        }
        waitForIdle()

        language = AppLanguage(code = "en", isLtr = true)
        waitForIdle()

        assertEquals(0, lastRememberedId)
    }
}
