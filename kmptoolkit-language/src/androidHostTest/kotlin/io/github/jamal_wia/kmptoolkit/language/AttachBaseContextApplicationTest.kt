package io.github.jamal_wia.kmptoolkit.language

import android.app.Activity
import android.app.Application
import android.content.Context
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/kmptoolkit-language/05-platform-notes.md (the `attachBaseContext` snippet) states that inside an Activity's
 * `attachBaseContext` the Activity's `application` is not set yet while `newBase.applicationContext` already is,
 * and the snippet reads the holder from `newBase.applicationContext`. The framework's own `Activity.attach` is
 * executed by Robolectric (it is not shadowed), so the order of its first two statements is observable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 29, 34])
class AttachBaseContextApplicationTest {

    class ProbeActivity : Activity() {
        var applicationInside: Application? = null
        var applicationContextInside: Context? = null
        var baseWasNull: Boolean = false

        override fun attachBaseContext(newBase: Context) {
            baseWasNull = baseContext == null
            applicationInside = application
            applicationContextInside = newBase.applicationContext
            super.attachBaseContext(newBase)
        }
    }

    @Test
    fun `inside attachBaseContext the activity application is null and the application context of the base is set`() {
        val activity: ProbeActivity = Robolectric.buildActivity(ProbeActivity::class.java).create().get()
        assertNull(activity.applicationInside, "documented: application is not set yet inside attachBaseContext")
        assertNotNull(activity.applicationContextInside, "documented: the application context already is")
        assertSame(activity.application, activity.applicationContextInside, "the documented lookup yields the Application")
    }
}
