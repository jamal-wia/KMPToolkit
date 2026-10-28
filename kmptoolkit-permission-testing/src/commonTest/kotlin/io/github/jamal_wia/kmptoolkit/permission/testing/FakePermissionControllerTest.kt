package io.github.jamal_wia.kmptoolkit.permission.testing

import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionFlowState
import io.github.jamal_wia.kmptoolkit.permission.PermissionRequestFlow
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Pins the contract documented on [FakePermissionController].
 *
 * A fixture that lies is worse than no fixture: a test suite built on it would pass while the real
 * controllers behave differently. So the two places this double claims to model the OS — a request
 * changing the status, and a request that the OS would never show — are asserted here, and so is
 * the end-to-end use it exists for: driving a [PermissionRequestFlow].
 */
class FakePermissionControllerTest {

    @Test
    fun `a fresh double has recorded nothing`() {
        val controller = FakePermissionController()

        assertTrue(controller.checks.isEmpty())
        assertTrue(controller.requests.isEmpty())
        assertTrue(controller.openedSettings.isEmpty())
        assertTrue(controller.batchRequests.isEmpty())
    }

    @Test
    fun `an unscripted permission reports the default status`() = runTest {
        assertEquals(
            PermissionStatus.NotDetermined,
            FakePermissionController().check(CommonPermission.Camera),
        )
    }

    @Test
    fun `the default status is configurable for every unscripted permission`() = runTest {
        val controller = FakePermissionController(defaultStatus = PermissionStatus.Granted)

        CommonPermission.entries.forEach { permission ->
            assertEquals(PermissionStatus.Granted, controller.check(permission), "p=$permission")
        }
    }

    @Test
    fun `a scripted status overrides the default for that permission only`() = runTest {
        val controller = FakePermissionController(defaultStatus = PermissionStatus.Granted)
        controller.setStatus(CommonPermission.Camera, PermissionStatus.PermanentlyDenied)

        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Camera))
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.Microphone))
    }

    @Test
    fun `checks are recorded in the order they arrived`() = runTest {
        val controller = FakePermissionController()

        controller.check(CommonPermission.Camera)
        controller.check(CommonPermission.Microphone)
        controller.check(CommonPermission.Camera)

        assertContentEquals(
            listOf(CommonPermission.Camera, CommonPermission.Microphone, CommonPermission.Camera),
            controller.checks,
        )
    }

    @Test
    fun `an unscripted request is granted`() = runTest {
        assertEquals(
            PermissionStatus.Granted,
            FakePermissionController().request(CommonPermission.Microphone),
        )
    }

    @Test
    fun `a scripted request outcome is returned`() = runTest {
        val controller = FakePermissionController()
        controller.scriptRequest(CommonPermission.Microphone, PermissionStatus.PermanentlyDenied)

        assertEquals(PermissionStatus.PermanentlyDenied, controller.request(CommonPermission.Microphone))
    }

    @Test
    fun `a request changes the status the way a system dialog would`() = runTest {
        val controller = FakePermissionController()
        controller.scriptRequest(
            CommonPermission.Microphone,
            PermissionStatus.Denied(explainFirst = true),
        )

        controller.request(CommonPermission.Microphone)

        assertEquals(
            PermissionStatus.Denied(explainFirst = true),
            controller.check(CommonPermission.Microphone),
        )
    }

    @Test
    fun `a request the platform would not show ignores the script`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Camera, PermissionStatus.PermanentlyDenied)
        controller.scriptRequest(CommonPermission.Camera, PermissionStatus.Granted)

        assertEquals(PermissionStatus.PermanentlyDenied, controller.request(CommonPermission.Camera))
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Camera))
    }

    @Test
    fun `a request for an already granted permission returns granted without consuming a script`() =
        runTest {
            val controller = FakePermissionController()
            controller.setStatus(CommonPermission.Camera, PermissionStatus.Granted)
            controller.scriptRequest(CommonPermission.Camera, PermissionStatus.PermanentlyDenied)

            assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Camera))
        }

    @Test
    fun `a request is recorded even when no dialog would have been shown`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Camera, PermissionStatus.Granted)

        controller.request(CommonPermission.Camera)

        assertContentEquals(listOf(CommonPermission.Camera), controller.requests)
    }

    @Test
    fun `settings trips are recorded per permission and their availability is configurable`() {
        val controller = FakePermissionController()

        assertTrue(controller.openSettings(CommonPermission.Camera))
        controller.settingsAvailable = false
        assertFalse(controller.openSettings(CommonPermission.Notifications))

        assertContentEquals(listOf(CommonPermission.Camera, CommonPermission.Notifications), controller.openedSettings)
    }

    @Test
    fun `clearing recordings keeps every scripted status`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Camera, PermissionStatus.PermanentlyDenied)
        controller.check(CommonPermission.Camera)
        controller.request(CommonPermission.Camera)
        controller.openSettings(CommonPermission.Camera)
        controller.request(listOf(CommonPermission.Camera))

        controller.clearRecordings()

        assertTrue(controller.checks.isEmpty())
        assertTrue(controller.requests.isEmpty())
        assertTrue(controller.openedSettings.isEmpty())
        assertTrue(controller.batchRequests.isEmpty())
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Camera))
    }

    @Test
    fun `a recording snapshot does not change when more calls arrive`() = runTest {
        val controller = FakePermissionController()
        controller.check(CommonPermission.Camera)
        val snapshot: List<Permission> = controller.checks

        controller.check(CommonPermission.Microphone)

        assertContentEquals(listOf(CommonPermission.Camera), snapshot)
    }

    @Test
    fun `two doubles record independently`() = runTest {
        val first = FakePermissionController()
        val second = FakePermissionController()

        first.check(CommonPermission.Camera)

        assertContentEquals(listOf(CommonPermission.Camera), first.checks)
        assertTrue(second.checks.isEmpty())
    }

    // --- The reason it exists -----------------------------------------------------------------

    @Test
    fun `it drives a request flow through the rationale path`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Microphone, PermissionStatus.Denied(explainFirst = true))
        controller.scriptRequest(CommonPermission.Microphone, PermissionStatus.Granted)
        val flow = PermissionRequestFlow(CommonPermission.Microphone, controller)

        assertEquals(PermissionFlowState.AwaitingRationale, flow.start())
        assertEquals(PermissionFlowState.Granted, flow.rationaleAcknowledged())
        assertContentEquals(listOf(CommonPermission.Microphone), controller.requests)
    }

    @Test
    fun `it drives a request flow through the settings path`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Camera, PermissionStatus.PermanentlyDenied)
        val flow = PermissionRequestFlow(CommonPermission.Camera, controller)

        assertEquals(PermissionFlowState.AwaitingSettings, flow.start())
        assertTrue(flow.openSettings())
        controller.setStatus(CommonPermission.Camera, PermissionStatus.Granted)

        assertEquals(PermissionFlowState.Granted, flow.refresh())
        assertContentEquals(listOf<Permission>(CommonPermission.Camera), controller.openedSettings)
    }

    @Test
    fun `it reproduces a permission revoked while the app was backgrounded`() = runTest {
        val controller = FakePermissionController(defaultStatus = PermissionStatus.Granted)
        val flow = PermissionRequestFlow(CommonPermission.Camera, controller)
        assertEquals(PermissionFlowState.Granted, flow.start())

        controller.setStatus(CommonPermission.Camera, PermissionStatus.NotDetermined)

        assertEquals(PermissionFlowState.Idle, flow.refresh())
    }

    @Test
    fun `observe follows scripted statuses and requests and the default and skips repeats`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = FakePermissionController()
            val seen = mutableListOf<PermissionStatus>()
            val collection = launch { controller.observe(CommonPermission.Camera).toList(seen) }

            controller.setStatus(CommonPermission.Microphone, PermissionStatus.Granted) // another permission
            controller.setStatus(CommonPermission.Camera, PermissionStatus.Denied(explainFirst = true))
            controller.request(CommonPermission.Camera)
            controller.defaultStatus = PermissionStatus.PermanentlyDenied // CAMERA is scripted: no change

            assertEquals(
                listOf(
                    PermissionStatus.NotDetermined,
                    PermissionStatus.Denied(explainFirst = true),
                    PermissionStatus.Granted,
                ),
                seen,
            )
            assertTrue(controller.checks.isEmpty())
            collection.cancel()
        }

    @Test
    fun `observe of an unscripted permission follows the default status`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakePermissionController()
        val seen = mutableListOf<PermissionStatus>()
        val collection = launch { controller.observe(CommonPermission.Location).toList(seen) }

        controller.defaultStatus = PermissionStatus.Granted

        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
        collection.cancel()
    }

    // --- 3.0 additions ------------------------------------------------------------------------

    @Test
    fun `a request the platform would not show is not shown for restricted or unavailable either`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Camera, PermissionStatus.Restricted)
        controller.setStatus(CommonPermission.Microphone, PermissionStatus.Unavailable)
        controller.scriptRequest(CommonPermission.Camera, PermissionStatus.Granted)
        controller.scriptRequest(CommonPermission.Microphone, PermissionStatus.Granted)

        assertEquals(PermissionStatus.Restricted, controller.request(CommonPermission.Camera))
        assertEquals(PermissionStatus.Unavailable, controller.request(CommonPermission.Microphone))
    }

    @Test
    fun `a limited permission stays limited unless a request outcome is scripted`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Location, PermissionStatus.Limited)

        assertEquals(PermissionStatus.Limited, controller.request(CommonPermission.Location))

        controller.scriptRequest(CommonPermission.Location, PermissionStatus.Granted)
        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Location))
    }

    @Test
    fun `a batch answers every distinct permission and is recorded as one call`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Camera, PermissionStatus.PermanentlyDenied)
        controller.scriptRequest(CommonPermission.Microphone, PermissionStatus.Denied(explainFirst = true))

        val result: Map<Permission, PermissionStatus> = controller.request(
            listOf(CommonPermission.Camera, CommonPermission.Microphone, CommonPermission.Camera, CommonPermission.Location),
        )

        assertEquals(
            mapOf<Permission, PermissionStatus>(
                CommonPermission.Camera to PermissionStatus.PermanentlyDenied,
                CommonPermission.Microphone to PermissionStatus.Denied(explainFirst = true),
                CommonPermission.Location to PermissionStatus.Granted,
            ),
            result,
        )
        assertEquals(
            listOf(
                listOf<Permission>(
                    CommonPermission.Camera, CommonPermission.Microphone, CommonPermission.Camera, CommonPermission.Location,
                ),
            ),
            controller.batchRequests,
        )
        assertTrue(controller.requests.isEmpty())
    }

    @Test
    fun `a held request suspends until released and a second request waits behind it`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = FakePermissionController()
            controller.holdRequests = true
            val order = mutableListOf<String>()

            val first = launch { controller.request(CommonPermission.Camera); order += "camera" }
            val second = launch { controller.request(CommonPermission.Microphone); order += "microphone" }

            assertTrue(order.isEmpty())
            assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))

            controller.holdRequests = false
            first.join()
            second.join()

            assertEquals(listOf("camera", "microphone"), order)
            assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.Camera))
        }

    @Test
    fun `a held request cancelled mid-request leaves the status unchanged`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakePermissionController()
        controller.holdRequests = true

        val request = launch { controller.request(CommonPermission.Camera) }
        request.cancel()
        controller.holdRequests = false

        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))
        assertContentEquals(listOf<Permission>(CommonPermission.Camera), controller.requests)
    }

    @Test
    fun `it drives a request flow to limited and to unavailable`() = runTest {
        val controller = FakePermissionController()
        controller.scriptRequest(CommonPermission.Location, PermissionStatus.Limited)
        controller.setStatus(CommonPermission.Camera, PermissionStatus.Restricted)

        assertEquals(PermissionFlowState.Limited, PermissionRequestFlow(CommonPermission.Location, controller).start())
        assertEquals(PermissionFlowState.Unavailable, PermissionRequestFlow(CommonPermission.Camera, controller).start())
    }
}
