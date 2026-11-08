package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Two controllers over one tracker and three windows, walked at random: holds and releases, windows resuming,
 * pausing, finishing and being destroyed. Two things must hold after every event, written from the contract:
 * when no controller holds, no window that can still be shown carries the flag; and a window that resumes
 * while a controller holds carries it.
 */
@RunWith(AndroidJUnit4::class)
class WakeLockWindowWalkFuzzTest {

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

    private enum class Phase { Fresh, Resumed, Paused, Dead }

    @Test
    fun `after every event of a random walk no live window keeps the flag without a holder and a window resumed under a holder has it`() {
        val random = Random(1108)
        repeat(40) { walk ->
            val wake: List<AndroidScreenWakeLockController> = List(2) { AndroidScreenWakeLockController(activityAccess) }
            val holds: BooleanArray = BooleanArray(2)
            val windows: List<ActivityController<Activity>> =
                List(3) { Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) } }
            val phase: MutableList<Phase> = MutableList(3) { Phase.Fresh }
            val trail: MutableList<String> = mutableListOf()

            fun check(justResumed: Int?) {
                looper.idle()
                if (holds.none { it }) {
                    windows.forEachIndexed { j, w ->
                        if (phase[j] != Phase.Dead && !w.get().isFinishing) {
                            assertFalse(w.get().flagged(), "walk $walk $trail: window $j keeps the screen awake with nobody holding")
                        }
                    }
                }
                if (justResumed != null && holds.any { it }) {
                    assertTrue(windows[justResumed].get().flagged(), "walk $walk $trail: window $justResumed resumed under a hold without the flag")
                }
            }

            repeat(random.nextInt(5, 41)) {
                val roll: Int = random.nextInt(4)
                var justResumed: Int? = null
                if (roll == 0) {
                    val c: Int = random.nextInt(2)
                    holds[c] = !holds[c]
                    trail += "c$c=${holds[c]}"
                    wake[c].setKeepScreenOn(holds[c])
                } else {
                    val i: Int = random.nextInt(3)
                    when (phase[i]) {
                        Phase.Fresh -> { trail += "start$i"; windows[i].setup(); phase[i] = Phase.Resumed; justResumed = i }
                        Phase.Resumed -> when (random.nextInt(4)) {
                            3 -> { trail += "rotate$i"; windows[i].recreate(); justResumed = i }
                            0 -> { trail += "pause$i"; windows[i].pause(); phase[i] = Phase.Paused }
                            1 -> { trail += "finish$i"; windows[i].get().finish(); windows[i].pause(); phase[i] = Phase.Paused }
                            else -> { trail += "destroy$i"; windows[i].pause().stop().destroy(); phase[i] = Phase.Dead }
                        }

                        Phase.Paused ->
                            if (!windows[i].get().isFinishing && random.nextBoolean()) {
                                trail += "resume$i"; windows[i].resume(); phase[i] = Phase.Resumed; justResumed = i
                            } else {
                                trail += "destroy$i"; windows[i].stop().destroy(); phase[i] = Phase.Dead
                            }

                        Phase.Dead -> Unit
                    }
                }
                check(justResumed)
            }
            for (c in 0..1) { holds[c] = false; wake[c].setKeepScreenOn(false) }
            trail += "release-all"
            check(null)
            assertEquals(false, holds.any { it })
            windows.forEachIndexed { j, w ->
                if (phase[j] != Phase.Dead) {
                    w.get().finish()
                    if (phase[j] == Phase.Resumed) w.pause()
                    w.stop().destroy()
                }
                w.close()
                controllers.remove(w)
            }
        }
    }
}
