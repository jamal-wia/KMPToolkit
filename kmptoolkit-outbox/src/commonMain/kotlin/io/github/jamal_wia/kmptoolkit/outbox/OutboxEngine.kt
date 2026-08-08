package io.github.jamal_wia.kmptoolkit.outbox

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.outbox.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore
import io.github.jamal_wia.kmptoolkit.outbox.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.outbox.spi.WakeScheduler
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope

/**
 * The full engine: [Outbox] plus the lifecycle and settlement operations that belong to whoever
 * owns it.
 *
 * Hand the narrow [Outbox] to features; keep this reference in your application bootstrap, where
 * [start] and [close] have somewhere sensible to live.
 *
 * ## Lifecycle
 *
 * Construct with [createOutboxEngine], call [start] once, and call [close] when the owning scope
 * goes away. Between those, the engine runs one drain coroutine that wakes on: startup (to pick up
 * whatever the previous process left behind), every enqueue, every `false → true` transition of a
 * [ConstraintProvider], every backoff gate coming due, every [trigger], and a heartbeat backstop.
 *
 * ## Guarantees
 *
 * - **At-least-once delivery.** An item is deleted only after its handler confirms success. The
 *   process can die between a successful send and that deletion, so handlers must tolerate a
 *   repeat — see [OutboxHandler].
 * - **Single-flight.** Exactly one drain runs at a time. Triggers are conflated.
 * - **FIFO within an ordering channel**, scoped per handler type. See [OutboxHandler.orderingKey]
 *   for what that does and does not promise across channels.
 * - **Persisted retry budgets.** Attempt counts and backoff gates live on the row, so a backoff
 *   survives a restart instead of resetting.
 * - **Nothing is deleted silently.** Every path that removes an item is either a confirmed
 *   delivery or an explicit decision — [AttemptResult.Drop], [SettleResult.Drop], or a
 *   [GiveUpPolicy.DropAfterAttempts] you configured. Everything else parks.
 */
public interface OutboxEngine : Outbox {

    /**
     * Starts the drain coroutine and wires the standing triggers, then immediately triggers one
     * pass to pick up whatever the previous process left in the queue.
     *
     * Call it once, from your application bootstrap, regardless of whether anyone is signed in —
     * the queue may already hold effects from the last session. Repeat calls are no-ops, and a
     * call after [close] is a no-op too.
     *
     * Does not suspend and does not block: the work happens on the [CoroutineScope] given to
     * [createOutboxEngine].
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
     * Addressed by item [id], which is what makes every race degrade to a harmless no-op rather
     * than to a corrupted queue: a superseded item was re-inserted under a new id, a wiped item is
     * gone, and a duplicate settle finds the row already deleted. In all three cases this call does
     * nothing but log.
     *
     * The guards, precisely:
     * - **Unknown id** — no-op. The debt is no longer this executor's.
     * - **[SettleResult.Failed] on an item that is not [OutboxItemState.IN_FLIGHT]** — no-op. A
     *   parked item must stay parked (reviving it would retry a known-permanent failure) and a
     *   pending item is already back in the drain's hands.
     * - **[SettleResult.Failed] whose lease no longer matches** the one the item carried when this
     *   call read it — no-op. The drain re-handed the item in between, and a stale report must not
     *   clobber the fresh claim.
     * - [SettleResult.Delivered], [SettleResult.Drop] and [SettleResult.Park] are unconditional:
     *   they are terminal facts about the effect, not opinions about its retry state.
     *
     * One window this does **not** close: an executor whose lease expired long ago, whose item was
     * re-handed to a second executor, and which only then reports [SettleResult.Failed]. That report
     * reads the second executor's claim as current, and the item returns to pending having spent an
     * attempt. The consequence is a possible redundant hand-off, not a lost effect — which is why
     * [AttemptResult.Detached] requires the hand-off to be idempotent. Sizing the lease to the
     * executor's real worst case is what keeps this rare.
     *
     * Suspends because it reads and writes the store.
     *
     * @param id the item id the executor received in [AttemptContext.id].
     * @param result what happened.
     */
    public suspend fun settle(id: String, result: SettleResult)

    /**
     * Triggers a drain and suspends until the queue is empty or [timeout] elapses.
     *
     * This is what a platform wake job calls: the job holds a background execution grant, and its
     * only task is to keep the process alive while the already-started engine works. It must not
     * call [drain] itself — single-flight belongs to the started engine.
     *
     * An [OutboxItemState.IN_FLIGHT] item counts as **not** drained. The effect is still owed, and
     * a wake job timing out on one is exactly the re-check that recovers a lease whose executor
     * died.
     *
     * @param timeout how long to wait. The engine polls at
     *   [OutboxConfig.drainPollInterval].
     * @return `true` if the queue emptied within the timeout.
     */
    public suspend fun awaitDrained(timeout: Duration): Boolean

    /**
     * Stops the engine: cancels the drain coroutine, the heartbeat, the pending backoff alarm and
     * the constraint subscriptions, and removes this engine from [OutboxEngineRegistry] if it is
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
 * Creates an outbox engine.
 *
 * Only the first three parameters have no sensible default: the engine needs somewhere to persist
 * ([store]), something to deliver ([handlers]), and somewhere to run ([scope]).
 *
 * ```kotlin
 * val outbox: OutboxEngine = createOutboxEngine(
 *     store = SqlDelightOutboxStore(database),
 *     handlers = listOf(sendMessageHandler, uploadAvatarHandler),
 *     scope = applicationScope,
 *     constraintProviders = listOf(networkConstraint),
 *     wakeScheduler = createWorkManagerWakeScheduler(context),
 * ).also { it.start() }
 * ```
 *
 * @param store where items are persisted. The one thing this module cannot supply for you — see
 *   [OutboxStore] and `docs/kmptoolkit-outbox/07-custom-store.md`.
 * @param handlers one per effect type. Their [OutboxHandler.type]s must be distinct.
 * @param scope the scope the drain coroutine, the heartbeat and the backoff alarms run in.
 *   Cancelling it stops the engine as surely as [OutboxEngine.close] does; the scope's dispatcher
 *   is where handlers execute and where the store is called.
 * @param constraintProviders live preconditions handlers can gate on, matched to
 *   [OutboxHandler.constraintKeys] by [ConstraintProvider.key]. Duplicate keys are rejected.
 * @param transactionRunner brackets each enqueue in a transaction, so a domain write and the effect
 *   it owes can commit atomically. Defaults to [TransactionRunner.Direct], which is correct
 *   whenever enqueueing is the only write.
 * @param wakeScheduler the OS-level wake layer that revives a killed process to finish the queue.
 *   Defaults to [WakeScheduler.NoOp] — delivery then happens while the app runs and at its next
 *   launch.
 * @param config timing knobs; see [OutboxConfig].
 * @param logger where the engine reports what it did. Defaults to [NoopLogger]. Every delivery,
 *   retry, park and drop is logged — pass a real logger and you can reconstruct the life of any
 *   item from the log.
 * @param clock the wall clock backing backoff gates and leases. Substitute it in tests.
 * @param idGenerator produces each item's id. The default is a random UUID; override it if your
 *   server requires a particular idempotency-key shape.
 * @return a constructed, **not yet started** engine. Call [OutboxEngine.start].
 * @throws IllegalArgumentException if two handlers share a [OutboxHandler.type], or two constraint
 *   providers share a [ConstraintProvider.key]. Both are wiring bugs whose symptom would otherwise
 *   be delayed, silent data loss.
 */
public fun createOutboxEngine(
    store: OutboxStore,
    handlers: List<OutboxHandler<*>>,
    scope: CoroutineScope,
    constraintProviders: List<ConstraintProvider> = emptyList(),
    transactionRunner: TransactionRunner = TransactionRunner.Direct,
    wakeScheduler: WakeScheduler = WakeScheduler.NoOp,
    config: OutboxConfig = OutboxConfig(),
    logger: Logger = NoopLogger,
    clock: OutboxClock = OutboxClock.System,
    idGenerator: () -> String = ::randomOutboxItemId,
): OutboxEngine = DefaultOutboxEngine(
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
