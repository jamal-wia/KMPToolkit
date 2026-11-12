@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

/**
 * The `Info.plist` table of docs/kmptoolkit-permission/05-platform-notes.md as a test: for every entry, the keys the
 * docs list are exactly the keys the controller insists on. A key outside the table that the controller also requires
 * would make a correctly configured app read `Unavailable`; a documented key it ignores would let iOS terminate the app
 * on the prompt. Entries whose key depends on the OS version list both spellings: the app has one of them.
 */
class IosInfoPlistKeyTableDocsTest {

    /** Entry -> groups of keys; every group must be present, and within a group any one spelling does. */
    private val documented: Map<Permission, List<Set<String>>> = mapOf(
        CommonPermission.Microphone to listOf(setOf("NSMicrophoneUsageDescription")),
        CommonPermission.Camera to listOf(setOf("NSCameraUsageDescription")),
        CommonPermission.Location to listOf(setOf("NSLocationWhenInUseUsageDescription")),
        CommonPermission.LocationBackground to listOf(
            setOf("NSLocationAlwaysAndWhenInUseUsageDescription"),
            setOf("NSLocationWhenInUseUsageDescription"),
        ),
        CommonPermission.AudioLibrary to listOf(setOf("NSAppleMusicUsageDescription")),
        CommonPermission.PhotoLibrary to listOf(setOf("NSPhotoLibraryUsageDescription")),
        CommonPermission.PhotoLibraryAdd to listOf(setOf("NSPhotoLibraryAddUsageDescription")),
        CommonPermission.Contacts to listOf(setOf("NSContactsUsageDescription")),
        CommonPermission.Calendar to listOf(setOf("NSCalendarsFullAccessUsageDescription", "NSCalendarsUsageDescription")),
        CommonPermission.CalendarWriteOnly to listOf(
            setOf("NSCalendarsWriteOnlyAccessUsageDescription", "NSCalendarsUsageDescription"),
        ),
        CommonPermission.MotionActivity to listOf(setOf("NSMotionUsageDescription")),
        CommonPermission.BluetoothConnect to listOf(setOf("NSBluetoothAlwaysUsageDescription")),
        CommonPermission.BluetoothScan to listOf(setOf("NSBluetoothAlwaysUsageDescription")),
        CommonPermission.BluetoothAdvertise to listOf(setOf("NSBluetoothAlwaysUsageDescription")),
        IosPermission.Tracking to listOf(setOf("NSUserTrackingUsageDescription")),
        IosPermission.SpeechRecognition to listOf(setOf("NSSpeechRecognitionUsageDescription")),
        IosPermission.Reminders to listOf(setOf("NSRemindersFullAccessUsageDescription", "NSRemindersUsageDescription")),
        IosPermission.FocusStatus to listOf(setOf("NSFocusStatusUsageDescription")),
    )

    private val asked: MutableList<String> = mutableListOf()

    private fun controller(hasKey: (String) -> Boolean): PermissionController {
        val port = ChaosIosAuthorizationPort()
        return IosPermissionController(NoopLogger, IosPermissionOptions(), { key -> asked += key; hasKey(key) }, port)
    }

    @Test
    fun `an app with exactly the documented keys reads every entry as determinable`() = runTest {
        documented.forEach { (permission, groups) ->
            val keys: Set<String> = groups.flatten().toSet()
            val status: PermissionStatus = controller { it in keys }.check(permission)
            assertNotEquals(PermissionStatus.Unavailable, status, "$permission with only $keys")
        }
    }

    @Test
    fun `an app that lacks any one documented group reads the entry as unavailable`() = runTest {
        documented.forEach { (permission, groups) ->
            groups.forEach { group: Set<String> ->
                val status: PermissionStatus = controller { it !in group }.check(permission)
                assertEquals(PermissionStatus.Unavailable, status, "$permission without $group")
            }
        }
    }

    @Test
    fun `an app with no key at all reads every keyed entry as unavailable and notifications as determinable`() = runTest {
        val bare: PermissionController = controller { false }
        documented.keys.forEach { assertEquals(PermissionStatus.Unavailable, bare.check(it), "$it") }
        assertEquals(PermissionStatus.NotDetermined, bare.check(CommonPermission.Notifications))
    }

    @Test
    fun `a request never raises a prompt for an entry whose key is missing`() = runTest {
        documented.keys.forEach { permission ->
            val port = ChaosIosAuthorizationPort()
            val bare: PermissionController = IosPermissionController(NoopLogger, IosPermissionOptions(), { false }, port)
            assertEquals(PermissionStatus.Unavailable, bare.request(permission), "$permission")
            assertEquals(0, port.promptCount(), "$permission prompted without its purpose string")
        }
    }
}
