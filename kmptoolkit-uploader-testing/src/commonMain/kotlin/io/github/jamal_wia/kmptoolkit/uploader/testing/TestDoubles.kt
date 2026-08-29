package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.UploaderClock
import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.uploader.spi.WakeScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A [ConstraintProvider] you flip by hand, for testing that effects wait for a precondition and
 * fire the moment it is met.
 *
 * ```kotlin
 * val network = MutableConstraintProvider("network", satisfied = false)
 * // ... nothing is attempted ...
 * network.satisfy()   // the engine drains on the false -> true transition
 * ```
 *
 * @param key the key handlers reference in `constraintKeys`.
 * @param satisfied the initial value.
 */
public class MutableConstraintProvider(
    override val key: String,
    satisfied: Boolean = true,
) : ConstraintProvider {

    private val state: MutableStateFlow<Boolean> = MutableStateFlow(satisfied)

    override val satisfied: StateFlow<Boolean> = state

    /** Sets the constraint. A `false → true` transition is what triggers a drain. */
    public fun set(value: Boolean) {
        state.value = value
    }

    /** Shorthand for `set(true)`. */
    public fun satisfy(): Unit = set(true)

    /** Shorthand for `set(false)`. */
    public fun block(): Unit = set(false)
}

/**
 * A [WakeScheduler] that counts calls instead of touching a platform scheduler.
 *
 * The engine arms a wake whenever work is owed and disarms it when the queue empties, so
 * [isArmed] is a compact way to assert "the app would be woken to finish this".
 */
public class RecordingWakeScheduler : WakeScheduler {

    /** How many times [scheduleWake] was called. Expect one per enqueue, plus one per gated pass. */
    public var scheduleCount: Int = 0
        private set

    /** How many times [cancelWake] was called. */
    public var cancelCount: Int = 0
        private set

    /** Whether the most recent call was a schedule — i.e. whether a wake would be pending now. */
    public var isArmed: Boolean = false
        private set

    override fun scheduleWake() {
        scheduleCount++
        isArmed = true
    }

    override fun cancelWake() {
        cancelCount++
        isArmed = false
    }

    /** Resets every counter and the armed flag. */
    public fun reset() {
        scheduleCount = 0
        cancelCount = 0
        isArmed = false
    }
}

/**
 * An [UploaderClock] you move by hand, so backoff gates and delivery leases can be crossed without
 * waiting for real time.
 *
 * Pair it with `runTest`'s virtual time: `advanceTimeBy` moves the coroutine clock that `delay`
 * observes, while this moves the wall clock the engine writes into gates and leases. They are two
 * different clocks and a test of lease expiry generally needs both.
 *
 * @param initial the starting epoch-millis reading.
 */
public class MutableUploaderClock(initial: Long = 0L) : UploaderClock {

    /** The current reading. Assign to it to jump anywhere, including backwards. */
    public var nowMillis: Long = initial

    override fun nowEpochMillis(): Long = nowMillis

    /**
     * Moves the clock forward.
     *
     * @param millis how far to advance; may be negative to simulate a wall-clock jump backwards.
     */
    public fun advanceBy(millis: Long) {
        nowMillis += millis
    }
}
