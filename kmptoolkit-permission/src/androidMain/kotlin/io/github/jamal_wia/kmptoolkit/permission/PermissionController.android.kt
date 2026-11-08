package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenKind
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What [AndroidPermissionController.launchForResult] delivers for a request the library's own host ended
 * as interrupted. Never a result code a system screen produces (those are `RESULT_OK`, `RESULT_CANCELED`
 * and small positive custom codes).
 */
private const val INTERRUPTED: Int = Int.MIN_VALUE

/**
 * The entries with a dialog of their own that [AndroidPermissionController.coveredBy] can share a learned
 * refusal with: the catalog's, which is all anyone can know of ahead of time.
 */
private val MIRRORABLE_ENTRIES: List<Permission> = CommonPermission.entries + listOf(
    AndroidPermission.NearbyWifiDevices,
    AndroidPermission.LocalNetwork,
    AndroidPermission.BodySensors,
    AndroidPermission.BodySensorsBackground,
)

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

/** The value versions before 1.5.0 wrote under an [askedKey]. */
private const val LEGACY_ASKED: String = "true"

/**
 * Creates the Android [PermissionController].
 *
 * Every collaborator but [context] and [storage] has a default, and each exists for a reason:
 *
 * - **[storage]** holds two flags per runtime permission. Android cannot distinguish "never asked",
 *   "dismissed" and "permanently denied" on its own — all three look identical through its API — and
 *   without them a first-run app, or a user who backed out of the dialog, is sent to settings for a
 *   permission the system would still happily ask for. See [AndroidPermissionConfig].
 * - **[activityAccess]** answers `shouldShowRequestPermissionRationale`, which only an `Activity` can
 *   answer, tells the controller when the user is back from a Settings screen, and is where screens
 *   open from. The default tracks activities itself; it must then be created in
 *   `Application.onCreate`, because a tracker created after the first activity resumed does not know
 *   that activity until it resumes again. Pass your app's own access to narrow it to your activities.
 *   Each call without one registers a lifecycle callback with the `Application` that lives as long as
 *   the process, so build the controller once, and pass one shared access if you build more than one.
 * - **[host]** shows the system dialog and the screens that answer with a result. The default,
 *   [ActivityResultPermissionRequestHost] on [activityAccess], needs no wiring and survives an activity
 *   recreated while a dialog is up; pass your own [PermissionRequestHost] if your app already owns that
 *   plumbing. The controller always calls it on the main thread.
 * - **[systemScreenLauncher]** decides how Settings screens open. The default,
 *   `SystemScreenLauncher.callerTask(activityAccess)`, opens them from the resumed activity on your
 *   task, and in a task of their own with no activity resumed. A lock-task (kiosk) app passes
 *   `SystemScreenLauncher.SeparateTask` or its own launcher — see `kmptoolkit-activity`.
 *
 * Every screen the controller opens — a special access's page, a page from
 * [PermissionController.openSettings] — reaches [systemScreenLauncher] as exactly one
 * [SystemScreenRequest] whose `kind` is a [PermissionScreen] naming the permission, with one or more
 * candidate intents, most specific first, without launch flags. A launcher that returns `false` or
 * throws is logged, and the call reports nothing opened.
 *
 * The controller declares no permission of its own; every permission it can request must be in
 * **your** `AndroidManifest.xml`, or it reads [PermissionStatus.Unavailable] and a request shows
 * nothing. See `docs/kmptoolkit-permission/05-platform-notes.md`.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param logger where a dialog that could not be shown, an undeclared permission, an unreadable flag
 *   or a screen that could not be opened is reported.
 */
public fun createPermissionController(
    context: Context,
    storage: KeyValueStorage,
    activityAccess: ActivityAccess = createActivityAccess(context.applicationContext as Application),
    host: PermissionRequestHost = ActivityResultPermissionRequestHost(activityAccess),
    systemScreenLauncher: SystemScreenLauncher = SystemScreenLauncher.callerTask(activityAccess),
    config: AndroidPermissionConfig = AndroidPermissionConfig(),
    logger: Logger = NoopLogger,
): PermissionController {
    val applicationContext: Context = context.applicationContext
    val declared: Set<String>? by lazy { applicationContext.declaredPermissions(logger) }
    return AndroidPermissionController(
        context = applicationContext,
        host = host,
        storage = storage,
        keyPrefix = config.resolveKeyPrefix(applicationContext.packageName),
        logger = logger,
        sdkInt = Build.VERSION.SDK_INT,
        targetSdk = applicationContext.applicationInfo.targetSdkVersion,
        protectionOf = { androidPermission -> applicationContext.protectionOf(androidPermission) },
        shouldShowRationale = { androidPermission ->
            activityAccess.withActivity { activity ->
                activity.shouldShowRequestPermissionRationale(androidPermission)
            }
        },
        awaitActivity = { activityAccess.awaitResumed(RESUME_WAIT) },
        addResumedListener = { listener -> activityAccess.addOnActivityResumedListener { listener() } },
        launchScreen = systemScreenLauncher::launch,
        disavowsLocationForScan = { applicationContext.declaresScanNeverForLocation(logger) },
        declaredPermissions = { declared },
        isRevokedByPolicy = { androidPermission ->
            applicationContext.packageManager.isPermissionRevokedByPolicy(androidPermission, applicationContext.packageName)
        },
        notificationsEnabled = { NotificationManagerCompat.from(applicationContext).areNotificationsEnabled() },
        specialAccesses = SpecialAccesses(applicationContext, logger) { declared },
        resultGrants = ResultGrants(applicationContext, logger),
    )
}

/**
 * The Settings screen for [permission], as the `kind` of every [SystemScreenRequest] the Android
 * [PermissionController] hands its `SystemScreenLauncher` — a special access's own page, and every
 * page [PermissionController.openSettings] opens. A launcher can match one screen by equality:
 *
 * ```kotlin
 * when (request.kind) {
 *     PermissionScreen(AndroidPermission.Overlay) -> ...
 *     is PermissionScreen -> ...
 *     else -> ...
 * }
 * ```
 */
public data class PermissionScreen(
    /** The permission whose Settings screen this is. */
    public val permission: Permission,
) : SystemScreenKind

/**
 * The permissions this app's manifest declares, or `null` when they cannot be read — in which case
 * nothing is treated as undeclared.
 */
internal fun Context.declaredPermissions(logger: Logger): Set<String>? = try {
    @Suppress("DEPRECATION") // The flags overload is API 33+.
    val info: PackageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
    info.requestedPermissions?.toSet().orEmpty()
} catch (cause: Exception) {
    logger.w(cause) { "Could not read this app's declared permissions" }
    null
}

/**
 * [androidPermission]'s protection level on this device, or `null` when the device does not know it.
 */
internal fun Context.protectionOf(androidPermission: String): Protection? {
    val info: PermissionInfo = try {
        packageManager.getPermissionInfo(androidPermission, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    val base: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.protection
        } else {
            @Suppress("DEPRECATION") // getProtection() is API 28+.
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        }
    return if (base == PermissionInfo.PROTECTION_DANGEROUS) Protection.Dangerous else Protection.Other
}

/**
 * Whether this app's manifest declares `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"`.
 *
 * Android reads the same flag, from the same `PackageInfo`, to decide whether a scan's results need fine
 * location as well; `false` when the permission is not declared at all, or on an API level without
 * the flag (below 31, where it is never asked).
 */
internal fun Context.declaresScanNeverForLocation(logger: Logger): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    return try {
        @Suppress("DEPRECATION") // The flags overload is API 33+; this one is what API 31 and 32 have.
        val info: PackageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val index: Int = info.requestedPermissions?.indexOf(Manifest.permission.BLUETOOTH_SCAN) ?: -1
        val flags: Int = info.requestedPermissionsFlags?.getOrNull(index) ?: 0
        index >= 0 && flags and PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION != 0
    } catch (cause: Exception) {
        logger.w(cause) { "Could not read this app's declared permissions" }
        false
    }
}

/**
 * How long a request waits, after the dialog answered, for the activity to be resumed again so the
 * rationale can be read. The answer is delivered just before `onResume`; this covers a caller whose
 * continuation runs in that gap, and is long enough for any real resume and short enough that a
 * backgrounded app does not keep the caller waiting.
 */
private val RESUME_WAIT: Duration = 1.seconds

/**
 * How long a request queued behind a cancelled request waits for that request's system dialog, which
 * stays on screen, to be answered. A user can take a while over a dialog, but a caller must not hang
 * on one for ever; past this the request fails like a refused launch (status unchanged, nothing shown).
 */
private val STALE_DIALOG_TIMEOUT: Duration = 30.seconds

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
 * Android's [PermissionController].
 *
 * Internal, and constructed with lambdas rather than an [ActivityAccess], so that every branch of
 * the status logic — including the ones that depend on an `Activity`, on the manifest and on the API
 * level — is reachable from a Robolectric unit test without an activity or an SDK switch.
 *
 * The runtime status logic, which is Android's own dialog policy read back through the one question it
 * answers (`shouldShowRequestPermissionRationale`) plus two remembered facts:
 *
 * - **Granted** (or **Limited**) is a grant, and a grant clears both facts, so a permission the user
 *   later revokes (or that Android auto-resets for an unused app) reads as never asked again — right,
 *   because the dialog will appear for it again.
 * - **Undeclared** in the manifest is [PermissionStatus.Unavailable]: the dialog would never appear.
 * - **Revoked by a device policy** is [PermissionStatus.Restricted].
 * - **Rationale `true`** is a refusal after which the dialog still appears: `Denied(explainFirst = true)`.
 *   It is also the only reliable sign that the user has *refused* the permission through the dialog,
 *   so it is remembered ("rationale seen").
 * - **Rationale `false`, asked, and a refusal remembered** is a permanent refusal — the second
 *   "Don't allow" on Android 11+, "Don't ask again" before it. So is an asked flag written by a
 *   version before 1.5.0, which recorded no refusals and read every such state as permanent.
 * - **Rationale `false`, asked, but never refused** is a dialog the user dismissed. On Android 11+ that
 *   is not a refusal at all and the dialog appears again, so it reads `NotDetermined`.
 * - **Rationale `false`, never asked** is a permission we have simply never asked for.
 * - **No activity to ask** decides nothing permanent: `NotDetermined` if never asked, `Denied(false)`
 *   otherwise. The answer is read again, with an activity, on the next call.
 *
 * What this cannot see, and neither can any app: a permission the user set to "Don't allow" in system
 * settings before the app ever asked. Android then refuses without a dialog and without a rationale,
 * which reads exactly like a dismissal — `NotDetermined`, with a request that returns at once.
 */
internal class AndroidPermissionController(
    private val context: Context,
    private val host: PermissionRequestHost,
    private val storage: KeyValueStorage,
    private val keyPrefix: String,
    private val logger: Logger,
    private val sdkInt: Int,
    /** The app's `targetSdkVersion`, which decides whether some permissions exist for it at all. */
    private val targetSdk: Int,
    /** A raw permission's protection level, `null` when the device does not know it. */
    private val protectionOf: (String) -> Protection?,
    /** `null` when there is no activity to ask right now. */
    private val shouldShowRationale: (String) -> Boolean?,
    /** Suspends, briefly and at most once per call, until an activity can be asked. */
    private val awaitActivity: suspend () -> Unit,
    /** Calls the listener on every activity resume — at once, if one is resumed — until cancelled. */
    private val addResumedListener: (() -> Unit) -> ActivitySubscription,
    /** Opens a screen; `false` if nothing opened. May throw. */
    private val launchScreen: (SystemScreenRequest) -> Boolean,
    /**
     * Whether the manifest declares `BLUETOOTH_SCAN` with `neverForLocation`. Read at most once: a
     * manifest does not change while the app runs.
     */
    disavowsLocationForScan: () -> Boolean,
    /** The manifest's declared permissions; `null` when unreadable, which filters nothing. */
    private val declaredPermissions: () -> Set<String>?,
    /** Whether a device policy has revoked the Android permission. */
    private val isRevokedByPolicy: (String) -> Boolean,
    /** Whether the app's notifications are switched on — the pre-API-33 notification status. */
    private val notificationsEnabled: () -> Boolean,
    private val specialAccesses: SpecialAccesses,
    private val resultGrants: ResultGrants,
    /**
     * How long a request waits for the dialog of a cancelled earlier request to be answered before its
     * own launch is attempted; see [staleDialog].
     */
    private val staleDialogTimeout: Duration = STALE_DIALOG_TIMEOUT,
) : PermissionController {

    /** A permission whose request just resolved, so every [observe] of it re-reads at once. */
    private val requestsResolved: MutableSharedFlow<Permission> = MutableSharedFlow(extraBufferCapacity = 64)

    /** Held for the whole of every request, so two prompts never overlap. */
    private val requestMutex: Mutex = Mutex()

    /**
     * Completes when the host has finished with the last dialog a *cancelled* caller left on screen
     * (answered, abandoned or never shown); `null` when no such dialog is up. Cancelling a caller frees
     * [requestMutex] but cannot take the system dialog back, and the host refuses a launch while one is
     * pending, so the next request waits on this before it launches its own. Read and written only with
     * [requestMutex] held, which is the synchronisation (D-21: a plain field behind the mutex).
     */
    private var staleDialog: CompletableDeferred<Unit>? = null

    /** Permissions already reported as undeclared, so a status observed on every resume warns once. */
    private val reportedUndeclared: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    /** Flag operations already reported as failed, so a storage that stays down warns once per flag. */
    private val reportedFlagFailures: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    /** `true` the first time [key] is reported. */
    private fun MutableStateFlow<Set<String>>.markReported(key: String): Boolean =
        key !in getAndUpdate { reported -> if (key in reported) reported else reported + key }

    /** Read, and warned about, on first use — so a status observed on every resume warns once. */
    private val scanDisavowsLocation: Boolean by lazy {
        disavowsLocationForScan().also { disavowed ->
            if (!disavowed) {
                logger.w {
                    "${CommonPermission.BluetoothScan} reads Unavailable: declare " +
                        "android.permission.BLUETOOTH_SCAN in the manifest with " +
                        "android:usesPermissionFlags=\"neverForLocation\""
                }
            }
        }
    }

    override suspend fun check(permission: Permission): PermissionStatus {
        val source: Permission = permission.statusSource()
        return when {
            source is AndroidPermission.SpecialAccess -> specialAccesses.status(source)
            source is AndroidPermission.Role -> roleStatus(source)
            source is AndroidPermission.DeviceAdmin -> deviceAdminStatus(source)
            ruleOf(source) != null -> runtimeStatus(source)
            else -> PermissionStatus.Unavailable
        }
    }

    override suspend fun request(permission: Permission): PermissionStatus =
        requestMutex.withLock { requestOne(permission, resumeWait = MutableStateFlow(false)) }

    override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
        requestMutex.withLock {
            val distinct: List<Permission> = permissions.distinct()
            // One wait for an activity per call, however many permissions settle (see ResumeWait).
            val resumeWait: MutableStateFlow<Boolean> = MutableStateFlow(false)
            // Keyed by status source: two entries that stand for one Android permission on this API
            // level are one request with one answer, never two dialogs.
            val results: MutableMap<Permission, PermissionStatus> = requestSharedDialog(distinct, resumeWait).toMutableMap()
            for (permission in distinct) {
                val source: Permission = permission.statusSource()
                if (source !in results) results[source] = requestOne(permission, resumeWait)
            }
            distinct.associateWith { permission -> results.getValue(permission.statusSource()) }
        }

    /**
     * Re-reads [permission] when collection starts, on every activity resume, and after every request
     * that covered it — or the entry it stands for on this API level. A resume is where a change made in
     * system settings — or by the system itself, auto-resetting an unused app's permissions — becomes
     * visible to the app.
     */
    override fun observe(permission: Permission): Flow<PermissionStatus> {
        val source: Permission = permission.statusSource()
        return callbackFlow {
            // UNDISPATCHED, and before the first tick: the subscription exists before any status read
            // can start on any dispatcher, so a request resolving in between is not emitted to
            // nobody (the flow has no replay).
            launch(start = CoroutineStart.UNDISPATCHED) {
                requestsResolved.collect { resolved -> if (resolved == source) send(Unit) }
            }
            val subscription: ActivitySubscription = addResumedListener { trySend(Unit) }
            trySend(Unit)
            awaitClose { subscription.cancel() }
        }
            .conflate()
            .map { check(source) }
            .distinctUntilChanged()
    }

    override fun openSettings(permission: Permission): Boolean {
        val source: Permission = permission.statusSource()
        val candidates: List<Intent> = when {
            source == CommonPermission.Notifications -> buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    add(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
                add(appDetails())
            }

            source is AndroidPermission.SpecialAccess -> specialAccesses.candidates(source)
            source is AndroidPermission.Role -> listOf(resultGrants.defaultAppsSettings(), appDetails())
            source is AndroidPermission.DeviceAdmin -> listOf(resultGrants.securitySettings())
            ruleOf(source) != null -> listOf(appDetails())
            else -> emptyList()
        }
        if (candidates.isEmpty()) {
            logger.d { "No settings screen to open for $permission" }
            return false
        }
        return openScreen(SystemScreenRequest(candidates, context, PermissionScreen(permission)), permission)
    }

    // --- requests -------------------------------------------------------------------------------

    /** One permission, with [requestMutex] held. */
    private suspend fun requestOne(permission: Permission, resumeWait: MutableStateFlow<Boolean>): PermissionStatus {
        val source: Permission = permission.statusSource()
        val status: PermissionStatus = when {
            source is AndroidPermission.SpecialAccess -> requestSpecialAccess(source)
            source is AndroidPermission.Role -> requestRole(source)
            source is AndroidPermission.DeviceAdmin -> requestDeviceAdmin(source)
            ruleOf(source) != null -> requestRuntime(source, requested = permission, resumeWait = resumeWait)
            else -> PermissionStatus.Unavailable
        }
        requestsResolved.tryEmit(source)
        return status
    }

    /**
     * Shows one system dialog for every runtime permission in [permissions] that can still prompt, and
     * returns the status each of those sources reached, keyed by status source; the others are left to
     * [requestOne].
     *
     * Background location is never in the group: Android grants it only on top of foreground location,
     * and ignores a request that asks for both at once.
     */
    private suspend fun requestSharedDialog(
        permissions: List<Permission>,
        resumeWait: MutableStateFlow<Boolean>,
    ): Map<Permission, PermissionStatus> {
        val dialogs: MutableMap<Permission, List<String>> = linkedMapOf()
        for (permission in permissions) {
            val source: Permission = permission.statusSource()
            if (foregroundOf(source) != null || source in dialogs) continue
            val dialog: RuntimeRule.Dialog = ruleOf(source) as? RuntimeRule.Dialog ?: continue
            if (!runtimeStatus(source).mayShowDialog() || source.isScanWithoutDisavowal()) continue
            dialogs[source] = declared(source, dialog.androidPermissions)
        }
        if (dialogs.size < 2) return emptyMap()

        val answers: Map<String, Boolean> = launchDialog(dialogs.values.flatten().distinct())
            ?: return dialogs.mapValues { (source, _) -> runtimeStatus(source) }
        return dialogs.mapValues { (source, strings) ->
            settle(source, strings, answers.filterKeys { it in strings }, resumeWait)
                .also { requestsResolved.tryEmit(source) }
        }
    }

    /** [requested] is only for the log: the entry the caller asked for, when [permission] stands for it. */
    private suspend fun requestRuntime(
        permission: Permission,
        requested: Permission,
        resumeWait: MutableStateFlow<Boolean>,
    ): PermissionStatus {
        val current: PermissionStatus = runtimeStatus(permission)
        if (!current.mayShowDialog()) {
            logger.d {
                val entry: String = if (requested == permission) "$permission" else "$requested (as $permission)"
                "Not showing a dialog for $entry: already $current"
            }
            return current
        }
        val foreground: Permission? = foregroundOf(permission)
        if (foreground != null && !runtimeStatus(foreground).isUsable) {
            // Android grants a background permission only on top of its foreground one: a request
            // without it is refused without any UI (background location on API 29 would even widen
            // silently into a foreground request). Either way there is no dialog to wait on.
            logger.w { "Not requesting $permission: request $foreground first" }
            return current
        }
        val dialog: RuntimeRule.Dialog = ruleOf(permission) as? RuntimeRule.Dialog ?: return current
        val strings: List<String> = declared(permission, dialog.androidPermissions)
        val answers: Map<String, Boolean> = launchDialog(strings) ?: return current
        return settle(permission, strings, answers, resumeWait)
    }

    /**
     * Records and reports the outcome of a dialog that showed [strings] for [permission].
     *
     * [resumeWait] is the call's "already waited for an activity" mark: a batch of refused permissions
     * in a backgrounded app waits once, not once per permission.
     */
    private suspend fun settle(
        permission: Permission,
        strings: List<String>,
        answers: Map<String, Boolean>,
        resumeWait: MutableStateFlow<Boolean>,
    ): PermissionStatus {
        // The platform answers an interrupted request with no entries: nothing was decided, so nothing
        // is recorded, and the status is whatever it was.
        if (answers.isEmpty()) return runtimeStatus(permission)
        val dialog: RuntimeRule.Dialog = ruleOf(permission) as RuntimeRule.Dialog
        return when (dialog.grantOf(answers)) {
            Grant.Full -> {
                clearFlags(permission)
                PermissionStatus.Granted
            }

            Grant.Limited -> {
                clearFlags(permission)
                PermissionStatus.Limited
            }

            Grant.None -> {
                // Recorded only now, after the dialog actually resolved. Recording it before launching
                // would turn a dialog that never appeared into a permanent denial the user never made.
                markAsked(permission)
                mirrorAsked(permission, strings, answers)
                // The answer arrives just before the activity is resumed again, and a continuation that
                // runs in that gap finds no activity to ask for the rationale. Waiting for the resume
                // turns "cannot tell" back into an answer in all but a backgrounded app.
                if (rationaleFor(strings) == null && resumeWait.compareAndSet(expect = false, update = true)) awaitActivity()
                runtimeStatus(permission)
            }
        }
    }

    /**
     * Opens [access]'s screen and suspends until the user is back in the app, then re-reads it.
     *
     * Already granted, or not available: nothing is opened. A screen that could not be opened returns
     * the status unchanged, at once.
     */
    private suspend fun requestSpecialAccess(access: AndroidPermission.SpecialAccess): PermissionStatus {
        val current: PermissionStatus = specialAccesses.status(access)
        if (current !is PermissionStatus.Denied) return current
        val candidates: List<Intent> = specialAccesses.candidates(access)
        if (candidates.isEmpty()) return current
        val returned: Boolean = openAndAwaitReturn(SystemScreenRequest(candidates, context, PermissionScreen(access)), access)
        return if (returned) specialAccesses.status(access) else current
    }

    /**
     * Shows the role dialog and waits for its result, then re-reads the role.
     *
     * A result that leaves the role not held is a refusal — the user declined, or backed out — and is
     * remembered: Android itself stops showing the dialog after the second. Nothing is recorded when the
     * dialog could not be shown.
     */
    private suspend fun requestRole(role: AndroidPermission.Role): PermissionStatus {
        val current: PermissionStatus = roleStatus(role)
        if (!current.mayShowDialog()) return current
        val intent: Intent = resultGrants.requestIntent(role) ?: return current
        val resultCode: Int = launchForResult(intent, role) ?: return current
        if (resultCode == INTERRUPTED) {
            logger.d { "The dialog for $role was interrupted; nothing recorded" }
            return roleStatus(role)
        }
        if (resultGrants.isHeld(role) != true) {
            logger.d { "$role was not granted (result $resultCode)" }
            if (readFlag(askedKey(keyPrefix, role)) == null) {
                markAsked(role)
            } else {
                markFlag(rationaleSeenKey(keyPrefix, role))
            }
        }
        return roleStatus(role)
    }

    /**
     * A role is held, or refused once (the asked flag), or refused twice (the rationale flag, reused:
     * for a role it means "Android will not show the dialog again").
     */
    private suspend fun roleStatus(role: AndroidPermission.Role): PermissionStatus = when (resultGrants.isHeld(role)) {
        null -> PermissionStatus.Unavailable
        true -> {
            clearFlags(role)
            PermissionStatus.Granted
        }

        false -> when {
            isSet(rationaleSeenKey(keyPrefix, role)) -> PermissionStatus.PermanentlyDenied
            readFlag(askedKey(keyPrefix, role)) != null -> PermissionStatus.Denied(explainFirst = true)
            else -> PermissionStatus.NotDetermined
        }
    }

    /** Shows the activation screen and waits for its result, then re-reads the admin. */
    private suspend fun requestDeviceAdmin(admin: AndroidPermission.DeviceAdmin): PermissionStatus {
        val current: PermissionStatus = deviceAdminStatus(admin)
        if (current !is PermissionStatus.Denied) return current
        launchForResult(resultGrants.requestIntent(admin), admin) ?: return current
        return deviceAdminStatus(admin)
    }

    /** Android shows the activation screen every time, so a refusal is never permanent. */
    private fun deviceAdminStatus(admin: AndroidPermission.DeviceAdmin): PermissionStatus =
        when (resultGrants.isActive(admin)) {
            null -> PermissionStatus.Unavailable
            true -> PermissionStatus.Granted
            false -> PermissionStatus.Denied(explainFirst = true)
        }

    /** Starts [intent] through the host for a result; the result code, or `null` when nothing was shown. */
    private suspend fun launchForResult(intent: Intent, permission: Permission): Int? =
        launchOnHost("The system screen for $permission") { deliver ->
            // The library's own host can tell an interrupted request from the user backing out; any other
            // host only has a result code, so what it reports as RESULT_CANCELED counts as a refusal.
            val own: ActivityResultPermissionRequestHost? = host as? ActivityResultPermissionRequestHost
            if (own != null) {
                own.launchForResultOrInterrupted(intent) { resultCode -> deliver(resultCode ?: INTERRUPTED) }
            } else {
                host.launchForResult(intent) { resultCode -> deliver(resultCode) }
            }
        }

    /**
     * Opens [request] and suspends until an activity of the app is resumed *after* it opened — the user
     * coming back. `false`, at once, when nothing opened.
     *
     * The resume listener fires synchronously for an activity that is already resumed when it is added;
     * that call is the screen not having appeared yet, and is ignored. Cancelling the caller removes the
     * listener; the screen stays where it is, and the next [check] or [observe] sees what the user did.
     */
    private suspend fun openAndAwaitReturn(request: SystemScreenRequest, permission: Permission): Boolean {
        val returned: CompletableDeferred<Boolean> = CompletableDeferred()
        // Handed from the main-thread subscribe to whichever thread finishes the wait; taken once.
        val subscription: MutableStateFlow<ActivitySubscription?> = MutableStateFlow(null)
        try {
            // Subscribe and open on the main thread. Off main a listener added while an activity is
            // resumed gets its immediate call posted, not run: it could land after `armed` is set
            // but before the old activity's pause, and end the wait while Settings is on screen.
            // On main that call is synchronous and has been ignored before the screen is opened.
            onMainThread {
                if (!returned.isActive) return@onMainThread
                var armed = false // main thread only: the listener (ActivityAccess contract) and the set below
                subscription.value = addResumedListener { if (armed) returned.complete(true) }
                // Finished while subscribing: the finally block may already have taken the (still empty) cell.
                if (!returned.isActive) {
                    subscription.getAndUpdate { null }?.cancel()
                    return@onMainThread
                }
                if (openScreen(request, permission)) armed = true else returned.complete(false)
            }
            return returned.await()
        } finally {
            returned.cancel()
            subscription.getAndUpdate { null }?.cancel()
        }
    }

    private fun openScreen(request: SystemScreenRequest, permission: Permission): Boolean {
        val opened: Boolean = try {
            launchScreen(request)
        } catch (cause: Exception) {
            logger.w(cause) { "The system screen launcher threw while opening settings for $permission" }
            return false
        }
        if (!opened) logger.w { "Could not open settings for $permission" }
        return opened
    }

    private fun appDetails(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /**
     * Shows the dialog and waits.
     *
     * @return each Android permission's answer, or `null` when the host could not show anything — a
     *   distinction the caller needs, because "no dialog appeared" must not be recorded as a refusal.
     */
    private suspend fun launchDialog(androidPermissions: List<String>): Map<String, Boolean>? =
        launchOnHost("The permission dialog for $androidPermissions") { deliver ->
            host.launch(androidPermissions) { answers -> deliver(answers) }
        }

    /**
     * Runs [launch] on the main thread — an `ActivityResultLauncher` must be started there, and the
     * caller's coroutine may be anywhere — and suspends for the value it delivers.
     *
     * The result is a single-assignment [CompletableDeferred]: the first value to arrive wins and any
     * later one is dropped.
     *
     * @return the delivered value, or `null` when the host could not show anything. A host that both
     *   reports failure and delivers breaks its contract; whichever of the two arrives first is the
     *   result, the other is ignored.
     */
    private suspend fun <T : Any> launchOnHost(what: String, launch: (deliver: (T) -> Unit) -> Boolean): T? {
        if (!awaitStaleDialog(what)) return null
        val result: CompletableDeferred<T?> = CompletableDeferred()
        // Completes when the host is done with this launch whatever the caller does: answered, failed
        // to launch, or never launched because the caller was cancelled first.
        val settled: CompletableDeferred<Unit> = CompletableDeferred()
        try {
            onMainThread {
                // Cancelled before this ran (off main it is posted): do not put up a dialog nobody waits for.
                if (!result.isActive) {
                    settled.complete(Unit)
                    return@onMainThread
                }
                val launched: Boolean = runCatching {
                    launch { value ->
                        result.complete(value)
                        settled.complete(Unit)
                    }
                }.getOrElse { cause ->
                    logger.w(cause) { "The permission request host threw while launching" }
                    false
                }
                if (!launched) {
                    settled.complete(Unit)
                    if (result.complete(null)) logger.w { "$what could not be shown" }
                }
            }
            return result.await()
        } finally {
            result.cancel() // a late delivery, or a still-queued launch, now sees a finished request
            // A caller cancelled with its dialog still up: the next request has to wait for that dialog.
            if (!settled.isCompleted) staleDialog = settled
        }
    }

    /**
     * Waits, for at most [staleDialogTimeout], until the dialog an earlier cancelled caller left on
     * screen is resolved, so a request queued behind it gets its own dialog instead of a refused launch.
     * Cancelling the waiting caller ends the wait at once.
     *
     * @return `false` when the dialog was still up at the timeout: the request then fails like a refused
     *   launch (nothing shown, status unchanged). The wait is not repeated for later requests.
     */
    private suspend fun awaitStaleDialog(what: String): Boolean {
        val stale: CompletableDeferred<Unit> = staleDialog ?: return true
        if (stale.isCompleted) {
            staleDialog = null
            return true
        }
        val resolved: Unit? = withTimeoutOrNull(staleDialogTimeout) { stale.await() }
        staleDialog = null
        if (resolved == null) {
            logger.w { "$what could not be shown: an earlier cancelled request's dialog is still on screen" }
            return false
        }
        return true
    }

    private fun onMainThread(block: () -> Unit) {
        val main: Looper = Looper.getMainLooper()
        if (Looper.myLooper() == main) block() else Handler(main).post(block)
    }

    // --- status ---------------------------------------------------------------------------------

    /**
     * The entry whose status, dialog and remembered refusals [this] is on this API level: itself, except
     * where the rule says it is another entry (Bluetooth scanning below API 31, background location
     * below API 29).
     */
    private fun Permission.statusSource(): Permission =
        when (val rule: RuntimeRule? = ruleOf(this)) {
            is RuntimeRule.SameAs -> rule.permission.statusSource()
            else -> this
        }

    /**
     * `BLUETOOTH_SCAN` declared without `neverForLocation`: Android would withhold scan results without
     * fine location, which this entry never asks for, so a grant could not make scanning work.
     */
    private fun Permission.isScanWithoutDisavowal(): Boolean =
        this == CommonPermission.BluetoothScan && !scanDisavowsLocation

    private fun ruleOf(permission: Permission): RuntimeRule? = runtimeRule(permission, sdkInt, targetSdk, protectionOf)

    /** Whether a request from this status may put a dialog on screen. */
    private fun PermissionStatus.mayShowDialog(): Boolean =
        this is PermissionStatus.NotDetermined || this is PermissionStatus.Denied || this is PermissionStatus.Limited

    /** The strings of [androidPermissions] the manifest declares, without reporting; all of them when it cannot be read. */
    private fun declaredQuietly(androidPermissions: List<String>): List<String> {
        val declared: Set<String> = declaredPermissions() ?: return androidPermissions
        return androidPermissions.filter { it in declared }
    }

    /** The strings of [androidPermissions] the manifest declares; all of them when it cannot be read. */
    private fun declared(permission: Permission, androidPermissions: List<String>): List<String> {
        val present: List<String> = declaredQuietly(androidPermissions)
        if (present.isEmpty() && declaredPermissions() != null && reportedUndeclared.markReported(permission.id)) {
            logger.w { "$permission reads Unavailable: declare ${androidPermissions.joinToString(" or ")} in the manifest" }
        }
        return present
    }

    private suspend fun runtimeStatus(permission: Permission): PermissionStatus =
        when (val rule: RuntimeRule? = ruleOf(permission)) {
            null -> PermissionStatus.Unavailable
            RuntimeRule.NoGrantNeeded -> PermissionStatus.Granted
            is RuntimeRule.SameAs -> runtimeStatus(rule.permission)
            // Not a permission, and nothing but settings changes it.
            RuntimeRule.NotificationSwitch ->
                if (notificationsEnabled()) PermissionStatus.Granted else PermissionStatus.PermanentlyDenied

            // Nothing to ask, nothing to remember: the app holds it or cannot get it from any prompt.
            is RuntimeRule.StatusOnly ->
                if (isGranted(rule.androidPermission)) PermissionStatus.Granted else PermissionStatus.Unavailable

            is RuntimeRule.Dialog -> dialogStatus(permission, rule)
        }

    private suspend fun dialogStatus(permission: Permission, rule: RuntimeRule.Dialog): PermissionStatus {
        if (permission.isScanWithoutDisavowal()) return PermissionStatus.Unavailable
        val androidPermissions: List<String> = declared(permission, rule.androidPermissions)
        if (androidPermissions.isEmpty()) return PermissionStatus.Unavailable

        when (rule.grantOf(androidPermissions.associateWith(::isGranted))) {
            Grant.Full -> {
                clearFlags(permission)
                return PermissionStatus.Granted
            }

            Grant.Limited -> {
                clearFlags(permission)
                return PermissionStatus.Limited
            }

            Grant.None -> Unit
        }
        if (androidPermissions.any(isRevokedByPolicy)) return PermissionStatus.Restricted

        val askedValue: String? = readFlag(askedKey(keyPrefix, permission))
        val asked: Boolean = askedValue != null
        return when (rationaleFor(androidPermissions)) {
            true -> {
                markFlag(rationaleSeenKey(keyPrefix, permission))
                mirrorRationaleSeen(permission, androidPermissions)
                PermissionStatus.Denied(explainFirst = true)
            }

            false -> when {
                !asked -> PermissionStatus.NotDetermined
                isSet(rationaleSeenKey(keyPrefix, permission)) -> PermissionStatus.PermanentlyDenied
                // Written by a version that never recorded refusals: read it as that version did.
                askedValue == LEGACY_ASKED -> PermissionStatus.PermanentlyDenied
                // Asked, never refused: the dialog was dismissed, and the system will show it again.
                else -> PermissionStatus.NotDetermined
            }

            null -> if (asked) PermissionStatus.Denied(explainFirst = false) else PermissionStatus.NotDetermined
        }
    }

    /**
     * The other catalog entries that ask Android for nothing but strings [strings] already holds (and for
     * at least one the manifest declares), with the declared strings each asks for. Several entries can
     * stand for one Android permission without being the same entry (`PhotoLibrary` and `AudioLibrary`
     * below API 33, `Calendar` and `CalendarWriteOnly`); the platform's "will not show again" is per
     * string, so what is learned through one is learned for these too. Entries that stand for another
     * (`SameAs`) already share flags and are left out; so is anything the catalog does not list (a
     * `Runtime(name)` keeps its own flags). Reads nothing and reports nothing.
     */
    private fun coveredBy(permission: Permission, strings: List<String>): List<Pair<Permission, List<String>>> =
        MIRRORABLE_ENTRIES.mapNotNull { entry: Permission ->
            if (entry == permission || entry.statusSource() != entry || entry.isScanWithoutDisavowal()) return@mapNotNull null
            val rule: RuntimeRule.Dialog = ruleOf(entry) as? RuntimeRule.Dialog ?: return@mapNotNull null
            val entryStrings: List<String> = declaredQuietly(rule.androidPermissions)
            if (entryStrings.isNotEmpty() && strings.containsAll(entryStrings)) entry to entryStrings else null
        }

    /** After a refusal of [permission] through [strings]: the same refusal, as the other entries read it. */
    private suspend fun mirrorAsked(permission: Permission, strings: List<String>, answers: Map<String, Boolean>) {
        for ((entry: Permission, entryStrings: List<String>) in coveredBy(permission, strings)) {
            val rule: RuntimeRule.Dialog = ruleOf(entry) as RuntimeRule.Dialog
            if (rule.grantOf(answers.filterKeys { it in entryStrings }) == Grant.None) markAsked(entry)
        }
    }

    /** After the platform asked for a rationale for [permission]'s [strings]: the other entries that ask for the same also saw it. */
    private suspend fun mirrorRationaleSeen(permission: Permission, strings: List<String>) {
        for ((entry: Permission, entryStrings: List<String>) in coveredBy(permission, strings)) {
            if (rationaleFor(entryStrings) == true) markFlag(rationaleSeenKey(keyPrefix, entry))
        }
    }

    private fun isGranted(androidPermission: String): Boolean =
        context.checkSelfPermission(androidPermission) == PackageManager.PERMISSION_GRANTED

    /**
     * The rationale answer for a group: `true` if the platform wants any of it explained, `null` when
     * there is no activity to ask.
     */
    private fun rationaleFor(androidPermissions: List<String>): Boolean? {
        var answer: Boolean = false
        for (androidPermission in androidPermissions) {
            when (shouldShowRationale(androidPermission)) {
                null -> return null
                true -> answer = true
                false -> Unit
            }
        }
        return answer
    }

    // --- flags ----------------------------------------------------------------------------------

    private suspend fun isSet(key: String): Boolean = readFlag(key) == FLAG_SET

    /**
     * The flag under [key], `null` when it is absent or the storage could not read it. An unreadable
     * flag reads as absent (the safe side: nothing is turned into a permanent denial), and is reported
     * to the logger once per key, so a storage that is down says so without a line on every check.
     */
    private suspend fun readFlag(key: String): String? =
        when (val result: StorageResult<String?> = storage.get(key)) {
            is StorageResult.Success -> result.value
            is StorageResult.Failure -> {
                reportFlagFailure("read", key, result)
                null
            }
        }

    private suspend fun writeFlag(key: String, value: String) {
        val result: StorageResult<Unit> = storage.put(key, value)
        if (result is StorageResult.Failure) reportFlagFailure("write", key, result)
    }

    private suspend fun removeFlag(key: String) {
        val result: StorageResult<Unit> = storage.remove(key)
        if (result is StorageResult.Failure) reportFlagFailure("remove", key, result)
    }

    /** Warns the first time [operation] on [key] fails; a storage that stays down does not fill the log. */
    private fun reportFlagFailure(operation: String, key: String, failure: StorageResult.Failure) {
        if (reportedFlagFailures.markReported("$operation $key")) {
            logger.w { "Could not $operation the permission flag $key (${failure.error}); it is treated as not set" }
        }
    }

    /** Records that the dialog was shown, leaving a flag an earlier version wrote as it is. */
    private suspend fun markAsked(permission: Permission) {
        val key: String = askedKey(keyPrefix, permission)
        if (readFlag(key) == null) writeFlag(key, ASKED)
    }

    /**
     * Sets a flag, but only when it is not set already — which keeps a repeated [check] of a
     * permission that keeps asking for a rationale from writing on every call.
     */
    private suspend fun markFlag(key: String) {
        if (!isSet(key)) writeFlag(key, FLAG_SET)
    }

    /**
     * Removes both flags, but only those there are to remove.
     *
     * The guard is what keeps [check] a query. It runs on every check of a granted permission — the
     * overwhelmingly common call — and the overwhelmingly common state there is "granted, nothing
     * stored", where an unconditional `remove` would turn each check into a persistent write.
     */
    private suspend fun clearFlags(permission: Permission) {
        listOf(askedKey(keyPrefix, permission), rationaleSeenKey(keyPrefix, permission)).forEach { key: String ->
            if (readFlag(key) != null) removeFlag(key)
        }
    }
}
