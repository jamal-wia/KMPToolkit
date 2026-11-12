@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

/**
 * The status table of 04-api-reference, iOS column, as a sweep over raw platform values, known and not: "Denied with
 * an explanation: only for LocationBackground", "Denied without: never", and the two grants only for values the
 * platform defines. A value this library has never seen (a future iOS) must not read as a grant, and nothing throws.
 */
class IosRawValueSweepRound10Test {

    private val sweep: IntRange = -3..12

    private val setters: List<Pair<Permission, (FakeIosAuthorizationPort, Int) -> Unit>> = listOf(
        CommonPermission.Notifications to { p, v -> p.notification = v.convert() },
        CommonPermission.Camera to { p, v -> p.camera = v.convert() },
        CommonPermission.Location to { p, v -> p.location = v.convert() },
        CommonPermission.LocationBackground to { p, v -> p.location = v.convert() },
        CommonPermission.AudioLibrary to { p, v -> p.mediaLibrary = v.convert() },
        CommonPermission.BluetoothConnect to { p, v -> p.bluetooth = v.convert() },
        CommonPermission.BluetoothScan to { p, v -> p.bluetooth = v.convert() },
        CommonPermission.BluetoothAdvertise to { p, v -> p.bluetooth = v.convert() },
        CommonPermission.PhotoLibrary to { p, v -> p.photoReadWrite = v.convert() },
        CommonPermission.PhotoLibraryAdd to { p, v -> p.photoAddOnly = v.convert() },
        CommonPermission.Contacts to { p, v -> p.contacts = v.convert() },
        CommonPermission.Calendar to { p, v -> p.calendar = v.convert() },
        CommonPermission.CalendarWriteOnly to { p, v -> p.calendar = v.convert() },
        CommonPermission.MotionActivity to { p, v -> p.motion = v.convert() },
        IosPermission.Reminders to { p, v -> p.reminders = v.convert() },
        IosPermission.Tracking to { p, v -> p.tracking = v.convert() },
        IosPermission.FocusStatus to { p, v -> p.focus = v.convert() },
    )

    @Test
    fun `no raw value reads as Denied without an explanation and only LocationBackground is ever Denied`() = runTest {
        for ((permission, set) in setters) for (raw in sweep) {
            val port = FakeIosAuthorizationPort()
            set(port, raw)
            val controller: PermissionController = IosPermissionController(NoopLogger, IosPermissionOptions(), { true }, port)

            val checked: PermissionStatus = controller.check(permission)
            val requested: PermissionStatus = controller.request(permission)

            for (status in listOf(checked, requested)) {
                assertTrue(status != PermissionStatus.Denied(explainFirst = false), "$permission raw $raw read $status")
                if (permission != CommonPermission.LocationBackground) {
                    assertTrue(status !is PermissionStatus.Denied, "$permission raw $raw read $status")
                }
            }
        }
    }

    @Test
    fun `a raw value outside what the platform defines never reads as a grant`() = runTest {
        for ((permission, set) in setters) for (raw in sweep.filter { it < 0 || it > 5 }) {
            val port = FakeIosAuthorizationPort()
            set(port, raw)
            val controller: PermissionController = IosPermissionController(NoopLogger, IosPermissionOptions(), { true }, port)

            val status: PermissionStatus = controller.check(permission)

            assertTrue(!status.isUsable, "$permission raw $raw read $status")
        }
    }

    @Test
    fun `with every value untouched every entry starts as NotDetermined or Unavailable and never as a refusal`() = runTest {
        val port = FakeIosAuthorizationPort()
        val controller: PermissionController = IosPermissionController(NoopLogger, IosPermissionOptions(), { true }, port)
        for ((permission, _) in setters) {
            val status: PermissionStatus = controller.check(permission)
            assertTrue(status == PermissionStatus.NotDetermined || status == PermissionStatus.Unavailable, "$permission read $status")
            assertEquals(false, status.isUsable)
        }
    }
}
