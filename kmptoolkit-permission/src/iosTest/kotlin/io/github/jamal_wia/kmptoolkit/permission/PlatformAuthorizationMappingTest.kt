package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSessionRecordPermissionUndetermined
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusAuthorized
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusDenied
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusNotDetermined
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusRestricted
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional

/**
 * Every iOS authorization value the common entries can read, mapped as each entry's KDoc on
 * [CommonPermission] states. The mappings are pure functions of the platform value precisely so that
 * no branch here needs a prompt on a simulator.
 */
class PlatformAuthorizationMappingTest {

    @Test
    fun `foreground location is granted by when-in-use and by always and limited with reduced accuracy`() {
        assertEquals(PermissionStatus.Granted, foregroundLocationStatus(kCLAuthorizationStatusAuthorizedWhenInUse))
        assertEquals(PermissionStatus.Granted, foregroundLocationStatus(kCLAuthorizationStatusAuthorizedAlways))
        assertEquals(
            PermissionStatus.Limited,
            foregroundLocationStatus(kCLAuthorizationStatusAuthorizedWhenInUse, reducedAccuracy = true),
        )
        assertEquals(PermissionStatus.PermanentlyDenied, foregroundLocationStatus(kCLAuthorizationStatusDenied))
        assertEquals(PermissionStatus.Restricted, foregroundLocationStatus(kCLAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, foregroundLocationStatus(kCLAuthorizationStatusNotDetermined))
        // Accuracy says nothing about a location that is not authorized at all.
        assertEquals(
            PermissionStatus.PermanentlyDenied,
            foregroundLocationStatus(kCLAuthorizationStatusDenied, reducedAccuracy = true),
        )
    }

    @Test
    fun `background location is granted only by always and when-in-use can still be upgraded`() {
        assertEquals(PermissionStatus.Granted, backgroundLocationStatus(kCLAuthorizationStatusAuthorizedAlways))
        assertEquals(
            PermissionStatus.Limited,
            backgroundLocationStatus(kCLAuthorizationStatusAuthorizedAlways, reducedAccuracy = true),
        )
        assertEquals(
            PermissionStatus.Denied(explainFirst = true),
            backgroundLocationStatus(kCLAuthorizationStatusAuthorizedWhenInUse),
        )
        assertEquals(PermissionStatus.PermanentlyDenied, backgroundLocationStatus(kCLAuthorizationStatusDenied))
        assertEquals(PermissionStatus.Restricted, backgroundLocationStatus(kCLAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, backgroundLocationStatus(kCLAuthorizationStatusNotDetermined))
    }

    @Test
    fun `when-in-use after the always upgrade was asked is permanently denied`() {
        assertEquals(
            PermissionStatus.PermanentlyDenied,
            backgroundLocationStatus(kCLAuthorizationStatusAuthorizedWhenInUse, upgradeAsked = true),
        )
        assertEquals(
            PermissionStatus.Granted,
            backgroundLocationStatus(kCLAuthorizationStatusAuthorizedAlways, upgradeAsked = true),
        )
    }

    @Test
    fun `the media library maps restricted to restricted`() {
        assertEquals(PermissionStatus.Granted, mediaLibraryStatus(MPMediaLibraryAuthorizationStatusAuthorized))
        assertEquals(PermissionStatus.PermanentlyDenied, mediaLibraryStatus(MPMediaLibraryAuthorizationStatusDenied))
        assertEquals(PermissionStatus.Restricted, mediaLibraryStatus(MPMediaLibraryAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, mediaLibraryStatus(MPMediaLibraryAuthorizationStatusNotDetermined))
    }

    @Test
    fun `bluetooth maps restricted to restricted`() {
        assertEquals(PermissionStatus.Granted, bluetoothStatus(CBManagerAuthorizationAllowedAlways))
        assertEquals(PermissionStatus.PermanentlyDenied, bluetoothStatus(CBManagerAuthorizationDenied))
        assertEquals(PermissionStatus.Restricted, bluetoothStatus(CBManagerAuthorizationRestricted))
        assertEquals(PermissionStatus.NotDetermined, bluetoothStatus(CBManagerAuthorizationNotDetermined))
    }

    @Test
    fun `the camera maps restricted to restricted`() {
        assertEquals(PermissionStatus.Granted, captureStatus(AVAuthorizationStatusAuthorized))
        assertEquals(PermissionStatus.PermanentlyDenied, captureStatus(AVAuthorizationStatusDenied))
        assertEquals(PermissionStatus.Restricted, captureStatus(AVAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, captureStatus(AVAuthorizationStatusNotDetermined))
    }

    @Test
    fun `the microphone has no restricted state`() {
        assertEquals(PermissionStatus.Granted, recordPermissionStatus(AVAudioSessionRecordPermissionGranted))
        assertEquals(PermissionStatus.PermanentlyDenied, recordPermissionStatus(AVAudioSessionRecordPermissionDenied))
        assertEquals(PermissionStatus.NotDetermined, recordPermissionStatus(AVAudioSessionRecordPermissionUndetermined))
    }

    @Test
    fun `provisional notifications are limited and ephemeral ones granted`() {
        assertEquals(PermissionStatus.Granted, notificationStatus(UNAuthorizationStatusAuthorized))
        assertEquals(PermissionStatus.Granted, notificationStatus(UNAuthorizationStatusEphemeral))
        assertEquals(PermissionStatus.Limited, notificationStatus(UNAuthorizationStatusProvisional))
        assertEquals(PermissionStatus.PermanentlyDenied, notificationStatus(UNAuthorizationStatusDenied))
        assertEquals(PermissionStatus.NotDetermined, notificationStatus(UNAuthorizationStatusNotDetermined))
        assertEquals(PermissionStatus.NotDetermined, notificationStatus(null))
    }

    // --- the controller ---------------------------------------------------------------------------

    /** A controller in a test bundle that pretends to have every purpose string. */
    private fun controller(hasKey: (String) -> Boolean = { true }): PermissionController =
        IosPermissionController(NoopLogger, hasUsageDescription = hasKey)

    @Test
    fun `checking every common entry reads the platform without prompting`() = runTest {
        val controller: PermissionController = controller()

        listOf(
            CommonPermission.Location,
            CommonPermission.LocationBackground,
            CommonPermission.AudioLibrary,
            CommonPermission.BluetoothConnect,
            CommonPermission.BluetoothScan,
            CommonPermission.Camera,
            CommonPermission.Microphone,
        ).forEach { permission -> controller.check(permission) }
    }

    @Test
    fun `bluetooth scanning reports the one Bluetooth authorization bluetooth connect reports`() = runTest {
        val controller: PermissionController = controller()

        assertEquals(bluetoothStatus(BluetoothAuthorization.current()), controller.check(CommonPermission.BluetoothScan))
        assertEquals(controller.check(CommonPermission.BluetoothConnect), controller.check(CommonPermission.BluetoothScan))
    }

    @Test
    fun `a permission whose purpose string is missing is unavailable and and a request shows nothing`() = runTest {
        // A request without NSCameraUsageDescription would terminate the app; the controller must not
        // reach the platform call at all. This test bundle has no purpose strings, so it would crash here.
        val controller: PermissionController = controller(hasKey = { key -> key != "NSCameraUsageDescription" })

        assertEquals(PermissionStatus.Unavailable, controller.check(CommonPermission.Camera))
        assertEquals(PermissionStatus.Unavailable, controller.request(CommonPermission.Camera))
        assertEquals(
            mapOf<Permission, PermissionStatus>(CommonPermission.Camera to PermissionStatus.Unavailable),
            controller.request(listOf(CommonPermission.Camera)),
        )
    }

    @Test
    fun `background location needs both location purpose strings`() = runTest {
        val controller: PermissionController =
            controller(hasKey = { key -> key != "NSLocationAlwaysAndWhenInUseUsageDescription" })

        assertEquals(PermissionStatus.Unavailable, controller.check(CommonPermission.LocationBackground))
    }

    @Test
    fun `the real factory reads Info_plist and which a test bundle does not have`() = runTest {
        assertEquals(PermissionStatus.Unavailable, createPermissionController().check(CommonPermission.Camera))
    }

    @OptIn(ToolkitInternalApi::class)
    private object Foreign : Permission {
        override val id: String = "FOREIGN"
    }

    @Test
    fun `a permission type the controller does not know is unavailable and opens nothing`() = runTest {
        val controller: PermissionController = controller()

        assertEquals(PermissionStatus.Unavailable, controller.check(Foreign))
        assertEquals(PermissionStatus.Unavailable, controller.request(Foreign))
        assertFalse(controller.openSettings(Foreign))
    }
}
