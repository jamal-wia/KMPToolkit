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
import kotlin.test.assertFailsWith
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

    /** A layer over [delegate] whose hook is held by one effect reading [interceptor]. */
    private fun layer(delegate: Window.Callback, interceptor: (KeyEvent) -> Boolean): KeyInterceptingWindowCallback =
        KeyInterceptingWindowCallback(delegate, WindowKeyHook().also { hook -> hook.take(interceptor) })

    private val volumeUp = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)

    @Test
    fun `a consumed key does not reach the delegate`() {
        val callback = layer(delegate) { true }

        assertTrue(callback.dispatchKeyEvent(volumeUp))
        assertFalse("dispatchKeyEvent" in calls)
    }

    @Test
    fun `a declined key reaches the delegate and its answer is returned`() {
        val callback = layer(delegate) { false }

        assertFalse(callback.dispatchKeyEvent(volumeUp))
        assertEquals(listOf("dispatchKeyEvent"), calls)
    }

    @Test
    fun `the java default methods are forwarded to the delegate`() {
        val callback = layer(delegate) { false }

        callback.onProvideKeyboardShortcuts(mutableListOf<KeyboardShortcutGroup>(), null as Menu?, 0)
        callback.onPointerCaptureChanged(true)

        assertEquals(listOf("onProvideKeyboardShortcuts", "onPointerCaptureChanged"), calls)
    }

    @Test
    fun `other members are delegated`() {
        val callback = layer(delegate) { true }

        callback.onContentChanged()

        assertEquals(listOf("onContentChanged"), calls)
    }

    private class Foreign(val inner: Window.Callback) : Window.Callback by inner

    @Test
    fun `two layers of one hook with a foreign decorator between them run the policy once per key`() {
        var policyCalls = 0
        val hook = WindowKeyHook().also { it.take { policyCalls++; false } }
        val below = KeyInterceptingWindowCallback(delegate, hook)
        val outer = KeyInterceptingWindowCallback(Foreign(below), hook)

        assertFalse(outer.dispatchKeyEvent(volumeUp))
        assertEquals(1, policyCalls, "the policy asked once per key, not once per layer")
        assertEquals(listOf("dispatchKeyEvent"), calls, "the key still reached the dialog's own callback once")
    }

    @Test
    fun `a policy that redispatches into the same window does not recurse into itself`() {
        var policyCalls = 0
        val hook = WindowKeyHook()
        lateinit var outer: KeyInterceptingWindowCallback
        hook.take { event -> policyCalls++; outer.dispatchKeyEvent(event); false }
        outer = KeyInterceptingWindowCallback(delegate, hook)

        outer.dispatchKeyEvent(volumeUp)

        assertEquals(1, policyCalls, "the redispatched key was offered to the policy again")
    }

    @Test
    fun `a policy that throws does not leave the guard closed`() {
        var policyCalls = 0
        var fail = true
        val hook = WindowKeyHook().also {
            it.take { policyCalls++; if (fail) error("policy failure"); false }
        }
        val callback = KeyInterceptingWindowCallback(delegate, hook)

        assertFailsWith<IllegalStateException> { callback.dispatchKeyEvent(volumeUp) }
        fail = false
        callback.dispatchKeyEvent(volumeUp)

        assertEquals(2, policyCalls, "the next key must consult the policy again")
    }
}
