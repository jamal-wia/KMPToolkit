package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Special accesses through the one controller: their status per API level, and a request that opens
 * the Settings screen and returns only once the user is back, with the status read again.
 *
 * The cases come from [AndroidPermission.SpecialAccess]'s contract and each entry's KDoc.
 * [AndroidPermission.InstallPackages] is the entry the request tests drive, because it is the one whose
 * grant Robolectric can flip (`setCanRequestPackageInstalls`) the way the user does on the screen.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSpecialAccessTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val storage: KeyValueStorage = InMemoryKeyValueStorage()
    private val resumes: ResumeListeners = ResumeListeners()
    private val opened: MutableList<SystemScreenRequest> = mutableListOf()

    private fun controller(
        declared: Set<String>? = null,
        launch: (SystemScreenRequest) -> Boolean = { request -> opened += request; true },
    ): AndroidPermissionController = testController(
        context = application,
        storage = storage,
        sdkInt = Build.VERSION.SDK_INT,
        resumeListeners = resumes,
        launchScreen = launch,
        declaredPermissions = { declared },
    )

    private fun allowInstalls(allowed: Boolean) = shadowOf(application.packageManager).setCanRequestPackageInstalls(allowed)

    // --- status per API level -----------------------------------------------------------------

    @Test
    @Config(sdk = [30])
    fun `exact alarms below API 31 need nothing and are granted`() = runTest {
        assertEquals(PermissionStatus.Granted, controller().check(AndroidPermission.ExactAlarm))
    }

    @Test
    @Config(sdk = [29])
    fun `all-files access below API 30 is granted, storage was not scoped`() = runTest {
        assertEquals(PermissionStatus.Granted, controller().check(AndroidPermission.AllFilesAccess))
    }

    @Test
    @Config(sdk = [29])
    fun `the unused-app exemption below API 30 is granted, nothing is taken back`() = runTest {
        assertEquals(PermissionStatus.Granted, controller().check(AndroidPermission.KeepPermissionsWhenUnused))
    }

    @Test
    @Config(sdk = [30])
    fun `media management below API 31 does not exist`() = runTest {
        assertEquals(PermissionStatus.Unavailable, controller().check(AndroidPermission.MediaManagement))
    }

    @Test
    @Config(sdk = [25])
    fun `installing packages below API 26 is unavailable, not the device-wide switch`() = runTest {
        @Suppress("DEPRECATION")
        android.provider.Settings.Secure.putInt(application.contentResolver, android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS, 1)

        assertEquals(PermissionStatus.Unavailable, controller().check(AndroidPermission.InstallPackages))
    }

    @Test
    @Config(sdk = [26])
    fun `installing packages from API 26 is this app's own grant`() = runTest {
        allowInstalls(false)
        assertEquals(PermissionStatus.Denied(explainFirst = true), controller().check(AndroidPermission.InstallPackages))

        allowInstalls(true)
        assertEquals(PermissionStatus.Granted, controller().check(AndroidPermission.InstallPackages))
    }

    @Test
    @Config(sdk = [33])
    fun `full-screen notifications below API 34 are granted only when declared`() = runTest {
        assertEquals(
            PermissionStatus.Granted,
            controller(declared = setOf(Manifest.permission.USE_FULL_SCREEN_INTENT)).check(AndroidPermission.FullScreenNotifications),
        )
        assertEquals(
            PermissionStatus.Unavailable,
            controller(declared = emptySet()).check(AndroidPermission.FullScreenNotifications),
        )
    }

    @Test
    fun `checking never throws for any entry`() = runTest {
        // Real device state (canDrawOverlays, usage-stats app-ops, …) is not something Robolectric fakes
        // meaningfully; every branch must still resolve to a status.
        val controller: AndroidPermissionController = controller()
        for (access in AndroidPermission.specialAccesses) controller.check(access)
    }

    @Test
    fun `nothing is stored for a special access`() = runTest {
        allowInstalls(false)
        val controller: AndroidPermissionController = controller()

        val request = launch { controller.request(AndroidPermission.InstallPackages) }
        testScheduler.runCurrent()
        resumes.fire()
        request.join()

        assertNull(storage.getStringOrNull(askedKey("test", AndroidPermission.InstallPackages)))
    }

    // --- request: open, wait for the return, read again ---------------------------------------

    @Test
    fun `a request opens the screen once and returns only after the user is back, with the new status`() =
        runTest(UnconfinedTestDispatcher()) {
            allowInstalls(false)
            val controller: AndroidPermissionController = controller()

            val result = async { controller.request(AndroidPermission.InstallPackages) }

            assertEquals(1, opened.size)
            assertEquals(PermissionScreen(AndroidPermission.InstallPackages), opened.single().kind)
            assertFalse(result.isCompleted, "the request must wait for the user to come back")

            allowInstalls(true) // the user flips the toggle on the screen
            resumes.fire()

            assertEquals(PermissionStatus.Granted, result.await())
            assertEquals(0, resumes.count, "the resume listener must not outlive the request")
        }

    @Test
    fun `coming back without granting returns the unchanged status`() = runTest(UnconfinedTestDispatcher()) {
        allowInstalls(false)
        val controller: AndroidPermissionController = controller()

        val result = async { controller.request(AndroidPermission.InstallPackages) }
        resumes.fire()

        assertEquals(PermissionStatus.Denied(explainFirst = true), result.await())
    }

    @Test
    fun `the resume of the activity that was already up is not mistaken for the return`() =
        runTest(UnconfinedTestDispatcher()) {
            allowInstalls(false)
            resumes.resumedNow = true // a resumed activity answers every new listener at once
            val controller: AndroidPermissionController = controller()

            val result = async { controller.request(AndroidPermission.InstallPackages) }

            assertFalse(result.isCompleted)
            resumes.fire()
            assertTrue(result.isCompleted)
        }

    @Test
    fun `a request for a granted access opens nothing`() = runTest {
        allowInstalls(true)

        assertEquals(PermissionStatus.Granted, controller().request(AndroidPermission.InstallPackages))
        assertEquals(emptyList(), opened)
    }

    @Test
    @Config(sdk = [30])
    fun `a request below the access's API level opens nothing`() = runTest {
        val controller: AndroidPermissionController = controller()

        assertEquals(PermissionStatus.Granted, controller.request(AndroidPermission.ExactAlarm))
        assertEquals(PermissionStatus.Unavailable, controller.request(AndroidPermission.MediaManagement))
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a screen that could not be opened returns at once, unchanged, with no listener left`() = runTest {
        allowInstalls(false)

        val status: PermissionStatus = controller(launch = { false }).request(AndroidPermission.InstallPackages)

        assertEquals(PermissionStatus.Denied(explainFirst = true), status)
        assertEquals(0, resumes.count)
    }

    @Test
    fun `a launcher that throws returns at once, unchanged, with no listener left`() = runTest {
        allowInstalls(false)

        val status: PermissionStatus =
            controller(launch = { error("launcher bug") }).request(AndroidPermission.InstallPackages)

        assertEquals(PermissionStatus.Denied(explainFirst = true), status)
        assertEquals(0, resumes.count)
    }

    @Test
    fun `cancelling a request while the user is away removes its listener`() = runTest(UnconfinedTestDispatcher()) {
        allowInstalls(false)
        val controller: AndroidPermissionController = controller()

        val request = launch { controller.request(AndroidPermission.InstallPackages) }
        assertEquals(1, resumes.count)

        request.cancel()

        assertEquals(0, resumes.count)
        // The screen stays where it is; what the user does there is seen by the next check.
        allowInstalls(true)
        assertEquals(PermissionStatus.Granted, controller.check(AndroidPermission.InstallPackages))
    }

    @Test
    fun `a second request waits for the first one's screen to be answered`() = runTest(UnconfinedTestDispatcher()) {
        allowInstalls(false)
        val controller: AndroidPermissionController = controller()

        val first = async { controller.request(AndroidPermission.InstallPackages) }
        val second = async { controller.request(AndroidPermission.Overlay) }

        assertEquals(listOf<Permission>(AndroidPermission.InstallPackages), opened.map { (it.kind as PermissionScreen).permission })
        assertFalse(second.isCompleted)

        resumes.fire() // back from the first screen; the second one opens
        assertTrue(first.isCompleted)
        assertEquals(
            listOf<Permission>(AndroidPermission.InstallPackages, AndroidPermission.Overlay),
            opened.map { (it.kind as PermissionScreen).permission },
        )

        resumes.fire()
        second.await()
    }

    @Test
    fun `observe sees the access granted on the screen once the user is back`() = runTest(UnconfinedTestDispatcher()) {
        allowInstalls(false)
        val controller: AndroidPermissionController = controller()
        val seen = mutableListOf<PermissionStatus>()
        val collection = launch { controller.observe(AndroidPermission.InstallPackages).toList(seen) }

        assertTrue(controller.openSettings(AndroidPermission.InstallPackages))
        allowInstalls(true)
        resumes.fire()

        assertEquals(listOf(PermissionStatus.Denied(explainFirst = true), PermissionStatus.Granted), seen)
        collection.cancel()
    }
}
