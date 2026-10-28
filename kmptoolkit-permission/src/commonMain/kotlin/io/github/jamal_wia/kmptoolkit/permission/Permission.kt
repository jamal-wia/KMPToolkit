package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi

/**
 * Anything a [PermissionController] can check and request.
 *
 * The vocabulary is split by where a permission exists, and the split is enforced by the compiler:
 *
 * - [CommonPermission] — the permissions **both** platforms have, under a name neither platform owns.
 *   This is the only part of the vocabulary shared code can see.
 * - `AndroidPermission` (androidMain) — Android-only runtime permissions, special app accesses, roles
 *   and the raw-string escape hatch.
 * - `IosPermission` (iosMain) — iOS-only authorizations.
 *
 * A permission only one platform has is therefore not in the common ABI at all: shared code that
 * needs one — "may this app schedule exact alarms?" — takes a `Permission` value that platform code
 * hands it, and never names the Android type. See `docs/kmptoolkit-permission/01-overview.md`.
 *
 * **Implemented only by this library.** The interface is open because Kotlin does not let a sealed
 * hierarchy span the source sets of one multiplatform module, but implementing it is an opt-in to
 * internal API: a [PermissionController] answers [PermissionStatus.Unavailable] for any type it does
 * not know, and a consumer-defined subtype would only ever get that answer. Exhaustive `when` is
 * possible over each sealed sub-hierarchy, not over `Permission` itself.
 */
@SubclassOptInRequired(ToolkitInternalApi::class)
public interface Permission {

    /**
     * A stable identifier for this permission.
     *
     * The Android controller keys its stored "asked" and "refused" flags by it, so it never changes
     * between releases: every entry that existed in 2.x keeps its 2.x enum name here (`CAMERA`,
     * `MEDIA_AUDIO`, `EXACT_ALARM`, …), and flags written by 2.x are read unchanged by 3.x. Also
     * yours to use for your own persisted data about a permission.
     */
    public val id: String
}

/**
 * The permissions both platforms have, each mapped to an Android permission and an iOS authorization
 * whose states [PermissionStatus] can express without distortion.
 *
 * Where a platform has a finer distinction than [PermissionStatus], the entry says how it is folded —
 * see `docs/kmptoolkit-permission/05-platform-notes.md` for the full table.
 *
 * **The set can grow in a minor release.** Adding an entry is binary-compatible, but an exhaustive
 * `when` over `CommonPermission` stops compiling until it handles the new entry — add an `else`
 * branch where you do not care about every permission.
 */
@OptIn(ToolkitInternalApi::class)
public sealed interface CommonPermission : Permission {

    /**
     * Post notifications.
     *
     * - Android: `POST_NOTIFICATIONS` from API 33. Below it there is no runtime permission, and this
     *   reports whether the user has the app's notifications switched on
     *   (`NotificationManagerCompat.areNotificationsEnabled()`): [PermissionStatus.Granted] or
     *   [PermissionStatus.PermanentlyDenied] — settings is the only place that switch changes.
     * - iOS: `UNUserNotificationCenter` with the options the iOS factory is given (alert, badge and
     *   sound by default). Provisional authorization — delivered quietly to Notification Center — is
     *   [PermissionStatus.Limited].
     */
    public data object Notifications : CommonPermission {
        override val id: String = "NOTIFICATIONS"
    }

    /**
     * Capture audio from the microphone.
     *
     * - Android: `RECORD_AUDIO`.
     * - iOS: `AVAudioSession`'s record permission.
     */
    public data object Microphone : CommonPermission {
        override val id: String = "MICROPHONE"
    }

    /**
     * Capture video from the camera.
     *
     * - Android: `CAMERA`.
     * - iOS: `AVCaptureDevice` authorization for `AVMediaTypeVideo`.
     */
    public data object Camera : CommonPermission {
        override val id: String = "CAMERA"
    }

    /**
     * The device's location while the app is in use.
     *
     * - Android: `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, requested together in one dialog —
     *   only then does Android 12+ offer the Precise / Approximate choice. A precise grant is
     *   [PermissionStatus.Granted]; an approximate-only grant is [PermissionStatus.Limited], and a
     *   request from there offers the upgrade to precise.
     * - iOS: `CLLocationManager` "when in use" authorization; "always" also counts. Reduced accuracy
     *   (iOS 14+) is [PermissionStatus.Limited].
     */
    public data object Location : CommonPermission {
        override val id: String = "LOCATION"
    }

    /**
     * The device's location while the app is in the background.
     *
     * - Android: `ACCESS_BACKGROUND_LOCATION` from API 29; below it, foreground location covers
     *   background too and this reports whatever [Location] reports. Android only grants it on top of
     *   [Location], so a request while [Location] is not usable shows nothing and returns the current
     *   status. From API 30 the "dialog" is the app's location page in system settings.
     * - iOS: `CLLocationManager` "always" authorization. "When in use" reports
     *   [PermissionStatus.Denied] with `explainFirst` — iOS may still offer the upgrade once — and a
     *   request while [Location] is not determined asks for "when in use" first, as iOS itself does.
     */
    public data object LocationBackground : CommonPermission {
        override val id: String = "LOCATION_BACKGROUND"
    }

    /**
     * The user's audio library.
     *
     * The two platforms mean related, not identical, things: Android grants reading audio files in
     * shared storage (`READ_MEDIA_AUDIO` from API 33, `READ_EXTERNAL_STORAGE` below it); iOS grants
     * access to the Apple Music library (`MPMediaLibrary`). Both are "the music the user has on this
     * device".
     *
     * Its [id] is `MEDIA_AUDIO`, the 2.x name, so flags stored by 2.x are kept.
     */
    public data object AudioLibrary : CommonPermission {
        override val id: String = "MEDIA_AUDIO"
    }

    /**
     * Connect to paired Bluetooth devices.
     *
     * - Android: `BLUETOOTH_CONNECT` from API 31; below it there is no runtime grant and this reports
     *   [PermissionStatus.Granted] (the install-time `BLUETOOTH` permission covers it).
     * - iOS: `CBManager` authorization. There is one Bluetooth authorization on iOS, so every Bluetooth
     *   entry reports the same status there.
     */
    public data object BluetoothConnect : CommonPermission {
        override val id: String = "BLUETOOTH_CONNECT"
    }

    /**
     * Discover nearby Bluetooth devices — classic discovery and BLE scanning — and receive what the scan
     * finds.
     *
     * - Android from API 31: `BLUETOOTH_SCAN`, which your manifest must declare with
     *   `android:usesPermissionFlags="neverForLocation"`. Without that flag Android also withholds scan
     *   results from an app that lacks fine location, so this entry then reports
     *   [PermissionStatus.Unavailable] and a request shows nothing; the controller logs a warning once.
     * - Android below API 31: there is no Bluetooth runtime grant, but scan results are delivered only
     *   to an app holding location. This entry is therefore [Location] there in every respect: it
     *   reports what [Location] reports, a request shows the location dialog, and a refusal is
     *   remembered as a refusal of [Location].
     * - iOS: `CBManager` authorization — the same single Bluetooth authorization as [BluetoothConnect].
     */
    public data object BluetoothScan : CommonPermission {
        override val id: String = "BLUETOOTH_SCAN"
    }

    /**
     * Advertise this device to nearby Bluetooth devices — act as a BLE peripheral.
     *
     * - Android: `BLUETOOTH_ADVERTISE` from API 31; below it there is no runtime grant and this reports
     *   [PermissionStatus.Granted].
     * - iOS: `CBManager` authorization — the same single Bluetooth authorization as [BluetoothConnect].
     */
    public data object BluetoothAdvertise : CommonPermission {
        override val id: String = "BLUETOOTH_ADVERTISE"
    }

    /**
     * Read the user's photos and videos.
     *
     * - Android: `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` from API 33 (whichever of the two your
     *   manifest declares), `READ_EXTERNAL_STORAGE` below it. From API 34 the dialog also offers
     *   "Select photos and videos" when you declare `READ_MEDIA_VISUAL_USER_SELECTED`: that partial
     *   grant is [PermissionStatus.Limited], and a request from there shows the dialog again, which is
     *   how the user changes the selection.
     * - iOS: `PHPhotoLibrary` authorization for the read-write access level. A limited library
     *   (iOS 14+) is [PermissionStatus.Limited]; iOS does not show the dialog again, so a request from
     *   there returns at once — call `presentLimitedPhotoLibraryPicker` (iosMain) to let the user change
     *   the selection.
     */
    public data object PhotoLibrary : CommonPermission {
        override val id: String = "PHOTO_LIBRARY"
    }

    /**
     * Save photos and videos to the user's library, without reading it.
     *
     * - Android: `WRITE_EXTERNAL_STORAGE` up to API 28; from API 29 an app adds media through
     *   `MediaStore` without any permission, and this reports [PermissionStatus.Granted].
     * - iOS: `PHPhotoLibrary` authorization for the add-only access level.
     */
    public data object PhotoLibraryAdd : CommonPermission {
        override val id: String = "PHOTO_LIBRARY_ADD"
    }

    /**
     * The user's contacts.
     *
     * - Android: `READ_CONTACTS`, together with `WRITE_CONTACTS` when your manifest declares it — both in
     *   one dialog, and granted only when every declared one is.
     * - iOS: `CNContactStore` authorization. Limited access — the contacts the user picked, iOS 18+ — is
     *   [PermissionStatus.Limited].
     */
    public data object Contacts : CommonPermission {
        override val id: String = "CONTACTS"
    }

    /**
     * Read and write the user's calendar events.
     *
     * - Android: `READ_CALENDAR` and `WRITE_CALENDAR`, in one dialog.
     * - iOS: EventKit full access to events (`requestFullAccessToEvents` from iOS 17, the single
     *   events authorization below it). Write-only access, which an iOS 17+ user can grant instead, is
     *   [PermissionStatus.Limited], and a request from there asks for the upgrade to full access.
     */
    public data object Calendar : CommonPermission {
        override val id: String = "CALENDAR"
    }

    /**
     * Add events to the user's calendar, without reading it.
     *
     * - Android: `WRITE_CALENDAR`.
     * - iOS 17+: EventKit write-only access to events; full access also counts. Below iOS 17 there is
     *   no write-only access, and a request asks for — and the dialog grants — full access.
     */
    public data object CalendarWriteOnly : CommonPermission {
        override val id: String = "CALENDAR_WRITE_ONLY"
    }

    /**
     * Recognise the user's physical activity — walking, running, driving — and count steps.
     *
     * - Android: `ACTIVITY_RECOGNITION` from API 29; below it there is no runtime grant and this
     *   reports [PermissionStatus.Granted].
     * - iOS: `CMMotionActivityManager` authorization. A device without motion-activity hardware is
     *   [PermissionStatus.Unavailable].
     */
    public data object MotionActivity : CommonPermission {
        override val id: String = "MOTION_ACTIVITY"
    }

    public companion object {

        /** Every common permission, in declaration order. */
        public val entries: List<CommonPermission> by lazy {
            listOf(
                Notifications,
                Microphone,
                Camera,
                Location,
                LocationBackground,
                AudioLibrary,
                BluetoothConnect,
                BluetoothScan,
                BluetoothAdvertise,
                PhotoLibrary,
                PhotoLibraryAdd,
                Contacts,
                Calendar,
                CalendarWriteOnly,
                MotionActivity,
            )
        }
    }
}
