package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.Window
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window-callback decorator on its own: key dispatch goes to the interceptor first, everything
 * else — including the two Java default methods Kotlin's `by` delegation does not forward — reaches
 * the dialog's own callback.
 */
@RunWith(RobolectricTestRunner::class)
class KeyInterceptingWindowCallbackTest {

    private val calls = mutableListOf<String>()

    /** A Window.Callback that records every member invoked on it and answers `false`/no-op. */
    private val delegate: Window.Callback = Proxy.newProxyInstance(
        Window.Callback::class.java.classLoader,
        arrayOf(Window.Callback::class.java),
    ) { _, method, _ ->
        calls += method.name
        if (method.returnType == java.lang.Boolean.TYPE) false else null
    } as Window.Callback

    private val volumeUp = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)

    @Test
    fun `a consumed key does not reach the delegate`() {
        val callback = KeyInterceptingWindowCallback(delegate) { true }

        assertTrue(callback.dispatchKeyEvent(volumeUp))
        assertFalse("dispatchKeyEvent" in calls)
    }

    @Test
    fun `a declined key reaches the delegate and its answer is returned`() {
        val callback = KeyInterceptingWindowCallback(delegate) { false }

        assertFalse(callback.dispatchKeyEvent(volumeUp))
        assertEquals(listOf("dispatchKeyEvent"), calls)
    }

    @Test
    fun `the java default methods are forwarded to the delegate`() {
        val callback = KeyInterceptingWindowCallback(delegate) { false }

        callback.onProvideKeyboardShortcuts(mutableListOf<KeyboardShortcutGroup>(), null as Menu?, 0)
        callback.onPointerCaptureChanged(true)

        assertEquals(listOf("onProvideKeyboardShortcuts", "onPointerCaptureChanged"), calls)
    }

    @Test
    fun `other members are delegated`() {
        val callback = KeyInterceptingWindowCallback(delegate) { true }

        callback.onContentChanged()

        assertEquals(listOf("onContentChanged"), calls)
    }
}
