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
import org.robolectric.annotation.Config

/**
 * The wake lock on the API levels the library supports, not only the module's default image. Two controllers
 * over one tracker and three windows, walked at random; after EVERY event, written from the contract: with no
 * holder no live window carries the flag, and with a holder every live resumed window carries it (the hold
 * "puts the flag on each" resumed window, not only on the ones that resume after it).
 * Seeded and capped: 8 walks of at most 30 events per level.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 28, 30, 34])
class WakeLockApiMatrixTest {

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
    fun `every live resumed window carries the flag while anyone holds and none carries it when nobody does`() {
        val random = Random(android.os.Build.VERSION.SDK_INT * 104729L)
        repeat(8) { walk ->
            val wake: List<AndroidScreenWakeLockController> = List(2) { AndroidScreenWakeLockController(activityAccess) }
            val holds: BooleanArray = BooleanArray(2)
            val windows: List<ActivityController<Activity>> =
                List(3) { Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) } }
            val phase: MutableList<Phase> = MutableList(3) { Phase.Fresh }
            val trail: MutableList<String> = mutableListOf()

            fun check() {
                looper.idle()
                windows.forEachIndexed { j, w ->
                    val live: Boolean = phase[j] != Phase.Dead && !w.get().isFinishing && !w.get().isDestroyed
                    if (!live) return@forEachIndexed
                    if (holds.none { it }) {
                        assertFalse(w.get().flagged(), "walk $walk $trail: window $j keeps the screen awake with nobody holding")
                    } else if (phase[j] == Phase.Resumed) {
                        assertTrue(w.get().flagged(), "walk $walk $trail: window $j is resumed under a hold without the flag")
                    }
                }
            }

            repeat(random.nextInt(5, 31)) {
                if (random.nextInt(4) == 0) {
                    val c: Int = random.nextInt(2)
                    holds[c] = !holds[c]
                    trail += "c$c=${holds[c]}"
                    wake[c].setKeepScreenOn(holds[c])
                } else {
                    val i: Int = random.nextInt(3)
                    when (phase[i]) {
                        Phase.Fresh -> { trail += "start$i"; windows[i].setup(); phase[i] = Phase.Resumed }
                        Phase.Resumed -> when (random.nextInt(4)) {
                            3 -> { trail += "rotate$i"; windows[i].recreate() }
                            0 -> { trail += "pause$i"; windows[i].pause(); phase[i] = Phase.Paused }
                            1 -> { trail += "finish$i"; windows[i].get().finish() }
                            else -> { trail += "destroy$i"; windows[i].pause().stop().destroy(); phase[i] = Phase.Dead }
                        }

                        Phase.Paused ->
                            if (!windows[i].get().isFinishing && random.nextBoolean()) {
                                trail += "resume$i"; windows[i].resume(); phase[i] = Phase.Resumed
                            } else {
                                trail += "destroy$i"; windows[i].stop().destroy(); phase[i] = Phase.Dead
                            }

                        Phase.Dead -> Unit
                    }
                }
                check()
            }
            for (c in 0..1) { holds[c] = false; wake[c].setKeepScreenOn(false) }
            trail += "release-all"
            check()
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
