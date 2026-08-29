package io.github.jamal_wia.kmptoolkit.uploader

/**
 * The outcome of one [UploaderHandler.execute] attempt, and the only way a handler talks back to the
 * engine.
 *
 * How each one maps onto the queue:
 *
 * | Result | Row | Attempt counter |
 * |---|---|---|
 * | [Success] | deleted | — |
 * | [Drop] | deleted | — |
 * | [Retry] | back to `PENDING` under the handler's [RetryPolicy] | +1 |
 * | [Park] | `PARKED`, kept and visible | unchanged |
 * | [Detached] | `IN_FLIGHT` under a lease | unchanged |
 */
public sealed interface AttemptResult {

    /** The effect was delivered and confirmed. The debt is settled and the row is deleted. */
    public data object Success : AttemptResult

    /**
     * Delivery failed for a reason that can heal — no network, a 5xx, a socket that is down.
     *
     * The engine bumps the persisted attempt counter, schedules the next try through the handler's
     * [RetryPolicy], and parks or drops the row if the policy's [GiveUpPolicy] fires.
     *
     * @property cause what went wrong, used for the item's `lastError` and for logging. `null` if
     *   there is nothing useful to attach.
     */
    public data class Retry(val cause: Throwable? = null) : AttemptResult

    /**
     * The effect is obsolete and must **not** be delivered — the state it guarded has moved on
     * (the message was deleted, the entity no longer exists).
     *
     * The row is deleted exactly as on [Success]. Dropping is a deliberate decision by the
     * handler, never a silent loss, which is why it carries a reason.
     *
     * @property reason why the effect is obsolete; logged, not shown to a user.
     */
    public data class Drop(val reason: String) : AttemptResult

    /**
     * Delivery cannot proceed, retrying will not help, and deleting would lose data — a permanent
     * 4xx that needs a human to look at it.
     *
     * The row stays in the queue as [UploaderItemState.PARKED], visible through [Uploader.observe] so
     * the app can surface it, and revivable by a fresh enqueue on the same unique key.
     *
     * @property reason why it cannot proceed; stored on the item as its last error.
     */
    public data class Park(val reason: String) : AttemptResult

    /**
     * **Advanced.** The handler handed delivery to an external executor with its own durability —
     * a WorkManager job doing a multi-hour upload, a background `URLSession` task — and that
     * executor will report the outcome later.
     *
     * The engine moves the row to [UploaderItemState.IN_FLIGHT] with a persisted lease of
     * [leaseMillis]. The executor must eventually call [UploaderEngine.settle] with the item id it
     * received in [AttemptContext.id].
     *
     * The attempt counter is **not** bumped: a successful hand-off is not a failure, and only
     * [SettleResult.Failed] spends retry budget.
     *
     * An expired lease returns the item to attempt-eligibility and `execute` runs again with
     * [AttemptContext.wasDetached] set — so **the hand-off must be idempotent**. Re-launching
     * delivery for the same item id has to rejoin or probe the possibly still-running executor,
     * never start a duplicate.
     *
     * @property leaseMillis how long the claim holds. Size it to the executor's worst-case
     *   completion time: too short risks a redundant hand-off racing a live executor (survivable
     *   but wasteful), too long delays recovery after the executor dies silently. Must be positive
     *   — a non-positive lease would expire the instant it is written, and the item would
     *   re-execute and re-detach on every drain iteration.
     * @throws IllegalArgumentException if [leaseMillis] is not positive.
     */
    public data class Detached(val leaseMillis: Long) : AttemptResult {
        init {
            require(leaseMillis > 0) { "leaseMillis must be > 0, was $leaseMillis." }
        }
    }
}

/**
 * The final outcome of a detached delivery, reported by the external executor through
 * [UploaderEngine.settle] — the counterpart of [AttemptResult] for the half of the work that happens
 * outside [UploaderHandler.execute].
 *
 * There is deliberately no detached/retry split here. From an executor's point of view every
 * non-terminal failure is just [Failed]; the engine routes it through the handler's [RetryPolicy]
 * and the next drain re-runs `execute`, which hands off again. An executor never owns retry policy.
 */
public sealed interface SettleResult {

    /** Delivery confirmed. The debt is settled and the row is deleted. */
    public data object Delivered : SettleResult

    /**
     * Delivery failed in a way that can heal.
     *
     * Spends one attempt of the handler's retry budget: the row returns to
     * [UploaderItemState.PENDING] with its lease cleared, under the handler's backoff, and parks or
     * drops if the give-up policy fires.
     *
     * Ignored — with a log line — if the row is no longer [UploaderItemState.IN_FLIGHT], or if its
     * lease has changed since the executor read it. See [UploaderEngine.settle].
     *
     * @property cause what went wrong, or `null`.
     */
    public data class Failed(val cause: Throwable? = null) : SettleResult

    /**
     * The effect is obsolete and must not be re-delivered. The row is deleted, exactly as with
     * [AttemptResult.Drop].
     *
     * @property reason why it is obsolete.
     */
    public data class Drop(val reason: String) : SettleResult

    /**
     * Delivery cannot proceed and retrying will not help, but deleting would lose data. The row is
     * parked, exactly as with [AttemptResult.Park].
     *
     * @property reason why it cannot proceed.
     */
    public data class Park(val reason: String) : SettleResult
}
