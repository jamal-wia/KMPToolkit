package io.github.jamal_wia.kmptoolkit.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Creates the Android [LocationProvider], backed by the plain `android.location.LocationManager`
 * API rather than Google Play Services' `FusedLocationProviderClient`.
 *
 * That is a deliberate choice, not an oversight: no other module in this repository depends on
 * Play Services, and pulling it in here would be exactly the kind of heavy, optional transitive
 * dependency this library's whole ethos avoids (the same reason `kmptoolkit-storage` uses
 * `AndroidKeyStore` and `Cipher` directly instead of Tink). The real trade-off: Fused blends GPS,
 * Wi-Fi and cell signal through Google's positioning service and typically produces a fix faster
 * and with lower power draw than either raw provider alone, and it degrades gracefully on devices
 * without Play Services installed, which `LocationManager` has no equivalent for. See
 * `docs/kmptoolkit-location/05-platform-notes.md` for the full comparison.
 *
 * The app must declare `android.permission.ACCESS_FINE_LOCATION` and/or
 * `android.permission.ACCESS_COARSE_LOCATION` itself — this library declares no permission, on
 * purpose. With neither permission, [LocationProvider.getCurrentLocation] returns `null` and
 * [LocationProvider.observeLocation] emits `null` and ends, instead of either throwing. With
 * `ACCESS_COARSE_LOCATION` alone, fixes come from the providers the platform lets a coarse caller
 * use. On Android 10 and lower that is the network provider only.
 *
 * [LocationProvider.openLocationSettings] opens the screen with
 * [SystemScreenLauncher.SeparateTask]: this factory has no activity to launch from, so the screen
 * gets a task of its own. To choose how it opens — for an ordinary app, on your own task with
 * `SystemScreenLauncher.callerTask(activityAccess)` — use [createLocationProviderWithLauncher].
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param config tuning for update throttling and the single-fix timeout; see
 *   [LocationProviderConfig].
 * @param logger where a missing permission or an unexpected platform failure is reported.
 */
public fun createLocationProvider(
    context: Context,
    config: LocationProviderConfig = LocationProviderConfig(),
    logger: Logger = NoopLogger,
): LocationProvider =
    createLocationProviderWithLauncher(context, SystemScreenLauncher.SeparateTask, config, logger)

/**
 * Creates the Android [LocationProvider] — the same one as [createLocationProvider] — whose
 * [LocationProvider.openLocationSettings] hands the location settings screen to
 * [systemScreenLauncher].
 *
 * For an ordinary app, pass `SystemScreenLauncher.callerTask(activityAccess)`: the screen is pushed
 * on top of your resumed activity, so Back returns to your screen and a two-pane Settings on a large
 * screen shows the page on its own instead of handing it to the Settings homepage. `callerTask`
 * does that only when `openLocationSettings` is called on the main thread with an activity resumed;
 * otherwise it opens the screen in a separate task. A lock-task (kiosk) app keeps
 * [SystemScreenLauncher.SeparateTask] — see `docs/kmptoolkit-location/03-guide.md`.
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
 * @param config tuning for update throttling and the single-fix timeout; see
 *   [LocationProviderConfig].
 * @param logger where a missing permission, a screen that could not be opened or an unexpected
 *   platform failure is reported.
 * @since 1.7.0
 */
public fun createLocationProviderWithLauncher(
    context: Context,
    systemScreenLauncher: SystemScreenLauncher,
    config: LocationProviderConfig = LocationProviderConfig(),
    logger: Logger = NoopLogger,
): LocationProvider = AndroidLocationProvider(context.applicationContext, config, logger, systemScreenLauncher)

/**
 * The part of `LocationManager` that [AndroidLocationProvider.observeLocation] uses, as a seam: the
 * framework class cannot be subclassed in a unit test, and the order of a registration against a
 * delivery is exactly what a test of the seed has to control.
 */
internal interface LocationUpdatesSource {
    val allProviders: List<String>
    fun isProviderEnabled(provider: String): Boolean
    fun lastKnownLocation(provider: String): Location?
    fun requestUpdates(provider: String, minTimeMillis: Long, minDistanceMeters: Float, listener: LocationListener)
    fun removeUpdates(listener: LocationListener)
}

@SuppressLint("MissingPermission")
private class ManagerUpdatesSource(private val service: LocationManager) : LocationUpdatesSource {
    override val allProviders: List<String> get() = service.allProviders
    override fun isProviderEnabled(provider: String): Boolean = service.isProviderEnabled(provider)
    override fun lastKnownLocation(provider: String): Location? = service.getLastKnownLocation(provider)
    override fun requestUpdates(
        provider: String,
        minTimeMillis: Long,
        minDistanceMeters: Float,
        listener: LocationListener,
    ) {
        service.requestLocationUpdates(provider, minTimeMillis, minDistanceMeters, listener, Looper.getMainLooper())
    }

    override fun removeUpdates(listener: LocationListener) = service.removeUpdates(listener)
}

internal class AndroidLocationProvider(
    private val context: Context,
    private val config: LocationProviderConfig,
    private val logger: Logger,
    private val systemScreenLauncher: SystemScreenLauncher,
    updatesSource: LocationUpdatesSource? = null,
) : LocationProvider {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val updatesSource: LocationUpdatesSource? =
        updatesSource ?: manager?.let { service: LocationManager -> ManagerUpdatesSource(service) }

    // ACCURACY_FINE steers getBestProvider() towards GPS over network positioning, so a fix can
    // still be obtained with no internet — a cold GNSS fix without assistance is slow, which is
    // why singleFixTimeoutMillis exists and is worth raising for a use case that needs it.
    private val criteria: Criteria = Criteria().apply {
        accuracy = Criteria.ACCURACY_FINE
    }

    override suspend fun getCurrentLocation(): GeoCoordinates? {
        val service: LocationManager = manager ?: return null
        val source: LocationUpdatesSource = updatesSource ?: return null
        return try {
            val provider: String = bestEnabledProvider(service) ?: return null
            // The freshest cache across the enabled tracked providers (plus the one getBestProvider
            // named, in case it is neither), the same rule observeLocation seeds with: the GPS provider
            // being on without a fix must not hide a fix the network provider already holds.
            val cached: Location? = freshestLastKnownLocation(source, (TRACKED_PROVIDERS + provider).distinct())
            if (cached != null) return cached.toCoordinates()
            // No cached fix — fall back to a fresh single shot, capped so a device with no signal
            // cannot suspend this call forever. A provider that refuses the caller's permission (the GPS
            // provider, for a coarse-only app on Android 10 and lower) is skipped for the next candidate,
            // the best provider first; only when every candidate refuses is the permission reported missing.
            val candidates: List<String> =
                (listOf(provider) + TRACKED_PROVIDERS).distinct().filter { candidate -> source.isProviderEnabled(candidate) }
            var lastRefusal: SecurityException? = null
            var exhausted = false
            val fix: GeoCoordinates? = withTimeoutOrNull(config.singleFixTimeoutMillis) {
                for (candidate in candidates) {
                    try {
                        return@withTimeoutOrNull requestSingleUpdate(source, candidate)
                    } catch (refused: SecurityException) {
                        lastRefusal = refused
                    }
                }
                exhausted = true
                null
            }
            val refusal: SecurityException? = lastRefusal
            if (fix == null && exhausted && refusal != null) {
                logger.w(refusal) { "Location permission missing in getCurrentLocation" }
            }
            fix
        } catch (security: SecurityException) {
            logger.w(security) { "Location permission missing in getCurrentLocation" }
            null
        }
    }

    override fun observeLocation(): Flow<GeoCoordinates?> = callbackFlow {
        val service: LocationUpdatesSource? = updatesSource
        if (service == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val providers: List<String> = TRACKED_PROVIDERS.filter { provider -> provider in service.allProviders }
        if (providers.isEmpty()) {
            trySend(null)
            close()
            return@callbackFlow
        }

        // The providers that can deliver: all tracked ones during the registration window, then only the
        // ones that accepted the registration (a provider that refused the caller's permission never will).
        val live = MutableStateFlow(providers)

        // Every LocationListener method is implemented: on API 24-29 the three legacy callbacks are
        // abstract (defaults only from API 30) and the platform calls them, so a missing one is an
        // AbstractMethodError on the main thread.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toCoordinates())
            }

            override fun onProviderEnabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderDisabled(provider: String) {
                // Only report "no signal" once every provider that can deliver is off; another one may
                // still be delivering updates.
                if (live.value.none { tracked -> service.isProviderEnabled(tracked) }) trySend(null)
            }
        }

        val accepted = mutableListOf<String>()
        val refusals = linkedMapOf<String, SecurityException>()
        try {
            // Seed first, register second. A live fix is delivered on the main looper while this
            // block may run on any dispatcher, so a seed sent after the registration can land behind
            // a newer fix and leave the collector on an older position until the next update. Sent
            // before, every live fix follows it in the channel by construction. The seed is the
            // freshest cached fix across the enabled tracked providers — or `null` when there is none or every provider is off — so
            // the flow is never silent: while the location service is off the update callback never
            // fires, and without it observeLocation() would emit nothing at all and every downstream
            // combine would hang on its initial value. Reading the cache needs the permission too,
            // so it shares the SecurityException path of the registration.
            trySend(freshestLastKnownLocation(service, providers)?.toCoordinates())
            // A provider that refuses the caller's permission is skipped, not fatal: a coarse-only app on
            // Android 10 and lower is refused the GPS provider and still gets the network one.
            providers.forEach { provider ->
                try {
                    service.requestUpdates(
                        provider,
                        config.minUpdateIntervalMillis,
                        config.minUpdateDistanceMeters,
                        listener,
                    )
                    accepted += provider
                } catch (refused: SecurityException) {
                    refusals[provider] = refused
                }
            }
            if (accepted.isEmpty()) throw refusals.values.last()
        } catch (security: SecurityException) {
            // A later provider's registration may have failed after an earlier one succeeded, and
            // awaitClose below is never installed on this path: without this the manager keeps the
            // first provider's listener (and, through it, this dead channel) for the whole process.
            // Removing a listener that is not registered is a no-op. The undo must not turn this
            // documented "emit null" path into a thrown exception, so its own failure is only logged.
            runCatching { service.removeUpdates(listener) }.exceptionOrNull()
                ?.let { undoFailure -> logger.w(undoFailure) { "Could not remove the location listener" } }
            logger.w(security) { "Location permission missing in observeLocation" }
            trySend(null)
            close()
            return@callbackFlow
        } catch (failure: Throwable) {
            // Same undo for any other failure (an OEM IllegalArgumentException, a dead system
            // server); the failure itself still propagates to the collector. A failing undo is
            // attached to it as suppressed instead of replacing it.
            runCatching { service.removeUpdates(listener) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }

        live.value = accepted.toList()
        refusals.keys.forEach { refused ->
            logger.d { "Location provider $refused refused the caller's permission; continuing with $accepted" }
        }

        awaitClose { service.removeUpdates(listener) }
    }.conflate() // a slow collector must end on the newest fix, never on a stale one

    override suspend fun isLocationEnabled(): Boolean {
        val service: LocationManager = manager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.isLocationEnabled
        } else {
            service.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                service.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
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

    private fun bestEnabledProvider(service: LocationManager): String? =
        service.getBestProvider(criteria, true)

    private fun freshestLastKnownLocation(service: LocationUpdatesSource, providers: List<String>): Location? =
        // Only providers that are switched on: the platform may keep handing out the last fix of a
        // disabled provider, and a collection that starts after the user turned location off must
        // begin on `null` (the same rule onProviderDisabled applies), not on a stale position.
        freshest(
            providers.filter { provider -> service.isProviderEnabled(provider) }
                .mapNotNull { provider ->
                    // A provider that refuses the caller's permission simply has no cached fix for it.
                    try {
                        service.lastKnownLocation(provider)
                    } catch (refused: SecurityException) {
                        null
                    }
                },
        )

    // Location.time is wall-clock and jumps when the clock is corrected; elapsedRealtimeNanos is the
    // platform's monotonic stamp. A fix without one (0: a test fixture) makes the whole comparison
    // fall back to time, so stamps from two different clocks are never compared.
    private fun freshest(fixes: List<Location>): Location? =
        if (fixes.all { fix -> fix.elapsedRealtimeNanos > 0L }) {
            fixes.maxByOrNull { fix -> fix.elapsedRealtimeNanos }
        } else {
            fixes.maxByOrNull { fix -> fix.time }
        }

    private suspend fun requestSingleUpdate(
        service: LocationUpdatesSource,
        provider: String,
    ): GeoCoordinates? = suspendCancellableCoroutine { continuation ->
        // Every LocationListener method is implemented: on API 24-29 the three legacy callbacks are
        // abstract (defaults only from API 30) and the platform calls them, so a missing one is an
        // AbstractMethodError on the main thread.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                service.removeUpdates(this)
                if (continuation.isActive) continuation.resume(location.toCoordinates())
            }

            override fun onProviderEnabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        service.requestUpdates(provider, 0L, 0f, listener)
        continuation.invokeOnCancellation { service.removeUpdates(listener) }
    }

    private fun Location.toCoordinates(): GeoCoordinates =
        GeoCoordinates(latitude = latitude, longitude = longitude)

    private companion object {
        val TRACKED_PROVIDERS: List<String> =
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
