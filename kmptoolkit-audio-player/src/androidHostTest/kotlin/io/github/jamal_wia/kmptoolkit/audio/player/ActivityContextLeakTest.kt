package io.github.jamal_wia.kmptoolkit.audio.player

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** A plain screen the leak probes create and destroy. */
class PlayerLeakProbeActivity : Activity()

/**
 * The factories take any `Context` and promise to keep the application context only. A screen that is
 * rotated or finished while its player outlives it (an application-scoped player, a view model that is
 * kept across a configuration change) must not be held by the player or its engine.
 */
@RunWith(AndroidJUnit4::class)
class ActivityContextLeakTest {

    private fun createAndDestroyScreen(player: Array<AudioPlayer?>): WeakReference<Activity> {
        val controller = Robolectric.buildActivity(PlayerLeakProbeActivity::class.java).setup()
        val activity: Activity = controller.get()
        player[0] = createAudioPlayer(activity)
        val reference = WeakReference(activity)
        controller.pause().stop().destroy()
        return reference
    }

    private fun collected(reference: WeakReference<*>): Boolean {
        repeat(60) {
            System.gc()
            System.runFinalization()
            if (reference.get() == null) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun `a destroyed screen is not kept alive by the player created from it`() {
        val holder = arrayOfNulls<AudioPlayer>(1)
        val reference: WeakReference<Activity> = createAndDestroyScreen(holder)

        assertTrue(collected(reference), "the destroyed activity is still reachable through the player or its engine")
        holder[0]!!.release()
    }
}
