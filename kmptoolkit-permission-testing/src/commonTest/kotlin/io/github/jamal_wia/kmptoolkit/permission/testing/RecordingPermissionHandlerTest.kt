package io.github.jamal_wia.kmptoolkit.permission.testing

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionFlowState
import io.github.jamal_wia.kmptoolkit.permission.PermissionRequestFlow
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Pins the contract documented on [RecordingPermissionHandler].
 *
 * A fixture that lies is worse than no fixture: a test suite built on it would pass while the real
 * handlers behave differently. So the two places this double claims to model the OS — a request
 * changing the status, and a request that the OS would never show — are asserted here, and so is
 * the end-to-end use it exists for: driving a [PermissionRequestFlow].
 */
class RecordingPermissionHandlerTest {

    @Test
    fun `a fresh double has recorded nothing`() {
        val handler = RecordingPermissionHandler()

        assertTrue(handler.checks.isEmpty())
        assertTrue(handler.requests.isEmpty())
        assertEquals(0, handler.openAppSettingsCount)
    }

    @Test
    fun `an unscripted permission reports the default status`() = runTest {
        assertEquals(
            PermissionStatus.NotDetermined,
            RecordingPermissionHandler().check(Permission.CAMERA),
        )
    }

    @Test
    fun `the default status is configurable for every unscripted permission`() = runTest {
        val handler = RecordingPermissionHandler(defaultStatus = PermissionStatus.Granted)

        Permission.entries.forEach { permission ->
            assertEquals(PermissionStatus.Granted, handler.check(permission), "p=$permission")
        }
    }

    @Test
    fun `a scripted status overrides the default for that permission only`() = runTest {
        val handler = RecordingPermissionHandler(defaultStatus = PermissionStatus.Granted)
        handler.setStatus(Permission.CAMERA, PermissionStatus.PermanentlyDenied)

        assertEquals(PermissionStatus.PermanentlyDenied, handler.check(Permission.CAMERA))
        assertEquals(PermissionStatus.Granted, handler.check(Permission.MICROPHONE))
    }

    @Test
    fun `checks are recorded in the order they arrived`() = runTest {
        val handler = RecordingPermissionHandler()

        handler.check(Permission.CAMERA)
        handler.check(Permission.MICROPHONE)
        handler.check(Permission.CAMERA)

        assertContentEquals(
            listOf(Permission.CAMERA, Permission.MICROPHONE, Permission.CAMERA),
            handler.checks,
        )
    }

    @Test
    fun `an unscripted request is granted`() = runTest {
        assertEquals(
            PermissionStatus.Granted,
            RecordingPermissionHandler().request(Permission.MICROPHONE),
        )
    }

    @Test
    fun `a scripted request outcome is returned`() = runTest {
        val handler = RecordingPermissionHandler()
        handler.scriptRequest(Permission.MICROPHONE, PermissionStatus.PermanentlyDenied)

        assertEquals(PermissionStatus.PermanentlyDenied, handler.request(Permission.MICROPHONE))
    }

    @Test
    fun `a request changes the status the way a system dialog would`() = runTest {
        val handler = RecordingPermissionHandler()
        handler.scriptRequest(
            Permission.MICROPHONE,
            PermissionStatus.Denied(shouldShowRationale = true),
        )

        handler.request(Permission.MICROPHONE)

        assertEquals(
            PermissionStatus.Denied(shouldShowRationale = true),
            handler.check(Permission.MICROPHONE),
        )
    }

    @Test
    fun `a request the platform would not show ignores the script`() = runTest {
        val handler = RecordingPermissionHandler()
        handler.setStatus(Permission.CAMERA, PermissionStatus.PermanentlyDenied)
        handler.scriptRequest(Permission.CAMERA, PermissionStatus.Granted)

        assertEquals(PermissionStatus.PermanentlyDenied, handler.request(Permission.CAMERA))
        assertEquals(PermissionStatus.PermanentlyDenied, handler.check(Permission.CAMERA))
    }

    @Test
    fun `a request for an already granted permission returns granted without consuming a script`() =
        runTest {
            val handler = RecordingPermissionHandler()
            handler.setStatus(Permission.CAMERA, PermissionStatus.Granted)
            handler.scriptRequest(Permission.CAMERA, PermissionStatus.PermanentlyDenied)

            assertEquals(PermissionStatus.Granted, handler.request(Permission.CAMERA))
        }

    @Test
    fun `a request is recorded even when no dialog would have been shown`() = runTest {
        val handler = RecordingPermissionHandler()
        handler.setStatus(Permission.CAMERA, PermissionStatus.Granted)

        handler.request(Permission.CAMERA)

        assertContentEquals(listOf(Permission.CAMERA), handler.requests)
    }

    @Test
    fun `settings trips are counted and their availability is configurable`() {
        val handler = RecordingPermissionHandler()

        assertTrue(handler.openAppSettings())
        handler.settingsAvailable = false
        assertFalse(handler.openAppSettings())

        assertEquals(2, handler.openAppSettingsCount)
    }

    @Test
    fun `clearing recordings keeps every scripted status`() = runTest {
        val handler = RecordingPermissionHandler()
        handler.setStatus(Permission.CAMERA, PermissionStatus.PermanentlyDenied)
        handler.check(Permission.CAMERA)
        handler.request(Permission.CAMERA)
        handler.openAppSettings()

        handler.clearRecordings()

        assertTrue(handler.checks.isEmpty())
        assertTrue(handler.requests.isEmpty())
        assertEquals(0, handler.openAppSettingsCount)
        assertEquals(PermissionStatus.PermanentlyDenied, handler.check(Permission.CAMERA))
    }

    @Test
    fun `a recording snapshot does not change when more calls arrive`() = runTest {
        val handler = RecordingPermissionHandler()
        handler.check(Permission.CAMERA)
        val snapshot: List<Permission> = handler.checks

        handler.check(Permission.MICROPHONE)

        assertContentEquals(listOf(Permission.CAMERA), snapshot)
    }

    @Test
    fun `two doubles record independently`() = runTest {
        val first = RecordingPermissionHandler()
        val second = RecordingPermissionHandler()

        first.check(Permission.CAMERA)

        assertContentEquals(listOf(Permission.CAMERA), first.checks)
        assertTrue(second.checks.isEmpty())
    }

    // --- The reason it exists -----------------------------------------------------------------

    @Test
    fun `it drives a request flow through the rationale path`() = runTest {
        val handler = RecordingPermissionHandler()
        handler.setStatus(Permission.MICROPHONE, PermissionStatus.Denied(shouldShowRationale = true))
        handler.scriptRequest(Permission.MICROPHONE, PermissionStatus.Granted)
        val flow = PermissionRequestFlow(Permission.MICROPHONE, handler)

        assertEquals(PermissionFlowState.AwaitingRationale, flow.start())
        assertEquals(PermissionFlowState.Granted, flow.rationaleAcknowledged())
        assertContentEquals(listOf(Permission.MICROPHONE), handler.requests)
    }

    @Test
    fun `it drives a request flow through the settings path`() = runTest {
        val handler = RecordingPermissionHandler()
        handler.setStatus(Permission.CAMERA, PermissionStatus.PermanentlyDenied)
        val flow = PermissionRequestFlow(Permission.CAMERA, handler)

        assertEquals(PermissionFlowState.AwaitingSettings, flow.start())
        assertTrue(flow.openSettings())
        handler.setStatus(Permission.CAMERA, PermissionStatus.Granted)

        assertEquals(PermissionFlowState.Granted, flow.refresh())
        assertEquals(1, handler.openAppSettingsCount)
    }

    @Test
    fun `it reproduces a permission revoked while the app was backgrounded`() = runTest {
        val handler = RecordingPermissionHandler(defaultStatus = PermissionStatus.Granted)
        val flow = PermissionRequestFlow(Permission.CAMERA, handler)
        assertEquals(PermissionFlowState.Granted, flow.start())

        handler.setStatus(Permission.CAMERA, PermissionStatus.NotDetermined)

        assertEquals(PermissionFlowState.Idle, flow.refresh())
    }
}
