package io.github.jamal_wia.kmptoolkit.permission

/**
 * A runtime permission this module knows how to check, request, and reason about on **both**
 * platforms.
 *
 * The catalog is closed and short on purpose. Every entry maps to Android permission strings and an
 * iOS authorization API whose "granted / refused / not yet asked" shape [PermissionStatus] can express
 * without distortion, and every mapping is exercised by a test. The value of this module is not the
 * mapping table; it is the denial bookkeeping and the state machine on top of it, and both give wrong
 * answers for a permission whose platform semantics do not fit.
 *
 * Where a platform has a finer distinction than [PermissionStatus], the entry says how it is folded,
 * and the finer fact stays with the platform API — see `docs/kmptoolkit-permission/05-platform-notes.md`:
 *
 * - **Location precision.** Android's approximate-only grant and iOS 14's reduced accuracy both count
 *   as [LOCATION] granted. Whether the fix is precise is a property of the location, not of the
 *   permission, and `kmptoolkit-location` reports it there.
 * - **Photos, contacts, calendar, health, SMS, phone.** Not in the catalog. Photos in particular has
 *   iOS's `Limited` state, which is neither granted nor refused. Call the platform API in platform code.
 *
 * **The catalog can grow in a minor release.** Adding an entry is binary-compatible, but an exhaustive
 * `when` over [Permission] in your code stops compiling until it handles the new entry — add an `else`
 * branch where you do not care about every permission.
 */
public enum class Permission {

    /**
     * Post notifications.
     *
     * - Android: `POST_NOTIFICATIONS`, which is a runtime permission only from API 33 (Tiramisu).
     *   Below that it is reported as granted, because there is no runtime grant to obtain — see
     *   `docs/kmptoolkit-permission/05-platform-notes.md` for why "granted" there does not mean
     *   the user has notifications switched on.
     * - iOS: `UNUserNotificationCenter`, alert + badge + sound.
     */
    NOTIFICATIONS,

    /**
     * Capture audio from the microphone.
     *
     * - Android: `RECORD_AUDIO`.
     * - iOS: `AVAudioSession`'s record permission.
     */
    MICROPHONE,

    /**
     * Capture video from the camera.
     *
     * - Android: `CAMERA`.
     * - iOS: `AVCaptureDevice` authorization for `AVMediaTypeVideo`.
     */
    CAMERA,

    /**
     * The device's location while the app is in use.
     *
     * - Android: `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, requested together in one
     *   dialog — only then does Android 12+ offer the Precise / Approximate choice. Either grant counts
     *   as [PermissionStatus.Granted]. The request needs a host implementing the multi-permission
     *   `PermissionRequestHost.launch`.
     * - iOS: `CLLocationManager` "when in use" authorization; "always" also counts.
     *
     * @since 1.5.0
     */
    LOCATION,

    /**
     * The device's location while the app is in the background.
     *
     * - Android: `ACCESS_BACKGROUND_LOCATION` from API 29; below it, foreground location covers
     *   background too and this reports whatever [LOCATION] reports. Android only grants it on top of
     *   [LOCATION], so a request while [LOCATION] is not granted shows nothing and returns the current
     *   status. From API 30 the "dialog" is the app's location page in system settings.
     * - iOS: `CLLocationManager` "always" authorization. "When in use" reports
     *   [PermissionStatus.Denied] with a rationale — iOS may still offer the upgrade once — and a
     *   request while [LOCATION] is not determined asks for "when in use" first, as iOS itself does.
     *
     * @since 1.5.0
     */
    LOCATION_BACKGROUND,

    /**
     * Read audio files from shared storage.
     *
     * - Android: `READ_MEDIA_AUDIO` from API 33, `READ_EXTERNAL_STORAGE` below it.
     * - iOS: `MPMediaLibrary` authorization — the user's music library.
     *
     * @since 1.5.0
     */
    MEDIA_AUDIO,

    /**
     * Connect to paired Bluetooth devices.
     *
     * - Android: `BLUETOOTH_CONNECT` from API 31; below it there is no runtime grant and this reports
     *   [PermissionStatus.Granted] (the install-time `BLUETOOTH` permission covers it).
     * - iOS: `CBManager` authorization. There is one Bluetooth permission on iOS, so this is it.
     *
     * @since 1.5.0
     */
    BLUETOOTH_CONNECT,
}
