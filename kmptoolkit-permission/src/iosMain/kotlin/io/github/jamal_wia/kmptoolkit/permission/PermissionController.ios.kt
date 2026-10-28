package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.AVFAudio.AVAudioSession
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNNotificationSettings
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Creates the iOS [PermissionController].
 *
 * It takes nothing but a logger, which is the whole reason the factory is per-platform rather than
 * an `expect fun`: iOS needs no context, no activity, and no storage. Every authorization it reads is
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
 * @param logger where a rejected authorization request or a missing purpose string is reported.
 */
public fun createPermissionController(logger: Logger = NoopLogger): PermissionController =
    IosPermissionController(logger, hasUsageDescription = ::hasInfoPlistKey)

/**
 * iOS's [PermissionController].
 *
 * @param hasUsageDescription whether the app's `Info.plist` has a key — a seam, so the "missing purpose
 *   string" path is testable in a test bundle that has none.
 */
internal class IosPermissionController(
    private val logger: Logger,
    private val hasUsageDescription: (String) -> Boolean,
) : PermissionController {

    /** A permission whose request just resolved, so every [observe] of it re-reads at once. */
    private val requestsResolved: MutableSharedFlow<Permission> = MutableSharedFlow(extraBufferCapacity = 64)

    /** Held for the whole of every request, so two prompts never overlap. */
    private val requestMutex: Mutex = Mutex()

    /** Purpose strings already reported missing, so a status observed on every activation warns once. */
    private val reportedMissing: AtomicReference<Set<String>> = AtomicReference(emptySet())

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
            trySend(Unit)
            val unregister: () -> Unit = addBecameActiveListener { trySend(Unit) }
            launch {
                requestsResolved.collect { resolved -> if (resolved.statusSource() == permission.statusSource()) send(Unit) }
            }
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
        val url: NSURL = NSURL.URLWithString(urlString) ?: return false
        val application: UIApplication = UIApplication.sharedApplication
        if (!application.canOpenURL(url)) {
            logger.w { "The system settings URL cannot be opened on this device" }
            return false
        }
        // UIApplication is main-thread-only, and this may well be called from a background
        // coroutine, so the open is dispatched rather than attempted in place. `true` therefore
        // means "handed to UIKit", not "the settings screen actually opened".
        dispatch_async(dispatch_get_main_queue()) {
            application.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
        }
        return true
    }

    /** iOS has one Bluetooth authorization: a request for any Bluetooth entry answers all of them. */
    private fun Permission.statusSource(): Permission =
        if (this == CommonPermission.BluetoothScan) CommonPermission.BluetoothConnect else this

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
    private fun markReported(key: String): Boolean {
        while (true) {
            val reported: Set<String> = reportedMissing.value
            if (key in reported) return false
            if (reportedMissing.compareAndSet(reported, reported + key)) return true
        }
    }

    /** The purpose strings a request for [permission] needs; `null` for a type this controller does not know. */
    private fun usageDescriptionKeys(permission: Permission): List<String>? = when (permission) {
        CommonPermission.Notifications -> emptyList()
        CommonPermission.Microphone -> listOf("NSMicrophoneUsageDescription")
        CommonPermission.Camera -> listOf("NSCameraUsageDescription")
        CommonPermission.Location -> listOf("NSLocationWhenInUseUsageDescription")
        CommonPermission.LocationBackground ->
            listOf("NSLocationWhenInUseUsageDescription", "NSLocationAlwaysAndWhenInUseUsageDescription")
        CommonPermission.AudioLibrary -> listOf("NSAppleMusicUsageDescription")
        CommonPermission.BluetoothConnect, CommonPermission.BluetoothScan -> listOf("NSBluetoothAlwaysUsageDescription")
        else -> null
    }

    private suspend fun currentStatus(permission: Permission): PermissionStatus = when (permission) {
        CommonPermission.Notifications -> checkNotifications()
        CommonPermission.Microphone -> recordPermissionStatus(AVAudioSession.sharedInstance().recordPermission)
        CommonPermission.Camera -> captureStatus(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo))
        CommonPermission.Location ->
            foregroundLocationStatus(LocationAuthorization.current(), LocationAuthorization.isAccuracyReduced())
        CommonPermission.LocationBackground -> backgroundLocationStatus(
            LocationAuthorization.current(),
            LocationAuthorization.alwaysUpgradeAsked,
            LocationAuthorization.isAccuracyReduced(),
        )
        CommonPermission.AudioLibrary -> mediaLibraryStatus(MediaLibraryAuthorization.current())
        CommonPermission.BluetoothConnect, CommonPermission.BluetoothScan ->
            bluetoothStatus(BluetoothAuthorization.current())
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
            !(current is PermissionStatus.Limited && permission == CommonPermission.Notifications)
        ) {
            return current
        }
        return when (permission) {
            CommonPermission.Notifications -> requestNotifications()
            CommonPermission.Microphone -> requestMicrophone()
            CommonPermission.Camera -> requestCamera()
            CommonPermission.Location -> {
                LocationAuthorization.requestWhenInUse()
                currentStatus(permission)
            }
            CommonPermission.LocationBackground -> requestBackgroundLocation()
            CommonPermission.AudioLibrary -> mediaLibraryStatus(MediaLibraryAuthorization.request())
            CommonPermission.BluetoothConnect, CommonPermission.BluetoothScan ->
                bluetoothStatus(BluetoothAuthorization.request())
            else -> current
        }
    }

    /**
     * iOS grants "always" as an upgrade of "when in use": asking for it while location is not
     * determined shows the "when in use" dialog first, and the upgrade is offered later by the system.
     * Asking again from "when in use" shows the upgrade prompt — at most once per install.
     */
    private suspend fun requestBackgroundLocation(): PermissionStatus {
        if (LocationAuthorization.current() == kCLAuthorizationStatusNotDetermined) {
            LocationAuthorization.requestWhenInUse()
            return backgroundLocationStatus(LocationAuthorization.current(), reducedAccuracy = LocationAuthorization.isAccuracyReduced())
        }
        val status: CLAuthorizationStatus = LocationAuthorization.requestAlways()
        return backgroundLocationStatus(status, LocationAuthorization.alwaysUpgradeAsked, LocationAuthorization.isAccuracyReduced())
    }

    /** The one common authorization iOS answers only asynchronously. */
    private suspend fun checkNotifications(): PermissionStatus =
        suspendCancellableCoroutine { continuation ->
            UNUserNotificationCenter.currentNotificationCenter()
                .getNotificationSettingsWithCompletionHandler { settings: UNNotificationSettings? ->
                    val status: PermissionStatus = notificationStatus(settings?.authorizationStatus)
                    if (continuation.isActive) continuation.resume(status)
                }
        }

    private suspend fun requestNotifications(): PermissionStatus {
        suspendCancellableCoroutine { continuation ->
            val options: ULong = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
            UNUserNotificationCenter.currentNotificationCenter()
                .requestAuthorizationWithOptions(options) { _, error ->
                    if (error != null) logger.w { "Notification authorization failed: $error" }
                    if (continuation.isActive) continuation.resume(Unit)
                }
        }
        // Re-read rather than trusting the Boolean: it does not tell provisional from full.
        return checkNotifications()
    }

    private suspend fun requestMicrophone(): PermissionStatus =
        suspendCancellableCoroutine { continuation ->
            AVAudioSession.sharedInstance().requestRecordPermission { granted: Boolean ->
                if (continuation.isActive) continuation.resume(granted.toStatus())
            }
        }

    private suspend fun requestCamera(): PermissionStatus =
        suspendCancellableCoroutine { continuation ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted: Boolean ->
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
