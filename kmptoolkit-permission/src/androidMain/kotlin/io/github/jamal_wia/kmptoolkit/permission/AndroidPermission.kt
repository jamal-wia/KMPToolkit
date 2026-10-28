package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi

/**
 * The permissions only Android has: Android-only runtime permissions, special app accesses granted on a
 * Settings screen, and the raw-string escape hatch [Runtime].
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

    // --- Android-only runtime permissions -------------------------------------------------------

    /**
     * Find and connect to nearby Wi-Fi devices without location — `NEARBY_WIFI_DEVICES` from API 33,
     * which your manifest should declare with `android:usesPermissionFlags="neverForLocation"` unless
     * you derive location from the results. Below API 33 Android hands Wi-Fi scan results only to an
     * app holding location, so this entry is [CommonPermission.Location] there in every respect:
     * status, dialog and remembered refusals.
     */
    public data object NearbyWifiDevices : AndroidPermission {
        override val id: String = "NEARBY_WIFI_DEVICES"
    }

    /**
     * Reach devices on the local network — `ACCESS_LOCAL_NETWORK`, a runtime permission from API 37 for
     * an app that targets API 37 or higher. On any other combination local network access needs no
     * grant, and this reports [PermissionStatus.Granted].
     */
    public data object LocalNetwork : AndroidPermission {
        override val id: String = "LOCAL_NETWORK"
    }

    /**
     * Read heart rate and other body sensors while the app is in use — `BODY_SENSORS` up to API 35;
     * from API 36 Android replaced it with the Health Connect permission
     * `android.permission.health.READ_HEART_RATE`, which is what this requests there. Declare the one
     * your `targetSdk` and the devices you support need — both, usually, the old one with
     * `android:maxSdkVersion="35"`.
     */
    public data object BodySensors : AndroidPermission {
        override val id: String = "BODY_SENSORS"
    }

    /**
     * Read body sensors while the app is in the background — `BODY_SENSORS_BACKGROUND` on API 33–35,
     * `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` from API 36. Below API 33 the
     * foreground grant covers background too, and this is [BodySensors] in every respect. Android grants
     * it only on top of [BodySensors], so a request while that is not granted shows nothing and returns
     * the current status — exactly as for [CommonPermission.LocationBackground].
     */
    public data object BodySensorsBackground : AndroidPermission {
        override val id: String = "BODY_SENSORS_BACKGROUND"
    }

    /**
     * Any Android permission by its manifest name — the escape hatch for one this vocabulary does not
     * model (`READ_PHONE_STATE`, `READ_SMS`, a permission of another app, a development permission).
     *
     * The controller treats it by its protection level on this device:
     *
     * - **Dangerous** — a runtime permission: one dialog of its own, and the same bookkeeping as every
     *   typed entry (first refusal, permanent refusal, policy restrictions), its flags keyed by [name].
     * - **Anything else** — install-time, signature, privileged or development: no app can prompt for
     *   it. A check reports [PermissionStatus.Granted] when the app holds it and
     *   [PermissionStatus.Unavailable] otherwise, and a request shows nothing.
     * - **Unknown to this device** — a typo, another app's permission that is not installed, or one
     *   newer than the OS: [PermissionStatus.Unavailable].
     *
     * Prefer a typed entry where one exists: `Runtime(Manifest.permission.CAMERA)` is a different entry
     * from [CommonPermission.Camera], with its own stored flags, and knows nothing of a permission's
     * API-level history — `Runtime(ACCESS_BACKGROUND_LOCATION)` does not ask for foreground location
     * first, for example.
     *
     * @property name the permission's manifest name, such as `android.permission.READ_PHONE_STATE`.
     */
    public data class Runtime(public val name: String) : AndroidPermission {
        init {
            require(name.isNotBlank()) { "A permission name must not be blank" }
        }

        /** [name] itself. */
        override val id: String get() = name
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
