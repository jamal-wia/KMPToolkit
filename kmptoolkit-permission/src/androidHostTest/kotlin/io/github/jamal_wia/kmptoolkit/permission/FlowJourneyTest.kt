package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * `PermissionRequestFlow` over the real Android controller, walking the documented rows end to end:
 * refusal, rationale, the second refusal, the trip to settings and back, and the paths that decline.
 */
@RunWith(AndroidJUnit4::class)
class FlowJourneyTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val camera: String = Manifest.permission.CAMERA
    private var rationale: Boolean? = false
    private val opened: MutableList<SystemScreenRequest> = mutableListOf()
    private var screenOpens: Boolean = true

    /** A host that refuses, and (like the platform) starts asking for a rationale after the first refusal. */
    private val host: RecordingHost = object : RecordingHost(answers = { false }) {
        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            val first: Boolean = launchCount == 0
            val launched: Boolean = super.launch(androidPermissions, onResult)
            if (first) rationale = true else rationale = false
            return launched
        }
    }

    private val controller: AndroidPermissionController = testController(
        context = application,
        storage = InMemoryKeyValueStorage(),
        host = host,
        sdkInt = Build.VERSION_CODES.TIRAMISU,
        shouldShowRationale = { rationale },
        launchScreen = { request -> opened += request; screenOpens },
    )

    @Test
    fun `refusal, rationale, second refusal, settings and the grant made there`() = runTest {
        shadowOf(application).denyPermissions(camera)
        val flow = PermissionRequestFlow(CommonPermission.Camera, controller)

        assertEquals(PermissionFlowState.Idle, flow.refresh())
        assertEquals(PermissionFlowState.Denied, flow.start(), "the first refusal")
        assertEquals(PermissionFlowState.AwaitingRationale, flow.start(), "the OS now wants a rationale")
        assertEquals(PermissionFlowState.Denied, flow.rationaleDismissed())
        assertEquals(PermissionFlowState.AwaitingRationale, flow.refresh(), "refresh clears Denied")
        assertEquals(PermissionFlowState.AwaitingSettings, flow.rationaleAcknowledged(), "the second refusal is for good")
        assertEquals(1 + 1, host.launchCount)

        assertTrue(flow.openSettings())
        assertEquals(PermissionFlowState.AwaitingSettings, flow.state.value)
        assertEquals(CommonPermission.Camera, (opened.single().kind as PermissionScreen).permission)
        assertEquals(PermissionFlowState.Denied, flow.settingsDeclined())
        assertFalse(flow.openSettings(), "openSettings outside AwaitingSettings opens nothing")
        assertEquals(1, opened.size)

        shadowOf(application).grantPermissions(camera)
        assertEquals(PermissionFlowState.Granted, flow.refresh())
        assertEquals(PermissionFlowState.Granted, flow.start())
        assertEquals(2, host.launchCount, "a granted permission shows nothing")
    }

    @Test
    fun `a request the host cannot show ends the flow as denied and asks again later`() = runTest {
        shadowOf(application).denyPermissions(camera)
        val silent = RecordingHost(answers = { false }, canLaunch = false)
        val flow = PermissionRequestFlow(
            CommonPermission.Camera,
            testController(context = application, storage = InMemoryKeyValueStorage(), host = silent, sdkInt = Build.VERSION_CODES.TIRAMISU),
        )

        assertEquals(PermissionFlowState.Denied, flow.start())
        assertEquals(PermissionFlowState.Idle, flow.refresh(), "nothing was asked, nothing was refused")
        assertEquals(PermissionFlowState.Denied, flow.start())
        assertEquals(2, silent.launchCount)
    }

    @Test
    fun `every method outside its state returns the unchanged state`() = runTest {
        shadowOf(application).denyPermissions(camera)
        val flow = PermissionRequestFlow(CommonPermission.Camera, controller)

        assertEquals(PermissionFlowState.Idle, flow.rationaleAcknowledged())
        assertEquals(PermissionFlowState.Idle, flow.rationaleDismissed())
        assertEquals(PermissionFlowState.Idle, flow.settingsDeclined())
        assertFalse(flow.openSettings())
        assertEquals(PermissionFlowState.Idle, flow.reset())
        assertEquals(0, host.launchCount)
        assertEquals(0, opened.size)
    }

    @Test
    fun `a special access goes through the rationale step and the screen, never the settings step`() = runTest {
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
        val flow = PermissionRequestFlow(AndroidPermission.InstallPackages, controller)

        assertEquals(PermissionFlowState.AwaitingRationale, flow.start())
        assertFalse(flow.openSettings(), "never AwaitingSettings for a special access")
    }
}
