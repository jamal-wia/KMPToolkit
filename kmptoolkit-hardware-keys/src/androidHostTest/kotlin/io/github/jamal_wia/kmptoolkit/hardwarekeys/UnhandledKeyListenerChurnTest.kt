package io.github.jamal_wia.kmptoolkit.hardwarekeys

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
 * The effect outside a dialog window registers one unhandled-key listener on its view while composed
 * and takes it off again; thirty enter/leave cycles must neither stack listeners nor leave one behind.
 * Counted twice: in the view tag androidx.core keeps its listeners in (it must stay empty, the effect
 * registers with the platform directly), and in the platform view's own listener list.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class UnhandledKeyListenerChurnTest {

    @After
    fun clearPolicy() {
        DialogWindowHardwareKeyPolicy.uninstall()
    }

    private fun listenerCount(view: View): Int {
        val map = view.getTag(androidx.core.R.id.tag_unhandled_key_listeners) as? androidx.collection.SimpleArrayMap<*, *>
        return map?.size() ?: 0
    }

    private fun platformListenerCount(view: View): Int {
        val info: Any = View::class.java.getDeclaredField("mListenerInfo")
            .apply { isAccessible = true }.get(view) ?: return 0
        val list: List<*> = info.javaClass.getDeclaredField("mUnhandledKeyListeners")
            .apply { isAccessible = true }.get(info) as? List<*> ?: return 0
        return list.size
    }

    @Test
    fun `thirty enter and leave cycles leave exactly the listeners of the effects composed`() = runComposeUiTest {
        DialogWindowHardwareKeyPolicy.install { false }
        var view: View? = null
        var shown by mutableStateOf(false)
        setContent {
            view = LocalView.current
            if (shown) DialogWindowHardwareKeyEffect()
        }
        waitForIdle()
        val root: View = assertNotNull(view)
        repeat(30) { cycle ->
            shown = true
            waitForIdle()
            shown = false
            waitForIdle()
        }
        assertEquals(0, listenerCount(root), "after 30 enter/leave cycles the view still holds registered listeners")
        assertEquals(0, platformListenerCount(root), "after 30 enter/leave cycles the platform view still holds listeners")
    }

    @Test
    fun `one platform listener per view while effects are composed, however many`() = runComposeUiTest {
        DialogWindowHardwareKeyPolicy.install { false }
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
        assertEquals(1, platformListenerCount(root), "one effect")
        two = true
        waitForIdle()
        assertEquals(1, platformListenerCount(root), "two effects share one listener")
        one = false
        waitForIdle()
        assertEquals(1, platformListenerCount(root), "the listener outlives the first effect that left")
        two = false
        waitForIdle()
        assertEquals(0, platformListenerCount(root), "the last effect to leave removes it")
    }
}
