package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.createKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The Kotlin samples of docs/kmptoolkit-permission (01-overview, 02-getting-started, 03-guide) typed in as
 * the docs print them, so a sample that no longer compiles against the public API fails here, and run
 * against the real controller, so a sample that compiles but behaves differently from its comment fails
 * too. The helper names the samples leave to the reader (startRecording, settingsWindow) are fakes below.
 */
@RunWith(AndroidJUnit4::class)
class DocSamplesCompileAndRunTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()

    @AfterTest
    fun tearDown() {
        accesses.forEach { it.release() }
    }

    // ---- 01-overview: RecorderPresenter ------------------------------------------------------------------

    private class RecorderPresenter(controller: PermissionController) {
        var recording: Boolean = false
        var explained: Boolean = false
        var settingsPrompt: Boolean = false

        private val microphone = PermissionRequestFlow(CommonPermission.Microphone, controller)

        suspend fun onRecordTapped() {
            when (microphone.start()) {
                PermissionFlowState.Granted -> startRecording()
                PermissionFlowState.AwaitingRationale -> showYourOwnExplanation()
                PermissionFlowState.AwaitingSettings -> showYourOwnSettingsPrompt()
                else -> Unit
            }
        }

        private fun startRecording() { recording = true }
        private fun showYourOwnExplanation() { explained = true }
        private fun showYourOwnSettingsPrompt() { settingsPrompt = true }
    }

    @Test
    fun `the overview presenter reacts to granted rationale and settings states of the real controller`() = runTest {
        val granted = RecorderPresenter(testController(application, InMemoryKeyValueStorage()).also {
            shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        })
        granted.onRecordTapped()
        assertTrue(granted.recording)
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)

        // First refusal with a rationale: the presenter shows its own explanation, no second dialog.
        val rationale = booleanArrayOf(true)
        val host = RecordingHost(answers = { false })
        val explained = RecorderPresenter(
            testController(application, InMemoryKeyValueStorage(), host, shouldShowRationale = { rationale[0] }),
        )
        explained.onRecordTapped()
        assertTrue(explained.explained, "the rationale branch was not taken")
        assertEquals(0, host.launchCount, "a rationale Android already asked for shows its explanation before any dialog")

        // Refused for good: the settings prompt.
        val storage = InMemoryKeyValueStorage()
        val controller = testController(application, storage, host, shouldShowRationale = { rationale[0] })
        controller.request(CommonPermission.Microphone)
        rationale[0] = false
        controller.request(CommonPermission.Microphone)
        val permanent = RecorderPresenter(controller)
        permanent.onRecordTapped()
        assertTrue(permanent.settingsPrompt)
        assertFalse(permanent.recording)
    }

    // ---- 02-getting-started ------------------------------------------------------------------------------

    @Test
    fun `the getting started wiring with the real key value storage builds a controller that answers`() = runTest {
        val permissions: PermissionController = createPermissionController(
            context = application,
            storage = createKeyValueStorage(application),
        )
        // Nothing declared in the manifest of this test application for the microphone: Unavailable, not a crash.
        application.declareInManifest()
        assertEquals(PermissionStatus.Unavailable, permissions.check(CommonPermission.Microphone))
    }

    @Test
    fun `the observe sample's state flow follows a grant made in settings on the next resume`() = runTest(UnconfinedTestDispatcher()) {
        val resumes = ResumeListeners()
        val controller = testController(application, InMemoryKeyValueStorage(), resumeListeners = resumes)
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val cameraUsable: StateFlow<Boolean> = controller.observe(CommonPermission.Camera)
            .map { status -> status.isUsable }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)
        // The sample's own collector keeps it hot.
        val seen: MutableList<Boolean> = mutableListOf()
        val collector = scope.launchCollect(cameraUsable, seen)
        assertEquals(false, cameraUsable.value)

        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        resumes.fire()

        assertEquals(true, cameraUsable.value)
        assertEquals(listOf(false, true), seen)
        collector.cancel()
        testScheduler.advanceTimeBy(5_001)
        testScheduler.runCurrent()
        assertEquals(0, resumes.count, "the resume listener outlived the collection")
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun CoroutineScope.launchCollect(flow: StateFlow<Boolean>, into: MutableList<Boolean>) =
        this.launch { flow.collect { into += it } }

    @Test
    fun `the notifications one liner and the batch sample return what their types say`() = runTest {
        val controller = testController(application, InMemoryKeyValueStorage(), RecordingHost(answers = { true }))

        val enabled: Boolean = controller.check(CommonPermission.Notifications).isUsable
        val statuses: Map<Permission, PermissionStatus> = controller.request(
            listOf(CommonPermission.Camera, CommonPermission.Microphone),
        )

        assertFalse(enabled, "NotDetermined is not usable")
        assertEquals(setOf<Permission>(CommonPermission.Camera, CommonPermission.Microphone), statuses.keys)
    }

    @Test
    fun `the exact alarm sample falls back to inexact when not granted and the commonMain shape accepts null`() = runTest {
        class ExactReminders(private val controller: PermissionController, private val exactAlarm: Permission?) {
            suspend fun canBeExact(): Boolean = exactAlarm == null || controller.check(exactAlarm).isGranted
        }
        val controller = testController(application, InMemoryKeyValueStorage())

        assertTrue(ExactReminders(controller, exactAlarm = null).canBeExact())
        // SCHEDULE_EXACT_ALARM is not allowed in this fixture application: the sample must read false, not throw.
        val android: Boolean = ExactReminders(controller, AndroidPermission.ExactAlarm).canBeExact()
        assertEquals(controller.check(AndroidPermission.ExactAlarm).isGranted, android)
    }

    @Test
    fun `the home role sample runs to completion on a device that does not offer the role`() = runTest {
        val controller = testController(application, InMemoryKeyValueStorage(), RecordingHost(resultCode = android.app.Activity.RESULT_CANCELED))
        var shown = false

        suspend fun onBecomeLauncherTapped() {
            when (controller.request(AndroidPermission.Role.Home)) {
                PermissionStatus.Granted -> shown = true
                PermissionStatus.PermanentlyDenied -> controller.openSettings(AndroidPermission.Role.Home)
                else -> Unit
            }
        }
        onBecomeLauncherTapped()

        assertFalse(shown)
        assertEquals(AndroidPermission.Role("android.app.role.HOME"), AndroidPermission.Role.Home)
    }

    @Test
    fun `the raw runtime sample and the bluetooth batch sample run`() = runTest {
        val host = RecordingHost(answers = { true })
        val controller = testController(
            application, InMemoryKeyValueStorage(), host, protectionOf = { Protection.Dangerous },
            disavowsLocationForScan = { false },
        )

        val phoneState = AndroidPermission.Runtime(Manifest.permission.READ_PHONE_STATE)
        controller.request(phoneState)
        assertEquals(listOf(listOf(Manifest.permission.READ_PHONE_STATE)), host.groups)

        suspend fun canOpenPicker(): Boolean =
            controller.request(listOf(CommonPermission.BluetoothScan, CommonPermission.BluetoothConnect))
                .values.all { it.isGranted }
        // On API 33 (the fixture default) the scan permission is not declared with neverForLocation: the sample
        // reports false instead of throwing, and shows nothing for the scan entry.
        assertFalse(canOpenPicker())
    }

    // ---- 03-guide: the kiosk and logging launchers -------------------------------------------------------

    private class AllowlistWindow {
        var opened: Int = 0
        var closed: Int = 0
        fun open() { opened++ }
        fun close() { closed++ }
    }

    @Test
    fun `the kiosk launcher sample opens its window for the two named screens and passes the others through`() = runTest {
        val settingsWindow = AllowlistWindow()
        val kiosk = SystemScreenLauncher { request ->
            when (request.kind) {
                PermissionScreen(AndroidPermission.ExactAlarm), PermissionScreen(CommonPermission.Notifications) -> {
                    settingsWindow.open()
                    val launched: Boolean = try {
                        SystemScreenLauncher.SeparateTask.launch(request)
                    } catch (cause: Exception) {
                        settingsWindow.close()
                        throw cause
                    }
                    if (!launched) settingsWindow.close()
                    launched
                }
                else -> SystemScreenLauncher.SeparateTask.launch(request)
            }
        }
        val access: ActivityAccess = createActivityAccess(application).also { accesses += it }
        val controller: PermissionController = createPermissionController(
            application, InMemoryKeyValueStorage(), access, systemScreenLauncher = kiosk,
        )

        assertTrue(controller.openSettings(CommonPermission.Notifications))
        assertEquals(1, settingsWindow.opened)
        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0, "the screen is not in a task of its own")
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0)

        assertTrue(controller.openSettings(CommonPermission.Camera))
        assertEquals(1, settingsWindow.opened, "a screen the sample does not name opened the window")
        assertNotNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `the logging launcher sample logs the kind and delegates`() = runTest {
        val access: ActivityAccess = createActivityAccess(application).also { accesses += it }
        val logged: MutableList<Any> = mutableListOf()
        val controller: PermissionController = createPermissionController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            activityAccess = access,
            systemScreenLauncher = SystemScreenLauncher { request ->
                logged += request.kind
                SystemScreenLauncher.callerTask(access).launch(request)
            },
        )

        assertTrue(controller.openSettings(CommonPermission.Camera))

        assertEquals(listOf<Any>(PermissionScreen(CommonPermission.Camera)), logged)
        val request: SystemScreenRequest = SystemScreenRequest(listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)), application, PermissionScreen(CommonPermission.Camera))
        assertEquals(PermissionScreen(CommonPermission.Camera), request.kind)
    }

}
