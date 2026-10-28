package io.github.jamal_wia.kmptoolkit.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Pins the transition table documented on [PermissionRequestFlow], one test per row plus the
 * sequences a real screen produces.
 *
 * The cases are derived from that table and from `docs/kmptoolkit-permission/01-overview.md`, not
 * from reading the implementation: the point of a state machine is that its contract is writable
 * down in advance.
 */
class PermissionRequestFlowTest {

    private fun flowFor(
        controller: ScriptedPermissionController,
        permission: Permission = CommonPermission.Microphone,
    ): PermissionRequestFlow = PermissionRequestFlow(permission, controller)

    // --- Construction -------------------------------------------------------------------------

    @Test
    fun `a fresh flow is idle and has asked the platform nothing`() {
        val controller = ScriptedPermissionController()

        val flow: PermissionRequestFlow = flowFor(controller)

        assertEquals(PermissionFlowState.Idle, flow.state.value)
        assertEquals(0, controller.checkCount)
        assertEquals(0, controller.requestCount)
    }

    @Test
    fun `a flow reports the permission it was built for`() {
        assertEquals(
            CommonPermission.Camera,
            flowFor(ScriptedPermissionController(), CommonPermission.Camera).permission,
        )
    }

    // --- start: the four statuses -------------------------------------------------------------

    @Test
    fun `start on a granted permission finishes without showing a dialog`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.Granted)
        val flow: PermissionRequestFlow = flowFor(controller)

        assertEquals(PermissionFlowState.Granted, flow.start())
        assertEquals(PermissionFlowState.Granted, flow.state.value)
        assertEquals(0, controller.requestCount)
    }

    @Test
    fun `start on a permanently denied permission asks for settings without showing a dialog`() =
        runTest {
            val controller = ScriptedPermissionController(status = PermissionStatus.PermanentlyDenied)
            val flow: PermissionRequestFlow = flowFor(controller)

            assertEquals(PermissionFlowState.AwaitingSettings, flow.start())
            assertEquals(0, controller.requestCount)
        }

    @Test
    fun `start stops for a rationale when the platform asks for one`() = runTest {
        val controller =
            ScriptedPermissionController(status = PermissionStatus.Denied(explainFirst = true))
        val flow: PermissionRequestFlow = flowFor(controller)

        assertEquals(PermissionFlowState.AwaitingRationale, flow.start())
        assertEquals(0, controller.requestCount)
    }

    @Test
    fun `start requests straight away when denied without a rationale`() = runTest {
        val controller = ScriptedPermissionController(
            status = PermissionStatus.Denied(explainFirst = false),
            requestOutcome = PermissionStatus.Granted,
        )
        val flow: PermissionRequestFlow = flowFor(controller)

        assertEquals(PermissionFlowState.Granted, flow.start())
        assertEquals(1, controller.requestCount)
    }

    @Test
    fun `start requests straight away when the permission was never asked for`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.NotDetermined)
        val flow: PermissionRequestFlow = flowFor(controller)

        flow.start()

        assertEquals(1, controller.requestCount)
    }

    // --- The request outcome ------------------------------------------------------------------

    @Test
    fun `a granted request lands on granted`() = runTest {
        val controller = ScriptedPermissionController(requestOutcome = PermissionStatus.Granted)

        assertEquals(PermissionFlowState.Granted, flowFor(controller).start())
    }

    @Test
    fun `a permanently denied request lands on the settings prompt`() = runTest {
        val controller = ScriptedPermissionController(requestOutcome = PermissionStatus.PermanentlyDenied)

        assertEquals(PermissionFlowState.AwaitingSettings, flowFor(controller).start())
    }

    @Test
    fun `a refused request lands on denied rather than looping back to the rationale`() = runTest {
        val controller = ScriptedPermissionController(
            requestOutcome = PermissionStatus.Denied(explainFirst = true),
        )

        assertEquals(PermissionFlowState.Denied, flowFor(controller).start())
    }

    @Test
    fun `a request that produced no answer at all lands on denied`() = runTest {
        val controller = ScriptedPermissionController(requestOutcome = PermissionStatus.NotDetermined)

        assertEquals(PermissionFlowState.Denied, flowFor(controller).start())
    }

    // --- Requesting is observable and exclusive -----------------------------------------------

    @Test
    fun `the requesting state is published while the dialog is up and locks the flow`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = ScriptedPermissionController(requestOutcome = PermissionStatus.Granted)
        controller.whileRequesting = { gate.await() }
        val flow: PermissionRequestFlow = flowFor(controller)

        val running = launch { flow.start() }
        runCurrent()

        assertEquals(PermissionFlowState.Requesting, flow.state.value)
        assertEquals(PermissionFlowState.Requesting, flow.start())
        assertEquals(PermissionFlowState.Requesting, flow.refresh())
        assertEquals(PermissionFlowState.Requesting, flow.reset())
        assertEquals(1, controller.requestCount)

        gate.complete(Unit)
        running.join()

        assertEquals(PermissionFlowState.Granted, flow.state.value)
    }

    @Test
    fun `a full run publishes idle then requesting then the outcome`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = ScriptedPermissionController(requestOutcome = PermissionStatus.PermanentlyDenied)
        controller.whileRequesting = { gate.await() }
        val flow: PermissionRequestFlow = flowFor(controller)
        val seen: MutableList<PermissionFlowState> = mutableListOf(flow.state.value)

        val running = launch { flow.start() }
        runCurrent()
        seen += flow.state.value
        gate.complete(Unit)
        running.join()
        seen += flow.state.value

        assertEquals(
            listOf(
                PermissionFlowState.Idle,
                PermissionFlowState.Requesting,
                PermissionFlowState.AwaitingSettings,
            ),
            seen,
        )
    }

    // --- The rationale branch -----------------------------------------------------------------

    @Test
    fun `acknowledging the rationale shows the dialog`() = runTest {
        val controller = ScriptedPermissionController(
            status = PermissionStatus.Denied(explainFirst = true),
            requestOutcome = PermissionStatus.Granted,
        )
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()

        assertEquals(PermissionFlowState.Granted, flow.rationaleAcknowledged())
        assertEquals(1, controller.requestCount)
    }

    @Test
    fun `dismissing the rationale ends the flow without a dialog`() = runTest {
        val controller =
            ScriptedPermissionController(status = PermissionStatus.Denied(explainFirst = true))
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()

        assertEquals(PermissionFlowState.Denied, flow.rationaleDismissed())
        assertEquals(0, controller.requestCount)
    }

    @Test
    fun `acknowledging a rationale nobody asked for does nothing`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.Granted)
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()

        assertEquals(PermissionFlowState.Granted, flow.rationaleAcknowledged())
        assertEquals(0, controller.requestCount)
    }

    @Test
    fun `dismissing a rationale nobody asked for does nothing`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.PermanentlyDenied)
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()

        assertEquals(PermissionFlowState.AwaitingSettings, flow.rationaleDismissed())
    }

    @Test
    fun `a double-tapped rationale button requests only once`() = runTest {
        val controller = ScriptedPermissionController(
            status = PermissionStatus.Denied(explainFirst = true),
            requestOutcome = PermissionStatus.Granted,
        )
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()

        flow.rationaleAcknowledged()
        flow.rationaleAcknowledged()

        assertEquals(1, controller.requestCount)
    }

    // --- The settings branch ------------------------------------------------------------------

    @Test
    fun `opening settings from the settings prompt leaves the flow waiting`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.PermanentlyDenied)
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()

        assertTrue(flow.openSettings())
        assertEquals(1, controller.openedSettings.size)
        assertEquals(PermissionFlowState.AwaitingSettings, flow.state.value)
    }

    @Test
    fun `opening settings outside the settings prompt does nothing at all`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.Granted)
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()

        assertFalse(flow.openSettings())
        assertEquals(0, controller.openedSettings.size)
    }

    @Test
    fun `a settings screen that cannot be opened is reported rather than assumed`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.PermanentlyDenied)
        controller.settingsAvailable = false
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()

        assertFalse(flow.openSettings())
        assertEquals(1, controller.openedSettings.size)
        assertEquals(PermissionFlowState.AwaitingSettings, flow.state.value)
    }

    @Test
    fun `declining the settings trip ends the flow`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.PermanentlyDenied)
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()

        assertEquals(PermissionFlowState.Denied, flow.settingsDeclined())
        assertEquals(0, controller.openedSettings.size)
    }

    @Test
    fun `declining a settings trip nobody offered does nothing`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.Granted)
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()

        assertEquals(PermissionFlowState.Granted, flow.settingsDeclined())
    }

    // --- refresh ------------------------------------------------------------------------------

    @Test
    fun `refresh reports a permission granted in settings`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.PermanentlyDenied)
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()
        flow.openSettings()

        controller.status = PermissionStatus.Granted

        assertEquals(PermissionFlowState.Granted, flow.refresh())
        assertEquals(0, controller.requestCount)
    }

    @Test
    fun `refresh keeps waiting for settings when the user changed nothing there`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.PermanentlyDenied)
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()
        flow.openSettings()

        assertEquals(PermissionFlowState.AwaitingSettings, flow.refresh())
    }

    @Test
    fun `refresh reports a rationale the platform now asks for`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.NotDetermined)
        val flow: PermissionRequestFlow = flowFor(controller)

        controller.status = PermissionStatus.Denied(explainFirst = true)

        assertEquals(PermissionFlowState.AwaitingRationale, flow.refresh())
    }

    @Test
    fun `refresh reports idle for a permission that can simply be asked for`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.NotDetermined)

        assertEquals(PermissionFlowState.Idle, flowFor(controller).refresh())
    }

    @Test
    fun `refresh reports idle for a denial the platform does not want explained`() = runTest {
        val controller =
            ScriptedPermissionController(status = PermissionStatus.Denied(explainFirst = false))

        assertEquals(PermissionFlowState.Idle, flowFor(controller).refresh())
    }

    @Test
    fun `refresh replaces a denial the user made in this flow with the platform's view`() =
        runTest {
            val controller = ScriptedPermissionController(status = PermissionStatus.PermanentlyDenied)
            val flow: PermissionRequestFlow = flowFor(controller)
            flow.start()
            flow.settingsDeclined()
            assertEquals(PermissionFlowState.Denied, flow.state.value)

            controller.status = PermissionStatus.NotDetermined

            assertEquals(PermissionFlowState.Idle, flow.refresh())
        }

    @Test
    fun `refresh catches a permission revoked while the app was backgrounded`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.Granted)
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()
        assertEquals(PermissionFlowState.Granted, flow.state.value)

        controller.status = PermissionStatus.NotDetermined

        assertEquals(PermissionFlowState.Idle, flow.refresh())
    }

    @Test
    fun `refresh catches a permission turned off in settings while the app was backgrounded`() =
        runTest {
            val controller = ScriptedPermissionController(status = PermissionStatus.Granted)
            val flow: PermissionRequestFlow = flowFor(controller)
            flow.start()

            controller.status = PermissionStatus.PermanentlyDenied

            assertEquals(PermissionFlowState.AwaitingSettings, flow.refresh())
        }

    // --- reset --------------------------------------------------------------------------------

    @Test
    fun `reset returns to idle from every resting state`() = runTest {
        val statuses: List<PermissionStatus> = listOf(
            PermissionStatus.Granted,
            PermissionStatus.PermanentlyDenied,
            PermissionStatus.Denied(explainFirst = true),
            PermissionStatus.NotDetermined,
        )

        statuses.forEach { status ->
            val flow: PermissionRequestFlow = flowFor(
                ScriptedPermissionController(
                    status = status,
                    requestOutcome = PermissionStatus.Denied(explainFirst = false),
                ),
            )
            flow.start()

            assertEquals(PermissionFlowState.Idle, flow.reset(), "status=$status")
        }
    }

    @Test
    fun `reset asks the platform nothing`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.Granted)
        val flow: PermissionRequestFlow = flowFor(controller)
        flow.start()
        val checksBefore: Int = controller.checkCount

        flow.reset()

        assertEquals(checksBefore, controller.checkCount)
    }

    // --- Sequences a real screen produces -----------------------------------------------------

    @Test
    fun `a first refusal becomes a rationale on the next attempt rather than a settings prompt`() =
        runTest {
            val controller = ScriptedPermissionController(
                status = PermissionStatus.NotDetermined,
                requestOutcome = PermissionStatus.Denied(explainFirst = true),
            )
            val flow: PermissionRequestFlow = flowFor(controller)

            assertEquals(PermissionFlowState.Denied, flow.start())
            assertEquals(PermissionFlowState.AwaitingRationale, flow.start())
        }

    @Test
    fun `a second refusal becomes a settings prompt rather than another rationale`() = runTest {
        val controller = ScriptedPermissionController(
            status = PermissionStatus.Denied(explainFirst = true),
            requestOutcome = PermissionStatus.PermanentlyDenied,
        )
        val flow: PermissionRequestFlow = flowFor(controller)
        assertEquals(PermissionFlowState.AwaitingRationale, flow.start())

        assertEquals(PermissionFlowState.AwaitingSettings, flow.rationaleAcknowledged())
        assertEquals(PermissionFlowState.AwaitingSettings, flow.start())
    }

    @Test
    fun `a granted permission is never asked for again however often start is called`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.Granted)
        val flow: PermissionRequestFlow = flowFor(controller)

        repeat(3) { flow.start() }

        assertEquals(0, controller.requestCount)
        assertEquals(PermissionFlowState.Granted, flow.state.value)
    }

    @Test
    fun `two flows over one controller track their own permissions independently`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.Granted)
        val microphone: PermissionRequestFlow = flowFor(controller, CommonPermission.Microphone)
        val camera: PermissionRequestFlow = flowFor(controller, CommonPermission.Camera)

        microphone.start()

        assertEquals(PermissionFlowState.Granted, microphone.state.value)
        assertEquals(PermissionFlowState.Idle, camera.state.value)
    }

    // --- 3.0: Limited, Restricted, Unavailable ------------------------------------------------

    @Test
    fun `start on a limited permission lands on limited without showing a dialog`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.Limited)

        assertEquals(PermissionFlowState.Limited, flowFor(controller).start())
        assertEquals(0, controller.requestCount)
    }

    @Test
    fun `start on a restricted or unavailable permission lands on unavailable without a dialog`() = runTest {
        listOf(PermissionStatus.Restricted, PermissionStatus.Unavailable).forEach { status ->
            val controller = ScriptedPermissionController(status = status)

            assertEquals(PermissionFlowState.Unavailable, flowFor(controller).start(), "status=$status")
            assertEquals(0, controller.requestCount, "status=$status")
        }
    }

    @Test
    fun `a request that comes back limited lands on limited`() = runTest {
        val controller = ScriptedPermissionController(requestOutcome = PermissionStatus.Limited)

        assertEquals(PermissionFlowState.Limited, flowFor(controller).start())
    }

    @Test
    fun `a request that comes back restricted or unavailable lands on unavailable`() = runTest {
        listOf(PermissionStatus.Restricted, PermissionStatus.Unavailable).forEach { outcome ->
            val controller = ScriptedPermissionController(requestOutcome = outcome)

            assertEquals(PermissionFlowState.Unavailable, flowFor(controller).start(), "outcome=$outcome")
        }
    }

    @Test
    fun `refresh reports limited and unavailable`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.Limited)
        val flow: PermissionRequestFlow = flowFor(controller)

        assertEquals(PermissionFlowState.Limited, flow.refresh())
        controller.status = PermissionStatus.Restricted
        assertEquals(PermissionFlowState.Unavailable, flow.refresh())
        controller.status = PermissionStatus.Unavailable
        assertEquals(PermissionFlowState.Unavailable, flow.refresh())
    }

    @Test
    fun `opening settings opens them for the flow's own permission`() = runTest {
        val controller = ScriptedPermissionController(status = PermissionStatus.PermanentlyDenied)
        val flow: PermissionRequestFlow = flowFor(controller, CommonPermission.Camera)
        flow.start()

        assertTrue(flow.openSettings())
        assertEquals(listOf<Permission>(CommonPermission.Camera), controller.openedSettings)
    }
}
