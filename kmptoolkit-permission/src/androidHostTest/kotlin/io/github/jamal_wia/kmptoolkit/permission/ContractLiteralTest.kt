package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The documented guarantees of `PermissionController.request(Collection)`, `check`, `openSettings` and
 * `observe`, read literally and fed edge inputs: nothing, duplicates, an unknown type, blank names.
 */
@RunWith(AndroidJUnit4::class)
class ContractLiteralTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val storage: KeyValueStorage = InMemoryKeyValueStorage()
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO

    private fun controller(host: RecordingHost, sdkInt: Int = Build.VERSION_CODES.TIRAMISU): AndroidPermissionController =
        testController(context = application, storage = storage, host = host, sdkInt = sdkInt)

    private fun deny(vararg permissions: String) = shadowOf(application).denyPermissions(*permissions)

    @Test
    fun `an empty batch shows nothing and returns an empty map`() = runTest {
        val host = RecordingHost(answers = { true })

        val result: Map<Permission, PermissionStatus> = controller(host).request(emptyList())

        assertEquals(emptyMap(), result)
        assertEquals(0, host.launchCount)
        assertEquals(0, host.intents.size)
    }

    @Test
    fun `a permission listed several times is requested once and the map keeps the first-seen order`() = runTest {
        deny(camera, microphone)
        val host = RecordingHost(answers = { false })

        val result: Map<Permission, PermissionStatus> = controller(host).request(
            listOf(CommonPermission.Microphone, CommonPermission.Camera, CommonPermission.Microphone, CommonPermission.Camera),
        )

        assertEquals(1, host.launchCount, "one shared dialog")
        assertEquals(listOf<Permission>(CommonPermission.Microphone, CommonPermission.Camera), result.keys.toList())
        assertEquals(setOf(camera, microphone), host.groups.single().toSet())
        assertEquals(2, host.groups.single().size, "no Android string asked twice")
    }

    @Test
    fun `a type the controller does not know is unavailable in a batch and never reaches the host`() = runTest {
        deny(camera)
        val host = RecordingHost(answers = { true })
        val controller: AndroidPermissionController = controller(host)

        val result: Map<Permission, PermissionStatus> = controller.request(listOf(ForeignPermission, CommonPermission.Camera))

        assertEquals(PermissionStatus.Unavailable, result.getValue(ForeignPermission))
        assertEquals(listOf(listOf(camera)), host.groups)
        assertEquals(PermissionStatus.Unavailable, controller.request(ForeignPermission))
        assertEquals(PermissionStatus.Unavailable, controller.check(ForeignPermission))
        assertFalse(controller.openSettings(ForeignPermission))
    }

    @Test
    fun `a batch of unknown types only shows nothing`() = runTest {
        val host = RecordingHost(answers = { true })

        val result: Map<Permission, PermissionStatus> = controller(host).request(listOf(ForeignPermission, ForeignPermission))

        assertEquals(mapOf<Permission, PermissionStatus>(ForeignPermission to PermissionStatus.Unavailable), result)
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `a batch that holds one permission already granted shows only the dialog for the rest`() = runTest {
        shadowOf(application).grantPermissions(camera)
        deny(microphone)
        val host = RecordingHost(answers = { false })

        val result: Map<Permission, PermissionStatus> = controller(host).request(listOf(CommonPermission.Camera, CommonPermission.Microphone))

        assertEquals(PermissionStatus.Granted, result.getValue(CommonPermission.Camera))
        assertEquals(listOf(listOf(microphone)), host.groups)
    }

    @Test
    fun `a request for a permission refused for good shows nothing and returns it`() = runTest {
        deny(camera)
        var rationale: Boolean? = false
        val host = RecordingHost(answers = { false })
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = storage,
            host = host,
            shouldShowRationale = { rationale },
        )
        controller.request(CommonPermission.Camera)
        rationale = true
        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.check(CommonPermission.Camera))
        rationale = false
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Camera))
        val launches: Int = host.launchCount

        assertEquals(PermissionStatus.PermanentlyDenied, controller.request(CommonPermission.Camera))
        val batch: Map<Permission, PermissionStatus> = controller.request(listOf(CommonPermission.Camera))

        assertEquals(launches, host.launchCount, "no UI for a permanently denied permission")
        assertEquals(PermissionStatus.PermanentlyDenied, batch.getValue(CommonPermission.Camera))
    }

    @Test
    fun `a blank name is rejected for a raw permission, a role and the key prefix`() {
        assertFailsWith<IllegalArgumentException> { AndroidPermission.Runtime("") }
        assertFailsWith<IllegalArgumentException> { AndroidPermission.Runtime("   ") }
        assertFailsWith<IllegalArgumentException> { AndroidPermission.Role("") }
        assertFailsWith<IllegalArgumentException> { AndroidPermissionConfig(keyPrefix = " ") }
    }

    @Test
    fun `observe of an entry that stands for another sees a request for either one`() = runTest(UnconfinedTestDispatcher()) {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val host = object : RecordingHost(answers = { true }) {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                shadowOf(application).grantPermissions(*androidPermissions.toTypedArray())
                return super.launch(androidPermissions, onResult)
            }
        }
        val controller: AndroidPermissionController = controller(host, sdkInt = Build.VERSION_CODES.R)
        val seen: MutableList<PermissionStatus> = mutableListOf()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.observe(CommonPermission.BluetoothScan).collect { seen += it }
        }

        controller.request(CommonPermission.Location)

        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
        collection.cancel()
    }

    @Test
    fun `observe emits only a different status after a request that changed nothing`() = runTest(UnconfinedTestDispatcher()) {
        deny(camera)
        val host = RecordingHost(answers = { false })
        val controller: AndroidPermissionController = controller(host)
        val seen: MutableList<PermissionStatus> = mutableListOf()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.observe(CommonPermission.Camera).collect { seen += it }
        }

        controller.request(CommonPermission.Camera) // refused: asked, and the platform gives no rationale
        controller.request(CommonPermission.Camera)

        assertTrue(seen.zipWithNext().all { (a, b) -> a != b }, "observe repeated a status: $seen")
        collection.cancel()
    }
}
