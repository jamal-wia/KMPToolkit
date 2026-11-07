package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.app.Activity
import android.view.KeyEvent
import android.view.Window
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Effects entering and leaving one window in random order while foreign decorators wrap and unwrap
 * the window callback (a foreign decorator only wraps while no effect is live, so each live effect
 * shares the one decoration): the policy runs exactly once per key while an effect is live and never
 * otherwise, and when the last foreign layer and the last effect are gone the original callback is back.
 */
@RunWith(RobolectricTestRunner::class)
class WindowCallbackChainFuzzTest {

    private class Foreign(val inner: Window.Callback) : Window.Callback by inner

    @Test
    fun `random effect and foreign decorator churn runs the policy once per key while live and never after`() {
        for (seed in 1..300) {
            val random = Random(seed)
            val controller = Robolectric.buildActivity(Activity::class.java).create()
            try {
                val window: Window = controller.get().window
                val original: Window.Callback = window.callback
                var calls = 0
                val policy: (KeyEvent) -> Boolean = { calls++; false }
                val live: MutableList<KeyInterceptingWindowCallback> = mutableListOf()
                val foreign: MutableList<Foreign> = mutableListOf()
                val log = StringBuilder()
                repeat(60) { step ->
                    when (random.nextInt(4)) {
                        0 -> { live += acquireInstallation(window, policy); log.append("acq ") }
                        1 -> if (live.isNotEmpty()) { releaseInstallation(window, live.removeAt(random.nextInt(live.size))); log.append("rel ") }
                        2 -> if (live.isEmpty()) { val f = Foreign(window.callback); foreign += f; window.callback = f; log.append("wrap ") }
                        else -> if (foreign.isNotEmpty() && window.callback === foreign.last()) {
                            window.callback = foreign.removeAt(foreign.size - 1).inner; log.append("unwrap ")
                        }
                    }
                    calls = 0
                    window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
                    assertEquals(if (live.isEmpty()) 0 else 1, calls, "seed $seed step $step ($log): policy calls per key")
                }
                live.toList().forEach { releaseInstallation(window, it); live.remove(it) }
                while (foreign.isNotEmpty() && window.callback === foreign.last()) window.callback = foreign.removeAt(foreign.size - 1).inner
                calls = 0
                window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
                assertEquals(0, calls, "seed $seed ($log): policy ran after every effect left")
                if (foreign.isEmpty() && window.callback !is KeyInterceptingWindowCallback) assertSame(original, window.callback, "seed $seed ($log)")
            } finally {
                controller.close()
            }
        }
    }
}
