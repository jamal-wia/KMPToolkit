package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Sentences of docs/kmptoolkit-permission/03-guide.md and 05-platform-notes.md, each run against the real
 * controller: a dismissed dialog changes nothing, a raw permission keeps flags of its own, one shared
 * dialog for a batch and the rest one by one, a background request that waits for its foreground one,
 * and a special access that stores nothing.
 */
@RunWith(AndroidJUnit4::class)
class GuideSentencesRunTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class WriteCountingStorage(private val inner: KeyValueStorage = InMemoryKeyValueStorage()) : KeyValueStorage by inner {
        var writes: Int = 0
        override suspend fun put(key: String, value: String): StorageResult<Unit> {
            writes++
            return inner.put(key, value)
        }

        override suspend fun remove(key: String): StorageResult<Unit> {
            writes++
            return inner.remove(key)
        }
    }

    @Test
    fun `a dismissed dialog still reads NotDetermined and the next request shows the dialog again`() = runTest {
        val host = RecordingHost(answers = { false })
        val controller = testController(application, InMemoryKeyValueStorage(), host)

        assertEquals(PermissionStatus.NotDetermined, controller.request(CommonPermission.Camera))
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))
        assertEquals(PermissionStatus.NotDetermined, controller.request(CommonPermission.Camera))
        assertEquals(2, host.launchCount, "the dismissed dialog was not offered again")
    }

    @Test
    fun `a raw permission for a string a catalog entry covers keeps its own flags`() = runTest {
        val host = RecordingHost(answers = { false })
        val controller = testController(application, InMemoryKeyValueStorage(), host, shouldShowRationale = { false })
        val raw = AndroidPermission.Runtime(Manifest.permission.CAMERA)

        // The catalog's Camera refused twice for good (asked + rationale seen) ...
        val rationale = booleanArrayOf(false)
        val seen = testController(application, InMemoryKeyValueStorage(), host, shouldShowRationale = { rationale[0] })
        rationale[0] = true
        seen.check(CommonPermission.Camera)
        rationale[0] = false
        seen.request(CommonPermission.Camera)
        assertEquals(PermissionStatus.PermanentlyDenied, seen.check(CommonPermission.Camera))
        // ... is not what the raw entry for the same string reads on the same storage.
        assertEquals(PermissionStatus.NotDetermined, seen.check(raw), "the raw entry shares the catalog entry's flags")
        val before: Int = host.launchCount
        controller.request(raw)
        assertEquals(before + 1, host.launchCount, "the raw entry showed no dialog of its own")
    }

    /** A host whose "Allow" really grants, the way the platform does, so a later request sees the grant. */
    private inner class GrantingHost : RecordingHost(answers = { true }) {
        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            shadowOf(application).grantPermissions(*androidPermissions.toTypedArray())
            return super.launch(androidPermissions, onResult)
        }
    }

    @Test
    fun `a batch shows one shared dialog and asks background location afterwards one by one in order`() = runTest {
        val host = GrantingHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host)
        val order: List<Permission> = listOf(
            CommonPermission.Camera, CommonPermission.Location, CommonPermission.LocationBackground, CommonPermission.Microphone,
        )

        val result: Map<Permission, PermissionStatus> = controller.request(order)

        assertEquals(order, result.keys.toList())
        assertTrue(result.values.all { it == PermissionStatus.Granted }, "$result")
        assertEquals(2, host.launchCount, "${host.groups}")
        assertEquals(
            setOf(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            host.groups[0].toSet(),
        )
        assertEquals(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), host.groups[1])
    }

    @Test
    fun `background location is not asked when the foreground one in the same batch was refused`() = runTest {
        val host = RecordingHost(answers = { false })
        val controller = testController(application, InMemoryKeyValueStorage(), host)

        val result: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.Location, CommonPermission.LocationBackground))

        assertEquals(1, host.launchCount, "${host.groups}")
        assertTrue(result.getValue(CommonPermission.LocationBackground) is PermissionStatus.NotDetermined, "$result")
    }

    @Test
    fun `a special access request stores nothing and an unchanged grant reads Denied with a rationale`() = runTest(UnconfinedTestDispatcher()) {
        val storage = WriteCountingStorage()
        val resumes = ResumeListeners()
        val opened: MutableList<SystemScreenRequest> = mutableListOf()
        val controller = testController(
            context = application,
            storage = storage,
            sdkInt = Build.VERSION.SDK_INT,
            resumeListeners = resumes,
            launchScreen = { request -> opened += request; true },
        )
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)

        val call = async { controller.request(AndroidPermission.InstallPackages) }
        assertEquals(1, opened.size, "the Settings screen was not opened")
        resumes.fire()

        assertEquals(PermissionStatus.Denied(explainFirst = true), call.await())
        assertEquals(0, storage.writes, "a special access wrote a flag")
    }
}
