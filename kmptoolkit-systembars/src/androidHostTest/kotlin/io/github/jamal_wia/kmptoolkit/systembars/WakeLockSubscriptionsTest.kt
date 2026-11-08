package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import java.lang.ref.WeakReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * What the wake lock leaves on the activity tracker: "calling `setKeepScreenOn` with the value it already
 * holds is a cheap no-op", "a controller that was cleared holds no subscription and is ordinary garbage, so
 * creating one per screen or per session is supported".
 */
@RunWith(AndroidJUnit4::class)
class WakeLockSubscriptionsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val real: ActivityAccess = createActivityAccess(application)
    private val access = CountingAccess(real)
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()
    private val looper = shadowOf(Looper.getMainLooper())

    @AfterTest
    fun tearDown() {
        real.release()
        controllers.forEach { it.close() }
    }

    private class CountingAccess(private val real: ActivityAccess) : ActivityAccess by real {
        var live: Int = 0
        var added: Int = 0

        override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
            live++
            added++
            val subscription: ActivitySubscription = real.addOnActivityResumedListener(listener)
            var cancelled = false
            return object : ActivitySubscription {
                override fun cancel() {
                    if (!cancelled) {
                        cancelled = true
                        live--
                    }
                    subscription.cancel()
                }
            }
        }
    }

    private fun resumedWindow(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup()

    @Test
    fun `a thousand holds with the same value subscribe once`() {
        resumedWindow()
        val wake = AndroidScreenWakeLockController(access)

        repeat(1000) { wake.setKeepScreenOn(true) }
        looper.idle()

        assertEquals(1, access.live)
        assertEquals(1, access.added, "an unchanged value subscribed again")
        wake.setKeepScreenOn(false)
        looper.idle()
        assertEquals(0, access.live)
    }

    @Test
    fun `three hundred controllers that each hold and release leave no subscription`() {
        val window: ActivityController<Activity> = resumedWindow()
        repeat(300) {
            val wake = AndroidScreenWakeLockController(access)
            wake.setKeepScreenOn(true)
            looper.idle()
            wake.setKeepScreenOn(false)
            looper.idle()
        }
        assertEquals(0, access.live, "a cleared controller left a subscription on the tracker")
        assertTrue(window.get().window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0)
    }

    @Test
    fun `a controller released after its window finished leaves no subscription`() {
        repeat(40) {
            val window: ActivityController<Activity> = resumedWindow()
            val wake = AndroidScreenWakeLockController(access)
            wake.setKeepScreenOn(true)
            looper.idle()
            window.get().finish()
            window.pause()
            wake.setKeepScreenOn(false)
            looper.idle()
            window.stop().destroy()
            window.close()
            controllers.remove(window)
        }
        assertEquals(0, access.live)
    }

    /** In a frame of its own, so no local of the test method keeps the controller reachable. */
    private fun heldAndReleased(): WeakReference<AndroidScreenWakeLockController> {
        val wake = AndroidScreenWakeLockController(access)
        wake.setKeepScreenOn(true)
        looper.idle()
        wake.setKeepScreenOn(false)
        looper.idle()
        return WeakReference(wake)
    }

    @Test
    fun `a released controller is collectable`() {
        resumedWindow()
        val reference: WeakReference<AndroidScreenWakeLockController> = heldAndReleased()
        var attempts = 0
        while (reference.get() != null && attempts < 40) {
            System.gc()
            System.runFinalization()
            Thread.sleep(25)
            attempts++
        }
        assertTrue(reference.get() == null, "a controller that was cleared is still reachable")
    }
}
