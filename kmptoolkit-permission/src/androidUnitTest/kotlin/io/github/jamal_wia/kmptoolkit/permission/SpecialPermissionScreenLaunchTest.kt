package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
 * How `requestViaSettings` opens its screens, through the real factories: which context starts the
 * screen and with which flags decide the task it lands in. Up to 1.6.0 they were a bare
 * `FLAG_ACTIVITY_NEW_TASK` from the application context, which brought a stale background Settings
 * task forward.
 */
@RunWith(AndroidJUnit4::class)
class SpecialPermissionScreenLaunchTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()
    private val controllers: MutableList<ActivityController<out Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        accesses.forEach { access -> access.release() }
        controllers.forEach { controller -> controller.close() }
    }

    // --- the Context-only factory ---

    @Test
    fun `the default factory opens every screen in a task of its own, once`() {
        val handler: SpecialPermissionHandler = createSpecialPermissionHandler(application)

        for (permission in SpecialPermission.entries) {
            assertTrue(handler.requestViaSettings(permission), "$permission")
            val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity, "$permission")
            assertEquals(SEPARATE_TASK, started.flags, "$permission")
            assertEquals(expectedCandidates(permission).first(), started.describe(), "$permission")
            assertNull(shadowOf(application).nextStartedActivity, "$permission: exactly one start")
        }
    }

    @Test
    fun `the default factory opens a separate task, not from the resumed activity`() {
        // Created before the activity resumes: a default that secretly tracked activities would still
        // see it, one that does not never can.
        val handler: SpecialPermissionHandler = createSpecialPermissionHandler(application)
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)

        assertTrue(handler.requestViaSettings(SpecialPermission.OVERLAY))

        assertEquals(emptyList(), activity.started)
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `a screen the device does not have is reported as not opened, and logged`() {
        shadowOf(application).checkActivities(true)
        val logger = RecordingLogger()

        assertFalse(createSpecialPermissionHandler(application, logger).requestViaSettings(SpecialPermission.OVERLAY))

        assertNull(shadowOf(application).nextStartedActivity)
        assertEquals(LogLevel.WARN, logger.entries.single().level)
    }

    // --- fallback candidates, through the default factory ---

    @Test
    fun `each screen falls back to the generic list when the device lacks this app's page`() {
        shadowOf(application).checkActivities(true)
        val handler: SpecialPermissionHandler = createSpecialPermissionHandler(application)
        val chains: Map<SpecialPermission, String> = mapOf(
            SpecialPermission.EXACT_ALARM to Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            SpecialPermission.OVERLAY to Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            SpecialPermission.WRITE_SETTINGS to Settings.ACTION_MANAGE_WRITE_SETTINGS,
            SpecialPermission.ALL_FILES_ACCESS to Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
            SpecialPermission.IGNORE_BATTERY_OPTIMIZATIONS to Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        )
        // Only the generic lists exist on this "device": a filter without a data scheme does not
        // match an intent that carries a package: URI.
        chains.values.forEach { action -> registerScreen(action, withPackageUri = false) }

        for ((permission, list) in chains) {
            assertTrue(handler.requestViaSettings(permission), "$permission")
            val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity, "$permission")
            assertEquals(Screen(list), started.describe(), "$permission")
            assertEquals(SEPARATE_TASK, started.flags, "$permission")
            assertNull(shadowOf(application).nextStartedActivity, "$permission: exactly one start")
        }
    }

    @Test
    fun `this app's page wins over the generic list when the device has both`() {
        shadowOf(application).checkActivities(true)
        registerScreen(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackageUri = true)
        registerScreen(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackageUri = false)

        assertTrue(createSpecialPermissionHandler(application).requestViaSettings(SpecialPermission.OVERLAY))

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Screen(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, thisPackage()), started.describe())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- createSpecialPermissionHandlerWithLauncher with callerTask ---

    @Test
    fun `callerTask on the app's access opens every screen from its activity, with no task flags`() {
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)
        val handler: SpecialPermissionHandler = createSpecialPermissionHandlerWithLauncher(
            application,
            SystemScreenLauncher.callerTask(FixedActivityAccess(activity)),
        )

        for (permission in SpecialPermission.entries) {
            activity.started.clear()

            assertTrue(handler.requestViaSettings(permission), "$permission")

            val started: Intent = activity.started.single()
            assertEquals(0, started.flags, "$permission")
            assertEquals(expectedCandidates(permission).first(), started.describe(), "$permission")
            assertNull(shadowOf(application).nextStartedActivity, "$permission: nothing else starts")
        }
    }

    @Test
    fun `callerTask on an access that tracks no activity opens a separate task`() {
        // Proves the access passed in is the one used: this one never reports the resumed activity.
        val access: ActivityAccess = createActivityAccess(application) { false }.also { accesses += it }
        val handler: SpecialPermissionHandler =
            createSpecialPermissionHandlerWithLauncher(application, SystemScreenLauncher.callerTask(access))
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)

        assertTrue(handler.requestViaSettings(SpecialPermission.WRITE_SETTINGS))

        assertEquals(emptyList(), activity.started)
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `callerTask falls back to a separate task with no activity resumed`() {
        val access: ActivityAccess = createActivityAccess(application).also { accesses += it }
        val handler: SpecialPermissionHandler =
            createSpecialPermissionHandlerWithLauncher(application, SystemScreenLauncher.callerTask(access))

        assertTrue(handler.requestViaSettings(SpecialPermission.WRITE_SETTINGS))

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- a launcher of your own ---

    @Test
    fun `a custom launcher gets one request per call, with every candidate and the screen's kind`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val handler: SpecialPermissionHandler =
            createSpecialPermissionHandlerWithLauncher(application, SystemScreenLauncher { requests += it; true })

        for (permission in SpecialPermission.entries) {
            requests.clear()

            assertTrue(handler.requestViaSettings(permission), "$permission")

            val request: SystemScreenRequest = requests.single()
            assertEquals(SpecialPermissionScreen(permission), request.kind, "$permission")
            assertEquals(expectedCandidates(permission), request.candidates.map { it.describe() }, "$permission")
            assertTrue(request.candidates.all { it.flags == 0 }, "$permission: candidates carry no launch flags")
        }
        assertNull(shadowOf(application).nextStartedActivity, "the custom launcher decides, nothing else starts")
    }

    @Test
    fun `a launcher that opens nothing is called once, and the request reports false and is logged`() {
        for (permission in SpecialPermission.entries) {
            var calls = 0
            val logger = RecordingLogger()
            val handler: SpecialPermissionHandler =
                createSpecialPermissionHandlerWithLauncher(application, SystemScreenLauncher { calls++; false }, logger)

            assertFalse(handler.requestViaSettings(permission), "$permission")

            assertEquals(1, calls, "$permission")
            val entry: RecordingLogger.Entry = logger.entries.single()
            assertEquals(LogLevel.WARN, entry.level, "$permission")
            assertTrue(permission.name in entry.message, "$permission: ${entry.message}")
        }
    }

    @Test
    fun `a launcher that throws is called once, and the request reports false and logs the cause`() {
        for (permission in SpecialPermission.entries) {
            var calls = 0
            val bug = IllegalStateException("launcher bug")
            val logger = RecordingLogger()
            val handler: SpecialPermissionHandler = createSpecialPermissionHandlerWithLauncher(
                application,
                SystemScreenLauncher { calls++; throw bug },
                logger,
            )

            assertFalse(handler.requestViaSettings(permission), "$permission")

            assertEquals(1, calls, "$permission")
            val entry: RecordingLogger.Entry = logger.entries.single()
            assertEquals(LogLevel.WARN, entry.level, "$permission")
            assertEquals(bug, entry.throwable, "$permission")
        }
    }

    // --- API-level boundaries ---

    @Test
    @Config(sdk = [29])
    fun `on API 29 exact alarms and all-files access never reach the launcher`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val handler: SpecialPermissionHandler =
            createSpecialPermissionHandlerWithLauncher(application, SystemScreenLauncher { requests += it; true })

        assertFalse(handler.requestViaSettings(SpecialPermission.EXACT_ALARM))
        assertFalse(handler.requestViaSettings(SpecialPermission.ALL_FILES_ACCESS))

        assertEquals(emptyList(), requests)
    }

    @Test
    @Config(sdk = [29])
    fun `on API 29 usage access names the app`() {
        assertEquals(
            listOf(
                Screen(
                    Settings.ACTION_USAGE_ACCESS_SETTINGS,
                    extras = mapOf(Settings.EXTRA_APP_PACKAGE to application.packageName),
                ),
            ),
            candidatesFor(SpecialPermission.USAGE_STATS_ACCESS),
        )
    }

    @Test
    @Config(sdk = [28])
    fun `on API 28 usage access does not name the app, which the platform would not read`() {
        assertEquals(listOf(Screen(Settings.ACTION_USAGE_ACCESS_SETTINGS)), candidatesFor(SpecialPermission.USAGE_STATS_ACCESS))
    }

    @Test
    @Config(sdk = [30])
    fun `on API 30 all-files access has its chain and exact alarms still have nothing to open`() {
        var calls = 0
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val handler: SpecialPermissionHandler = createSpecialPermissionHandlerWithLauncher(
            application,
            SystemScreenLauncher { calls++; requests += it; true },
        )

        assertFalse(handler.requestViaSettings(SpecialPermission.EXACT_ALARM))
        assertEquals(0, calls)

        assertTrue(handler.requestViaSettings(SpecialPermission.ALL_FILES_ACCESS))
        assertEquals(1, calls)
        assertEquals(
            listOf(
                Screen(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, thisPackage()),
                Screen(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            ),
            requests.single().candidates.map { it.describe() },
        )
    }

    @Test
    @Config(sdk = [31])
    fun `on API 31 exact alarms have this app's page then the generic one`() {
        assertEquals(
            listOf(
                Screen(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, thisPackage()),
                Screen(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
            ),
            candidatesFor(SpecialPermission.EXACT_ALARM),
        )
    }

    // --- the factories as values ---

    @Test
    fun `the factories can be referenced without naming a function type`() {
        // An untyped reference — what `singleOf(::createSpecialPermissionHandler)` and similar DI
        // declarations need — compiles only while each name has exactly one overload.
        val plain = ::createSpecialPermissionHandler
        val withLauncher = ::createSpecialPermissionHandlerWithLauncher

        val typedPlain: (Context, Logger) -> SpecialPermissionHandler = plain
        val typedWithLauncher: (Context, SystemScreenLauncher, Logger) -> SpecialPermissionHandler = withLauncher
        assertIs<SpecialPermissionHandler>(typedPlain(application, NoopLogger))
        assertIs<SpecialPermissionHandler>(typedWithLauncher(application, SystemScreenLauncher.SeparateTask, NoopLogger))
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

    private fun thisPackage(): String = "package:${application.packageName}"

    /** The candidates a launcher receives for [permission], on the current API level. */
    private fun candidatesFor(permission: SpecialPermission): List<Screen> {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        createSpecialPermissionHandlerWithLauncher(application, SystemScreenLauncher { requests += it; true })
            .requestViaSettings(permission)
        return requests.single().candidates.map { it.describe() }
    }

    /** The contract, per entry, on the Robolectric default API level (35). */
    private fun expectedCandidates(permission: SpecialPermission): List<Screen> = when (permission) {
        SpecialPermission.EXACT_ALARM -> listOf(
            Screen(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, thisPackage()),
            Screen(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
        )

        SpecialPermission.OVERLAY -> listOf(
            Screen(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, thisPackage()),
            Screen(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )

        SpecialPermission.WRITE_SETTINGS -> listOf(
            Screen(Settings.ACTION_MANAGE_WRITE_SETTINGS, thisPackage()),
            Screen(Settings.ACTION_MANAGE_WRITE_SETTINGS),
        )

        SpecialPermission.ALL_FILES_ACCESS -> listOf(
            Screen(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, thisPackage()),
            Screen(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )

        SpecialPermission.USAGE_STATS_ACCESS -> listOf(
            Screen(
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                extras = mapOf(Settings.EXTRA_APP_PACKAGE to application.packageName),
            ),
        )

        SpecialPermission.IGNORE_BATTERY_OPTIMIZATIONS -> listOf(
            Screen(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, thisPackage()),
            Screen(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )

        SpecialPermission.NOTIFICATION_LISTENER_ACCESS -> listOf(Screen(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        SpecialPermission.DO_NOT_DISTURB_ACCESS -> listOf(Screen(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    /** Makes [action] resolvable, with or without a `package:` URI, for `checkActivities(true)`. */
    private fun registerScreen(action: String, withPackageUri: Boolean) {
        val component = ComponentName("com.example.settings", "com.example.settings.$action.$withPackageUri")
        shadowOf(application.packageManager).apply {
            addActivityIfNotPresent(component)
            addIntentFilterForActivity(
                component,
                IntentFilter(action).apply {
                    // startActivity resolves with CATEGORY_DEFAULT, as the platform does.
                    addCategory(Intent.CATEGORY_DEFAULT)
                    if (withPackageUri) addDataScheme("package")
                },
            )
        }
    }

    private fun <A : Activity> resumed(type: Class<A>): A =
        Robolectric.buildActivity(type).also { controllers.add(it) }.setup().get()

    private companion object {
        const val SEPARATE_TASK: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
    }
}
