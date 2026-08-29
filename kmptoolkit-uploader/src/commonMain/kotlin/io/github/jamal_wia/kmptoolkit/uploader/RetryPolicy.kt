package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.math.min
import kotlin.random.Random

/**
 * How long to wait before re-attempting a failed effect, and when to stop attempting it at all.
 *
 * A policy is a plain function of the persisted attempt count, which is what makes a retry budget
 * survive process death: nothing about the pacing lives in memory. It is also what makes the whole
 * thing testable under virtual time — the engine never consults a wall clock to decide *when*, it
 * writes a gate and skips the item until that gate passes.
 *
 * [ExponentialBackoffRetryPolicy] covers the usual case. Implement this interface yourself for a
 * fixed delay, a schedule read from a remote config, or a curve derived from the failure.
 *
 * ## Contract
 *
 * - **[backoffMillis] is pure and cheap.** No I/O, no suspension. It may be random (jitter is
 *   normal) but must not depend on the current time.
 * - **It must never return a value above [maxDelayMillis]**, and must never return a negative one.
 *   A zero return is legal but means "retry immediately", which for a permanently failing item
 *   turns the drain into a hot loop — prefer a positive floor.
 * - **[maxDelayMillis] is a real upper bound.** The engine uses it to recognize a corrupt backoff
 *   gate: a gate further in the future than this bound can reach was written before the device's
 *   wall clock jumped backwards, and honoring it would freeze the item — and everything behind it
 *   in its ordering channel — for the whole duration of the jump. Understate it and legitimate
 *   gates get ignored; overstate it and a clock jump takes longer to recover from.
 */
public interface RetryPolicy {

    /**
     * The largest value [backoffMillis] can ever return, jitter included. Must be positive.
     *
     * See the class contract for how the engine uses it.
     */
    public val maxDelayMillis: Long

    /** What to do once retrying has clearly stopped helping. */
    public val giveUp: GiveUpPolicy

    /**
     * How long to wait before the next attempt.
     *
     * @param attempts the number of attempts that have failed, including the one that just did —
     *   so the first call is `1`.
     * @return the delay in milliseconds; never negative, never above [maxDelayMillis].
     */
    public fun backoffMillis(attempts: Int): Long
}

/**
 * The usual policy: exponential backoff with jitter, saturating at a ceiling.
 *
 * The delay after `n` failed attempts is `min(maxDelayMillis, baseDelayMillis * 2^(n-1))`, then
 * multiplied by a random factor in `1 ± jitterRatio`. Jitter matters more than it looks: without
 * it, every queued item of an app that lost connectivity retries in lockstep and hits the server
 * as one spike the moment it returns.
 *
 * @param baseDelayMillis the delay before the first retry; doubles with each further attempt. Must
 *   be positive — a zero base puts the next gate at "now", and a permanently failing item then
 *   re-executes on every iteration of the same drain pass.
 * @param maxDelayMillis the ceiling the exponential curve saturates at, jitter excluded. Must be at
 *   least [baseDelayMillis].
 * @param jitterRatio the fraction of random spread applied to the delay, in `0.0..1.0`. `0.0`
 *   disables jitter and makes the policy fully deterministic, which is occasionally what a test
 *   wants.
 * @param giveUp what happens once retries keep failing; [GiveUpPolicy.Never] by default.
 * @param random the source of jitter. Inject a seeded [Random] to make a test deterministic
 *   without setting [jitterRatio] to zero.
 * @throws IllegalArgumentException if any bound above is violated.
 */
public class ExponentialBackoffRetryPolicy(
    private val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    override val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    private val jitterRatio: Double = DEFAULT_JITTER_RATIO,
    override val giveUp: GiveUpPolicy = GiveUpPolicy.Never,
    private val random: Random = Random.Default,
) : RetryPolicy {

    init {
        require(baseDelayMillis > 0) { "baseDelayMillis must be > 0, was $baseDelayMillis." }
        require(maxDelayMillis >= baseDelayMillis) {
            "maxDelayMillis ($maxDelayMillis) must be >= baseDelayMillis ($baseDelayMillis)."
        }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be within 0.0..1.0, was $jitterRatio." }
    }

    /**
     * `min(max, base * 2^(attempts-1))`, spread by ±`jitterRatio` and clamped to
     * `0..`[maxDelayMillis].
     *
     * @param attempts failed attempts so far, `1` after the first failure. Values below `1` are
     *   treated as `1`.
     */
    override fun backoffMillis(attempts: Int): Long {
        val exponent: Int = (attempts - 1).coerceIn(0, MAX_EXPONENT)
        val raw: Long = min(maxDelayMillis, baseDelayMillis shl exponent)
        val spread: Double = 1.0 + jitterRatio * (random.nextDouble() * 2 - 1)
        return (raw * spread).toLong().coerceIn(0L, maxDelayMillis)
    }

    /** Defaults, exposed so a consumer can override one of them and keep the rest. */
    public companion object {

        /** Default [baseDelayMillis]: one second. */
        public const val DEFAULT_BASE_DELAY_MILLIS: Long = 1_000L

        /** Default [maxDelayMillis]: five minutes. */
        public const val DEFAULT_MAX_DELAY_MILLIS: Long = 5 * 60_000L

        /** Default [jitterRatio]: ±20%. */
        public const val DEFAULT_JITTER_RATIO: Double = 0.2

        /** Caps `1 shl n` well below `Long` overflow; `2^20 × base` already exceeds any real cap. */
        private const val MAX_EXPONENT: Int = 20
    }
}

/**
 * What the engine does when retries keep failing.
 *
 * The default is [Never], and that default is deliberate: retrying a broken effect forever beats
 * losing it silently, and a generic attempt count cannot tell "ten real server rejections" apart
 * from "ten retries while the device was in a tunnel". A handler that *can* tell them apart should
 * return [AttemptResult.Park] or [AttemptResult.Drop] from `execute` instead of relying on this.
 */
public sealed interface GiveUpPolicy {

    /**
     * Keep retrying indefinitely; the backoff ceiling caps the pressure.
     *
     * This removes only the give-up parking. A handler returning [AttemptResult.Park], or a poison
     * payload (undecodable, newer schema, no registered handler), still parks the item.
     */
    public data object Never : GiveUpPolicy

    /**
     * After [maxAttempts] failures move the row to [UploaderItemState.PARKED] — kept, visible, no
     * longer retried.
     *
     * @property maxAttempts number of failed attempts that triggers parking. Must be positive.
     * @throws IllegalArgumentException if [maxAttempts] is not positive.
     */
    public data class ParkAfterAttempts(val maxAttempts: Int) : GiveUpPolicy {
        init {
            require(maxAttempts > 0) { "maxAttempts must be > 0, was $maxAttempts." }
        }
    }

    /**
     * After [maxAttempts] failures delete the row.
     *
     * Only for genuinely optional effects — a telemetry ping, a read receipt. Anything the user
     * would notice missing belongs in [ParkAfterAttempts].
     *
     * @property maxAttempts number of failed attempts that triggers deletion. Must be positive.
     * @throws IllegalArgumentException if [maxAttempts] is not positive.
     */
    public data class DropAfterAttempts(val maxAttempts: Int) : GiveUpPolicy {
        init {
            require(maxAttempts > 0) { "maxAttempts must be > 0, was $maxAttempts." }
        }
    }
}
