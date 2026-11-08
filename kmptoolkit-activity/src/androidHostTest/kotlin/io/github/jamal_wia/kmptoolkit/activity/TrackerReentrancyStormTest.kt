package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * Listeners that subscribe, cancel and release from inside a delivery, across three windows resuming over
 * and over: "called from inside a listener (its own or another's), it never waits and cannot deadlock", and a
 * cancelled listener is never called again, even for the resume being delivered. Bounded: 150 resumes.
 */
@RunWith(AndroidJUnit4::class)
class TrackerReentrancyStormTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker = LifecycleActivityTracker(application).also { it.register() }
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        tracker.release()
        controllers.forEach { it.close() }
    }

    @Test
    fun `listeners that subscribe and cancel each other inside a delivery are never called after their cancel`() {
        val windows: List<ActivityController<Activity>> =
            List(3) { Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup() }
        val random = Random(3)
        val cancelledAt: MutableMap<Int, Int> = mutableMapOf()
        val calls: MutableMap<Int, MutableList<Int>> = mutableMapOf()
        val subscriptions: MutableMap<Int, io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription> = mutableMapOf()
        var clock = 0
        var nextId = 0

        fun subscribe() {
            val id: Int = nextId++
            calls[id] = mutableListOf()
            subscriptions[id] = tracker.addOnActivityResumedListener {
                calls.getValue(id) += clock
                // From inside the delivery: cancel another listener at random, and sometimes add a new one.
                val victim: Int = random.nextInt(nextId)
                subscriptions[victim]?.let { it.cancel(); cancelledAt.putIfAbsent(victim, clock) }
                if (nextId < 200 && random.nextInt(3) == 0) subscribe()
            }
        }
        repeat(8) { subscribe() }

        repeat(150) { step ->
            clock = step + 1
            val window: ActivityController<Activity> = windows[random.nextInt(3)]
            window.pause()
            window.resume()
        }

        for ((id, stamps) in calls) {
            val cancelled: Int? = cancelledAt[id]
            if (cancelled != null) {
                assertTrue(stamps.none { it > cancelled }, "listener $id was called at ${stamps.filter { it > cancelled }} after being cancelled at $cancelled")
            }
        }
        subscriptions.values.forEach { it.cancel() }
        assertEquals(0, tracker.listenerCountForTest(), "listeners were left on the tracker")
    }
}
