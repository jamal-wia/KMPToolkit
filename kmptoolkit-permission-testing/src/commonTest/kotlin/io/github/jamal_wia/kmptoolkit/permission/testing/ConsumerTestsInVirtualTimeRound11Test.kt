package io.github.jamal_wia.kmptoolkit.permission.testing

import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionFlowState
import io.github.jamal_wia.kmptoolkit.permission.PermissionRequestFlow
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.isGranted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 06-testing, "The scenarios worth covering": the eight sample tests of the document typed in as printed, over the
 * presenters a consumer writes around `PermissionRequestFlow` (the document leaves the presenters to the reader),
 * and then the consumer tests the document implies but does not print - virtual time, a request held past a
 * timeout, a retry loop with `delay`, an observer on `backgroundScope` - all on the one `runTest` of a real consumer.
 */
class ConsumerTestsInVirtualTimeRound11Test {

    private class RecorderPresenter(controller: PermissionController, scope: CoroutineScope) {
        private val microphone = PermissionRequestFlow(CommonPermission.Microphone, controller)
        private val controller: PermissionController = controller

        var isRecording: Boolean = false
        var isSettingsPromptVisible: Boolean = false
        val permissionState: StateFlow<PermissionFlowState> = microphone.state

        /** Observes the microphone, as the sample's comment says. */
        val isRecordButtonEnabled: StateFlow<Boolean> =
            controller.observe(CommonPermission.Microphone).map { it.isGranted }.stateIn(scope, SharingStarted.Eagerly, false)

        suspend fun onRecordTapped() {
            when (microphone.start()) {
                PermissionFlowState.Granted -> isRecording = true
                PermissionFlowState.AwaitingRationale -> Unit
                PermissionFlowState.AwaitingSettings -> isSettingsPromptVisible = true
                else -> Unit
            }
        }

        fun onOpenSettingsTapped() {
            microphone.openSettings()
        }
    }

    private class NavigationPresenter(private val controller: PermissionController) {
        suspend fun onStartTapped(): Boolean = controller.check(CommonPermission.Location).let { it.isGranted || it == PermissionStatus.Limited }
    }

    private class CallPresenter(private val controller: PermissionController) {
        suspend fun onCallTapped(): Boolean {
            val answers: Map<Permission, PermissionStatus> = controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone))
            return answers.values.all { it.isGranted }
        }
    }

    // ---- the document's eight samples ----------------------------------------------------------------------

    @Test
    fun `tapping record starts recording when the microphone is granted`() = runTest {
        val controller = FakePermissionController(defaultStatus = PermissionStatus.Granted)
        val presenter = RecorderPresenter(controller, backgroundScope)

        presenter.onRecordTapped()

        assertTrue(presenter.isRecording)
    }

    @Test
    fun `a first refusal asks the screen for a rationale`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Microphone, PermissionStatus.Denied(explainFirst = true))
        val presenter = RecorderPresenter(controller, backgroundScope)

        presenter.onRecordTapped()

        assertEquals(PermissionFlowState.AwaitingRationale, presenter.permissionState.value)
        assertTrue(controller.requests.isEmpty(), "no dialog before the user has seen the rationale")
    }

    @Test
    fun `a permanently denied microphone offers system settings instead of another prompt`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Microphone, PermissionStatus.PermanentlyDenied)
        val presenter = RecorderPresenter(controller, backgroundScope)

        presenter.onRecordTapped()
        presenter.onOpenSettingsTapped()

        assertEquals(listOf<Permission>(CommonPermission.Microphone), controller.openedSettings)
        assertTrue(controller.requests.isEmpty())
    }

    @Test
    fun `approximate location still starts navigation`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Location, PermissionStatus.Limited)

        assertTrue(NavigationPresenter(controller).onStartTapped())
    }

    @Test
    fun `an unavailable permission hides the feature instead of offering settings`() = runTest {
        val controller = FakePermissionController(defaultStatus = PermissionStatus.Unavailable)
        val presenter = RecorderPresenter(controller, backgroundScope)

        presenter.onRecordTapped()

        assertFalse(presenter.isSettingsPromptVisible)
        assertTrue(controller.openedSettings.isEmpty())
    }

    @Test
    fun `a permission revoked while the app was away disables the feature`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakePermissionController(defaultStatus = PermissionStatus.Granted)
        val presenter = RecorderPresenter(controller, backgroundScope) // observes the microphone
        assertTrue(presenter.isRecordButtonEnabled.value, "the granted microphone enables the button before the revocation")

        controller.setStatus(CommonPermission.Microphone, PermissionStatus.NotDetermined)

        assertFalse(presenter.isRecordButtonEnabled.value)
    }

    @Test
    fun `the settings trip sets Granted after openSettings and the screen enables itself`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Microphone, PermissionStatus.PermanentlyDenied)
        val presenter = RecorderPresenter(controller, backgroundScope)
        presenter.onRecordTapped()
        presenter.onOpenSettingsTapped()
        assertFalse(presenter.isRecordButtonEnabled.value)

        controller.setStatus(CommonPermission.Microphone, PermissionStatus.Granted)

        assertTrue(presenter.isRecordButtonEnabled.value)
    }

    @Test
    fun `the video call asks for camera and microphone together`() = runTest {
        val controller = FakePermissionController()
        controller.scriptRequest(CommonPermission.Camera, PermissionStatus.Granted)
        controller.scriptRequest(CommonPermission.Microphone, PermissionStatus.PermanentlyDenied)

        val started: Boolean = CallPresenter(controller).onCallTapped()

        assertFalse(started)
        assertEquals(listOf(listOf<Permission>(CommonPermission.Camera, CommonPermission.Microphone)), controller.batchRequests)
    }

    // ---- virtual time ---------------------------------------------------------------------------------------

    @Test
    fun `a request held past a consumer timeout in virtual time still keeps the answer`() = runTest {
        val controller = FakePermissionController()
        controller.scriptRequest(CommonPermission.Microphone, PermissionStatus.Granted)
        controller.holdRequests = true

        val outcome = async { withTimeoutOrNull(5.seconds) { controller.request(CommonPermission.Microphone) } }
        advanceTimeBy(5.seconds + 1.milliseconds)
        runCurrent()
        assertNull(outcome.await(), "the held request must time out in virtual time")
        controller.holdRequests = false

        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.Microphone))
    }

    @Test
    fun `a polling consumer sees a grant made in settings after the virtual seconds it waited`() = runTest {
        val controller = FakePermissionController()
        controller.setStatus(CommonPermission.Camera, PermissionStatus.PermanentlyDenied)
        var polls = 0
        val done = MutableStateFlow(false)
        launch {
            while (!controller.check(CommonPermission.Camera).isGranted) {
                polls++
                delay(1.seconds)
            }
            done.value = true
        }

        advanceTimeBy(3.seconds)
        runCurrent()
        assertFalse(done.value)
        controller.setStatus(CommonPermission.Camera, PermissionStatus.Granted)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertTrue(done.value)
        assertEquals(4, polls)
    }

    @Test
    fun `an observer on the background scope under a standard dispatcher gets each different status once`() = runTest {
        val controller = FakePermissionController()
        val seen: MutableList<PermissionStatus> = mutableListOf()
        backgroundScope.launch { controller.observe(CommonPermission.Camera).collect { seen += it } }
        runCurrent()

        controller.setStatus(CommonPermission.Camera, PermissionStatus.Granted)
        runCurrent()
        controller.setStatus(CommonPermission.Camera, PermissionStatus.Granted)
        runCurrent()
        // A flip and flip back before the collector runs is not a change the consumer can see.
        controller.setStatus(CommonPermission.Camera, PermissionStatus.Denied(explainFirst = false))
        controller.setStatus(CommonPermission.Camera, PermissionStatus.Granted)
        runCurrent()
        controller.setStatus(CommonPermission.Camera, PermissionStatus.PermanentlyDenied)
        runCurrent()

        assertEquals(
            listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted, PermissionStatus.PermanentlyDenied),
            seen,
        )
    }

    @Test
    fun `a thousand held requests resolve in the order they were made and the cancelled queued half changes nothing`() = runTest {
        val controller = FakePermissionController()
        val permissions: List<Permission> = listOf(CommonPermission.Camera, CommonPermission.Microphone)
        controller.scriptRequest(CommonPermission.Camera, PermissionStatus.Granted)
        controller.scriptRequest(CommonPermission.Microphone, PermissionStatus.PermanentlyDenied)
        controller.holdRequests = true
        val finished: MutableList<Int> = mutableListOf()
        val jobs = List(1000) { index ->
            launch {
                controller.request(permissions[index % 2])
                finished += index
            }
        }
        runCurrent()
        // Cancel every request after the first from the second half; the first one's dialog is up.
        jobs.drop(500).forEach { it.cancel() }
        runCurrent()

        controller.holdRequests = false
        runCurrent()

        assertEquals((0 until 500).toList(), finished.take(finished.size), "the requests of the live half resolve in the order they were made")
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.Camera))
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Microphone))
        assertEquals(1000, controller.requests.size)
    }
}
