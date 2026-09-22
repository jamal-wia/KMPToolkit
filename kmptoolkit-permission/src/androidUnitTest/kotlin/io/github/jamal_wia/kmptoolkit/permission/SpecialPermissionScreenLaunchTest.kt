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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * How `requestViaSettings` opens its screens, through the real factories: the flags on the started
 * intent are the task the screen lands in, and up to 1.6.0 they were a bare `FLAG_ACTIVITY_NEW_TASK`,
 * which brought a stale background Settings task forward.
 */
@RunWith(AndroidJUnit4::class)
class SpecialPermissionScreenLaunchTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val activityAccess: ActivityAccess = createActivityAccess(application)
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        activityAccess.release()
        controllers.forEach { controller -> controller.close() }
    }

    // --- the Context-only factory ---

    @Test
    fun `the default factory opens every screen in a task of its own`() {
        val handler: SpecialPermissionHandler = createSpecialPermissionHandler(application)

        for (permission in SpecialPermission.entries) {
            assertTrue(handler.requestViaSettings(permission), "$permission")
            val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity, "$permission")
            assertEquals(SEPARATE_TASK, started.flags, "$permission")
            assertEquals(expectedScreen(permission), started.describe(), "$permission")
        }
    }

    @Test
    fun `the default factory opens a separate task even with an activity resumed`() {
        val handler: SpecialPermissionHandler = createSpecialPermissionHandler(application)
        resumedActivity()

        handler.requestViaSettings(SpecialPermission.OVERLAY)

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `a screen the device does not have is reported as not opened`() {
        shadowOf(application).checkActivities(true)

        assertFalse(createSpecialPermissionHandler(application).requestViaSettings(SpecialPermission.OVERLAY))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- the ActivityAccess factory ---

    @Test
    fun `the activity access factory opens every screen on the caller's task`() {
        val handler: SpecialPermissionHandler = createSpecialPermissionHandler(application, activityAccess)
        val activity: Activity = resumedActivity()

        for (permission in SpecialPermission.entries) {
            assertTrue(handler.requestViaSettings(permission), "$permission")
            val started: Intent = assertNotNull(shadowOf(activity).nextStartedActivity, "$permission")
            assertEquals(0, started.flags, "$permission")
            assertEquals(expectedScreen(permission), started.describe(), "$permission")
        }
    }

    @Test
    fun `the activity access factory falls back to a separate task with no activity resumed`() {
        val handler: SpecialPermissionHandler = createSpecialPermissionHandler(application, activityAccess)

        assertTrue(handler.requestViaSettings(SpecialPermission.WRITE_SETTINGS))

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    // --- a launcher of your own ---

    @Test
    fun `a custom launcher gets one request per call, with every candidate and the screen's kind`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val handler: SpecialPermissionHandler =
            createSpecialPermissionHandler(application, NoopLogger, SystemScreenLauncher { requests += it; true })

        for (permission in SpecialPermission.entries) {
            requests.clear()

            assertTrue(handler.requestViaSettings(permission), "$permission")

            val request: SystemScreenRequest = requests.single()
            assertEquals(SpecialPermissionScreen(permission), request.kind, "$permission")
            assertEquals(listOf(expectedScreen(permission)), request.candidates.map { it.describe() }, "$permission")
            assertTrue(request.candidates.all { it.flags == 0 }, "$permission: candidates carry no launch flags")
        }
        assertNull(shadowOf(application).nextStartedActivity, "the custom launcher decides, nothing else starts")
    }

    @Test
    fun `a launcher that opens nothing makes the request report false`() {
        val handler: SpecialPermissionHandler =
            createSpecialPermissionHandler(application, NoopLogger, SystemScreenLauncher { false })

        for (permission in SpecialPermission.entries) {
            assertFalse(handler.requestViaSettings(permission), "$permission")
        }
    }

    @Test
    fun `a launcher that throws makes the request report false rather than throw`() {
        val handler: SpecialPermissionHandler = createSpecialPermissionHandler(
            application,
            NoopLogger,
            SystemScreenLauncher { throw IllegalStateException("launcher bug") },
        )

        for (permission in SpecialPermission.entries) {
            assertFalse(handler.requestViaSettings(permission), "$permission")
        }
    }

    @Test
    @Config(sdk = [29])
    fun `an entry with nothing to open never reaches the launcher`() {
        var calls = 0
        val handler: SpecialPermissionHandler =
            createSpecialPermissionHandler(application, NoopLogger, SystemScreenLauncher { calls++; true })

        assertFalse(handler.requestViaSettings(SpecialPermission.EXACT_ALARM))
        assertFalse(handler.requestViaSettings(SpecialPermission.ALL_FILES_ACCESS))

        assertEquals(0, calls)
    }

    @Test
    @Config(sdk = [28])
    fun `usage access names the app only where the platform reads it`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val handler: SpecialPermissionHandler =
            createSpecialPermissionHandler(application, NoopLogger, SystemScreenLauncher { requests += it; true })

        handler.requestViaSettings(SpecialPermission.USAGE_STATS_ACCESS)

        assertEquals(
            listOf(Screen(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
            requests.single().candidates.map { it.describe() },
        )
    }

    // --- the kind ---

    @Test
    fun `screen kinds are equal exactly when they name the same permission`() {
        assertEquals(SpecialPermissionScreen(SpecialPermission.OVERLAY), SpecialPermissionScreen(SpecialPermission.OVERLAY))
        assertEquals(
            SpecialPermissionScreen(SpecialPermission.OVERLAY).hashCode(),
            SpecialPermissionScreen(SpecialPermission.OVERLAY).hashCode(),
        )
        assertNotEquals(
            SpecialPermissionScreen(SpecialPermission.OVERLAY),
            SpecialPermissionScreen(SpecialPermission.WRITE_SETTINGS),
        )
        assertEquals("SpecialPermissionScreen(OVERLAY)", SpecialPermissionScreen(SpecialPermission.OVERLAY).toString())
    }

    // --- helpers ---

    /** What a started or requested intent opens, independent of its flags. */
    private data class Screen(val action: String?, val data: String? = null, val extras: Map<String, Any?> = emptyMap())

    private fun Intent.describe(): Screen = Screen(
        action = action,
        data = dataString,
        extras = extras?.let { bundle -> bundle.keySet().associateWith { key -> @Suppress("DEPRECATION") bundle.get(key) } }
            .orEmpty(),
    )

    /** The contract, per entry, on the Robolectric default API level (35). */
    private fun expectedScreen(permission: SpecialPermission): Screen {
        val thisPackage = "package:${application.packageName}"
        return when (permission) {
            SpecialPermission.EXACT_ALARM -> Screen(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, thisPackage)
            SpecialPermission.OVERLAY -> Screen(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, thisPackage)
            SpecialPermission.WRITE_SETTINGS -> Screen(Settings.ACTION_MANAGE_WRITE_SETTINGS, thisPackage)
            SpecialPermission.ALL_FILES_ACCESS ->
                Screen(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, thisPackage)

            SpecialPermission.USAGE_STATS_ACCESS -> Screen(
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                extras = mapOf(Settings.EXTRA_APP_PACKAGE to application.packageName),
            )

            SpecialPermission.IGNORE_BATTERY_OPTIMIZATIONS ->
                Screen(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, thisPackage)

            SpecialPermission.NOTIFICATION_LISTENER_ACCESS -> Screen(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            SpecialPermission.DO_NOT_DISTURB_ACCESS -> Screen(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
    }

    private fun resumedActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup().get()

    private companion object {
        const val SEPARATE_TASK: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
    }
}
