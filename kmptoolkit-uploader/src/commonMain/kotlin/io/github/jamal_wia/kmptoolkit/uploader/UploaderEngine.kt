package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.uploader.spi.WakeScheduler
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope

/**
 * The full engine: [Uploader] plus the lifecycle and settlement operations that belong to whoever
 * owns it.
 *
 * Hand the narrow [Uploader] to features; keep this reference in your application bootstrap, where
 * [start] and [close] have somewhere sensible to live.
 *
 * ## Lifecycle
 *
 * Construct with [createUploaderEngine], call [start] once, and call [close] when the owning scope
 * goes away. Between those, the engine runs one drain coroutine that wakes on: startup (to pick up
 * whatever the previous process left behind), every enqueue, every `false → true` transition of a
 * [ConstraintProvider], every backoff gate coming due, every [trigger], and a heartbeat backstop.
 *
 * ## Guarantees
 *
 * - **At-least-once delivery.** An item is deleted only after its handler confirms success. The
 *   process can die between a successful send and that deletion, so handlers must tolerate a
 *   repeat — see [UploaderHandler].
 * - **Single-flight.** Exactly one drain runs at a time. Triggers are conflated.
 * - **FIFO within an ordering channel**, scoped per handler type. See [UploaderHandler.orderingKey]
 *   for what that does and does not promise across channels.
 * - **Persisted retry budgets.** Attempt counts and backoff gates live on the row, so a backoff
 *   survives a restart instead of resetting.
 * - **Nothing is deleted silently.** Every path that removes an item is either a confirmed
 *   delivery or an explicit decision — [AttemptResult.Drop], [SettleResult.Drop], a
 *   [GiveUpPolicy.DropAfterAttempts] you configured, or your own [cancel] / [cancelByTag].
 *   Everything else parks.
 */
public interface UploaderEngine : Uploader {

    /**
     * Starts the drain coroutine and wires the standing triggers, then immediately triggers one
     * pass to pick up whatever the previous process left in the queue.
     *
     * Call it once, from your application bootstrap, regardless of whether anyone is signed in —
     * the queue may already hold effects from the last session. Repeat calls are no-ops, and a
     * call after [close] is a no-op too.
     *
     * Does not suspend and does not block: the work happens on the [CoroutineScope] given to
     * [createUploaderEngine].
     */
    public fun start()

    /**
     * Runs one full drain pass and suspends until it finishes.
     *
     * A pass sweeps the queue, executes every item that is eligible right now, and repeats until a
     * sweep executes nothing; it then arms an alarm for the soonest gate and returns. It never
     * sleeps waiting for a backoff.
     *
     * **You almost never call this.** It exists for tests that want a deterministic pass, and for
     * diagnostics. Calling it on a started engine breaks the single-flight guarantee — use
     * [trigger] there instead.
     */
    public suspend fun drain()

    /**
     * Reports the final outcome of a detached delivery — the counterpart of
     * [AttemptResult.Detached], called by the external executor that took the hand-off.
     *
     * Addressed by item [id] and by the [claim] the executor was handed. The id makes a report on a
     * superseded, wiped or already-settled item land on nothing; the claim tells this executor's
     * report from one by an earlier executor of the same item whose lease expired and which was
     * replaced by a re-hand-off. Each degrades to a logged no-op rather than to a corrupted queue.
     *
     * A report is **current** when [claim] is the claim the item is held under, or the claim the
     * drain is about to write for it — a fast executor can report before `execute` has returned and
     * the hand-off has been recorded. Any later claim supersedes it. Whether a report must be current
     * depends on what it says:
     * - **[SettleResult.Delivered] and [SettleResult.Drop] stand from any claim.** They are facts
     *   about the effect: it landed, or it is no longer owed. A superseded executor that delivered
     *   has still delivered, and re-handing the item would send it twice. The row is deleted.
     * - **[SettleResult.Failed] and [SettleResult.Park] stand only from the current claim.** They are
     *   verdicts on one attempt, and they steer the next one — spend the retry budget and back off,
     *   or stop trying. A superseded executor's verdict is about an attempt the engine has already
     *   given up on; the current executor reaches its own. A stale failure would otherwise return to
     *   pending an item a live executor is uploading, and a stale park would take it out of rotation
     *   under that executor — a failure the current attempt never had. Ignoring one costs nothing
     *   the current executor's own report does not settle.
     * - A [SettleResult.Failed] or [SettleResult.Park] on a **parked** item is a no-op, as is any
     *   report on an **unknown id**.
     *
     * The check and the write are one compare-and-set in the store, so a re-hand-off landing between
     * this call's read and its write supersedes the report instead of being overwritten by it.
     *
     * Suspends because it reads and writes the store.
     *
     * @param id the item id the executor received in [AttemptContext.id].
     * @param claim the claim the executor received in [AttemptContext.claim].
     * @param result what happened.
     */
    public suspend fun settle(id: String, claim: Long, result: SettleResult)

    /**
     * Triggers a drain and suspends until the queue is empty or [timeout] elapses.
     *
     * This is what a platform wake job calls: the job holds a background execution grant, and its
     * only task is to keep the process alive while the already-started engine works. It must not
     * call [drain] itself — single-flight belongs to the started engine.
     *
     * An [UploaderItemState.IN_FLIGHT] item counts as **not** drained. The effect is still owed, and
     * a wake job timing out on one is exactly the re-check that recovers a lease whose executor
     * died.
     *
     * @param timeout how long to wait. The engine polls at
     *   [UploaderConfig.drainPollInterval].
     * @return `true` if the queue emptied within the timeout.
     */
    public suspend fun awaitDrained(timeout: Duration): Boolean

    /**
     * Stops the engine: cancels the drain coroutine, the heartbeat, the pending backoff alarm and
     * the constraint subscriptions, and removes this engine from [UploaderEngineRegistry] if it is
     * the registered one.
     *
     * Idempotent, non-suspending, and safe to call on an engine that was never started — teardown
     * paths are exactly where there is no coroutine left to launch in.
     *
     * It does **not** cancel the [CoroutineScope] you provided, which is yours, and it does not
     * touch the queue: everything owed is still owed, and a freshly constructed engine picks it up.
     * After [close] the engine is permanently inert — [enqueue] still persists (the store is
     * independent) but nothing will drain it. Construct a new engine rather than reusing a closed
     * one.
     */
    public fun close()
}

/**
 * Creates an uploader engine.
 *
 * Only the first three parameters have no sensible default: the engine needs somewhere to persist
 * ([store]), something to deliver ([handlers]), and somewhere to run ([scope]).
 *
 * ```kotlin
 * val uploader: UploaderEngine = createUploaderEngine(
 *     store = SqlDelightUploaderStore(database),
 *     handlers = listOf(sendMessageHandler, uploadAvatarHandler),
 *     scope = applicationScope,
 *     constraintProviders = listOf(networkConstraint),
 *     wakeScheduler = createWorkManagerWakeScheduler(context),
 * ).also { it.start() }
 * ```
 *
 * @param store where items are persisted. The one thing this module cannot supply for you — see
 *   [UploaderStore] and `docs/kmptoolkit-uploader/07-custom-store.md`.
 * @param handlers one per effect type. Their [UploaderHandler.type]s must be distinct.
 * @param scope the scope the drain coroutine, the heartbeat and the backoff alarms run in.
 *   Cancelling it stops the engine as surely as [UploaderEngine.close] does; the scope's dispatcher
 *   is where handlers execute and where the store is called.
 * @param constraintProviders live preconditions handlers can gate on, matched to
 *   [UploaderHandler.constraintKeys] by [ConstraintProvider.key]. Duplicate keys are rejected.
 * @param transactionRunner brackets each enqueue in a transaction, so a domain write and the effect
 *   it owes can commit atomically. Defaults to [TransactionRunner.Direct], which is correct
 *   whenever enqueueing is the only write.
 * @param wakeScheduler the OS-level wake layer that revives a killed process to finish the queue.
 *   Defaults to [WakeScheduler.NoOp] — delivery then happens while the app runs and at its next
 *   launch.
 * @param config timing knobs; see [UploaderConfig].
 * @param logger where the engine reports what it did. Defaults to [NoopLogger]. Every delivery,
 *   retry, park and drop is logged — pass a real logger and you can reconstruct the life of any
 *   item from the log.
 * @param clock the wall clock backing backoff gates and leases. Substitute it in tests.
 * @param idGenerator produces each item's id. The default is a random UUID; override it if your
 *   server requires a particular idempotency-key shape.
 * @return a constructed, **not yet started** engine. Call [UploaderEngine.start].
 * @throws IllegalArgumentException if two handlers share a [UploaderHandler.type], or two constraint
 *   providers share a [ConstraintProvider.key]. Both are wiring bugs whose symptom would otherwise
 *   be delayed, silent data loss.
 */
public fun createUploaderEngine(
    store: UploaderStore,
    handlers: List<UploaderHandler<*>>,
    scope: CoroutineScope,
    constraintProviders: List<ConstraintProvider> = emptyList(),
    transactionRunner: TransactionRunner = TransactionRunner.Direct,
    wakeScheduler: WakeScheduler = WakeScheduler.NoOp,
    config: UploaderConfig = UploaderConfig(),
    logger: Logger = NoopLogger,
    clock: UploaderClock = UploaderClock.System,
    idGenerator: () -> String = ::randomUploaderItemId,
): UploaderEngine = DefaultUploaderEngine(
    store = store,
    handlers = handlers,
    scope = scope,
    constraintProviders = constraintProviders,
    transactionRunner = transactionRunner,
    wakeScheduler = wakeScheduler,
    config = config,
    logger = logger,
    clock = clock,
    idGenerator = idGenerator,
)
