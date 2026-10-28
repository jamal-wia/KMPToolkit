package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.w

/**
 * The status and the Settings screen of every [AndroidPermission.SpecialAccess].
 *
 * Each entry is checked with its own public platform API — never a shared app-op guess — and reads the
 * real API level (`Build.VERSION.SDK_INT`), because the platform calls behind it exist only from that
 * level; tests pin the level with Robolectric's `@Config(sdk = …)`.
 *
 * @param declaredPermissions the permissions this app's manifest declares, or `null` when they could
 *   not be read.
 */
internal class SpecialAccesses(
    private val context: Context,
    private val logger: Logger,
    private val declaredPermissions: () -> Set<String>?,
) {

    /**
     * [PermissionStatus.Granted]; [PermissionStatus.Denied] with `explainFirst` when not granted, since
     * its screen can always be opened again; or [PermissionStatus.Unavailable] where the access does not
     * exist. A platform call that throws reads as not granted, and is logged.
     */
    fun status(access: AndroidPermission.SpecialAccess): PermissionStatus {
        val granted: Boolean? = try {
            isGranted(access)
        } catch (cause: Exception) {
            logger.w(cause) { "Checking $access threw — treating it as not granted" }
            false
        }
        return when (granted) {
            null -> PermissionStatus.Unavailable
            true -> PermissionStatus.Granted
            false -> PermissionStatus.Denied(explainFirst = true)
        }
    }

    /** `null` when the access does not exist on this API level or this build. */
    private fun isGranted(access: AndroidPermission.SpecialAccess): Boolean? = when (access) {
        AndroidPermission.ExactAlarm -> canScheduleExactAlarms()
        AndroidPermission.Overlay -> Settings.canDrawOverlays(context)
        AndroidPermission.WriteSettings -> Settings.System.canWrite(context)
        AndroidPermission.AllFilesAccess -> canManageExternalStorage()
        AndroidPermission.UsageStatsAccess -> hasUsageStatsAccess()
        AndroidPermission.IgnoreBatteryOptimizations -> isIgnoringBatteryOptimizations()
        AndroidPermission.NotificationListenerAccess ->
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        AndroidPermission.DoNotDisturbAccess -> isNotificationPolicyAccessGranted()
        AndroidPermission.MediaManagement ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null else MediaStore.canManageMedia(context)
        AndroidPermission.InstallPackages ->
            // Below API 26 there is only the device-wide "Unknown sources" switch: a different thing
            // under the same words, so not reported as this app's grant.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) null else context.packageManager.canRequestPackageInstalls()
        AndroidPermission.FullScreenNotifications -> canUseFullScreenIntent()
        AndroidPermission.KeepPermissionsWhenUnused ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || context.packageManager.isAutoRevokeWhitelisted
        is AndroidPermission.AccessibilityService -> isAccessibilityServiceEnabled(access.component)
    }

    /**
     * The intents that open [access]'s Settings screen, most specific first, without launch flags; empty
     * when there is nothing to open on this API level.
     *
     * Where the platform has both, this app's own page comes first and the generic list of the same
     * access second: an OEM Settings app that drops the package-specific screen usually keeps the list,
     * and the list is still one tap from the toggle.
     */
    fun candidates(access: AndroidPermission.SpecialAccess): List<Intent> = when (access) {
        // Below API 31 there is no such screen, and exact alarms need nothing.
        AndroidPermission.ExactAlarm -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            emptyList()
        } else {
            thisPackageThenList(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        }

        AndroidPermission.Overlay -> thisPackageThenList(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        AndroidPermission.WriteSettings -> thisPackageThenList(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        // Not applicable below API 30. The list is a separate action.
        AndroidPermission.AllFilesAccess -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            emptyList()
        } else {
            listOf(
                forThisPackage(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
        }

        // Already the generic list; the platform has no public per-app usage-access screen.
        AndroidPermission.UsageStatsAccess -> listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            },
        )
        // A system dialog rather than a Settings page, but a screen all the same. Its fallback is the
        // Settings list the dialog adds the app to.
        AndroidPermission.IgnoreBatteryOptimizations -> listOf(
            forThisPackage(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )

        // Only the generic list: the per-app pages need a listener component (notification listener)
        // or are system-only (Do Not Disturb).
        AndroidPermission.NotificationListenerAccess -> listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        AndroidPermission.DoNotDisturbAccess -> listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))

        // The next three have only this app's own page: each action requires the package: URI, and the
        // platform has no public list action for them. Below the API level that adds the access there
        // is nothing to open.
        AndroidPermission.MediaManagement -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            emptyList()
        } else {
            listOf(forThisPackage(Settings.ACTION_REQUEST_MANAGE_MEDIA))
        }

        AndroidPermission.FullScreenNotifications -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            emptyList()
        } else {
            listOf(forThisPackage(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT))
        }

        AndroidPermission.KeepPermissionsWhenUnused -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            emptyList()
        } else {
            listOf(forThisPackage(Intent.ACTION_AUTO_REVOKE_PERMISSIONS))
        }

        AndroidPermission.InstallPackages -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            emptyList()
        } else {
            thisPackageThenList(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        }

        // Only the list: the per-service page is not a public action.
        is AndroidPermission.AccessibilityService -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** [action] for this app, then [action] alone — for the screens whose package URI is optional. */
    private fun thisPackageThenList(action: String): List<Intent> = listOf(forThisPackage(action), Intent(action))

    /** [action] deep-linked to this app's own entry via a `package:` URI. */
    private fun forThisPackage(action: String): Intent =
        Intent(action, Uri.fromParts("package", context.packageName, null))

    /**
     * Whether [component] is among the enabled accessibility services; `null` when the package manager
     * does not know it as a service.
     */
    private fun isAccessibilityServiceEnabled(component: ComponentName): Boolean? {
        try {
            context.packageManager.getServiceInfo(component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val enabled: String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        // A colon-separated list of flattened component names, short or long form.
        return enabled.split(':').any { entry -> ComponentName.unflattenFromString(entry) == component }
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager: AlarmManager? = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        return alarmManager?.canScheduleExactAlarms() ?: false
    }

    private fun canManageExternalStorage(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        // Known to throw on some OEM/OS builds when it cannot resolve the current user's storage
        // volumes; status() turns that into "not granted".
        return Environment.isExternalStorageManager()
    }

    private fun hasUsageStatsAccess(): Boolean {
        val appOps: AppOpsManager? = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        // unsafeCheckOpNoThrow only exists from API 29 — below that the identically-behaved (and then
        // non-deprecated) checkOpNoThrow must be used, or this throws NoSuchMethodError.
        val mode: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } ?: AppOpsManager.MODE_DEFAULT
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager: PowerManager? = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    private fun isNotificationPolicyAccessGranted(): Boolean {
        val notificationManager: NotificationManager? =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return notificationManager?.isNotificationPolicyAccessGranted ?: false
    }

    /**
     * From API 34 the user's grant. Below it an install-time permission: there exactly when the manifest
     * declares it — which 2.x did not check, and reported granted for an app that could not use it.
     * A manifest that cannot be read is given the benefit of the doubt.
     */
    private fun canUseFullScreenIntent(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val declared: Set<String> = declaredPermissions() ?: return true
            return if (Manifest.permission.USE_FULL_SCREEN_INTENT in declared) true else null
        }
        val notificationManager: NotificationManager? =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return notificationManager?.canUseFullScreenIntent() ?: false
    }
}
