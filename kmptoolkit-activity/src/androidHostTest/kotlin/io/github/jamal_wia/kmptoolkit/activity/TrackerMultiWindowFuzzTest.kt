package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * Several windows of one app resumed at once, walked at random (resume, pause, finish, destroy) and compared
 * with a model written from the contract alone: the activity that is resumed right now is the most recently
 * resumed one that is still resumed, not finishing and not destroyed; every resume reaches a listener once.
 * Seeded and capped: 40 walks of at most 40 events over five windows.
 */
@RunWith(AndroidJUnit4::class)
class TrackerMultiWindowFuzzTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker = LifecycleActivityTracker(application) { it !is Picker }.also { it.register() }
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        tracker.release()
        controllers.forEach { it.close() }
    }

    /** A screen of another app's kind that this tracker is not willing to answer with (a picker over the app). */
    private class Picker : Activity()

    private enum class Phase { Fresh, Resumed, Paused, Dead }

    @Test
    fun `the tracker names the newest resumed live window after every event of a random walk`() {
        val random = Random(20261108)
        repeat(40) { walk ->
            val windows: List<ActivityController<Activity>> = List(6) { j ->
                @Suppress("UNCHECKED_CAST")
                val window: ActivityController<Activity> = (
                    if (j >= 4) Robolectric.buildActivity(Picker::class.java) else Robolectric.buildActivity(Activity::class.java)
                    ) as ActivityController<Activity>
                window.also { controllers.add(it) }
            }
            val tracked: (Int) -> Boolean = { j -> j < 4 }
            val phase: MutableList<Phase> = MutableList(6) { Phase.Fresh }
            val order: MutableList<Int> = mutableListOf() // currently resumed windows, the newest last
            val delivered: MutableList<Activity> = mutableListOf()
            val subscription = tracker.addOnActivityResumedListener { delivered += it }
            val trail: MutableList<String> = mutableListOf()
            var expectedDeliveries = 0

            repeat(random.nextInt(5, 41)) {
                val i: Int = random.nextInt(6)
                when (phase[i]) {
                    Phase.Fresh -> {
                        trail += "start$i"
                        windows[i].setup()
                        phase[i] = Phase.Resumed
                        if (tracked(i)) { order += i; expectedDeliveries++ }
                    }

                    Phase.Resumed -> when (random.nextInt(4)) {
                        3 -> {
                            trail += "rotate$i"
                            windows[i].recreate()
                            if (tracked(i)) { order -= i; order += i; expectedDeliveries++ }
                        }

                        0 -> { trail += "pause$i"; windows[i].pause(); phase[i] = Phase.Paused; order -= i }
                        1 -> trail += "finish$i".also { windows[i].get().finish() }
                        else -> {
                            trail += "destroy$i"
                            windows[i].pause().stop().destroy()
                            phase[i] = Phase.Dead
                            order -= i
                        }
                    }

                    Phase.Paused -> if (random.nextBoolean() && !windows[i].get().isFinishing) {
                        trail += "resume$i"
                        windows[i].resume()
                        phase[i] = Phase.Resumed
                        if (tracked(i)) { order -= i; order += i; expectedDeliveries++ }
                    } else {
                        trail += "destroy$i"
                        windows[i].stop().destroy()
                        phase[i] = Phase.Dead
                    }

                    Phase.Dead -> Unit
                }
                val expected: Activity? = order.lastOrNull { j ->
                    !windows[j].get().isFinishing && !windows[j].get().isDestroyed
                }?.let { j -> windows[j].get() }
                val actual: Activity? = tracker.withActivity { it }
                // A listener added now is replayed the same window (or nothing), never another one.
                val replayed: MutableList<Activity> = mutableListOf()
                tracker.addOnActivityResumedListener { replayed += it }.cancel()
                assertEquals(listOfNotNull(actual), replayed, "walk $walk $trail: the replay disagrees with withActivity")
                if (expected == null) {
                    assertEquals(null, actual, "walk $walk $trail")
                } else {
                    assertSame(expected, actual, "walk $walk $trail")
                }
            }
            assertEquals(expectedDeliveries, delivered.size, "walk $walk $trail: every resume reaches the listener once")
            subscription.cancel()
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
