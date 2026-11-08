package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The controller's contract: "Nothing throws. A missing activity, a screen no device app handles, a platform
 * call that fails — all of it comes back as a [PermissionStatus] or as `false`." One platform call at a time
 * fails here (the seams are the `PackageManager`, `NotificationManager` and `Activity` calls the real factory
 * wires in), and `check`, `request` and `observe` must still answer.
 */
@RunWith(AndroidJUnit4::class)
class PlatformCallFailsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun boom(): Nothing = throw IllegalStateException("the platform call failed")

    private fun controller(
        sdkInt: Int = Build.VERSION_CODES.TIRAMISU,
        notificationsEnabled: () -> Boolean = { true },
        isRevokedByPolicy: (String) -> Boolean = { false },
        shouldShowRationale: (String) -> Boolean? = { false },
        protectionOf: (String) -> Protection? = { Protection.Dangerous },
    ): AndroidPermissionController = testController(
        context = application,
        storage = InMemoryKeyValueStorage(),
        sdkInt = sdkInt,
        notificationsEnabled = notificationsEnabled,
        isRevokedByPolicy = isRevokedByPolicy,
        shouldShowRationale = shouldShowRationale,
        protectionOf = protectionOf,
        host = RecordingHost(answers = { false }),
    )

    private fun assertAnswers(block: suspend () -> PermissionStatus): PermissionStatus {
        val result: Result<PermissionStatus> = runCatching { runBlocking { block() } }
        assertTrue(result.isSuccess, "a failing platform call escaped as ${result.exceptionOrNull()}")
        return result.getOrThrow()
    }

    @Test
    fun `a notification switch that cannot be read does not make check throw`() {
        val controller = controller(sdkInt = Build.VERSION_CODES.R, notificationsEnabled = { boom() })
        assertAnswers { controller.check(CommonPermission.Notifications) }
    }

    @Test
    fun `a notification switch that cannot be read does not make request throw`() {
        val controller = controller(sdkInt = Build.VERSION_CODES.R, notificationsEnabled = { boom() })
        assertAnswers { controller.request(CommonPermission.Notifications) }
    }

    @Test
    fun `a policy lookup that fails does not make check throw`() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val controller = controller(isRevokedByPolicy = { boom() })
        assertAnswers { controller.check(CommonPermission.Camera) }
    }

    @Test
    fun `a rationale lookup that fails does not make check throw`() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val controller = controller(shouldShowRationale = { boom() })
        assertAnswers { controller.check(CommonPermission.Camera) }
    }

    @Test
    fun `a rationale lookup that fails after the dialog does not make request throw`() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val controller = controller(shouldShowRationale = { boom() })
        assertAnswers { controller.request(CommonPermission.Camera) }
    }

    @Test
    fun `a protection lookup that fails does not make check of a raw permission throw`() {
        val controller = controller(protectionOf = { boom() })
        assertAnswers { controller.check(AndroidPermission.Runtime("com.example.CUSTOM")) }
    }

    @Test
    fun `a failing platform call reads as the status it can be, not as a failure of observe`() {
        val controller = controller(sdkInt = Build.VERSION_CODES.R, notificationsEnabled = { boom() })
        val first: Result<PermissionStatus> = runCatching {
            runBlocking { kotlinx.coroutines.withTimeout(5_000) { controller.observe(CommonPermission.Notifications).first() } }
        }
        assertTrue(first.isSuccess, "observe failed with ${first.exceptionOrNull()}")
        assertEquals(true, first.getOrNull() != null)
    }
}
