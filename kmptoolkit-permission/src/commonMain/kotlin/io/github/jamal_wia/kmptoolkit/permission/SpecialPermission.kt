package io.github.jamal_wia.kmptoolkit.permission

/**
 * A "special access" permission — **not** a runtime permission (contrast [Permission]).
 *
 * A [Permission] is granted through the system's in-app request dialog. A special access has no
 * such dialog: the user can only grant it from a dedicated system Settings screen, so
 * [SpecialPermissionHandler] maps each entry to a "granted?" check (the permission's own platform
 * API) and a "request" that opens the relevant Settings screen for the user to toggle by hand.
 *
 * Kept separate from [Permission] on purpose: the request/check mechanics differ enough that mixing
 * them into the runtime enum would leak this module's special cases into the ordinary runtime-
 * permission flow every other [Permission] goes through.
 */
public enum class SpecialPermission {

    /**
     * Schedule exact alarms — reminders that fire precisely even in Doze.
     * - Android 12+ (API 31+): `SCHEDULE_EXACT_ALARM`. Denied by default from API 33; below API 31
     *   exact alarms need no permission and are always granted.
     * - iOS: not applicable — always granted. Exact local notifications need only the standard
     *   notification authorization ([Permission.NOTIFICATIONS]).
     */
    EXACT_ALARM,

    /** Draw over other apps. Android: `SYSTEM_ALERT_WINDOW`. iOS: not applicable, always granted. */
    OVERLAY,

    /** Modify system settings. Android: `WRITE_SETTINGS`. iOS: not applicable, always granted. */
    WRITE_SETTINGS,

    /**
     * Broad ("all files") external storage access, beyond a scoped [Permission].
     * - Android 11+ (API 30+): `MANAGE_EXTERNAL_STORAGE`. Always granted below API 30.
     * - iOS: not applicable — always granted.
     */
    ALL_FILES_ACCESS,

    /** Per-app usage statistics ("Usage access"). Android: `PACKAGE_USAGE_STATS`. iOS: always granted. */
    USAGE_STATS_ACCESS,

    /**
     * Exemption from battery-optimization/Doze throttling for this app.
     * Android: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. iOS: not applicable, always granted.
     */
    IGNORE_BATTERY_OPTIMIZATIONS,

    /**
     * Notification-listener access (read other apps' posted notifications) — tied to a
     * manifest-declared listener `<service>` you own. Android: `BIND_NOTIFICATION_LISTENER_SERVICE`.
     * iOS: not applicable, always granted.
     */
    NOTIFICATION_LISTENER_ACCESS,

    /**
     * Do Not Disturb / notification-policy access (read or change DND state).
     * Android: `ACCESS_NOTIFICATION_POLICY`. iOS: not applicable, always granted.
     */
    DO_NOT_DISTURB_ACCESS,

    /**
     * Edit, trash and delete media files without a confirmation dialog for each one.
     * - Android 12+ (API 31+): `MANAGE_MEDIA`, checked with `MediaStore.canManageMedia()`. Below API 31
     *   the access does not exist and this reports granted.
     * - iOS: not applicable — always granted.
     *
     * @since 2.2.1
     */
    MEDIA_MANAGEMENT,

    /**
     * Install other apps — "Install unknown apps".
     * - Android 8+ (API 26+): `REQUEST_INSTALL_PACKAGES`, a per-app grant checked with
     *   `PackageManager.canRequestPackageInstalls()`. Below API 26 there is no per-app grant: this
     *   reports the device-wide "Unknown sources" switch.
     * - iOS: not applicable — always granted.
     *
     * @since 2.2.1
     */
    INSTALL_UNKNOWN_APPS,

    /**
     * Show a full-screen notification over the lock screen, as an alarm or an incoming call does.
     * - Android 14+ (API 34+): `USE_FULL_SCREEN_INTENT`, checked with
     *   `NotificationManager.canUseFullScreenIntent()`. Below API 34 it is granted at install to every
     *   app that declares it, and this reports granted.
     * - iOS: not applicable — always granted.
     *
     * @since 2.2.1
     */
    FULL_SCREEN_INTENT,

    /**
     * Keep this app's permissions when it is not used for months. Android otherwise takes them back
     * (and, from API 31, also pauses the app) — the "Pause app activity if unused" switch.
     * - Android 11+ (API 30+): granted when the app is exempt, `PackageManager.isAutoRevokeWhitelisted`.
     *   Below API 30 nothing is taken back and this reports granted.
     * - iOS: not applicable — always granted.
     *
     * @since 2.2.1
     */
    KEEP_PERMISSIONS_WHEN_UNUSED,
}
