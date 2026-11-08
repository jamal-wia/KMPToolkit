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

/** A foreign library that puts back the callback it saw earlier drops our live layer from the chain. */
@RunWith(RobolectricTestRunner::class)
class RestoredCallbackClobberTest {

    @Test
    fun `an effect entering after a foreign restore of an older callback still gets the policy`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val window: Window = controller.get().window
            var calls = 0
            val policy: (KeyEvent) -> Boolean = { calls++; false }
            val saved: Window.Callback = window.callback
            val first = acquireInstallation(window, policy)
            window.callback = saved
            val second = acquireInstallation(window, policy)
            window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
            assertEquals(1, calls, "the second effect is live but the policy did not run for it")
            releaseInstallation(window, second)
            releaseInstallation(window, first)
            assertSame(saved, window.callback, "after both releases the callback is the one the foreign library put back")
        } finally {
            controller.close()
        }
    }
}
