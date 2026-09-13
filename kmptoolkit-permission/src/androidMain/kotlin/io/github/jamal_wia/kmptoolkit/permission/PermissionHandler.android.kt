package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** The value written under a [rationaleSeenKey]. Its presence is the flag; the text is for a human reading a dump. */
private const val FLAG_SET: String = "true"

/**
 * The value this version writes under an [askedKey].
 *
 * Deliberately not [LEGACY_ASKED]: an asked flag written by 1.3.x or earlier was written without the
 * refusal flag next to it, so for such a flag "never refused" is unknown rather than known. Telling
 * the two apart by value is what lets a permission those versions recorded as permanently denied stay
 * permanently denied, instead of turning into a request that returns at once forever.
 */
private const val ASKED: String = "dialog-shown"

/** The value versions before 1.4.0 wrote under an [askedKey]. */
private const val LEGACY_ASKED: String = "true"

/**
 * Creates the Android [PermissionHandler].
 *
 * Four collaborators, each for a reason Android forces on us:
 *
 * - **[host]** shows the system dialog. It is yours to implement because an
 *   `ActivityResultLauncher` belongs to an activity — see [PermissionRequestHost].
 * - An internally tracked activity answers `shouldShowRequestPermissionRationale`, which only an
 *   `Activity` can answer, and opens the settings screen from the foreground activity when there
 *   is one. No activity is retained: the access is scoped per call. When your app already has an
 *   `ActivityAccess`, pass it through the other overload instead.
 * - **[storage]** holds two flags per permission. Android cannot distinguish "never asked",
 *   "dismissed" and "permanently denied" on its own — all three look identical through its API — and
 *   without them a first-run app, or a user who backed out of the dialog, is sent to settings for a
 *   permission the system would still happily ask for. See [PermissionConfig].
 * - **[config]** decides the key prefix, defaulting to the consuming app's own package name.
 *
 * The handler declares no permission of its own; every permission it can request must be in
 * **your** `AndroidManifest.xml`, or the system dialog never appears and the request comes straight
 * back denied. See `docs/kmptoolkit-permission/05-platform-notes.md`.
 *
 * Create it in `Application.onCreate`, like the activity tracker it creates: a tracker created
 * after the first activity resumed does not know that activity until it resumes again.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param logger where a dialog that could not be shown, or an unreadable flag, is reported.
 */
public fun createPermissionHandler(
    context: Context,
    host: PermissionRequestHost,
    storage: KeyValueStorage,
    config: PermissionConfig = PermissionConfig(),
    logger: Logger = NoopLogger,
): PermissionHandler {
    val applicationContext: Context = context.applicationContext
    return createPermissionHandler(
        context = applicationContext,
        host = host,
        storage = storage,
        activityAccess = createActivityAccess(applicationContext as Application),
        config = config,
        logger = logger,
    )
}

/**
 * Creates the Android [PermissionHandler] on an [ActivityAccess] your app already owns.
 *
 * The same handler as the overload without it; use this one when the app has its own tracker —
 * typically one narrowed with `isTracked` to the activities it owns. The handler then asks exactly
 * that activity for `shouldShowRequestPermissionRationale`, and opens settings from it, rather than
 * from whichever activity resumed last, and the app does not register a second tracker.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param activityAccess the tracker the rationale question and the settings screen go through.
 *   It must have been created before the activity that requests a permission first resumed.
 * @param logger where a dialog that could not be shown, or an unreadable flag, is reported.
 * @since 1.4.0
 */
public fun createPermissionHandler(
    context: Context,
    host: PermissionRequestHost,
    storage: KeyValueStorage,
    activityAccess: ActivityAccess,
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
            }
        },
        awaitActivity = { activityAccess.awaitResumed(RESUME_WAIT) },
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
 * How long a request waits, after the dialog answered, for the activity to be resumed again so the
 * rationale can be read. The answer is delivered just before `onResume`; this covers a caller whose
 * continuation runs in that gap, and is long enough for any real resume and short enough that a
 * backgrounded app does not keep the caller waiting.
 */
private val RESUME_WAIT: Duration = 1.seconds

/** Suspends until an activity is resumed (at once if one is), or [timeout] passes. */
private suspend fun ActivityAccess.awaitResumed(timeout: Duration) {
    var subscription: ActivitySubscription? = null
    try {
        withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { continuation ->
                // The listener fires synchronously, from inside this call, when an activity is already
                // resumed; resuming the continuation from there is fine, and the guard keeps a later
                // resume from resuming it twice.
                subscription = addOnActivityResumedListener {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    } finally {
        subscription?.cancel()
    }
}

/**
 * Android's [PermissionHandler].
 *
 * Internal, and constructed with lambdas rather than an [ActivityAccess], so that every branch of
 * the status logic — including the ones that depend on an `Activity` and the one that depends on the
 * API level — is reachable from a Robolectric unit test without an activity or an SDK switch.
 *
 * The status logic, which is Android's own dialog policy read back through the one question it
 * answers (`shouldShowRequestPermissionRationale`) plus two remembered facts:
 *
 * - **Granted** is granted, and a grant clears both facts, so a permission the user later revokes
 *   (or that Android auto-resets for an unused app) reads as never asked again — right, because the
 *   dialog will appear for it again.
 * - **Rationale `true`** is a refusal after which the dialog still appears: `Denied(true)`. It is also
 *   the only reliable sign that the user has *refused* the permission through the dialog, so it is
 *   remembered ("rationale seen").
 * - **Rationale `false`, asked, and a refusal remembered** is a permanent refusal — the second
 *   "Don't allow" on Android 11+, "Don't ask again" before it. So is an asked flag written by a
 *   version before 1.4.0, which recorded no refusals and read every such state as permanent.
 * - **Rationale `false`, asked, but never refused** is a dialog the user dismissed — back, or a tap
 *   outside it. On Android 11+ that is not a refusal at all and the dialog appears again, so it reads
 *   `NotDetermined`. Treating it as permanent was a bug: it sent every user who backed out of the
 *   first dialog to settings, for good.
 * - **Rationale `false`, never asked** is a permission we have simply never asked for.
 * - **No activity to ask** decides nothing permanent: `NotDetermined` if never asked, `Denied(false)`
 *   otherwise. The answer is read again, with an activity, on the next call.
 *
 * What this cannot see, and neither can any app: a permission the user set to "Don't allow" in system
 * settings before the app ever asked. Android then refuses without a dialog and without a rationale,
 * which reads exactly like a dismissal — `NotDetermined`, with a request that returns at once.
 */
internal class AndroidPermissionHandler(
    private val context: Context,
    private val host: PermissionRequestHost,
    private val storage: KeyValueStorage,
    private val keyPrefix: String,
    private val logger: Logger,
    private val sdkInt: Int,
    /** `null` when there is no activity to ask right now. */
    private val shouldShowRationale: (String) -> Boolean?,
    /** Suspends, briefly and at most once per call, until an activity can be asked. */
    private val awaitActivity: suspend () -> Unit,
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
            clearFlags(permission)
            PermissionStatus.Granted
        } else {
            // Recorded only now, after the dialog actually resolved. Recording it before launching
            // would turn a dialog that never appeared into a permanent denial the user never made.
            markAsked(permission)
            // The answer arrives just before the activity is resumed again, and a continuation that
            // runs in that gap finds no activity to ask for the rationale. Waiting for the resume
            // turns "cannot tell" back into an answer in all but a backgrounded app.
            if (shouldShowRationale(androidPermission) == null) awaitActivity()
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
            clearFlags(permission)
            return PermissionStatus.Granted
        }
        val askedValue: String? = storage.getStringOrNull(askedKey(keyPrefix, permission))
        val asked: Boolean = askedValue != null
        return when (shouldShowRationale(androidPermission)) {
            true -> {
                markFlag(rationaleSeenKey(keyPrefix, permission))
                PermissionStatus.Denied(shouldShowRationale = true)
            }

            false -> when {
                !asked -> PermissionStatus.NotDetermined
                isSet(rationaleSeenKey(keyPrefix, permission)) -> PermissionStatus.PermanentlyDenied
                // Written by a version that never recorded refusals: read it as that version did.
                askedValue == LEGACY_ASKED -> PermissionStatus.PermanentlyDenied
                // Asked, never refused: the dialog was dismissed, and the system will show it again.
                else -> PermissionStatus.NotDetermined
            }

            null -> if (asked) PermissionStatus.Denied(shouldShowRationale = false) else PermissionStatus.NotDetermined
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

    private fun isSet(key: String): Boolean = storage.getStringOrNull(key) == FLAG_SET

    /** Records that the dialog was shown, leaving a flag an earlier version wrote as it is. */
    private fun markAsked(permission: Permission) {
        val key: String = askedKey(keyPrefix, permission)
        if (storage.getStringOrNull(key) == null) storage.put(key, ASKED)
    }

    /**
     * Sets a flag, but only when it is not set already — which keeps a repeated [check] of a
     * permission that keeps asking for a rationale from writing on every call.
     */
    private fun markFlag(key: String) {
        if (!isSet(key)) storage.put(key, FLAG_SET)
    }

    /**
     * Removes both flags, but only those there are to remove.
     *
     * The guard is what keeps [check] a query. It runs on every check of a granted permission — the
     * overwhelmingly common call — and the overwhelmingly common state there is "granted, nothing
     * stored", where an unconditional `remove` would turn each check into a persistent write. A
     * consumer polling a permission per UI frame would otherwise pay a hundred writes for a 0..100
     * progress loop.
     *
     * The reads that replace them are `SharedPreferences` lookups, served from the in-memory map that
     * backs it, with no disk access and no commit.
     */
    private fun clearFlags(permission: Permission) {
        listOf(askedKey(keyPrefix, permission), rationaleSeenKey(keyPrefix, permission)).forEach { key: String ->
            if (storage.getStringOrNull(key) != null) storage.remove(key)
        }
    }
}
