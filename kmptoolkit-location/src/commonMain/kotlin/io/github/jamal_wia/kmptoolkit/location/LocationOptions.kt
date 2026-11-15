package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How hard the platform should work for a fix. A request, not a guarantee: an approximate grant
 * caps every level. The per-platform mapping table is in
 * `docs/kmptoolkit-location/05-platform-notes.md`.
 */
public enum class LocationAccuracy {

    /** GNSS-grade, works offline (Android fused high accuracy or gps; iOS Best). */
    HIGH_ACCURACY,

    /** About 100 m, may need a data connection (Android fused balanced or network; iOS HundredMeters). */
    BALANCED,

    /** City-level, least power (Android fused low power or network; iOS Kilometer). */
    LOW_POWER,
}

/**
 * Options of one [LocationProvider.getCurrentLocation] call.
 *
 * Throws [IllegalArgumentException] for a negative [maxAge] or a non-positive [timeout].
 *
 * @property accuracy Precision against power.
 * @property maxAge Oldest cached fix acceptable, judged on the platform's monotonic clock.
 * [Duration.ZERO] forces a fresh fix, [Duration.INFINITE] accepts any cache.
 * @property timeout How long to wait for a fresh fix before `Unavailable(NO_FIX)`;
 * [Duration.INFINITE] waits until cancelled.
 * @property platform Options only one platform understands (`AndroidCurrentLocationOptions`,
 * `IosCurrentLocationOptions`), built in platform code and passed through shared code.
 */
public data class CurrentLocationOptions(
    public val accuracy: LocationAccuracy = LocationAccuracy.BALANCED,
    public val maxAge: Duration = 30.seconds,
    public val timeout: Duration = 30.seconds,
    public val platform: PlatformCurrentLocationOptions? = null,
) {
    init {
        require(!maxAge.isNegative()) { "maxAge must not be negative: $maxAge" }
        require(timeout.isPositive()) { "timeout must be positive (Duration.INFINITE waits until cancelled): $timeout" }
    }
}

/**
 * Options of one [LocationProvider.observeLocation] stream.
 *
 * Throws [IllegalArgumentException] for a negative or non-finite [minUpdateDistanceMeters], a
 * negative or infinite [minUpdateInterval], or a negative [maxSeedAge].
 *
 * @property accuracy Precision against power.
 * @property minUpdateDistanceMeters Smallest movement between two emitted fixes in meters, measured
 * from the last emitted fix; `0.0` means every update. Never delays or drops an
 * [LocationReading.Unavailable].
 * @property minUpdateInterval Shortest time between two emitted fixes, honoured on every platform
 * (iOS throttled in common code). Never delays or drops an [LocationReading.Unavailable].
 * @property maxSeedAge Oldest cached fix the stream may emit first, judged on the platform's
 * monotonic clock; [Duration.ZERO] to [Duration.INFINITE]. With none that young the first value is
 * `Unavailable(NO_FIX)`.
 * @property platform Options only one platform understands (`AndroidLocationUpdatesOptions`,
 * `IosLocationUpdatesOptions`), built in platform code and passed through shared code.
 */
public data class LocationUpdatesOptions(
    public val accuracy: LocationAccuracy = LocationAccuracy.BALANCED,
    public val minUpdateDistanceMeters: Double = 0.0,
    public val minUpdateInterval: Duration = Duration.ZERO,
    public val maxSeedAge: Duration = 30.seconds,
    public val platform: PlatformLocationUpdatesOptions? = null,
) {
    init {
        require(minUpdateDistanceMeters.isFinite() && minUpdateDistanceMeters >= 0.0) {
            "minUpdateDistanceMeters must be finite and not negative: $minUpdateDistanceMeters"
        }
        require(minUpdateInterval.isFinite() && !minUpdateInterval.isNegative()) {
            "minUpdateInterval must be finite and not negative: $minUpdateInterval"
        }
        require(!maxSeedAge.isNegative()) { "maxSeedAge must not be negative: $maxSeedAge" }
    }
}

/**
 * Platform-only options of one [LocationProvider.getCurrentLocation] call. Implemented only by this
 * library, so a stream-only type cannot be passed here: the interface carries
 * `@SubclassOptInRequired(ToolkitInternalApi::class)`.
 */
@SubclassOptInRequired(ToolkitInternalApi::class)
public interface PlatformCurrentLocationOptions

/**
 * Platform-only options of one [LocationProvider.observeLocation] stream. Implemented only by this
 * library: the interface carries `@SubclassOptInRequired(ToolkitInternalApi::class)`.
 */
@SubclassOptInRequired(ToolkitInternalApi::class)
public interface PlatformLocationUpdatesOptions
