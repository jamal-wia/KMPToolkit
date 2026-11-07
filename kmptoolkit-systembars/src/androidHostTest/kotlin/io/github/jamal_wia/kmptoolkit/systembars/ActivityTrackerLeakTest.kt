package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import org.junit.runner.RunWith
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An [Application] that counts how many lifecycle callbacks are registered on it right now. */
class CountingApplication : Application() {
    var live: Int = 0
        private set
    var registrations: Int = 0
        private set

    override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks?) {
        registrations++
        live++
        super.registerActivityLifecycleCallbacks(callback)
    }

    override fun unregisterActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks?) {
        live--
        super.unregisterActivityLifecycleCallbacks(callback)
    }
}

/** Wraps a real [ActivityAccess], counting its live resume subscriptions. */
private class CountingWrapper(private val real: ActivityAccess) : ActivityAccess by real {
    var live: Int = 0
        private set

    override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
        live++
        val inner: ActivitySubscription = real.addOnActivityResumedListener(listener)
        var cancelled = false
        return object : ActivitySubscription {
            override fun cancel() {
                if (!cancelled) live--
                cancelled = true
                inner.cancel()
            }
        }
    }
}

/** An [ActivityAccess] that counts live resume subscriptions. */
private class CountingActivityAccess : ActivityAccess {
    var live: Int = 0
        private set

    override fun <R> withActivity(block: (Activity) -> R): R? = null

    override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
        live++
        var cancelled = false
        return object : ActivitySubscription {
            override fun cancel() {
                if (!cancelled) live--
                cancelled = true
            }
        }
    }

    override fun release() = Unit
}

/**
 * Activity-tracker retention of the Android controllers: what a controller leaves registered on the
 * [Application] or on its [ActivityAccess] once its owner is done with it.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = CountingApplication::class)
class ActivityTrackerLeakTest {

    private val application: CountingApplication = ApplicationProvider.getApplicationContext()

    @Test
    fun `a wake-lock controller that was cleared holds no subscription`() {
        val access = CountingActivityAccess()
        val controller = createScreenWakeLockController(access)

        controller.setKeepScreenOn(true)
        controller.setKeepScreenOn(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, access.live)
    }

    @Test
    fun `a wake-lock controller that never held the flag holds no subscription`() {
        val access = CountingActivityAccess()

        createScreenWakeLockController(access)

        assertEquals(0, access.live)
    }

    @Test
    fun `a wake-lock controller holds one subscription while the flag is wanted, not one per call`() {
        val access = CountingActivityAccess()
        val controller = createScreenWakeLockController(access)

        controller.setKeepScreenOn(true)
        controller.setKeepScreenOn(true)

        assertEquals(1, access.live)
    }

    @Test
    fun `the Context overloads of both factories register one tracker however often they are called`() {
        repeat(5) {
            createScreenWakeLockController(application)
            createSystemBarsController(application)
        }

        assertEquals(1, application.registrations)
    }

    @Test
    fun `create and release of a Context system-bars controller leaves one tracker, not one per cycle`() {
        repeat(3) { createSystemBarsController(application).release() }

        assertEquals(1, application.live)
    }

    // Created on demand: a real tracker registers a callback, which the application-count tests above must not see.
    private var realAccess: ActivityAccess? = null

    private fun countingAccess(): CountingWrapper =
        CountingWrapper(createActivityAccess(application).also { realAccess = it })

    @AfterTest
    fun releaseRealAccess() {
        realAccess?.release()
    }

    private fun Activity.keepsScreenOn(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    @Test
    fun `a release that lands after the activity finished and paused leaves no subscription`() {
        val counting: CountingWrapper = countingAccess() // before the activity: the tracker only sees later resumes
        val activity = Robolectric.buildActivity(Activity::class.java).create().resume()
        val controller = createScreenWakeLockController(counting)
        controller.setKeepScreenOn(true)
        activity.pause()
        activity.get().finish()

        controller.setKeepScreenOn(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, counting.live)
    }

    @Test
    fun `a release owed to a paused window keeps exactly one subscription and drops it on its resume`() {
        val counting: CountingWrapper = countingAccess() // before the activity: the tracker only sees later resumes
        val activity = Robolectric.buildActivity(Activity::class.java).create().resume()
        val controller = createScreenWakeLockController(counting)
        controller.setKeepScreenOn(true)
        activity.pause()

        controller.setKeepScreenOn(false)
        assertEquals(1, counting.live, "the release is still owed to that window")
        activity.resume()

        assertFalse(activity.get().keepsScreenOn())
        assertEquals(0, counting.live)
    }

    @Test
    fun `true then false then true again re-subscribes and re-applies to a recreated activity`() {
        val counting: CountingWrapper = countingAccess()
        val first = Robolectric.buildActivity(Activity::class.java).create().resume()
        val controller = createScreenWakeLockController(counting)
        controller.setKeepScreenOn(true)
        controller.setKeepScreenOn(false)
        assertEquals(0, counting.live)

        controller.setKeepScreenOn(true)
        assertEquals(1, counting.live)
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(Activity::class.java).create().resume()

        assertTrue(second.get().keepsScreenOn())
        assertEquals(1, counting.live)
    }
}
