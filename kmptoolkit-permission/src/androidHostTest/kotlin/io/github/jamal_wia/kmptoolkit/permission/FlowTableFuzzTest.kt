package io.github.jamal_wia.kmptoolkit.permission

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * `PermissionRequestFlow` against its documented transition table, as an oracle written from the table
 * alone: a seeded random walk of every event over every status the controller can report, comparing the
 * returned state, `state.value`, the request count and `openSettings` at each step.
 */
class FlowTableFuzzTest {

    private class ScriptedController : PermissionController {
        var status: PermissionStatus = PermissionStatus.NotDetermined
        var outcome: PermissionStatus = PermissionStatus.Granted
        var settingsOpens: Boolean = true
        var requests: Int = 0
        var settingsCalls: Int = 0
        override suspend fun check(permission: Permission): PermissionStatus = status
        override suspend fun request(permission: Permission): PermissionStatus {
            requests++
            status = outcome
            return outcome
        }
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> = emptyMap()
        override fun observe(permission: Permission): Flow<PermissionStatus> = emptyFlow()
        override fun openSettings(permission: Permission): Boolean {
            settingsCalls++
            return settingsOpens
        }
    }

    private val statuses: List<PermissionStatus> = listOf(
        PermissionStatus.Granted,
        PermissionStatus.Limited,
        PermissionStatus.Denied(explainFirst = true),
        PermissionStatus.Denied(explainFirst = false),
        PermissionStatus.PermanentlyDenied,
        PermissionStatus.NotDetermined,
        PermissionStatus.Restricted,
        PermissionStatus.Unavailable,
    )

    private fun afterRequest(outcome: PermissionStatus): PermissionFlowState = when (outcome) {
        is PermissionStatus.Granted -> PermissionFlowState.Granted
        is PermissionStatus.Limited -> PermissionFlowState.Limited
        is PermissionStatus.PermanentlyDenied -> PermissionFlowState.AwaitingSettings
        is PermissionStatus.Restricted, is PermissionStatus.Unavailable -> PermissionFlowState.Unavailable
        is PermissionStatus.Denied, is PermissionStatus.NotDetermined -> PermissionFlowState.Denied
    }

    private fun afterStart(status: PermissionStatus, outcome: PermissionStatus): PermissionFlowState = when (status) {
        is PermissionStatus.Granted -> PermissionFlowState.Granted
        is PermissionStatus.Limited -> PermissionFlowState.Limited
        is PermissionStatus.PermanentlyDenied -> PermissionFlowState.AwaitingSettings
        is PermissionStatus.Restricted, is PermissionStatus.Unavailable -> PermissionFlowState.Unavailable
        is PermissionStatus.Denied -> if (status.explainFirst) PermissionFlowState.AwaitingRationale else afterRequest(outcome)
        is PermissionStatus.NotDetermined -> afterRequest(outcome)
    }

    private fun afterRefresh(status: PermissionStatus): PermissionFlowState = when (status) {
        is PermissionStatus.Granted -> PermissionFlowState.Granted
        is PermissionStatus.Limited -> PermissionFlowState.Limited
        is PermissionStatus.PermanentlyDenied -> PermissionFlowState.AwaitingSettings
        is PermissionStatus.Restricted, is PermissionStatus.Unavailable -> PermissionFlowState.Unavailable
        is PermissionStatus.Denied -> if (status.explainFirst) PermissionFlowState.AwaitingRationale else PermissionFlowState.Idle
        is PermissionStatus.NotDetermined -> PermissionFlowState.Idle
    }

    @Test
    fun `ten thousand random events follow the documented table`() = runTest {
        val random = Random(20261108)
        val controller = ScriptedController()
        val flow = PermissionRequestFlow(CommonPermission.Camera, controller)
        var model: PermissionFlowState = PermissionFlowState.Idle
        var expectedRequests = 0

        repeat(10_000) { step ->
            controller.status = statuses.random(random)
            controller.outcome = statuses.random(random)
            controller.settingsOpens = random.nextBoolean()
            val event: Int = random.nextInt(8)
            val label: String
            val returned: PermissionFlowState
            when (event) {
                0 -> {
                    label = "start"
                    val before: PermissionStatus = controller.status
                    val next: PermissionFlowState = afterStart(before, controller.outcome)
                    if (next == afterRequest(controller.outcome) && (before is PermissionStatus.NotDetermined || (before is PermissionStatus.Denied && !before.explainFirst))) expectedRequests++
                    model = next
                    returned = flow.start()
                }
                1 -> {
                    label = "rationaleAcknowledged"
                    if (model == PermissionFlowState.AwaitingRationale) {
                        model = afterRequest(controller.outcome)
                        expectedRequests++
                    }
                    returned = flow.rationaleAcknowledged()
                }
                2 -> {
                    label = "rationaleDismissed"
                    if (model == PermissionFlowState.AwaitingRationale) model = PermissionFlowState.Denied
                    returned = flow.rationaleDismissed()
                }
                3 -> {
                    label = "settingsDeclined"
                    if (model == PermissionFlowState.AwaitingSettings) model = PermissionFlowState.Denied
                    returned = flow.settingsDeclined()
                }
                4 -> {
                    label = "openSettings"
                    val calls: Int = controller.settingsCalls
                    val opened: Boolean = flow.openSettings()
                    val expectedOpen: Boolean = model == PermissionFlowState.AwaitingSettings && controller.settingsOpens
                    assertEquals(expectedOpen, opened, "step $step $label from $model")
                    assertEquals(if (model == PermissionFlowState.AwaitingSettings) calls + 1 else calls, controller.settingsCalls, "step $step $label from $model")
                    returned = flow.state.value
                }
                5 -> {
                    label = "refresh"
                    model = afterRefresh(controller.status)
                    returned = flow.refresh()
                }
                6 -> {
                    label = "reset"
                    model = PermissionFlowState.Idle
                    returned = flow.reset()
                }
                else -> {
                    label = "observe state"
                    returned = flow.state.value
                }
            }
            assertEquals(model, returned, "step $step $label returned the wrong state")
            assertEquals(model, flow.state.value, "step $step $label left state.value out of step")
            assertEquals(expectedRequests, controller.requests, "step $step $label: the number of system requests")
        }
    }
}
