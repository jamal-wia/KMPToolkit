@file:OptIn(ExperimentalForeignApi::class, ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import platform.AVFAudio.AVAudioSessionRecordPermission
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSessionRecordPermissionUndetermined
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatus
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusAuthorized
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusDenied
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusNotDetermined
import platform.Contacts.CNAuthorizationStatus
import platform.Contacts.CNAuthorizationStatusAuthorized
import platform.Contacts.CNAuthorizationStatusDenied
import platform.Contacts.CNAuthorizationStatusNotDetermined
import platform.CoreBluetooth.CBManagerAuthorization
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreMotion.CMAuthorizationStatus
import platform.CoreMotion.CMAuthorizationStatusAuthorized
import platform.CoreMotion.CMAuthorizationStatusDenied
import platform.CoreMotion.CMAuthorizationStatusNotDetermined
import platform.EventKit.EKAuthorizationStatus
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKAuthorizationStatusNotDetermined
import platform.Foundation.NSError
import platform.Intents.INFocusStatusAuthorizationStatus
import platform.Intents.INFocusStatusAuthorizationStatusAuthorized
import platform.Intents.INFocusStatusAuthorizationStatusDenied
import platform.Intents.INFocusStatusAuthorizationStatusNotDetermined
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatus
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusAuthorized
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusDenied
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusNotDetermined
import platform.Photos.PHAccessLevel
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.UserNotifications.UNAuthorizationOptions
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusNotDetermined

/** A value cell that is safe to read and write from any thread (a [MutableStateFlow] underneath). */
class Cell<T>(initial: T) {
    private val state: MutableStateFlow<T> = MutableStateFlow(initial)
    var value: T
        get() = state.value
        set(next) {
            state.value = next
        }

    fun update(transform: (T) -> T) = state.update(transform)
}

/** One prompt a [ChaosIosAuthorizationPort] is holding: answer it with [answer], once or several times. */
class HeldPrompt(val name: String, private val complete: (Boolean) -> Unit, private val raw: (Boolean) -> Unit) {
    /** The user answers: the status flips and the completion runs. */
    fun answer(granted: Boolean) = complete(granted)

    /** The platform delivers the completion again (or late) without any status change. */
    fun redeliver(granted: Boolean) = raw(granted)
}

/**
 * A thread-safe scripted [IosAuthorizationPort] for the round 9 storm tests: raw statuses in [Cell]s, a counter per
 * call name, the number of prompts in flight (and its maximum), and three ways to answer a prompt:
 *
 * - automatically (the default), the way [autoGrant] says, flipping the status as a real prompt would;
 * - held ([hold]): a callback request keeps its completion as a [HeldPrompt] and a suspending request waits on a gate
 *   the test opens with [openGates]; every prompt can then be answered late, twice, from another thread or never;
 * - the notification status read can be left unanswered ([hangNotificationReads]) and answered by hand.
 *
 * Nothing here touches the platform and nothing waits on the main queue.
 */
class ChaosIosAuthorizationPort : IosAuthorizationPort {

    private val counts: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val inFlight: Cell<Int> = Cell(0)
    val maxInFlight: Cell<Int> = Cell(0)

    val autoGrant: Cell<Boolean> = Cell(true)
    val hold: Cell<Boolean> = Cell(false)
    val hangNotificationReads: Cell<Boolean> = Cell(false)
    val throwOn: Cell<String?> = Cell(null)

    val heldPrompts: Cell<List<HeldPrompt>> = Cell(emptyList())
    val heldNotificationReads: Cell<List<(UNAuthorizationStatus?) -> Unit>> = Cell(emptyList())
    private val gates: Cell<List<Pair<String, CompletableDeferred<Boolean>>>> = Cell(emptyList())

    val notification: Cell<UNAuthorizationStatus?> = Cell(UNAuthorizationStatusNotDetermined)
    val recordState: Cell<AVAudioSessionRecordPermission> = Cell(AVAudioSessionRecordPermissionUndetermined)
    val camera: Cell<AVAuthorizationStatus> = Cell(AVAuthorizationStatusNotDetermined)
    val location: Cell<CLAuthorizationStatus> = Cell(kCLAuthorizationStatusNotDetermined)
    val media: Cell<MPMediaLibraryAuthorizationStatus> = Cell(MPMediaLibraryAuthorizationStatusNotDetermined)
    val bluetooth: Cell<CBManagerAuthorization> = Cell(CBManagerAuthorizationNotDetermined)
    val photo: Cell<PHAuthorizationStatus> = Cell(PHAuthorizationStatusNotDetermined)
    val contacts: Cell<CNAuthorizationStatus> = Cell(CNAuthorizationStatusNotDetermined)
    val calendar: Cell<EKAuthorizationStatus> = Cell(EKAuthorizationStatusNotDetermined)
    val reminders: Cell<EKAuthorizationStatus> = Cell(EKAuthorizationStatusNotDetermined)
    val motion: Cell<CMAuthorizationStatus> = Cell(CMAuthorizationStatusNotDetermined)
    val tracking: Cell<ATTrackingManagerAuthorizationStatus> = Cell(ATTrackingManagerAuthorizationStatusNotDetermined)
    val speech: Cell<SFSpeechRecognizerAuthorizationStatus> =
        Cell(SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined)
    val focus: Cell<INFocusStatusAuthorizationStatus> = Cell(INFocusStatusAuthorizationStatusNotDetermined)
    private val upgradeAsked: Cell<Boolean> = Cell(false)

    private val listeners: Cell<List<Pair<Int, () -> Unit>>> = Cell(emptyList())
    private val listenerIds: Cell<Int> = Cell(0)
    val registered: Cell<Int> = Cell(0)
    val unregistered: Cell<Int> = Cell(0)
    val openedUrls: Cell<List<String>> = Cell(emptyList())
    val openResult: Cell<Boolean> = Cell(true)

    fun count(name: String): Int = counts.value[name] ?: 0

    /** How many prompts were raised so far, whatever the permission. */
    fun promptCount(): Int = counts.value.filterKeys { it.startsWith("request") }.values.sum()

    val liveListeners: Int get() = listeners.value.size

    /** Fires every registered did-become-active listener, as the app returning to the foreground would. */
    fun activate() {
        listeners.value.forEach { (_, listener) -> listener() }
    }

    /** Opens every gate of a suspending request that is waiting, with [granted]. */
    fun openGates(granted: Boolean) {
        val open: List<Pair<String, CompletableDeferred<Boolean>>> = gates.value
        gates.value = emptyList()
        open.forEach { (_, gate) -> gate.complete(granted) }
    }

    val waitingGates: Int get() = gates.value.size

    private fun record(name: String) {
        counts.update { it + (name to ((it[name] ?: 0) + 1)) }
        if (throwOn.value == name) throw IllegalStateException("scripted failure of $name")
    }

    private fun enter() {
        val now: Int = inFlight.let { cell ->
            var result = 0
            cell.update { current -> (current + 1).also { result = it } }
            result
        }
        maxInFlight.update { maxOf(it, now) }
    }

    private fun leave() {
        inFlight.update { it - 1 }
    }

    /** What the user did to the prompt of [name]: flips the matching status like the real prompt would. */
    private fun applyAnswer(name: String, granted: Boolean) {
        when (name) {
            "requestNotifications" -> notification.value =
                if (granted) UNAuthorizationStatusAuthorized else UNAuthorizationStatusDenied
            "requestRecordPermission" -> recordState.value =
                if (granted) AVAudioSessionRecordPermissionGranted else AVAudioSessionRecordPermissionDenied
            "requestCameraAccess" -> camera.value =
                if (granted) AVAuthorizationStatusAuthorized else AVAuthorizationStatusDenied
            "requestWhenInUse" -> location.value =
                if (granted) kCLAuthorizationStatusAuthorizedWhenInUse else kCLAuthorizationStatusDenied
            "requestAlways" -> {
                upgradeAsked.value = true
                if (granted) location.value = kCLAuthorizationStatusAuthorizedAlways
            }
            "requestMediaLibrary" -> media.value =
                if (granted) MPMediaLibraryAuthorizationStatusAuthorized else MPMediaLibraryAuthorizationStatusDenied
            "requestBluetooth" -> bluetooth.value =
                if (granted) CBManagerAuthorizationAllowedAlways else CBManagerAuthorizationDenied
            "requestPhotoLibrary" -> photo.value =
                if (granted) PHAuthorizationStatusAuthorized else PHAuthorizationStatusDenied
            "requestContacts" -> contacts.value =
                if (granted) CNAuthorizationStatusAuthorized else CNAuthorizationStatusDenied
            "requestCalendar" -> calendar.value =
                if (granted) EKAuthorizationStatusFullAccess else EKAuthorizationStatusDenied
            "requestReminders" -> reminders.value =
                if (granted) EKAuthorizationStatusFullAccess else EKAuthorizationStatusDenied
            "requestMotionActivity" -> motion.value =
                if (granted) CMAuthorizationStatusAuthorized else CMAuthorizationStatusDenied
            "requestTracking" -> tracking.value =
                if (granted) ATTrackingManagerAuthorizationStatusAuthorized else ATTrackingManagerAuthorizationStatusDenied
            "requestSpeechRecognition" -> speech.value =
                if (granted) SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized
                else SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusDenied
            "requestFocusStatus" -> focus.value =
                if (granted) INFocusStatusAuthorizationStatusAuthorized else INFocusStatusAuthorizationStatusDenied
        }
    }

    private suspend fun suspendingRequest(name: String) {
        record(name)
        enter()
        try {
            val granted: Boolean = if (hold.value) {
                val gate: CompletableDeferred<Boolean> = CompletableDeferred()
                gates.update { it + (name to gate) }
                gate.await()
            } else {
                yield()
                autoGrant.value
            }
            applyAnswer(name, granted)
        } finally {
            leave()
        }
    }

    private fun callbackRequest(name: String, completion: (Boolean) -> Unit) {
        record(name)
        enter()
        val prompt = HeldPrompt(
            name,
            complete = { granted: Boolean ->
                applyAnswer(name, granted)
                leave()
                completion(granted)
            },
            raw = completion,
        )
        if (hold.value) {
            heldPrompts.update { it + prompt }
        } else {
            prompt.answer(autoGrant.value)
        }
    }

    override fun notificationAuthorization(completion: (UNAuthorizationStatus?) -> Unit) {
        record("notificationAuthorization")
        if (hangNotificationReads.value) {
            heldNotificationReads.update { it + completion }
        } else {
            completion(notification.value)
        }
    }

    override fun requestNotifications(options: UNAuthorizationOptions, completion: (NSError?) -> Unit) =
        callbackRequest("requestNotifications") { completion(null) }

    override fun recordPermission(): AVAudioSessionRecordPermission {
        record("recordPermission")
        return recordState.value
    }

    override fun requestRecordPermission(completion: (Boolean) -> Unit) =
        callbackRequest("requestRecordPermission", completion)

    override fun cameraAuthorization(): AVAuthorizationStatus {
        record("cameraAuthorization")
        return camera.value
    }

    override fun requestCameraAccess(completion: (Boolean) -> Unit) =
        callbackRequest("requestCameraAccess", completion)

    override fun locationAuthorization(): CLAuthorizationStatus {
        record("locationAuthorization")
        return location.value
    }

    override fun isLocationAccuracyReduced(): Boolean = false

    override val alwaysUpgradeAsked: Boolean get() = upgradeAsked.value

    override suspend fun requestWhenInUse(): CLAuthorizationStatus {
        suspendingRequest("requestWhenInUse")
        return location.value
    }

    override suspend fun requestAlways(): CLAuthorizationStatus {
        suspendingRequest("requestAlways")
        return location.value
    }

    override fun mediaLibraryAuthorization(): MPMediaLibraryAuthorizationStatus {
        record("mediaLibraryAuthorization")
        return media.value
    }

    override suspend fun requestMediaLibrary(): MPMediaLibraryAuthorizationStatus {
        suspendingRequest("requestMediaLibrary")
        return media.value
    }

    override fun bluetoothAuthorization(): CBManagerAuthorization {
        record("bluetoothAuthorization")
        return bluetooth.value
    }

    override suspend fun requestBluetooth(): CBManagerAuthorization {
        suspendingRequest("requestBluetooth")
        return bluetooth.value
    }

    override fun photoLibraryAuthorization(level: PHAccessLevel): PHAuthorizationStatus {
        record("photoLibraryAuthorization")
        return photo.value
    }

    override suspend fun requestPhotoLibrary(level: PHAccessLevel): PHAuthorizationStatus {
        suspendingRequest("requestPhotoLibrary")
        return photo.value
    }

    override fun contactsAuthorization(): CNAuthorizationStatus {
        record("contactsAuthorization")
        return contacts.value
    }

    override suspend fun requestContacts(onError: (NSError) -> Unit) = suspendingRequest("requestContacts")

    override fun calendarAuthorization(): EKAuthorizationStatus {
        record("calendarAuthorization")
        return calendar.value
    }

    override fun remindersAuthorization(): EKAuthorizationStatus {
        record("remindersAuthorization")
        return reminders.value
    }

    override suspend fun requestCalendar(writeOnly: Boolean, onError: (NSError) -> Unit) =
        suspendingRequest("requestCalendar")

    override suspend fun requestReminders(onError: (NSError) -> Unit) = suspendingRequest("requestReminders")

    override fun motionAuthorization(): CMAuthorizationStatus {
        record("motionAuthorization")
        return motion.value
    }

    override fun isMotionActivityAvailable(): Boolean = true

    override suspend fun requestMotionActivity() = suspendingRequest("requestMotionActivity")

    override fun trackingAuthorization(): ATTrackingManagerAuthorizationStatus {
        record("trackingAuthorization")
        return tracking.value
    }

    override suspend fun requestTracking(): ATTrackingManagerAuthorizationStatus {
        suspendingRequest("requestTracking")
        return tracking.value
    }

    override fun speechRecognitionAuthorization(): SFSpeechRecognizerAuthorizationStatus {
        record("speechRecognitionAuthorization")
        return speech.value
    }

    override suspend fun requestSpeechRecognition(): SFSpeechRecognizerAuthorizationStatus {
        suspendingRequest("requestSpeechRecognition")
        return speech.value
    }

    override fun focusStatusAuthorization(): INFocusStatusAuthorizationStatus {
        record("focusStatusAuthorization")
        return focus.value
    }

    override suspend fun requestFocusStatus(): INFocusStatusAuthorizationStatus {
        suspendingRequest("requestFocusStatus")
        return focus.value
    }

    override fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
        record("addBecameActiveListener")
        var id = 0
        listenerIds.update { (it + 1).also { next -> id = next } }
        listeners.update { it + (id to onActive) }
        registered.update { it + 1 }
        return {
            listeners.update { current -> current.filterNot { (listenerId, _) -> listenerId == id } }
            unregistered.update { it + 1 }
        }
    }

    override fun openSettingsUrl(urlString: String): Boolean {
        record("openSettingsUrl")
        openedUrls.update { it + urlString }
        return openResult.value
    }
}

/**
 * What the user (or Settings) leaves the platform with for [permission]: [state] 0 not determined, 1 granted,
 * 2 denied. Entries that share one platform authorization write the same cell.
 */
fun ChaosIosAuthorizationPort.setState(permission: Permission, state: Int) {
    val on: Boolean = state == 1
    val off: Boolean = state == 2
    when (permission) {
        CommonPermission.Notifications -> notification.value = when {
            on -> UNAuthorizationStatusAuthorized
            off -> UNAuthorizationStatusDenied
            else -> UNAuthorizationStatusNotDetermined
        }
        CommonPermission.Microphone -> recordState.value = when {
            on -> AVAudioSessionRecordPermissionGranted
            off -> AVAudioSessionRecordPermissionDenied
            else -> AVAudioSessionRecordPermissionUndetermined
        }
        CommonPermission.Camera -> camera.value = when {
            on -> AVAuthorizationStatusAuthorized
            off -> AVAuthorizationStatusDenied
            else -> AVAuthorizationStatusNotDetermined
        }
        CommonPermission.Location -> location.value = when {
            on -> kCLAuthorizationStatusAuthorizedWhenInUse
            off -> kCLAuthorizationStatusDenied
            else -> kCLAuthorizationStatusNotDetermined
        }
        CommonPermission.LocationBackground -> location.value = when {
            on -> kCLAuthorizationStatusAuthorizedAlways
            off -> kCLAuthorizationStatusDenied
            else -> kCLAuthorizationStatusNotDetermined
        }
        CommonPermission.AudioLibrary -> media.value = when {
            on -> MPMediaLibraryAuthorizationStatusAuthorized
            off -> MPMediaLibraryAuthorizationStatusDenied
            else -> MPMediaLibraryAuthorizationStatusNotDetermined
        }
        CommonPermission.BluetoothConnect, CommonPermission.BluetoothScan, CommonPermission.BluetoothAdvertise ->
            bluetooth.value = when {
                on -> CBManagerAuthorizationAllowedAlways
                off -> CBManagerAuthorizationDenied
                else -> CBManagerAuthorizationNotDetermined
            }
        CommonPermission.PhotoLibrary, CommonPermission.PhotoLibraryAdd -> photo.value = when {
            on -> PHAuthorizationStatusAuthorized
            off -> PHAuthorizationStatusDenied
            else -> PHAuthorizationStatusNotDetermined
        }
        CommonPermission.Contacts -> contacts.value = when {
            on -> CNAuthorizationStatusAuthorized
            off -> CNAuthorizationStatusDenied
            else -> CNAuthorizationStatusNotDetermined
        }
        CommonPermission.Calendar, CommonPermission.CalendarWriteOnly -> calendar.value = when {
            on -> EKAuthorizationStatusFullAccess
            off -> EKAuthorizationStatusDenied
            else -> EKAuthorizationStatusNotDetermined
        }
        CommonPermission.MotionActivity -> motion.value = when {
            on -> CMAuthorizationStatusAuthorized
            off -> CMAuthorizationStatusDenied
            else -> CMAuthorizationStatusNotDetermined
        }
        IosPermission.Tracking -> tracking.value = when {
            on -> ATTrackingManagerAuthorizationStatusAuthorized
            off -> ATTrackingManagerAuthorizationStatusDenied
            else -> ATTrackingManagerAuthorizationStatusNotDetermined
        }
        IosPermission.SpeechRecognition -> speech.value = when {
            on -> SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized
            off -> SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusDenied
            else -> SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined
        }
        IosPermission.Reminders -> reminders.value = when {
            on -> EKAuthorizationStatusFullAccess
            off -> EKAuthorizationStatusDenied
            else -> EKAuthorizationStatusNotDetermined
        }
        IosPermission.FocusStatus -> focus.value = when {
            on -> INFocusStatusAuthorizationStatusAuthorized
            off -> INFocusStatusAuthorizationStatusDenied
            else -> INFocusStatusAuthorizationStatusNotDetermined
        }
        else -> error("no script for $permission")
    }
}

/** The status the controller must report for [permission] given what the platform holds now (reduced accuracy never set). */
fun ChaosIosAuthorizationPort.expectedStatus(permission: Permission): PermissionStatus = when (permission) {
    CommonPermission.Notifications -> notificationStatus(notification.value)
    CommonPermission.Microphone -> recordPermissionStatus(recordState.value)
    CommonPermission.Camera -> captureStatus(camera.value)
    CommonPermission.Location -> foregroundLocationStatus(location.value, false)
    CommonPermission.LocationBackground -> backgroundLocationStatus(location.value, alwaysUpgradeAsked, false)
    CommonPermission.AudioLibrary -> mediaLibraryStatus(media.value)
    CommonPermission.BluetoothConnect, CommonPermission.BluetoothScan, CommonPermission.BluetoothAdvertise ->
        bluetoothStatus(bluetooth.value)
    CommonPermission.PhotoLibrary, CommonPermission.PhotoLibraryAdd -> photoLibraryStatus(photo.value)
    CommonPermission.Contacts -> contactsStatus(contacts.value)
    CommonPermission.Calendar -> calendarStatus(calendar.value, writeOnly = false)
    CommonPermission.CalendarWriteOnly -> calendarStatus(calendar.value, writeOnly = true)
    CommonPermission.MotionActivity -> motionActivityStatus(motion.value, true)
    IosPermission.Tracking -> trackingStatus(tracking.value)
    IosPermission.SpeechRecognition -> speechRecognitionStatus(speech.value)
    IosPermission.Reminders -> remindersStatus(reminders.value)
    IosPermission.FocusStatus -> focusStatusStatus(focus.value)
    else -> PermissionStatus.Unavailable
}
