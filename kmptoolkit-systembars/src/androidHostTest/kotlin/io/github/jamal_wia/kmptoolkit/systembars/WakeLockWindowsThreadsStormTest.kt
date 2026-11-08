package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Six real threads flip two controllers while the main thread pumps its looper and one of two resumed windows
 * pauses and resumes. The last call decides: after a final release no window carries the flag, after a final
 * hold the newest resumed window does.
 */
@RunWith(AndroidJUnit4::class)
class WakeLockWindowsThreadsStormTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val activityAccess: ActivityAccess = createActivityAccess(application)
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()
    private val looper = shadowOf(Looper.getMainLooper())

    @AfterTest
    fun tearDown() {
        activityAccess.release()
        controllers.forEach { it.close() }
    }

    private fun Activity.flagged(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    @Test
    fun `threads flipping two controllers while a window blinks end with the last call winning on both windows`() {
        repeat(10) { round ->
            val left: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup()
            val right: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup()
            val wake: List<AndroidScreenWakeLockController> = List(2) { AndroidScreenWakeLockController(activityAccess) }
            val finished = AtomicInteger()
            val threads: List<Thread> = List(6) { t ->
                Thread {
                    val random = Random(round * 31 + t)
                    repeat(200) { wake[random.nextInt(2)].setKeepScreenOn(random.nextBoolean()) }
                    finished.incrementAndGet()
                }.also { it.isDaemon = true; it.start() }
            }
            val deadline: Long = System.nanoTime() + 20_000_000_000L
            while (finished.get() < 6 && System.nanoTime() < deadline) {
                right.pause(); right.resume()
                looper.idle()
            }
            threads.forEach { it.join(5_000) }
            assertTrue(threads.none { it.isAlive }, "round $round: a thread did not end")
            looper.idle()
            val finalHold: Boolean = round % 2 == 0
            wake.forEach { it.setKeepScreenOn(finalHold) }
            looper.idle()
            if (finalHold) {
                assertTrue(right.get().flagged(), "round $round: held, the newest window has no flag")
            } else {
                assertFalse(right.get().flagged(), "round $round: released, the right window keeps the flag")
                assertFalse(left.get().flagged(), "round $round: released, the left window keeps the flag")
            }
            wake.forEach { it.setKeepScreenOn(false) }
            looper.idle()
            listOf(left, right).forEach { it.get().finish(); it.pause().stop().destroy() }
        }
    }
}
