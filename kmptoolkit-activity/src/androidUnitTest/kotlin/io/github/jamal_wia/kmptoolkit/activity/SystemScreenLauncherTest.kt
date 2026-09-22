package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The contract of the presets is the task a screen lands in, which a unit test sees as the flags on
 * the started intent: exactly `NEW_TASK | NEW_DOCUMENT` for a separate task, none for the caller's
 * task. Robolectric is told to fail unresolvable starts (`checkActivities`), so "the device has no
 * such screen" is the platform's own `ActivityNotFoundException`, not a simulation of it.
 */
@RunWith(AndroidJUnit4::class)
class SystemScreenLauncherTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val activityAccess: ActivityAccess = createActivityAccess(application)
    private val controllers: MutableList<ActivityController<out Activity>> = mutableListOf()

    @BeforeTest
    fun setUp() {
        shadowOf(application).checkActivities(true)
        registerScreen(RESOLVABLE)
        registerScreen(SECOND_RESOLVABLE)
    }

    @AfterTest
    fun tearDown() {
        activityAccess.release()
        controllers.forEach { controller -> controller.close() }
    }

    // --- SystemScreenRequest ---

    @Test
    fun `a request without candidates is rejected`() {
        assertFailsWith<IllegalArgumentException> { SystemScreenRequest(emptyList(), application, TestScreen) }
    }

    @Test
    fun `a request keeps its own copy of the candidates`() {
        val source: MutableList<Intent> = mutableListOf(Intent(RESOLVABLE))
        val request = SystemScreenRequest(source, application, TestScreen)

        source.clear()

        assertEquals(listOf(RESOLVABLE), request.candidates.map { it.action })
    }

    @Test
    fun `a request keeps the application context, not the activity it was given`() {
        val activity: Activity = resumedActivity()

        val request = SystemScreenRequest(listOf(Intent(RESOLVABLE)), activity, TestScreen)

        assertSame(application, request.applicationContext)
    }

    @Test
    fun `startFirstResolvable tries candidates in order and stops at the first that starts`() {
        val request = request(UNRESOLVABLE, RESOLVABLE, SECOND_RESOLVABLE)
        val tried: MutableList<String?> = mutableListOf()

        val started: Boolean = request.startFirstResolvable { intent ->
            tried.add(intent.action)
            if (intent.action == UNRESOLVABLE) throw ActivityNotFoundException()
        }

        assertTrue(started)
        assertEquals(listOf<String?>(UNRESOLVABLE, RESOLVABLE), tried)
    }

    @Test
    fun `startFirstResolvable moves past a screen that is not exported to the app`() {
        val tried: MutableList<String?> = mutableListOf()

        val started: Boolean = request(UNRESOLVABLE, RESOLVABLE).startFirstResolvable { intent ->
            tried.add(intent.action)
            if (intent.action == UNRESOLVABLE) throw SecurityException("not exported")
        }

        assertTrue(started)
        assertEquals(listOf<String?>(UNRESOLVABLE, RESOLVABLE), tried)
    }

    @Test
    fun `startFirstResolvable answers false when no candidate starts`() {
        val started: Boolean = request(UNRESOLVABLE, RESOLVABLE).startFirstResolvable {
            throw ActivityNotFoundException()
        }

        assertFalse(started)
    }

    @Test
    fun `startFirstResolvable lets any other failure propagate`() {
        assertFailsWith<IllegalStateException> {
            request(RESOLVABLE).startFirstResolvable { throw IllegalStateException("launcher bug") }
        }
    }

    @Test
    fun `a request copies the intents, so neither the caller nor a launcher can change them afterwards`() {
        val original = Intent(RESOLVABLE)
        val request = SystemScreenRequest(listOf(original), application, TestScreen)

        original.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        request.candidates.single().addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        assertEquals(0, request.candidates.single().flags)
    }

    @Test
    fun `startFirstResolvable hands out copies, so flags added by a launcher do not stick to the request`() {
        val request: SystemScreenRequest = request(RESOLVABLE)

        request.startFirstResolvable { intent -> intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        assertEquals(0, request.candidates.single().flags)
    }

    // --- SeparateTask ---

    @Test
    fun `SeparateTask starts the screen in a task of its own`() {
        val launched: Boolean = SystemScreenLauncher.SeparateTask.launch(request(RESOLVABLE))

        assertTrue(launched)
        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(RESOLVABLE, started.action)
        assertEquals(SEPARATE_TASK, started.flags)
    }

    @Test
    fun `SeparateTask separates the task even when an activity is resumed`() {
        resumedActivity()

        SystemScreenLauncher.SeparateTask.launch(request(RESOLVABLE))

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `SeparateTask falls through to the next candidate the device can show`() {
        val launched: Boolean = SystemScreenLauncher.SeparateTask.launch(request(UNRESOLVABLE, SECOND_RESOLVABLE))

        assertTrue(launched)
        assertEquals(SECOND_RESOLVABLE, assertNotNull(shadowOf(application).nextStartedActivity).action)
    }

    @Test
    fun `SeparateTask starts only the first of several resolvable candidates`() {
        SystemScreenLauncher.SeparateTask.launch(request(RESOLVABLE, SECOND_RESOLVABLE))

        assertEquals(RESOLVABLE, assertNotNull(shadowOf(application).nextStartedActivity).action)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `SeparateTask answers false when the device has none of the screens`() {
        assertFalse(SystemScreenLauncher.SeparateTask.launch(request(UNRESOLVABLE)))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- callerTask ---
    //
    // Robolectric records starts from an activity and from the application in one shared queue, so
    // the queue cannot tell which context started a screen. Starts from the activity are observed
    // on RecordingActivity itself; the shared queue then only holds starts that bypassed it.

    @Test
    fun `callerTask starts the screen from the resumed activity without task flags`() {
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)

        val launched: Boolean = SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE))

        assertTrue(launched)
        val started: Intent = activity.started.single()
        assertEquals(RESOLVABLE, started.action)
        assertEquals(0, started.flags)
    }

    @Test
    fun `callerTask starts from the activity its own ActivityAccess answers with`() {
        val tracked: RecordingActivity = resumed(RecordingActivity::class.java)
        val other: RecordingActivity = resumed(RecordingActivity::class.java)
        val access: ActivityAccess = fixedAccess(tracked)

        SystemScreenLauncher.callerTask(access).launch(request(RESOLVABLE))

        assertEquals(1, tracked.started.size)
        assertTrue(other.started.isEmpty(), "only the activity the ActivityAccess answers with may start it")
    }

    @Test
    fun `callerTask moves on to the next candidate the device can show`() {
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)

        SystemScreenLauncher.callerTask(activityAccess).launch(request(UNRESOLVABLE, SECOND_RESOLVABLE))

        assertEquals(listOf<String?>(SECOND_RESOLVABLE), activity.started.map { it.action })
    }

    @Test
    fun `callerTask starts only the first of several resolvable candidates`() {
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)

        SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE, SECOND_RESOLVABLE))

        assertEquals(listOf<String?>(RESOLVABLE), activity.started.map { it.action })
    }

    @Test
    fun `callerTask falls back to a separate task when no activity is resumed`() {
        val launched: Boolean = SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE))

        assertTrue(launched)
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `callerTask falls back to a separate task once the activity has paused`() {
        val controller: ActivityController<RecordingActivity> = launch(RecordingActivity::class.java).setup()
        controller.pause()

        SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE))

        assertTrue(controller.get().started.isEmpty())
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `callerTask falls back to a separate task from a finishing activity`() {
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)
        activity.finish()

        // The real tracker, not a fake: its refusal to report a finishing activity is the contract.
        SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE))

        assertTrue(activity.started.isEmpty())
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `callerTask falls back to a separate task from a singleInstance activity`() {
        shadowOf(application.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = application.packageName
                name = SingleInstanceActivity::class.java.name
                launchMode = ActivityInfo.LAUNCH_SINGLE_INSTANCE
            },
        )
        val activity: SingleInstanceActivity = resumed(SingleInstanceActivity::class.java)

        SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE))

        assertTrue(activity.started.isEmpty())
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `callerTask falls back to a separate task when called off the main thread`() {
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)
        var launched = false

        val worker = Thread { launched = SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE)) }
        worker.start()
        worker.join()

        assertTrue(launched)
        assertTrue(activity.started.isEmpty(), "Activity.startActivity must not run off the main thread")
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `callerTask answers false, without a second try in a separate task, when no screen resolves`() {
        // The activity cannot start RESOLVABLE, although the application context could: a retry in a
        // separate task would succeed and show up in the shared queue.
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)
        activity.unresolvable += RESOLVABLE

        val launched: Boolean = SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE))

        assertFalse(launched)
        assertTrue(activity.started.isEmpty())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- helpers ---

    private fun request(vararg actions: String): SystemScreenRequest =
        SystemScreenRequest(actions.map { Intent(it) }, application, TestScreen)

    private fun resumedActivity(): Activity = launch(Activity::class.java).setup().get()

    private fun <A : Activity> resumed(type: Class<A>): A = launch(type).setup().get()

    /** An ActivityAccess that always answers with [activity] while it is alive, as the real one does. */
    private fun fixedAccess(activity: Activity): ActivityAccess = object : ActivityAccess {
        override fun <R> withActivity(block: (Activity) -> R): R? =
            if (activity.isFinishing || activity.isDestroyed) null else block(activity)

        override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription =
            object : ActivitySubscription {
                override fun cancel(): Unit = Unit
            }

        override fun release(): Unit = Unit
    }

    private fun <A : Activity> launch(type: Class<A>): ActivityController<A> =
        Robolectric.buildActivity(type).also { controllers.add(it) }

    private fun registerScreen(action: String) {
        val component = ComponentName("com.example.settings", "com.example.settings.$action")
        shadowOf(application.packageManager).apply {
            addActivityIfNotPresent(component)
            // startActivity resolves with CATEGORY_DEFAULT, as the platform does.
            addIntentFilterForActivity(component, IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
    }

    private object TestScreen : SystemScreenKind

    /**
     * Records every start it makes instead of reaching the shared queue, and fails the ones listed in
     * [unresolvable] as the platform would for a screen it cannot find — [UNRESOLVABLE] always.
     */
    open class RecordingActivity : Activity() {
        val started: MutableList<Intent> = mutableListOf()
        val unresolvable: MutableSet<String> = mutableSetOf(UNRESOLVABLE)

        override fun startActivity(intent: Intent, options: Bundle?) {
            if (intent.action in unresolvable) throw ActivityNotFoundException(intent.action)
            started += intent
        }
    }

    class SingleInstanceActivity : RecordingActivity()

    private companion object {
        const val RESOLVABLE = "test.action.RESOLVABLE"
        const val SECOND_RESOLVABLE = "test.action.SECOND_RESOLVABLE"
        const val UNRESOLVABLE = "test.action.UNRESOLVABLE"
        const val SEPARATE_TASK: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
    }
}
