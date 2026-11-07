package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Three dialogs, with and without the effect, opened and closed in every order; a dismissed window
 * collectable; and another decorator layered on top of ours.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class DialogWindowChurnTest {

    @After
    fun clearPolicy() {
        DialogWindowHardwareKeyPolicy.uninstall()
    }

    @Composable
    private fun thisWindow(): Window = assertNotNull((LocalView.current.parent as? DialogWindowProvider)?.window)

    private class ThirdParty(val inner: Window.Callback) : Window.Callback by inner

    private fun volume(): KeyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)

    private val orders: List<List<Int>> = listOf(
        listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0),
    )

    @Test
    fun `three dialogs opened and closed in every order keep the policy while shown and restore every callback`() {
        for (order in orders) {
            runComposeUiTest {
                var calls = 0
                DialogWindowHardwareKeyPolicy.install { _ ->
                    calls++
                    false
                }
                val open: List<androidx.compose.runtime.MutableState<Boolean>> = List(3) { mutableStateOf(true) }
                val windows: Array<Window?> = arrayOfNulls(3)
                val originals: Array<Window.Callback?> = arrayOfNulls(3)
                setContent {
                    for (index in 0 until 3) {
                        if (open[index].value) {
                            Dialog(onDismissRequest = {}) {
                                val window: Window = thisWindow()
                                if (windows[index] == null) {
                                    windows[index] = window
                                    originals[index] = window.callback
                                }
                                // dialog 2 never composes the effect
                                if (index != 2) DialogWindowHardwareKeyEffect()
                            }
                        }
                    }
                }
                waitForIdle()
                for (index in 0 until 3) assertNotNull(windows[index], "order $order: dialog $index not shown")
                assertIs<KeyInterceptingWindowCallback>(windows[0]!!.callback)
                assertIs<KeyInterceptingWindowCallback>(windows[1]!!.callback)
                assertFalse(windows[2]!!.callback is KeyInterceptingWindowCallback)

                for ((step, index) in order.withIndex()) {
                    open[index].value = false
                    waitForIdle()
                    for (still in order.drop(step + 1)) {
                        if (still != 2) {
                            assertIs<KeyInterceptingWindowCallback>(
                                windows[still]!!.callback,
                                "order $order: the policy was lost on dialog $still while it is still shown",
                            )
                            val before: Int = calls
                            windows[still]!!.callback.dispatchKeyEvent(volume())
                            assertEquals(before + 1, calls, "order $order: policy did not run once on dialog $still")
                        }
                    }
                    if (index != 2) {
                        assertSame(
                            originals[index],
                            windows[index]!!.callback,
                            "order $order: the callback of dismissed dialog $index was not restored",
                        )
                    }
                }
            }
        }
    }

}
