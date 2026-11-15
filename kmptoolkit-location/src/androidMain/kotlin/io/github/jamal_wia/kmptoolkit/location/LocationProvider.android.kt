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
import java.util.concurrent.ConcurrentHashMap
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

/**
 * The sources `AUTO` offers, in preference order. [firstOnly] rows ("network, else gps") use only the
 * first candidate the caller can use; the others use every candidate that accepts.
 */
private class AutoPick(val candidates: List<String>, val firstOnly: Boolean) {
    /** The sources to use now: the candidates minus the [refused] ones, cut to the first when [firstOnly]. */
    fun target(refused: Set<String>): List<String> {
        val usable: List<String> = candidates.filter { provider: String -> provider !in refused }
        return if (firstOnly) usable.take(1) else usable
    }
}

/** Which sources a request uses, or the typed reason there is none. */
private sealed interface Selection {
    /** [names] in preference order; a [firstOnly] selection uses just the first one the caller can use. */
    class Providers(val names: List<String>, val firstOnly: Boolean) : Selection

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
        val selection: Selection.Providers = when (val chosen: Selection = select(platform, requested, options.accuracy, forStream = false)) {
            is Selection.Failed -> return unavailable(chosen.reason)
            is Selection.Providers -> chosen
        }
        val providers: List<String> = if (selection.firstOnly) selection.names.take(1) else selection.names
        // A switched-off source is a known answer: reported at once, never after waiting out the timeout.
        if (isDisabled(platform, providers)) return unavailable(LocationUnavailability.SERVICE_DISABLED)
        val enabled: List<String> = providers.filter { provider: String -> platform.isProviderEnabled(provider) }
        // The row's candidates that this call does not ask (switched off, or behind the first of a "first of" row):
        // when every source that was asked refuses the caller, they decide between "grant missing" and "service off".
        val notRaced: List<String> = selection.names.filter { provider: String -> provider !in enabled }
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
                when {
                    !outcome.refused -> LocationUnavailability.NO_FIX
                    // The stream on the same device registers the candidate that is only off and reads SERVICE_DISABLED
                    // (PLAN D8 one mapper, D19): the grant is not what is missing, so the answer must not be PERMISSION_MISSING.
                    onlySwitchedOffRemains(platform, notRaced) -> LocationUnavailability.SERVICE_DISABLED
                    else -> LocationUnavailability.PERMISSION_MISSING
                },
            )
            RaceResult.Disabled -> unavailable(LocationUnavailability.SERVICE_DISABLED)
            null -> unavailable(LocationUnavailability.NO_FIX)
        }
    }

    /**
     * Every source the call asked refused the caller. True when a candidate that was not asked is present and
     * switched off (or the device switch is off): the caller holds its grant and the user can change the service,
     * exactly what the stream reports for the same state. False when every candidate refused (`PERMISSION_MISSING`)
     * and for an explicit source, whose only candidate was asked.
     */
    private fun onlySwitchedOffRemains(platform: LocationBackend, notRaced: List<String>): Boolean =
        notRaced.isNotEmpty() && (!platform.isLocationEnabled() || notRaced.any { provider: String -> !platform.isProviderEnabled(provider) })

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
        // The sources that can still deliver: a source that refused the caller or failed is removed, because its
        // "on" flag says nothing (it can never answer). "Disabled" is decided over this set only (PLAN D19).
        val inRace: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(providers) }
        fun checkDisabled() {
            try {
                val remaining: List<String> = inRace.toList()
                // An empty set is "every source ended", which sourceEnded answers; it is not "switched off".
                if (remaining.isNotEmpty() && isDisabled(platform, remaining)) winner.complete(RaceResult.Disabled)
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
                            inRace.remove(provider)
                            sourceEnded(winner, ended, refusals, providers.size, ::checkDisabled)
                        } catch (failure: Exception) {
                            // A platform failure of one source (an OEM IllegalArgumentException) must not fail the call.
                            logger.w(failure) { "Location provider $provider failed in getCurrentLocation" }
                            inRace.remove(provider)
                            sourceEnded(winner, ended, refusals, providers.size, ::checkDisabled)
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

    /**
     * One source ended without a fix. The last one ends the race ([RaceResult.Exhausted]); otherwise the
     * sources still waiting may all be off already (the one that ended may have been the only one that was on),
     * so [recheckDisabled] runs: a known answer at once, never the timeout.
     */
    private fun sourceEnded(
        winner: CompletableDeferred<RaceResult>,
        ended: AtomicInteger,
        refusals: AtomicInteger,
        total: Int,
        recheckDisabled: () -> Unit,
    ) {
        if (ended.incrementAndGet() == total) winner.complete(RaceResult.Exhausted(refused = refusals.get() > 0)) else recheckDisabled()
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
                    // acceptDelivery reads the boot clock and the grant (platform calls): a failure must not escape into the
                    // platform's listener callback (the main looper's uncaught exception). The delivery is dropped and logged;
                    // the source keeps waiting for the next fix, bounded by the caller's timeout like any silent source.
                    val fix: LocationFix = try {
                        acceptDelivery(platform, location, options.maxAge, startedAtNanos)
                    } catch (failure: Exception) {
                        logger.w(failure) { "Could not judge a delivered location fix in getCurrentLocation; the fix is dropped" }
                        null
                    } ?: return
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
        val selection: Selection.Providers = when (val chosen: Selection = select(platform, requested, options.accuracy, forStream = true)) {
            is Selection.Failed -> {
                trySend(StreamReading.live(unavailable(chosen.reason)))
                close()
                return@callbackFlow
            }
            is Selection.Providers -> chosen
        }
        val session = StreamSession(
            platform,
            options,
            selection,
            autoSelect = requested == AndroidLocationSource.AUTO,
            out = this,
        )
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
        initial: Selection.Providers,
        private val autoSelect: Boolean,
        private val out: SendChannel<StreamReading>,
    ) : LocationListener {

        private val gate: StateMachineLock = StateMachineLock()

        private val request: UpdatesRequest = UpdatesRequest(
            minTimeMillis = options.minUpdateInterval.inWholeMilliseconds,
            // A finite Double above Float.MAX_VALUE is a valid option but Infinity as a Float, which the platform
            // request rejects; the common throttle applies the exact Double, so the platform only needs "far".
            minDistanceMeters = options.minUpdateDistanceMeters.coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat(),
            maxUpdateDelayMillis =
                ((options.platform as? AndroidLocationUpdatesOptions)?.maxUpdateDelay ?: Duration.ZERO).inWholeMilliseconds,
            accuracy = options.accuracy,
        )

        /**
         * What is registered: the candidates until [start] has registered, then the sources that accepted
         * (empty after a failed re-registration, so the next check retries, and while every candidate refused the
         * caller with the service switched off: the stream waits for the service, see [start]).
         */
        private var selected: List<String> = initial.names

        /** The candidates of the request as selected at the start (an explicit source: its one provider). */
        private val initialNames: List<String> = initial.names

        /** The "first usable only" rows of the AUTO table register one source; every other selection registers all that accept. */
        private val firstOnly: Boolean = initial.firstOnly

        /**
         * The sources that can deliver: all candidates during the registration window, then only the ones
         * that accepted it (a source that refused the caller's permission never will, so its flag says nothing).
         */
        private var deliverable: List<String> = initial.names

        /**
         * The sources the platform refused this caller for (a SecurityException). A revoke kills the process
         * (D16), so a refusal only goes away with a new grant: AUTO skips these sources instead of trying them
         * again on every event, until the fine grant differs from the one they were met under
         * ([forgetRefusalsOfAnotherGrant]).
         */
        private val refused: MutableSet<String> = mutableSetOf()

        /**
         * Whether the app held the fine grant when the refusals in [refused] were met. A grant can arrive while the
         * stream runs (a revoke kills the process, a grant does not) and a refusal under the coarse grant says nothing
         * about the fine one, so [refused] is dropped as soon as the grant differs from this.
         */
        private var refusedUnderFine: Boolean = false

        /**
         * The AUTO decision whose move failed on the platform (not a refusal) and was undone by putting the previous
         * registration back. The restored source is switched off, and the platform answers a registration on a disabled
         * provider with `onProviderDisabled` at once: that event would re-run the same failing move forever. The move is
         * not retried until the decision itself changes (then a transient failure gets its new chance).
         */
        private var failedMove: List<String>? = null

        private var lastRefusal: SecurityException? = null

        /**
         * The state last reported, so a provider callback and a broadcast that describe the same transition send one
         * reading. `null` means unknown: an evaluation did not finish (a platform read failed), so what the collector
         * holds may no longer be the state recorded here (a live fix can arrive in between and contradict it). Unknown
         * never equals a state, so the next evaluation always reports the true one, at worst as one duplicate reading.
         */
        private var disabledNow: Boolean? = false

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
                // The reading is built BEFORE the state is recorded: a failing platform read while building it must not
                // leave a recorded state whose reading was never sent.
                val seed: StreamReading = StreamReading.seed(currentReading(platform, deliverable, options.maxSeedAge, disabled))
                disabledNow = disabled
                out.trySend(seed)
                var accepted: List<String> = register(selected, firstOnly)
                if (accepted.isEmpty()) {
                    // A grant that landed after the platform's refusal is not a missing grant: refusals met under another
                    // grant than the current one are asked again once (the same rule as every later evaluation).
                    forgetRefusalsOfAnotherGrant()
                    if (refused.isEmpty()) accepted = register(selected, firstOnly)
                }
                if (accepted.isEmpty()) {
                    // PERMISSION_MISSING only when every candidate refused while the service is ON. A switched-off
                    // service wins over a refusal (PLAN 4.2: "denied with services off is SERVICE_DISABLED", D8: the
                    // one-shot answers the same): the stream stays open on SERVICE_DISABLED with nothing registered
                    // and ends with PERMISSION_MISSING when the service comes back (evaluateLocked).
                    if (!isDisabled(platform, deliverable)) {
                        throw lastRefusal ?: SecurityException("no location provider accepted the registration")
                    }
                    selected = emptyList()
                } else {
                    selected = accepted
                    deliverable = accepted
                }
                unsubscribe = platform.observeServiceChanges { evaluate() }
                // A change between the seed and the subscription would otherwise be missed. This evaluation stands in for
                // the service event the subscription missed, so it behaves like one, grant re-judgement included.
                evaluateLocked()
            }
        }

        /**
         * Registers [wanted] in order and returns the sources that accepted (all of them, or just the first when
         * [firstOnly]). A provider that refuses the caller's permission is skipped, not fatal, and remembered in
         * [refused]: a coarse-only app on Android 10 and lower is refused GPS and still gets the network one.
         * Any other failure propagates, unless [skipFailures] (a move): then that candidate is skipped like a
         * refusal, but not remembered as refused (it can be transient), so the candidates that accept are kept.
         */
        private fun register(wanted: List<String>, firstOnly: Boolean, skipFailures: Boolean = false): List<String> {
            val accepted: MutableList<String> = mutableListOf()
            for (provider: String in wanted) {
                if (provider in refused) continue
                // Read BEFORE the platform is asked: a grant that lands between the refusal and a later read would
                // be recorded as the grant the refusal was met under and pin it. Before errs the safe way: a refusal
                // recorded under the coarse grant is tried once more under the fine one.
                val fineBefore: Boolean = platform.hasFinePermission()
                try {
                    platform.requestUpdates(provider, request, this)
                    accepted += provider
                } catch (refusal: SecurityException) {
                    lastRefusal = refusal
                    refusedUnderFine = fineBefore
                    refused += provider
                    logger.d { "Location provider $provider refused the caller's permission" }
                } catch (failure: Exception) {
                    if (!skipFailures) throw failure
                    logger.w(failure) { "Location provider $provider failed to register during a move" }
                }
                if (firstOnly && accepted.isNotEmpty()) break
            }
            return accepted
        }

        /** Entry for every platform event that may change the service state; never blocks, never throws. */
        fun evaluate() {
            submitEvaluation(provider = null)
        }

        /**
         * Entry for a listener callback about [provider]. A callback for a provider this session does not hold is
         * ignored: on API 24-28 the platform delivers a registration's callbacks as posted messages even after the
         * registration was removed, and the brief registration of a switched-off source inside a failed move posts
         * `onProviderDisabled` at once, so re-evaluating on it would re-run that move and post the next callback,
         * forever. The service broadcast ([evaluate]) fires on every real change and is what retries a state with
         * nothing registered.
         */
        private fun evaluateFor(provider: String) {
            submitEvaluation(provider)
        }

        /**
         * Queues one evaluation behind the gate; [provider] null runs it unconditionally, else only while this session
         * holds that provider. A failure is logged and swallowed (a submitted action must not throw: it would surface
         * on an unrelated thread), and whatever ended the evaluation early, an `Exception` or an `Error` (which still
         * propagates), leaves the recorded state unknown ([disabledNow]): the next service event reports the true state
         * even if a live fix was sent in between.
         */
        private fun submitEvaluation(provider: String?) {
            gate.submit {
                var finished: Boolean = false
                try {
                    if (provider == null || provider in selected) evaluateLocked()
                    finished = true
                } catch (failure: Exception) {
                    logger.w(failure) { "Could not re-read the location service state" }
                } finally {
                    if (!finished) disabledNow = null
                }
            }
        }

        /**
         * Gate held. Off: the reason, the stream stays open and the listener stays registered. Back on: reseed.
         * A waiting stream (nothing registered, every candidate refused) never reseeds: it reads SERVICE_DISABLED until
         * the terminal or a grant. [recheckGrant]: the terminal is decided on refusals, so the grant is read once more
         * right before it; a grant that landed during this evaluation runs it again (once, under the new grant).
         */
        private fun evaluateLocked(recheckGrant: Boolean = true) {
            if (closed) return
            forgetRefusalsOfAnotherGrant()
            var candidates: List<String> = initialNames
            if (autoSelect) {
                // AUTO uses the first ENABLED source of its table that accepts the caller (PLAN D19): when the one
                // it registered went off while another is on, the registration moves instead of reading
                // SERVICE_DISABLED. A move never ends the evaluation: whatever is registered afterwards is what
                // the decision below judges.
                val pick: AutoPick = pickAuto(platform, options.accuracy, forStream = true)
                val target: List<String> = pick.target(refused)
                candidates = pick.candidates
                if (target != failedMove) failedMove = null
                if (target.isNotEmpty() && target != selected && failedMove == null) rebind(pick)
            } else if (selected.isEmpty() && initialNames.any { provider: String -> provider !in refused }) {
                // An explicit source that was refused while the service was off, and the grant changed since (or a
                // platform failure left nothing registered): ask again. Only the service broadcast reaches this
                // state (no provider is held, so no listener callback does).
                val accepted: List<String> = registerQuietly(initialNames, firstOnly = false)
                if (accepted.isNotEmpty()) {
                    selected = accepted
                    deliverable = accepted
                }
            }
            if (selected.isEmpty() && candidates.isNotEmpty() && candidates.all { provider: String -> provider in refused }) {
                // Waiting: nothing is registered and every candidate refused the caller (D-30). ONE read of the service
                // state decides everything below, so a switch that turns on between two reads cannot publish a reseed
                // the stream has no registration to back.
                val candidatesOff: Boolean = isDisabled(platform, candidates)
                if (candidatesOff) {
                    if (disabledNow != true) {
                        disabledNow = true
                        out.trySend(StreamReading.live(unavailable(LocationUnavailability.SERVICE_DISABLED)))
                    }
                    return
                }
                if (recheckGrant && platform.hasFinePermission() != refusedUnderFine) return evaluateLocked(recheckGrant = false)
                // The service is on again and the refusals stand under the current grant (D16: only a new grant changes
                // them): now the grant is what is missing, as at the start of a stream whose service was on. No platform
                // call. The terminal reading is the last element.
                closed = true
                out.trySend(StreamReading.live(unavailable(LocationUnavailability.PERMISSION_MISSING)))
                out.close()
                return
            }
            val disabled: Boolean = isDisabled(platform, deliverable)
            if (disabled == disabledNow) return
            // Build the reading, then record the state, then send: a reading that fails to build (a failing grant or
            // cache read) never records a state whose reading was not sent, and the evaluation entry marks the state
            // unknown, so the next event runs this transition again even after a live fix was sent in between.
            val reading: StreamReading = if (disabled) {
                StreamReading.live(unavailable(LocationUnavailability.SERVICE_DISABLED))
            } else {
                StreamReading.seed(currentReading(platform, deliverable, options.maxSeedAge, disabled = false))
            }
            disabledNow = disabled
            out.trySend(reading)
        }

        /**
         * Gate held. Refusals met under another grant than the current one are not evidence any more: they are tried
         * again.
         */
        private fun forgetRefusalsOfAnotherGrant() {
            if (refused.isEmpty() || platform.hasFinePermission() == refusedUnderFine) return
            refused.clear()
            failedMove = null
        }

        /**
         * Moves the registration to [pick]: the old one is removed first (the platform treats a second
         * registration of the same listener as a replacement, and two would deliver twice), then the first
         * accepting candidate is registered. When none accepts (every candidate refused the caller, or the
         * platform failed) the previous sources are put back, so something stays registered and the caller's
         * decision judges the real state instead of a hole. Never throws: a failure is logged and leaves
         * [selected] empty only when nothing could be registered, so the next event tries again. A move that failed
         * on the platform and was restored is not retried until the decision changes ([failedMove]).
         */
        private fun rebind(pick: AutoPick) {
            val previous: List<String> = deliverable
            selected = emptyList()
            deliverable = emptyList()
            removeQuietly(platform, this)
            var accepted: List<String> = try {
                // A platform failure of one candidate is skipped like a refusal: the candidates that accept are kept.
                register(pick.candidates, pick.firstOnly, skipFailures = true)
            } catch (failure: Exception) {
                logger.w(failure) { "Could not move the location registration" }
                removeQuietly(platform, this)
                emptyList()
            }
            // A usable candidate of the decision that did not register failed on the platform (a refusal is not usable).
            val incomplete: Boolean = accepted != pick.target(refused)
            if (accepted.isEmpty()) accepted = registerQuietly(previous, firstOnly = false)
            if (accepted.isEmpty()) {
                // Nothing is registered: keep judging the last known sources; selected stays empty. The next service
                // broadcast retries; a listener callback does not (the session holds no provider it could come from).
                deliverable = previous
                return
            }
            // Whatever is registered now is not the decision: remember it, so the echo of this very registration
            // (a restored or kept source that is switched off answers onProviderDisabled at once) is not a retry.
            if (incomplete) failedMove = pick.target(refused)
            selected = accepted
            deliverable = accepted
        }

        /** [register] for a move: a failure is logged, whatever it had registered is removed, and nothing is accepted. */
        private fun registerQuietly(wanted: List<String>, firstOnly: Boolean): List<String> =
            try {
                register(wanted, firstOnly)
            } catch (failure: Exception) {
                logger.w(failure) { "Could not move the location registration" }
                removeQuietly(platform, this)
                emptyList()
            }

        // Every LocationListener method is implemented: on API 24-29 the three legacy callbacks are
        // abstract (defaults only from API 30) and the platform calls them, so a missing one is an
        // AbstractMethodError on the main thread.
        override fun onLocationChanged(location: Location) {
            // Through the gate, so a fix is never sent ahead of a reseed that was read before it arrived. The fix is
            // built INSIDE the submitted action (it reads the grant, a platform call): a failing read must not escape
            // into the platform's listener callback, which is the main looper's uncaught exception. The fix is dropped
            // and logged; the next one is built afresh.
            gate.submit {
                try {
                    val fix: LocationFix = location.toLocationFix(coarseOnly = isCoarseOnly(platform)) ?: return@submit
                    out.trySend(StreamReading.live(LocationReading.Available(fix)))
                } catch (failure: Exception) {
                    logger.w(failure) { "Could not read the location grant for a delivered fix; the fix is dropped" }
                }
            }
        }

        override fun onProviderEnabled(provider: String) = evaluateFor(provider)

        @Deprecated("Deprecated in Java")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderDisabled(provider: String) = evaluateFor(provider)

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
        val pick: AutoPick = when (requested) {
            AndroidLocationSource.FUSED -> AutoPick(listOf(FUSED), firstOnly = false)
            AndroidLocationSource.GPS -> AutoPick(listOf(LocationManager.GPS_PROVIDER), firstOnly = false)
            AndroidLocationSource.NETWORK -> AutoPick(listOf(LocationManager.NETWORK_PROVIDER), firstOnly = false)
            AndroidLocationSource.PASSIVE -> AutoPick(listOf(LocationManager.PASSIVE_PROVIDER), firstOnly = false)
            AndroidLocationSource.AUTO -> pickAuto(platform, accuracy, forStream)
        }
        val present: List<String> = pick.candidates.filter { provider: String -> platform.hasProvider(provider) }
        return if (present.isEmpty()) Selection.Failed(LocationUnavailability.UNSUPPORTED) else Selection.Providers(present, pick.firstOnly)
    }

    /**
     * The sources of `AUTO` (PLAN D13, D19): the framework fused provider when the device has it AND it is
     * on, else by accuracy: high gps and network, balanced gps and network (a stream: network, else gps),
     * low power network, else passive. A "first of" row lists its present sources with the ON ones first, then
     * the ones that are only PRESENT, and is [AutoPick.firstOnly]: the caller uses the first candidate that
     * accepts it, so a switched-off network provider never hides an enabled gps or passive one ("AUTO races
     * only the enabled providers, and reads SERVICE_DISABLED only when none is enabled"), and a candidate the
     * platform refuses the caller is passed over, never turned into a terminal PERMISSION_MISSING while
     * another candidate of the row can serve (it reads SERVICE_DISABLED while that one is off). A device that
     * has only a fused provider uses it even when it is off.
     */
    private fun pickAuto(platform: LocationBackend, accuracy: LocationAccuracy, forStream: Boolean): AutoPick {
        if (platform.hasProvider(FUSED) && platform.isProviderEnabled(FUSED)) return AutoPick(listOf(FUSED), firstOnly = false)
        fun firstOf(vararg order: String): AutoPick {
            val present: List<String> = order.filter { provider: String -> platform.hasProvider(provider) }
            val (on: List<String>, off: List<String>) = present.partition { provider: String -> platform.isProviderEnabled(provider) }
            return AutoPick(on + off, firstOnly = true)
        }
        val table: AutoPick = when {
            accuracy == LocationAccuracy.HIGH_ACCURACY || (accuracy == LocationAccuracy.BALANCED && !forStream) ->
                AutoPick(
                    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                        .filter { provider: String -> platform.hasProvider(provider) },
                    firstOnly = false,
                )
            accuracy == LocationAccuracy.BALANCED -> firstOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            else -> firstOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        return if (table.candidates.isEmpty() && platform.hasProvider(FUSED)) AutoPick(listOf(FUSED), firstOnly = false) else table
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
