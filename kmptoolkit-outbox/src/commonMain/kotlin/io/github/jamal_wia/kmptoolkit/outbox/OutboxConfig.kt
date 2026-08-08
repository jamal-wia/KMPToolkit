package io.github.jamal_wia.kmptoolkit.outbox

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The engine's timing knobs. Every one has a working default; override them when your effects are
 * unusually long-lived or unusually urgent.
 *
 * None of these is a correctness parameter — the queue delivers the same items in the same order
 * whatever you set. They trade responsiveness against wake-ups and database reads.
 *
 * @param heartbeatInterval how often the engine pokes itself as a safety net, in case a wake-up
 *   signal was lost — a constraint that never transitions, an alarm killed by process churn. It is
 *   a backstop, not the normal path: enqueues, constraint transitions and backoff alarms all
 *   trigger a drain directly. Must be positive.
 * @param minAlarmDelay the floor applied to the one-shot alarm that re-triggers the drain at the
 *   next backoff gate. Without it, a gate that has just passed would re-trigger the drain
 *   immediately and spin. Must be positive.
 * @param drainPollInterval how often [OutboxEngine.awaitDrained] re-checks whether the queue has
 *   emptied. Coarse is fine — its callers are background wake jobs with budgets measured in
 *   minutes. Must be positive.
 * @param clockAnomalyFactor how far past a retry policy's own [RetryPolicy.maxDelayMillis] a
 *   persisted backoff gate may legitimately sit before the engine concludes the device's wall
 *   clock jumped backwards and runs the item anyway. `2` leaves generous room for jitter and for a
 *   policy that understates its ceiling. Must be at least `1`. Raising it makes the engine more
 *   tolerant of odd gates and slower to recover from a clock jump.
 * @throws IllegalArgumentException if any bound above is violated. Failing at construction beats
 *   discovering months later that a queue was quietly spinning.
 */
public data class OutboxConfig(
    val heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL,
    val minAlarmDelay: Duration = DEFAULT_MIN_ALARM_DELAY,
    val drainPollInterval: Duration = DEFAULT_DRAIN_POLL_INTERVAL,
    val clockAnomalyFactor: Int = DEFAULT_CLOCK_ANOMALY_FACTOR,
) {
    init {
        require(heartbeatInterval.isPositive()) {
            "heartbeatInterval must be positive, was $heartbeatInterval."
        }
        require(minAlarmDelay.isPositive()) { "minAlarmDelay must be positive, was $minAlarmDelay." }
        require(drainPollInterval.isPositive()) {
            "drainPollInterval must be positive, was $drainPollInterval."
        }
        require(clockAnomalyFactor >= 1) {
            "clockAnomalyFactor must be >= 1, was $clockAnomalyFactor."
        }
    }

    /** The defaults, so you can override one and keep the rest. */
    public companion object {

        /** Default [heartbeatInterval]: 30 seconds. */
        public val DEFAULT_HEARTBEAT_INTERVAL: Duration = 30.seconds

        /** Default [minAlarmDelay]: 50 milliseconds. */
        public val DEFAULT_MIN_ALARM_DELAY: Duration = 50.milliseconds

        /** Default [drainPollInterval]: 500 milliseconds. */
        public val DEFAULT_DRAIN_POLL_INTERVAL: Duration = 500.milliseconds

        /** Default [clockAnomalyFactor]: `2`. */
        public const val DEFAULT_CLOCK_ANOMALY_FACTOR: Int = 2
    }
}
