package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlin.time.Duration

/**
 * Android-only options of one [LocationProvider.getCurrentLocation] call. Build them in Android
 * code and hand them to shared code as [PlatformCurrentLocationOptions] (the slot of
 * [CurrentLocationOptions.platform]).
 *
 * @property source Which `LocationManager` source(s) answer the call; see [AndroidLocationSource].
 */
@OptIn(ToolkitInternalApi::class)
public data class AndroidCurrentLocationOptions(
    public val source: AndroidLocationSource = AndroidLocationSource.AUTO,
) : PlatformCurrentLocationOptions

/**
 * Android-only options of one [LocationProvider.observeLocation] stream. Build them in Android code
 * and hand them to shared code as [PlatformLocationUpdatesOptions] (the slot of
 * [LocationUpdatesOptions.platform]).
 *
 * Throws [IllegalArgumentException] for a negative or infinite [maxUpdateDelay].
 *
 * @property source Which `LocationManager` source(s) the stream registers; see
 * [AndroidLocationSource].
 * @property maxUpdateDelay Lets the platform batch fixes up to this delay to save power. Honoured on
 * API 31 and later (`LocationRequest.Builder.setMaxUpdateDelayMillis`); it has no effect below API 31.
 * [Duration.ZERO] means no batching.
 */
@OptIn(ToolkitInternalApi::class)
public data class AndroidLocationUpdatesOptions(
    public val source: AndroidLocationSource = AndroidLocationSource.AUTO,
    public val maxUpdateDelay: Duration = Duration.ZERO,
) : PlatformLocationUpdatesOptions {
    init {
        require(maxUpdateDelay.isFinite() && !maxUpdateDelay.isNegative()) {
            "maxUpdateDelay must be finite and not negative: $maxUpdateDelay"
        }
    }
}

/**
 * A `LocationManager` source. A source that is absent on the device answers
 * `Unavailable(UNSUPPORTED)`; one that exists but is switched off answers `Unavailable(SERVICE_DISABLED)`
 * at once, never after a timeout.
 */
public enum class AndroidLocationSource {

    /**
     * Library policy: the framework `FUSED_PROVIDER` when present (API 31 and later), else the available
     * providers chosen by [LocationAccuracy] (and raced by a one-shot call). Races only the enabled
     * providers, and reads `SERVICE_DISABLED` only when none is enabled.
     */
    AUTO,

    /** `LocationManager.FUSED_PROVIDER` only (API 31 and later; absent below). */
    FUSED,

    /** `GPS_PROVIDER` only: raw GNSS, works offline, no network positioning. */
    GPS,

    /** `NETWORK_PROVIDER` only: Wi-Fi and cell positioning, usually needs a data connection. */
    NETWORK,

    /** `PASSIVE_PROVIDER`: only fixes other apps already requested; adds no power draw. */
    PASSIVE,
}
