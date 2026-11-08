package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.view.KeyEvent
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
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The same revived-layer sequence as the unit-level test, driven through four real effects in one dialog. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class DialogRevivedLayerShadowTest {

    @After
    fun clearPolicy() {
        DialogWindowHardwareKeyPolicy.uninstall()
    }

    private class ThirdParty(val inner: Window.Callback) : Window.Callback by inner

    @Test
    fun `four effects entering and leaving around two foreign decorators run the policy once per key`() = runComposeUiTest {
        var calls = 0
        DialogWindowHardwareKeyPolicy.install { _ -> calls++; false }
        var a by mutableStateOf(false)
        var b by mutableStateOf(false)
        var c by mutableStateOf(false)
        var d by mutableStateOf(false)
        var window: Window? = null
        setContent {
            Dialog(onDismissRequest = {}) {
                window = assertNotNull((LocalView.current.parent as? DialogWindowProvider)?.window)
                if (a) DialogWindowHardwareKeyEffect()
                if (b) DialogWindowHardwareKeyEffect()
                if (c) DialogWindowHardwareKeyEffect()
                if (d) DialogWindowHardwareKeyEffect()
            }
        }
        waitForIdle()
        val shown: Window = assertNotNull(window)
        a = true; waitForIdle()
        val first = ThirdParty(shown.callback)
        shown.callback = first
        a = false; waitForIdle()
        b = true; waitForIdle()
        b = false; waitForIdle()
        shown.callback = first.inner
        c = true; waitForIdle()
        shown.callback = ThirdParty(shown.callback)
        d = true; waitForIdle()
        calls = 0
        shown.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(1, calls, "the policy ran $calls times for one key")
    }
}
