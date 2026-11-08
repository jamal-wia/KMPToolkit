package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * An `ActivityAccess` decorator written the way a consumer writes one (`ActivityAccess by real`, overriding
 * one member to count or narrow) must not lose the windows `forEachResumedActivity` reaches: the wake lock
 * held while two windows are resumed flags both through the decorator exactly as it does through the tracker.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 30, 34])
class WakeLockDecoratedAccessTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val real: ActivityAccess = createActivityAccess(application)
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()
    private val looper = shadowOf(Looper.getMainLooper())

    @AfterTest
    fun tearDown() {
        real.release()
        controllers.forEach { it.close() }
    }

    /** What a consumer writes: delegate everything, intercept the subscription. */
    private class Counting(private val inner: ActivityAccess) : ActivityAccess by inner {
        var subscriptions: Int = 0
        override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
            subscriptions++
            return inner.addOnActivityResumedListener(listener)
        }
    }

    private fun Activity.flagged(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    private fun resumedWindow(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup()

    @Test
    fun `a delegating decorator still flags both resumed windows`() {
        val decorated = Counting(real)
        val controller = AndroidScreenWakeLockController(decorated)
        val left = resumedWindow()
        val right = resumedWindow()

        controller.setKeepScreenOn(true)
        looper.idle()

        assertEquals(1, decorated.subscriptions)
        assertTrue(right.get().flagged())
        assertTrue(left.get().flagged(), "the decorator hid the earlier resumed window from the wake lock")
    }
}
