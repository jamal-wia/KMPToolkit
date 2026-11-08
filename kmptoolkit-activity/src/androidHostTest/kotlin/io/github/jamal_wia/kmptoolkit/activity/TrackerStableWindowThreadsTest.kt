package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * One window stays resumed the whole time while a second one pauses and resumes three hundred times on the
 * main thread and four real threads ask the tracker for "the activity that is resumed right now" without a
 * pause: the answer is never `null` and never a destroyed activity, whatever the interleaving.
 */
@RunWith(AndroidJUnit4::class)
class TrackerStableWindowThreadsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker = LifecycleActivityTracker(application).also { it.register() }
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        tracker.release()
        controllers.forEach { it.close() }
    }

    @Test
    fun `a window that stays resumed is always the answer to threads while another window blinks`() {
        val stable: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup()
        val blinking: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup()
        val stop = AtomicBoolean(false)
        val problems = ConcurrentLinkedQueue<String>()
        val samples = java.util.concurrent.atomic.AtomicInteger()
        val threads: List<Thread> = List(4) {
            Thread {
                val deadline: Long = System.nanoTime() + 20_000_000_000L
                while (!stop.get() && System.nanoTime() < deadline) {
                    val seen: Activity? = tracker.withActivity { it }
                    samples.incrementAndGet()
                    if (seen == null) problems += "null although a window stayed resumed"
                    else if (seen.isDestroyed) problems += "a destroyed activity"
                }
            }.also { it.isDaemon = true; it.start() }
        }
        repeat(300) {
            blinking.pause()
            blinking.resume()
        }
        stop.set(true)
        threads.forEach { it.join(5_000) }
        assertTrue(threads.none { it.isAlive }, "a sampling thread did not end")
        assertTrue(samples.get() > 0)
        assertTrue(problems.isEmpty(), "${problems.size} bad answers, first: ${problems.firstOrNull()}")
        assertTrue(stable.get().let { !it.isFinishing })
    }
}
