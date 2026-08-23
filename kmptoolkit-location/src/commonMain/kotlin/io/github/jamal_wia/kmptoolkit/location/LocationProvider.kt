package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic access to the device's geographic position.
 *
 * Obtain one from the platform factory — `createLocationProvider(context, config, logger)` on
 * Android, `createLocationProvider(config, logger)` on iOS — and pass it into shared code as this
 * interface, which is what keeps a `Context` out of `commonMain` without an `expect` declaration
 * that would have to lie about its parameters.
 *
 * Raw coordinates only: no caching, no permission UI, no business logic. Implementations assume
 * the location permission is already granted and never throw or block waiting for it — if the
 * permission is missing, [observeLocation] emits `null` and [getCurrentLocation] returns `null`.
 * Requesting the permission itself is `kmptoolkit-permission`'s job (or your own platform code);
 * this module is permission-agnostic on purpose, see
 * `docs/kmptoolkit-location/05-platform-notes.md`.
 */
public interface LocationProvider {

    /**
     * Returns a single location fix, or `null` if none could be obtained.
     *
     * Prefers a cached fix the platform already has; falls back to requesting a fresh one, capped
     * by [LocationProviderConfig.singleFixTimeoutMillis] so a device with no signal cannot hang
     * this call forever. Never throws for a missing permission or a disabled location service —
     * both come back as `null`.
     */
    public suspend fun getCurrentLocation(): GeoCoordinates?

    /**
     * Hot stream of location updates. Emits `null` when no fix is available (permission revoked,
     * location service disabled, no signal) so a collector never sits without an answer.
     * Implementations stop the underlying location request when the flow is cancelled.
     */
    public fun observeLocation(): Flow<GeoCoordinates?>

    /**
     * Whether the device-wide location service (the quick-settings "Location" toggle) is currently
     * on. Independent of the app's location *permission*: the permission can be granted while the
     * service is off.
     *
     * `suspend` so implementations can run the check off the main thread — on iOS
     * `CLLocationManager.locationServicesEnabled()` warns when called on the main thread while
     * authorization is still being determined.
     */
    public suspend fun isLocationEnabled(): Boolean

    /**
     * Sends the user to the system screen where they can turn the location service back on.
     *
     * On Android that is the dedicated location-source settings screen; on iOS the platform
     * exposes no deep link to the Location Services toggle, so this falls back to the app's own
     * settings page.
     */
    public fun openLocationSettings()
}
