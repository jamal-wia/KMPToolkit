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
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenKind
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w

/**
 * Creates the Android [SpecialPermissionHandler].
 *
 * Settings screens open through [SystemScreenLauncher.SeparateTask]: a task of their own, never a
 * Settings task left in the background. To open them on your app's own task instead, or to decide
 * yourself, use [createSpecialPermissionHandlerWithLauncher].
 *
 * @param context any `Context`; its application context is what gets retained. Both operations are
 *   context-level, so this does not depend on an Activity.
 * @param logger where a Settings screen that could not be opened, or a platform check that threw, is
 *   reported.
 */
public fun createSpecialPermissionHandler(
    context: Context,
    logger: Logger = NoopLogger,
): SpecialPermissionHandler =
    AndroidSpecialPermissionHandler(context.applicationContext, logger, SystemScreenLauncher.SeparateTask)

/**
 * Creates the Android [SpecialPermissionHandler] with the [SystemScreenLauncher] its Settings screens
 * open through.
 *
 * The same handler as [createSpecialPermissionHandler], which uses [SystemScreenLauncher.SeparateTask].
 * For an ordinary app, pass `SystemScreenLauncher.callerTask(activityAccess)`: the screen is started
 * from your resumed activity, on your task, so the system Back button returns to it and a two-pane
 * Settings shows it in a single pane. Not for a lock-task (kiosk) app — see
 * [SystemScreenLauncher.callerTask].
 *
 * Each [SpecialPermissionHandler.requestViaSettings] call that has a screen to open calls
 * [systemScreenLauncher] exactly once, with a [SystemScreenRequest] whose `kind` is a
 * [SpecialPermissionScreen] naming the permission and whose candidates are one or more intents,
 * most specific first, without launch flags — currently the screen for this app, then the generic
 * list of the same permission where the platform has one. A launcher that returns `false` or throws
 * makes `requestViaSettings` return `false`, and is logged. An entry that has nothing to open on this
 * API level (`EXACT_ALARM` below API 31, `ALL_FILES_ACCESS` below API 30) never reaches the launcher.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param systemScreenLauncher decides how, and in which task, each Settings screen opens.
 * @param logger where a Settings screen that could not be opened, or a platform check that threw, is
 *   reported.
 * @since 1.7.0
 */
public fun createSpecialPermissionHandlerWithLauncher(
    context: Context,
    systemScreenLauncher: SystemScreenLauncher,
    logger: Logger = NoopLogger,
): SpecialPermissionHandler =
    AndroidSpecialPermissionHandler(context.applicationContext, logger, systemScreenLauncher)

/**
 * The Settings screen that grants [permission], as the `kind` of the [SystemScreenRequest]
 * [SpecialPermissionHandler.requestViaSettings] hands to a [SystemScreenLauncher].
 *
 * Two instances are equal when they name the same [permission], so a launcher can match one screen:
 *
 * ```kotlin
 * when (request.kind) {
 *     SpecialPermissionScreen(SpecialPermission.OVERLAY) -> ...
 *     is SpecialPermissionScreen -> ...
 *     else -> ...
 * }
 * ```
 *
 * @since 1.7.0
 */
public class SpecialPermissionScreen(
    /** The special permission whose Settings screen this is. */
    public val permission: SpecialPermission,
) : SystemScreenKind {

    override fun equals(other: Any?): Boolean =
        this === other || (other is SpecialPermissionScreen && other.permission == permission)

    override fun hashCode(): Int = permission.hashCode()

    override fun toString(): String = "SpecialPermissionScreen($permission)"
}

/**
 * Android [SpecialPermissionHandler].
 *
 * [SpecialPermission.EXACT_ALARM] maps to `SCHEDULE_EXACT_ALARM`: granted-check via
 * [AlarmManager.canScheduleExactAlarms] (API 31+, always granted below); "request" opens the system
 * exact-alarm screen ([Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM]). The other seven entries follow
 * the same granted-check-plus-Settings-screen shape, one platform API each.
 *
 * Every screen is opened through [launcher], once per request, with intents that carry no launch
 * flags — which task the screen lands in is the launcher's decision, not this class's.
 */
internal class AndroidSpecialPermissionHandler(
    private val context: Context,
    private val logger: Logger,
    private val launcher: SystemScreenLauncher,
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

    override fun requestViaSettings(permission: SpecialPermission): Boolean {
        val candidates: List<Intent> = settingsCandidates(permission)
        if (candidates.isEmpty()) return false
        val request = SystemScreenRequest(candidates, context, SpecialPermissionScreen(permission))
        val launched: Boolean = try {
            launcher.launch(request)
        } catch (cause: Exception) {
            logger.w(cause) { "The system screen launcher threw while opening settings for $permission" }
            return false
        }
        if (!launched) logger.w { "Could not open settings for $permission" }
        return launched
    }

    /**
     * The intents that open [permission]'s Settings screen, most specific first, without launch flags;
     * empty when there is nothing to open on this API level.
     *
     * Where the platform has both, this app's own page comes first and the generic list of the same
     * permission second: an OEM Settings app that drops the package-specific screen usually keeps the
     * list, and the list is still one tap from the toggle.
     */
    private fun settingsCandidates(permission: SpecialPermission): List<Intent> = when (permission) {
        // Below API 31 there is no such screen and the permission is already granted — nothing to open.
        SpecialPermission.EXACT_ALARM -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            emptyList()
        } else {
            thisPackageThenList(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        }

        SpecialPermission.OVERLAY -> thisPackageThenList(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        SpecialPermission.WRITE_SETTINGS -> thisPackageThenList(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        // Not applicable below API 30 — always granted, nothing to open. The list is a separate action.
        SpecialPermission.ALL_FILES_ACCESS -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            emptyList()
        } else {
            listOf(
                forThisPackage(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
        }

        // Already the generic list; the platform has no public per-app usage-access screen.
        SpecialPermission.USAGE_STATS_ACCESS -> listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            },
        )
        // A system dialog rather than a Settings page, but a screen all the same: it goes through the
        // launcher like every other entry. Its fallback is the Settings list the dialog adds the app to.
        SpecialPermission.IGNORE_BATTERY_OPTIMIZATIONS -> listOf(
            forThisPackage(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )

        // Only the generic list: the per-app pages need a listener component (notification listener)
        // or are system-only (Do Not Disturb).
        SpecialPermission.NOTIFICATION_LISTENER_ACCESS -> listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        SpecialPermission.DO_NOT_DISTURB_ACCESS -> listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    /** [action] for this app, then [action] alone — for the screens whose package URI is optional. */
    private fun thisPackageThenList(action: String): List<Intent> = listOf(forThisPackage(action), Intent(action))

    /** [action] deep-linked to this app's own entry via a `package:` URI. */
    private fun forThisPackage(action: String): Intent =
        Intent(action, Uri.fromParts("package", context.packageName, null))

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager: AlarmManager? = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        return alarmManager?.canScheduleExactAlarms() ?: false
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
}
