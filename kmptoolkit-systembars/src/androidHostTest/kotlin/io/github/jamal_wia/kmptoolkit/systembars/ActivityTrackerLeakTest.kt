package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
