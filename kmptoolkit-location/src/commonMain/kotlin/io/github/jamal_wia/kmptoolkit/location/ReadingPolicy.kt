package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.Duration

/**
 * A cached fix together with its age at the moment of the decision. The age is computed by the
 * platform provider on its own monotonic clock (Android `elapsedRealtimeNanos`, iOS the
 * delivery-time formula of PLAN section 4.2), never from [LocationFix.timestampEpochMillis]: a wall
 * clock jumps when it is corrected.
 */
internal class AgedFix(val fix: LocationFix, val age: Duration)

/**
 * The **cache** decision (PLAN D11, section 4.2 "Single fix" and "Stream seed"): whether a fix the
 * platform already held when the request began, [age] old, may answer for [maxAge].
 * [Duration.ZERO] forces a fresh fix, so it accepts nothing; [Duration.INFINITE] accepts any age;
 * otherwise an age equal to [maxAge] is still acceptable. A negative age (a clock that stepped back)
 * counts as zero.
 *
 * Use it only for fixes that existed before the call or the stream started. A fix that the platform
 * *delivers* after the request began is judged by [isFreshDelivery]: every delivered fix has an age
 * above zero, so this function would reject all of them under `maxAge = ZERO`.
 */
internal fun acceptsCachedFix(age: Duration, maxAge: Duration): Boolean =
    maxAge.isPositive() && age.coerceAtLeast(Duration.ZERO) <= maxAge

/**
 * The **delivery** decision (PLAN section 4.2: the age check of "a framework answer", "a delivered
 * fix" and of the first deliveries of a stream): whether a fix delivered to the request, [age] old,
 * is fresh enough. [sinceRequestStart] is the time elapsed since the call or the stream started, on
 * the same monotonic clock as [age]. A fix that is no older than the request itself was taken after
 * it began, so it is always fresh: [Duration.ZERO] then means "taken after the request", not "never".
 * Beyond that a fix is fresh while it is within [maxAge]; [Duration.INFINITE] accepts any age. A
 * negative age or a negative elapsed time counts as zero.
 */
internal fun isFreshDelivery(age: Duration, maxAge: Duration, sinceRequestStart: Duration): Boolean =
    age.coerceAtLeast(Duration.ZERO) <= maxOf(maxAge, sinceRequestStart.coerceAtLeast(Duration.ZERO))

/** The youngest of [candidates] that [acceptsCachedFix], or `null` when none is. Ties keep the first. */
internal fun freshestWithin(candidates: Iterable<AgedFix>, maxAge: Duration): LocationFix? {
    var best: AgedFix? = null
    for (candidate: AgedFix in candidates) {
        if (!acceptsCachedFix(candidate.age, maxAge)) continue
        val current: AgedFix? = best
        if (current == null || candidate.age < current.age) best = candidate
    }
    return best?.fix
}

/**
 * The first value of a stream (PLAN section 4.2, "Stream seed"): the freshest cached fix within
 * [maxSeedAge], else `Unavailable(`[noSeedReason]`)` (`NO_FIX` when everything is on, otherwise the
 * reason the platform reports now).
 */
internal fun seedReading(
    candidates: Iterable<AgedFix>,
    maxSeedAge: Duration,
    noSeedReason: LocationUnavailability,
): LocationReading {
    val fix: LocationFix? = freshestWithin(candidates, maxSeedAge)
    return if (fix != null) LocationReading.Available(fix) else LocationReading.Unavailable(noSeedReason)
}

/**
 * Whether the stream ends after this reading: [LocationUnavailability.PERMISSION_MISSING] and
 * [LocationUnavailability.UNSUPPORTED] are terminal, [LocationUnavailability.SERVICE_DISABLED] and
 * [LocationUnavailability.NO_FIX] are not (PLAN D8), identically on every platform.
 */
internal val LocationReading.endsStream: Boolean
    get() = this is LocationReading.Unavailable &&
        (reason == LocationUnavailability.PERMISSION_MISSING || reason == LocationUnavailability.UNSUPPORTED)

/**
 * Emits readings up to and including the first terminal one ([endsStream]) and then completes,
 * cancelling the upstream, so the platform registration behind it is released at once. Cold:
 * collecting again starts a new run.
 */
internal fun Flow<LocationReading>.completingAfterTerminalReading(): Flow<LocationReading> =
    transformWhile { reading: LocationReading ->
        emit(reading)
        !reading.endsStream
    }
