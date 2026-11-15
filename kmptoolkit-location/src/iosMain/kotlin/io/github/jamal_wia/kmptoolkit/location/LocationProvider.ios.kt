package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.kCLDistanceFilterNone

/**
 * Creates the iOS [LocationProvider], on top of `CLLocationManager`.
 *
 * Ignores the transient `kCLErrorLocationUnknown` error, drops invalid fixes (a negative
 * `horizontalAccuracy`), maps the requested accuracy per call, and answers
 * [LocationUnavailability.PERMISSION_MISSING] at once when the app is not authorized (not
 * determined, denied or restricted) instead of waiting for a timeout. No permission request happens
 * here: the app must declare `NSLocationWhenInUseUsageDescription` (and/or
 * `NSLocationAlwaysAndWhenInUseUsageDescription`) in `Info.plist` and request authorization itself,
 * for example through `kmptoolkit-permission`; see `docs/kmptoolkit-location/05-platform-notes.md`.
 *
 * Every `CLLocationManager` is created, started and released on the **main queue** (CoreLocation
 * delivers its delegate callbacks on the run loop of the thread that created the manager), whichever
 * thread the caller is on. The manager's delegate is held strongly until the call or the stream ends
 * (CoreLocation keeps it weakly) and set to nil before the manager is dropped. When a collection
 * ends or is cancelled the manager is already stopped and released: the collector is not resumed
 * until the main-queue release has run.
 *
 * Age is judged on a monotonic clock: at delivery the provider captures the age of the fix and the
 * system uptime, so a wall-clock correction later changes nothing (PLAN section 10, item 7).
 *
 * Platform-only options and facts: [IosCurrentLocationOptions], [IosLocationUpdatesOptions],
 * [IosFixDetails].
 *
 * @param logger where an unexpected `CLLocationManager` failure is reported.
 */
public fun createLocationProvider(logger: Logger = NoopLogger): LocationProvider =
    IosLocationProvider(SystemIosLocationEnvironment, logger)

internal class IosLocationProvider(
    private val env: IosLocationEnvironment,
    private val logger: Logger,
) : LocationProvider {

    override suspend fun getCurrentLocation(options: CurrentLocationOptions): LocationReading {
        val reason: LocationUnavailability? = blockingReason()
        if (reason != null) return LocationReading.Unavailable(reason)
        // The whole request runs on the main queue: the manager is created, started and released
        // there, and its callbacks arrive there.
        val outcome: OneShotOutcome = withContext(env.mainDispatcher) { OneShotRequest(env, logger, options).run() }
        return when (outcome) {
            is OneShotOutcome.Reading -> outcome.reading
            // A denied failure: authorization changed during the call, so the status is read again.
            OneShotOutcome.Recheck -> LocationReading.Unavailable(blockingReason() ?: LocationUnavailability.NO_FIX)
        }
    }

    override fun observeLocation(options: LocationUpdatesOptions): Flow<LocationReading> =
        // The throttle sits on the final stream, after the platform flow, and the completion after
        // the throttle: a terminal reason is never delayed and the registration is released at once.
        // Every element of the platform flow carries its own seed mark (D-21), so no reseed can be
        // forgotten and nothing is shared between the session and the throttle.
        updates(options)
            .throttledByMarks(options, env.timeSource)
            .completingAfterTerminalReading()

    // Off the main thread: locationServicesEnabled() logs a runtime warning when called there.
    override suspend fun isLocationEnabled(): Boolean = servicesEnabled()

    override fun observeLocationEnabled(): Flow<Boolean> = channelFlow {
        val recheck: Channel<Unit> = Channel(Channel.CONFLATED)
        var manager: IosLocationManager? = null
        try {
            withContext(env.mainDispatcher) {
                manager = env.createManager(AuthorizationChanges { recheck.trySend(Unit) })
            }
            // The first read, then one per authorization change (the switch change arrives as one).
            recheck.trySend(Unit)
            for (signal: Unit in recheck) send(servicesEnabled())
        } finally {
            // The manager is released before the collection ends, on the main queue, even when cancelled.
            withContext(NonCancellable + env.mainDispatcher) {
                manager?.detach()
                manager = null
            }
        }
    }.conflate().distinctUntilChanged()

    override fun openLocationSettings() {
        env.openAppSettings()
    }

    private suspend fun servicesEnabled(): Boolean = withContext(env.offMainDispatcher) { env.locationServicesEnabled() }

    /** Why no request can run now, or `null`; answered at once, never after a timeout. */
    private suspend fun blockingReason(): LocationUnavailability? {
        val status: Int = env.authorizationStatus()
        val servicesOn: Boolean = if (decidesWithoutServices(status)) true else servicesEnabled()
        return authorizationReason(status, servicesOn)
    }

    /**
     * The platform half of the stream. Everything that touches the manager runs on the main queue;
     * the services switch is read off it by a single loop fed by a conflated signal, so two quick
     * authorization changes can never apply an older read after a newer one.
     */
    private fun updates(options: LocationUpdatesOptions): Flow<StreamReading> = channelFlow {
        val platform: IosLocationUpdatesOptions? = options.platform as? IosLocationUpdatesOptions
        // Before any authorization read: a missing background mode fails in every authorization state.
        if (platform?.allowsBackgroundLocationUpdates == true) {
            check(env.hasLocationBackgroundMode()) {
                "allowsBackgroundLocationUpdates needs the \"location\" entry in the UIBackgroundModes key of Info.plist"
            }
        }
        val initialReason: LocationUnavailability? = blockingReason()
        if (initialReason == LocationUnavailability.PERMISSION_MISSING) {
            // Answered at once and without a manager: nothing to register, nothing to release.
            trySend(StreamReading.live(LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)))
            return@channelFlow
        }
        val recheck: Channel<Unit> = Channel(Channel.CONFLATED)
        val session = StreamSession(
            env = env,
            logger = logger,
            options = options,
            platform = platform,
            out = { reading: StreamReading -> trySend(reading) },
            onChange = { recheck.trySend(Unit) },
        )
        try {
            launch {
                for (signal: Unit in recheck) {
                    val servicesOn: Boolean = servicesEnabled()
                    withContext(env.mainDispatcher) { session.applyServices(servicesOn) }
                }
            }
            withContext(env.mainDispatcher) { session.start(initialReason) }
            awaitCancellation()
        } finally {
            // NonCancellable: the release must run even for a cancelled collector, on the main queue,
            // and finish before the collection does (the precedent: TorchBlinker, IosAlarmScheduler).
            // It is a few synchronous calls; its only wait is the main queue itself.
            withContext(NonCancellable + env.mainDispatcher) { session.release() }
            recheck.close()
        }
    }.conflate() // a slow collector must end on the newest reading, never on a stale one
}

/** `locationManagerDidChangeAuthorization` and nothing else: the services switch observer. */
private class AuthorizationChanges(private val onChange: () -> Unit) : IosLocationEvents {
    override fun onAuthorizationChanged() = onChange()
}

private sealed interface OneShotOutcome {
    class Reading(val reading: LocationReading) : OneShotOutcome

    /** Authorization changed under the call: read the reason again. */
    data object Recheck : OneShotOutcome
}

/** Whether a fix was delivered approximate: under a reduced grant, or for a `REDUCED` request (R6-3). */
private fun approximate(manager: IosLocationManager, config: IosManagerConfig): Boolean =
    manager.isAccuracyReduced || isReducedRequest(config.desiredAccuracy)

/**
 * One `getCurrentLocation` call after the authorization check. Confined to the main queue: [run],
 * and every event, run there, so no field needs a lock.
 */
private class OneShotRequest(
    private val env: IosLocationEnvironment,
    private val logger: Logger,
    private val options: CurrentLocationOptions,
) : IosLocationEvents {

    private val config: IosManagerConfig = IosManagerConfig(
        desiredAccuracy = (options.platform as? IosCurrentLocationOptions)?.desiredAccuracyOverride?.toDesiredAccuracy()
            ?: options.accuracy.toDesiredAccuracy(),
        distanceFilter = kCLDistanceFilterNone,
        allowsBackgroundLocationUpdates = false,
        showsBackgroundLocationIndicator = false,
        pausesLocationUpdatesAutomatically = false,
        activityType = IosActivityType.OTHER.toCoreLocation(),
    )
    private val outcome: CompletableDeferred<OneShotOutcome> = CompletableDeferred()
    private var manager: IosLocationManager? = null
    private var escalated: Boolean = false
    private var startedAt: Duration = Duration.ZERO

    suspend fun run(): OneShotOutcome {
        val created: IosLocationManager = env.createManager(this)
        manager = created
        try {
            created.apply(config)
            startedAt = env.clock.uptime()
            cachedReading(created)?.let { cached: LocationReading -> return OneShotOutcome.Reading(cached) }
            created.requestLocation()
            return withTimeoutOrNull(options.timeout) { outcome.await() }
                ?: OneShotOutcome.Reading(LocationReading.Unavailable(LocationUnavailability.NO_FIX))
        } finally {
            // Synchronous and on the main queue (this coroutine runs there): cancelled, timed out or
            // done, the manager is stopped and its delegate set to nil before it is dropped.
            manager = null
            created.stopUpdatingLocation()
            created.detach()
        }
    }

    /** The cache decision (PLAN D11): `manager.location` when it is within `maxAge`, aged with "now" as the delivery. */
    private fun cachedReading(created: IosLocationManager): LocationReading? {
        val raw: IosRawFix = created.lastLocation ?: return null
        val fix: LocationFix = raw.toFixOrNull(approximate(created, config)) ?: return null
        return if (acceptsCachedFix(env.clock.wallAgeOf(raw), options.maxAge)) LocationReading.Available(fix) else null
    }

    override fun onLocations(fixes: List<IosRawFix>) {
        val current: IosLocationManager = manager ?: return
        if (outcome.isCompleted) return
        for (raw: IosRawFix in fixes) {
            val fix: LocationFix? = raw.toFixOrNull(approximate(current, config))
            val uptime: Duration = env.clock.uptime()
            if (fix != null && isFreshDelivery(env.clock.wallAgeOf(raw), options.maxAge, uptime - startedAt)) {
                outcome.complete(OneShotOutcome.Reading(LocationReading.Available(fix)))
                return
            }
        }
        // Invalid or older than maxAge: discarded, and the call keeps waiting until its timeout.
        keepWaiting()
    }

    override fun onFailure(kind: IosFailureKind, description: String) {
        when (kind) {
            IosFailureKind.LOCATION_UNKNOWN -> keepWaiting()
            IosFailureKind.DENIED -> outcome.complete(OneShotOutcome.Recheck)
            IosFailureKind.OTHER -> {
                logger.w { "getCurrentLocation failed: $description" }
                outcome.complete(OneShotOutcome.Reading(LocationReading.Unavailable(LocationUnavailability.NO_FIX)))
            }
        }
    }

    override fun onAuthorizationChanged() {
        val status: Int = manager?.authorizationStatus ?: return
        if (authorizationReason(status, servicesEnabled = true) == LocationUnavailability.PERMISSION_MISSING) {
            outcome.complete(OneShotOutcome.Recheck)
        }
    }

    /**
     * `requestLocation` answers once and stops, so after a discarded answer or a transient error
     * nothing more would arrive: the same manager continues with ordinary updates (one start, never a
     * loop of requests) until the timeout, and the manager is released with the call.
     */
    private fun keepWaiting() {
        if (escalated) return
        escalated = true
        manager?.startUpdatingLocation()
    }
}

private enum class StreamState { IDLE, READY, SERVICE_DISABLED, TERMINAL }

/**
 * One `observeLocation` collection after the first authorization check. Confined to the main queue:
 * [start], [applyServices], [release] and every event run there.
 *
 * It owns exactly one manager for the whole collection. A service flap stops and restarts updates on
 * that manager and re-seeds; it never creates a second manager or delegate (no restart leak).
 */
private class StreamSession(
    private val env: IosLocationEnvironment,
    private val logger: Logger,
    private val options: LocationUpdatesOptions,
    private val platform: IosLocationUpdatesOptions?,
    private val out: (StreamReading) -> Unit,
    private val onChange: () -> Unit,
) : IosLocationEvents {

    private val config: IosManagerConfig = IosManagerConfig(
        desiredAccuracy = platform?.desiredAccuracyOverride?.toDesiredAccuracy() ?: options.accuracy.toDesiredAccuracy(),
        distanceFilter = if (options.minUpdateDistanceMeters > 0.0) options.minUpdateDistanceMeters else kCLDistanceFilterNone,
        allowsBackgroundLocationUpdates = platform?.allowsBackgroundLocationUpdates ?: false,
        showsBackgroundLocationIndicator = platform?.showsBackgroundLocationIndicator ?: false,
        pausesLocationUpdatesAutomatically = platform?.pausesLocationUpdatesAutomatically ?: false,
        activityType = (platform?.activityType ?: IosActivityType.OTHER).toCoreLocation(),
    )
    private var manager: IosLocationManager? = null
    private var unregisterActive: (() -> Unit)? = null
    private var released: Boolean = false
    private var state: StreamState = StreamState.IDLE
    private var paused: Boolean = false

    /** Whether a fix fresh enough for the seed rule has arrived since updates were (re)started. */
    private var freshSeen: Boolean = false
    private var windowStart: Duration = Duration.ZERO

    /** The last fix this stream received itself, for the re-seed after a service flap. */
    private var lastDelivery: IosDelivery? = null

    fun start(initialReason: LocationUnavailability?) {
        if (released) return
        val created: IosLocationManager = env.createManager(this)
        manager = created
        created.apply(config)
        if (config.pausesLocationUpdatesAutomatically) {
            unregisterActive = env.addBecameActiveListener { becameActive() }
        }
        enter(initialReason)
    }

    /** The services switch was read ([servicesOn]); the authorization status is read here, on the main queue. */
    fun applyServices(servicesOn: Boolean) {
        if (released) return
        val current: IosLocationManager = manager ?: return
        enter(authorizationReason(current.authorizationStatus, servicesOn))
    }

    fun release() {
        if (released) return
        released = true
        unregisterActive?.invoke()
        unregisterActive = null
        val current: IosLocationManager? = manager
        manager = null
        lastDelivery = null
        current?.stopUpdatingLocation()
        current?.detach()
    }

    private fun enter(reason: LocationUnavailability?) {
        val current: IosLocationManager = manager ?: return
        // Terminal stays terminal: once PERMISSION_MISSING was emitted nothing may follow it, whatever
        // the authorization does afterwards (the stream is about to complete).
        if (state == StreamState.TERMINAL) return
        when (reason) {
            null -> {
                if (state == StreamState.READY) return
                state = StreamState.READY
                // The seed first, then the registration: nothing a delegate delivers can overtake it.
                emitSeed(current)
                beginUpdates(current)
            }
            LocationUnavailability.SERVICE_DISABLED -> {
                if (state == StreamState.SERVICE_DISABLED) return
                state = StreamState.SERVICE_DISABLED
                current.stopUpdatingLocation()
                out(StreamReading.live(LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)))
            }
            LocationUnavailability.PERMISSION_MISSING -> {
                state = StreamState.TERMINAL
                current.stopUpdatingLocation()
                out(StreamReading.live(LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)))
            }
            else -> Unit
        }
    }

    private fun beginUpdates(current: IosLocationManager) {
        freshSeen = false
        windowStart = env.clock.uptime()
        paused = false
        current.startUpdatingLocation()
    }

    /** `manager.location` and the last fix received here, judged by the cache rule within `maxSeedAge`, else `NO_FIX`. */
    private fun emitSeed(current: IosLocationManager) {
        val candidates: MutableList<AgedFix> = mutableListOf()
        val cachedRaw: IosRawFix? = current.lastLocation
        val cached: LocationFix? = cachedRaw?.toFixOrNull(approximate(current, config))
        if (cachedRaw != null && cached != null) candidates += AgedFix(cached, env.clock.wallAgeOf(cachedRaw))
        lastDelivery?.let { received: IosDelivery -> candidates += AgedFix(received.fix, received.ageAt(env.clock.uptime())) }
        val seed: LocationReading = seedReading(candidates, options.maxSeedAge, LocationUnavailability.NO_FIX)
        // The seed is a position the platform already held (or the NO_FIX standing for none): marked in the element.
        out(StreamReading.seed(seed))
    }

    override fun onLocations(fixes: List<IosRawFix>) {
        if (released || state != StreamState.READY) return
        for (raw: IosRawFix in fixes) {
            val current: IosLocationManager = manager ?: return
            val fix: LocationFix = raw.toFixOrNull(approximate(current, config)) ?: continue
            val uptime: Duration = env.clock.uptime()
            val delivery = IosDelivery(fix, env.clock.wallAgeOf(raw), uptime)
            // Until the first fresh fix, deliveries older than maxSeedAge are cached leftovers: dropped.
            if (!freshSeen) {
                if (!isFreshDelivery(delivery.ageAtDelivery, options.maxSeedAge, uptime - windowStart)) continue
                freshSeen = true
            }
            lastDelivery = delivery
            out(StreamReading.live(LocationReading.Available(fix)))
        }
    }

    override fun onFailure(kind: IosFailureKind, description: String) {
        if (released) return
        when (kind) {
            IosFailureKind.LOCATION_UNKNOWN -> Unit
            IosFailureKind.DENIED -> {
                // Apple: on a denial, stop the service. Only a running stream (READY) goes back to IDLE so the
                // re-read decides what to emit; SERVICE_DISABLED and TERMINAL already said it and stay as they
                // are, so the same state is never emitted twice (iOS reports a switch-off through the
                // authorization callback AND through this error, in either order).
                if (state == StreamState.READY) {
                    manager?.stopUpdatingLocation()
                    state = StreamState.IDLE
                }
                onChange()
            }
            IosFailureKind.OTHER -> logger.w { "observeLocation failed: $description" }
        }
    }

    override fun onAuthorizationChanged() {
        if (released) return
        // The accuracy grade may have changed: the mapped desiredAccuracy is applied again.
        manager?.apply(config)
        onChange()
    }

    override fun onPaused() {
        if (released || !config.pausesLocationUpdatesAutomatically || state != StreamState.READY || paused) return
        paused = true
        out(StreamReading.live(LocationReading.Unavailable(LocationUnavailability.NO_FIX)))
    }

    override fun onResumed() {
        paused = false
    }

    /** Apple leaves restarting after an automatic pause to the app; this is the restart. */
    private fun becameActive() {
        if (released || !paused || state != StreamState.READY) return
        val current: IosLocationManager = manager ?: return
        beginUpdates(current)
    }
}
