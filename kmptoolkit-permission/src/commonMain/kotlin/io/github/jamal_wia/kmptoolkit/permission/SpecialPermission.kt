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
}
