package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.app.Activity
import android.view.KeyEvent
import android.view.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Two effects live in one window at the same time while a foreign decorator was layered on the window
 * callback between their entries: the policy must still run once per key.
 */
@RunWith(RobolectricTestRunner::class)
class ForeignDecoratorBetweenEffectsTest {

    private class ThirdParty(val inner: Window.Callback) : Window.Callback by inner

    @Test
    fun `a foreign decorator between two live effects does not run the policy twice per key`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val window: Window = controller.get().window
            var calls = 0
            val policy: (KeyEvent) -> Boolean = { calls++; false }

            val first: KeyInterceptingWindowCallback = acquireInstallation(window, policy)
            window.callback = ThirdParty(window.callback)
            val second: KeyInterceptingWindowCallback = acquireInstallation(window, policy)

            window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
            assertEquals(1, calls, "the policy ran once per decoration, not once per key")

            assertSame(first, second, "a second live layer was stacked under the foreign decorator")

            releaseInstallation(window, second)
            releaseInstallation(window, first)

            // Both effects are gone while the foreign decorator still sits on top: our layer stays in the
            // chain, inert, so the policy no longer runs, and it is in the chain exactly once.
            calls = 0
            window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
            assertEquals(0, calls, "the policy acted after both effects left")
            var layers = 0
            var link: Window.Callback? = window.callback
            while (link != null) {
                link = when (link) {
                    is ThirdParty -> link.inner
                    is KeyInterceptingWindowCallback -> { layers++; link.delegate }
                    else -> null
                }
            }
            assertEquals(1, layers, "our layer appears more than once in the chain")
        } finally {
            controller.close()
        }
    }
}
