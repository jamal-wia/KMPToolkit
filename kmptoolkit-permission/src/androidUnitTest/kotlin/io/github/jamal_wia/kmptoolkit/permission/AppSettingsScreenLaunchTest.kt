package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
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
 * How `openAppSettings` opens this app's details page, through the real factories: from the resumed
 * activity with no task flags, and — with none — in a task of its own. Up to 1.6.0 that fallback used
 * a bare `FLAG_ACTIVITY_NEW_TASK`.
 */
@RunWith(AndroidJUnit4::class)
class AppSettingsScreenLaunchTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val activityAccess: ActivityAccess = createActivityAccess(application)
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    /** A host that never shows a dialog; `openAppSettings` does not need one. */
    private val host: PermissionRequestHost = object : PermissionRequestHost {
        override fun launch(androidPermission: String, onResult: (Boolean) -> Unit): Boolean = false
    }

    @AfterTest
    fun tearDown() {
        activityAccess.release()
        controllers.forEach { controller -> controller.close() }
    }

    // --- the factory that tracks activities itself ---

    @Test
    fun `the default factory opens settings from the resumed activity, on the app's task`() {
        val handler: PermissionHandler = createPermissionHandler(application, host, InMemoryKeyValueStorage())
        val activity: Activity = resumedActivity()

        assertTrue(handler.openAppSettings())

        val started: Intent = assertNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, started.flags)
        assertAppDetails(started)
    }

    @Test
    fun `the default factory opens settings in a task of its own with no activity resumed`() {
        val handler: PermissionHandler = createPermissionHandler(application, host, InMemoryKeyValueStorage())

        assertTrue(handler.openAppSettings())

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(SEPARATE_TASK, started.flags)
        assertAppDetails(started)
    }

    // --- the ActivityAccess factory ---

    @Test
    fun `the activity access factory opens settings from that access's activity`() {
        val handler: PermissionHandler =
            createPermissionHandler(application, host, InMemoryKeyValueStorage(), activityAccess)
        val activity: Activity = resumedActivity()

        assertTrue(handler.openAppSettings())

        val started: Intent = assertNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, started.flags)
        assertAppDetails(started)
    }

    @Test
    fun `the activity access factory falls back to a separate task with no activity resumed`() {
        val handler: PermissionHandler =
            createPermissionHandler(application, host, InMemoryKeyValueStorage(), activityAccess)

        assertTrue(handler.openAppSettings())

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `a details page the device does not have is reported as not opened`() {
        shadowOf(application).checkActivities(true)
        val handler: PermissionHandler =
            createPermissionHandler(application, host, InMemoryKeyValueStorage(), activityAccess)
        resumedActivity()

        assertFalse(handler.openAppSettings())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- a launcher of your own ---

    @Test
    fun `a custom launcher gets one request with the details page and its kind`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val handler: PermissionHandler = handlerWith(SystemScreenLauncher { requests += it; true })
        resumedActivity()

        assertTrue(handler.openAppSettings())

        val request: SystemScreenRequest = requests.single()
        assertEquals(AppDetailsScreen, request.kind)
        val candidate: Intent = request.candidates.single()
        assertEquals(0, candidate.flags)
        assertAppDetails(candidate)
        assertNull(shadowOf(application).nextStartedActivity, "the custom launcher decides, nothing else starts")
    }

    @Test
    fun `a launcher that opens nothing makes openAppSettings report false`() {
        assertFalse(handlerWith(SystemScreenLauncher { false }).openAppSettings())
    }

    @Test
    fun `a launcher that throws makes openAppSettings report false rather than throw`() {
        assertFalse(handlerWith(SystemScreenLauncher { throw IllegalStateException("launcher bug") }).openAppSettings())
    }

    @Test
    fun `the kind names itself`() {
        assertEquals("AppDetailsScreen", AppDetailsScreen.toString())
    }

    // --- helpers ---

    private fun handlerWith(launcher: SystemScreenLauncher): PermissionHandler = createPermissionHandler(
        context = application,
        host = host,
        storage = InMemoryKeyValueStorage(),
        activityAccess = activityAccess,
        config = PermissionConfig(),
        logger = NoopLogger,
        systemScreenLauncher = launcher,
    )

    private fun assertAppDetails(intent: Intent) {
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${application.packageName}", intent.dataString)
    }

    private fun resumedActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup().get()

    private companion object {
        const val SEPARATE_TASK: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
    }
}
