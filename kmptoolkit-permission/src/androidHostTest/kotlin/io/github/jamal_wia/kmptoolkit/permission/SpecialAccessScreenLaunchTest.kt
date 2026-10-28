package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.app.Application
import android.content.ComponentName
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
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
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
 * How a special access's Settings screen is opened, through the real factory: which candidates, which
 * context starts the screen and with which flags decide the task it lands in. Exercised through
 * `openSettings`, which opens exactly the screen a request opens but does not wait for the user to come
 * back — the waiting is pinned in `AndroidSpecialAccessTest`. Up to 1.6.0 these were a bare
 * `FLAG_ACTIVITY_NEW_TASK` from the application context, which brought a stale background Settings task
 * forward.
 */
@RunWith(AndroidJUnit4::class)
class SpecialAccessScreenLaunchTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()
    private val controllers: MutableList<ActivityController<out Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        accesses.forEach { access -> access.release() }
        controllers.forEach { controller -> controller.close() }
    }

    // --- SeparateTask ---

    @Test
    fun `SeparateTask opens every screen in a task of its own, once`() {
        val handler: PermissionController = special(SystemScreenLauncher.SeparateTask)

        for (permission in AndroidPermission.specialAccesses) {
            assertTrue(handler.openSettings(permission), "$permission")
            val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity, "$permission")
            assertEquals(SEPARATE_TASK, started.flags, "$permission")
            assertEquals(expectedCandidates(permission).first(), started.describe(), "$permission")
            assertNull(shadowOf(application).nextStartedActivity, "$permission: exactly one start")
        }
    }

    @Test
    fun `SeparateTask opens a separate task, not from the resumed activity`() {
        val handler: PermissionController = special(SystemScreenLauncher.SeparateTask)
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)

        assertTrue(handler.openSettings(AndroidPermission.Overlay))

        assertEquals(emptyList(), activity.started)
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `a screen the device does not have is reported as not opened, and logged`() {
        shadowOf(application).checkActivities(true)
        val logger = RecordingLogger()

        assertFalse(special(SystemScreenLauncher.SeparateTask, logger).openSettings(AndroidPermission.Overlay))

        assertNull(shadowOf(application).nextStartedActivity)
        assertEquals(LogLevel.WARN, logger.entries.single().level)
    }

    // --- fallback candidates ---

    @Test
    fun `each screen falls back to the generic list when the device lacks this app's page`() {
        shadowOf(application).checkActivities(true)
        val handler: PermissionController = special(SystemScreenLauncher.SeparateTask)
        val chains: Map<AndroidPermission.SpecialAccess, String> = mapOf(
            AndroidPermission.ExactAlarm to Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            AndroidPermission.Overlay to Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            AndroidPermission.WriteSettings to Settings.ACTION_MANAGE_WRITE_SETTINGS,
            AndroidPermission.AllFilesAccess to Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
            AndroidPermission.IgnoreBatteryOptimizations to Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            AndroidPermission.InstallPackages to Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        )
        // Only the generic lists exist on this "device": a filter without a data scheme does not
        // match an intent that carries a package: URI.
        chains.values.forEach { action -> registerScreen(action, withPackageUri = false) }

        for ((permission, list) in chains) {
            assertTrue(handler.openSettings(permission), "$permission")
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

        assertTrue(special(SystemScreenLauncher.SeparateTask).openSettings(AndroidPermission.Overlay))

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Screen(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, thisPackage()), started.describe())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- callerTask ---

    @Test
    fun `callerTask on the app's access opens every screen from its activity, with no task flags`() {
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)
        val handler: PermissionController = special(SystemScreenLauncher.callerTask(FixedActivityAccess(activity)),
        )

        for (permission in AndroidPermission.specialAccesses) {
            activity.started.clear()

            assertTrue(handler.openSettings(permission), "$permission")

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
        val handler: PermissionController =
            special(SystemScreenLauncher.callerTask(access))
        val activity: RecordingActivity = resumed(RecordingActivity::class.java)

        assertTrue(handler.openSettings(AndroidPermission.WriteSettings))

        assertEquals(emptyList(), activity.started)
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `callerTask falls back to a separate task with no activity resumed`() {
        val access: ActivityAccess = createActivityAccess(application).also { accesses += it }
        val handler: PermissionController =
            special(SystemScreenLauncher.callerTask(access))

        assertTrue(handler.openSettings(AndroidPermission.WriteSettings))

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- a launcher of your own ---

    @Test
    fun `a custom launcher gets one request per call, with every candidate and the screen's kind`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val handler: PermissionController =
            special(SystemScreenLauncher { requests += it; true })

        for (permission in AndroidPermission.specialAccesses) {
            requests.clear()

            assertTrue(handler.openSettings(permission), "$permission")

            val request: SystemScreenRequest = requests.single()
            assertEquals(PermissionScreen(permission), request.kind, "$permission")
            assertEquals(expectedCandidates(permission), request.candidates.map { it.describe() }, "$permission")
            assertTrue(request.candidates.all { it.flags == 0 }, "$permission: candidates carry no launch flags")
        }
        assertNull(shadowOf(application).nextStartedActivity, "the custom launcher decides, nothing else starts")
    }

    @Test
    fun `a launcher that opens nothing is called once, and the request reports false and is logged`() {
        for (permission in AndroidPermission.specialAccesses) {
            var calls = 0
            val logger = RecordingLogger()
            val handler: PermissionController =
                special(SystemScreenLauncher { calls++; false }, logger)

            assertFalse(handler.openSettings(permission), "$permission")

            assertEquals(1, calls, "$permission")
            val entry: RecordingLogger.Entry = logger.entries.single()
            assertEquals(LogLevel.WARN, entry.level, "$permission")
            assertTrue("$permission" in entry.message, "$permission: ${entry.message}")
        }
    }

    @Test
    fun `a launcher that throws is called once, and the request reports false and logs the cause`() {
        for (permission in AndroidPermission.specialAccesses) {
            var calls = 0
            val bug = IllegalStateException("launcher bug")
            val logger = RecordingLogger()
            val handler: PermissionController = special(SystemScreenLauncher { calls++; throw bug },
                logger,
            )

            assertFalse(handler.openSettings(permission), "$permission")

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
        val handler: PermissionController =
            special(SystemScreenLauncher { requests += it; true })

        assertFalse(handler.openSettings(AndroidPermission.ExactAlarm))
        assertFalse(handler.openSettings(AndroidPermission.AllFilesAccess))

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
            candidatesFor(AndroidPermission.UsageStatsAccess),
        )
    }

    @Test
    @Config(sdk = [28])
    fun `on API 28 usage access does not name the app, which the platform would not read`() {
        assertEquals(listOf(Screen(Settings.ACTION_USAGE_ACCESS_SETTINGS)), candidatesFor(AndroidPermission.UsageStatsAccess))
    }

    @Test
    @Config(sdk = [30])
    fun `on API 30 all-files access has its chain and exact alarms still have nothing to open`() {
        var calls = 0
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val handler: PermissionController = special(SystemScreenLauncher { calls++; requests += it; true },
        )

        assertFalse(handler.openSettings(AndroidPermission.ExactAlarm))
        assertEquals(0, calls)

        assertTrue(handler.openSettings(AndroidPermission.AllFilesAccess))
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
            candidatesFor(AndroidPermission.ExactAlarm),
        )
    }

    @Test
    @Config(sdk = [25])
    fun `on API 25 installing packages has nothing to open, since there is no per-app grant`() {
        // 2.x opened the device-wide "Unknown sources" switch here: a different setting under the same words.
        val requests: MutableList<SystemScreenRequest> = mutableListOf()

        assertFalse(special(SystemScreenLauncher { requests += it; true }).openSettings(AndroidPermission.InstallPackages))
        assertEquals(emptyList(), requests)
    }

    @Test
    @Config(sdk = [26])
    fun `on API 26 installing unknown apps has this app's page then the generic one`() {
        assertEquals(
            listOf(
                Screen(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, thisPackage()),
                Screen(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            ),
            candidatesFor(AndroidPermission.InstallPackages),
        )
    }

    @Test
    @Config(sdk = [29])
    fun `on API 29 media management full-screen intents and unused-app exemption never reach the launcher`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val handler: PermissionController =
            special(SystemScreenLauncher { requests += it; true })

        assertFalse(handler.openSettings(AndroidPermission.MediaManagement))
        assertFalse(handler.openSettings(AndroidPermission.FullScreenNotifications))
        assertFalse(handler.openSettings(AndroidPermission.KeepPermissionsWhenUnused))

        assertEquals(emptyList(), requests)
    }

    @Test
    @Config(sdk = [30])
    fun `on API 30 the unused-app exemption has this app's page and media management still has nothing`() {
        assertEquals(
            listOf(Screen(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, thisPackage())),
            candidatesFor(AndroidPermission.KeepPermissionsWhenUnused),
        )
        assertFalse(
            special(SystemScreenLauncher { true })
                .openSettings(AndroidPermission.MediaManagement),
        )
    }

    @Test
    @Config(sdk = [31])
    fun `on API 31 media management has this app's page and full-screen intents still have nothing`() {
        assertEquals(
            listOf(Screen(Settings.ACTION_REQUEST_MANAGE_MEDIA, thisPackage())),
            candidatesFor(AndroidPermission.MediaManagement),
        )
        assertFalse(
            special(SystemScreenLauncher { true })
                .openSettings(AndroidPermission.FullScreenNotifications),
        )
    }

    @Test
    @Config(sdk = [34])
    fun `on API 34 full-screen intents have this app's page`() {
        assertEquals(
            listOf(Screen(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, thisPackage())),
            candidatesFor(AndroidPermission.FullScreenNotifications),
        )
    }

    // --- the kind ---

    @Test
    fun `screen kinds are equal exactly when they name the same permission`() {
        assertEquals(PermissionScreen(AndroidPermission.Overlay), PermissionScreen(AndroidPermission.Overlay))
        assertEquals(
            PermissionScreen(AndroidPermission.Overlay).hashCode(),
            PermissionScreen(AndroidPermission.Overlay).hashCode(),
        )
        assertNotEquals(
            PermissionScreen(AndroidPermission.Overlay),
            PermissionScreen(AndroidPermission.WriteSettings),
        )
        assertEquals("PermissionScreen(permission=Overlay)", PermissionScreen(AndroidPermission.Overlay).toString())
    }

    // --- helpers ---

    /** The controller, opening screens through [launcher]; it never shows a runtime dialog here. */
    private fun special(launcher: SystemScreenLauncher, logger: Logger = NoopLogger): PermissionController =
        createPermissionController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = RecordingHost(canLaunch = false),
            activityAccess = createActivityAccess(application).also { accesses += it },
            systemScreenLauncher = launcher,
            logger = logger,
        )

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
    private fun candidatesFor(permission: AndroidPermission.SpecialAccess): List<Screen> {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        special(SystemScreenLauncher { requests += it; true })
            .openSettings(permission)
        return requests.single().candidates.map { it.describe() }
    }

    /** The contract, per entry, on the Robolectric default API level (35). */
    private fun expectedCandidates(permission: AndroidPermission.SpecialAccess): List<Screen> = when (permission) {
        AndroidPermission.ExactAlarm -> listOf(
            Screen(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, thisPackage()),
            Screen(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
        )

        AndroidPermission.Overlay -> listOf(
            Screen(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, thisPackage()),
            Screen(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )

        AndroidPermission.WriteSettings -> listOf(
            Screen(Settings.ACTION_MANAGE_WRITE_SETTINGS, thisPackage()),
            Screen(Settings.ACTION_MANAGE_WRITE_SETTINGS),
        )

        AndroidPermission.AllFilesAccess -> listOf(
            Screen(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, thisPackage()),
            Screen(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )

        AndroidPermission.UsageStatsAccess -> listOf(
            Screen(
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                extras = mapOf(Settings.EXTRA_APP_PACKAGE to application.packageName),
            ),
        )

        AndroidPermission.IgnoreBatteryOptimizations -> listOf(
            Screen(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, thisPackage()),
            Screen(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )

        AndroidPermission.NotificationListenerAccess -> listOf(Screen(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        AndroidPermission.DoNotDisturbAccess -> listOf(Screen(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        AndroidPermission.MediaManagement -> listOf(Screen(Settings.ACTION_REQUEST_MANAGE_MEDIA, thisPackage()))
        AndroidPermission.InstallPackages -> listOf(
            Screen(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, thisPackage()),
            Screen(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        )

        AndroidPermission.FullScreenNotifications ->
            listOf(Screen(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, thisPackage()))

        AndroidPermission.KeepPermissionsWhenUnused ->
            listOf(Screen(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, thisPackage()))

        // Covered by AndroidRolesAndAdminsTest, which declares the service it needs.
        is AndroidPermission.AccessibilityService -> listOf(Screen(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
