package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.app.Activity
import android.view.KeyEvent
import android.view.Window
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * A decoration revived as the outermost callback (the foreign decorator above it left) is not entered into the
 * window registry again, so a foreign decorator that wraps once more hides it and the next effect stacks a second
 * live layer: the policy runs twice per key.
 */
@RunWith(RobolectricTestRunner::class)
class RevivedLayerShadowTest {

    private class Foreign(val inner: Window.Callback) : Window.Callback by inner

    private fun key(): KeyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)

    @Test
    fun `a revived layer hidden by a new foreign decorator is shared and not stacked again`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val window: Window = controller.get().window
            var calls = 0
            val policy: (KeyEvent) -> Boolean = { calls++; false }
            val a = acquireInstallation(window, policy)
            val f1 = Foreign(window.callback)
            window.callback = f1
            releaseInstallation(window, a) // inert D1 under f1
            val b = acquireInstallation(window, policy) // D2 over f1, registered
            releaseInstallation(window, b) // callback back to f1
            window.callback = f1.inner // f1 leaves: D1 (inert) is outermost again
            val c = acquireInstallation(window, policy) // revives D1
            window.callback = Foreign(window.callback) // another foreign wrap over the live D1
            val d = acquireInstallation(window, policy)
            calls = 0
            window.callback.dispatchKeyEvent(key())
            assertEquals(1, calls, "two live layers: the policy ran once per layer")
            releaseInstallation(window, d)
            releaseInstallation(window, c)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `random foreign wrap and unwrap at any time runs the policy once per key while an effect is live`() {
        for (seed in 1..200) {
            val random = Random(seed)
            val controller = Robolectric.buildActivity(Activity::class.java).create()
            try {
                val window: Window = controller.get().window
                val originalCallback: Window.Callback = window.callback
                var calls = 0
                val policy: (KeyEvent) -> Boolean = { calls++; false }
                val live: MutableList<KeyPolicyRecord.Hold> = mutableListOf()
                val foreign: MutableList<Foreign> = mutableListOf()
                val log = StringBuilder()
                repeat(50) { step ->
                    when (random.nextInt(4)) {
                        0 -> { live += acquireInstallation(window, policy); log.append("acq ") }
                        1 -> if (live.isNotEmpty()) {
                            releaseInstallation(window, live.removeAt(random.nextInt(live.size))); log.append("rel ")
                        }
                        2 -> { val f = Foreign(window.callback); foreign += f; window.callback = f; log.append("wrap ") }
                        else -> if (foreign.isNotEmpty() && window.callback === foreign.last()) {
                            window.callback = foreign.removeAt(foreign.size - 1).inner; log.append("unwrap ")
                        }
                    }
                    calls = 0
                    window.callback.dispatchKeyEvent(key())
                    assertEquals(if (live.isEmpty()) 0 else 1, calls, "seed $seed step $step ($log): policy calls per key")
                }
                // Release everything and unwrap what can be unwrapped: peeling must have left the original, or
                // a chain of only our inert layers over it, with the original never lost.
                val original: Window.Callback = originalCallback
                live.toList().forEach { releaseInstallation(window, it); live.remove(it) }
                while (foreign.isNotEmpty() && window.callback === foreign.last()) window.callback = foreign.removeAt(foreign.size - 1).inner
                calls = 0
                window.callback.dispatchKeyEvent(key())
                assertEquals(0, calls, "seed $seed ($log): policy ran after every effect left")
                var link: Window.Callback? = window.callback
                var ours = 0
                var foreignLinks = 0
                while (link != null && link !== original) {
                    link = when (link) {
                        is KeyInterceptingWindowCallback -> { ours++; link.delegate }
                        is Foreign -> { foreignLinks++; link.inner }
                        else -> null
                    }
                }
                assertEquals(true, link === original, "seed $seed ($log): the original callback was lost from the chain")
                if (foreign.isEmpty()) assertEquals(0, foreignLinks, "seed $seed ($log): a foreign layer is left that the test unwrapped")
            } finally {
                controller.close()
            }
        }
    }
}
