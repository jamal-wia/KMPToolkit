package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An effect composed after the process-wide policy was replaced reads the new one (install: "read once per
 * window"); the activity-window path shares one platform listener per view, created with the first policy.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class UnhandledKeyPolicyReplacedTest {

    @After
    fun clearPolicy() {
        DialogWindowHardwareKeyPolicy.uninstall()
    }

    private fun dispatchUnhandled(view: View, event: KeyEvent) {
        val info: Any = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(view)
        @Suppress("UNCHECKED_CAST")
        val list = info.javaClass.getDeclaredField("mUnhandledKeyListeners").apply { isAccessible = true }.get(info) as List<View.OnUnhandledKeyEventListener>
        list.forEach { it.onUnhandledKeyEvent(view, event) }
    }

    @Test
    fun `an effect composed under a replaced policy consults the policy in force when it composed`() = runComposeUiTest {
        var firstCalls = 0
        var secondCalls = 0
        DialogWindowHardwareKeyPolicy.install { firstCalls++; false }
        var view: View? = null
        var one by mutableStateOf(false)
        var two by mutableStateOf(false)
        setContent {
            view = LocalView.current
            if (one) DialogWindowHardwareKeyEffect()
            if (two) DialogWindowHardwareKeyEffect()
        }
        waitForIdle()
        val root: View = assertNotNull(view)
        one = true
        waitForIdle()
        DialogWindowHardwareKeyPolicy.install { secondCalls++; false }
        two = true
        waitForIdle()
        one = false // the effect that read the first policy leaves; the second one is the only one left
        waitForIdle()
        dispatchUnhandled(root, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(1, secondCalls, "the effect composed under the second policy was not served by it")
        assertEquals(0, firstCalls, "the first policy kept acting after the only effect that read it left")
    }
}
