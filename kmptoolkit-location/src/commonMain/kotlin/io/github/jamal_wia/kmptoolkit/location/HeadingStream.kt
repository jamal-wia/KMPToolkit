@file:OptIn(ExperimentalAtomicApi::class, ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/*
 * The part of every platform HeadingProvider that is not platform-specific: how one collection owns its
 * state, how a platform callback hands data over without ever blocking, how the minimum-change filter of
 * decision D25 is applied, and that whatever the platform registered is released when the collection ends.
 * Written once so Android and iOS cannot diverge on those rules, and so they are tested on the JVM.
 */

/**
 * [degrees] reduced into `[0, 360)`: never 360.0 (a tiny negative angle rounds up to exactly a turn on
 * the way) and never `-0.0`. A non-finite input comes out non-finite; callers check finiteness first.
 */
internal fun normalizeDegrees(degrees: Double): Double {
    val reduced: Double = degrees % 360.0
    val positive: Double = if (reduced < 0.0) reduced + 360.0 else reduced
    return if (positive >= 360.0) 0.0 else positive + 0.0
}

/**
 * Emits one event of a failure path, and absorbs whatever the app's [Logger] throws. A logger is app code and
 * promises nothing about throwing (`Logger.log`): on a failure path, or inside a platform callback, its failure
 * must never replace what the path was doing (releasing a sensor, a manager) nor reach the platform's thread.
 * Only `Exception` is absorbed; an `Error` (out of memory, a linkage error) still propagates.
 */
internal fun Logger.logSafely(level: LogLevel, throwable: Throwable? = null, message: () -> String) {
    try {
        log(level, throwable, message)
    } catch (ignored: Exception) {
        // Nowhere left to report a logger that fails to log; the event is lost, the caller carries on.
    }
}

/**
 * The minimum-change filter of [HeadingProvider.observeHeading] (decision D25), one instance per
 * collection and confined to the coroutine that collects (it has no lock, and must not be shared).
 *
 * The first reading passes. Afterwards a reading passes when the magnetic heading has moved at least
 * `minChangeDegrees` from the last heading that PASSED (not from the previous sample, so slow turns in
 * small steps still add up) measured with [angleDifference] (so 359.5 to 0.5 is a change of 1), or when
 * [Heading.needsCalibration] changed, or when [Heading.trueDegrees] became non-null or null. A reading
 * with a non-finite magnetic heading never passes.
 */
internal class HeadingFilter(private val minChangeDegrees: Double) {

    init {
        checkMinChangeDegrees(minChangeDegrees)
    }

    private var lastPassed: Heading? = null

    /** [candidate] when it passes, else `null`; a pass becomes the new reference. */
    fun accept(candidate: Heading): Heading? {
        if (!candidate.magneticDegrees.isFinite()) return null
        val last: Heading? = lastPassed
        val passes: Boolean = last == null ||
            candidate.needsCalibration != last.needsCalibration ||
            (candidate.trueDegrees == null) != (last.trueDegrees == null) ||
            abs(angleDifference(last.magneticDegrees, candidate.magneticDegrees)) >= minChangeDegrees
        if (!passes) return null
        lastPassed = candidate
        return candidate
    }
}

/**
 * The newest state of one collection, written by platform callbacks and read by the collector.
 *
 * A callback never blocks and never waits for the collector: [update] swaps in a new immutable state with
 * a compare-and-set (the lock-free idiom of `StateMachineLock`'s queue) and posts one conflated wake-up.
 * The collector reads [current] only after a wake-up, so it always works on the newest state, and a slow
 * collector skips intermediate states instead of queueing them: memory stays bounded however fast the
 * sensor reports. Every state carries the whole truth (a calibration flag, a declination), so skipping an
 * intermediate one loses no information the newest one lacks.
 */
internal class HeadingInputs<S : Any>(initial: S, private val wake: () -> Unit) {

    private val latest: AtomicReference<S> = AtomicReference(initial)

    /** The newest state. */
    fun current(): S = latest.load()

    /**
     * Replaces the state with `transform(state)`. [transform] must be pure: it runs again when another
     * thread won the race. Returning the very same instance means "nothing changed" and wakes nobody.
     * Safe from any thread, including the platform's callback thread.
     */
    fun update(transform: (S) -> S) {
        while (true) {
            val before: S = latest.load()
            val after: S = transform(before)
            if (after === before) return
            if (latest.compareAndSet(before, after)) break
        }
        wake()
    }
}

/**
 * The stream of [HeadingProvider.observeHeading] for one platform: cold, one independent collection state
 * per collector (its own [HeadingInputs], its own [HeadingFilter], its own registration).
 *
 * On each collection, in the collecting coroutine and in this order: [initial] builds the first state
 * (before anything is registered, so nothing can race it); [register] makes the platform registration and
 * returns what releases it; then every wake-up composes the newest state into a [Heading] ([compose]
 * answers `null` while the state is not enough for one), the filter decides, and a passing heading is
 * emitted. Cancellation, a failing collector and a failing [compose] all end in the same `finally`, which
 * releases the registration exactly once; nothing is held between collections.
 *
 * [register] contracts: it releases whatever it registered itself before it throws (a thrown call has no
 * handle to release); it returns `null` when nothing is registered (no hardware, a refused sensor), and
 * then the stream stays open and silent; the handle's `close()` must not throw and may be called on any
 * thread. Callbacks it installs call [HeadingInputs.update] and must not let an exception escape into the
 * platform.
 *
 * @throws IllegalArgumentException at the call, not on collection, when [minChangeDegrees] is negative
 * or non-finite.
 */
internal fun <S : Any> headingStream(
    minChangeDegrees: Double,
    initial: () -> S,
    compose: (S) -> Heading?,
    register: (HeadingInputs<S>) -> AutoCloseable?,
): Flow<Heading> {
    checkMinChangeDegrees(minChangeDegrees)
    return flow {
        val wake: Channel<Unit> = Channel(Channel.CONFLATED)
        val inputs: HeadingInputs<S> = HeadingInputs(initial()) { wake.trySend(Unit) }
        val filter = HeadingFilter(minChangeDegrees)
        var registration: AutoCloseable? = null
        try {
            registration = register(inputs)
            for (ignored: Unit in wake) {
                // A fast callback can refill the wake-up as quickly as it is drained, and a receive that
                // finds a value does not suspend: this is what lets a cancelled collection stop anyway.
                currentCoroutineContext().ensureActive()
                val composed: Heading = compose(inputs.current()) ?: continue
                val passed: Heading = filter.accept(composed) ?: continue
                emit(passed)
            }
        } finally {
            registration?.close()
            wake.cancel()
        }
    }
}
