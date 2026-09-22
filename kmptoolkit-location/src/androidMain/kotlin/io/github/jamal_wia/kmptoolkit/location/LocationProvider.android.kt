package io.github.jamal_wia.kmptoolkit.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
 * purpose. Without it, [LocationProvider.getCurrentLocation] returns `null` and
 * [LocationProvider.observeLocation] emits `null`, instead of either throwing.
 *
 * [LocationProvider.openLocationSettings] opens the screen with
 * [SystemScreenLauncher.SeparateTask]: this factory has no activity to launch from, so the screen
 * gets a task of its own. Pass your `ActivityAccess` to the overload that takes one to open it on
 * your own task instead, or a [SystemScreenLauncher] to decide yourself.
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
): LocationProvider = createLocationProvider(context, config, logger, SystemScreenLauncher.SeparateTask)

/**
 * Creates the Android [LocationProvider] — the same one as `createLocationProvider(context, config,
 * logger)` — whose [LocationProvider.openLocationSettings] opens the location settings screen on
 * your app's own task, with [SystemScreenLauncher.callerTask] on [activityAccess].
 *
 * The screen is pushed on top of the resumed activity, so the system Back button returns to it and
 * a two-pane Settings on a large screen shows the page on its own instead of handing it to the
 * Settings homepage. With no activity resumed it falls back to a task of its own. Not for a
 * lock-task (kiosk) app: use the overload that takes a [SystemScreenLauncher] and pass
 * [SystemScreenLauncher.SeparateTask]. See `docs/kmptoolkit-location/03-guide.md`.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param activityAccess the app's own activity tracker, created once in `Application.onCreate`
 *   with `createActivityAccess`. Not released by the provider.
 * @param config tuning for update throttling and the single-fix timeout; see
 *   [LocationProviderConfig].
 * @param logger where a missing permission or an unexpected platform failure is reported.
 * @since 1.7.0
 */
public fun createLocationProvider(
    context: Context,
    activityAccess: ActivityAccess,
    config: LocationProviderConfig = LocationProviderConfig(),
    logger: Logger = NoopLogger,
): LocationProvider =
    createLocationProvider(context, config, logger, SystemScreenLauncher.callerTask(activityAccess))

/**
 * Creates the Android [LocationProvider] — the same one as `createLocationProvider(context, config,
 * logger)` — whose [LocationProvider.openLocationSettings] hands the location settings screen to
 * [systemScreenLauncher].
 *
 * Each [LocationProvider.openLocationSettings] call makes exactly one
 * [SystemScreenLauncher.launch] call, with one candidate intent
 * (`Settings.ACTION_LOCATION_SOURCE_SETTINGS`, no launch flags) and the kind
 * [LocationSettingsScreen]. A launcher that answers `false` or throws is reported through [logger]
 * as "could not open", exactly like a device without the screen; `openLocationSettings` itself never
 * throws. The launcher is called on the thread that called `openLocationSettings`.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param config tuning for update throttling and the single-fix timeout; see
 *   [LocationProviderConfig].
 * @param logger where a missing permission, a screen that could not be opened or an unexpected
 *   platform failure is reported.
 * @param systemScreenLauncher decides how the settings screen opens and which task it lands in; see
 *   [SystemScreenLauncher.SeparateTask] and [SystemScreenLauncher.callerTask].
 * @since 1.7.0
 */
public fun createLocationProvider(
    context: Context,
    config: LocationProviderConfig,
    logger: Logger,
    systemScreenLauncher: SystemScreenLauncher,
): LocationProvider = AndroidLocationProvider(context.applicationContext, config, logger, systemScreenLauncher)

private class AndroidLocationProvider(
    private val context: Context,
    private val config: LocationProviderConfig,
    private val logger: Logger,
    private val systemScreenLauncher: SystemScreenLauncher,
) : LocationProvider {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // ACCURACY_FINE steers getBestProvider() towards GPS over network positioning, so a fix can
    // still be obtained with no internet — a cold GNSS fix without assistance is slow, which is
    // why singleFixTimeoutMillis exists and is worth raising for a use case that needs it.
    private val criteria: Criteria = Criteria().apply {
        accuracy = Criteria.ACCURACY_FINE
    }

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): GeoCoordinates? {
        val service: LocationManager = manager ?: return null
        return try {
            val provider: String = bestEnabledProvider(service) ?: return null
            val cached: Location? = service.getLastKnownLocation(provider)
            if (cached != null) return cached.toCoordinates()
            // No cached fix — fall back to a fresh single shot, capped so a device with no signal
            // cannot suspend this call forever.
            withTimeoutOrNull(config.singleFixTimeoutMillis) {
                requestSingleUpdate(service, provider)
            }
        } catch (security: SecurityException) {
            logger.w(security) { "Location permission missing in getCurrentLocation" }
            null
        }
    }

    @SuppressLint("MissingPermission")
    override fun observeLocation(): Flow<GeoCoordinates?> = callbackFlow {
        val service: LocationManager? = manager
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

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toCoordinates())
            }

            override fun onProviderDisabled(provider: String) {
                // Only report "no signal" once every tracked provider is off; another one may
                // still be delivering updates.
                if (providers.none { tracked -> service.isProviderEnabled(tracked) }) trySend(null)
            }
        }

        try {
            providers.forEach { provider ->
                service.requestLocationUpdates(
                    provider,
                    config.minUpdateIntervalMillis,
                    config.minUpdateDistanceMeters,
                    listener,
                    Looper.getMainLooper(),
                )
            }
        } catch (security: SecurityException) {
            logger.w(security) { "Location permission missing in observeLocation" }
            trySend(null)
            close()
            return@callbackFlow
        }

        // Seed immediately with the freshest cached fix across the tracked providers — or `null`
        // when there is none — so the flow is never silent. While the location service is off the
        // update callback never fires, so without this seed observeLocation() would emit nothing
        // at all and every downstream combine would hang on its initial value.
        trySend(freshestLastKnownLocation(service, providers)?.toCoordinates())

        awaitClose { service.removeUpdates(listener) }
    }

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
            logger.w(failure) { "Could not open the location settings screen: the launcher threw" }
            return
        }
        if (!opened) logger.w { "Could not open the location settings screen" }
    }

    private fun bestEnabledProvider(service: LocationManager): String? =
        service.getBestProvider(criteria, true)

    @SuppressLint("MissingPermission")
    private fun freshestLastKnownLocation(service: LocationManager, providers: List<String>): Location? =
        providers.mapNotNull { provider -> service.getLastKnownLocation(provider) }
            .maxByOrNull { location -> location.time }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdate(
        service: LocationManager,
        provider: String,
    ): GeoCoordinates? = suspendCancellableCoroutine { continuation ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                service.removeUpdates(this)
                if (continuation.isActive) continuation.resume(location.toCoordinates())
            }
        }
        service.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        continuation.invokeOnCancellation { service.removeUpdates(listener) }
    }

    private fun Location.toCoordinates(): GeoCoordinates =
        GeoCoordinates(latitude = latitude, longitude = longitude)

    private companion object {
        val TRACKED_PROVIDERS: List<String> =
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
