@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import kotlin.coroutines.resume
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorization
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFAudio.AVAudioSessionRecordPermission
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AppTrackingTransparency.ATTrackingManager
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatus
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusAuthorized
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusDenied
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusRestricted
import platform.Contacts.CNAuthorizationStatus
import platform.Contacts.CNAuthorizationStatusAuthorized
import platform.Contacts.CNAuthorizationStatusDenied
import platform.Contacts.CNAuthorizationStatusLimited
import platform.Contacts.CNAuthorizationStatusRestricted
import platform.Contacts.CNContactStore
import platform.Contacts.CNEntityType
import platform.CoreMotion.CMAuthorizationStatus
import platform.CoreMotion.CMAuthorizationStatusAuthorized
import platform.CoreMotion.CMAuthorizationStatusDenied
import platform.CoreMotion.CMAuthorizationStatusRestricted
import platform.CoreMotion.CMMotionActivityManager
import platform.EventKit.EKAuthorizationStatus
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKAuthorizationStatusRestricted
import platform.EventKit.EKAuthorizationStatusWriteOnly
import platform.EventKit.EKEntityType
import platform.EventKit.EKEventStore
import platform.Foundation.NSBundle
import platform.Intents.INFocusStatusAuthorizationStatus
import platform.Intents.INFocusStatusAuthorizationStatusAuthorized
import platform.Intents.INFocusStatusAuthorizationStatusDenied
import platform.Intents.INFocusStatusAuthorizationStatusRestricted
import platform.Intents.INFocusStatusCenter
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Photos.PHAccessLevel
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusRestricted
import platform.Photos.PHPhotoLibrary
import platform.Foundation.NSNotification
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.MediaPlayer.MPMediaLibrary
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatus
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusAuthorized
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusDenied
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusRestricted
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.darwin.DISPATCH_TIME_NOW

// --- Status mappings ----------------------------------------------------------------------------
//
// Pure functions of the platform's own status value, so every branch is testable on a simulator
// without an authorization prompt.

/**
 * [CommonPermission.Location]: "when in use" or "always" is granted — [PermissionStatus.Limited] when the
 * user chose reduced accuracy ([reducedAccuracy], iOS 14+). Restricted — a parental control or an MDM
 * profile — is [PermissionStatus.Restricted].
 */
internal fun foregroundLocationStatus(
    status: CLAuthorizationStatus,
    reducedAccuracy: Boolean = false,
): PermissionStatus = when (status) {
    kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse ->
        if (reducedAccuracy) PermissionStatus.Limited else PermissionStatus.Granted
    kCLAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    kCLAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

/**
 * [CommonPermission.LocationBackground]: only "always" is granted ([PermissionStatus.Limited] with reduced
 * accuracy). "When in use" is a refusal the app may still ask about once more — iOS can offer the
 * upgrade to "always" — which is exactly what `Denied(explainFirst = true)` says; once that upgrade has
 * been asked for ([upgradeAsked]) and the status stayed "when in use", iOS will not offer it again and
 * only settings can change it.
 */
internal fun backgroundLocationStatus(
    status: CLAuthorizationStatus,
    upgradeAsked: Boolean = false,
    reducedAccuracy: Boolean = false,
): PermissionStatus = when (status) {
    kCLAuthorizationStatusAuthorizedAlways ->
        if (reducedAccuracy) PermissionStatus.Limited else PermissionStatus.Granted
    kCLAuthorizationStatusAuthorizedWhenInUse ->
        if (upgradeAsked) PermissionStatus.PermanentlyDenied else PermissionStatus.Denied(explainFirst = true)
    kCLAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    kCLAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

internal fun mediaLibraryStatus(status: MPMediaLibraryAuthorizationStatus): PermissionStatus = when (status) {
    MPMediaLibraryAuthorizationStatusAuthorized -> PermissionStatus.Granted
    MPMediaLibraryAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    MPMediaLibraryAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

internal fun bluetoothStatus(authorization: CBManagerAuthorization): PermissionStatus = when (authorization) {
    CBManagerAuthorizationAllowedAlways -> PermissionStatus.Granted
    CBManagerAuthorizationDenied -> PermissionStatus.PermanentlyDenied
    CBManagerAuthorizationRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

/**
 * [CommonPermission.Notifications]: provisional authorization — delivered quietly, without alerts — is
 * [PermissionStatus.Limited], and a request from there shows the full prompt. Ephemeral (an App Clip's)
 * is granted for the clip's lifetime.
 */
internal fun notificationStatus(status: UNAuthorizationStatus?): PermissionStatus = when (status) {
    UNAuthorizationStatusAuthorized, UNAuthorizationStatusEphemeral -> PermissionStatus.Granted
    UNAuthorizationStatusProvisional -> PermissionStatus.Limited
    UNAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    else -> PermissionStatus.NotDetermined
}

/** Microphone: iOS has no "restricted" record permission. */
internal fun recordPermissionStatus(permission: AVAudioSessionRecordPermission): PermissionStatus = when (permission) {
    AVAudioSessionRecordPermissionGranted -> PermissionStatus.Granted
    AVAudioSessionRecordPermissionDenied -> PermissionStatus.PermanentlyDenied
    else -> PermissionStatus.NotDetermined
}

/** Camera — and every `AVAuthorizationStatus`. */
internal fun captureStatus(status: AVAuthorizationStatus): PermissionStatus = when (status) {
    AVAuthorizationStatusAuthorized -> PermissionStatus.Granted
    AVAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    AVAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

/**
 * [CommonPermission.PhotoLibrary] and [CommonPermission.PhotoLibraryAdd]: a limited library — the
 * items the user picked — is [PermissionStatus.Limited]. iOS reports it only for the read-write level.
 */
internal fun photoLibraryStatus(status: PHAuthorizationStatus): PermissionStatus = when (status) {
    PHAuthorizationStatusAuthorized -> PermissionStatus.Granted
    PHAuthorizationStatusLimited -> PermissionStatus.Limited
    PHAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    PHAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

/** [CommonPermission.Contacts]: limited access (iOS 18+), the contacts the user picked, is [PermissionStatus.Limited]. */
internal fun contactsStatus(status: CNAuthorizationStatus): PermissionStatus = when (status) {
    CNAuthorizationStatusAuthorized -> PermissionStatus.Granted
    CNAuthorizationStatusLimited -> PermissionStatus.Limited
    CNAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    CNAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

/**
 * [CommonPermission.Calendar] and, with [writeOnly], [CommonPermission.CalendarWriteOnly]. Below iOS 17
 * the one "authorized" value is full access (`EKAuthorizationStatusAuthorized` is
 * `EKAuthorizationStatusFullAccess`). Write-only access is all [CommonPermission.CalendarWriteOnly]
 * asks for, and a [PermissionStatus.Limited] grant of [CommonPermission.Calendar].
 */
internal fun calendarStatus(status: EKAuthorizationStatus, writeOnly: Boolean): PermissionStatus = when (status) {
    EKAuthorizationStatusFullAccess -> PermissionStatus.Granted
    EKAuthorizationStatusWriteOnly -> if (writeOnly) PermissionStatus.Granted else PermissionStatus.Limited
    EKAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    EKAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

/** [CommonPermission.MotionActivity]: a device without the hardware ([available] `false`) is unavailable. */
internal fun motionActivityStatus(status: CMAuthorizationStatus, available: Boolean): PermissionStatus = when {
    !available -> PermissionStatus.Unavailable
    status == CMAuthorizationStatusAuthorized -> PermissionStatus.Granted
    status == CMAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    status == CMAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

/** [IosPermission.Reminders]: EventKit's reminders authorization has no write-only level. */
internal fun remindersStatus(status: EKAuthorizationStatus): PermissionStatus = when (status) {
    EKAuthorizationStatusFullAccess -> PermissionStatus.Granted
    EKAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    EKAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

internal fun trackingStatus(status: ATTrackingManagerAuthorizationStatus): PermissionStatus = when (status) {
    ATTrackingManagerAuthorizationStatusAuthorized -> PermissionStatus.Granted
    ATTrackingManagerAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    ATTrackingManagerAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

internal fun speechRecognitionStatus(status: SFSpeechRecognizerAuthorizationStatus): PermissionStatus = when (status) {
    SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized -> PermissionStatus.Granted
    SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

internal fun focusStatusStatus(status: INFocusStatusAuthorizationStatus): PermissionStatus = when (status) {
    INFocusStatusAuthorizationStatusAuthorized -> PermissionStatus.Granted
    INFocusStatusAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    INFocusStatusAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

/**
 * Whether this OS is at least [major].[minor]. The deployment target is iOS 15, so every API introduced
 * later is reached only behind this: sending its selector to an older system is a crash.
 */
internal fun isIosAtLeast(major: Long, minor: Long = 0): Boolean =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        majorVersion > major || (majorVersion == major && minorVersion >= minor)
    }

/** Whether the app's `Info.plist` has [key] — the purpose string a request for it requires. */
internal fun hasInfoPlistKey(key: String): Boolean = NSBundle.mainBundle.objectForInfoDictionaryKey(key) != null

// --- Location -----------------------------------------------------------------------------------

/**
 * Location authorization through `CLLocationManager`.
 *
 * A manager is created per request, on the main queue — CoreLocation delivers delegate callbacks on
 * the run loop of the thread that created the manager — and held, with its delegate, until the request
 * resolves: `CLLocationManager.delegate` is weak, so the set below is what keeps both alive.
 */
internal object LocationAuthorization {

    private val pending: MutableSet<AuthorizationDelegate> = mutableSetOf()

    /**
     * Whether this process has asked for the "always" upgrade from "when in use". iOS offers it at most
     * once per install and reports nothing when it declines, so this is the only way to tell "may still
     * be asked" from "settings only". Kept in memory: after a restart one more request finds out again.
     */
    @Volatile
    var alwaysUpgradeAsked: Boolean = false
        private set

    // Read on the calling thread: hopping to the main queue would deadlock a caller that blocks it.
    fun current(): CLAuthorizationStatus = CLLocationManager().authorizationStatus

    /** Whether the user chose approximate location (iOS 14+, so always available on this target). */
    fun isAccuracyReduced(): Boolean {
        val accuracy: CLAccuracyAuthorization = CLLocationManager().accuracyAuthorization
        return accuracy == CLAccuracyAuthorization.CLAccuracyAuthorizationReducedAccuracy
    }

    /**
     * Asks for "when in use" and suspends until iOS reports a decision. The dialog is guaranteed to
     * appear while the status is not determined, so the delegate is guaranteed to hear back.
     */
    suspend fun requestWhenInUse(): CLAuthorizationStatus = request { manager -> manager.requestWhenInUseAuthorization() }

    /**
     * Asks for "always". iOS shows the upgrade prompt **at most once** per install and reports nothing
     * when it declines to show it, so waiting for the delegate alone could wait forever. This waits for
     * whichever comes first: a changed status, or — if the app did not resign active shortly after the
     * call, meaning no prompt covered it — a short timeout; and if it did resign, until it is active
     * again.
     */
    suspend fun requestAlways(): CLAuthorizationStatus {
        val before: CLAuthorizationStatus = current()
        return awaitPossiblePrompt(
            start = { onChange ->
                startRequest(before, onChange) { manager -> manager.requestAlwaysAuthorization() }
            },
            fallback = ::current,
        ).also { if (before == kCLAuthorizationStatusAuthorizedWhenInUse) alwaysUpgradeAsked = true }
    }

    private suspend fun request(ask: (CLLocationManager) -> Unit): CLAuthorizationStatus =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val abandon: () -> Unit = startRequest(kCLAuthorizationStatusNotDetermined, { status ->
                    if (continuation.isActive) continuation.resume(status)
                }, ask)
                continuation.invokeOnCancellation {
                    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 0), dispatch_get_main_queue()) { abandon() }
                }
            }
        }

    /**
     * Must run on the main queue. Calls [onChange] once, with the first status that differs from
     * [before], and returns the function that abandons the request — releasing its manager — when the
     * caller stops waiting for other reasons.
     */
    private fun startRequest(
        before: CLAuthorizationStatus,
        onChange: (CLAuthorizationStatus) -> Unit,
        ask: (CLLocationManager) -> Unit,
    ): () -> Unit {
        val manager = CLLocationManager()
        val delegate = AuthorizationDelegate(before) { finished: AuthorizationDelegate, status ->
            release(finished)
            onChange(status)
        }
        delegate.manager = manager
        manager.delegate = delegate
        pending += delegate
        ask(manager)
        return { release(delegate) }
    }

    private fun release(delegate: AuthorizationDelegate) {
        delegate.manager?.delegate = null
        delegate.manager = null
        pending -= delegate
    }
}

private class AuthorizationDelegate(
    private val before: CLAuthorizationStatus,
    private val onDecided: (AuthorizationDelegate, CLAuthorizationStatus) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {

    var manager: CLLocationManager? = null
    private var decided: Boolean = false

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        // CoreLocation calls this once as soon as the delegate is set, with the status as it already
        // was; only a different status is the user's answer.
        val status: CLAuthorizationStatus = manager.authorizationStatus
        if (decided || status == before) return
        decided = true
        onDecided(this, status)
    }
}

// --- Media library ------------------------------------------------------------------------------

internal object MediaLibraryAuthorization {

    fun current(): MPMediaLibraryAuthorizationStatus = MPMediaLibrary.authorizationStatus()

    suspend fun request(): MPMediaLibraryAuthorizationStatus = suspendCancellableCoroutine { continuation ->
        MPMediaLibrary.requestAuthorization { status: MPMediaLibraryAuthorizationStatus ->
            if (continuation.isActive) continuation.resume(status)
        }
    }
}

// --- Bluetooth ----------------------------------------------------------------------------------

/**
 * Bluetooth authorization through `CBManager`.
 *
 * iOS has no "request Bluetooth permission" call: the prompt appears when the app first creates a
 * central manager, and the manager reports `centralManagerDidUpdateState` once the user has answered
 * (or at once, when there was nothing to ask). The manager is created with the power alert turned off —
 * asking for permission must not also nag the user to switch Bluetooth on.
 */
internal object BluetoothAuthorization {

    private val pending: MutableSet<BluetoothStateDelegate> = mutableSetOf()

    fun current(): CBManagerAuthorization = CBManager.authorization

    suspend fun request(): CBManagerAuthorization = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val delegate = BluetoothStateDelegate { finished: BluetoothStateDelegate ->
                finished.manager?.delegate = null
                finished.manager = null
                pending -= finished
                if (continuation.isActive) continuation.resume(CBManager.authorization)
            }
            pending += delegate
            delegate.manager = CBCentralManager(
                delegate = delegate,
                queue = null,
                options = mapOf<Any?, Any?>(CBCentralManagerOptionShowPowerAlertKey to false),
            )
            continuation.invokeOnCancellation {
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 0), dispatch_get_main_queue()) {
                    delegate.manager?.delegate = null
                    delegate.manager = null
                    pending -= delegate
                }
            }
        }
    }
}

private class BluetoothStateDelegate(
    private val onUpdated: (BluetoothStateDelegate) -> Unit,
) : NSObject(), CBCentralManagerDelegateProtocol {

    var manager: CBCentralManager? = null
    private var updated: Boolean = false

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        // A state update can arrive while the prompt is still on screen; only a decided authorization
        // answers the request.
        if (updated || CBManager.authorization == CBManagerAuthorizationNotDetermined) return
        updated = true
        onUpdated(this)
    }
}

// --- Photos, contacts, calendar, motion ---------------------------------------------------------

internal object PhotoLibraryAuthorization {

    fun current(level: PHAccessLevel): PHAuthorizationStatus = PHPhotoLibrary.authorizationStatusForAccessLevel(level)

    suspend fun request(level: PHAccessLevel): PHAuthorizationStatus = suspendCancellableCoroutine { continuation ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(level) { status: PHAuthorizationStatus ->
            if (continuation.isActive) continuation.resume(status)
        }
    }
}

internal object ContactsAuthorization {

    fun current(): CNAuthorizationStatus = CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts)

    /** Suspends until the user answers; the caller re-reads the status, which tells limited from full. */
    suspend fun request(onError: (NSError) -> Unit) {
        // Held until the completion runs: the store owns the pending request.
        val store = CNContactStore()
        suspendCancellableCoroutine { continuation ->
            store.requestAccessForEntityType(CNEntityType.CNEntityTypeContacts) { _: Boolean, error: NSError? ->
                if (error != null) onError(error)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}

internal object CalendarAuthorization {

    fun current(): EKAuthorizationStatus = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)

    fun currentReminders(): EKAuthorizationStatus =
        EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeReminder)

    /** Asks for full access to reminders (iOS 17+; the one reminders request below it). */
    suspend fun requestReminders(onError: (NSError) -> Unit) {
        val store = EKEventStore()
        suspendCancellableCoroutine { continuation ->
            val completion: (Boolean, NSError?) -> Unit = { _, error ->
                if (error != null) onError(error)
                if (continuation.isActive) continuation.resume(Unit)
            }
            if (isIosAtLeast(17)) {
                store.requestFullAccessToRemindersWithCompletion(completion)
            } else {
                store.requestAccessToEntityType(EKEntityType.EKEntityTypeReminder, completion)
            }
        }
    }

    /**
     * Asks for full or [writeOnly] access to events and suspends until the user answers. The split
     * requests exist from iOS 17; below it the one events request grants full access.
     */
    suspend fun request(writeOnly: Boolean, onError: (NSError) -> Unit) {
        val store = EKEventStore()
        suspendCancellableCoroutine { continuation ->
            val completion: (Boolean, NSError?) -> Unit = { _, error ->
                if (error != null) onError(error)
                if (continuation.isActive) continuation.resume(Unit)
            }
            when {
                !isIosAtLeast(17) -> store.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent, completion)
                writeOnly -> store.requestWriteOnlyAccessToEventsWithCompletion(completion)
                else -> store.requestFullAccessToEventsWithCompletion(completion)
            }
        }
    }
}

/**
 * Motion activity through `CMMotionActivityManager`, which has no "request" call: the prompt appears
 * on the first query for activity data. An empty query — from now to now — triggers it, and its
 * handler runs once the user has answered.
 */
internal object MotionActivityAuthorization {

    fun current(): CMAuthorizationStatus = CMMotionActivityManager.authorizationStatus()

    fun isAvailable(): Boolean = CMMotionActivityManager.isActivityAvailable()

    suspend fun request() {
        val manager = CMMotionActivityManager()
        suspendCancellableCoroutine { continuation ->
            val now = NSDate()
            manager.queryActivityStartingFromDate(now, toDate = now, toQueue = NSOperationQueue.mainQueue) { _, _ ->
                // A refusal arrives as an error; the caller re-reads the status either way.
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}

// --- iOS-only authorizations --------------------------------------------------------------------

internal object TrackingAuthorization {

    fun current(): ATTrackingManagerAuthorizationStatus = ATTrackingManager.trackingAuthorizationStatus()

    suspend fun request(): ATTrackingManagerAuthorizationStatus = suspendCancellableCoroutine { continuation ->
        ATTrackingManager.requestTrackingAuthorizationWithCompletionHandler { status: ATTrackingManagerAuthorizationStatus ->
            if (continuation.isActive) continuation.resume(status)
        }
    }
}

internal object SpeechRecognitionAuthorization {

    fun current(): SFSpeechRecognizerAuthorizationStatus = SFSpeechRecognizer.authorizationStatus()

    suspend fun request(): SFSpeechRecognizerAuthorizationStatus = suspendCancellableCoroutine { continuation ->
        SFSpeechRecognizer.requestAuthorization { status: SFSpeechRecognizerAuthorizationStatus ->
            if (continuation.isActive) continuation.resume(status)
        }
    }
}

internal object FocusStatusAuthorization {

    fun current(): INFocusStatusAuthorizationStatus = INFocusStatusCenter.defaultCenter.authorizationStatus

    suspend fun request(): INFocusStatusAuthorizationStatus = suspendCancellableCoroutine { continuation ->
        INFocusStatusCenter.defaultCenter.requestAuthorizationWithCompletionHandler { status: INFocusStatusAuthorizationStatus ->
            if (continuation.isActive) continuation.resume(status)
        }
    }
}

// --- App activity -------------------------------------------------------------------------------

/**
 * Registers [onActive] for every `UIApplicationDidBecomeActiveNotification`, delivered on the main
 * queue. Returns the function that unregisters it.
 */
internal fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
    val center: NSNotificationCenter = NSNotificationCenter.defaultCenter
    val observer: Any = center.addObserverForName(
        name = UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _: NSNotification? -> onActive() }
    return { center.removeObserver(observer) }
}

/**
 * Starts a request that may or may not put a system prompt on screen, and suspends until it resolves.
 *
 * [start] runs on the main queue, receives the callback for a decided status, and returns the function
 * that abandons the request. When no status
 * arrives, a prompt is inferred from the app resigning active within [PROMPT_GRACE_MILLIS] of the
 * call: if it did not, nothing was shown and [fallback] is read; if it did, [fallback] is read once the
 * app is active again.
 */
private suspend fun <T> awaitPossiblePrompt(
    start: (onChange: (T) -> Unit) -> () -> Unit,
    fallback: () -> T,
): T = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val center: NSNotificationCenter = NSNotificationCenter.defaultCenter
        val observers: MutableList<Any> = mutableListOf()
        var resigned = false
        var finished = false
        var abandon: () -> Unit = {}

        fun finish(value: T) {
            if (finished) return
            finished = true
            observers.forEach(center::removeObserver)
            abandon()
            if (continuation.isActive) continuation.resume(value)
        }

        observers += center.addObserverForName(
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? -> resigned = true }
        observers += center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? -> if (resigned) finish(fallback()) }

        abandon = start { value -> finish(value) }

        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, PROMPT_GRACE_MILLIS * NANOS_PER_MILLI),
            dispatch_get_main_queue(),
        ) {
            if (!resigned) finish(fallback())
        }
        continuation.invokeOnCancellation {
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 0), dispatch_get_main_queue()) {
                if (!finished) {
                    finished = true
                    observers.forEach(center::removeObserver)
                    abandon()
                }
            }
        }
    }
}

/** How long after an "always" request a system prompt has to take the app out of the active state. */
private const val PROMPT_GRACE_MILLIS: Long = 1_000

private const val NANOS_PER_MILLI: Long = 1_000_000
