package io.github.jamal_wia.kmptoolkit.permission

import kotlin.coroutines.resume
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorization
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.MediaPlayer.MPMediaLibrary
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatus
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusAuthorized
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusDenied
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusNotDetermined
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusRestricted
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.darwin.DISPATCH_TIME_NOW

// --- Status mappings ----------------------------------------------------------------------------
//
// Pure functions of the platform's own status value, so every branch is testable on a simulator
// without an authorization prompt.

/**
 * [Permission.LOCATION]: "when in use" or "always" is granted. Restricted — a parental control or an
 * MDM profile — is folded into permanently denied, as for the camera: settings is the only place it
 * can change.
 */
internal fun foregroundLocationStatus(status: CLAuthorizationStatus): PermissionStatus = when (status) {
    kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse -> PermissionStatus.Granted
    kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> PermissionStatus.PermanentlyDenied
    else -> PermissionStatus.NotDetermined
}

/**
 * [Permission.LOCATION_BACKGROUND]: only "always" is granted. "When in use" is a refusal the app may
 * still ask about once more — iOS can offer the upgrade to "always" — which is exactly what
 * `Denied(shouldShowRationale = true)` says; once that upgrade has been asked for ([upgradeAsked]) and
 * the status stayed "when in use", iOS will not offer it again and only settings can change it.
 */
internal fun backgroundLocationStatus(
    status: CLAuthorizationStatus,
    upgradeAsked: Boolean = false,
): PermissionStatus = when (status) {
    kCLAuthorizationStatusAuthorizedAlways -> PermissionStatus.Granted
    kCLAuthorizationStatusAuthorizedWhenInUse ->
        if (upgradeAsked) PermissionStatus.PermanentlyDenied else PermissionStatus.Denied(shouldShowRationale = true)
    kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> PermissionStatus.PermanentlyDenied
    else -> PermissionStatus.NotDetermined
}

internal fun mediaLibraryStatus(status: MPMediaLibraryAuthorizationStatus): PermissionStatus = when (status) {
    MPMediaLibraryAuthorizationStatusAuthorized -> PermissionStatus.Granted
    MPMediaLibraryAuthorizationStatusDenied, MPMediaLibraryAuthorizationStatusRestricted ->
        PermissionStatus.PermanentlyDenied

    MPMediaLibraryAuthorizationStatusNotDetermined -> PermissionStatus.NotDetermined
    else -> PermissionStatus.NotDetermined
}

internal fun bluetoothStatus(authorization: CBManagerAuthorization): PermissionStatus = when (authorization) {
    CBManagerAuthorizationAllowedAlways -> PermissionStatus.Granted
    CBManagerAuthorizationDenied, CBManagerAuthorizationRestricted -> PermissionStatus.PermanentlyDenied
    CBManagerAuthorizationNotDetermined -> PermissionStatus.NotDetermined
    else -> PermissionStatus.NotDetermined
}

// --- Location -----------------------------------------------------------------------------------

/**
 * Location authorization through `CLLocationManager`.
 *
 * A manager is created per request, on the main queue — CoreLocation delivers delegate callbacks on
 * the run loop of the thread that created the manager — and held, with its delegate, until the request
 * resolves: `CLLocationManager.delegate` is weak, so the set below is what keeps both alive.
 */
internal object LocationAuthorization {

    private val pending: MutableSet<AuthorizationDelegate> = mutableSetOf()

    /**
     * Whether this process has asked for the "always" upgrade from "when in use". iOS offers it at most
     * once per install and reports nothing when it declines, so this is the only way to tell "may still
     * be asked" from "settings only". Kept in memory: after a restart one more request finds out again.
     */
    @Volatile
    var alwaysUpgradeAsked: Boolean = false
        private set

    // Read on the calling thread: hopping to the main queue would deadlock a caller that blocks it.
    fun current(): CLAuthorizationStatus = CLLocationManager().authorizationStatus

    /**
     * Asks for "when in use" and suspends until iOS reports a decision. The dialog is guaranteed to
     * appear while the status is not determined, so the delegate is guaranteed to hear back.
     */
    suspend fun requestWhenInUse(): CLAuthorizationStatus = request { manager -> manager.requestWhenInUseAuthorization() }

    /**
     * Asks for "always". iOS shows the upgrade prompt **at most once** per install and reports nothing
     * when it declines to show it, so waiting for the delegate alone could wait forever. This waits for
     * whichever comes first: a changed status, or — if the app did not resign active shortly after the
     * call, meaning no prompt covered it — a short timeout; and if it did resign, until it is active
     * again.
     */
    suspend fun requestAlways(): CLAuthorizationStatus {
        val before: CLAuthorizationStatus = current()
        return awaitPossiblePrompt(
            start = { onChange ->
                startRequest(before, onChange) { manager -> manager.requestAlwaysAuthorization() }
            },
            fallback = ::current,
        ).also { if (before == kCLAuthorizationStatusAuthorizedWhenInUse) alwaysUpgradeAsked = true }
    }

    private suspend fun request(ask: (CLLocationManager) -> Unit): CLAuthorizationStatus =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val abandon: () -> Unit = startRequest(kCLAuthorizationStatusNotDetermined, { status ->
                    if (continuation.isActive) continuation.resume(status)
                }, ask)
                continuation.invokeOnCancellation {
                    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 0), dispatch_get_main_queue()) { abandon() }
                }
            }
        }

    /**
     * Must run on the main queue. Calls [onChange] once, with the first status that differs from
     * [before], and returns the function that abandons the request — releasing its manager — when the
     * caller stops waiting for other reasons.
     */
    private fun startRequest(
        before: CLAuthorizationStatus,
        onChange: (CLAuthorizationStatus) -> Unit,
        ask: (CLLocationManager) -> Unit,
    ): () -> Unit {
        val manager = CLLocationManager()
        val delegate = AuthorizationDelegate(before) { finished: AuthorizationDelegate, status ->
            release(finished)
            onChange(status)
        }
        delegate.manager = manager
        manager.delegate = delegate
        pending += delegate
        ask(manager)
        return { release(delegate) }
    }

    private fun release(delegate: AuthorizationDelegate) {
        delegate.manager?.delegate = null
        delegate.manager = null
        pending -= delegate
    }
}

private class AuthorizationDelegate(
    private val before: CLAuthorizationStatus,
    private val onDecided: (AuthorizationDelegate, CLAuthorizationStatus) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {

    var manager: CLLocationManager? = null
    private var decided: Boolean = false

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        // CoreLocation calls this once as soon as the delegate is set, with the status as it already
        // was; only a different status is the user's answer.
        val status: CLAuthorizationStatus = manager.authorizationStatus
        if (decided || status == before) return
        decided = true
        onDecided(this, status)
    }
}

// --- Media library ------------------------------------------------------------------------------

internal object MediaLibraryAuthorization {

    fun current(): MPMediaLibraryAuthorizationStatus = MPMediaLibrary.authorizationStatus()

    suspend fun request(): MPMediaLibraryAuthorizationStatus = suspendCancellableCoroutine { continuation ->
        MPMediaLibrary.requestAuthorization { status: MPMediaLibraryAuthorizationStatus ->
            if (continuation.isActive) continuation.resume(status)
        }
    }
}

// --- Bluetooth ----------------------------------------------------------------------------------

/**
 * Bluetooth authorization through `CBManager`.
 *
 * iOS has no "request Bluetooth permission" call: the prompt appears when the app first creates a
 * central manager, and the manager reports `centralManagerDidUpdateState` once the user has answered
 * (or at once, when there was nothing to ask). The manager is created with the power alert turned off —
 * asking for permission must not also nag the user to switch Bluetooth on.
 */
internal object BluetoothAuthorization {

    private val pending: MutableSet<BluetoothStateDelegate> = mutableSetOf()

    fun current(): CBManagerAuthorization = CBManager.authorization

    suspend fun request(): CBManagerAuthorization = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val delegate = BluetoothStateDelegate { finished: BluetoothStateDelegate ->
                finished.manager?.delegate = null
                finished.manager = null
                pending -= finished
                if (continuation.isActive) continuation.resume(CBManager.authorization)
            }
            pending += delegate
            delegate.manager = CBCentralManager(
                delegate = delegate,
                queue = null,
                options = mapOf<Any?, Any?>(CBCentralManagerOptionShowPowerAlertKey to false),
            )
            continuation.invokeOnCancellation {
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 0), dispatch_get_main_queue()) {
                    delegate.manager?.delegate = null
                    delegate.manager = null
                    pending -= delegate
                }
            }
        }
    }
}

private class BluetoothStateDelegate(
    private val onUpdated: (BluetoothStateDelegate) -> Unit,
) : NSObject(), CBCentralManagerDelegateProtocol {

    var manager: CBCentralManager? = null
    private var updated: Boolean = false

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        // A state update can arrive while the prompt is still on screen; only a decided authorization
        // answers the request.
        if (updated || CBManager.authorization == CBManagerAuthorizationNotDetermined) return
        updated = true
        onUpdated(this)
    }
}

// --- App activity -------------------------------------------------------------------------------

/**
 * Registers [onActive] for every `UIApplicationDidBecomeActiveNotification`, delivered on the main
 * queue. Returns the function that unregisters it.
 */
internal fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
    val center: NSNotificationCenter = NSNotificationCenter.defaultCenter
    val observer: Any = center.addObserverForName(
        name = UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _: NSNotification? -> onActive() }
    return { center.removeObserver(observer) }
}

/**
 * Starts a request that may or may not put a system prompt on screen, and suspends until it resolves.
 *
 * [start] runs on the main queue, receives the callback for a decided status, and returns the function
 * that abandons the request. When no status
 * arrives, a prompt is inferred from the app resigning active within [PROMPT_GRACE_MILLIS] of the
 * call: if it did not, nothing was shown and [fallback] is read; if it did, [fallback] is read once the
 * app is active again.
 */
private suspend fun <T> awaitPossiblePrompt(
    start: (onChange: (T) -> Unit) -> () -> Unit,
    fallback: () -> T,
): T = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val center: NSNotificationCenter = NSNotificationCenter.defaultCenter
        val observers: MutableList<Any> = mutableListOf()
        var resigned = false
        var finished = false
        var abandon: () -> Unit = {}

        fun finish(value: T) {
            if (finished) return
            finished = true
            observers.forEach(center::removeObserver)
            abandon()
            if (continuation.isActive) continuation.resume(value)
        }

        observers += center.addObserverForName(
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? -> resigned = true }
        observers += center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? -> if (resigned) finish(fallback()) }

        abandon = start { value -> finish(value) }

        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, PROMPT_GRACE_MILLIS * NANOS_PER_MILLI),
            dispatch_get_main_queue(),
        ) {
            if (!resigned) finish(fallback())
        }
        continuation.invokeOnCancellation {
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 0), dispatch_get_main_queue()) {
                if (!finished) {
                    finished = true
                    observers.forEach(center::removeObserver)
                    abandon()
                }
            }
        }
    }
}

/** How long after an "always" request a system prompt has to take the app out of the active state. */
private const val PROMPT_GRACE_MILLIS: Long = 1_000

private const val NANOS_PER_MILLI: Long = 1_000_000
