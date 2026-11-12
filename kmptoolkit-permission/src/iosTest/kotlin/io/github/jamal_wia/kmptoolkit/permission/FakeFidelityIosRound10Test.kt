@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.permission.FidelityScenarios.runAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusRestricted

/** The real iOS controller, through its port, as a [FidelityWorld]. */
class IosWorld(private val scope: TestScope) : FidelityWorld {

    val port: FakeIosAuthorizationPort = FakeIosAuthorizationPort()
    private var hasKey: Boolean = true
    override val controller: PermissionController =
        IosPermissionController(NoopLogger, IosPermissionOptions(), { hasKey }, port)

    // iOS has no refusal that may be explained for the camera: a refusal is final.
    override val supported: Set<PreState> = PreState.entries.toSet() - PreState.DeniedWithRationale

    override val dialogsShown: Int get() = port.count("requestCameraAccess") + port.count("requestRecordPermission")

    override suspend fun setUp(permission: Permission, state: PreState) {
        when (state) {
            PreState.Granted -> grantInSettings(permission)
            PreState.PermanentlyDenied -> port.camera = AVAuthorizationStatusDenied
            PreState.Restricted -> port.camera = AVAuthorizationStatusRestricted
            PreState.Unavailable -> hasKey = false
            PreState.DeniedWithRationale -> error("not on iOS")
        }
    }

    private val names: Map<Permission, String> = mapOf(
        CommonPermission.Camera to "requestCameraAccess",
        CommonPermission.Microphone to "requestRecordPermission",
    )

    private fun grantOf(p: Permission): Boolean = if (p == CommonPermission.Camera) port.cameraGrant else port.microphoneGrant

    private fun applyAnswer(p: Permission) {
        val grant: Boolean = grantOf(p)
        if (p == CommonPermission.Camera) {
            port.camera = if (grant) AVAuthorizationStatusAuthorized else AVAuthorizationStatusDenied
        } else {
            port.recordState = if (grant) AVAudioSessionRecordPermissionGranted else AVAudioSessionRecordPermissionDenied
        }
    }

    init {
        names.forEach { (p, name) -> port.hooks[name] = { applyAnswer(p) } }
    }

    override fun willAnswer(permission: Permission, grant: Boolean) {
        if (permission == CommonPermission.Camera) port.cameraGrant = grant else port.microphoneGrant = grant
    }

    override fun hold(on: Boolean) {
        if (on) port.holds += names.values else port.holds.clear()
    }

    override fun answerHeld() {
        port.holds.clear()
        val held: Map<String, (Boolean) -> Unit> = port.heldCompletions.toMap()
        port.heldCompletions.clear()
        held.forEach { (name, completion) ->
            val p: Permission = names.entries.first { it.value == name }.key
            applyAnswer(p)
            completion(grantOf(p))
        }
    }

    override fun settingsOpenable(can: Boolean) {
        port.canOpenUrl = can
    }

    override val settingsOpened: Int get() = port.openedUrls.size

    override fun grantInSettings(permission: Permission) {
        if (permission == CommonPermission.Camera) {
            port.camera = AVAuthorizationStatusAuthorized
        } else {
            port.recordState = AVAudioSessionRecordPermissionGranted
        }
    }

    override fun returnToApp() {
        port.activate()
    }

    override suspend fun settle() {
        scope.testScheduler.runCurrent()
    }
}

/**
 * Fake fidelity, iOS half: the same scripted scenarios as the Android half, against the iOS controller over a
 * scripted port and against the fake. The scenario that the fake does not follow (a cancelled caller whose dialog
 * is answered later) lives in suspects/r10-S1.kt.
 */
class FakeFidelityIosRound10Test {

    @Test
    fun `every scenario reads the same on the iOS controller and on the fake`() = runTest {
        val scope: TestScope = this
        val fake: Map<String, List<String>> = runAll { FakeWorld { scope.testScheduler.runCurrent() } }
        val real: Map<String, List<String>> = runAll { IosWorld(scope) }
        for ((name, lines) in fake) {
            if (name == "cancelThenAnswered") continue
            assertEquals(lines, real[name], "scenario $name: fake on the left, iOS on the right")
        }
    }

    @Test
    fun `a request from a state that shows no system UI reads the same on the iOS controller and on the fake`() = runTest {
        val scope: TestScope = this
        for (state in PreState.entries - PreState.DeniedWithRationale) {
            val fake: List<String> = noUiRequest(FakeWorld { scope.testScheduler.runCurrent() }, state)
            val real: List<String> = noUiRequest(IosWorld(scope), state)
            assertEquals(fake.filterNot { it.startsWith("dialogsShown") }, real.filterNot { it.startsWith("dialogsShown") }, "$state")
            assertEquals("dialogsShown:0", real.last { it.startsWith("dialogsShown") }, "$state: a prompt for a state that shows none")
        }
    }

    @Test
    fun `the iOS controller keeps the answer of a prompt whose caller was cancelled and the next check sees it`() = runTest {
        val scope: TestScope = this
        val real: Map<String, List<String>> = runAll { IosWorld(scope) }
        assertEquals(listOf("cancelled:true", "beforeAnswer:notGranted", "afterAnswer:granted"), real.getValue("cancelThenAnswered"))
    }
}
