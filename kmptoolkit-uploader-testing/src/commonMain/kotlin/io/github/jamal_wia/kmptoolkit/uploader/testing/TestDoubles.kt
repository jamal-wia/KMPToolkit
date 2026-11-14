package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.UploaderClock
import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.uploader.spi.WakeScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
 *
 * Safe to use from any thread: the engine calls it from the caller's thread (in `enqueue`) and from its drain at
 * once, and no call or count is lost.
 */
public class RecordingWakeScheduler : WakeScheduler {

    private data class Calls(val scheduleCount: Int = 0, val cancelCount: Int = 0, val isArmed: Boolean = false)

    private val calls: MutableStateFlow<Calls> = MutableStateFlow(Calls())

    // The properties stay `var ... private set` so the published ABI is unchanged; the setters write through the
    // same atomic state as everything else.

    /** How many times [scheduleWake] was called. Expect one per enqueue, plus one per gated pass. */
    public var scheduleCount: Int
        get() = calls.value.scheduleCount
        private set(value) {
            calls.update { it.copy(scheduleCount = value) }
        }

    /** How many times [cancelWake] was called. */
    public var cancelCount: Int
        get() = calls.value.cancelCount
        private set(value) {
            calls.update { it.copy(cancelCount = value) }
        }

    /** Whether the most recent call was a schedule — i.e. whether a wake would be pending now. */
    public var isArmed: Boolean
        get() = calls.value.isArmed
        private set(value) {
            calls.update { it.copy(isArmed = value) }
        }

    override fun scheduleWake() {
        calls.update { it.copy(scheduleCount = it.scheduleCount + 1, isArmed = true) }
    }

    override fun cancelWake() {
        calls.update { it.copy(cancelCount = it.cancelCount + 1, isArmed = false) }
    }

    /** Resets every counter and the armed flag. */
    public fun reset() {
        calls.value = Calls()
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
 * Safe to use from any thread: the engine reads the clock from the caller's thread (in `enqueue`) and from its
 * drain at once, and [advanceBy] never loses an update.
 *
 * @param initial the starting epoch-millis reading.
 */
public class MutableUploaderClock(initial: Long = 0L) : UploaderClock {

    private val state: MutableStateFlow<Long> = MutableStateFlow(initial)

    /** The current reading. Assign to it to jump anywhere, including backwards. */
    public var nowMillis: Long
        get() = state.value
        set(value) {
            state.value = value
        }

    override fun nowEpochMillis(): Long = state.value

    /**
     * Moves the clock forward.
     *
     * The reading stops at the `Long` range instead of wrapping around, so a huge advance never lands in the past.
     * A clock held at `Long.MAX_VALUE` makes every backoff and lease read as due; each item still gets at most one
     * turn per `drain()`.
     *
     * @param millis how far to advance; may be negative to simulate a wall-clock jump backwards.
     */
    public fun advanceBy(millis: Long) {
        state.update { current: Long ->
            val sum: Long = current + millis
            // Overflow happened exactly when both operands share a sign the sum does not.
            if (((current xor sum) and (millis xor sum)) < 0) (if (current < 0) Long.MIN_VALUE else Long.MAX_VALUE) else sum
        }
    }
}
