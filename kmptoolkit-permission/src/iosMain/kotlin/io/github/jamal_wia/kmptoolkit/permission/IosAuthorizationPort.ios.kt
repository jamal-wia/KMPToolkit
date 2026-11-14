@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermission
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatus
import platform.Contacts.CNAuthorizationStatus
import platform.CoreBluetooth.CBManagerAuthorization
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreMotion.CMAuthorizationStatus
import platform.EventKit.EKAuthorizationStatus
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Intents.INFocusStatusAuthorizationStatus
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatus
import platform.Photos.PHAccessLevel
import platform.Photos.PHAuthorizationStatus
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.UIKit.UIApplication
import platform.UserNotifications.UNAuthorizationOptions
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Every call [IosPermissionController] makes into the platform, carrying the platform's own raw values: a test
 * seam, so the controller's logic (the request mutex, the batch de-duplication, the shared Bluetooth and
 * calendar authorizations, the limited-to-full upgrade, the two-step background location request, the
 * missing-purpose-string rule, the re-read after a request) runs against a scripted fake with no authorization
 * prompt. The mapping from a raw value to a [PermissionStatus] stays in the controller's pure functions.
 *
 * A one-to-one adapter, [SystemIosAuthorizations], is the only implementation in the library. Internal: it is
 * not API.
 *
 * @since 5.0.0
 */
internal interface IosAuthorizationPort {

    /** The notification authorization, answered asynchronously; `null` when the center reports no settings. */
    fun notificationAuthorization(completion: (UNAuthorizationStatus?) -> Unit)

    /** Asks for the notification [options]; [completion] runs with the platform's error, if any. */
    fun requestNotifications(options: UNAuthorizationOptions, completion: (NSError?) -> Unit)

    fun recordPermission(): AVAudioSessionRecordPermission

    /** Asks for the microphone; [completion] runs with whether it was granted. */
    fun requestRecordPermission(completion: (Boolean) -> Unit)

    fun cameraAuthorization(): AVAuthorizationStatus

    /** Asks for the camera; [completion] runs with whether it was granted. */
    fun requestCameraAccess(completion: (Boolean) -> Unit)

    fun locationAuthorization(): CLAuthorizationStatus

    fun isLocationAccuracyReduced(): Boolean

    /** Whether this process has asked for the "always" upgrade; the state itself stays in `LocationAuthorization`. */
    val alwaysUpgradeAsked: Boolean

    suspend fun requestWhenInUse(): CLAuthorizationStatus

    suspend fun requestAlways(): CLAuthorizationStatus

    fun mediaLibraryAuthorization(): MPMediaLibraryAuthorizationStatus

    suspend fun requestMediaLibrary(): MPMediaLibraryAuthorizationStatus

    fun bluetoothAuthorization(): CBManagerAuthorization

    suspend fun requestBluetooth(): CBManagerAuthorization

    fun photoLibraryAuthorization(level: PHAccessLevel): PHAuthorizationStatus

    suspend fun requestPhotoLibrary(level: PHAccessLevel): PHAuthorizationStatus

    fun contactsAuthorization(): CNAuthorizationStatus

    suspend fun requestContacts(onError: (NSError) -> Unit)

    fun calendarAuthorization(): EKAuthorizationStatus

    fun remindersAuthorization(): EKAuthorizationStatus

    suspend fun requestCalendar(writeOnly: Boolean, onError: (NSError) -> Unit)

    suspend fun requestReminders(onError: (NSError) -> Unit)

    fun motionAuthorization(): CMAuthorizationStatus

    fun isMotionActivityAvailable(): Boolean

    suspend fun requestMotionActivity()

    fun trackingAuthorization(): ATTrackingManagerAuthorizationStatus

    suspend fun requestTracking(): ATTrackingManagerAuthorizationStatus

    fun speechRecognitionAuthorization(): SFSpeechRecognizerAuthorizationStatus

    suspend fun requestSpeechRecognition(): SFSpeechRecognizerAuthorizationStatus

    fun focusStatusAuthorization(): INFocusStatusAuthorizationStatus

    suspend fun requestFocusStatus(): INFocusStatusAuthorizationStatus

    /** Registers [onActive] for every did-become-active notification; returns the function that unregisters it. */
    fun addBecameActiveListener(onActive: () -> Unit): () -> Unit

    /**
     * Opens the settings page at [urlString]: `false` when the string is no URL or cannot be opened on this
     * device, `true` when the open was handed to the main queue (not that the screen appeared).
     */
    fun openSettingsUrl(urlString: String): Boolean
}

/**
 * The real platform behind [IosAuthorizationPort]: each member delegates one to one to the singleton or the
 * system call [IosPermissionController] made directly before the port existed. The notification center and the
 * shared application are fetched on every call, never at construction, so building a controller in a test
 * binary touches neither.
 *
 * @since 5.0.0
 */
internal object SystemIosAuthorizations : IosAuthorizationPort {

    override fun notificationAuthorization(completion: (UNAuthorizationStatus?) -> Unit) {
        UNUserNotificationCenter.currentNotificationCenter()
            .getNotificationSettingsWithCompletionHandler { settings -> completion(settings?.authorizationStatus) }
    }

    override fun requestNotifications(options: UNAuthorizationOptions, completion: (NSError?) -> Unit) {
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(options) { _, error -> completion(error) }
    }

    override fun recordPermission(): AVAudioSessionRecordPermission = AVAudioSession.sharedInstance().recordPermission

    override fun requestRecordPermission(completion: (Boolean) -> Unit) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted: Boolean -> completion(granted) }
    }

    override fun cameraAuthorization(): AVAuthorizationStatus =
        AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)

    override fun requestCameraAccess(completion: (Boolean) -> Unit) {
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted: Boolean -> completion(granted) }
    }

    override fun locationAuthorization(): CLAuthorizationStatus = LocationAuthorization.current()

    override fun isLocationAccuracyReduced(): Boolean = LocationAuthorization.isAccuracyReduced()

    override val alwaysUpgradeAsked: Boolean get() = LocationAuthorization.alwaysUpgradeAsked

    override suspend fun requestWhenInUse(): CLAuthorizationStatus = LocationAuthorization.requestWhenInUse()

    override suspend fun requestAlways(): CLAuthorizationStatus = LocationAuthorization.requestAlways()

    override fun mediaLibraryAuthorization(): MPMediaLibraryAuthorizationStatus = MediaLibraryAuthorization.current()

    override suspend fun requestMediaLibrary(): MPMediaLibraryAuthorizationStatus = MediaLibraryAuthorization.request()

    override fun bluetoothAuthorization(): CBManagerAuthorization = BluetoothAuthorization.current()

    override suspend fun requestBluetooth(): CBManagerAuthorization = BluetoothAuthorization.request()

    override fun photoLibraryAuthorization(level: PHAccessLevel): PHAuthorizationStatus =
        PhotoLibraryAuthorization.current(level)

    override suspend fun requestPhotoLibrary(level: PHAccessLevel): PHAuthorizationStatus =
        PhotoLibraryAuthorization.request(level)

    override fun contactsAuthorization(): CNAuthorizationStatus = ContactsAuthorization.current()

    override suspend fun requestContacts(onError: (NSError) -> Unit) = ContactsAuthorization.request(onError)

    override fun calendarAuthorization(): EKAuthorizationStatus = CalendarAuthorization.current()

    override fun remindersAuthorization(): EKAuthorizationStatus = CalendarAuthorization.currentReminders()

    override suspend fun requestCalendar(writeOnly: Boolean, onError: (NSError) -> Unit) =
        CalendarAuthorization.request(writeOnly, onError)

    override suspend fun requestReminders(onError: (NSError) -> Unit) = CalendarAuthorization.requestReminders(onError)

    override fun motionAuthorization(): CMAuthorizationStatus = MotionActivityAuthorization.current()

    override fun isMotionActivityAvailable(): Boolean = MotionActivityAuthorization.isAvailable()

    override suspend fun requestMotionActivity() = MotionActivityAuthorization.request()

    override fun trackingAuthorization(): ATTrackingManagerAuthorizationStatus = TrackingAuthorization.current()

    override suspend fun requestTracking(): ATTrackingManagerAuthorizationStatus = TrackingAuthorization.request()

    override fun speechRecognitionAuthorization(): SFSpeechRecognizerAuthorizationStatus =
        SpeechRecognitionAuthorization.current()

    override suspend fun requestSpeechRecognition(): SFSpeechRecognizerAuthorizationStatus =
        SpeechRecognitionAuthorization.request()

    override fun focusStatusAuthorization(): INFocusStatusAuthorizationStatus = FocusStatusAuthorization.current()

    override suspend fun requestFocusStatus(): INFocusStatusAuthorizationStatus = FocusStatusAuthorization.request()

    override fun addBecameActiveListener(onActive: () -> Unit): () -> Unit =
        io.github.jamal_wia.kmptoolkit.permission.addBecameActiveListener(onActive)

    override fun openSettingsUrl(urlString: String): Boolean {
        val url: NSURL = NSURL.URLWithString(urlString) ?: return false
        val application: UIApplication = UIApplication.sharedApplication
        if (!application.canOpenURL(url)) return false
        // UIApplication is main-thread-only, and this may well be called from a background
        // coroutine, so the open is dispatched rather than attempted in place. `true` therefore
        // means "handed to UIKit", not "the settings screen actually opened".
        dispatch_async(dispatch_get_main_queue()) {
            application.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
        }
        return true
    }
}
