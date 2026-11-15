package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The common throttle of [LocationUpdatesOptions.minUpdateInterval] and
 * [LocationUpdatesOptions.minUpdateDistanceMeters], shared by every platform provider (iOS relies on
 * it alone for the interval; Android also hands both values to the platform request).
 *
 * The contract (PLAN D16, M10):
 * - an [LocationReading.Unavailable] is never delayed and never dropped, whatever the interval or
 *   the distance: a throttled terminal reason would delay completion, and a dropped
 *   `SERVICE_DISABLED` would hide the off state;
 * - the distance is measured from the last **emitted** fix, not from the last one received;
 * - the interval is measured from the last emitted fix on the injected [timeSource];
 * - after an emitted `Unavailable` the next fix is always emitted: a consumer whose latest value is a
 *   reason must not be left there by a fix that arrives inside the interval.
 *
 * Concurrency: the state is confined to one collection. The operator below creates a fresh instance
 * inside `flow { }`, so it is touched only by the collecting coroutine, never by a platform callback
 * (a callback hands readings to the stream through its channel, and this runs downstream of it), and
 * two collectors never share one. That is why it holds no lock; it also holds no registration, so
 * there is nothing to release, and a cancelled collection leaves nothing behind.
 */
private class ReadingThrottle(
    private val minDistanceMeters: Double,
    private val minInterval: Duration,
    private val timeSource: TimeSource,
) {
    private var lastEmittedFix: LocationFix? = null
    private var lastEmittedAt: TimeMark? = null

    /** Whether [reading] passes; records it as the new reference when it does. */
    fun shouldEmit(reading: LocationReading): Boolean = when (reading) {
        is LocationReading.Unavailable -> {
            // Forget the reference: the next fix ends the unavailable state at once.
            lastEmittedFix = null
            lastEmittedAt = null
            true
        }
        is LocationReading.Available -> {
            val previousFix: LocationFix? = lastEmittedFix
            val previousAt: TimeMark? = lastEmittedAt
            val passes: Boolean = previousFix == null || previousAt == null ||
                (previousAt.elapsedNow() >= minInterval &&
                    previousFix.coordinates.distanceInMetersTo(reading.fix.coordinates) >= minDistanceMeters)
            if (passes) {
                lastEmittedFix = reading.fix
                lastEmittedAt = timeSource.markNow()
            }
            passes
        }
    }
}

/**
 * Applies [options]' `minUpdateInterval` and `minUpdateDistanceMeters` to this stream of readings;
 * see [ReadingThrottle] for the rules. Cold: each collection starts with no reference fix, so the
 * first fix of every collection is emitted.
 *
 * @param timeSource the clock the interval is measured on; tests inject a virtual one.
 */
internal fun Flow<LocationReading>.throttledBy(
    options: LocationUpdatesOptions,
    timeSource: TimeSource = TimeSource.Monotonic,
): Flow<LocationReading> {
    val upstream: Flow<LocationReading> = this
    return flow {
        val throttle = ReadingThrottle(options.minUpdateDistanceMeters, options.minUpdateInterval, timeSource)
        upstream.collect { reading: LocationReading ->
            if (throttle.shouldEmit(reading)) emit(reading)
        }
    }
}
