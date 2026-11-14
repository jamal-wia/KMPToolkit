package io.github.jamal_wia.kmptoolkit.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * `state` is a StateFlow of values: a collector sees a state once, however many times a method re-publishes
 * the same one (a refresh that reads the same status again, a reset of an idle flow). Found by round 8 as a
 * mutation survivor: with the de-duplication of the published wrapper removed, no test noticed a collector
 * being woken with an unchanged state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionRequestFlowEmissionsTest {

    @Test
    fun `refreshing to the same state again and resetting an idle flow emit nothing new`() = runTest(UnconfinedTestDispatcher()) {
        val controller = ScriptedPermissionController(status = PermissionStatus.Granted)
        val flow = PermissionRequestFlow(CommonPermission.Camera, controller)
        val seen: MutableList<PermissionFlowState> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.state.collect { seen += it } }

        flow.reset()
        flow.refresh()
        flow.refresh()
        flow.refresh()
        flow.reset()
        flow.reset()
        flow.reset()

        assertEquals(listOf(PermissionFlowState.Idle, PermissionFlowState.Granted, PermissionFlowState.Idle), seen)
    }

    @Test
    fun `a start that lands on the state it already holds emits nothing new`() = runTest(UnconfinedTestDispatcher()) {
        val controller = ScriptedPermissionController(status = PermissionStatus.PermanentlyDenied)
        val flow = PermissionRequestFlow(CommonPermission.Camera, controller)
        val seen: MutableList<PermissionFlowState> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.state.collect { seen += it } }

        flow.start()
        flow.start()
        flow.openSettings()
        flow.start()

        assertEquals(listOf(PermissionFlowState.Idle, PermissionFlowState.AwaitingSettings), seen)
    }
}
