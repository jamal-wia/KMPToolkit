package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.platform.activity.createActivityTracker
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** A [PermissionRequestHost] whose answer — and whose ability to answer — the test dictates. */
private class StubHost(
    private val granted: Boolean = true,
    private val canLaunch: Boolean = true,
    private val throwOnLaunch: Boolean = false,
    private val answerThenReportFailure: Boolean = false,
) : PermissionRequestHost {

    var launchedWith: String? = null
        private set

    var launchCount: Int = 0
        private set

    override fun launch(androidPermission: String, onResult: (Boolean) -> Unit): Boolean {
        launchedWith = androidPermission
        launchCount++
        if (throwOnLaunch) error("the activity is gone")
        if (answerThenReportFailure) {
            onResult(granted)
            return false
        }
        if (!canLaunch) return false
        onResult(granted)
        return true
    }
}

/**
 * The Android status logic, exercised against a real `Context` and a real `PackageManager`.
 *
 * The cases come from the module's contract — `docs/kmptoolkit-permission/01-overview.md` and
 * `05-platform-notes.md` — with particular attention to the distinction Android cannot draw for
 * itself: "never asked" and "permanently denied" look identical through its API, and getting them
 * the wrong way round either sends a first-run user to system settings or leaves a permanently
 * denied user tapping a dead button forever.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPermissionHandlerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val context: Context = application
    private val storage: KeyValueStorage = InMemoryKeyValueStorage()

    private fun handler(
        host: PermissionRequestHost = StubHost(),
        shouldShowRationale: Boolean = false,
        sdkInt: Int = Build.VERSION_CODES.TIRAMISU,
        keyPrefix: String = "test",
        settings: (Intent) -> Boolean = { true },
    ): AndroidPermissionHandler = AndroidPermissionHandler(
        context = context,
        host = host,
        storage = storage,
        keyPrefix = keyPrefix,
        logger = NoopLogger,
        sdkInt = sdkInt,
        shouldShowRationale = { shouldShowRationale },
        startSettings = settings,
    )

    private fun grant(androidPermission: String) {
        shadowOf(application).grantPermissions(androidPermission)
    }

    private fun deny(androidPermission: String) {
        shadowOf(application).denyPermissions(androidPermission)
    }

    private fun askedFlag(permission: Permission, keyPrefix: String = "test"): String? =
        storage.getStringOrNull(askedKey(keyPrefix, permission))

    // --- check --------------------------------------------------------------------------------

    @Test
    fun `a granted permission is granted`() = runTest {
        grant(Manifest.permission.CAMERA)

        assertEquals(PermissionStatus.Granted, handler().check(Permission.CAMERA))
    }

    @Test
    fun `a permission never asked for is not determined`() = runTest {
        deny(Manifest.permission.CAMERA)

        assertEquals(PermissionStatus.NotDetermined, handler().check(Permission.CAMERA))
    }

    @Test
    fun `a permission the platform wants explained is denied once`() = runTest {
        deny(Manifest.permission.CAMERA)

        assertEquals(
            PermissionStatus.Denied(shouldShowRationale = true),
            handler(shouldShowRationale = true).check(Permission.CAMERA),
        )
    }

    @Test
    fun `a permission already asked for with no rationale left is permanently denied`() = runTest {
        deny(Manifest.permission.CAMERA)
        storage.put(askedKey("test", Permission.CAMERA), "true")

        assertEquals(PermissionStatus.PermanentlyDenied, handler().check(Permission.CAMERA))
    }

    @Test
    fun `granting clears the flag so a later revocation reads as not determined again`() = runTest {
        storage.put(askedKey("test", Permission.CAMERA), "true")
        grant(Manifest.permission.CAMERA)
        assertEquals(PermissionStatus.Granted, handler().check(Permission.CAMERA))

        deny(Manifest.permission.CAMERA)

        assertEquals(PermissionStatus.NotDetermined, handler().check(Permission.CAMERA))
        assertNull(askedFlag(Permission.CAMERA))
    }

    @Test
    fun `one permission's flag does not decide another's status`() = runTest {
        deny(Manifest.permission.CAMERA)
        deny(Manifest.permission.RECORD_AUDIO)
        storage.put(askedKey("test", Permission.CAMERA), "true")

        assertEquals(PermissionStatus.PermanentlyDenied, handler().check(Permission.CAMERA))
        assertEquals(PermissionStatus.NotDetermined, handler().check(Permission.MICROPHONE))
    }

    @Test
    fun `checking shows nothing to the user`() = runTest {
        deny(Manifest.permission.CAMERA)
        val host = StubHost()

        handler(host = host).check(Permission.CAMERA)

        assertEquals(0, host.launchCount)
    }

    // --- Notifications before and after API 33 ------------------------------------------------

    @Test
    fun `notifications are granted below api 33 where there is no runtime grant to obtain`() =
        runTest {
            deny(Manifest.permission.POST_NOTIFICATIONS)
            storage.put(askedKey("test", Permission.NOTIFICATIONS), "true")

            val status: PermissionStatus =
                handler(sdkInt = Build.VERSION_CODES.S_V2).check(Permission.NOTIFICATIONS)

            assertEquals(PermissionStatus.Granted, status)
        }

    @Test
    fun `requesting notifications below api 33 shows no dialog`() = runTest {
        deny(Manifest.permission.POST_NOTIFICATIONS)
        val host = StubHost()

        val status: PermissionStatus =
            handler(host = host, sdkInt = Build.VERSION_CODES.S_V2).request(Permission.NOTIFICATIONS)

        assertEquals(PermissionStatus.Granted, status)
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `notifications follow the normal path from api 33`() = runTest {
        deny(Manifest.permission.POST_NOTIFICATIONS)

        assertEquals(PermissionStatus.NotDetermined, handler().check(Permission.NOTIFICATIONS))
    }

    // --- The platform permission strings ------------------------------------------------------

    @Test
    fun `each permission reaches the dialog as its own platform string`() = runTest {
        val expected: Map<Permission, String> = mapOf(
            Permission.CAMERA to Manifest.permission.CAMERA,
            Permission.MICROPHONE to Manifest.permission.RECORD_AUDIO,
            Permission.NOTIFICATIONS to Manifest.permission.POST_NOTIFICATIONS,
        )

        expected.forEach { (permission, androidPermission) ->
            deny(androidPermission)
            val host = StubHost(granted = false)

            handler(host = host).request(permission)

            assertEquals(androidPermission, host.launchedWith, "permission=$permission")
        }
    }

    // --- request ------------------------------------------------------------------------------

    @Test
    fun `requesting a granted permission shows no dialog`() = runTest {
        grant(Manifest.permission.CAMERA)
        val host = StubHost()

        assertEquals(PermissionStatus.Granted, handler(host = host).request(Permission.CAMERA))
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `requesting a permanently denied permission shows no dialog`() = runTest {
        deny(Manifest.permission.CAMERA)
        storage.put(askedKey("test", Permission.CAMERA), "true")
        val host = StubHost()

        val status: PermissionStatus = handler(host = host).request(Permission.CAMERA)

        assertEquals(PermissionStatus.PermanentlyDenied, status)
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `a granted request reports granted and leaves no flag behind`() = runTest {
        deny(Manifest.permission.CAMERA)
        val host = StubHost(granted = true)

        val status: PermissionStatus = handler(host = host).request(Permission.CAMERA)

        assertEquals(PermissionStatus.Granted, status)
        assertEquals(1, host.launchCount)
        assertNull(askedFlag(Permission.CAMERA))
    }

    @Test
    fun `a first refusal reports denied with a rationale and records the attempt`() = runTest {
        deny(Manifest.permission.CAMERA)
        val host = StubHost(granted = false)

        val status: PermissionStatus =
            handler(host = host, shouldShowRationale = true).request(Permission.CAMERA)

        assertEquals(PermissionStatus.Denied(shouldShowRationale = true), status)
        assertEquals("true", askedFlag(Permission.CAMERA))
    }

    @Test
    fun `a refusal with no rationale left reports permanently denied`() = runTest {
        deny(Manifest.permission.CAMERA)
        val host = StubHost(granted = false)

        val status: PermissionStatus =
            handler(host = host, shouldShowRationale = false).request(Permission.CAMERA)

        assertEquals(PermissionStatus.PermanentlyDenied, status)
        assertEquals("true", askedFlag(Permission.CAMERA))
    }

    @Test
    fun `a dialog that could not be shown is not recorded as a refusal`() = runTest {
        deny(Manifest.permission.CAMERA)
        val host = StubHost(canLaunch = false)

        val status: PermissionStatus = handler(host = host).request(Permission.CAMERA)

        assertEquals(PermissionStatus.NotDetermined, status)
        assertNull(
            askedFlag(Permission.CAMERA),
            "a permission whose dialog never appeared must not become permanently denied",
        )
    }

    @Test
    fun `a host that throws is not recorded as a refusal either`() = runTest {
        deny(Manifest.permission.CAMERA)
        val host = StubHost(throwOnLaunch = true)

        val status: PermissionStatus = handler(host = host).request(Permission.CAMERA)

        assertEquals(PermissionStatus.NotDetermined, status)
        assertNull(askedFlag(Permission.CAMERA))
    }

    @Test
    fun `a host that answers and then reports failure is taken at its answer`() = runTest {
        deny(Manifest.permission.CAMERA)
        val host = StubHost(granted = true, answerThenReportFailure = true)

        assertEquals(PermissionStatus.Granted, handler(host = host).request(Permission.CAMERA))
    }

    @Test
    fun `a second refusal after a first turns the permission permanently denied`() = runTest {
        deny(Manifest.permission.CAMERA)
        val first: PermissionStatus = handler(host = StubHost(granted = false), shouldShowRationale = true)
            .request(Permission.CAMERA)
        assertEquals(PermissionStatus.Denied(shouldShowRationale = true), first)

        val second: PermissionStatus = handler(host = StubHost(granted = false), shouldShowRationale = false)
            .request(Permission.CAMERA)

        assertEquals(PermissionStatus.PermanentlyDenied, second)
    }

    // --- Keys ---------------------------------------------------------------------------------

    @Test
    fun `the flag is written under the configured prefix`() = runTest {
        deny(Manifest.permission.CAMERA)

        handler(host = StubHost(granted = false), keyPrefix = "com.example.custom")
            .request(Permission.CAMERA)

        assertEquals("true", askedFlag(Permission.CAMERA, keyPrefix = "com.example.custom"))
        assertNull(askedFlag(Permission.CAMERA))
    }

    @Test
    fun `the factory defaults the prefix to the consuming app's package name`() = runTest {
        deny(Manifest.permission.CAMERA)
        val handler: PermissionHandler = createPermissionHandler(
            context = context,
            host = StubHost(granted = false),
            activityAccess = createActivityTracker(application),
            storage = storage,
        )

        handler.request(Permission.CAMERA)

        assertEquals(
            "true",
            storage.getStringOrNull("${context.packageName}.kmptoolkit.permission.asked.CAMERA"),
        )
    }

    // --- Settings -----------------------------------------------------------------------------

    @Test
    fun `opening settings targets this app's own details page`() {
        var opened: Intent? = null

        val result: Boolean = handler(settings = { intent -> opened = intent; true }).openAppSettings()

        assertTrue(result)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened?.action)
        assertEquals(context.packageName, opened?.data?.schemeSpecificPart)
    }

    @Test
    fun `a settings screen that cannot be opened is reported rather than thrown`() {
        val handler: AndroidPermissionHandler =
            handler(settings = { error("no activity handles this intent") })

        assertFalse(handler.openAppSettings())
    }

    @Test
    fun `a settings screen the platform refuses is reported as false`() {
        assertFalse(handler(settings = { false }).openAppSettings())
    }
}
