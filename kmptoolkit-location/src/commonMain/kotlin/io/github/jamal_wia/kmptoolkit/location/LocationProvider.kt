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

    /**
     * Asks the system to turn the location service back on without leaving the app, if the
     * platform offers a way to do that. See [LocationServicePrompt] for what came of it.
     *
     * Neither this module's Android nor its iOS implementation raises an in-place dialog — Android's
     * version would need Google Play Services' `SettingsClient`, which this module deliberately does
     * not depend on (see `docs/kmptoolkit-location/05-platform-notes.md`), and iOS exposes no public
     * API for it. The default implementation reflects that honestly: [LocationServicePrompt.ALREADY_ON]
     * when [isLocationEnabled] is already `true`, [LocationServicePrompt.UNSUPPORTED] otherwise — so
     * a caller can write one `when` over all four cases that already does the right thing today, and
     * would keep doing the right thing if your own [LocationProvider] (or a future revision of this
     * one) ever adds a real resolution dialog behind [LocationServicePrompt.PROMPTED] /
     * [LocationServicePrompt.NOT_NOW] instead of just overriding [openLocationSettings]'s fallback.
     */
    public suspend fun promptToEnableService(): LocationServicePrompt =
        if (isLocationEnabled()) LocationServicePrompt.ALREADY_ON else LocationServicePrompt.UNSUPPORTED
}
