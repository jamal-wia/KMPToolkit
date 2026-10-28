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
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * How `openSettings` opens this app's settings screens, through the real factories: from the resumed
 * activity with no task flags, and — with none — in a task of its own. Up to 1.6.0 that fallback used
 * a bare `FLAG_ACTIVITY_NEW_TASK`.
 *
 * "From the resumed activity" is proven with a [RecordingActivity]: Robolectric's activity and
 * application shadows share one queue of started activities, so only a recording made by the
 * activity itself shows which context started the page.
 */
@RunWith(AndroidJUnit4::class)
class AppSettingsScreenLaunchTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()
    private val controllers: MutableList<ActivityController<out Activity>> = mutableListOf()

    /** A host that never shows a dialog; `openSettings` does not need one. */
    private val host: PermissionRequestHost = RecordingHost(canLaunch = false)

    @AfterTest
    fun tearDown() {
        accesses.forEach { access -> access.release() }
        controllers.forEach { controller -> controller.close() }
    }

    // --- the factory that tracks activities itself ---

    @Test
    fun `the default factory opens settings from the resumed activity, on the app's task`() {
        val handler: PermissionController = createPermissionController(application, InMemoryKeyValueStorage(), host)
        val activity: RecordingActivity = resumedRecordingActivity()

        assertTrue(handler.openSettings(CommonPermission.Camera))

        val started: Intent = activity.started.single()
        assertEquals(0, started.flags)
        assertAppDetails(started)
        assertNull(shadowOf(application).nextStartedActivity, "nothing else starts")
    }

    @Test
    fun `the default factory opens settings in a task of its own with no activity resumed`() {
        val handler: PermissionController = createPermissionController(application, InMemoryKeyValueStorage(), host)

        assertTrue(handler.openSettings(CommonPermission.Camera))

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(SEPARATE_TASK, started.flags)
        assertAppDetails(started)
        assertNull(shadowOf(application).nextStartedActivity, "exactly one start")
    }

    // --- the ActivityAccess factory ---

    @Test
    fun `the activity access factory opens settings from that access's activity`() {
        val activity: RecordingActivity = resumedRecordingActivity()
        val handler: PermissionController =
            createPermissionController(application, InMemoryKeyValueStorage(), host, FixedActivityAccess(activity))

        assertTrue(handler.openSettings(CommonPermission.Camera))

        val started: Intent = activity.started.single()
        assertEquals(0, started.flags)
        assertAppDetails(started)
        assertNull(shadowOf(application).nextStartedActivity, "nothing else starts")
    }

    @Test
    fun `the activity access factory uses the access it is given, not the last resumed activity`() {
        val access: ActivityAccess = createActivityAccess(application) { false }.also { accesses += it }
        val handler: PermissionController = createPermissionController(application, InMemoryKeyValueStorage(), host, access)
        val activity: RecordingActivity = resumedRecordingActivity()

        assertTrue(handler.openSettings(CommonPermission.Camera))

        assertEquals(emptyList(), activity.started)
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity, "exactly one start")
    }

    @Test
    fun `the activity access factory falls back to a separate task with no activity resumed`() {
        val access: ActivityAccess = createActivityAccess(application).also { accesses += it }
        val handler: PermissionController = createPermissionController(application, InMemoryKeyValueStorage(), host, access)

        assertTrue(handler.openSettings(CommonPermission.Camera))

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity, "exactly one start")
    }

    @Test
    fun `a page the activity cannot start is not retried in a separate task`() {
        // The application context could start it (Robolectric resolves anything by default); 1.6.0
        // retried there. Now the activity's answer is final.
        val activity: RecordingActivity = resumedRecordingActivity()
        activity.unresolvable = setOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val logger = RecordingLogger()
        val handler: PermissionController = createPermissionController(
            application,
            InMemoryKeyValueStorage(),
            host,
            FixedActivityAccess(activity),
            logger = logger,
        )

        assertFalse(handler.openSettings(CommonPermission.Camera))

        assertAppDetails(activity.started.single())
        assertNull(shadowOf(application).nextStartedActivity, "no second try from the application context")
        assertEquals(LogLevel.WARN, logger.entries.single().level)
    }

    @Test
    fun `a details page the device does not have is reported as not opened`() {
        shadowOf(application).checkActivities(true)
        val handler: PermissionController = createPermissionController(application, InMemoryKeyValueStorage(), host)

        assertFalse(handler.openSettings(CommonPermission.Camera))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- a custom SystemScreenLauncher ---

    @Test
    fun `a custom launcher gets one request with the details page and its kind`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val handler: PermissionController = handlerWith(SystemScreenLauncher { requests += it; true })
        resumedRecordingActivity()

        assertTrue(handler.openSettings(CommonPermission.Camera))

        val request: SystemScreenRequest = requests.single()
        assertEquals(PermissionScreen(CommonPermission.Camera), request.kind)
        val candidate: Intent = request.candidates.single()
        assertEquals(0, candidate.flags)
        assertAppDetails(candidate)
        assertNull(shadowOf(application).nextStartedActivity, "the custom launcher decides, nothing else starts")
    }

    @Test
    fun `callerTask passed as the launcher opens from that access's activity`() {
        val activity: RecordingActivity = resumedRecordingActivity()
        val access = FixedActivityAccess(activity)
        val handler: PermissionController = createPermissionController(
            application,
            InMemoryKeyValueStorage(),
            host,
            access,
            SystemScreenLauncher.callerTask(access),
        )

        assertTrue(handler.openSettings(CommonPermission.Camera))

        assertEquals(0, activity.started.single().flags)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `SeparateTask passed as the launcher opens a separate task even with an activity resumed`() {
        val activity: RecordingActivity = resumedRecordingActivity()
        val handler: PermissionController = handlerWith(SystemScreenLauncher.SeparateTask, FixedActivityAccess(activity))

        assertTrue(handler.openSettings(CommonPermission.Camera))

        assertEquals(emptyList(), activity.started)
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `a launcher that opens nothing is called once, and openSettings reports false and logs`() {
        var calls = 0
        val logger = RecordingLogger()

        assertFalse(handlerWith(SystemScreenLauncher { calls++; false }, logger = logger).openSettings(CommonPermission.Camera))

        assertEquals(1, calls)
        assertEquals(LogLevel.WARN, logger.entries.single().level)
    }

    @Test
    fun `a launcher that throws is called once, and openSettings reports false and logs the cause`() {
        var calls = 0
        val bug = IllegalStateException("launcher bug")
        val logger = RecordingLogger()

        assertFalse(handlerWith(SystemScreenLauncher { calls++; throw bug }, logger = logger).openSettings(CommonPermission.Camera))

        assertEquals(1, calls)
        val entry: RecordingLogger.Entry = logger.entries.single()
        assertEquals(LogLevel.WARN, entry.level)
        assertEquals(bug, entry.throwable)
    }

    @Test
    fun `the factory can be referenced without naming a function type`() {
        val reference = ::createPermissionController

        assertIs<PermissionController>(
            reference(
                application,
                InMemoryKeyValueStorage(),
                host,
                FixedActivityAccess(resumedRecordingActivity()),
                SystemScreenLauncher.SeparateTask,
                AndroidPermissionConfig(),
                NoopLogger,
            ),
        )
    }

    @Test
    fun `screen kinds are equal exactly when they name the same permission`() {
        assertEquals(PermissionScreen(CommonPermission.Camera), PermissionScreen(CommonPermission.Camera))
        assertTrue(PermissionScreen(CommonPermission.Camera) != PermissionScreen(CommonPermission.Microphone))
        assertTrue(PermissionScreen(CommonPermission.Camera) != PermissionScreen(AndroidPermission.Overlay))
    }

    // --- the most specific screen per permission ---

    @Test
    fun `notifications open the app's notification settings first, then its details page`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()

        assertTrue(handlerWith(SystemScreenLauncher { requests += it; true }).openSettings(CommonPermission.Notifications))

        val request: SystemScreenRequest = requests.single()
        assertEquals(PermissionScreen(CommonPermission.Notifications), request.kind)
        val (notifications: Intent, details: Intent) = request.candidates
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.action)
        assertEquals(application.packageName, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(0, notifications.flags)
        assertAppDetails(details)
    }

    @Test
    fun `a special access opens its own screen, the same one a request opens`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()

        assertTrue(handlerWith(SystemScreenLauncher { requests += it; true }).openSettings(AndroidPermission.Overlay))

        val request: SystemScreenRequest = requests.single()
        assertEquals(PermissionScreen(AndroidPermission.Overlay), request.kind)
        assertEquals(
            listOf(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            request.candidates.map { it.action },
        )
    }

    @Test
    fun `a permission type the controller does not know opens nothing`() {
        var calls = 0

        assertFalse(handlerWith(SystemScreenLauncher { calls++; true }).openSettings(ForeignPermission))
        assertEquals(0, calls)
    }

    // --- helpers ---

    private fun handlerWith(
        launcher: SystemScreenLauncher,
        access: ActivityAccess = createActivityAccess(application).also { accesses += it },
        logger: Logger = NoopLogger,
    ): PermissionController = createPermissionController(
        context = application,
        storage = InMemoryKeyValueStorage(),
        host = host,
        activityAccess = access,
        systemScreenLauncher = launcher,
        logger = logger,
    )

    private fun assertAppDetails(intent: Intent) {
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${application.packageName}", intent.dataString)
    }

    private fun resumedRecordingActivity(): RecordingActivity =
        Robolectric.buildActivity(RecordingActivity::class.java).also { controllers.add(it) }.setup().get()

    private companion object {
        const val SEPARATE_TASK: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
    }
}
