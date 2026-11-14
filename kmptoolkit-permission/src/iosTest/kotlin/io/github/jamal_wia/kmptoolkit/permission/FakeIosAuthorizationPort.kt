@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import platform.AVFAudio.AVAudioSessionRecordPermission
import platform.AVFAudio.AVAudioSessionRecordPermissionUndetermined
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatus
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusNotDetermined
import platform.Contacts.CNAuthorizationStatus
import platform.Contacts.CNAuthorizationStatusNotDetermined
import platform.CoreBluetooth.CBManagerAuthorization
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreMotion.CMAuthorizationStatus
import platform.CoreMotion.CMAuthorizationStatusNotDetermined
import platform.EventKit.EKAuthorizationStatus
import platform.EventKit.EKAuthorizationStatusNotDetermined
import platform.Foundation.NSError
import platform.Intents.INFocusStatusAuthorizationStatus
import platform.Intents.INFocusStatusAuthorizationStatusNotDetermined
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatus
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusNotDetermined
import platform.Photos.PHAccessLevel
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.UserNotifications.UNAuthorizationOptions
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusNotDetermined

/** A [Logger] that keeps every message. */
class ListLogger : Logger {
    val messages: MutableList<String> = mutableListOf()
    override val tag: String = "ListLogger"
    override fun isLoggable(level: LogLevel): Boolean = true
    override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
        messages += message()
    }
}

/**
 * A scripted [IosAuthorizationPort]: every status is a field the test sets, every call is recorded by name in
 * [calls], a request runs the hook the test registered under its name (to flip a status the way the real prompt
 * would) and may be held — a callback request keeps its completion in [heldCompletions], a suspending one waits
 * on [gates] — to test serialization and cancellation. Nothing here touches the platform and nothing waits on the
 * main queue.
 */
class FakeIosAuthorizationPort : IosAuthorizationPort {

    val calls: MutableList<String> = mutableListOf()
    val hooks: MutableMap<String, () -> Unit> = mutableMapOf()
    val holds: MutableSet<String> = mutableSetOf()
    val heldCompletions: MutableMap<String, (Boolean) -> Unit> = mutableMapOf()
    val gates: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()

    var notification: UNAuthorizationStatus? = UNAuthorizationStatusNotDetermined
    var notificationError: NSError? = null
    val notificationOptionsAsked: MutableList<UNAuthorizationOptions> = mutableListOf()
    var recordState: AVAudioSessionRecordPermission = AVAudioSessionRecordPermissionUndetermined
    var microphoneGrant: Boolean = true
    var camera: AVAuthorizationStatus = AVAuthorizationStatusNotDetermined
    var cameraGrant: Boolean = true
    var location: CLAuthorizationStatus = kCLAuthorizationStatusNotDetermined
    var locationReduced: Boolean = false
    override var alwaysUpgradeAsked: Boolean = false
    var mediaLibrary: MPMediaLibraryAuthorizationStatus = MPMediaLibraryAuthorizationStatusNotDetermined
    var bluetooth: CBManagerAuthorization = CBManagerAuthorizationNotDetermined
    var photoReadWrite: PHAuthorizationStatus = PHAuthorizationStatusNotDetermined
    var photoAddOnly: PHAuthorizationStatus = PHAuthorizationStatusNotDetermined
    var contacts: CNAuthorizationStatus = CNAuthorizationStatusNotDetermined
    var contactsError: NSError? = null
    var calendar: EKAuthorizationStatus = EKAuthorizationStatusNotDetermined
    var reminders: EKAuthorizationStatus = EKAuthorizationStatusNotDetermined
    var motion: CMAuthorizationStatus = CMAuthorizationStatusNotDetermined
    var motionAvailable: Boolean = true
    var tracking: ATTrackingManagerAuthorizationStatus = ATTrackingManagerAuthorizationStatusNotDetermined
    var speech: SFSpeechRecognizerAuthorizationStatus =
        SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined
    var focus: INFocusStatusAuthorizationStatus = INFocusStatusAuthorizationStatusNotDetermined

    val becameActiveListeners: MutableList<() -> Unit> = mutableListOf()
    var unregistered: Int = 0
    val openedUrls: MutableList<String> = mutableListOf()
    var canOpenUrl: Boolean = true

    fun count(name: String): Int = calls.count { it == name }

    /** Fires every registered did-become-active listener, as the app returning to the foreground would. */
    fun activate() {
        becameActiveListeners.toList().forEach { it() }
    }

    private fun record(name: String) {
        calls += name
    }

    private fun runHook(name: String) {
        hooks[name]?.invoke()
    }

    private suspend fun suspendingRequest(name: String) {
        record(name)
        gates[name]?.await()
        runHook(name)
    }

    private fun callbackRequest(name: String, grant: Boolean, completion: (Boolean) -> Unit) {
        record(name)
        if (name in holds) {
            heldCompletions[name] = completion
        } else {
            runHook(name)
            completion(grant)
        }
    }

    override fun notificationAuthorization(completion: (UNAuthorizationStatus?) -> Unit) {
        record("notificationAuthorization")
        completion(notification)
    }

    override fun requestNotifications(options: UNAuthorizationOptions, completion: (NSError?) -> Unit) {
        record("requestNotifications")
        notificationOptionsAsked += options
        runHook("requestNotifications")
        completion(notificationError)
    }

    override fun recordPermission(): AVAudioSessionRecordPermission {
        record("recordPermission")
        return recordState
    }

    override fun requestRecordPermission(completion: (Boolean) -> Unit) =
        callbackRequest("requestRecordPermission", microphoneGrant, completion)

    override fun cameraAuthorization(): AVAuthorizationStatus {
        record("cameraAuthorization")
        return camera
    }

    override fun requestCameraAccess(completion: (Boolean) -> Unit) =
        callbackRequest("requestCameraAccess", cameraGrant, completion)

    override fun locationAuthorization(): CLAuthorizationStatus {
        record("locationAuthorization")
        return location
    }

    override fun isLocationAccuracyReduced(): Boolean = locationReduced

    override suspend fun requestWhenInUse(): CLAuthorizationStatus {
        suspendingRequest("requestWhenInUse")
        return location
    }

    override suspend fun requestAlways(): CLAuthorizationStatus {
        suspendingRequest("requestAlways")
        return location
    }

    override fun mediaLibraryAuthorization(): MPMediaLibraryAuthorizationStatus {
        record("mediaLibraryAuthorization")
        return mediaLibrary
    }

    override suspend fun requestMediaLibrary(): MPMediaLibraryAuthorizationStatus {
        suspendingRequest("requestMediaLibrary")
        return mediaLibrary
    }

    override fun bluetoothAuthorization(): CBManagerAuthorization {
        record("bluetoothAuthorization")
        return bluetooth
    }

    override suspend fun requestBluetooth(): CBManagerAuthorization {
        suspendingRequest("requestBluetooth")
        return bluetooth
    }

    override fun photoLibraryAuthorization(level: PHAccessLevel): PHAuthorizationStatus {
        record("photoLibraryAuthorization")
        return photoFor(level)
    }

    override suspend fun requestPhotoLibrary(level: PHAccessLevel): PHAuthorizationStatus {
        suspendingRequest("requestPhotoLibrary")
        return photoFor(level)
    }

    private fun photoFor(level: PHAccessLevel): PHAuthorizationStatus =
        if (level == platform.Photos.PHAccessLevelAddOnly) photoAddOnly else photoReadWrite

    override fun contactsAuthorization(): CNAuthorizationStatus {
        record("contactsAuthorization")
        return contacts
    }

    override suspend fun requestContacts(onError: (NSError) -> Unit) {
        suspendingRequest("requestContacts")
        contactsError?.let(onError)
    }

    override fun calendarAuthorization(): EKAuthorizationStatus {
        record("calendarAuthorization")
        return calendar
    }

    override fun remindersAuthorization(): EKAuthorizationStatus {
        record("remindersAuthorization")
        return reminders
    }

    override suspend fun requestCalendar(writeOnly: Boolean, onError: (NSError) -> Unit) {
        suspendingRequest("requestCalendar(writeOnly=$writeOnly)")
    }

    override suspend fun requestReminders(onError: (NSError) -> Unit) {
        suspendingRequest("requestReminders")
    }

    override fun motionAuthorization(): CMAuthorizationStatus {
        record("motionAuthorization")
        return motion
    }

    override fun isMotionActivityAvailable(): Boolean = motionAvailable

    override suspend fun requestMotionActivity() {
        suspendingRequest("requestMotionActivity")
    }

    override fun trackingAuthorization(): ATTrackingManagerAuthorizationStatus {
        record("trackingAuthorization")
        return tracking
    }

    override suspend fun requestTracking(): ATTrackingManagerAuthorizationStatus {
        suspendingRequest("requestTracking")
        return tracking
    }

    override fun speechRecognitionAuthorization(): SFSpeechRecognizerAuthorizationStatus {
        record("speechRecognitionAuthorization")
        return speech
    }

    override suspend fun requestSpeechRecognition(): SFSpeechRecognizerAuthorizationStatus {
        suspendingRequest("requestSpeechRecognition")
        return speech
    }

    override fun focusStatusAuthorization(): INFocusStatusAuthorizationStatus {
        record("focusStatusAuthorization")
        return focus
    }

    override suspend fun requestFocusStatus(): INFocusStatusAuthorizationStatus {
        suspendingRequest("requestFocusStatus")
        return focus
    }

    override fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
        record("addBecameActiveListener")
        becameActiveListeners += onActive
        return {
            unregistered++
            becameActiveListeners -= onActive
        }
    }

    override fun openSettingsUrl(urlString: String): Boolean {
        record("openSettingsUrl")
        openedUrls += urlString
        return canOpenUrl
    }
}
