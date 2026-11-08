package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.view.KeyEvent
import android.view.Window
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The decoration forwards every `Window.Callback` member, abstract or Java default, of the SDK under test
 * to the callback it wraps: each one reaches the delegate exactly once, whatever the platform adds.
 */
@RunWith(RobolectricTestRunner::class)
class WindowCallbackForwardingTest {

    private fun dummy(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        KeyEvent::class.java -> KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        android.view.MotionEvent::class.java -> android.view.MotionEvent.obtain(0L, 0L, 0, 0f, 0f, 0)
        android.view.View::class.java -> android.view.View(org.robolectric.RuntimeEnvironment.getApplication())
        android.view.WindowManager.LayoutParams::class.java -> android.view.WindowManager.LayoutParams()
        android.view.accessibility.AccessibilityEvent::class.java -> android.view.accessibility.AccessibilityEvent()
        android.view.SearchEvent::class.java -> android.view.SearchEvent(null)
        else -> if (type.isInterface) {
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, _ -> if (m.returnType == java.lang.Boolean.TYPE) false else null }
        } else {
            runCatching { type.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }.getOrNull()
        }
    }

    @Test
    fun `every window callback method reaches the wrapped callback once`() {
        val seen = mutableListOf<String>()
        val handler = InvocationHandler { _, method: Method, _ ->
            seen += method.name + method.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Void.TYPE -> null
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        }
        val delegate = Proxy.newProxyInstance(
            Window.Callback::class.java.classLoader, arrayOf(Window.Callback::class.java), handler,
        ) as Window.Callback
        val decoration = KeyInterceptingWindowCallback(delegate, WindowKeyHook().also { it.take { false } })
        val expected = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (method in Window.Callback::class.java.methods) {
            val args: Array<Any?> = method.parameterTypes.map(::dummy).toTypedArray()
            if (method.parameterTypes.indices.any { i -> args[i] == null && !method.parameterTypes[i].isPrimitive }) { skipped += method.name; continue }
            expected += method.name + method.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
            method.invoke(decoration, *args)
        }
        assertTrue(skipped.size <= 3, "could not build arguments for $skipped")
        assertTrue(expected.size >= 15, "reflection found only ${expected.size} members")
        assertEquals(expected.sorted(), seen.sorted(), "members not forwarded exactly once")
    }
}
