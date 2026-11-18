package io.github.jamal_wia.kmptoolkit.location

/**
 * What a location request produced: a fix, or a typed reason there is none.
 *
 * Both calls of [LocationProvider] answer with this type, so one `when` serves both. The set of
 * subtypes is closed; the set of [LocationUnavailability] reasons is not.
 */
public sealed interface LocationReading {

    /**
     * A fix that met the request.
     *
     * @property fix The position and everything the platform reported with it.
     */
    public data class Available(public val fix: LocationFix) : LocationReading

    /**
     * No fix, and why.
     *
     * @property reason What is missing; typed state, never display text.
     */
    public data class Unavailable(public val reason: LocationUnavailability) : LocationReading
}

/**
 * Why there is no fix. Typed state, never display text. This set can grow, so give a `when` over it
 * an `else` branch.
 */
public enum class LocationUnavailability {

    /**
     * No location permission or authorization (iOS: also not yet determined). A stream completes
     * after it.
     */
    PERMISSION_MISSING,

    /**
     * The device-wide location service, or the Android source the request named, is off. Answered
     * at once, never after a timeout. A stream stays open and reseeds when the service returns.
     */
    SERVICE_DISABLED,

    /**
     * Everything is on but there is no current fix: still searching (stream) or none within the
     * timeout (one-shot). A stream stays open.
     */
    NO_FIX,

    /**
     * The device, the requested platform source, or the platform (desktop JVM) has no usable
     * location source. A stream completes after it.
     */
    UNSUPPORTED,
}

/** The fix of a [LocationReading.Available], or `null` for a [LocationReading.Unavailable]. */
public fun LocationReading.fixOrNull(): LocationFix? = (this as? LocationReading.Available)?.fix
