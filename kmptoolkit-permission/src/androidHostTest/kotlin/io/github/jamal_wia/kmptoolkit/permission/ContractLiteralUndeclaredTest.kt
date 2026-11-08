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
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Undeclared and unknown entries, read literally from the status table: "not declared in the manifest" is
 * `Unavailable`, shows nothing, and is logged once; "a type the controller does not know" is `Unavailable`
 * everywhere, `observe` included.
 */
@RunWith(AndroidJUnit4::class)
class ContractLiteralUndeclaredTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val logger = RecordingLogger()

    private fun controller(host: RecordingHost, declared: Set<String>?): AndroidPermissionController = testController(
        context = application,
        storage = InMemoryKeyValueStorage(),
        host = host,
        sdkInt = Build.VERSION_CODES.TIRAMISU,
        declaredPermissions = { declared },
        logger = logger,
    )

    @Test
    fun `an undeclared permission in a batch is unavailable, never asked, and logged once`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val host = RecordingHost(answers = { false })
        val controller: AndroidPermissionController = controller(host, declared = setOf(Manifest.permission.CAMERA))

        val first: Map<Permission, PermissionStatus> = controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone))
        controller.request(listOf(CommonPermission.Microphone))
        controller.check(CommonPermission.Microphone)

        assertEquals(PermissionStatus.Unavailable, first.getValue(CommonPermission.Microphone))
        assertEquals(listOf(listOf(Manifest.permission.CAMERA)), host.groups, "only the declared one is asked")
        assertEquals(1, logger.entries.count { it.level == LogLevel.WARN && "RECORD_AUDIO" in it.message }, "logged once: ${logger.entries}")
    }

    @Test
    fun `a batch whose every permission is undeclared shows nothing`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val host = RecordingHost(answers = { true })

        val result: Map<Permission, PermissionStatus> =
            controller(host, declared = emptySet()).request(listOf(CommonPermission.Camera, CommonPermission.Microphone))

        assertEquals(PermissionStatus.Unavailable, result.getValue(CommonPermission.Camera))
        assertEquals(PermissionStatus.Unavailable, result.getValue(CommonPermission.Microphone))
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `observe of a type the controller does not know emits unavailable once and then stays quiet`() = runTest {
        val controller: AndroidPermissionController = controller(RecordingHost(), declared = null)

        assertEquals(PermissionStatus.Unavailable, controller.observe(ForeignPermission).first())
        assertEquals(listOf(PermissionStatus.Unavailable), controller.observe(ForeignPermission).take(1).toList())
        assertTrue(logger.entries.none { it.level >= LogLevel.ERROR })
    }
}
