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

/** A layer revived as the outermost callback keeps the policy of the effect that created it. */
@RunWith(RobolectricTestRunner::class)
class RevivedLayerStalePolicyTest {

    private class Foreign(val inner: Window.Callback) : Window.Callback by inner

    @Test
    fun `an effect entering under a revived layer consults the policy it entered with`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val window: Window = controller.get().window
            var firstCalls = 0
            var secondCalls = 0
            val first = acquireInstallation(window, { firstCalls++; false })
            val foreign = Foreign(window.callback)
            window.callback = foreign
            releaseInstallation(window, first)
            window.callback = foreign.inner
            val second = acquireInstallation(window, { secondCalls++; false })
            window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
            assertEquals(1, secondCalls, "the policy the second effect passed in was not consulted")
            assertEquals(0, firstCalls, "the first effect's policy ran after its effect left")
            releaseInstallation(window, second)
        } finally {
            controller.close()
        }
    }
}
