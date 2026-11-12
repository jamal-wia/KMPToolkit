package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.Foundation.NSError
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAccessLevelReadWrite
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationStatus

/**
 * Creates the iOS [PermissionController].
 *
 * It takes nothing but [options] and a logger, which is the whole reason the factory is per-platform
 * rather than an `expect fun`: iOS needs no context, no activity, and no storage. Every authorization it reads is
 * a value the OS itself keeps and reports, with "not determined" among the values, so there is nothing
 * for this library to remember — compare `AndroidPermissionConfig`.
 *
 * Two iOS facts shape everything here, and both are in
 * `docs/kmptoolkit-permission/05-platform-notes.md`:
 *
 * - **A refusal is final.** iOS shows its system dialog at most once per install. Anything the
 *   user declines therefore reports [PermissionStatus.PermanentlyDenied], and only the one-time
 *   "always" location upgrade reads [PermissionStatus.Denied]. Explain *before* calling
 *   [PermissionController.request], while the status is still [PermissionStatus.NotDetermined].
 * - **Every permission you request needs an `Info.plist` purpose string.** iOS terminates an app that
 *   requests one without it. This controller checks first: a permission whose key is missing reads
 *   [PermissionStatus.Unavailable], a request shows nothing, and the missing key is logged.
 *
 * It answers for every [CommonPermission] and every [IosPermission]; any other type is
 * [PermissionStatus.Unavailable].
 *
 * @param options how notifications are asked for.
 * @param logger where a rejected authorization request or a missing purpose string is reported.
 */
public fun createPermissionController(
    options: IosPermissionOptions = IosPermissionOptions(),
    logger: Logger = NoopLogger,
): PermissionController = IosPermissionController(logger, options, hasUsageDescription = ::hasInfoPlistKey)

/**
 * iOS's [PermissionController].
 *
 * @param hasUsageDescription whether the app's `Info.plist` has a key — a seam, so the "missing purpose
 *   string" path is testable in a test bundle that has none.
 * @param port every platform call the controller makes — a seam, so the controller's own logic is testable
 *   against a scripted fake; the real platform by default.
 */
internal class IosPermissionController(
    private val logger: Logger,
    private val options: IosPermissionOptions,
    private val hasUsageDescription: (String) -> Boolean,
    private val port: IosAuthorizationPort = SystemIosAuthorizations,
) : PermissionController {

    /** A permission whose request just resolved, so every [observe] of it re-reads at once. */
    private val requestsResolved: MutableSharedFlow<Permission> = MutableSharedFlow(extraBufferCapacity = 64)

    /** Held for the whole of every request, so two prompts never overlap. */
    private val requestMutex: Mutex = Mutex()

    /** Purpose strings already reported missing, so a status observed on every activation warns once. */
    private val reportedMissing: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    override suspend fun check(permission: Permission): PermissionStatus {
        val missing: String = missingUsageDescription(permission) ?: return PermissionStatus.Unavailable
        if (missing.isNotEmpty()) return PermissionStatus.Unavailable
        return currentStatus(permission)
    }

    override suspend fun request(permission: Permission): PermissionStatus =
        requestMutex.withLock { requestOne(permission) }

    override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
        requestMutex.withLock {
            // iOS has no shared dialog: one prompt after another, each waiting for the previous one.
            permissions.distinct().associateWith { permission -> requestOne(permission) }
        }

    override fun observe(permission: Permission): Flow<PermissionStatus> =
        callbackFlow {
            // UNDISPATCHED, and before the first tick: subscribed before any status read can start on
            // any dispatcher (the flow has no replay).
            launch(start = CoroutineStart.UNDISPATCHED) {
                requestsResolved.collect { resolved -> if (resolved.statusSource() == permission.statusSource()) send(Unit) }
            }
            val unregister: () -> Unit = port.addBecameActiveListener { trySend(Unit) }
            trySend(Unit)
            awaitClose { unregister() }
        }
            .conflate()
            .map { check(permission) }
            .distinctUntilChanged()

    override fun openSettings(permission: Permission): Boolean {
        if (missingUsageDescription(permission) == null) return false
        // The notification page itself, where this OS has a URL for it (iOS 15.4+); the app's page
        // otherwise, which has a Notifications row.
        val urlString: String =
            if (permission == CommonPermission.Notifications && isIosAtLeast(15, 4)) {
                UIApplicationOpenNotificationSettingsURLString
            } else {
                UIApplicationOpenSettingsURLString
            }
        val opened: Boolean = port.openSettingsUrl(urlString)
        if (!opened) logger.w { "The system settings URL cannot be opened on this device" }
        return opened
    }

    /**
     * The entry whose request answers [this] too: iOS has one Bluetooth authorization for every
     * Bluetooth entry, and one events authorization for both calendar entries.
     */
    private fun Permission.statusSource(): Permission = when (this) {
        CommonPermission.BluetoothScan, CommonPermission.BluetoothAdvertise -> CommonPermission.BluetoothConnect
        CommonPermission.CalendarWriteOnly -> CommonPermission.Calendar
        else -> this
    }

    /**
     * The `Info.plist` key [permission] needs and the app lacks: `""` when nothing is missing, `null`
     * when this controller does not know [permission] at all. A missing key is logged.
     */
    private fun missingUsageDescription(permission: Permission): String? {
        val keys: List<String> = usageDescriptionKeys(permission) ?: return null
        val missing: String = keys.firstOrNull { key -> !hasUsageDescription(key) } ?: return ""
        if (markReported(missing)) logger.w { "$permission reads Unavailable: add $missing to Info.plist" }
        return missing
    }

    /** Adds [key] to [reportedMissing]; `true` when it was not there yet. */
    private fun markReported(key: String): Boolean =
        key !in reportedMissing.getAndUpdate { reported -> if (key in reported) reported else reported + key }

    /** The purpose strings a request for [permission] needs; `null` for a type this controller does not know. */
    private fun usageDescriptionKeys(permission: Permission): List<String>? = when (permission) {
        CommonPermission.Notifications -> emptyList()
        CommonPermission.Microphone -> listOf("NSMicrophoneUsageDescription")
        CommonPermission.Camera -> listOf("NSCameraUsageDescription")
        CommonPermission.Location -> listOf("NSLocationWhenInUseUsageDescription")
        CommonPermission.LocationBackground ->
            listOf("NSLocationWhenInUseUsageDescription", "NSLocationAlwaysAndWhenInUseUsageDescription")
        CommonPermission.AudioLibrary -> listOf("NSAppleMusicUsageDescription")
        CommonPermission.BluetoothConnect, CommonPermission.BluetoothScan, CommonPermission.BluetoothAdvertise ->
            listOf("NSBluetoothAlwaysUsageDescription")
        CommonPermission.PhotoLibrary -> listOf("NSPhotoLibraryUsageDescription")
        CommonPermission.PhotoLibraryAdd -> listOf("NSPhotoLibraryAddUsageDescription")
        CommonPermission.Contacts -> listOf("NSContactsUsageDescription")
        // iOS 17 split the calendar purpose string by access level; the old key is ignored there.
        CommonPermission.Calendar ->
            listOf(if (isIosAtLeast(17)) "NSCalendarsFullAccessUsageDescription" else "NSCalendarsUsageDescription")
        CommonPermission.CalendarWriteOnly ->
            listOf(if (isIosAtLeast(17)) "NSCalendarsWriteOnlyAccessUsageDescription" else "NSCalendarsUsageDescription")
        CommonPermission.MotionActivity -> listOf("NSMotionUsageDescription")
        IosPermission.Tracking -> listOf("NSUserTrackingUsageDescription")
        IosPermission.SpeechRecognition -> listOf("NSSpeechRecognitionUsageDescription")
        IosPermission.Reminders ->
            listOf(if (isIosAtLeast(17)) "NSRemindersFullAccessUsageDescription" else "NSRemindersUsageDescription")
        IosPermission.FocusStatus -> listOf("NSFocusStatusUsageDescription")
        else -> null
    }

    private suspend fun currentStatus(permission: Permission): PermissionStatus = when (permission) {
        CommonPermission.Notifications -> checkNotifications()
        CommonPermission.Microphone -> recordPermissionStatus(port.recordPermission())
        CommonPermission.Camera -> captureStatus(port.cameraAuthorization())
        CommonPermission.Location ->
            foregroundLocationStatus(port.locationAuthorization(), port.isLocationAccuracyReduced())
        CommonPermission.LocationBackground -> backgroundLocationStatus(
            port.locationAuthorization(),
            port.alwaysUpgradeAsked,
            port.isLocationAccuracyReduced(),
        )
        CommonPermission.AudioLibrary -> mediaLibraryStatus(port.mediaLibraryAuthorization())
        CommonPermission.BluetoothConnect, CommonPermission.BluetoothScan, CommonPermission.BluetoothAdvertise ->
            bluetoothStatus(port.bluetoothAuthorization())
        CommonPermission.PhotoLibrary -> photoLibraryStatus(port.photoLibraryAuthorization(PHAccessLevelReadWrite))
        CommonPermission.PhotoLibraryAdd -> photoLibraryStatus(port.photoLibraryAuthorization(PHAccessLevelAddOnly))
        CommonPermission.Contacts -> contactsStatus(port.contactsAuthorization())
        CommonPermission.Calendar -> calendarStatus(port.calendarAuthorization(), writeOnly = false)
        CommonPermission.CalendarWriteOnly -> calendarStatus(port.calendarAuthorization(), writeOnly = true)
        CommonPermission.MotionActivity ->
            motionActivityStatus(port.motionAuthorization(), port.isMotionActivityAvailable())
        IosPermission.Tracking -> trackingStatus(port.trackingAuthorization())
        IosPermission.SpeechRecognition -> speechRecognitionStatus(port.speechRecognitionAuthorization())
        IosPermission.Reminders -> remindersStatus(port.remindersAuthorization())
        IosPermission.FocusStatus -> focusStatusStatus(port.focusStatusAuthorization())
        else -> PermissionStatus.Unavailable
    }

    /** One permission, with [requestMutex] held. */
    private suspend fun requestOne(permission: Permission): PermissionStatus =
        requestDialog(permission).also { requestsResolved.tryEmit(permission.statusSource()) }

    private suspend fun requestDialog(permission: Permission): PermissionStatus {
        val current: PermissionStatus = check(permission)
        // iOS shows nothing for these, so asking would suspend on a callback that fires immediately
        // with the same answer — or, for a missing purpose string, terminate the app.
        if (current !is PermissionStatus.NotDetermined && current !is PermissionStatus.Denied &&
            !(current is PermissionStatus.Limited && permission.upgradesFromLimited())
        ) {
            return current
        }
        return when (permission) {
            CommonPermission.Notifications -> requestNotifications(upgrading = current is PermissionStatus.Limited)
            CommonPermission.Microphone -> requestMicrophone()
            CommonPermission.Camera -> requestCamera()
            CommonPermission.Location -> {
                port.requestWhenInUse()
                currentStatus(permission)
            }
            CommonPermission.LocationBackground -> requestBackgroundLocation()
            CommonPermission.AudioLibrary -> mediaLibraryStatus(port.requestMediaLibrary())
            CommonPermission.BluetoothConnect, CommonPermission.BluetoothScan, CommonPermission.BluetoothAdvertise ->
                bluetoothStatus(port.requestBluetooth())
            CommonPermission.PhotoLibrary ->
                photoLibraryStatus(port.requestPhotoLibrary(PHAccessLevelReadWrite))
            CommonPermission.PhotoLibraryAdd ->
                photoLibraryStatus(port.requestPhotoLibrary(PHAccessLevelAddOnly))
            CommonPermission.Contacts -> {
                port.requestContacts { error -> logger.w { "Contacts authorization failed: $error" } }
                // Re-read rather than trusting the Boolean: it does not tell limited from full.
                currentStatus(permission)
            }
            CommonPermission.Calendar, CommonPermission.CalendarWriteOnly -> {
                port.requestCalendar(writeOnly = permission == CommonPermission.CalendarWriteOnly) { error ->
                    logger.w { "Calendar authorization failed: $error" }
                }
                currentStatus(permission)
            }
            CommonPermission.MotionActivity -> {
                port.requestMotionActivity()
                currentStatus(permission)
            }
            IosPermission.Tracking -> trackingStatus(port.requestTracking())
            IosPermission.SpeechRecognition -> speechRecognitionStatus(port.requestSpeechRecognition())
            IosPermission.Reminders -> {
                port.requestReminders { error -> logger.w { "Reminders authorization failed: $error" } }
                currentStatus(permission)
            }
            IosPermission.FocusStatus -> focusStatusStatus(port.requestFocusStatus())
            else -> current
        }
    }

    /**
     * Whether iOS still shows a dialog from a [PermissionStatus.Limited] grant of this entry: the full
     * notification prompt from provisional, and the full-access prompt from write-only calendar access.
     * A limited photo library or contact list is changed in the system's own pickers instead.
     */
    private fun Permission.upgradesFromLimited(): Boolean =
        this == CommonPermission.Notifications || this == CommonPermission.Calendar

    /**
     * iOS grants "always" as an upgrade of "when in use": asking for it while location is not
     * determined shows the "when in use" dialog first, and the upgrade is offered later by the system.
     * Asking again from "when in use" shows the upgrade prompt — at most once per install.
     */
    private suspend fun requestBackgroundLocation(): PermissionStatus {
        if (port.locationAuthorization() == kCLAuthorizationStatusNotDetermined) {
            port.requestWhenInUse()
            return backgroundLocationStatus(port.locationAuthorization(), reducedAccuracy = port.isLocationAccuracyReduced())
        }
        val status: CLAuthorizationStatus = port.requestAlways()
        return backgroundLocationStatus(status, port.alwaysUpgradeAsked, port.isLocationAccuracyReduced())
    }

    /** The one common authorization iOS answers only asynchronously. */
    private suspend fun checkNotifications(): PermissionStatus =
        suspendCancellableCoroutine { continuation ->
            port.notificationAuthorization { authorization: UNAuthorizationStatus? ->
                val status: PermissionStatus = notificationStatus(authorization)
                if (continuation.isActive) continuation.resume(status)
            }
        }

    /**
     * Asks with [options]' notification options — without provisional when [upgrading] from a
     * provisional grant, since asking for provisional again would show nothing.
     */
    private suspend fun requestNotifications(upgrading: Boolean): PermissionStatus {
        val asked: Set<IosNotificationOption> =
            if (upgrading) options.notificationOptions - IosNotificationOption.Provisional else options.notificationOptions
        // Provisional alone, already provisional: nothing more to ask for.
        if (asked.isEmpty()) return checkNotifications()
        suspendCancellableCoroutine { continuation ->
            port.requestNotifications(authorizationOptions(asked)) { error: NSError? ->
                if (error != null) logger.w { "Notification authorization failed: $error" }
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        // Re-read rather than trusting the Boolean: it does not tell provisional from full.
        return checkNotifications()
    }

    private suspend fun requestMicrophone(): PermissionStatus =
        suspendCancellableCoroutine { continuation ->
            port.requestRecordPermission { granted: Boolean ->
                if (continuation.isActive) continuation.resume(granted.toStatus())
            }
        }

    private suspend fun requestCamera(): PermissionStatus =
        suspendCancellableCoroutine { continuation ->
            port.requestCameraAccess { granted: Boolean ->
                if (continuation.isActive) continuation.resume(granted.toStatus())
            }
        }

    /**
     * A refused iOS request is permanent, not a "denied once" — the dialog it came from will not
     * appear a second time.
     */
    private fun Boolean.toStatus(): PermissionStatus =
        if (this) PermissionStatus.Granted else PermissionStatus.PermanentlyDenied
}
