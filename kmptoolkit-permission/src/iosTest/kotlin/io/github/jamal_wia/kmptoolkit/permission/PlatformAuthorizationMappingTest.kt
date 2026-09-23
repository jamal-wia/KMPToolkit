package io.github.jamal_wia.kmptoolkit.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
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

/**
 * Every iOS authorization value the 1.5.0 catalog entries can read, mapped as each entry's KDoc on
 * [Permission] states. The mappings are pure functions of the platform value precisely so that no
 * branch here needs a prompt on a simulator.
 */
class PlatformAuthorizationMappingTest {

    @Test
    fun `foreground location is granted by when-in-use and by always`() {
        assertEquals(PermissionStatus.Granted, foregroundLocationStatus(kCLAuthorizationStatusAuthorizedWhenInUse))
        assertEquals(PermissionStatus.Granted, foregroundLocationStatus(kCLAuthorizationStatusAuthorizedAlways))
        assertEquals(PermissionStatus.PermanentlyDenied, foregroundLocationStatus(kCLAuthorizationStatusDenied))
        assertEquals(PermissionStatus.PermanentlyDenied, foregroundLocationStatus(kCLAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, foregroundLocationStatus(kCLAuthorizationStatusNotDetermined))
    }

    @Test
    fun `background location is granted only by always and when-in-use can still be upgraded`() {
        assertEquals(PermissionStatus.Granted, backgroundLocationStatus(kCLAuthorizationStatusAuthorizedAlways))
        assertEquals(
            PermissionStatus.Denied(shouldShowRationale = true),
            backgroundLocationStatus(kCLAuthorizationStatusAuthorizedWhenInUse),
        )
        assertEquals(PermissionStatus.PermanentlyDenied, backgroundLocationStatus(kCLAuthorizationStatusDenied))
        assertEquals(PermissionStatus.PermanentlyDenied, backgroundLocationStatus(kCLAuthorizationStatusRestricted))
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
    fun `the media library maps restricted to permanently denied`() {
        assertEquals(PermissionStatus.Granted, mediaLibraryStatus(MPMediaLibraryAuthorizationStatusAuthorized))
        assertEquals(PermissionStatus.PermanentlyDenied, mediaLibraryStatus(MPMediaLibraryAuthorizationStatusDenied))
        assertEquals(PermissionStatus.PermanentlyDenied, mediaLibraryStatus(MPMediaLibraryAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, mediaLibraryStatus(MPMediaLibraryAuthorizationStatusNotDetermined))
    }

    @Test
    fun `bluetooth maps restricted to permanently denied`() {
        assertEquals(PermissionStatus.Granted, bluetoothStatus(CBManagerAuthorizationAllowedAlways))
        assertEquals(PermissionStatus.PermanentlyDenied, bluetoothStatus(CBManagerAuthorizationDenied))
        assertEquals(PermissionStatus.PermanentlyDenied, bluetoothStatus(CBManagerAuthorizationRestricted))
        assertEquals(PermissionStatus.NotDetermined, bluetoothStatus(CBManagerAuthorizationNotDetermined))
    }

    @Test
    fun `checking a new catalog entry reads the platform without prompting`() = runTest {
        // A check must never show UI and must not need an Info.plist purpose string; on a simulator
        // with nothing granted, each answers without throwing.
        val handler: PermissionHandler = createPermissionHandler()

        listOf(
            Permission.LOCATION,
            Permission.LOCATION_BACKGROUND,
            Permission.MEDIA_AUDIO,
            Permission.BLUETOOTH_CONNECT,
            Permission.BLUETOOTH_SCAN,
        ).forEach { permission -> handler.check(permission) }
    }

    @Test
    fun `bluetooth scanning reports the one Bluetooth authorization bluetooth connect reports`() = runTest {
        val handler: PermissionHandler = createPermissionHandler()

        assertEquals(bluetoothStatus(BluetoothAuthorization.current()), handler.check(Permission.BLUETOOTH_SCAN))
        assertEquals(handler.check(Permission.BLUETOOTH_CONNECT), handler.check(Permission.BLUETOOTH_SCAN))
    }
}
