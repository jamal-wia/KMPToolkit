package io.github.jamal_wia.kmptoolkit.location

/**
 * Tuning for a [LocationProvider].
 *
 * @property minUpdateDistanceMeters the smallest movement, in meters, that produces a new
 *   [LocationProvider.observeLocation] emission. `0` (the default) means "every update the
 *   platform reports, however small". Maps directly to Android's
 *   `LocationManager.requestLocationUpdates` `minDistance` and to iOS's
 *   `CLLocationManager.distanceFilter`.
 * @property minUpdateIntervalMillis the shortest time, in milliseconds, between two
 *   [LocationProvider.observeLocation] emissions. `0` (the default) means "no throttling". Maps to
 *   Android's `requestLocationUpdates` `minTime`. **iOS ignores this** — CoreLocation has no
 *   time-based throttle, only [minUpdateDistanceMeters]; see
 *   `docs/kmptoolkit-location/05-platform-notes.md`.
 * @property singleFixTimeoutMillis how long [LocationProvider.getCurrentLocation] waits for a
 *   fresh fix before giving up and returning `null`, when no cached location is available. Both
 *   platforms can otherwise suspend forever indoors or with no signal — this cap is what keeps the
 *   call honest. Defaults to 30 seconds.
 */
public data class LocationProviderConfig(
    public val minUpdateDistanceMeters: Float = DEFAULT_MIN_UPDATE_DISTANCE_METERS,
    public val minUpdateIntervalMillis: Long = DEFAULT_MIN_UPDATE_INTERVAL_MILLIS,
    public val singleFixTimeoutMillis: Long = DEFAULT_SINGLE_FIX_TIMEOUT_MILLIS,
) {
    init {
        require(minUpdateDistanceMeters >= 0f) {
            "minUpdateDistanceMeters must not be negative, was $minUpdateDistanceMeters"
        }
        require(minUpdateIntervalMillis >= 0L) {
            "minUpdateIntervalMillis must not be negative, was $minUpdateIntervalMillis"
        }
        require(singleFixTimeoutMillis > 0L) {
            "singleFixTimeoutMillis must be positive, was $singleFixTimeoutMillis"
        }
    }

    public companion object {
        /** `0` — every update, however small. */
        public const val DEFAULT_MIN_UPDATE_DISTANCE_METERS: Float = 0f

        /** `0` — no time-based throttling. */
        public const val DEFAULT_MIN_UPDATE_INTERVAL_MILLIS: Long = 0L

        /** 30 seconds — the default [singleFixTimeoutMillis]. */
        public const val DEFAULT_SINGLE_FIX_TIMEOUT_MILLIS: Long = 30_000L
    }
}
