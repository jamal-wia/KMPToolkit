package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi

/**
 * The permissions only Android has: special app accesses granted on a Settings screen, and — in later
 * groups — Android-only runtime permissions, roles and a raw-string escape hatch.
 *
 * None of these is visible to shared code. Shared code that needs one takes a [Permission] value from
 * Android code, typically through your DI:
 *
 * ```kotlin
 * // commonMain
 * class CheckAlarmAccess(private val permissions: PermissionController, private val exactAlarm: Permission?) {
 *     suspend operator fun invoke(): Boolean = exactAlarm == null || permissions.check(exactAlarm).isGranted
 * }
 * // androidMain: CheckAlarmAccess(controller, AndroidPermission.ExactAlarm)
 * // iosMain:     CheckAlarmAccess(controller, null)
 * ```
 */
@OptIn(ToolkitInternalApi::class)
public sealed interface AndroidPermission : Permission {

    /**
     * An access the user grants by hand on a dedicated Settings screen, with no in-app dialog.
     *
     * A request opens that screen through the controller's `SystemScreenLauncher` and suspends until
     * an activity of the app is resumed again — the user coming back — then re-reads the status. A
     * special access that is not granted reads [PermissionStatus.Denied] with `explainFirst = true`:
     * the screen can always be opened again, and sending a user out of the app unannounced is the
     * worst way to ask. Below the API level that introduced it, an access the OS used to give every
     * app reads [PermissionStatus.Granted]; one that simply did not exist reads
     * [PermissionStatus.Unavailable]. Nothing is stored for a special access.
     */
    public sealed interface SpecialAccess : AndroidPermission

    /**
     * Schedule exact alarms — `SCHEDULE_EXACT_ALARM`, checked with `AlarmManager.canScheduleExactAlarms()`
     * from API 31. Below API 31 exact alarms need no permission: [PermissionStatus.Granted].
     */
    public data object ExactAlarm : SpecialAccess {
        override val id: String = "EXACT_ALARM"
    }

    /** Draw over other apps — `SYSTEM_ALERT_WINDOW`, checked with `Settings.canDrawOverlays`. */
    public data object Overlay : SpecialAccess {
        override val id: String = "OVERLAY"
    }

    /** Modify system settings — `WRITE_SETTINGS`, checked with `Settings.System.canWrite`. */
    public data object WriteSettings : SpecialAccess {
        override val id: String = "WRITE_SETTINGS"
    }

    /**
     * Broad ("all files") external storage access — `MANAGE_EXTERNAL_STORAGE` from API 30. Below API 30
     * storage was not scoped: [PermissionStatus.Granted].
     */
    public data object AllFilesAccess : SpecialAccess {
        override val id: String = "ALL_FILES_ACCESS"
    }

    /** Per-app usage statistics ("Usage access") — `PACKAGE_USAGE_STATS`, an app-op. */
    public data object UsageStatsAccess : SpecialAccess {
        override val id: String = "USAGE_STATS_ACCESS"
    }

    /**
     * Exemption from battery optimization — `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, checked with
     * `PowerManager.isIgnoringBatteryOptimizations`. The "screen" is a system dialog.
     */
    public data object IgnoreBatteryOptimizations : SpecialAccess {
        override val id: String = "IGNORE_BATTERY_OPTIMIZATIONS"
    }

    /**
     * Notification-listener access for this app's package — granted when any listener `<service>`
     * of this package is enabled. Opens the generic notification-listener list.
     */
    public data object NotificationListenerAccess : SpecialAccess {
        override val id: String = "NOTIFICATION_LISTENER_ACCESS"
    }

    /** Do Not Disturb (notification policy) access — `ACCESS_NOTIFICATION_POLICY`. */
    public data object DoNotDisturbAccess : SpecialAccess {
        override val id: String = "DO_NOT_DISTURB_ACCESS"
    }

    /**
     * Edit, trash and delete media files without a confirmation per file — `MANAGE_MEDIA`, checked with
     * `MediaStore.canManageMedia()` from API 31. Below API 31 the access does not exist:
     * [PermissionStatus.Unavailable].
     */
    public data object MediaManagement : SpecialAccess {
        override val id: String = "MEDIA_MANAGEMENT"
    }

    /**
     * Install other apps — `REQUEST_INSTALL_PACKAGES`, a per-app grant checked with
     * `PackageManager.canRequestPackageInstalls()` from API 26. Below API 26 there is only a device-wide
     * switch, a different thing: [PermissionStatus.Unavailable].
     */
    public data object InstallPackages : SpecialAccess {
        override val id: String = "INSTALL_UNKNOWN_APPS"
    }

    /**
     * Show full-screen notifications over the lock screen, as an alarm or a call does —
     * `USE_FULL_SCREEN_INTENT`, checked with `NotificationManager.canUseFullScreenIntent()` from API 34.
     * Below API 34 it is an install-time permission: [PermissionStatus.Granted] when the manifest
     * declares it, [PermissionStatus.Unavailable] when it does not.
     */
    public data object FullScreenNotifications : SpecialAccess {
        override val id: String = "FULL_SCREEN_INTENT"
    }

    /**
     * Keep this app's permissions when it goes unused for months — granted when the app is exempt from
     * auto-revoke (`PackageManager.isAutoRevokeWhitelisted`) from API 30. Below API 30 nothing is taken
     * back: [PermissionStatus.Granted].
     */
    public data object KeepPermissionsWhenUnused : SpecialAccess {
        override val id: String = "KEEP_PERMISSIONS_WHEN_UNUSED"
    }

    public companion object {

        /** Every [SpecialAccess] that takes no parameter, in declaration order. */
        public val specialAccesses: List<SpecialAccess> by lazy {
            listOf(
                ExactAlarm,
                Overlay,
                WriteSettings,
                AllFilesAccess,
                UsageStatsAccess,
                IgnoreBatteryOptimizations,
                NotificationListenerAccess,
                DoNotDisturbAccess,
                MediaManagement,
                InstallPackages,
                FullScreenNotifications,
                KeepPermissionsWhenUnused,
            )
        }
    }
}
