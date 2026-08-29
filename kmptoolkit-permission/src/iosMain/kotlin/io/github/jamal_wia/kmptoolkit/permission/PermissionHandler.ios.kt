package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNNotificationSettings
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Creates the iOS [PermissionHandler].
 *
 * It takes nothing but a logger, which is the whole reason the factory is per-platform rather than
 * an `expect fun`: iOS needs no context, no activity, and no storage. The authorization status of
 * every permission in the [Permission] catalog is a value the OS itself keeps and reports, with
 * "not determined" among the values, so there is nothing for this library to remember — compare
 * `PermissionConfig`, which exists purely for Android's inability to report that.
 *
 * Two iOS facts shape everything here, and both are in
 * `docs/kmptoolkit-permission/05-platform-notes.md`:
 *
 * - **A refusal is final.** iOS shows its system dialog at most once per install. Anything the
 *   user declines therefore reports [PermissionStatus.PermanentlyDenied], never
 *   [PermissionStatus.Denied], and [PermissionStatus.Denied.shouldShowRationale] is never `true` —
 *   there is no second dialog for a rationale to precede. Explain *before* calling [request],
 *   while the status is still [PermissionStatus.NotDetermined].
 * - **Every permission needs an `Info.plist` string.** A missing `NSMicrophoneUsageDescription`,
 *   `NSCameraUsageDescription` — the app is terminated by the OS at the moment of the request, not
 *   handed an error this handler could turn into a status.
 *
 * @param logger where a rejected authorization request is reported.
 */
public fun createPermissionHandler(logger: Logger = NoopLogger): PermissionHandler =
    IosPermissionHandler(logger)

private class IosPermissionHandler(private val logger: Logger) : PermissionHandler {

    override suspend fun check(permission: Permission): PermissionStatus = when (permission) {
        Permission.NOTIFICATIONS -> checkNotifications()
        Permission.MICROPHONE -> checkMicrophone()
        Permission.CAMERA -> checkCamera()
    }

    override suspend fun request(permission: Permission): PermissionStatus {
        val current: PermissionStatus = check(permission)
        // iOS shows nothing for either of these, so asking would suspend on a callback that fires
        // immediately with the same answer. Returning early keeps that explicit.
        if (current is PermissionStatus.Granted || current is PermissionStatus.PermanentlyDenied) {
            return current
        }
        return when (permission) {
            Permission.NOTIFICATIONS -> requestNotifications()
            Permission.MICROPHONE -> requestMicrophone()
            Permission.CAMERA -> requestCamera()
        }
    }

    override fun openAppSettings(): Boolean {
        val url: NSURL = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return false
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

    /**
     * The one permission whose status iOS refuses to answer synchronously — the reason
     * [PermissionHandler.check] is a suspending function at all.
     *
     * `Provisional` and `Ephemeral` both count as granted: notifications *are* deliverable under
     * either, quietly for the first and for an App Clip's lifetime for the second. Neither can be
     * requested through this module, but an app that obtained one elsewhere must not be told it has
     * nothing.
     */
    private suspend fun checkNotifications(): PermissionStatus =
        suspendCancellableCoroutine { continuation ->
            UNUserNotificationCenter.currentNotificationCenter()
                .getNotificationSettingsWithCompletionHandler { settings: UNNotificationSettings? ->
                    val status: PermissionStatus = when (settings?.authorizationStatus) {
                        null -> PermissionStatus.NotDetermined
                        UNAuthorizationStatusNotDetermined -> PermissionStatus.NotDetermined
                        UNAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
                        else -> PermissionStatus.Granted
                    }
                    if (continuation.isActive) continuation.resume(status)
                }
        }

    private suspend fun requestNotifications(): PermissionStatus =
        suspendCancellableCoroutine { continuation ->
            val options: ULong =
                UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
            UNUserNotificationCenter.currentNotificationCenter()
                .requestAuthorizationWithOptions(options) { granted, error ->
                    if (error != null) logger.w { "Notification authorization failed: $error" }
                    if (continuation.isActive) continuation.resume(granted.toStatus())
                }
        }

    private fun checkMicrophone(): PermissionStatus =
        when (AVAudioSession.sharedInstance().recordPermission) {
            AVAudioSessionRecordPermissionGranted -> PermissionStatus.Granted
            AVAudioSessionRecordPermissionDenied -> PermissionStatus.PermanentlyDenied
            else -> PermissionStatus.NotDetermined
        }

    private suspend fun requestMicrophone(): PermissionStatus =
        suspendCancellableCoroutine { continuation ->
            AVAudioSession.sharedInstance().requestRecordPermission { granted: Boolean ->
                if (continuation.isActive) continuation.resume(granted.toStatus())
            }
        }

    private fun checkCamera(): PermissionStatus =
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> PermissionStatus.Granted
            // Restricted is not a refusal the user made and not one they can lift — a parental
            // control or an MDM profile. It still belongs here: settings is the only place it can
            // possibly change, which is exactly what PermanentlyDenied promises.
            AVAuthorizationStatusDenied, AVAuthorizationStatusRestricted ->
                PermissionStatus.PermanentlyDenied

            else -> PermissionStatus.NotDetermined
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
