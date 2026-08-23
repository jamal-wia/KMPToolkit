package io.github.jamal_wia.kmptoolkit.proximity

import kotlinx.coroutines.flow.Flow

/**
 * The proximity sensor: whether something is right up against the screen.
 *
 * Reports a plain boolean rather than a distance because that is all the hardware really answers —
 * most units are binary, replying either "zero" or their own maximum. The centimetre threshold that
 * folds a distance into the boolean lives in [ProximityRule].
 *
 * Obtain one from the platform factory (`createProximitySensor(context)` on Android,
 * `createProximitySensor()` on iOS) and pass it into shared code as this interface — shared code
 * never names the factory. See `docs/01-architecture.md`.
 *
 * Two honesty caveats every consumer must know:
 *  - **`true` is decisive** — only something physically near the screen produces it, and it keeps
 *    holding while the device vibrates.
 *  - **`false` proves nothing.** The sensor is optical and built for the phone-to-ear case: dark or
 *    matte surfaces reflect almost no infrared, and some devices service it only during calls. A
 *    real phone flat on a table has been seen reporting "far". Treat a negative reading as
 *    "no reading".
 */
public interface ProximitySensor {

    /**
     * Whether the device has a usable proximity sensor. A sensor reporting a zero maximum range can
     * never answer "near" and counts as absent.
     */
    public val isAvailable: Boolean

    /**
     * Emits whether something is near the screen — only on change, which may be minutes apart.
     *
     * Cold: the sensor is registered when collection starts and released when it ends. On a device
     * where [isAvailable] is false it never emits.
     */
    public fun observe(): Flow<Boolean>
}
