package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The Kotlin samples of docs/kmptoolkit-activity (02-getting-started, 03-guide, 06-testing) typed in as
 * the docs print them and run on a real tracker and real activities, so a sample that stops compiling
 * against the public API, or behaves differently from the sentence beside it, fails here.
 */
@RunWith(AndroidJUnit4::class)
class ActivityDocSamplesRunTest {

    private class MainActivity : Activity()
    private class SettingsActivity : Activity()
    private class Picker : Activity()

    private class RecordingMain : Activity() {
        val started: MutableList<Intent> = mutableListOf()
        override fun startActivity(intent: Intent) {
            started += intent
        }
    }

    private object BiometricEnrollmentScreen : SystemScreenKind
    private object OtherScreen : SystemScreenKind

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<*>> = mutableListOf()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()

    @AfterTest
    fun tearDown() {
        accesses.forEach { it.release() }
        controllers.forEach { it.close() }
    }

    private fun <T : Activity> start(type: Class<T>): T =
        Robolectric.buildActivity(type).also { controllers += it }.setup().get()

    private fun track(isTracked: (Activity) -> Boolean = { true }): ActivityAccess =
        createActivityAccess(application, isTracked).also { accesses += it }

    @Test
    fun `the getting started sample starts a result from the current activity and reports whether it ran`() {
        val activityAccess: ActivityAccess = track()
        val intent = Intent("some.ACTION")
        val none: Boolean? = activityAccess.withActivity { activity ->
            activity.startActivityForResult(intent, 7)
            true
        }
        assertNull(none, "with no resumed activity the block must not run")

        val main: MainActivity = start(MainActivity::class.java)
        val handled: Boolean? = activityAccess.withActivity { activity ->
            activity.startActivityForResult(intent, 7)
            true
        }

        assertEquals(true, handled)
        assertEquals("some.ACTION", shadowOf(main).nextStartedActivityForResult.intent.action)
    }

    @Test
    fun `the listener sample sees the replacement activity after a rotation`() {
        val activityAccess: ActivityAccess = track()
        val flagged: MutableList<Activity> = mutableListOf()
        fun applyWindowFlags(window: android.view.Window) { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val controller: ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).also { controllers += it }.setup()

        val subscription = activityAccess.addOnActivityResumedListener { activity ->
            flagged += activity
            applyWindowFlags(activity.window)
        }
        assertEquals(1, flagged.size, "a subscriber added while an activity is resumed hears it at once")
        val before: Activity = controller.get()
        controller.recreate()
        val after: Activity = controller.get()

        assertTrue(before !== after)
        assertEquals(listOf(before, after), flagged)
        assertTrue(after.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        subscription.cancel()
    }

    @Test
    fun `the predicate sample tracks the two named activities and ignores a picker`() {
        val activityAccess: ActivityAccess = track { it is MainActivity || it is SettingsActivity }
        val main: MainActivity = start(MainActivity::class.java)
        start(Picker::class.java)

        assertEquals(main, activityAccess.withActivity { it })

        val settings: SettingsActivity = start(SettingsActivity::class.java)
        assertEquals(settings, activityAccess.withActivity { it })
    }

    @Test
    fun `the dialog sample answers false when nothing is resumed and true when something is`() {
        val activityAccess: ActivityAccess = track()
        val shownOn: MutableList<Activity> = mutableListOf()
        fun showDialog(): Boolean = activityAccess.withActivity { activity ->
            shownOn += activity
            true
        } ?: false

        assertFalse(showDialog())
        val main: MainActivity = start(MainActivity::class.java)
        assertTrue(showDialog())
        assertEquals(listOf<Activity>(main), shownOn)
    }

    @Test
    fun `the keep screen on sample sets the flag on the current window only`() {
        val activityAccess: ActivityAccess = track()
        val main: MainActivity = start(MainActivity::class.java)

        activityAccess.withActivity { activity -> activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

        assertTrue(main.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    fun `the logged launcher sample logs the kind and delegates for both presets`() {
        val activityAccess: ActivityAccess = track()
        val log: MutableList<String> = mutableListOf()
        fun logged(delegate: SystemScreenLauncher) = SystemScreenLauncher { request ->
            log += "opening ${request.kind}"
            delegate.launch(request)
        }
        val request = SystemScreenRequest(listOf(Intent("a.SCREEN")), application, OtherScreen)

        assertTrue(logged(SystemScreenLauncher.SeparateTask).launch(request))
        assertNotNull(shadowOf(application).nextStartedActivity)
        val main: RecordingMain = start(RecordingMain::class.java)
        assertTrue(logged(SystemScreenLauncher.callerTask(activityAccess)).launch(request))

        assertEquals(2, log.size)
        assertEquals(0, main.started.single().flags, "the caller-task preset added task flags")
    }

    @Test
    fun `the kiosk sample brackets the enrolment screen in its window and passes others straight to a separate task`() {
        var opened = 0
        var closed = 0
        fun launchInsideAllowlistWindow(request: SystemScreenRequest): Boolean {
            opened++
            val launched: Boolean = try {
                SystemScreenLauncher.SeparateTask.launch(request)
            } catch (e: Exception) {
                closed++
                throw e
            }
            if (!launched) closed++
            return launched
        }
        val kiosk = SystemScreenLauncher { request ->
            if (request.kind == BiometricEnrollmentScreen) {
                launchInsideAllowlistWindow(request)
            } else {
                SystemScreenLauncher.SeparateTask.launch(request)
            }
        }

        assertTrue(kiosk.launch(SystemScreenRequest(listOf(Intent("enroll.SCREEN")), application, BiometricEnrollmentScreen)))
        assertTrue(kiosk.launch(SystemScreenRequest(listOf(Intent("other.SCREEN")), application, OtherScreen)))

        assertEquals(1, opened)
        assertEquals(0, closed)
        val first: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT, first.flags)
    }

    @Test
    fun `the for result sample launches through the activity's own launcher on the main thread and falls back otherwise`() {
        val activityAccess: ActivityAccess = track()
        WithResults.launched.clear()
        val forResult = SystemScreenLauncher { request ->
            val onMainThread: Boolean = Looper.myLooper() == Looper.getMainLooper()
            if (!onMainThread) return@SystemScreenLauncher SystemScreenLauncher.SeparateTask.launch(request)
            activityAccess.withActivity { activity ->
                val results: ResultLauncher = (activity as? HasSettingsResults)?.settingsResults ?: return@withActivity null
                request.startFirstResolvable { intent -> results.launch(intent) }
            } ?: SystemScreenLauncher.SeparateTask.launch(request)
        }
        val request = SystemScreenRequest(listOf(Intent("a.SCREEN")), application, OtherScreen)

        // No resumed activity: the separate task.
        assertTrue(forResult.launch(request))
        assertNotNull(shadowOf(application).nextStartedActivity)

        // A resumed activity that offers no launcher: the separate task again.
        start(MainActivity::class.java)
        assertTrue(forResult.launch(request))
        assertNotNull(shadowOf(application).nextStartedActivity)

        // One that does: its launcher got the intent and nothing started from the application.
        start(WithResults::class.java)
        assertTrue(forResult.launch(request))
        assertEquals(listOf("a.SCREEN"), WithResults.launched.map { it.action })
        assertNull(shadowOf(application).nextStartedActivity)
    }

    /** Stands in for androidx's ActivityResultLauncher, which this module does not depend on. */
    fun interface ResultLauncher { fun launch(input: Intent) }

    interface HasSettingsResults { val settingsResults: ResultLauncher }

    class WithResults : Activity(), HasSettingsResults {
        override val settingsResults: ResultLauncher = ResultLauncher { launched += it }

        companion object { val launched: MutableList<Intent> = mutableListOf() }
    }
}
