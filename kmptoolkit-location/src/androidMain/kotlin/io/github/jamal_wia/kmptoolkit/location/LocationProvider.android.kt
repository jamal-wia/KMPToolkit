package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.core.StateMachineLock
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.w
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Creates the Android [LocationProvider], backed by the plain `android.location.LocationManager`
 * API: the framework `FUSED_PROVIDER` on API 31 and later when the device has it, otherwise the
 * available providers raced. Never Google Play Services: no other module in this repository depends
 * on it, and a library artifact must not force it on a consumer (an app that wants Play Services
 * wraps it in its own [LocationProvider], see `docs/kmptoolkit-location/03-guide.md`). See
 * `docs/kmptoolkit-location/05-platform-notes.md` for the full comparison.
 *
 * The app must declare `android.permission.ACCESS_FINE_LOCATION` and/or
 * `android.permission.ACCESS_COARSE_LOCATION` itself: this library declares no permission, on
 * purpose. With neither permission, [LocationProvider.getCurrentLocation] answers
 * `Unavailable(PERMISSION_MISSING)` at once and [LocationProvider.observeLocation] emits it and
 * completes; nothing throws. With `ACCESS_COARSE_LOCATION` alone the platform coarsens the fixes and
 * they read `isApproximate == true`.
 *
 * [LocationProvider.openLocationSettings] opens the screen with
 * [SystemScreenLauncher.SeparateTask]: this factory has no activity to launch from, so the screen
 * gets a task of its own. To choose how it opens, for an ordinary app on your own task with
 * `SystemScreenLauncher.callerTask(activityAccess)`, use [createLocationProviderWithLauncher].
 *
 * Per-call Android options are [AndroidCurrentLocationOptions] and [AndroidLocationUpdatesOptions]
 * (the `platform` slot of the common options); each fix carries [AndroidFixDetails].
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param logger where a missing permission or an unexpected platform failure is reported.
 */
public fun createLocationProvider(
    context: Context,
    logger: Logger = NoopLogger,
): LocationProvider = createLocationProviderWithLauncher(context, SystemScreenLauncher.SeparateTask, logger)

/**
 * Creates the Android [LocationProvider], the same one as [createLocationProvider], whose
 * [LocationProvider.openLocationSettings] hands the location settings screen to
 * [systemScreenLauncher].
 *
 * For an ordinary app, pass `SystemScreenLauncher.callerTask(activityAccess)`: the screen is pushed
 * on top of your resumed activity, so Back returns to your screen and a two-pane Settings on a large
 * screen shows the page on its own instead of handing it to the Settings homepage. `callerTask`
 * does that only when `openLocationSettings` is called on the main thread with an activity resumed;
 * otherwise it opens the screen in a separate task. A lock-task (kiosk) app keeps
 * [SystemScreenLauncher.SeparateTask]: see `docs/kmptoolkit-location/03-guide.md`.
 *
 * Each [LocationProvider.openLocationSettings] call makes exactly one
 * [SystemScreenLauncher.launch] call, with one or more candidate intents, most specific first
 * (currently one: `Settings.ACTION_LOCATION_SOURCE_SETTINGS`), none carrying launch flags, and the
 * kind [LocationSettingsScreen]. A launcher that answers `false` or throws is reported through
 * [logger] as "could not open", exactly like a device without the screen; `openLocationSettings`
 * itself never throws. The launcher is called on the thread that called `openLocationSettings`.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param systemScreenLauncher decides how the settings screen opens and which task it lands in; see
 *   [SystemScreenLauncher.SeparateTask] and [SystemScreenLauncher.callerTask].
 * @param logger where a missing permission, a screen that could not be opened or an unexpected
 *   platform failure is reported.
 * @since 1.7.0
 */
public fun createLocationProviderWithLauncher(
    context: Context,
    systemScreenLauncher: SystemScreenLauncher,
    logger: Logger = NoopLogger,
): LocationProvider = AndroidLocationProvider(context.applicationContext, logger, systemScreenLauncher)

/** What the racing sources of one `getCurrentLocation` call ended with. */
private sealed interface RaceResult {
    class Fix(val fix: LocationFix) : RaceResult

    /** Every source ended without a fix; [refused] when at least one of them refused the caller's permission. */
    class Exhausted(val refused: Boolean) : RaceResult

    /** The sources were switched off while the call waited: a known answer, `SERVICE_DISABLED`, at once. */
    data object Disabled : RaceResult
}

/** Which sources a request uses, or the typed reason there is none. */
private sealed interface Selection {
    class Providers(val names: List<String>) : Selection

    class Failed(val reason: LocationUnavailability) : Selection
}

@OptIn(ToolkitInternalApi::class)
internal class AndroidLocationProvider(
    private val context: Context,
    private val logger: Logger,
    private val systemScreenLauncher: SystemScreenLauncher,
    backend: LocationBackend? = null,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : LocationProvider {

    private val backend: LocationBackend? = backend
        ?: (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
            ?.let { service: LocationManager -> ManagerLocationBackend(context, service) }

    override suspend fun getCurrentLocation(options: CurrentLocationOptions): LocationReading {
        val platform: LocationBackend = backend ?: return unavailable(LocationUnavailability.UNSUPPORTED)
        return try {
            oneShot(platform, options)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (security: SecurityException) {
            logger.w(security) { "Location permission missing in getCurrentLocation" }
            unavailable(LocationUnavailability.PERMISSION_MISSING)
        } catch (failure: Exception) {
            // The contract: never throws for a platform error. A dead system server or an OEM failure outside
            // the race (the race treats a failing source the same way) is "no fix", reported through the logger.
            logger.w(failure) { "The location platform failed in getCurrentLocation" }
            unavailable(LocationUnavailability.NO_FIX)
        }
    }

    private suspend fun oneShot(platform: LocationBackend, options: CurrentLocationOptions): LocationReading {
        val startedAtNanos: Long = platform.elapsedRealtimeNanos()
        if (!hasAnyPermission(platform)) return unavailable(LocationUnavailability.PERMISSION_MISSING)
        val requested: AndroidLocationSource =
            (options.platform as? AndroidCurrentLocationOptions)?.source ?: AndroidLocationSource.AUTO
        val providers: List<String> = when (val selection: Selection = select(platform, requested, options.accuracy, forStream = false)) {
            is Selection.Failed -> return unavailable(selection.reason)
            is Selection.Providers -> selection.names
        }
        // A switched-off source is a known answer: reported at once, never after waiting out the timeout.
        if (isDisabled(platform, providers)) return unavailable(LocationUnavailability.SERVICE_DISABLED)
        val enabled: List<String> = providers.filter { provider: String -> platform.isProviderEnabled(provider) }
        // The sources can switch off between the two reads above: an empty list is the same known answer.
        if (enabled.isEmpty()) return unavailable(LocationUnavailability.SERVICE_DISABLED)
        val cached: LocationFix? = freshestWithin(agedCache(platform, enabled), options.maxAge)
        if (cached != null) return LocationReading.Available(cached)
        // The timeout bounds the whole race; cancelling it (or the caller) cancels every source, and
        // each source removes its own listener in its cancellation handler.
        val outcome: RaceResult? = withTimeoutOrNull(options.timeout) { race(platform, enabled, options, startedAtNanos) }
        return when (outcome) {
            is RaceResult.Fix -> LocationReading.Available(outcome.fix)
            is RaceResult.Exhausted -> unavailable(
                if (outcome.refused) LocationUnavailability.PERMISSION_MISSING else LocationUnavailability.NO_FIX,
            )
            RaceResult.Disabled -> unavailable(LocationUnavailability.SERVICE_DISABLED)
            null -> unavailable(LocationUnavailability.NO_FIX)
        }
    }

    /**
     * Asks every source for a fresh fix at once and takes the first valid one (PLAN D13, F2): a GPS that
     * stays silent indoors no longer burns the timeout before the network provider is asked. The losers
     * are cancelled, which removes their listeners.
     *
     * A participant besides the sources watches the service: when every source is switched off while the
     * call waits (a provider callback or the service broadcast), the race ends with
     * [RaceResult.Disabled] at once instead of waiting out a timeout whose outcome is known (PLAN D19).
     * The participant only completes the one [winner] (first completion wins, thread-safe), so it needs
     * no lock; its broadcast subscription is released in `finally` like the listeners.
     */
    private suspend fun race(
        platform: LocationBackend,
        providers: List<String>,
        options: CurrentLocationOptions,
        startedAtNanos: Long,
    ): RaceResult = coroutineScope {
        val winner = CompletableDeferred<RaceResult>()
        val ended = AtomicInteger(0)
        val refusals = AtomicInteger(0)
        fun checkDisabled() {
            try {
                if (isDisabled(platform, providers)) winner.complete(RaceResult.Disabled)
            } catch (failure: Exception) {
                logger.w(failure) { "Could not re-read the location service state" }
            }
        }
        val unsubscribe: (() -> Unit)? = try {
            platform.observeServiceChanges { checkDisabled() }
        } catch (failure: Exception) {
            // Without the subscription the call still works; it just cannot end early on a switch-off.
            logger.w(failure) { "Could not watch the location service during getCurrentLocation" }
            null
        }
        try {
            // A switch-off between the caller's read and the subscription would otherwise be missed.
            checkDisabled()
            if (!winner.isCompleted) {
                for (provider: String in providers) {
                    launch {
                        try {
                            winner.complete(RaceResult.Fix(awaitFreshFix(platform, provider, options, startedAtNanos, ::checkDisabled)))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (security: SecurityException) {
                            refusals.incrementAndGet()
                            logger.d { "Location provider $provider refused the caller's permission" }
                            sourceEnded(winner, ended, refusals, providers.size)
                        } catch (failure: Exception) {
                            // A platform failure of one source (an OEM IllegalArgumentException) must not fail the call.
                            logger.w(failure) { "Location provider $provider failed in getCurrentLocation" }
                            sourceEnded(winner, ended, refusals, providers.size)
                        }
                    }
                }
            }
            winner.await()
        } finally {
            coroutineContext.cancelChildren()
            if (unsubscribe != null) {
                try {
                    unsubscribe()
                } catch (failure: Exception) {
                    logger.w(failure) { "Could not unregister the location service receiver" }
                }
            }
        }
    }

    private fun sourceEnded(winner: CompletableDeferred<RaceResult>, ended: AtomicInteger, refusals: AtomicInteger, total: Int) {
        if (ended.incrementAndGet() == total) winner.complete(RaceResult.Exhausted(refused = refusals.get() > 0))
    }

    /** Removes [listener]; a failing removal (a dead system server) is logged, never thrown into a callback or a cancellation handler. */
    private fun removeQuietly(platform: LocationBackend, listener: LocationListener) {
        try {
            platform.removeUpdates(listener)
        } catch (failure: Exception) {
            logger.w(failure) { "Could not remove the location listener" }
        }
    }

    /**
     * One source's fresh fix: the framework's one-shot answer first (API 30 and later), judged by the
     * delivery rule (it may hand back a cached fix up to 30 s old), and when that is stale or empty a
     * one-update registration that is removed after the first fresh fix, until the caller's timeout.
     * [onDisabled] is called when the platform reports this source switched off.
     */
    private suspend fun awaitFreshFix(
        platform: LocationBackend,
        provider: String,
        options: CurrentLocationOptions,
        startedAtNanos: Long,
        onDisabled: () -> Unit,
    ): LocationFix {
        val answer: Location? = suspendCancellableCoroutine { continuation ->
            val cancel: (() -> Unit)? = platform.requestCurrentLocation(provider, options.accuracy) { location: Location? ->
                if (continuation.isActive) continuation.resume(location)
            }
            if (cancel == null) {
                continuation.resume(null)
            } else {
                continuation.invokeOnCancellation {
                    // A cancellation handler must not throw: the exception would reach the thread's uncaught handler.
                    try {
                        cancel()
                    } catch (failure: Exception) {
                        logger.w(failure) { "Could not cancel the framework location request" }
                    }
                }
            }
        }
        val framework: LocationFix? = answer?.let { location: Location -> acceptDelivery(platform, location, options.maxAge, startedAtNanos) }
        if (framework != null) return framework
        return suspendCancellableCoroutine { continuation ->
            // Every LocationListener method is implemented: on API 24-29 the three legacy callbacks are
            // abstract (defaults only from API 30) and the platform calls them, so a missing one is an
            // AbstractMethodError on the main thread.
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val fix: LocationFix = acceptDelivery(platform, location, options.maxAge, startedAtNanos) ?: return
                    removeQuietly(platform, this)
                    if (continuation.isActive) continuation.resume(fix)
                }

                override fun onProviderEnabled(provider: String) = Unit

                @Deprecated("Deprecated in Java")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderDisabled(provider: String) = onDisabled()
            }
            // A refused registration throws here and registers nothing.
            platform.requestUpdates(provider, UpdatesRequest(0L, 0f, 0L, options.accuracy), listener)
            continuation.invokeOnCancellation { removeQuietly(platform, listener) }
        }
    }

    /** A delivered fix that is valid and fresh by the delivery rule, else `null` (the source keeps waiting). */
    private fun acceptDelivery(platform: LocationBackend, location: Location, maxAge: Duration, startedAtNanos: Long): LocationFix? {
        val now: Long = platform.elapsedRealtimeNanos()
        val fresh: Boolean = isFreshDelivery(
            age = location.deliveredAge(now),
            maxAge = maxAge,
            sinceRequestStart = (now - startedAtNanos).nanoseconds,
        )
        return if (fresh) location.toLocationFix(coarseOnly = isCoarseOnly(platform)) else null
    }

    override fun observeLocation(options: LocationUpdatesOptions): Flow<LocationReading> =
        // Every element of the platform stream says whether it is a seed or live (loc-core, StreamReading): the
        // mark travels with the reading, so every reseed is marked where it is built, per collection. The final
        // conflate is the contract of observeLocation (a busy collector skips intermediate readings and ends on
        // the newest): the throttle's inbox would otherwise hand it every stale reading in order. A terminal
        // reading is always the last element (the stream completes after it), and conflation never drops the
        // last element, so a busy collector is never stranded without it.
        stream(options).throttledByMarks(options, timeSource).completingAfterTerminalReading().conflate()

    /**
     * The platform stream of one collection. Registration and release happen per collection (nothing is
     * held between collections): the listener and the service-change subscription are removed in
     * `awaitClose` and on every failure path, so a cancelled, completed or failed collection leaves
     * nothing registered. Conflated: a slow collector ends on the newest reading, never a stale one.
     * Every element is [StreamReading.seed] (the first reading and each reseed after the service
     * returned) or [StreamReading.live].
     */
    private fun stream(options: LocationUpdatesOptions): Flow<StreamReading> = callbackFlow {
        val platform: LocationBackend? = backend
        if (platform == null) {
            trySend(StreamReading.live(unavailable(LocationUnavailability.UNSUPPORTED)))
            close()
            return@callbackFlow
        }
        if (!hasAnyPermission(platform)) {
            trySend(StreamReading.live(unavailable(LocationUnavailability.PERMISSION_MISSING)))
            close()
            return@callbackFlow
        }
        val requested: AndroidLocationSource =
            (options.platform as? AndroidLocationUpdatesOptions)?.source ?: AndroidLocationSource.AUTO
        val providers: List<String> = when (val selection: Selection = select(platform, requested, options.accuracy, forStream = true)) {
            is Selection.Failed -> {
                trySend(StreamReading.live(unavailable(selection.reason)))
                close()
                return@callbackFlow
            }
            is Selection.Providers -> selection.names
        }
        val session = StreamSession(platform, options, providers, autoSelect = requested == AndroidLocationSource.AUTO, out = this)
        try {
            session.start()
        } catch (security: SecurityException) {
            // A later provider's registration may have failed after an earlier one succeeded, and awaitClose
            // below is never installed on this path: without this the manager keeps the first provider's
            // listener (and, through it, this dead channel) for the whole process.
            session.release()
            logger.w(security) { "Location permission missing in observeLocation" }
            trySend(StreamReading.live(unavailable(LocationUnavailability.PERMISSION_MISSING)))
            close()
            return@callbackFlow
        } catch (failure: Throwable) {
            // Same undo for any other failure (an OEM IllegalArgumentException, a dead system server); the
            // failure itself still propagates to the collector.
            session.release(failure)
            throw failure
        }
        awaitClose { session.release() }
    }.conflate()

    /**
     * The state of one stream collection and the listener it registers. Every decision about the service
     * state (read the platform, compare with what was reported, publish) runs under one per-collection
     * [StateMachineLock] ([gate]), the repository's primitive for platform callbacks (PATTERNS Part 1):
     * the provider callbacks, the service broadcast, the post-subscription check and every live fix go
     * through `submit`, which never blocks and runs the actions one at a time, in the order they arrived.
     * The later reading therefore always reflects the later read, a transition is reported once, and a
     * live fix cannot overtake a reseed that was read before it. Only the collector's own set-up and
     * release take `exclusive` (they are not platform callbacks). The platform calls made under the gate
     * are plain reads and (de)registrations that never call back synchronously on another thread, so the
     * gate is never held across a suspension or a foreign callback.
     *
     * Everything below the `gate` line of properties is read and written only under [gate].
     */
    private inner class StreamSession(
        private val platform: LocationBackend,
        private val options: LocationUpdatesOptions,
        providers: List<String>,
        private val autoSelect: Boolean,
        private val out: SendChannel<StreamReading>,
    ) : LocationListener {

        private val gate: StateMachineLock = StateMachineLock()

        private val request: UpdatesRequest = UpdatesRequest(
            minTimeMillis = options.minUpdateInterval.inWholeMilliseconds,
            minDistanceMeters = options.minUpdateDistanceMeters.toFloat(),
            maxUpdateDelayMillis =
                ((options.platform as? AndroidLocationUpdatesOptions)?.maxUpdateDelay ?: Duration.ZERO).inWholeMilliseconds,
            accuracy = options.accuracy,
        )

        /** What the selection last decided (empty after a failed re-registration, so the next check retries). */
        private var selected: List<String> = providers

        /**
         * The sources that can deliver: all selected ones during the registration window, then only the ones
         * that accepted it (a source that refused the caller's permission never will, so its flag says nothing).
         */
        private var deliverable: List<String> = providers

        /** The state last reported, so a provider callback and a broadcast that describe the same transition send one reading. */
        private var disabledNow: Boolean = false

        /** Set by [release]: nothing is registered after it. */
        private var closed: Boolean = false

        private var unsubscribe: (() -> Unit)? = null

        /**
         * Seed first, register second. A live fix is delivered on the main looper while this runs on the
         * collector's dispatcher, so a seed sent after the registration could land behind a newer fix and
         * leave the collector on an older position until the next update. The gate is held for the whole
         * set-up, so callbacks that arrive meanwhile are queued behind it and see the finished state. The
         * seed is the freshest cached fix within maxSeedAge, else the reason (SERVICE_DISABLED while the
         * service is off, else NO_FIX), so the stream is never silent. Reading the cache needs the permission
         * too, so it shares the SecurityException path of the registration.
         */
        fun start() {
            gate.exclusive {
                val disabled: Boolean = isDisabled(platform, deliverable)
                disabledNow = disabled
                out.trySend(StreamReading.seed(currentReading(platform, deliverable, options.maxSeedAge, disabled)))
                deliverable = register(selected)
                unsubscribe = platform.observeServiceChanges { evaluate() }
                // A change between the seed and the subscription would otherwise be missed.
                evaluateLocked()
            }
        }

        /** A provider that refuses the caller's permission is skipped, not fatal: a coarse-only app on Android 10 and lower is refused GPS and still gets the network one. */
        private fun register(wanted: List<String>): List<String> {
            val accepted: MutableList<String> = mutableListOf()
            var lastRefusal: SecurityException? = null
            for (provider: String in wanted) {
                try {
                    platform.requestUpdates(provider, request, this)
                    accepted += provider
                } catch (refused: SecurityException) {
                    lastRefusal = refused
                    logger.d { "Location provider $provider refused the caller's permission" }
                }
            }
            if (accepted.isEmpty()) throw lastRefusal ?: SecurityException("no location provider accepted the registration")
            return accepted
        }

        /** Entry for every platform event that may change the service state; never blocks, never throws. */
        fun evaluate() {
            gate.submit {
                try {
                    evaluateLocked()
                } catch (failure: Exception) {
                    // A submitted action must not throw: it would surface on an unrelated thread.
                    logger.w(failure) { "Could not re-read the location service state" }
                }
            }
        }

        /** Gate held. Off: the reason, the stream stays open and the listener stays registered. Back on: reseed. */
        private fun evaluateLocked() {
            if (closed) return
            if (autoSelect) {
                // AUTO uses the first ENABLED source of its table (PLAN D19): when the one it registered went
                // off while another is on, the registration moves instead of reading SERVICE_DISABLED.
                val wanted: List<String> = pickAuto(platform, options.accuracy, forStream = true)
                if (wanted.isNotEmpty() && wanted != selected) rebind(wanted)
            }
            val disabled: Boolean = isDisabled(platform, deliverable)
            if (disabled == disabledNow) return
            disabledNow = disabled
            out.trySend(
                if (disabled) {
                    StreamReading.live(unavailable(LocationUnavailability.SERVICE_DISABLED))
                } else {
                    StreamReading.seed(currentReading(platform, deliverable, options.maxSeedAge, disabled = false))
                },
            )
        }

        private fun rebind(wanted: List<String>) {
            selected = emptyList()
            deliverable = emptyList()
            removeQuietly(platform, this)
            deliverable = register(wanted)
            selected = wanted
        }

        // Every LocationListener method is implemented: on API 24-29 the three legacy callbacks are
        // abstract (defaults only from API 30) and the platform calls them, so a missing one is an
        // AbstractMethodError on the main thread.
        override fun onLocationChanged(location: Location) {
            val fix: LocationFix = location.toLocationFix(coarseOnly = isCoarseOnly(platform)) ?: return
            // Through the gate, so a fix is never sent ahead of a reseed that was read before it arrived.
            gate.submit { out.trySend(StreamReading.live(LocationReading.Available(fix))) }
        }

        override fun onProviderEnabled(provider: String) = evaluate()

        @Deprecated("Deprecated in Java")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderDisabled(provider: String) = evaluate()

        /**
         * Removes the listener and the subscription; idempotent. Removing a listener that is not registered,
         * or unsubscribing twice, is a no-op. The undo must never replace the failure that made it necessary:
         * its own failure is attached to that failure as suppressed, or only logged when there is none.
         */
        fun release(failure: Throwable? = null) {
            gate.exclusive {
                closed = true
                fun undone(undoFailure: Throwable?, what: String) {
                    if (undoFailure == null) return
                    if (failure != null) failure.addSuppressed(undoFailure) else logger.w(undoFailure) { what }
                }
                undone(runCatching { platform.removeUpdates(this) }.exceptionOrNull(), "Could not remove the location listener")
                undone(runCatching { unsubscribe?.invoke() }.exceptionOrNull(), "Could not unregister the location service receiver")
                unsubscribe = null
            }
        }
    }

    override suspend fun isLocationEnabled(): Boolean = backend?.isLocationEnabled() ?: false

    override fun observeLocationEnabled(): Flow<Boolean> {
        val platform: LocationBackend = backend ?: return flowOf(false)
        // The callback only SIGNALS a change; the single consumer reads the value. A value read in the callback
        // and one read by the collector are produced on two threads and could be sent in either order, leaving
        // the flow on the older one. Here every change after a read produces a later tick, so the last read is
        // always current; conflation merges the ticks that have not been consumed yet.
        return callbackFlow {
            // Subscribe first, tick second: a change between the two is then delivered, never lost.
            val unsubscribe: () -> Unit = platform.observeServiceChanges { trySend(Unit) }
            trySend(Unit)
            awaitClose { unsubscribe() }
        }.conflate().map { platform.isLocationEnabled() }.distinctUntilChanged()
    }

    override fun openLocationSettings() {
        val request = SystemScreenRequest(
            candidates = listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
            context = context,
            kind = LocationSettingsScreen,
        )
        val opened: Boolean = try {
            systemScreenLauncher.launch(request)
        } catch (failure: Exception) {
            logger.w(failure) { "Could not open the location settings screen" }
            return
        }
        if (!opened) logger.w { "Could not open the location settings screen" }
    }

    private fun unavailable(reason: LocationUnavailability): LocationReading = LocationReading.Unavailable(reason)

    private fun hasAnyPermission(platform: LocationBackend): Boolean =
        platform.hasFinePermission() || platform.hasCoarsePermission()

    /** Approximate only when just the coarse permission is held at delivery (PLAN section 4.1). */
    private fun isCoarseOnly(platform: LocationBackend): Boolean =
        !platform.hasFinePermission() && platform.hasCoarsePermission()

    /**
     * The sources a request uses (PLAN sections 4.2 and 4.3, D13, D19). An explicit source is used alone, and
     * absent on the device is `UNSUPPORTED`. `AUTO` is [pickAuto].
     */
    private fun select(
        platform: LocationBackend,
        requested: AndroidLocationSource,
        accuracy: LocationAccuracy,
        forStream: Boolean,
    ): Selection {
        val wanted: List<String> = when (requested) {
            AndroidLocationSource.FUSED -> listOf(FUSED)
            AndroidLocationSource.GPS -> listOf(LocationManager.GPS_PROVIDER)
            AndroidLocationSource.NETWORK -> listOf(LocationManager.NETWORK_PROVIDER)
            AndroidLocationSource.PASSIVE -> listOf(LocationManager.PASSIVE_PROVIDER)
            AndroidLocationSource.AUTO -> pickAuto(platform, accuracy, forStream)
        }
        val present: List<String> = wanted.filter { provider: String -> platform.hasProvider(provider) }
        return if (present.isEmpty()) Selection.Failed(LocationUnavailability.UNSUPPORTED) else Selection.Providers(present)
    }

    /**
     * The sources of `AUTO` (PLAN D13, D19): the framework fused provider when the device has it AND it is
     * on, else by accuracy: high gps and network, balanced gps and network (a stream: network, else gps),
     * low power network, else passive. A "first of" row takes the first source that is ON and falls back to
     * the first one that is only PRESENT when none is on (then the call reads `SERVICE_DISABLED`), so a
     * switched-off network provider never hides an enabled gps or passive one ("AUTO races only the enabled
     * providers, and reads SERVICE_DISABLED only when none is enabled"). A device that has only a fused
     * provider uses it even when it is off.
     */
    private fun pickAuto(platform: LocationBackend, accuracy: LocationAccuracy, forStream: Boolean): List<String> {
        if (platform.hasProvider(FUSED) && platform.isProviderEnabled(FUSED)) return listOf(FUSED)
        fun firstOf(vararg order: String): List<String> {
            val present: List<String> = order.filter { provider: String -> platform.hasProvider(provider) }
            return listOfNotNull(present.firstOrNull { provider: String -> platform.isProviderEnabled(provider) } ?: present.firstOrNull())
        }
        val table: List<String> = when {
            accuracy == LocationAccuracy.HIGH_ACCURACY || (accuracy == LocationAccuracy.BALANCED && !forStream) ->
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .filter { provider: String -> platform.hasProvider(provider) }
            accuracy == LocationAccuracy.BALANCED -> firstOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            else -> firstOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        return if (table.isEmpty() && platform.hasProvider(FUSED)) listOf(FUSED) else table
    }

    /** Device-wide off, or none of the request's sources is on (for a named source: that source is off). */
    private fun isDisabled(platform: LocationBackend, providers: List<String>): Boolean =
        !platform.isLocationEnabled() || providers.none { provider: String -> platform.isProviderEnabled(provider) }

    /**
     * What a stream shows now, given the decided [disabled] state: the reason while off, else the freshest
     * cache within [maxSeedAge], else `NO_FIX`. The state is passed in, not re-read, so the reading and the
     * state the caller records come from one read.
     */
    private fun currentReading(
        platform: LocationBackend,
        providers: List<String>,
        maxSeedAge: Duration,
        disabled: Boolean,
    ): LocationReading =
        if (disabled) {
            unavailable(LocationUnavailability.SERVICE_DISABLED)
        } else {
            seedReading(
                agedCache(platform, providers.filter { provider: String -> platform.isProviderEnabled(provider) }),
                maxSeedAge,
                LocationUnavailability.NO_FIX,
            )
        }

    /**
     * The cached fixes of [providers] with their age on the boot clock. A provider that refuses the
     * caller's permission, or whose cache read fails on the platform (a dead system server), simply has no
     * cached fix, and an invalid cached fix is dropped.
     */
    private fun agedCache(platform: LocationBackend, providers: List<String>): List<AgedFix> {
        val now: Long = platform.elapsedRealtimeNanos()
        val coarseOnly: Boolean = isCoarseOnly(platform)
        return providers.mapNotNull { provider: String ->
            val cached: Location? = try {
                platform.lastKnownLocation(provider)
            } catch (refused: SecurityException) {
                null
            } catch (failure: RuntimeException) {
                logger.w(failure) { "Could not read the cached location of $provider" }
                null
            }
            val fix: LocationFix? = cached?.toLocationFix(coarseOnly)
            if (cached == null || fix == null) null else AgedFix(fix, cached.cachedAge(now))
        }
    }

    private companion object {
        /** `LocationManager.FUSED_PROVIDER`, API 31; the literal keeps the reference free of a class load below it. */
        const val FUSED: String = "fused"
    }
}
