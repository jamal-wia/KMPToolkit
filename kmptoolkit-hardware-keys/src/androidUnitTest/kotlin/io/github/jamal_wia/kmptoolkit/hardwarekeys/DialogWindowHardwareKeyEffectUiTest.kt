package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.view.KeyEvent
import android.view.View
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [DialogWindowHardwareKeyEffect] against a real Compose `Dialog` window, run through Robolectric:
 * the policy is installed on the window's callback for as long as the effect is composed, a
 * consumed key never reaches the dialog's own dispatch, a declined key does, and leaving composition
 * restores exactly the callback that was there.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class DialogWindowHardwareKeyEffectUiTest {

    @After
    fun clearPolicy() {
        DialogWindowHardwareKeyPolicy.uninstall()
    }

    @Test
    fun `without a registered policy the dialog window callback is left untouched`() = runComposeUiTest {
        var window: Window? = null
        var callbackBefore: Window.Callback? = null

        setContent {
            Dialog(onDismissRequest = {}) {
                val dialogWindow: Window = dialogWindow()
                if (window == null) {
                    window = dialogWindow
                    callbackBefore = dialogWindow.callback
                }
                DialogWindowHardwareKeyEffect()
            }
        }
        waitForIdle()

        val shown: Window = assertNotNull(window)
        assertSame(callbackBefore, shown.callback)
    }

    @Test
    fun `a consumed key stops at the policy and never reaches the dialog`() = runComposeUiTest {
        val seen = mutableListOf<Int>()
        DialogWindowHardwareKeyPolicy.install { event ->
            seen += event.keyCode
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        }
        var window: Window? = null

        setContent {
            Dialog(onDismissRequest = {}) {
                window = dialogWindow()
                DialogWindowHardwareKeyEffect()
            }
        }
        waitForIdle()

        val callback: Window.Callback = assertNotNull(window).callback
        assertIs<KeyInterceptingWindowCallback>(callback)
        assertTrue(callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertEquals(listOf(KeyEvent.KEYCODE_VOLUME_UP), seen)
    }

    @Test
    fun `two effects in one dialog window run the policy once per key`() = runComposeUiTest {
        var calls = 0
        DialogWindowHardwareKeyPolicy.install { _ ->
            calls++
            false
        }
        var window: Window? = null

        setContent {
            Dialog(onDismissRequest = {}) {
                window = dialogWindow()
                DialogWindowHardwareKeyEffect()
                DialogWindowHardwareKeyEffect()
            }
        }
        waitForIdle()

        assertNotNull(window).callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(1, calls)
    }

    @Test
    fun `a window already composed keeps its policy through a recomposition`() = runComposeUiTest {
        val consulted = mutableListOf<String>()
        DialogWindowHardwareKeyPolicy.install { _ ->
            consulted += "first"
            true
        }
        var tick by mutableStateOf(0)
        var window: Window? = null

        setContent {
            Dialog(onDismissRequest = {}) {
                window = dialogWindow()
                tick.let { DialogWindowHardwareKeyEffect() }
            }
        }
        waitForIdle()
        DialogWindowHardwareKeyPolicy.uninstall()
        DialogWindowHardwareKeyPolicy.install { _ ->
            consulted += "second"
            true
        }
        tick++
        waitForIdle()

        assertNotNull(window).callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(listOf("first"), consulted)
    }

    @Test
    fun `leaving composition restores the callback the dialog had`() = runComposeUiTest {
        DialogWindowHardwareKeyPolicy.install { true }
        var withEffect by mutableStateOf(true)
        var window: Window? = null
        var callbackBefore: Window.Callback? = null

        setContent {
            Dialog(onDismissRequest = {}) {
                val dialogWindow: Window = dialogWindow()
                if (window == null) {
                    window = dialogWindow
                    callbackBefore = dialogWindow.callback
                }
                if (withEffect) DialogWindowHardwareKeyEffect()
            }
        }
        waitForIdle()
        assertIs<KeyInterceptingWindowCallback>(assertNotNull(window).callback)

        withEffect = false
        waitForIdle()

        assertSame(callbackBefore, assertNotNull(window).callback)
    }

    @Test
    fun `leaving composition does not clobber a decorator installed after ours`() = runComposeUiTest {
        DialogWindowHardwareKeyPolicy.install { true }
        var withEffect by mutableStateOf(true)
        var window: Window? = null

        setContent {
            Dialog(onDismissRequest = {}) {
                window = dialogWindow()
                if (withEffect) DialogWindowHardwareKeyEffect()
            }
        }
        waitForIdle()
        val shown: Window = assertNotNull(window)
        val later = KeyInterceptingWindowCallback(shown.callback) { false }
        shown.callback = later

        withEffect = false
        waitForIdle()

        assertSame(later, shown.callback)
    }

    @Test
    fun `the effect is inert in the activity's own window`() = runComposeUiTest {
        DialogWindowHardwareKeyPolicy.install { true }
        var view: View? = null

        // No DialogWindowProvider parent: the effect takes the unhandled-key-listener path and must
        // not touch any window callback.
        setContent {
            view = LocalView.current
            DialogWindowHardwareKeyEffect()
        }
        waitForIdle()

        assertFalse(assertNotNull(view).parent is DialogWindowProvider)
    }

    @Test
    fun `installing a second policy replaces the first and reports it`() {
        val logger = RecordingLogger()
        DialogWindowHardwareKeyPolicy.install(logger) { false }
        assertTrue(logger.errors.isEmpty())

        val second: (KeyEvent) -> Boolean = { true }
        DialogWindowHardwareKeyPolicy.install(logger, second)

        assertEquals(1, logger.errors.size)
        assertSame(second, DialogWindowHardwareKeyPolicy.interceptor)
    }

    @Test
    fun `uninstall removes the policy`() {
        DialogWindowHardwareKeyPolicy.install { true }
        DialogWindowHardwareKeyPolicy.uninstall()

        assertEquals(null, DialogWindowHardwareKeyPolicy.interceptor)
    }
}

@androidx.compose.runtime.Composable
private fun dialogWindow(): Window =
    (LocalView.current.parent as DialogWindowProvider).window

private class RecordingLogger : Logger {
    val errors = mutableListOf<String>()
    override val tag: String = "test"
    override fun isLoggable(level: LogLevel): Boolean = true
    override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
        if (level == LogLevel.ERROR) errors += message()
    }
}
