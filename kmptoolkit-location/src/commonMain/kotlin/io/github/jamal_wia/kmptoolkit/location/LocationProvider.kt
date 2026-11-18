package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.flow.Flow

/**
 * Device location: one-shot reads, a stream and the service state.
 *
 * Obtain one from the platform factory (`createLocationProvider(context, logger)` on Android,
 * `createLocationProvider(logger)` on iOS, `createLocationProvider()` on desktop JVM) and pass it into
 * shared code as this interface, which keeps a `Context` out of `commonMain`.
 *
 * Requests no permission: assume it is granted, or read the typed answer. Requesting it is
 * `kmptoolkit-permission`'s job (or your own platform code); this module is permission-agnostic on
 * purpose, see `docs/kmptoolkit-location/05-platform-notes.md`.
 *
 * An invalid platform fix (NaN or out-of-range coordinates, on iOS also a negative horizontal
 * accuracy) is dropped, never thrown and never emitted: implementations build coordinates through
 * [GeoCoordinates.orNull]. App decorators implement this interface like any other.
 */
public interface LocationProvider {

    /**
     * One reading: a cached fix within [CurrentLocationOptions.maxAge], else a fresh fix (the
     * available sources raced) within [CurrentLocationOptions.timeout], else a typed reason.
     *
     * Never throws for a missing permission, a disabled service, a platform error or a timeout;
     * each comes back as [LocationReading.Unavailable]. Cancellable.
     */
    public suspend fun getCurrentLocation(options: CurrentLocationOptions = CurrentLocationOptions()): LocationReading

    /**
     * Cold, conflated stream of readings until cancelled.
     *
     * Seeds at once: a cached fix within [LocationUpdatesOptions.maxSeedAge], else a reason. It
     * completes after [LocationUnavailability.PERMISSION_MISSING] or
     * [LocationUnavailability.UNSUPPORTED] and stays open through
     * [LocationUnavailability.SERVICE_DISABLED] and [LocationUnavailability.NO_FIX], identically on
     * every platform. A collector that is busy skips intermediate readings and ends on the newest.
     * Implementations stop the underlying request when the flow is cancelled.
     */
    public fun observeLocation(options: LocationUpdatesOptions = LocationUpdatesOptions()): Flow<LocationReading>

    /**
     * Whether the device-wide location service (the quick-settings "Location" toggle) is on now.
     * Independent of the app's location permission: the permission can be granted while the
     * service is off.
     *
     * `suspend` so implementations can run the check off the main thread; on iOS
     * `CLLocationManager.locationServicesEnabled()` warns when called on the main thread.
     */
    public suspend fun isLocationEnabled(): Boolean

    /**
     * The device-wide service state, emitted at once and on every change (distinct), without
     * starting location updates.
     */
    public fun observeLocationEnabled(): Flow<Boolean>

    /**
     * Opens the system location settings (iOS: the app's own settings page, because iOS exposes no
     * deep link to the Location Services toggle; desktop JVM: does nothing). Never throws.
     */
    public fun openLocationSettings()

    /**
     * Asks for an in-place "turn location on" prompt where one exists. See [LocationServicePrompt]
     * for what came of it.
     *
     * Neither the Android nor the iOS factory raises an in-place dialog by default: Android's
     * would need Google Play Services' `SettingsClient`, which this module deliberately does not
     * depend on, and on iOS the system's own "Turn On Location Services" alert is opt-in through
     * `withSystemServicesPrompt()`. The default body reflects that honestly:
     * [LocationServicePrompt.ALREADY_ON] when [isLocationEnabled] is `true`, else
     * [LocationServicePrompt.UNSUPPORTED]. A caller can write one `when` over all four cases that
     * does the right thing today and keeps doing it if an implementation adds a real dialog.
     */
    public suspend fun promptToEnableService(): LocationServicePrompt =
        if (isLocationEnabled()) LocationServicePrompt.ALREADY_ON else LocationServicePrompt.UNSUPPORTED
}
