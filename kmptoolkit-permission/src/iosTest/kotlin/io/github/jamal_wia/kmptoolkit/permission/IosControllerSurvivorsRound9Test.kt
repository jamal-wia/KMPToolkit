@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusAuthorized

/**
 * Round 9 (E1, bm): the survivors of the first iOS mutant batch (I46 and I50 add-only photo access read and asked at the
 * read-write level, I48 motion availability ignored, I4 the conflation of became-active bursts).
 */
class IosControllerSurvivorsRound9Test {

    @Test
    fun `add only photo access is read and asked at its own level and never at the read write one`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            photoReadWrite = PHAuthorizationStatusDenied
            photoAddOnly = PHAuthorizationStatusNotDetermined
            hooks["requestPhotoLibrary"] = { photoAddOnly = PHAuthorizationStatusAuthorized }
        }
        val controller: PermissionController = IosPermissionController(NoopLogger, IosPermissionOptions(), { true }, port)

        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.PhotoLibraryAdd))
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.PhotoLibrary))

        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.PhotoLibraryAdd))
        assertEquals(PHAuthorizationStatusDenied, port.photoReadWrite, "the read-write authorization is not touched")
        assertEquals(PermissionStatus.PermanentlyDenied, controller.request(CommonPermission.PhotoLibrary), "read-write stays refused: no prompt")
        assertEquals(1, port.count("requestPhotoLibrary"))
    }

    @Test
    fun `a device without motion hardware reads motion activity as unavailable and a request raises nothing`() = runTest {
        val port = FakeIosAuthorizationPort().apply { motionAvailable = false }
        val controller: PermissionController = IosPermissionController(NoopLogger, IosPermissionOptions(), { true }, port)

        assertEquals(PermissionStatus.Unavailable, controller.check(CommonPermission.MotionActivity))
        assertEquals(PermissionStatus.Unavailable, controller.request(CommonPermission.MotionActivity))
        assertEquals(0, port.count("requestMotionActivity"))
    }

    /**
     * Efficiency, not documented behaviour: a burst of did-become-active notifications that arrives while one status read
     * is still in flight costs one more read, not one per notification (the flow is conflated). Without the conflation
     * the same emissions are produced after up to 64 re-reads.
     */
    @Test
    fun `a burst of activations during one pending read costs a single extra read`() = runTest {
        val port = ChaosIosAuthorizationPort().apply { hangNotificationReads.value = true }
        val controller: PermissionController = IosPermissionController(NoopLogger, IosPermissionOptions(), { true }, port)
        val seen: MutableList<PermissionStatus> = mutableListOf()
        val job: Job = launch { controller.observe(CommonPermission.Notifications).collect { seen += it } }
        runCurrent()
        assertEquals(1, port.count("notificationAuthorization"), "the first read is pending")

        repeat(40) { port.activate() }
        port.hangNotificationReads.value = false
        port.notification.value = UNAuthorizationStatusAuthorized
        port.heldNotificationReads.value.single()(UNAuthorizationStatusAuthorized)
        runCurrent()

        assertEquals(listOf<PermissionStatus>(PermissionStatus.Granted), seen)
        assertTrue(port.count("notificationAuthorization") <= 3, "${port.count("notificationAuthorization")} reads for one burst")
        job.cancel()
    }
}
