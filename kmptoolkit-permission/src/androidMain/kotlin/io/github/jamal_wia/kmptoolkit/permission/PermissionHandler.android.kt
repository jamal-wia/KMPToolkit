package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.platform.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The value written under an [askedKey]. Its presence is the flag; the text is for a human reading a dump. */
private const val ASKED: String = "true"

/**
 * Creates the Android [PermissionHandler].
 *
 * Four collaborators, each for a reason Android forces on us:
 *
 * - **[host]** shows the system dialog. It is yours to implement because an
 *   `ActivityResultLauncher` belongs to an activity — see [PermissionRequestHost].
 * - **[activityAccess]** answers `shouldShowRequestPermissionRationale`, which only an `Activity`
 *   can answer, and opens the settings screen from the foreground activity when there is one. No
 *   activity is retained: the access is scoped per call.
 * - **[storage]** holds one flag per permission. Android cannot distinguish "never asked" from
 *   "permanently denied" on its own — both look identical through its API — and without that flag
 *   a first-run app sends users to settings for a permission it never asked for. See
 *   [PermissionConfig].
 * - **[config]** decides the key prefix, defaulting to the consuming app's own package name.
 *
 * The handler declares no permission of its own; every permission it can request must be in
 * **your** `AndroidManifest.xml`, or the system dialog never appears and the request comes straight
 * back denied. See `docs/kmptoolkit-permission/05-platform-notes.md`.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param logger where a dialog that could not be shown, or an unreadable flag, is reported.
 */
public fun createPermissionHandler(
    context: Context,
    host: PermissionRequestHost,
    activityAccess: ActivityAccess,
    storage: KeyValueStorage,
    config: PermissionConfig = PermissionConfig(),
    logger: Logger = NoopLogger,
): PermissionHandler {
    val applicationContext: Context = context.applicationContext
    return AndroidPermissionHandler(
        context = applicationContext,
        host = host,
        storage = storage,
        keyPrefix = config.resolveKeyPrefix(applicationContext.packageName),
        logger = logger,
        sdkInt = Build.VERSION.SDK_INT,
        shouldShowRationale = { androidPermission ->
            activityAccess.withActivity { activity ->
                activity.shouldShowRequestPermissionRationale(androidPermission)
            } == true
        },
        startSettings = { intent ->
            // Preferred from the resumed activity: an activity-started settings screen sits on the
            // app's own task, so the system back button returns to the screen that asked. The
            // application-context fallback needs FLAG_ACTIVITY_NEW_TASK and lands in a task of its
            // own, which is worse but still better than not opening at all.
            val fromActivity: Boolean = activityAccess.withActivity { activity ->
                runCatching { activity.startActivity(intent) }.isSuccess
            } == true
            fromActivity || runCatching {
                applicationContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        },
    )
}

/**
 * Android's [PermissionHandler].
 *
 * Internal, and constructed with lambdas rather than an [ActivityAccess], so that every branch of
 * the status logic — including the two that depend on an `Activity` and the one that depends on the
 * API level — is reachable from a Robolectric unit test without an activity or an SDK switch.
 *
 * The status logic in one paragraph. Granted is granted, and grant clears the flag, so a permission
 * the user later revokes (or that Android auto-resets for an unused app) reads as never asked
 * again — which is exactly right, because the system dialog will appear for it again. Not granted
 * plus `shouldShowRequestPermissionRationale` is a first refusal. Not granted, no rationale, and
 * the flag set is a permanent refusal. Not granted, no rationale, and no flag is a permission we
 * have simply never asked for.
 */
internal class AndroidPermissionHandler(
    private val context: Context,
    private val host: PermissionRequestHost,
    private val storage: KeyValueStorage,
    private val keyPrefix: String,
    private val logger: Logger,
    private val sdkInt: Int,
    private val shouldShowRationale: (String) -> Boolean,
    private val startSettings: (Intent) -> Boolean,
) : PermissionHandler {

    override suspend fun check(permission: Permission): PermissionStatus = currentStatus(permission)

    override suspend fun request(permission: Permission): PermissionStatus {
        val current: PermissionStatus = currentStatus(permission)
        if (current is PermissionStatus.Granted || current is PermissionStatus.PermanentlyDenied) {
            logger.d { "Not showing a dialog for $permission: already $current" }
            return current
        }

        val androidPermission: String = permission.androidPermission()
        val granted: Boolean = launchDialog(androidPermission) ?: return current

        return if (granted) {
            clearAsked(permission)
            PermissionStatus.Granted
        } else {
            // Recorded only now, after the dialog actually resolved. Recording it before launching
            // would turn a dialog that never appeared into a permanent denial the user never made.
            markAsked(permission)
            currentStatus(permission)
        }
    }

    override fun openAppSettings(): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        return runCatching { startSettings(intent) }.getOrElse { cause ->
            logger.w(cause) { "Could not open the application settings screen" }
            false
        }
    }

    private fun currentStatus(permission: Permission): PermissionStatus {
        if (permission == Permission.NOTIFICATIONS && sdkInt < Build.VERSION_CODES.TIRAMISU) {
            // There is no runtime grant to obtain before API 33 — POST_NOTIFICATIONS did not exist
            // and notifications were allowed by default. Whether the user has since switched the
            // app's notifications off in system settings is a different question, and not one a
            // permission API answers; see 05-platform-notes.md.
            return PermissionStatus.Granted
        }

        val androidPermission: String = permission.androidPermission()
        if (context.checkSelfPermission(androidPermission) == PackageManager.PERMISSION_GRANTED) {
            clearAsked(permission)
            return PermissionStatus.Granted
        }
        if (shouldShowRationale(androidPermission)) return PermissionStatus.Denied(shouldShowRationale = true)
        return if (wasAsked(permission)) {
            PermissionStatus.PermanentlyDenied
        } else {
            PermissionStatus.NotDetermined
        }
    }

    /**
     * Shows the dialog and waits.
     *
     * @return `true`/`false` as the user answered, or `null` when the host could not show anything
     *   — a distinction the caller needs, because "no dialog appeared" must not be recorded as a
     *   refusal.
     */
    private suspend fun launchDialog(androidPermission: String): Boolean? =
        suspendCancellableCoroutine { continuation ->
            // A host that both reports failure and invokes the callback breaks its contract; this
            // guarantees the continuation is resumed exactly once regardless.
            var delivered = false
            val launched: Boolean = runCatching {
                host.launch(androidPermission) { granted ->
                    if (!delivered) {
                        delivered = true
                        if (continuation.isActive) continuation.resume(granted)
                    }
                }
            }.getOrElse { cause ->
                logger.w(cause) { "The permission request host threw while launching" }
                false
            }
            if (!launched && !delivered) {
                logger.w { "The permission dialog for $androidPermission could not be shown" }
                delivered = true
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /**
     * The `android.Manifest.permission` string this [Permission] maps to.
     *
     * Notifications are the only API-level-dependent case left in the catalog, and the branch is
     * about the *constant*, not about behavior: below API 33 [currentStatus] never gets here.
     */
    private fun Permission.androidPermission(): String = when (this) {
        Permission.NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
        Permission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
        Permission.CAMERA -> Manifest.permission.CAMERA
    }

    private fun wasAsked(permission: Permission): Boolean =
        storage.getStringOrNull(askedKey(keyPrefix, permission)) == ASKED

    private fun markAsked(permission: Permission) {
        storage.put(askedKey(keyPrefix, permission), ASKED)
    }

    /**
     * Removes the flag, but only when there is one to remove.
     *
     * The guard is what keeps [check] a query. It runs on every check of a granted permission — the
     * overwhelmingly common call — and the overwhelmingly common state there is "granted, nothing
     * stored", where an unconditional `remove` would turn each check into a persistent write. A
     * consumer polling a permission per UI frame (`kmptoolkit-notification` checks before every
     * `post`, including the progress frames its coalescer then suppresses) would otherwise pay a
     * hundred writes for a 0..100 progress loop.
     *
     * The read that replaces them is the same one [wasAsked] already does on the not-granted path:
     * a `SharedPreferences` lookup, served from the in-memory map that backs it, with no disk
     * access and no commit. That is cheap enough that no "already cleared" memo is needed here —
     * and a memo would be the wrong trade anyway, since it would be state that has to stay correct
     * across a handler outliving a process boundary.
     */
    private fun clearAsked(permission: Permission) {
        val key: String = askedKey(keyPrefix, permission)
        if (storage.getStringOrNull(key) != null) storage.remove(key)
    }
}
