package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * A listener added from inside another listener's delivery of resume X is called once for the activity
 * that is resumed - its immediate replay - and not again for X, the delivery that was already in flight when
 * it was added. A caller that continues inside the delivery of a resume (a `Dispatchers.Main.immediate`
 * continuation) and subscribes there relies on this: the permission controller opens a Settings screen and
 * arms its "user is back" listener in that position, and a second call for the same resume would be taken
 * for the user coming back.
 */
@RunWith(AndroidJUnit4::class)
class ListenerAddedDuringDeliveryTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker = LifecycleActivityTracker(application).also { it.register() }
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        tracker.release()
        controllers.forEach { it.close() }
    }

    @Test
    fun `a listener added inside a delivery of a resume is called once, by the replay, and not again for that resume`() {
        val screen: ActivityController<Activity> =
            Robolectric.buildActivity(Activity::class.java).also { controllers += it }.create().start()
        val late: MutableList<Activity> = mutableListOf()
        var added = false
        tracker.addOnActivityResumedListener {
            if (!added) {
                added = true
                tracker.addOnActivityResumedListener { late += it }
            }
        }

        screen.resume() // the first listener subscribes the second one while this resume is being delivered

        assertEquals(1, late.size, "the late listener must see the resume once (the replay), not twice")
    }

    @Test
    fun `a listener added inside a delivery sees only later resumes after its replay`() {
        val screen: ActivityController<Activity> =
            Robolectric.buildActivity(Activity::class.java).also { controllers += it }.create().start()
        var calls = 0
        var added = false
        tracker.addOnActivityResumedListener {
            if (!added) {
                added = true
                tracker.addOnActivityResumedListener { calls++ }
            }
        }

        screen.resume()
        assertEquals(1, calls)
        screen.pause()
        screen.resume() // a real later resume
        assertEquals(2, calls)
    }
}
