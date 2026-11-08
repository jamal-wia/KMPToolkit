package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.os.Build
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The effect on every API level from the library minimum up: in a dialog window the policy runs once per key
 * and the original callback comes back when the effect leaves; outside a dialog window the unhandled-key
 * listener exists only from API 28 (below it the effect composes, leaves and registers nothing, without
 * touching an API the level does not have).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 28, 30, 34])
@OptIn(ExperimentalTestApi::class)
class DialogWindowEffectApiMatrixTest {

    @After
    fun clearPolicy() {
        DialogWindowHardwareKeyPolicy.uninstall()
    }

    private fun platformListenerCount(view: View): Int {
        val info: Any = View::class.java.getDeclaredField("mListenerInfo")
            .apply { isAccessible = true }.get(view) ?: return 0
        val list: List<*> = info.javaClass.getDeclaredField("mUnhandledKeyListeners")
            .apply { isAccessible = true }.get(info) as? List<*> ?: return 0
        return list.size
    }

    @Test
    fun `in a dialog window two effects run the policy once per key and leaving restores the callback`() = runComposeUiTest {
        var calls = 0
        DialogWindowHardwareKeyPolicy.install { _ -> calls++; false }
        var window: Window? = null
        var original: Window.Callback? = null
        var shown by mutableStateOf(true)
        setContent {
            Dialog(onDismissRequest = {}) {
                window = (LocalView.current.parent as? DialogWindowProvider)?.window
                if (original == null) original = window?.callback
                if (shown) {
                    DialogWindowHardwareKeyEffect()
                    DialogWindowHardwareKeyEffect()
                }
            }
        }
        waitForIdle()
        val w: Window = assertNotNull(window)
        w.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(1, calls, "once per key")

        shown = false
        waitForIdle()
        assertSame(original, w.callback, "the dialog's own callback must be back")
        calls = 0
        w.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(0, calls, "no policy after the effect left")
    }

    @Test
    fun `outside a dialog window the listener exists from API 28 and the effect is inert below it`() = runComposeUiTest {
        DialogWindowHardwareKeyPolicy.install { false }
        var view: View? = null
        var shown by mutableStateOf(false)
        setContent {
            view = LocalView.current
            if (shown) DialogWindowHardwareKeyEffect()
        }
        waitForIdle()
        val root: View = assertNotNull(view)
        repeat(5) {
            shown = true
            waitForIdle()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) assertEquals(1, platformListenerCount(root), "API ${Build.VERSION.SDK_INT}: one listener while composed")
            shown = false
            waitForIdle()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) assertEquals(0, platformListenerCount(root), "API ${Build.VERSION.SDK_INT}: none after leaving")
        }
    }
}
