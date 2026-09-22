package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
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
    fun `SeparateTask answers false when the device has none of the screens`() {
        assertFalse(SystemScreenLauncher.SeparateTask.launch(request(UNRESOLVABLE)))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- callerTask ---

    @Test
    fun `callerTask starts the screen from the resumed activity without task flags`() {
        val activity: Activity = resumedActivity()

        val launched: Boolean = SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE))

        assertTrue(launched)
        val started: Intent = assertNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(RESOLVABLE, started.action)
        assertEquals(0, started.flags)
    }

    @Test
    fun `callerTask falls back to a separate task when no activity is resumed`() {
        val launched: Boolean = SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE))

        assertTrue(launched)
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `callerTask falls back to a separate task once the activity has paused`() {
        val controller: ActivityController<Activity> = launch(Activity::class.java).setup()
        controller.pause()

        SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE))

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `callerTask falls back to a separate task from a finishing activity`() {
        resumedActivity().finish()

        SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE))

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
        launch(SingleInstanceActivity::class.java).setup()

        SystemScreenLauncher.callerTask(activityAccess).launch(request(RESOLVABLE))

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `callerTask answers false, without a second try in a separate task, when no screen resolves`() {
        resumedActivity()

        val launched: Boolean = SystemScreenLauncher.callerTask(activityAccess).launch(request(UNRESOLVABLE))

        assertFalse(launched)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- helpers ---

    private fun request(vararg actions: String): SystemScreenRequest =
        SystemScreenRequest(actions.map { Intent(it) }, application, TestScreen)

    private fun resumedActivity(): Activity = launch(Activity::class.java).setup().get()

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

    class SingleInstanceActivity : Activity()

    private companion object {
        const val RESOLVABLE = "test.action.RESOLVABLE"
        const val SECOND_RESOLVABLE = "test.action.SECOND_RESOLVABLE"
        const val UNRESOLVABLE = "test.action.UNRESOLVABLE"
        const val SEPARATE_TASK: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
    }
}
