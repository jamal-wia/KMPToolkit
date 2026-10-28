package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusAuthorized
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusDenied
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusNotDetermined
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusRestricted
import platform.Contacts.CNAuthorizationStatusAuthorized
import platform.Contacts.CNAuthorizationStatusDenied
import platform.Contacts.CNAuthorizationStatusLimited
import platform.Contacts.CNAuthorizationStatusNotDetermined
import platform.Contacts.CNAuthorizationStatusRestricted
import platform.CoreMotion.CMAuthorizationStatusAuthorized
import platform.CoreMotion.CMAuthorizationStatusDenied
import platform.CoreMotion.CMAuthorizationStatusNotDetermined
import platform.CoreMotion.CMAuthorizationStatusRestricted
import platform.EventKit.EKAuthorizationStatusAuthorized
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKAuthorizationStatusNotDetermined
import platform.EventKit.EKAuthorizationStatusRestricted
import platform.EventKit.EKAuthorizationStatusWriteOnly
import platform.Intents.INFocusStatusAuthorizationStatusAuthorized
import platform.Intents.INFocusStatusAuthorizationStatusDenied
import platform.Intents.INFocusStatusAuthorizationStatusNotDetermined
import platform.Intents.INFocusStatusAuthorizationStatusRestricted
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHAuthorizationStatusRestricted
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.UIKit.UIViewController
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionCriticalAlert
import platform.UserNotifications.UNAuthorizationOptionProvisional
import platform.UserNotifications.UNAuthorizationOptionSound
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

    @Test
    fun `a limited photo library is limited and add-only access is never limited`() {
        assertEquals(PermissionStatus.Granted, photoLibraryStatus(PHAuthorizationStatusAuthorized))
        assertEquals(PermissionStatus.Limited, photoLibraryStatus(PHAuthorizationStatusLimited))
        assertEquals(PermissionStatus.PermanentlyDenied, photoLibraryStatus(PHAuthorizationStatusDenied))
        assertEquals(PermissionStatus.Restricted, photoLibraryStatus(PHAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, photoLibraryStatus(PHAuthorizationStatusNotDetermined))
    }

    @Test
    fun `limited contacts access is limited`() {
        assertEquals(PermissionStatus.Granted, contactsStatus(CNAuthorizationStatusAuthorized))
        assertEquals(PermissionStatus.Limited, contactsStatus(CNAuthorizationStatusLimited))
        assertEquals(PermissionStatus.PermanentlyDenied, contactsStatus(CNAuthorizationStatusDenied))
        assertEquals(PermissionStatus.Restricted, contactsStatus(CNAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, contactsStatus(CNAuthorizationStatusNotDetermined))
    }

    @Test
    fun `write-only calendar access is limited for full access and granted for write-only`() {
        assertEquals(PermissionStatus.Granted, calendarStatus(EKAuthorizationStatusFullAccess, writeOnly = false))
        assertEquals(PermissionStatus.Granted, calendarStatus(EKAuthorizationStatusFullAccess, writeOnly = true))
        assertEquals(PermissionStatus.Limited, calendarStatus(EKAuthorizationStatusWriteOnly, writeOnly = false))
        assertEquals(PermissionStatus.Granted, calendarStatus(EKAuthorizationStatusWriteOnly, writeOnly = true))
        listOf(false, true).forEach { writeOnly ->
            assertEquals(PermissionStatus.PermanentlyDenied, calendarStatus(EKAuthorizationStatusDenied, writeOnly))
            assertEquals(PermissionStatus.Restricted, calendarStatus(EKAuthorizationStatusRestricted, writeOnly))
            assertEquals(PermissionStatus.NotDetermined, calendarStatus(EKAuthorizationStatusNotDetermined, writeOnly))
        }
    }

    @Test
    fun `the pre-iOS-17 authorized calendar status is full access`() {
        assertEquals(PermissionStatus.Granted, calendarStatus(EKAuthorizationStatusAuthorized, writeOnly = false))
    }

    @Test
    fun `motion activity without the hardware is unavailable whatever the authorization`() {
        assertEquals(PermissionStatus.Granted, motionActivityStatus(CMAuthorizationStatusAuthorized, available = true))
        assertEquals(PermissionStatus.PermanentlyDenied, motionActivityStatus(CMAuthorizationStatusDenied, available = true))
        assertEquals(PermissionStatus.Restricted, motionActivityStatus(CMAuthorizationStatusRestricted, available = true))
        assertEquals(PermissionStatus.NotDetermined, motionActivityStatus(CMAuthorizationStatusNotDetermined, available = true))
        assertEquals(PermissionStatus.Unavailable, motionActivityStatus(CMAuthorizationStatusAuthorized, available = false))
        assertEquals(PermissionStatus.Unavailable, motionActivityStatus(CMAuthorizationStatusNotDetermined, available = false))
    }

    @Test
    fun `the iOS-only authorizations map restricted to restricted and a refusal to permanent`() {
        assertEquals(PermissionStatus.Granted, trackingStatus(ATTrackingManagerAuthorizationStatusAuthorized))
        assertEquals(PermissionStatus.PermanentlyDenied, trackingStatus(ATTrackingManagerAuthorizationStatusDenied))
        assertEquals(PermissionStatus.Restricted, trackingStatus(ATTrackingManagerAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, trackingStatus(ATTrackingManagerAuthorizationStatusNotDetermined))

        assertEquals(PermissionStatus.Granted, speechRecognitionStatus(SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized))
        assertEquals(PermissionStatus.PermanentlyDenied, speechRecognitionStatus(SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusDenied))
        assertEquals(PermissionStatus.Restricted, speechRecognitionStatus(SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, speechRecognitionStatus(SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined))

        assertEquals(PermissionStatus.Granted, focusStatusStatus(INFocusStatusAuthorizationStatusAuthorized))
        assertEquals(PermissionStatus.PermanentlyDenied, focusStatusStatus(INFocusStatusAuthorizationStatusDenied))
        assertEquals(PermissionStatus.Restricted, focusStatusStatus(INFocusStatusAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, focusStatusStatus(INFocusStatusAuthorizationStatusNotDetermined))

        assertEquals(PermissionStatus.Granted, remindersStatus(EKAuthorizationStatusFullAccess))
        assertEquals(PermissionStatus.PermanentlyDenied, remindersStatus(EKAuthorizationStatusDenied))
        assertEquals(PermissionStatus.Restricted, remindersStatus(EKAuthorizationStatusRestricted))
        assertEquals(PermissionStatus.NotDetermined, remindersStatus(EKAuthorizationStatusNotDetermined))
    }

    @Test
    fun `notification options become their UNAuthorizationOptions bits`() {
        assertEquals(
            UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound,
            authorizationOptions(IosPermissionOptions().notificationOptions),
        )
        assertEquals(
            UNAuthorizationOptionProvisional or UNAuthorizationOptionCriticalAlert,
            authorizationOptions(setOf(IosNotificationOption.Provisional, IosNotificationOption.CriticalAlert)),
        )
    }

    @Test
    fun `notification options must not be empty`() {
        assertFailsWith<IllegalArgumentException> { IosPermissionOptions(notificationOptions = emptySet()) }
    }

    @Test
    fun `iOS-only ids are unique and distinct from the common ones`() {
        val ids: List<String> = (CommonPermission.entries + IosPermission.entries).map { it.id }

        assertEquals(ids.size, ids.toSet().size, "ids collided: $ids")
    }

    // --- the controller ---------------------------------------------------------------------------

    /** A controller in a test bundle that pretends to have every purpose string. */
    private fun controller(hasKey: (String) -> Boolean = { true }): PermissionController =
        IosPermissionController(NoopLogger, IosPermissionOptions(), hasUsageDescription = hasKey)

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
            CommonPermission.BluetoothAdvertise,
            CommonPermission.PhotoLibrary,
            CommonPermission.PhotoLibraryAdd,
            CommonPermission.Contacts,
            CommonPermission.Calendar,
            CommonPermission.CalendarWriteOnly,
            CommonPermission.MotionActivity,
            IosPermission.Tracking,
            IosPermission.SpeechRecognition,
            IosPermission.Reminders,
            IosPermission.FocusStatus,
        ).forEach { permission -> controller.check(permission) }
    }

    @Test
    fun `each iOS-only entry is unavailable without its purpose string and a request shows nothing`() = runTest {
        val remindersKey: String = if (isIosAtLeast(17)) "NSRemindersFullAccessUsageDescription" else "NSRemindersUsageDescription"
        mapOf(
            IosPermission.Tracking to "NSUserTrackingUsageDescription",
            IosPermission.SpeechRecognition to "NSSpeechRecognitionUsageDescription",
            IosPermission.Reminders to remindersKey,
            IosPermission.FocusStatus to "NSFocusStatusUsageDescription",
        ).forEach { (permission, key) ->
            val controller: PermissionController = controller(hasKey = { it != key })

            assertEquals(PermissionStatus.Unavailable, controller.check(permission), "$permission without $key")
            assertEquals(PermissionStatus.Unavailable, controller.request(permission), "$permission without $key")
        }
    }

    @Test
    fun `bluetooth advertising reports the one Bluetooth authorization`() = runTest {
        val controller: PermissionController = controller()

        assertEquals(controller.check(CommonPermission.BluetoothConnect), controller.check(CommonPermission.BluetoothAdvertise))
    }

    @Test
    fun `each new entry is unavailable without its own purpose string and a request shows nothing`() = runTest {
        val calendarKey: String = if (isIosAtLeast(17)) "NSCalendarsFullAccessUsageDescription" else "NSCalendarsUsageDescription"
        val writeOnlyKey: String =
            if (isIosAtLeast(17)) "NSCalendarsWriteOnlyAccessUsageDescription" else "NSCalendarsUsageDescription"
        mapOf(
            CommonPermission.BluetoothAdvertise to "NSBluetoothAlwaysUsageDescription",
            CommonPermission.PhotoLibrary to "NSPhotoLibraryUsageDescription",
            CommonPermission.PhotoLibraryAdd to "NSPhotoLibraryAddUsageDescription",
            CommonPermission.Contacts to "NSContactsUsageDescription",
            CommonPermission.Calendar to calendarKey,
            CommonPermission.CalendarWriteOnly to writeOnlyKey,
            CommonPermission.MotionActivity to "NSMotionUsageDescription",
        ).forEach { (permission, key) ->
            // A request reaching the platform here would terminate the test process.
            val controller: PermissionController = controller(hasKey = { it != key })

            assertEquals(PermissionStatus.Unavailable, controller.check(permission), "$permission without $key")
            assertEquals(PermissionStatus.Unavailable, controller.request(permission), "$permission without $key")
        }
    }

    @Test
    fun `the photo picker for a limited library shows nothing when the library is not limited`() = runTest {
        // A test bundle has no photo library authorization at all.
        assertFalse(presentLimitedPhotoLibraryPicker(UIViewController()))
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
