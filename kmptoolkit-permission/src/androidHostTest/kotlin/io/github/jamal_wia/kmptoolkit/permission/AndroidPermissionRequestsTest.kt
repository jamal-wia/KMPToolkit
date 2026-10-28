package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The parts of [PermissionController]'s contract that are about requests as a whole rather than one
 * entry: serialization, the batch request, cancellation, flags stored by 2.x, and a type the controller
 * does not know.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPermissionRequestsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val storage: KeyValueStorage = InMemoryKeyValueStorage()
    private val resumes: ResumeListeners = ResumeListeners()

    private fun grant(vararg permissions: String) = shadowOf(application).grantPermissions(*permissions)

    private fun deny(vararg permissions: String) = shadowOf(application).denyPermissions(*permissions)

    /**
     * A host that holds every dialog open until the test answers it — the system dialog on screen —
     * and records how many were open at once.
     */
    private class HeldHost : RecordingHost() {
        val pending: MutableList<Pair<List<String>, (Map<String, Boolean>) -> Unit>> = mutableListOf()
        var mostOpenAtOnce: Int = 0

        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            groups += androidPermissions
            pending += androidPermissions to onResult
            mostOpenAtOnce = maxOf(mostOpenAtOnce, pending.size)
            return true
        }

        fun answerNext(granted: Boolean) {
            val (strings: List<String>, onResult: (Map<String, Boolean>) -> Unit) = pending.removeAt(0)
            onResult(strings.associateWith { granted })
        }
    }

    private fun controller(
        host: PermissionRequestHost = RecordingHost(),
        launchScreen: (SystemScreenRequest) -> Boolean = { true },
    ): AndroidPermissionController = testController(
        context = application,
        storage = storage,
        host = host,
        resumeListeners = resumes,
        launchScreen = launchScreen,
    )

    // --- flags stored by 2.x --------------------------------------------------------------------

    @Test
    fun `a refusal stored by 2_x under its enum name is still read as permanent`() = runTest {
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_MEDIA_AUDIO)
        // The exact keys 2.2.x wrote: "<prefix>.asked.<Permission.name>" and "<prefix>.rationale.<name>".
        storage.put("test.asked.MICROPHONE", "dialog-shown")
        storage.put("test.rationale.MICROPHONE", "true")
        storage.put("test.asked.MEDIA_AUDIO", "dialog-shown")
        storage.put("test.rationale.MEDIA_AUDIO", "true")

        val controller: AndroidPermissionController = controller()

        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Microphone))
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.AudioLibrary))
    }

    @Test
    fun `an asked flag stored before 1_5 under its enum name is still read as permanent`() = runTest {
        deny(Manifest.permission.CAMERA)
        storage.put("test.asked.CAMERA", "true")

        assertEquals(PermissionStatus.PermanentlyDenied, controller().check(CommonPermission.Camera))
    }

    @Test
    fun `an asked flag stored by 2_x without a refusal is still a dismissed dialog`() = runTest {
        deny(Manifest.permission.CAMERA)
        storage.put("test.asked.CAMERA", "dialog-shown")

        assertEquals(PermissionStatus.NotDetermined, controller().check(CommonPermission.Camera))
    }

    // --- a type the controller does not know ----------------------------------------------------

    @Test
    fun `a permission type the controller does not know is unavailable and shows nothing`() = runTest {
        val host = RecordingHost()
        val controller: AndroidPermissionController = controller(host)

        assertEquals(PermissionStatus.Unavailable, controller.check(ForeignPermission))
        assertEquals(PermissionStatus.Unavailable, controller.request(ForeignPermission))
        assertEquals(PermissionStatus.Unavailable, controller.observe(ForeignPermission).first())
        assertEquals(
            mapOf<Permission, PermissionStatus>(ForeignPermission to PermissionStatus.Unavailable),
            controller.request(listOf(ForeignPermission)),
        )
        assertEquals(0, host.launchCount)
    }

    // --- serialization and cancellation ---------------------------------------------------------

    @Test
    fun `two concurrent requests never put two dialogs on screen`() = runTest(UnconfinedTestDispatcher()) {
        deny(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val host = HeldHost()
        val controller: AndroidPermissionController = controller(host)

        val camera = async { controller.request(CommonPermission.Camera) }
        val microphone = async { controller.request(CommonPermission.Microphone) }

        assertEquals(1, host.pending.size, "the second request must wait for the first dialog")
        host.answerNext(granted = true)
        assertEquals(1, host.pending.size, "then the second dialog is shown")
        host.answerNext(granted = true)

        assertEquals(PermissionStatus.Granted, camera.await())
        assertEquals(PermissionStatus.Granted, microphone.await())
        assertEquals(1, host.mostOpenAtOnce)
        assertEquals(listOf(listOf(Manifest.permission.CAMERA), listOf(Manifest.permission.RECORD_AUDIO)), host.groups)
    }

    @Test
    fun `cancelling a request whose dialog is up records nothing and frees the next request`() =
        runTest(UnconfinedTestDispatcher()) {
            deny(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            val host = HeldHost()
            val controller: AndroidPermissionController = controller(host)

            val camera = launch { controller.request(CommonPermission.Camera) }
            camera.cancel()
            // The user answers after the caller gave up: the answer lands nowhere, and nothing is stored.
            host.answerNext(granted = false)

            assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.Camera)))
            val microphone = async { controller.request(CommonPermission.Microphone) }
            assertEquals(1, host.pending.size, "a cancelled request must not keep the next one waiting")
            host.answerNext(granted = true)
            assertEquals(PermissionStatus.Granted, microphone.await())
        }

    // --- the batch request ---------------------------------------------------------------------

    @Test
    fun `a batch shows one dialog for every runtime permission that can still prompt`() = runTest {
        deny(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        grant(Manifest.permission.POST_NOTIFICATIONS)
        val host = RecordingHost(answers = { it == Manifest.permission.CAMERA })

        val result: Map<Permission, PermissionStatus> = controller(host).request(
            listOf(CommonPermission.Camera, CommonPermission.Notifications, CommonPermission.Microphone, CommonPermission.Camera),
        )

        assertEquals(listOf(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)), host.groups)
        assertEquals(
            mapOf<Permission, PermissionStatus>(
                CommonPermission.Camera to PermissionStatus.Granted,
                CommonPermission.Notifications to PermissionStatus.Granted,
                // Refused with no rationale: a dismissal, as for a single request.
                CommonPermission.Microphone to PermissionStatus.NotDetermined,
            ),
            result,
        )
        assertEquals(listOf<Permission>(CommonPermission.Camera, CommonPermission.Notifications, CommonPermission.Microphone), result.keys.toList())
        assertEquals("dialog-shown", storage.getStringOrNull(askedKey("test", CommonPermission.Microphone)))
        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.Camera)))
    }

    @Test
    fun `a batch asks for background location only after foreground location, in a dialog of its own`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.CAMERA)
        deny(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val host = object : RecordingHost() {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                groups += androidPermissions
                grant(*androidPermissions.toTypedArray())
                onResult(androidPermissions.associateWith { true })
                return true
            }
        }

        val result: Map<Permission, PermissionStatus> = controller(host).request(
            listOf(CommonPermission.LocationBackground, CommonPermission.Location, CommonPermission.Camera),
        )

        assertEquals(
            listOf(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.CAMERA),
                listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            ),
            host.groups,
        )
        assertTrue(result.values.all { it == PermissionStatus.Granted }, "$result")
    }

    @Test
    fun `a batch opens special access screens one after another once the dialog is answered`() =
        runTest(UnconfinedTestDispatcher()) {
            deny(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
            val opened: MutableList<SystemScreenRequest> = mutableListOf()
            val host = HeldHost()
            val controller: AndroidPermissionController = controller(host) { request -> opened += request; true }

            val result = async {
                controller.request(listOf(AndroidPermission.InstallPackages, CommonPermission.Camera, CommonPermission.Microphone))
            }

            assertEquals(1, host.pending.size)
            assertEquals(emptyList(), opened, "no screen while the dialog is up")
            host.answerNext(granted = true)

            assertEquals(listOf<Permission>(AndroidPermission.InstallPackages), opened.map { (it.kind as PermissionScreen).permission })
            assertFalse(result.isCompleted, "the batch waits for the user to come back from the screen")
            shadowOf(application.packageManager).setCanRequestPackageInstalls(true)
            resumes.fire()

            assertEquals(
                mapOf(
                    AndroidPermission.InstallPackages to PermissionStatus.Granted,
                    CommonPermission.Camera to PermissionStatus.Granted,
                    CommonPermission.Microphone to PermissionStatus.Granted,
                ),
                result.await(),
            )
        }

    @Test
    fun `a batch cancelled mid-way keeps what was answered and records nothing for the rest`() =
        runTest(UnconfinedTestDispatcher()) {
            deny(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
            val host = object : RecordingHost() {
                override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                    groups += androidPermissions
                    onResult(androidPermissions.associateWith { false })
                    return true
                }
            }
            val controller: AndroidPermissionController = controller(host)
            val seen: MutableList<PermissionStatus> = mutableListOf()
            val observation = launch { controller.observe(CommonPermission.Camera).collect { seen += it } }

            val batch = launch {
                controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone, AndroidPermission.InstallPackages))
            }
            // The dialog was answered; the batch now waits on the Settings screen. Cancel it there.
            batch.cancel()

            assertEquals("dialog-shown", storage.getStringOrNull(askedKey("test", CommonPermission.Camera)))
            assertEquals("dialog-shown", storage.getStringOrNull(askedKey("test", CommonPermission.Microphone)))
            assertNull(storage.getStringOrNull(askedKey("test", AndroidPermission.InstallPackages)))
            assertEquals(1, resumes.count, "only the observation's listener is left")
            assertEquals(listOf<PermissionStatus>(PermissionStatus.NotDetermined), seen)
            observation.cancel()
        }

    @Test
    fun `a batch whose dialog could not be shown records nothing and reports every status unchanged`() = runTest {
        deny(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        val result: Map<Permission, PermissionStatus> =
            controller(RecordingHost(canLaunch = false)).request(listOf(CommonPermission.Camera, CommonPermission.Microphone))

        assertEquals(
            mapOf<Permission, PermissionStatus>(CommonPermission.Camera to PermissionStatus.NotDetermined, CommonPermission.Microphone to PermissionStatus.NotDetermined),
            result,
        )
        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.Camera)))
    }
}
