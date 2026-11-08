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
import org.robolectric.annotation.Config

/**
 * The tracker on the API levels the library supports, not only the newest one the module's default image
 * runs: several windows of one app resumed at once, walked at random and compared with a model written from
 * the contract alone. `withActivity` is the newest resumed live tracked window, `forEachResumedActivity`
 * visits every resumed live tracked window earliest first, a replay agrees with `withActivity`, and every
 * resume reaches a listener once. Seeded and capped: 10 walks of at most 30 events over five windows per level.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 28, 29, 30, 31, 33, 34])
class TrackerApiMatrixTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker = LifecycleActivityTracker(application) { it !is Picker }.also { it.register() }
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        tracker.release()
        controllers.forEach { it.close() }
    }

    /** A screen the tracker is not willing to answer with (a picker over the app). */
    private class Picker : Activity()

    private enum class Phase { Fresh, Resumed, Paused, Dead }

    @Test
    fun `withActivity and forEachResumedActivity agree with the contract after every event on this level`() {
        val random = Random(android.os.Build.VERSION.SDK_INT * 7919L)
        repeat(10) { walk ->
            val windows: List<ActivityController<Activity>> = List(5) { j ->
                @Suppress("UNCHECKED_CAST")
                val window: ActivityController<Activity> = (
                    if (j >= 4) Robolectric.buildActivity(Picker::class.java) else Robolectric.buildActivity(Activity::class.java)
                    ) as ActivityController<Activity>
                window.also { controllers.add(it) }
            }
            val phase: MutableList<Phase> = MutableList(5) { Phase.Fresh }
            val order: MutableList<Int> = mutableListOf()
            val delivered: MutableList<Activity> = mutableListOf()
            val subscription = tracker.addOnActivityResumedListener { delivered += it }
            val trail: MutableList<String> = mutableListOf()
            var expectedDeliveries = 0

            repeat(random.nextInt(5, 31)) {
                val i: Int = random.nextInt(5)
                when (phase[i]) {
                    Phase.Fresh -> {
                        trail += "start$i"
                        windows[i].setup()
                        phase[i] = Phase.Resumed
                        if (i < 4) { order += i; expectedDeliveries++ }
                    }

                    Phase.Resumed -> when (random.nextInt(4)) {
                        3 -> {
                            trail += "rotate$i"
                            windows[i].recreate()
                            if (i < 4) { order -= i; order += i; expectedDeliveries++ }
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
                        if (i < 4) { order -= i; order += i; expectedDeliveries++ }
                    } else {
                        trail += "destroy$i"
                        windows[i].stop().destroy()
                        phase[i] = Phase.Dead
                    }

                    Phase.Dead -> Unit
                }
                val live: List<Activity> = order
                    .map { j -> windows[j].get() }
                    .filter { !it.isFinishing && !it.isDestroyed }
                val actual: Activity? = tracker.withActivity { it }
                val visited: MutableList<Activity> = mutableListOf()
                tracker.forEachResumedActivity { visited += it }
                if (live.isEmpty()) {
                    assertEquals(null, actual, "walk $walk $trail")
                } else {
                    assertSame(live.last(), actual, "walk $walk $trail")
                }
                assertEquals(live.size, visited.size, "walk $walk $trail: forEachResumedActivity visited a different count")
                live.zip(visited).forEach { (want, got) -> assertSame(want, got, "walk $walk $trail: visit order") }
                val replayed: MutableList<Activity> = mutableListOf()
                tracker.addOnActivityResumedListener { replayed += it }.cancel()
                assertEquals(listOfNotNull(actual), replayed, "walk $walk $trail: the replay disagrees with withActivity")
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
