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
    fun `eight threads asking for the activity while the main thread hands over between activities in both orders never lose or mix up the current one`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val expected = AtomicReference<Activity?>(null)
        val stop = AtomicBoolean(false)
        val violations: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val queries = AtomicInteger()
        val workers: List<Thread> = List(8) { index ->
            background("ask-$index") {
                while (!stop.get()) {
                    val before: Activity? = expected.get()
                    val answer: Activity? = tracker.withActivity { it }
                    val after: Activity? = expected.get()
                    queries.incrementAndGet()
                    if (before != null && before === after && answer !== before) {
                        if (violations.size < 5) violations += "expected $before, got $answer"
                    }
                }
            }
        }

        var current: ActivityController<Activity> = launchActivity().setup()
        expected.set(current.get())
        val deadline: Long = System.nanoTime() + 8_000_000_000L
        var round = 0
        while (round < HANDOVERS && System.nanoTime() < deadline && violations.isEmpty()) {
            val next: ActivityController<Activity> = launchActivity().create().start()
            expected.set(null) // a hand-over is in progress: no single answer is right
            if (round % 2 == 0) {
                // The usual order: the old one pauses, then the new one resumes.
                current.pause()
                next.resume()
            } else {
                // The new one resumes before the old one pauses and is destroyed.
                next.resume()
                current.pause().stop().destroy()
            }
            expected.set(next.get())
            // Let the workers see this activity for a moment before the next hand-over.
            val seen: Int = queries.get()
            val settle: Long = System.nanoTime() + 2_000_000L
            while (queries.get() - seen < 16 && System.nanoTime() < settle) Thread.onSpinWait()
            current = next
            round++
        }
        stop.set(true)
        workers.forEach { it.join(3_000) }
        assertTrue(workers.none { it.isAlive }, "an asking thread is stuck")
        assertTrue(violations.isEmpty(), "withActivity lost or mixed up the current activity: $violations")
        assertTrue(round >= 20, "only $round hand-overs ran")
    }

    @Test
    fun `a listener that releases the tracker from inside a delivery stops every later listener of that same delivery`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity()
        val laterCalls = AtomicInteger()
        val ownCalls = AtomicInteger()

        tracker.addOnActivityResumedListener {
            ownCalls.incrementAndGet()
            tracker.release()
        }
        tracker.addOnActivityResumedListener { laterCalls.incrementAndGet() }
        controller.setup()
        controller.pause().resume()

        assertEquals(1, ownCalls.get())
        assertEquals(0, laterCalls.get(), "a listener ran after release() returned inside the same delivery")
        val added: AtomicReference<ActivitySubscription?> = AtomicReference(null)
        background("add-after-release") { added.set(tracker.addOnActivityResumedListener { laterCalls.incrementAndGet() }) }.join(5_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, laterCalls.get(), "a listener added after release() was called")
        assertEquals(0, tracker.listenerCountForTest())
    }

    @Test
    fun `eight threads that add listeners with replays queued and then a release leave no replay that reaches a listener`() {
        val tracker: LifecycleActivityTracker = newTracker()
        launchActivity().setup()
        val calls = AtomicInteger()
        val afterRelease = AtomicInteger()
        val released = AtomicBoolean(false)
        val adders: List<Thread> = List(8) { index ->
            background("replay-$index") {
                repeat(500) {
                    tracker.addOnActivityResumedListener {
                        calls.incrementAndGet()
                        if (released.get()) afterRelease.incrementAndGet()
                    }
                }
            }
        }
        adders.forEach { it.join(10_000) }
        assertTrue(adders.none { it.isAlive }, "an adding thread is stuck")
        tracker.release()
        released.set(true)
        val looper = shadowOf(Looper.getMainLooper())
        var ran = 0
        while (!looper.isIdle && ran < 10_000) {
            looper.runOneTask()
            ran++
        }
        assertEquals(0, afterRelease.get(), "queued replays reached listeners after release() returned")
        assertEquals(0, tracker.listenerCountForTest())
    }

    @Test
    fun `twenty thousand registrations cancelled from eight threads while resumes keep arriving finish in bounded time and leave none`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity()
        val activity: Activity = controller.get()
        controller.setup()
        val subscriptions: List<ActivitySubscription> = List(20_000) { tracker.addOnActivityResumedListener { } }
        val cancellers: List<Thread> = List(8) { index ->
            background("mass-cancel-$index") {
                var position: Int = index
                while (position < subscriptions.size) {
                    subscriptions[position].cancel()
                    position += 8
                }
            }
        }
        val deadline: Long = System.nanoTime() + 15_000_000_000L
        var deliveries = 0
        while (cancellers.any { it.isAlive } && System.nanoTime() < deadline && deliveries < 100_000) {
            tracker.onActivityResumed(activity)
            deliveries++
        }
        cancellers.forEach { it.join(maxOf(1L, (deadline - System.nanoTime()) / 1_000_000)) }
        assertTrue(cancellers.none { it.isAlive }, "mass cancellation did not finish in bounded time ($deliveries deliveries)")
        assertEquals(0, tracker.listenerCountForTest())
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
        const val HANDOVERS: Int = 120
        const val ROUNDS: Int = 300
    }
    /** True when [thread] is parked on a lock: it has started and cannot proceed. */
    private fun awaitBlocked(thread: Thread): Boolean {
        val deadline: Long = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.WAITING || thread.state == Thread.State.BLOCKED) return true
            if (!thread.isAlive) return false
            Thread.sleep(1)
        }
        return false
    }


    @Test
    fun `a release that loses the race to another thread's release does not return before every listener is stopped`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity()
        val firstCalls = AtomicInteger()
        val laterCalls = AtomicInteger()
        val laterCalledAfterReleaseReturned = AtomicBoolean(false)
        val releaseReturned = AtomicBoolean(false)
        val otherRelease = AtomicReference<Thread?>(null)

        tracker.addOnActivityResumedListener {
            if (firstCalls.incrementAndGet() == 1) {
                // The main thread is mid-delivery, holding this registration's lock. Another thread's
                // release() takes the "first caller" role and parks on that lock.
                val other: Thread = background("release") { tracker.release() }
                otherRelease.set(other)
                assertTrue(awaitBlocked(other), "the other release() did not park on the delivery")
                // This release() lost the race: whatever it returns must already be true of the tracker.
                tracker.release()
                releaseReturned.set(true)
                // A resume reaches the tracker again while the other release() is still parked.
                tracker.onActivityResumed(controller.get())
            }
        }
        tracker.addOnActivityResumedListener {
            laterCalls.incrementAndGet()
            if (releaseReturned.get()) laterCalledAfterReleaseReturned.set(true)
        }

        controller.setup()

        otherRelease.get()!!.join(5_000)
        assertFalse(otherRelease.get()!!.isAlive, "the first release() never finished (deadlock)")
        assertFalse(
            laterCalledAfterReleaseReturned.get(),
            "a listener was called after release() had returned on the main thread",
        )
        assertEquals(0, tracker.listenerCountForTest())
    }

    @Test
    fun `adders, back to back deliveries and a release racing on real threads never call a listener after release returned`() {
        val violations = AtomicInteger()
        val totalDeadline: Long = System.nanoTime() + 12_000_000_000L
        var rounds = 0
        while (rounds < ROUNDS && System.nanoTime() < totalDeadline) {
            rounds++
            val tracker: LifecycleActivityTracker = newTracker()
            val activity: Activity = launchActivity().get()
            tracker.onActivityResumed(activity)
            val released = AtomicBoolean(false)
            val stop = AtomicBoolean(false)
            val adders: List<Thread> = List(3) { index ->
                background("add-$index") {
                    var added = 0
                    while (!stop.get() && added < 20_000) {
                        tracker.addOnActivityResumedListener { if (released.get()) violations.incrementAndGet() }
                        added++
                    }
                }
            }
            val releaser: Thread = background("release") {
                val spin: Long = System.nanoTime() + (rounds % 7) * 30_000L
                while (System.nanoTime() < spin) Thread.onSpinWait()
                tracker.release()
                released.set(true)
            }
            val roundDeadline: Long = System.nanoTime() + 2_000_000_000L
            var deliveries = 0
            while (!released.get() && deliveries < 200_000 && System.nanoTime() < roundDeadline) {
                tracker.onActivityResumed(activity)
                deliveries++
            }
            // Deliveries keep coming for a moment after release() returned, to catch a late registration.
            repeat(20) { tracker.onActivityResumed(activity) }
            stop.set(true)
            releaser.join(3_000)
            adders.forEach { it.join(3_000) }
            val stuck: List<String> = (adders + releaser).filter { it.isAlive }.map { it.name }
            assertTrue(stuck.isEmpty(), "round $rounds: threads stuck: $stuck")
            shadowOf(Looper.getMainLooper()).idle()
            tracker.onActivityResumed(activity)
            assertEquals(0, tracker.listenerCountForTest(), "round $rounds: the released tracker still holds listeners")
        }
        assertEquals(0, violations.get(), "listeners were called after release() had returned (${violations.get()} in $rounds rounds)")
    }
}
