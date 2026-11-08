package io.github.jamal_wia.kmptoolkit.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The flow publishes with compare-and-set on the state VALUE, so a transition that returns to a value it
 * left cannot be told from one that never moved. "The newer state wins" must hold across such a round trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionRequestFlowAbaTest {

    @Test
    fun `a slow refresh does not overwrite the state of a start that began and was cancelled while it was reading`() = runTest(StandardTestDispatcher()) {
        val controller = ScriptedPermissionController(status = PermissionStatus.PermanentlyDenied)
        val flow = PermissionRequestFlow(CommonPermission.Camera, controller)
        val gate = CompletableDeferred<Unit>()
        controller.whileChecking = { gate.await() } // the refresh's platform read: has its answer, still in flight

        val refresh = launch { flow.refresh() }
        runCurrent()
        assertEquals(PermissionFlowState.Idle, flow.state.value)

        // The OS changed since that read (the user lifted the restriction), and a screen asked: Requesting, then gone.
        controller.whileChecking = null
        controller.status = PermissionStatus.NotDetermined
        controller.whileRequesting = { awaitCancellation() }
        val start = launch { flow.start() }
        runCurrent()
        assertEquals(PermissionFlowState.Requesting, flow.state.value)
        start.cancel()
        runCurrent()
        assertEquals(PermissionFlowState.Idle, flow.state.value)

        gate.complete(Unit)
        refresh.join()

        assertNotEquals(
            PermissionFlowState.AwaitingSettings,
            flow.state.value,
            "a refresh that read the OS before the start overwrote the newer state with its stale reading",
        )
    }
}
