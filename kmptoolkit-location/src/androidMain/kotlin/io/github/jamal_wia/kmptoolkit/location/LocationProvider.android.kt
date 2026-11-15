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
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.w
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
}

/** Which sources a request uses, or the typed reason there is none. */
private sealed interface Selection {
    class Providers(val names: List<String>) : Selection

    class Failed(val reason: LocationUnavailability) : Selection
}

/**
 * Remembers the seed readings of one stream collection (the first one and every reseed after the service
 * returned) so the throttle can recognise each by identity (decision D-21, correction round 1): a cached
 * position never starts the interval, so the first live fix after any seed is not held behind it. Only the
 * last few are kept: a seed that the conflated stream dropped before the throttle saw it is never asked
 * about, so nothing needs removing, and the list cannot grow with the number of flaps.
 */
private class SeedMarker {
    @Volatile
    private var recent: List<LocationReading> = emptyList()

    /** Marks [reading] as a seed; call before sending it. */
    fun mark(reading: LocationReading): LocationReading {
        recent = (recent + reading).takeLast(RECENT_SEEDS)
        return reading
    }

    fun isSeed(reading: LocationReading): Boolean = recent.any { seed: LocationReading -> seed === reading }

    private companion object {
        const val RECENT_SEEDS: Int = 4
    }
}

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
        return try {
            val cached: LocationFix? = freshestWithin(agedCache(platform, enabled), options.maxAge)
            if (cached != null) return LocationReading.Available(cached)
            // The timeout bounds the whole race; cancelling it (or the caller) cancels every source, and
            // each source removes its own listener in its cancellation handler.
            val outcome: RaceResult? = withTimeoutOrNull(options.timeout) { race(platform, enabled, options, startedAtNanos) }
            when (outcome) {
                is RaceResult.Fix -> LocationReading.Available(outcome.fix)
                is RaceResult.Exhausted -> unavailable(
                    if (outcome.refused) LocationUnavailability.PERMISSION_MISSING else LocationUnavailability.NO_FIX,
                )
                null -> unavailable(LocationUnavailability.NO_FIX)
            }
        } catch (security: SecurityException) {
            logger.w(security) { "Location permission missing in getCurrentLocation" }
            unavailable(LocationUnavailability.PERMISSION_MISSING)
        }
    }

    /**
     * Asks every source for a fresh fix at once and takes the first valid one (PLAN D13, F2): a GPS that
     * stays silent indoors no longer burns the timeout before the network provider is asked. The losers
     * are cancelled, which removes their listeners.
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
        for (provider: String in providers) {
            launch {
                try {
                    winner.complete(RaceResult.Fix(awaitFreshFix(platform, provider, options, startedAtNanos)))
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
        try {
            winner.await()
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    private fun sourceEnded(winner: CompletableDeferred<RaceResult>, ended: AtomicInteger, refusals: AtomicInteger, total: Int) {
        if (ended.incrementAndGet() == total) winner.complete(RaceResult.Exhausted(refused = refusals.get() > 0))
    }

    /**
     * One source's fresh fix: the framework's one-shot answer first (API 30 and later), judged by the
     * delivery rule (it may hand back a cached fix up to 30 s old), and when that is stale or empty a
     * one-update registration that is removed after the first fresh fix, until the caller's timeout.
     */
    private suspend fun awaitFreshFix(
        platform: LocationBackend,
        provider: String,
        options: CurrentLocationOptions,
        startedAtNanos: Long,
    ): LocationFix {
        val answer: Location? = suspendCancellableCoroutine { continuation ->
            val cancel: (() -> Unit)? = platform.requestCurrentLocation(provider, options.accuracy) { location: Location? ->
                if (continuation.isActive) continuation.resume(location)
            }
            if (cancel == null) continuation.resume(null) else continuation.invokeOnCancellation { cancel() }
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
                    platform.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(fix)
                }

                override fun onProviderEnabled(provider: String) = Unit

                @Deprecated("Deprecated in Java")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }
            // A refused registration throws here and registers nothing.
            platform.requestUpdates(provider, UpdatesRequest(0L, 0f, 0L, options.accuracy), listener)
            continuation.invokeOnCancellation { platform.removeUpdates(listener) }
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

    override fun observeLocation(options: LocationUpdatesOptions): Flow<LocationReading> = flow {
        // One marker per collection: two collectors of the same flow must not see each other's seed.
        val marker = SeedMarker()
        emitAll(
            stream(options, marker)
                .throttledBy(options, timeSource) { reading: LocationReading -> marker.isSeed(reading) }
                .completingAfterTerminalReading(),
        )
    }

    /**
     * The platform stream of one collection. Registration and release happen per collection (nothing is
     * held between collections): the listener and the service-change subscription are removed in
     * `awaitClose` and on every failure path, so a cancelled, completed or failed collection leaves
     * nothing registered. Conflated: a slow collector ends on the newest reading, never a stale one.
     */
    private fun stream(options: LocationUpdatesOptions, marker: SeedMarker): Flow<LocationReading> = callbackFlow {
        val platform: LocationBackend? = backend
        if (platform == null) {
            trySend(unavailable(LocationUnavailability.UNSUPPORTED))
            close()
            return@callbackFlow
        }
        if (!hasAnyPermission(platform)) {
            trySend(unavailable(LocationUnavailability.PERMISSION_MISSING))
            close()
            return@callbackFlow
        }
        val requested: AndroidLocationSource =
            (options.platform as? AndroidLocationUpdatesOptions)?.source ?: AndroidLocationSource.AUTO
        val providers: List<String> = when (val selection: Selection = select(platform, requested, options.accuracy, forStream = true)) {
            is Selection.Failed -> {
                trySend(unavailable(selection.reason))
                close()
                return@callbackFlow
            }
            is Selection.Providers -> selection.names
        }
        val maxUpdateDelay: Duration = (options.platform as? AndroidLocationUpdatesOptions)?.maxUpdateDelay ?: Duration.ZERO
        val request = UpdatesRequest(
            minTimeMillis = options.minUpdateInterval.inWholeMilliseconds,
            minDistanceMeters = options.minUpdateDistanceMeters.toFloat(),
            maxUpdateDelayMillis = maxUpdateDelay.inWholeMilliseconds,
            accuracy = options.accuracy,
        )

        // The state last reported, so a provider callback and a broadcast that describe the same
        // transition send one reading, not two.
        val disabledNow = AtomicBoolean(isDisabled(platform, providers))
        // The sources that can deliver: all of them during the registration window, then only the ones that
        // accepted it (a source that refused the caller's permission never will, so its flag says nothing).
        val deliverable = AtomicReference<List<String>>(providers)

        fun reseed(): LocationReading = currentReading(platform, deliverable.get(), options.maxSeedAge)

        fun evaluate() {
            val disabled: Boolean = isDisabled(platform, deliverable.get())
            if (disabledNow.getAndSet(disabled) == disabled) return
            // Off: the reason, the stream stays open and the listener stays registered. Back on: reseed.
            trySend(if (disabled) unavailable(LocationUnavailability.SERVICE_DISABLED) else marker.mark(reseed()))
        }

        // Every LocationListener method is implemented: on API 24-29 the three legacy callbacks are
        // abstract (defaults only from API 30) and the platform calls them, so a missing one is an
        // AbstractMethodError on the main thread.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix: LocationFix = location.toLocationFix(coarseOnly = isCoarseOnly(platform)) ?: return
                trySend(LocationReading.Available(fix))
            }

            override fun onProviderEnabled(provider: String) = evaluate()

            @Deprecated("Deprecated in Java")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderDisabled(provider: String) = evaluate()
        }

        var unsubscribe: (() -> Unit)? = null
        fun release(failure: Throwable? = null) {
            // Removing a listener that is not registered, or unsubscribing twice, is a no-op. The undo must
            // never replace the failure that made it necessary: its own failure is attached to that failure
            // as suppressed, or only logged when there is none.
            fun undone(undoFailure: Throwable?, what: String) {
                if (undoFailure == null) return
                if (failure != null) failure.addSuppressed(undoFailure) else logger.w(undoFailure) { what }
            }
            undone(runCatching { platform.removeUpdates(listener) }.exceptionOrNull(), "Could not remove the location listener")
            undone(runCatching { unsubscribe?.invoke() }.exceptionOrNull(), "Could not unregister the location service receiver")
            unsubscribe = null
        }

        try {
            // Seed first, register second. A live fix is delivered on the main looper while this block may
            // run on any dispatcher, so a seed sent after the registration can land behind a newer fix and
            // leave the collector on an older position until the next update. Sent before, every live fix
            // follows it in the channel by construction. The seed is the freshest cached fix within
            // maxSeedAge, else the reason (SERVICE_DISABLED while the service is off, else NO_FIX), so the
            // stream is never silent. Reading the cache needs the permission too, so it shares the
            // SecurityException path of the registration.
            trySend(marker.mark(reseed()))
            // A provider that refuses the caller's permission is skipped, not fatal: a coarse-only app on
            // Android 10 and lower is refused the GPS provider and still gets the network one.
            val accepted = mutableListOf<String>()
            var lastRefusal: SecurityException? = null
            for (provider: String in providers) {
                try {
                    platform.requestUpdates(provider, request, listener)
                    accepted += provider
                } catch (refused: SecurityException) {
                    lastRefusal = refused
                    logger.d { "Location provider $provider refused the caller's permission" }
                }
            }
            if (accepted.isEmpty()) throw lastRefusal ?: SecurityException("no location provider accepted the registration")
            deliverable.set(accepted.toList())
            unsubscribe = platform.observeServiceChanges { evaluate() }
            // A change between the seed and the subscription would otherwise be missed.
            evaluate()
        } catch (security: SecurityException) {
            // A later provider's registration may have failed after an earlier one succeeded, and awaitClose
            // below is never installed on this path: without this the manager keeps the first provider's
            // listener (and, through it, this dead channel) for the whole process.
            release()
            logger.w(security) { "Location permission missing in observeLocation" }
            trySend(unavailable(LocationUnavailability.PERMISSION_MISSING))
            close()
            return@callbackFlow
        } catch (failure: Throwable) {
            // Same undo for any other failure (an OEM IllegalArgumentException, a dead system server); the
            // failure itself still propagates to the collector.
            release(failure)
            throw failure
        }

        awaitClose { release() }
    }.conflate()

    override suspend fun isLocationEnabled(): Boolean = backend?.isLocationEnabled() ?: false

    override fun observeLocationEnabled(): Flow<Boolean> = callbackFlow {
        val platform: LocationBackend? = backend
        if (platform == null) {
            trySend(false)
            close()
            return@callbackFlow
        }
        // Subscribe first, read second: a change between the two is then delivered, never lost.
        val unsubscribe: () -> Unit = platform.observeServiceChanges { trySend(platform.isLocationEnabled()) }
        trySend(platform.isLocationEnabled())
        awaitClose { unsubscribe() }
    }.conflate().distinctUntilChanged()

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
     * absent on the device is `UNSUPPORTED`. `AUTO` uses the framework fused provider when the device has it,
     * else by accuracy: high gps and network, balanced gps and network (a stream: network, else gps), low
     * power network, else passive.
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
            AndroidLocationSource.AUTO -> when {
                platform.hasProvider(FUSED) -> listOf(FUSED)
                accuracy == LocationAccuracy.HIGH_ACCURACY -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                accuracy == LocationAccuracy.BALANCED && !forStream ->
                    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                accuracy == LocationAccuracy.BALANCED ->
                    listOfNotNull(
                        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                            .firstOrNull { provider: String -> platform.hasProvider(provider) },
                    )
                else -> listOfNotNull(
                    listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                        .firstOrNull { provider: String -> platform.hasProvider(provider) },
                )
            }
        }
        val present: List<String> = wanted.filter { provider: String -> platform.hasProvider(provider) }
        return if (present.isEmpty()) Selection.Failed(LocationUnavailability.UNSUPPORTED) else Selection.Providers(present)
    }

    /** Device-wide off, or none of the request's sources is on (for a named source: that source is off). */
    private fun isDisabled(platform: LocationBackend, providers: List<String>): Boolean =
        !platform.isLocationEnabled() || providers.none { provider: String -> platform.isProviderEnabled(provider) }

    /** What a stream shows now: the reason while off, else the freshest cache within [maxSeedAge], else `NO_FIX`. */
    private fun currentReading(platform: LocationBackend, providers: List<String>, maxSeedAge: Duration): LocationReading =
        if (isDisabled(platform, providers)) {
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
     * caller's permission simply has no cached fix for it, and an invalid cached fix is dropped.
     */
    private fun agedCache(platform: LocationBackend, providers: List<String>): List<AgedFix> {
        val now: Long = platform.elapsedRealtimeNanos()
        val coarseOnly: Boolean = isCoarseOnly(platform)
        return providers.mapNotNull { provider: String ->
            val cached: Location? = try {
                platform.lastKnownLocation(provider)
            } catch (refused: SecurityException) {
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
