package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Lock and re-entrancy hazards of the per-registration lock behind `cancel()` and `release()`:
 * deadlock from every side, a listener that adds, throws or cancels during a delivery, and
 * starvation of a cancelling thread.
 *
 * Raw daemon threads and bounded joins only: every wait has a hard real-time bound, and nothing
 * here can leave the test process hanging.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleActivityTrackerStormTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()
    private val trackers: MutableList<ActivityAccess> = mutableListOf()

    @AfterTest
    fun tearDown() {
        trackers.forEach { it.release() }
        controllers.forEach { it.close() }
    }

    private fun launchActivity(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }

    private fun newTracker(): LifecycleActivityTracker =
        (createActivityAccess(application) as LifecycleActivityTracker).also { trackers.add(it) }

    private fun background(name: String, action: () -> Unit): Thread =
        thread(name = "storm-$name", isDaemon = true) { action() }

    @Test
    fun `a listener that adds a listener during a delivery gets exactly one immediate call and the outer delivery does not call it`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity()
        val outerCalls = AtomicInteger()
        val innerCalls = AtomicInteger()
        val innerRef = AtomicReference<ActivitySubscription?>(null)

        tracker.addOnActivityResumedListener {
            if (outerCalls.incrementAndGet() == 1) {
                innerRef.set(tracker.addOnActivityResumedListener { innerCalls.incrementAndGet() })
                assertEquals(1, innerCalls.get(), "the replay of a listener added inside a delivery did not run inline")
            }
        }
        controller.setup()

        assertEquals(1, innerCalls.get(), "the outer delivery also called the listener that was added during it")
        controller.pause().resume()
        assertEquals(2, innerCalls.get())
        innerRef.get()!!.cancel()
        controller.pause().resume()
        assertEquals(2, innerCalls.get())
    }

    @Test
    fun `a listener that throws does not leave its lock held, so cancel and release from other threads still return`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity()
        val activity: Activity = controller.get()
        val throwing = AtomicBoolean(true)
        val calls = AtomicInteger()

        val subscription: ActivitySubscription = tracker.addOnActivityResumedListener {
            calls.incrementAndGet()
            if (throwing.get()) error("listener failure")
        }

        assertFailsWith<IllegalStateException> { tracker.onActivityResumed(activity) }
        assertEquals(1, calls.get())

        val cancelled: Thread = background("cancel") { subscription.cancel() }
        cancelled.join(5_000)
        assertFalse(cancelled.isAlive, "cancel() from another thread hangs after the listener threw: the lock leaked")

        val released: Thread = background("release") { tracker.release() }
        released.join(5_000)
        assertFalse(released.isAlive, "release() from another thread hangs after a listener threw")
        throwing.set(false)
        tracker.onActivityResumed(activity)
        assertEquals(1, calls.get(), "a cancelled listener ran again")
    }

    @Test
    fun `cancels, a release and an add from three threads while the main thread is parked in one listener do not deadlock and nothing runs afterwards`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity()
        val calls = IntArray(3).map { AtomicInteger() }
        val subs: Array<AtomicReference<ActivitySubscription?>> = Array(3) { AtomicReference(null) }
        val threads: MutableList<Thread> = mutableListOf()
        val addReturnedWhileParked = AtomicBoolean(false)

        subs[0].set(
            tracker.addOnActivityResumedListener {
                if (calls[0].incrementAndGet() == 1) {
                    threads += background("cancel-own") { subs[0].get()!!.cancel() }
                    threads += background("cancel-sibling") { subs[1].get()!!.cancel() }
                    threads += background("release") { tracker.release() }
                    val adder: Thread = background("add") {
                        tracker.addOnActivityResumedListener { calls[2].incrementAndGet() }
                        addReturnedWhileParked.set(true)
                    }
                    threads += adder
                    adder.join(2_000)
                    // The main thread also cancels the sibling and itself while other threads queue behind it.
                    subs[1].get()!!.cancel()
                    subs[0].get()!!.cancel()
                    tracker.release()
                }
            },
        )
        subs[1].set(tracker.addOnActivityResumedListener { calls[1].incrementAndGet() })

        controller.setup()
        val deadline: Long = System.nanoTime() + 8_000_000_000L
        threads.forEach { it.join(maxOf(1L, (deadline - System.nanoTime()) / 1_000_000)) }
        val stuck: List<String> = threads.filter { it.isAlive }.map { it.name }
        assertTrue(stuck.isEmpty(), "threads stuck (deadlock): $stuck")
        assertTrue(addReturnedWhileParked.get(), "adding a listener from another thread blocked on a running delivery")

        val before: List<Int> = calls.map { it.get() }
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, calls.map { it.get() }, "a listener was called after release() and cancel() returned")
        assertEquals(0, tracker.listenerCountForTest())
    }

    @Test
    fun `a cancelling thread is not starved by a main thread that delivers resumes back to back`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity()
        val activity: Activity = controller.get()
        val subscription: ActivitySubscription = tracker.addOnActivityResumedListener { }
        val returned = AtomicBoolean(false)
        val canceller: Thread = background("cancel") {
            subscription.cancel()
            returned.set(true)
        }

        var deliveries = 0
        val deadline: Long = System.nanoTime() + 5_000_000_000L
        while (!returned.get() && deliveries < MAX_DELIVERIES && System.nanoTime() < deadline) {
            tracker.onActivityResumed(activity)
            deliveries++
        }
        canceller.join(2_000)
        assertTrue(returned.get(), "cancel() was starved by $deliveries back-to-back deliveries")
    }

    @Test
    fun `an isTracked predicate that throws leaves the tracker usable for the next resume`() {
        val failing = AtomicBoolean(true)
        val tracker: LifecycleActivityTracker =
            (createActivityAccess(application) { if (failing.get()) error("predicate failure") else true } as LifecycleActivityTracker)
                .also { trackers.add(it) }
        val controller: ActivityController<Activity> = launchActivity()
        val activity: Activity = controller.get()
        val calls = AtomicInteger()
        val subscription: ActivitySubscription = tracker.addOnActivityResumedListener { calls.incrementAndGet() }

        assertFailsWith<IllegalStateException> { tracker.onActivityResumed(activity) }
        assertEquals(0, calls.get())
        assertEquals(null, tracker.activityReferenceForTest(), "an activity the predicate failed on became current")

        failing.set(false)
        tracker.onActivityResumed(activity)
        assertEquals(1, calls.get())
        val cancelled: Thread = background("cancel") { subscription.cancel() }
        cancelled.join(5_000)
        assertFalse(cancelled.isAlive)
    }

    private companion object {
        const val MAX_DELIVERIES: Int = 2_000_000
    }
}
