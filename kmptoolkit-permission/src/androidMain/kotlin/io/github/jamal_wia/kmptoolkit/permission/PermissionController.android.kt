package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) : PermissionController {

    /** A permission whose request just resolved, so every [observe] of it re-reads at once. */
    private val requestsResolved: MutableSharedFlow<Permission> = MutableSharedFlow(extraBufferCapacity = 64)

    /** Held for the whole of every request, so two prompts never overlap. */
    private val requestMutex: Mutex = Mutex()

    /** Permissions already reported as undeclared, so a status observed on every resume warns once. */
    private val reportedUndeclared: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
            runtimeRule(source, sdkInt) != null -> runtimeStatus(source)
            else -> PermissionStatus.Unavailable
        }
    }

    override suspend fun request(permission: Permission): PermissionStatus =
        requestMutex.withLock { requestOne(permission) }

    override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
        requestMutex.withLock {
            val distinct: List<Permission> = permissions.distinct()
            val results: MutableMap<Permission, PermissionStatus> = requestSharedDialog(distinct).toMutableMap()
            for (permission in distinct) {
                if (permission !in results) results[permission] = requestOne(permission)
            }
            distinct.associateWith { permission -> results.getValue(permission) }
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
            trySend(Unit)
            val subscription: ActivitySubscription = addResumedListener { trySend(Unit) }
            launch { requestsResolved.collect { resolved -> if (resolved == source) send(Unit) } }
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
            runtimeRule(source, sdkInt) != null -> listOf(appDetails())
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
    private suspend fun requestOne(permission: Permission): PermissionStatus {
        val source: Permission = permission.statusSource()
        val status: PermissionStatus = when {
            source is AndroidPermission.SpecialAccess -> requestSpecialAccess(source)
            runtimeRule(source, sdkInt) != null -> requestRuntime(source, requested = permission)
            else -> PermissionStatus.Unavailable
        }
        requestsResolved.tryEmit(source)
        return status
    }

    /**
     * Shows one system dialog for every runtime permission in [permissions] that can still prompt, and
     * returns the status each of them reached; the others are left to [requestOne].
     *
     * Background location is never in the group: Android grants it only on top of foreground location,
     * and ignores a request that asks for both at once.
     */
    private suspend fun requestSharedDialog(permissions: List<Permission>): Map<Permission, PermissionStatus> {
        val dialogs: MutableMap<Permission, List<String>> = linkedMapOf()
        for (permission in permissions) {
            val source: Permission = permission.statusSource()
            if (source == CommonPermission.LocationBackground || source in dialogs) continue
            val dialog: RuntimeRule.Dialog = runtimeRule(source, sdkInt) as? RuntimeRule.Dialog ?: continue
            if (!runtimeStatus(source).mayShowDialog() || source.isScanWithoutDisavowal()) continue
            dialogs[source] = declared(source, dialog.androidPermissions)
        }
        if (dialogs.size < 2) return emptyMap()

        val answers: Map<String, Boolean> = launchDialog(dialogs.values.flatten().distinct())
            ?: return permissions.filter { it.statusSource() in dialogs }.associateWith { runtimeStatus(it.statusSource()) }
        val bySource: Map<Permission, PermissionStatus> = dialogs.mapValues { (source, strings) ->
            settle(source, strings, answers.filterKeys { it in strings })
                .also { requestsResolved.tryEmit(source) }
        }
        return permissions.filter { it.statusSource() in bySource }.associateWith { bySource.getValue(it.statusSource()) }
    }

    /** [requested] is only for the log: the entry the caller asked for, when [permission] stands for it. */
    private suspend fun requestRuntime(permission: Permission, requested: Permission): PermissionStatus {
        val current: PermissionStatus = runtimeStatus(permission)
        if (!current.mayShowDialog()) {
            logger.d {
                val entry: String = if (requested == permission) "$permission" else "$requested (as $permission)"
                "Not showing a dialog for $entry: already $current"
            }
            return current
        }
        if (permission == CommonPermission.LocationBackground && !runtimeStatus(CommonPermission.Location).isUsable) {
            // Android grants background location only on top of foreground location: from API 30 a
            // request without it is refused without any UI, and on API 29 it would silently widen
            // into a foreground request. Either way there is no dialog for this call to wait on.
            logger.w { "Not requesting $permission: request ${CommonPermission.Location} first" }
            return current
        }
        val dialog: RuntimeRule.Dialog = runtimeRule(permission, sdkInt) as? RuntimeRule.Dialog ?: return current
        val strings: List<String> = declared(permission, dialog.androidPermissions)
        val answers: Map<String, Boolean> = launchDialog(strings) ?: return current
        return settle(permission, strings, answers)
    }

    /** Records and reports the outcome of a dialog that showed [strings] for [permission]. */
    private suspend fun settle(permission: Permission, strings: List<String>, answers: Map<String, Boolean>): PermissionStatus {
        // The platform answers an interrupted request with no entries: nothing was decided, so nothing
        // is recorded, and the status is whatever it was.
        if (answers.isEmpty()) return runtimeStatus(permission)
        val dialog: RuntimeRule.Dialog = runtimeRule(permission, sdkInt) as RuntimeRule.Dialog
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
                // The answer arrives just before the activity is resumed again, and a continuation that
                // runs in that gap finds no activity to ask for the rationale. Waiting for the resume
                // turns "cannot tell" back into an answer in all but a backgrounded app.
                if (rationaleFor(strings) == null) awaitActivity()
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
     * Opens [request] and suspends until an activity of the app is resumed *after* it opened — the user
     * coming back. `false`, at once, when nothing opened.
     *
     * The resume listener fires synchronously for an activity that is already resumed when it is added;
     * that call is the screen not having appeared yet, and is ignored. Cancelling the caller removes the
     * listener; the screen stays where it is, and the next [check] or [observe] sees what the user did.
     */
    private suspend fun openAndAwaitReturn(request: SystemScreenRequest, permission: Permission): Boolean {
        var subscription: ActivitySubscription? = null
        try {
            return suspendCancellableCoroutine { continuation ->
                val armed = AtomicBoolean(false)
                subscription = addResumedListener {
                    if (armed.get() && continuation.isActive) continuation.resume(true)
                }
                if (openScreen(request, permission)) {
                    armed.set(true)
                } else if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        } finally {
            subscription?.cancel()
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
     * @return the delivered value, or `null` when the host could not show anything. A host that both
     *   reports failure and delivers breaks its contract; the continuation is resumed exactly once
     *   regardless.
     */
    private suspend fun <T : Any> launchOnHost(what: String, launch: (deliver: (T) -> Unit) -> Boolean): T? =
        suspendCancellableCoroutine { continuation ->
            val delivered = AtomicBoolean(false)
            val deliver: (T?) -> Unit = { value ->
                if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resume(value)
            }
            onMainThread {
                val launched: Boolean = runCatching { launch { value -> deliver(value) } }.getOrElse { cause ->
                    logger.w(cause) { "The permission request host threw while launching" }
                    false
                }
                if (!launched && !delivered.get()) {
                    logger.w { "$what could not be shown" }
                    deliver(null)
                }
            }
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
        when (val rule: RuntimeRule? = runtimeRule(this, sdkInt)) {
            is RuntimeRule.SameAs -> rule.permission.statusSource()
            else -> this
        }

    /**
     * `BLUETOOTH_SCAN` declared without `neverForLocation`: Android would withhold scan results without
     * fine location, which this entry never asks for, so a grant could not make scanning work.
     */
    private fun Permission.isScanWithoutDisavowal(): Boolean =
        this == CommonPermission.BluetoothScan && !scanDisavowsLocation

    /** Whether a request from this status may put a dialog on screen. */
    private fun PermissionStatus.mayShowDialog(): Boolean =
        this is PermissionStatus.NotDetermined || this is PermissionStatus.Denied || this is PermissionStatus.Limited

    /** The strings of [androidPermissions] the manifest declares; all of them when it cannot be read. */
    private fun declared(permission: Permission, androidPermissions: List<String>): List<String> {
        val declared: Set<String> = declaredPermissions() ?: return androidPermissions
        val present: List<String> = androidPermissions.filter { it in declared }
        if (present.isEmpty() && reportedUndeclared.add(permission.id)) {
            logger.w { "$permission reads Unavailable: declare ${androidPermissions.joinToString(" or ")} in the manifest" }
        }
        return present
    }

    private suspend fun runtimeStatus(permission: Permission): PermissionStatus =
        when (val rule: RuntimeRule? = runtimeRule(permission, sdkInt)) {
            null -> PermissionStatus.Unavailable
            RuntimeRule.NoGrantNeeded -> PermissionStatus.Granted
            is RuntimeRule.SameAs -> runtimeStatus(rule.permission)
            // Not a permission, and nothing but settings changes it.
            RuntimeRule.NotificationSwitch ->
                if (notificationsEnabled()) PermissionStatus.Granted else PermissionStatus.PermanentlyDenied

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

        val askedValue: String? = storage.getStringOrNull(askedKey(keyPrefix, permission))
        val asked: Boolean = askedValue != null
        return when (rationaleFor(androidPermissions)) {
            true -> {
                markFlag(rationaleSeenKey(keyPrefix, permission))
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

    private suspend fun isSet(key: String): Boolean = storage.getStringOrNull(key) == FLAG_SET

    /** Records that the dialog was shown, leaving a flag an earlier version wrote as it is. */
    private suspend fun markAsked(permission: Permission) {
        val key: String = askedKey(keyPrefix, permission)
        if (storage.getStringOrNull(key) == null) storage.put(key, ASKED)
    }

    /**
     * Sets a flag, but only when it is not set already — which keeps a repeated [check] of a
     * permission that keeps asking for a rationale from writing on every call.
     */
    private suspend fun markFlag(key: String) {
        if (!isSet(key)) storage.put(key, FLAG_SET)
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
            if (storage.getStringOrNull(key) != null) storage.remove(key)
        }
    }
}
