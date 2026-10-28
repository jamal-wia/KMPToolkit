package io.github.jamal_wia.kmptoolkit.permission

import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.w

/**
 * The platform side of the grants that are a system screen answering with a result — a
 * [AndroidPermission.Role] and a [AndroidPermission.DeviceAdmin]: whether each is held, and the intent
 * that asks for it. The bookkeeping around them is the controller's.
 *
 * Reads the real API level, like [SpecialAccesses]: `RoleManager` exists only from API 29.
 */
internal class ResultGrants(
    private val context: Context,
    private val logger: Logger,
) {

    /** Whether this app holds [role]; `null` when the role does not exist here. A throw reads as not held. */
    fun isHeld(role: AndroidPermission.Role): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roleManager: RoleManager = context.getSystemService(RoleManager::class.java) ?: return null
        return try {
            if (!roleManager.isRoleAvailable(role.name)) null else roleManager.isRoleHeld(role.name)
        } catch (cause: Exception) {
            logger.w(cause) { "Checking $role threw — treating it as not held" }
            false
        }
    }

    /** The system dialog asking for [role]; `null` when there is none to show. */
    fun requestIntent(role: AndroidPermission.Role): Intent? = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            null
        } else {
            context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(role.name)
        }
    } catch (cause: Exception) {
        logger.w(cause) { "Could not create the request for $role" }
        null
    }

    /** The screen where the user picks every default app, roles among them. */
    fun defaultAppsSettings(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

    /** Whether [admin] is active; `null` when the package manager does not know its receiver. */
    fun isActive(admin: AndroidPermission.DeviceAdmin): Boolean? {
        try {
            context.packageManager.getReceiverInfo(admin.component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val policyManager: DevicePolicyManager? =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        return policyManager?.isAdminActive(admin.component) ?: false
    }

    /** The system's "Activate device admin app" screen for [admin]. */
    fun requestIntent(admin: AndroidPermission.DeviceAdmin): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin.component)

    /** The security settings, which list the device admin apps. */
    fun securitySettings(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
}
