package io.github.jamal_wia.kmptoolkit.permission

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w

/**
 * Creates the Android [SpecialPermissionHandler].
 *
 * @param context any `Context`; its application context is what gets retained. Both operations are
 *   context-level, so this does not depend on an Activity.
 * @param logger where a Settings screen that could not be opened, or a platform check that threw, is
 *   reported.
 */
public fun createSpecialPermissionHandler(
    context: Context,
    logger: Logger = NoopLogger,
): SpecialPermissionHandler = AndroidSpecialPermissionHandler(context.applicationContext, logger)

/**
 * Android [SpecialPermissionHandler].
 *
 * [SpecialPermission.EXACT_ALARM] maps to `SCHEDULE_EXACT_ALARM`: granted-check via
 * [AlarmManager.canScheduleExactAlarms] (API 31+, always granted below); "request" opens the system
 * exact-alarm screen ([Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM]). The other six entries follow
 * the same granted-check-plus-Settings-screen shape, one platform API each.
 */
internal class AndroidSpecialPermissionHandler(
    private val context: Context,
    private val logger: Logger,
) : SpecialPermissionHandler {

    override fun isGranted(permission: SpecialPermission): Boolean = when (permission) {
        SpecialPermission.EXACT_ALARM -> canScheduleExactAlarms()
        SpecialPermission.OVERLAY -> Settings.canDrawOverlays(context)
        SpecialPermission.WRITE_SETTINGS -> Settings.System.canWrite(context)
        SpecialPermission.ALL_FILES_ACCESS -> canManageExternalStorage()
        SpecialPermission.USAGE_STATS_ACCESS -> hasUsageStatsAccess()
        SpecialPermission.IGNORE_BATTERY_OPTIMIZATIONS -> isIgnoringBatteryOptimizations()
        SpecialPermission.NOTIFICATION_LISTENER_ACCESS -> isNotificationListenerEnabled()
        SpecialPermission.DO_NOT_DISTURB_ACCESS -> isNotificationPolicyAccessGranted()
    }

    override fun requestViaSettings(permission: SpecialPermission): Boolean = when (permission) {
        SpecialPermission.EXACT_ALARM -> openExactAlarmSettings()
        SpecialPermission.OVERLAY -> openSettingsForThisPackage(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        SpecialPermission.WRITE_SETTINGS -> openSettingsForThisPackage(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        SpecialPermission.ALL_FILES_ACCESS -> openAllFilesAccessSettings()
        SpecialPermission.USAGE_STATS_ACCESS -> openUsageAccessSettings()
        SpecialPermission.IGNORE_BATTERY_OPTIMIZATIONS -> openIgnoreBatteryOptimizationsSettings()
        SpecialPermission.NOTIFICATION_LISTENER_ACCESS ->
            openGenericSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        SpecialPermission.DO_NOT_DISTURB_ACCESS ->
            openGenericSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager: AlarmManager? = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        return alarmManager?.canScheduleExactAlarms() ?: false
    }

    /** @return `true` if the exact-alarm Settings screen was launched, `false` otherwise. */
    private fun openExactAlarmSettings(): Boolean {
        // Below API 31 there is no such screen and the permission is already granted — nothing to open.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                // Deep-link straight to this app's toggle rather than the generic list.
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse { cause ->
            logger.w(cause) { "Failed to open exact-alarm settings" }
            false
        }
    }

    private fun canManageExternalStorage(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        // Known to throw on some OEM/OS builds when it cannot resolve the current user's storage
        // volumes — treat a crash as "not granted" rather than crash the caller.
        return runCatching { Environment.isExternalStorageManager() }.getOrElse { cause ->
            logger.w(cause) { "isExternalStorageManager() threw — treating ALL_FILES_ACCESS as ungranted" }
            false
        }
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

    private fun isNotificationListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun isNotificationPolicyAccessGranted(): Boolean {
        val notificationManager: NotificationManager? =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return notificationManager?.isNotificationPolicyAccessGranted ?: false
    }

    /** Opens [action] deep-linked to this app's own entry via a `package:` URI. */
    private fun openSettingsForThisPackage(action: String): Boolean = runCatching {
        val intent = Intent(action).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse { cause ->
        logger.w(cause) { "Failed to open settings for action=$action" }
        false
    }

    /** Opens [action] with no data URI — some special-access screens only accept the generic list. */
    private fun openGenericSettings(action: String): Boolean = runCatching {
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        true
    }.getOrElse { cause ->
        logger.w(cause) { "Failed to open settings for action=$action" }
        false
    }

    private fun openAllFilesAccessSettings(): Boolean {
        // Not applicable below API 30 — always granted, nothing to open.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return openSettingsForThisPackage(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
    }

    private fun openUsageAccessSettings(): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
        context.startActivity(intent)
        true
    }.getOrElse { cause ->
        logger.w(cause) { "Failed to open usage-access settings" }
        false
    }

    private fun openIgnoreBatteryOptimizationsSettings(): Boolean =
        openSettingsForThisPackage(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
}
