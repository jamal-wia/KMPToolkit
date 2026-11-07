package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper

/**
 * Race and re-initialisation hazards of the activity tracker, with no coroutine test library: raw
 * threads, a barrier and bounded joins only.
 *
 * Cancel guarantee: once cancel() (or release()) has returned, the listener is never called again
 * from any thread, and cancel() waits for a delivery already running. The deterministic tests pin
 * that with the main thread parked inside the listener; the stress test adds sixteen threads.
 * Re-initialisation: trackers created and released in a loop across activity recreations must leave
 * nothing tracking.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleActivityTrackerAdversarialTest {

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

    private fun threadDump(): String =
        Thread.getAllStackTraces().entries
            .filter { (thread, _) -> thread.name == "main" || thread.name.startsWith("adv-") }
            .joinToString("\n") { (thread, stack) ->
                "${thread.name}:\n" + stack.take(12).joinToString("\n") { "    at $it" }
            }

    /** At most [DRAIN_CAP] tasks, never an open-ended idle(): producers may keep posting while it runs. */
    private fun drainBounded(looper: ShadowLooper) {
        var ran = 0
        while (ran < DRAIN_CAP && !looper.isIdle) {
            looper.runOneTask()
            ran++
        }
    }

    /**
     * Starts a thread that runs [action] and records whether it had returned, then waits up to
     * [WAIT_MS] ms for it. Called from inside a listener, so the main thread is mid-delivery.
     */
    private fun runOnOtherThreadWhileDelivering(action: () -> Unit): Pair<Thread, AtomicBoolean> {
        val returned = AtomicBoolean(false)
        val thread: Thread = thread(name = "adv-cancel") {
            action()
            returned.set(true)
        }
        thread.join(WAIT_MS)
        return thread to returned
    }

    @Test
    fun `cancel from another thread waits for a resume delivery already running and the listener is never called again`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity()
        val calls = AtomicInteger()
        val returnedDuringDelivery = AtomicBoolean(false)
        val holder = AtomicReference<Thread?>(null)
        val subscriptionRef = AtomicReference<ActivitySubscription?>(null)

        val subscription: ActivitySubscription = tracker.addOnActivityResumedListener {
            if (calls.incrementAndGet() == 1) {
                val (thread, returned) = runOnOtherThreadWhileDelivering { subscriptionRef.get()!!.cancel() }
                holder.set(thread)
                returnedDuringDelivery.set(returned.get())
            }
        }
        subscriptionRef.set(subscription)
        // The first resume, after the listener is in place, is the delivery under test.
        controller.setup()

        assertEquals(1, calls.get(), "the delivery under test did not run")
        assertFalse(returnedDuringDelivery.get(), "cancel() returned while the listener was still running")
        holder.get()!!.join(5_000)
        assertFalse(holder.get()!!.isAlive, "cancel() never returned after the delivery ended (deadlock)")

        controller.pause().resume()
        assertEquals(1, calls.get(), "the listener was called after cancel() returned")
        assertEquals(0, tracker.listenerCountForTest())
    }

    @Test
    fun `cancel from another thread waits for a posted replay already running and the listener is never called again`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity().setup()
        val looper = shadowOf(Looper.getMainLooper())
        val calls = AtomicInteger()
        val returnedDuringDelivery = AtomicBoolean(false)
        val holder = AtomicReference<Thread?>(null)
        val subscriptionRef = AtomicReference<ActivitySubscription?>(null)

        thread(name = "adv-add") {
            subscriptionRef.set(
                tracker.addOnActivityResumedListener {
                    if (calls.incrementAndGet() == 1) {
                        val (thread, returned) = runOnOtherThreadWhileDelivering { subscriptionRef.get()!!.cancel() }
                        holder.set(thread)
                        returnedDuringDelivery.set(returned.get())
                    }
                },
            )
        }.join(5_000)
        assertNotNull(subscriptionRef.get(), "the listener was not added")

        drainBounded(looper)

        assertEquals(1, calls.get(), "the replay under test did not run")
        assertFalse(returnedDuringDelivery.get(), "cancel() returned while the replay was still running")
        holder.get()!!.join(5_000)
        assertFalse(holder.get()!!.isAlive, "cancel() never returned after the replay ended (deadlock)")

        controller.pause().resume()
        drainBounded(looper)
        assertEquals(1, calls.get(), "the listener was called after cancel() returned")
    }

    @Test
    fun `release from another thread waits for a delivery already running and no listener is called afterwards`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity()
        val calls = AtomicInteger()
        val returnedDuringDelivery = AtomicBoolean(false)
        val holder = AtomicReference<Thread?>(null)

        tracker.addOnActivityResumedListener {
            if (calls.incrementAndGet() == 1) {
                val (thread, returned) = runOnOtherThreadWhileDelivering { tracker.release() }
                holder.set(thread)
                returnedDuringDelivery.set(returned.get())
            }
        }
        controller.setup()

        assertEquals(1, calls.get(), "the delivery under test did not run")
        assertFalse(returnedDuringDelivery.get(), "release() returned while a listener was still running")
        holder.get()!!.join(5_000)
        assertFalse(holder.get()!!.isAlive, "release() never returned after the delivery ended (deadlock)")

        controller.pause().resume()
        assertEquals(1, calls.get(), "a listener was called after release() returned")
    }

    @Test
    fun `a listener may cancel its own subscription and a sibling from inside a delivery without deadlock`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity()
        val calls = AtomicInteger()
        val siblingCalls = AtomicInteger()
        val ownRef = AtomicReference<ActivitySubscription?>(null)
        lateinit var sibling: ActivitySubscription

        ownRef.set(
            tracker.addOnActivityResumedListener {
                calls.incrementAndGet()
                ownRef.get()?.cancel()
                sibling.cancel()
            },
        )
        sibling = tracker.addOnActivityResumedListener { siblingCalls.incrementAndGet() }
        controller.setup()
        controller.pause().resume()

        assertEquals(1, calls.get())
        assertEquals(0, siblingCalls.get(), "a sibling cancelled earlier in the dispatch was still called")
        assertEquals(0, tracker.listenerCountForTest())
    }

    @Test
    fun `listeners added and cancelled from sixteen threads during resumes are never called after cancel returned and none are left`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val controller: ActivityController<Activity> = launchActivity().setup()
        val looper = shadowOf(Looper.getMainLooper())
        val failures: ConcurrentLinkedQueue<Throwable> = ConcurrentLinkedQueue()
        val calledAfterCancel = AtomicInteger()
        val calls = AtomicInteger()
        val barrier = CyclicBarrier(THREADS + 1)
        val stop = AtomicBoolean(false)
        val rounds = AtomicInteger()

        val workers: List<Thread> = List(THREADS) { id ->
            thread(name = "adv-listener-$id") {
                val random = Random(SEED + id)
                try {
                    barrier.await(5, TimeUnit.SECONDS)
                    while (!stop.get()) {
                        rounds.incrementAndGet()
                        val cancelReturned = AtomicInteger(0)
                        val subscription: ActivitySubscription = tracker.addOnActivityResumedListener {
                            calls.incrementAndGet()
                            if (cancelReturned.get() == 1) calledAfterCancel.incrementAndGet()
                        }
                        if (random.nextInt(3) == 0) Thread.yield()
                        // Bounded rate: each off-main add posts a replay, and an unthrottled flood would
                        // keep the main looper busy draining forever.
                        subscription.cancel()
                        cancelReturned.set(1)
                        Thread.sleep(1)
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
        }
        barrier.await(5, TimeUnit.SECONDS)

        // The main thread resumes the activity in a loop while the workers churn the listener list,
        // and runs the immediate replays that the off-main subscriptions queued.
        val deadline: Long = System.nanoTime() + 15_000_000_000L
        var resumes = 0
        while (resumes < RESUMES && System.nanoTime() < deadline) {
            controller.pause().resume()
            drainBounded(looper)
            resumes++
        }
        stop.set(true)
        val joinDeadline: Long = System.nanoTime() + 5_000_000_000L
        workers.forEach { it.join(((joinDeadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)) }
        drainBounded(looper)

        assertTrue(workers.none { it.isAlive }, "workers did not finish (seed $SEED, $resumes resumes)\n${threadDump()}")
        assertTrue(failures.isEmpty(), "a worker or a dispatch threw (seed $SEED): ${failures.firstOrNull()}")
        assertEquals(
            0,
            calledAfterCancel.get(),
            "a listener was called after its cancel() had returned: ${calledAfterCancel.get()} of ${calls.get()} calls in $resumes resumes (seed $SEED)",
        )
        assertEquals(0, tracker.listenerCountForTest(), "listeners are left behind (seed $SEED)")
        assertEquals(RESUMES, resumes, "the main thread did not finish its resumes in time")
        assertTrue(calls.get() > 0 && rounds.get() > THREADS, "the callers never overlapped a resume: ${calls.get()} calls, ${rounds.get()} rounds")
    }

    @Test
    fun `fifty trackers created and released across activity recreations leave none of them tracking`() {
        val controller: ActivityController<Activity> = launchActivity().setup()
        val released: MutableList<LifecycleActivityTracker> = mutableListOf()
        val lateCalls = AtomicInteger()

        repeat(CYCLES) { cycle ->
            val tracker: LifecycleActivityTracker = newTracker()
            tracker.addOnActivityResumedListener { lateCalls.incrementAndGet() }
            controller.recreate()
            assertSame(controller.get(), tracker.withActivity { it }, "cycle $cycle: a live tracker follows the recreated activity")

            tracker.release()
            released += tracker
            controller.recreate()

            released.forEachIndexed { index, old ->
                assertNull(old.withActivity { it }, "cycle $cycle: tracker $index answers after release")
                assertNull(old.activityReferenceForTest(), "cycle $cycle: tracker $index tracks an activity after release")
                assertEquals(0, old.listenerCountForTest(), "cycle $cycle: tracker $index holds a listener after release")
            }
        }

        val before: Int = lateCalls.get()
        released.forEach { old -> old.addOnActivityResumedListener { lateCalls.incrementAndGet() } }
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(before, lateCalls.get(), "a listener of a released tracker fired on a later resume")
        released.forEach { old -> assertEquals(0, old.listenerCountForTest(), "a listener added after release is held") }

        // The last instance created after all that still works.
        val last: LifecycleActivityTracker = newTracker()
        controller.pause().resume()
        assertNotNull(last.withActivity { it })
    }

    private companion object {
        const val CYCLES: Int = 50
        const val THREADS: Int = 16
        const val RESUMES: Int = 400
        const val SEED: Int = 1707
        const val DRAIN_CAP: Int = 64
        const val WAIT_MS: Long = 300L
    }
}
