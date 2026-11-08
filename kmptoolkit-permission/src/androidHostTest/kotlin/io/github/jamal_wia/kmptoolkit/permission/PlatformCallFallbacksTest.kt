package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * What a platform call that throws reads as: the status the platform cannot contradict, never a failure and
 * never a new state. Complements [PlatformCallFailsTest], which pins that nothing escapes.
 */
@RunWith(AndroidJUnit4::class)
class PlatformCallFallbacksTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val logger = RecordingLogger()

    private fun boom(): Nothing = throw IllegalStateException("the platform call failed")

    private fun controller(
        sdkInt: Int = Build.VERSION_CODES.TIRAMISU,
        notificationsEnabled: () -> Boolean = { true },
        isRevokedByPolicy: (String) -> Boolean = { false },
        shouldShowRationale: (String) -> Boolean? = { false },
        protectionOf: (String) -> Protection? = { Protection.Dangerous },
        host: RecordingHost = RecordingHost(answers = { false }),
    ): AndroidPermissionController = testController(
        context = application,
        storage = InMemoryKeyValueStorage(),
        sdkInt = sdkInt,
        notificationsEnabled = notificationsEnabled,
        isRevokedByPolicy = isRevokedByPolicy,
        shouldShowRationale = shouldShowRationale,
        protectionOf = protectionOf,
        host = host,
        logger = logger,
    )

    @Test
    fun `an unreadable notification switch below API 33 reads as switched off`() {
        val controller = controller(sdkInt = Build.VERSION_CODES.R, notificationsEnabled = { boom() })
        assertEquals(PermissionStatus.PermanentlyDenied, runBlocking { controller.check(CommonPermission.Notifications) })
    }

    @Test
    fun `a failing policy lookup never reads as Restricted`() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val controller = controller(isRevokedByPolicy = { boom() })
        assertEquals(PermissionStatus.NotDetermined, runBlocking { controller.check(CommonPermission.Camera) })
    }

    @Test
    fun `a failing rationale lookup reads as cannot tell before and after a refused dialog`() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val controller = controller(shouldShowRationale = { boom() })
        assertEquals(PermissionStatus.NotDetermined, runBlocking { controller.check(CommonPermission.Camera) })
        runBlocking { controller.request(CommonPermission.Camera) }
        assertEquals(PermissionStatus.Denied(explainFirst = false), runBlocking { controller.check(CommonPermission.Camera) })
    }

    @Test
    fun `a failing protection lookup reads a raw permission as Unavailable`() {
        val controller = controller(protectionOf = { boom() })
        assertEquals(
            PermissionStatus.Unavailable,
            runBlocking { controller.check(AndroidPermission.Runtime("com.example.CUSTOM")) },
        )
    }

    @Test
    fun `a seam that keeps failing warns exactly once`() {
        val controller = controller(sdkInt = Build.VERSION_CODES.R, notificationsEnabled = { boom() })
        repeat(20) { runBlocking { controller.check(CommonPermission.Notifications) } }
        assertEquals(1, logger.entries.count { it.level == LogLevel.WARN })
    }

    @Test
    fun `a cancellation thrown by a platform call is rethrown by check`() {
        val controller = controller(
            sdkInt = Build.VERSION_CODES.R,
            notificationsEnabled = { throw CancellationException("cancelled") },
        )
        assertFailsWith<CancellationException> { runBlocking { controller.check(CommonPermission.Notifications) } }
    }
}
