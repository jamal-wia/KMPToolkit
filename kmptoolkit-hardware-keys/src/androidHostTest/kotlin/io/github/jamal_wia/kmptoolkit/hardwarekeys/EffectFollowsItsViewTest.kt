package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The effect belongs to the view it was composed with (the keys of its `DisposableEffect`): when the view the
 * composition provides changes, the unhandled-key listener leaves the old view and lands on the new one, and a view
 * that is no longer provided keeps nothing. A listener left on the old view would keep a window the app moved
 * away from consulting the process-wide policy, and the new view would get no key handling at all.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class EffectFollowsItsViewTest {

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
    fun `switching the provided view moves the listener and ten round trips leave exactly one`() = runComposeUiTest {
        DialogWindowHardwareKeyPolicy.install { false }
        var first: View? = null
        var second: View? = null
        var useFirst by mutableStateOf(true)
        setContent {
            val context = LocalContext.current
            val a: View = androidx.compose.runtime.remember { View(context) }
            val b: View = androidx.compose.runtime.remember { View(context) }
            first = a
            second = b
            CompositionLocalProvider(LocalView provides if (useFirst) a else b) {
                DialogWindowHardwareKeyEffect()
            }
        }
        waitForIdle()
        val a: View = checkNotNull(first)
        val b: View = checkNotNull(second)
        assertEquals(1, platformListenerCount(a), "the effect starts on the first view")
        assertEquals(0, platformListenerCount(b))

        repeat(10) {
            useFirst = false
            waitForIdle()
            assertEquals(0, platformListenerCount(a), "the old view keeps nothing")
            assertEquals(1, platformListenerCount(b), "the new view has the listener")
            useFirst = true
            waitForIdle()
            assertEquals(1, platformListenerCount(a))
            assertEquals(0, platformListenerCount(b))
        }
    }
}
