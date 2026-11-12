package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.uploader.spi.WakeScheduler
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The engine implementation. See [UploaderEngine] for the contract and [createUploaderEngine] for how
 * it is built; this class is internal so the public surface stays an interface plus a factory.
 *
 * Three structural decisions are worth knowing when reading it:
 *
 * - **Single-flight through a conflated channel.** [start] launches exactly one drain coroutine
 *   that reads the channel of the [EngineLifecycle]. Every wake-up path — enqueue, constraint transition, alarm,
 *   heartbeat, an explicit [trigger] — sends to that channel, so a burst collapses into one pass
 *   and two drains never overlap.
 * - **Backoff lives in the rows, not in memory.** A failed attempt persists its attempt count and
 *   its next-run gate; the drain skips gated rows and arms a one-shot alarm for the soonest one.
 *   The drain never sleeps while holding the queue, and a retry budget survives process death.
 * - **Attempts run in a child coroutine.** The drain runs each attempt in a child of its own, so a withdrawal
 *   ([cancel], [cancelByTag], a REPLACE) can cancel the running attempt of the item it removes.
 * - **Its own child scope.** Everything is launched in the [EngineLifecycle]'s scope, a child of the caller's
 *   scope, so [close] can stop the engine without cancelling a scope it does not own — while
 *   cancelling the caller's scope still stops the engine.
 *
 * ## Structure: a front over components that each own rules
 *
 * This class decides what to do and keeps every name the platform entry points and the tests call, but it holds no
 * store and cancels no upload itself. The store is out of reach **by construction** (the constructor hands it to the
 * ledger and keeps nothing); upload cancels and the process-wide memories are kept to their owners by the review
 * gate below, not by the compiler:
 *
 * | Component | Owns | Rules |
 * |---|---|---|
 * | [RowLedger] | the only holder of the [UploaderStore] and the [TransactionRunner]; the only code that touches [DeliveredUnconfirmed] and [ClaimsInDoubt] | P2 (fact writes), P3, P7, P10, the row half of P1 |
 * | [Withdrawals] | the side effects of a removed row; the only caller of `UploadTransport.cancel` | P1 order, P8 |
 * | [AttemptRegistry] | the attempts the drain runs and their handshake with a withdrawal | P1, P2 |
 * | [DrainCall] | the turns of one call of `drain()` | P9 |
 *
 * The review gate: `rg 'store\.'` and `rg 'transport\.cancel'` over this file find nothing, and neither do
 * `DeliveredUnconfirmed.` or `ClaimsInDoubt.` outside KDoc. This class still reaches the transports (it launches
 * attempts), so a cancel could be written here again; handing the launch to an attempt runner that alone holds the
 * transports is the cure, and it belongs to step 2 of the split (carry-over: the attempt runner, the settle desk and
 * the lifecycle are still methods of this class, and moving them out changes no test).
 *
 * ## Cancellation, close and claims
 *
 * What an attempt, a withdrawal, a claim or a closing engine may still write once a cancellation has landed is one
 * protocol, stated here once. Every call site that applies a rule carries a one-line comment naming it (`// P3: ...`).
 *
 * - **P1, Withdrawal.** A withdrawal ([cancel], [cancelByTag], a REPLACE) whose caller is cancelled before its write
 *   starts does nothing. Once the write has started it runs under `NonCancellable`, and the stop of the attempt and the
 *   cancel of the upload follow it in a `finally`. The side effects follow the row: they run unless the row is
 *   provably still there (a failed delete followed by a read that still finds the row changes nothing, so the call
 *   may be repeated). The delivered-but-unconfirmed memory is not a side effect: a withdrawal never clears it (it may be
 *   rolled back), only a confirming delete does.
 *   *Pinned by:* UploaderEngineWithdrawalWindowRound5AdversarialTest, UploaderStorageCancelledCallerRound5AdversarialTest,
 *   UploaderEngineRestartAdversarialTest, UploaderCancelTest, UploaderEngineContractRound6AdversarialTest (hung
 *   withdrawal) and UploaderEngineContractGapsRound6AdversarialTest (failed cancel, throwing wake scheduler).
 * - **P2, Facts outlive the attempt.** An answer that reports something which already happened outside the engine is
 *   recorded under `NonCancellable`, whether or not the attempt is still active: `Success` (delete the row) and
 *   `Detached` (claim the row under its lease, guarded by the state and claim the pass read). The one exception: a
 *   `Detached` from a **withdrawn** attempt is not recorded, because the withdrawal told its handler to stop the
 *   executor. Every other answer of a cancelled attempt (`Retry`, `Park`, `Drop`, every `UploadPreparation`) records
 *   nothing.
 *   *Pinned by:* UploaderEngineRollbackRound5AdversarialTest, UploaderEngineContractSuspectsRound6AdversarialTest
 *   (delivered and detached answers as close lands), UploaderStorageCloseDeliveryRound6AdversarialTest (SQLite).
 * - **P3, Claim ownership.** A write guarded by a claim this engine computed (`context.claim`, the read claim + 1)
 *   rather than read from the store is made only after the engine has **proved** that the claim is its own. Proof is
 *   either `markInFlight` returning `true`, or a `NonCancellable` read showing the row `IN_FLIGHT` with that claim
 *   **and the exact lease this hand-off wrote**. Within a process, two unresolved claims of one item never carry the
 *   same lease ([ClaimsInDoubt]). A release that cannot prove ownership writes nothing; the lease is the fallback.
 *   *Pinned by:* UploaderEngineCloseClaimRound5AdversarialTest, UploaderStorageCloseClaimRound5AdversarialTest,
 *   UploaderStorageClaimReleaseRound6AdversarialTest, UploaderEngineContractGapsRound6AdversarialTest (late release of a
 *   closed engine).
 * - **P4, Engine alive gate.** No hand-off of a closed engine launches. A claim that returns `true` after [close] is
 *   released (P3) and nothing is launched. A withdrawal does **not** gate the launch: the withdrawal's own cancel,
 *   after its stop of the attempt, plus the post-launch check keep a cancel after the last launch. A [close] from another
 *   thread that lands between the check and the launch cannot be excluded without making [close] block.
 *   *Pinned by:* UploaderEngineWithdrawalWindowRound5AdversarialTest (`[cancel, launch, cancel]`: the gate is on the
 *   engine, not on the attempt) and UploaderEngineContractSuspectsRound6AdversarialTest (claim finished after close).
 * - **P5, A cancelled preparation is never charged.** On the drain side and the worker side alike, a throw or an
 *   answer from `prepareUpload` that arrives after the caller's cancellation spends no attempt and applies no Drop or
 *   Park.
 *   *Pinned by:* UploaderEngineContractSuspectsRound6AdversarialTest (worker side), UploaderEngineContractRound6AdversarialTest
 *   (control) and the round-5 drain-side tests.
 * - **P6, Bounds.** [close] never waits. A `NonCancellable` write is bounded only by the store: a call that never
 *   returns holds its caller for good. An attempt that ignores its cancellation can still be running when a
 *   replacement engine starts the same item, which is the same at-least-once window a process kill opens.
 *   *Pinned by:* UploaderEngineContractRound6AdversarialTest (hung withdrawal).
 * - **P8, Withdraw.** Every row a withdrawal removes ([cancel], [cancelByTag], a REPLACE) goes through one funnel,
 *   [Withdrawals.onRemoved]: the attempt the engine runs for it is stopped and its upload is cancelled, **whatever the
 *   row's state, parked included**. A parked row can still have an earlier claim's upload running: a re-hand that
 *   parks does not cancel that upload (the transports erase an outcome journal entry on cancel, and a delivery
 *   waiting there must still settle the item), and an iOS background transfer outlives the process. So no withdrawal
 *   may rely on "a parked item has no upload"; cancelling an id the transport does not run is a no-op. A funnel call
 *   never clears the delivered-but-unconfirmed memory (P1). The one other place that cancels an upload is the end of
 *   a re-hand of an expired lease that **drops** the item (the preparation answered `Drop`, or a failure the engine
 *   gave up on with a drop): the item is gone, so the earlier claim's transfer is cancelled, once the drop applied.
 *   *Pinned by:* EngineBossRound3SuspectsAdversarialTest (bn3-S1a, S1b, S1c) and UploaderCancelTest.
 * - **P7, Confirm.** Every row the engine deletes because its effect ran or was dropped goes through one funnel,
 *   [RowLedger.confirm], whichever path reports it: a handler's `Success` or `Drop`, a `Delivered` or `Drop` settled
 *   through [settle] (an executor's own report, the upload channel's included), an upload preparation's `Drop`. A
 *   delete that fails is **remembered for this process** ([DeliveredUnconfirmed], also seen by an engine built to
 *   replace this one) and a delete that succeeds forgets it, so the row is never handed out again: the drain's next
 *   turn for it only retries the delete, and the call that reported the effect still throws the store's failure (a
 *   repeated `settle` is harmless). The funnel is for a row whose effect is a fact; a withdrawal (P8) and the drain's
 *   own confirming retry are not calls to it: the first never remembers or forgets, the second is the retry itself.
 *   *Pinned by:* EngineBossRound3SuspectsAdversarialTest (bn3-S2a and its fix tests), the boss round 1 and 2 suites of
 *   the delivered memory, UploadHandlerTest.
 * - **P9, Turn.** In one call of `drain()` (and in one pass of the engine's own loop) an item gets **at most one
 *   turn**, owned by [DrainCall] and checked in one place in [drainPass], ahead of every kind of work on the item (it
 *   follows only the gates, which write nothing): an attempt that ran, an attempt that failed (outside its handler or
 *   not), a park of an item with no handler, or a confirming delete that failed uses it up; a later sweep of the same
 *   call leaves the item for the next trigger or alarm. A confirming delete that **succeeded** uses nothing (the row is
 *   gone, and the tail of its ordering channel must still run in the same call). The turn bounds every clock: the
 *   in-memory backoff of a failure outside the handler cannot (a clock held at `Long.MAX_VALUE` saturates every gate
 *   and lease, so each reads as due, and items then retry at the alarm floor per call instead of at their backoff).
 *   *Pinned by:* EngineBossRound3SuspectsAdversarialTest (bn3-S3a, S3b, control and the fix tests),
 *   EngineBossRound3AdversarialTest (P1: the tail of a confirmed delivery runs in the same drain) and
 *   EngineBossRound4SuspectsAdversarialTest (bv4-S2a, the orphan park).
 * - **P10, A fact outranks a verdict.** A Park, a Failed or any give-up (PARK or DROP) is never applied to a row this
 *   process still holds as delivered ([RowLedger.holds]): [RowLedger.park] and [RowLedger.recordFailure], the only two
 *   verdict writers, run the P7 confirming delete instead (forget on success, held on failure) and report the verdict as
 *   not applied: a stale `settle` (`applied = false`), no `onSettled` and a dropped journal entry for an upload. The
 *   drain asks nothing else of a held row before it retries its delete: the check for it follows the gates and the
 *   turn and precedes the handler lookup, the constraints and the backoff gate. The order of the two does not matter: a
 *   verdict that landed **first** (the row is PARKED, out of the active list the drain reads) is rescued too, because
 *   a row held as delivered is confirmed in whatever state it is: once per call, ahead of the empty-queue check,
 *   [RowLedger.heldOutOfRotation] names the parked held rows and the drain retries their delete under the same turn
 *   and backoff rules, keeps an alarm armed for them when the queue is otherwise empty (the platform wake stays
 *   disarmed: the memory dies with the process), and `settle` triggers a pass even when its delete throws.
 *   *Pinned by:* EngineBossRound4SuspectsAdversarialTest (bv4-S3a, S3b), EngineSplit1P10AdversarialTest and
 *   EngineSplit1HeldRescueAdversarialTest (a verdict that came first).
 */
internal class DefaultUploaderEngine(
    store: UploaderStore,
    handlers: List<UploaderHandler<*>>,
    scope: CoroutineScope,
    constraintProviders: List<ConstraintProvider>,
    transactionRunner: TransactionRunner,
    private val wakeScheduler: WakeScheduler,
    private val config: UploaderConfig,
    logger: Logger,
    private val clock: UploaderClock,
    private val idGenerator: () -> String,
) : UploaderEngine {

    /**
     * The caller's logger behind a guard: a sink that throws (an uninitialised crash reporter, say) must
     * never take the drain, an enqueue or a settle down with it, so every call goes through [NonThrowingLogger].
     */
    private val logger: Logger = logger.nonThrowing()

    /** The only holder of the store: every row read and write of this engine goes through it. */
    private val ledger: RowLedger = RowLedger(store, transactionRunner, this.logger)

    private val attemptRegistry: AttemptRegistry = AttemptRegistry(ledger, this.logger)

    private val handlersByType: Map<String, UploaderHandler<*>> =
        handlers.associateBy { it.type }.also { byType ->
            // A duplicate type would surface as delayed data loss rather than as an error: enqueue
            // encodes with the caller's handler, the drain decodes with whichever won the map, and
            // the mismatched payload parks as undecodable days later. Fail at construction instead.
            require(byType.size == handlers.size) {
                "Duplicate UploaderHandler types: " +
                    handlers.groupBy { it.type }.filterValues { it.size > 1 }.keys
            }
        }

    /** The only code that stops an attempt for a removed row or cancels an upload (rule P8). */
    private val withdrawals: Withdrawals = Withdrawals(handlersByType, attemptRegistry, this.logger)

    private val providersByKey: Map<String, ConstraintProvider> =
        constraintProviders.associateBy { it.key }.also { byKey ->
            require(byKey.size == constraintProviders.size) {
                "Duplicate ConstraintProvider keys: " +
                    constraintProviders.groupBy { it.key }.filterValues { it.size > 1 }.keys
            }
        }

    /** The life of this engine: scope, drain loop, alarm, close (rules P6 and the state of P4). */
    private val lifecycle: EngineLifecycle =
        EngineLifecycle(this, scope, ledger, constraintProviders, config, clock, this.logger)

    /** What happens to one admitted attempt: the handler run, the upload hand-off and the recorded answer. */
    private val attemptRunner: AttemptRunner =
        AttemptRunner(ledger, withdrawals, clock, this.logger, isClosed = { isClosed })

    override fun start() {
        lifecycle.start { drainPass() }
    }

    override fun trigger() {
        lifecycle.trigger()
    }

    override suspend fun <P : Any> enqueue(
        handler: UploaderHandler<P>,
        payload: P,
        uniqueKey: String?,
        tag: String?,
        conflictPolicy: ConflictPolicy,
    ): String? {
        val record = UploaderItem(
            id = idGenerator(),
            type = handler.type,
            payload = handler.encodePayload(payload),
            schemaVersion = handler.schemaVersion,
            uniqueKey = uniqueKey,
            // The handler owns its ordering discipline, not the enqueue call sites. Derived once,
            // here, and persisted — so rows already queued keep their channel even if the handler's
            // implementation changes in a later release.
            orderingKey = handler.orderingKey(payload),
            tag = tag,
            state = UploaderItemState.PENDING,
            attempts = 0,
            nextRunAtEpochMillis = 0L,
            createdAtEpochMillis = clock.nowEpochMillis(),
            lastError = null,
        )
        // The item a REPLACE supersedes, or a KEEP supersedes (a PARKED holder), comes back from the insert itself rather
        // than from a read made first: under a TransactionRunner that is not a real transaction (Direct, the default), an
        // item enqueued between such a read and the insert would lose its row without its upload
        // ever being cancelled. One handed off before the replacement lands is cancelled here; one whose
        // hand-off is still launching cancels itself, finding its row gone right after the launch.
        val superseded: RowLedger.Superseded = RowLedger.Superseded()
        // A caller already cancelled when the write would start writes nothing. Once the write has
        // started it is made non-cancellable: a store that notices a cancelled caller (one that hops to a
        // database thread, say) would otherwise throw after committing, and the superseded item's attempt
        // and upload would be left running for a row that is gone.
        currentCoroutineContext().ensureActive()
        val inserted: Boolean = try {
            ledger.enqueue(record, conflictPolicy, superseded)
        } finally {
            // P1: the superseded item's side effects come first. A real transaction runner reports the caller's
            // cancellation on return even though the transaction committed, and the stop and the cancel must still
            // happen, whatever the wake scheduler below does. P8: whatever the row's state, parked included (an earlier
            // claim's upload can outlive its lease). None of this suspends or throws.
            superseded.row?.let { old -> withdrawals.onRemoved(listOf(old), reason = "superseded by ${record.logName}") }
            // Persist-first for the wake too: even if the process dies before the drain runs, the
            // platform wake is already armed for the debt that was just persisted. This sits in a finally
            // because a caller cancelled (or a store failing) after the row committed but before the call
            // returned cannot tell whether the row landed, and a committed row with no armed wake and no
            // trigger would wait for some unrelated one. Arming for a row that did not land is harmless:
            // the next drain pass finds the queue empty and disarms.
            trigger()
            wakeScheduler.scheduleWake()
        }
        if (inserted) {
            logger.d { "Enqueued ${record.logName}." }
        } else {
            logger.d { "Enqueue of ${record.logName} kept the already-queued item." }
        }
        return record.id.takeIf { inserted }
    }

    override fun observe(type: String): Flow<List<UploaderItem>> = ledger.observe(type)

    override suspend fun cancel(id: String) {
        // P1: a caller cancelled before the write starts removes nothing; once it has started, the write and
        // the side effects that follow the row run to the end whatever happens to the caller (a cleared
        // viewModelScope during a logout wipe, say).
        val withdrawn: RowLedger.Withdrawn? = ledger.withdraw(id)
        if (withdrawn == null) {
            logger.i { "cancel for unknown id=$id — no-op." }
            return
        }
        // P1: the side effects follow the row. A delete that committed and then failed must not orphan the upload,
        // but one that changed nothing must not stop a live one: only a row provably still queued keeps its attempt
        // and upload.
        if (!withdrawn.stillQueued) withdrawals.onRemoved(listOf(withdrawn.row), reason = "withdrawn")
        trigger() // an emptied queue lets the drain disarm the wake
        withdrawn.failure?.let { throw it }
    }

    override suspend fun cancelByTag(tag: String) {
        // The rows come back from the wipe itself rather than from a read made first: an item enqueued
        // and handed off between such a read and the delete would lose its row while its upload ran on —
        // after a logout wipe, under the next account. P8: a parked item is withdrawn like any other.
        // A caller cancelled before the wipe starts removes nothing; once started, the wipe is
        // non-cancellable so its list always comes back and every stop and cancel below runs.
        val removed: List<UploaderItem> = ledger.withdrawByTag(tag)
        withdrawals.onRemoved(removed, reason = "withdrawn by tag")
        logger.i { "Withdrew every item tagged '$tag' (${removed.size} removed)." }
        trigger()
    }

    override suspend fun awaitDrained(timeout: Duration): Boolean {
        trigger()
        // An already empty queue is drained whatever budget is left: a zero (or spent) timeout must not
        // turn "nothing owed" into "not drained" just because the timed block below never gets to run.
        if (isQueueEmpty()) return true
        // Bounded by coroutine time rather than by the clock, so a test driving virtual time gets
        // the same behavior as production without also having to advance a fake wall clock.
        val drained: Boolean? = withTimeoutOrNull(timeout) {
            do {
                delay(config.drainPollInterval)
            } while (!isQueueEmpty())
            true
        }
        return drained == true
    }

    /**
     * Whether the store reports nothing owed. A failing read answers `false` ("not known to be empty")
     * instead of throwing: the caller is typically a platform wake job, where an exception ends the whole
     * job over what is usually a momentary lock, while the drain loop itself already survives the same
     * failure. A persistent failure therefore ends in the caller's timeout, which reports "not drained".
     * Real cancellation, including the timeout's own, still propagates.
     */
    private suspend fun isQueueEmpty(): Boolean = try {
        ledger.active().isEmpty()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        logger.e(e) { "Queue read leaked a cancellation; treating the queue as not drained." }
        false
    } catch (e: Throwable) {
        logger.e(e) { "Queue read failed; treating the queue as not drained." }
        false
    }

    override suspend fun settle(id: String, claim: Long, result: SettleResult) {
        commitSettle(id, claim, result)
    }

    /**
     * The commit behind [settle].
     *
     * @return whether it applied: `false` for a missing row, a `Park` or `Failed` rejected as stale, a park
     *   or failure the store refused; `true` for a delivery or drop on a present row and an applied park or
     *   failure. An upload settlement runs its `onSettled` hook only for a commit that applied.
     */
    private suspend fun commitSettle(id: String, claim: Long, result: SettleResult): Boolean {
        val record: UploaderItem? = ledger.get(id)
        if (record == null) {
            // Late, duplicate, or superseded settle — the debt is no longer this executor's.
            logger.i { "settle($result) for unknown id=$id — no-op." }
            return false
        }
        // Whatever the commit did, a throw included, the drain is triggered: a delivery whose delete failed leaves a held
        // row that only the drain can confirm (P7), and that retry should start now, not at the next unrelated wake.
        try {
            val applied: Boolean = when (result) {
                // Facts about the effect, so they stand from any claim: a superseded executor that delivered
                // has still delivered, and re-handing the item would send it twice.
                is SettleResult.Delivered -> {
                    deleteAfterEffect(record) // P7
                    logger.i { "Settled ${record.logName} as delivered." }
                    true
                }

                is SettleResult.Drop -> {
                    deleteAfterEffect(record) // P7
                    logger.i { "Settled ${record.logName} as dropped — ${result.reason}." }
                    true
                }

                // Verdicts on one attempt, so they stand only from the attempt the engine still runs.
                is SettleResult.Park -> if (record.isCurrentFor(claim, result)) {
                    val parked: Boolean = ledger.park(record, result.reason)
                    if (parked) {
                        logger.w { "Settled ${record.logName} as parked — ${result.reason}." }
                    } else {
                        logger.i { "Park settle for ${record.logName} not applied — no-op." }
                    }
                    parked
                } else {
                    false
                }

                is SettleResult.Failed -> if (record.isCurrentFor(claim, result)) settleFailed(record, result) else false
            }
            // Delivered may have emptied the queue (let the drain disarm the wake); Failed armed a
            // backoff gate (let the drain schedule an alarm for it).
            return applied
        } finally {
            trigger()
        }
    }

    /**
     * Whether a report from [claim] is about the attempt the engine still runs for this item: the claim
     * it is held under, or — for a fast executor reporting before `execute` returned and the hand-off
     * was recorded — the claim the drain is about to write, one past the row's. Any claim written since
     * supersedes it, and a parked item is held by no claim at all. Logs the stale report it rejects.
     */
    private fun UploaderItem.isCurrentFor(claim: Long, result: SettleResult): Boolean {
        val current: Boolean = when (state) {
            UploaderItemState.IN_FLIGHT -> claim == this.claim || claim == this.claim + 1
            UploaderItemState.PENDING -> claim == this.claim + 1
            UploaderItemState.PARKED -> false
        }
        if (!current) {
            logger.i { "Stale $result settle for $logName (claim $claim; item $state under ${this.claim}) — no-op." }
        }
        return current
    }

    private suspend fun settleFailed(record: UploaderItem, result: SettleResult.Failed): Boolean {
        val handler: UploaderHandler<*>? = handlersByType[record.type]
        if (handler == null) {
            // The same treatment the drain gives an orphaned type: keep it, keep it visible.
            val parked: Boolean = parkAsRead(record, "no handler registered for type '${record.type}'")
            logger.e { "Parked ${record.logName} on settle — no handler for its type." }
            return parked
        }
        // Guarded by the state and claim the record carried when we read it: if the drain re-handed the
        // item in between, the claim moved and this write no-ops instead of clobbering the fresh one.
        return attemptRunner.recordRetry(record = record, handler = handler, cause = result.cause) != null
    }

    /** Parks [record] as read (see [RowLedger.park]). */
    private suspend fun parkAsRead(record: UploaderItem, reason: String?): Boolean = ledger.park(record, reason)
    override suspend fun drain() {
        lifecycle.drain { drainPass() }
    }

    /** One call's worth of drain work: the body of [drain], and of every pass of the engine's own loop. */
    private suspend fun drainPass() {
        // Per call of drain(), rule P9: the turns used so far (see DrainCall).
        val call = DrainCall()
        // The held rows that are out of rotation (see sweepHeldOutOfRotation): the ids whose backoff the pruning keeps,
        // and the soonest time one of them is worth another try, which keeps an alarm armed when the queue is empty.
        var heldIds: Set<String> = emptySet()
        var heldGate: Long? = null
        while (true) {
            // A store whose calls never suspend gives cancellation no other place to be seen: without this a
            // close() (or a cancelled scope) from inside a handler would not stop the pass.
            currentCoroutineContext().ensureActive()
            // A closed engine is permanently inert: a manual drain() on one runs nothing, however it is called.
            if (isClosed) return
            if (call.takeHeldSweep()) {
                // P7: a row held as delivered is confirmed in whatever state it is. The active list below never offers a
                // PARKED one, so they are visited here, once per call, ahead of the empty-queue check.
                val swept: HeldSweep = sweepHeldOutOfRotation(call)
                heldIds = swept.ids
                heldGate = swept.gate
            }
            val active: List<UploaderItem> = ledger.active()
            pruneOutsideFailures(active, heldIds)
            if (active.isEmpty()) {
                // A held row that is still owed its delete keeps the alarm: the delete is retried when it is due, with no
                // other trigger needed. The platform wake gains nothing (the memory dies with the process).
                if (heldGate != null) lifecycle.scheduleAlarm(heldGate) else lifecycle.cancelAlarm()
                wakeScheduler.cancelWake() // nothing owed — disarm the platform wake
                // An enqueue that landed between the read above and that cancel has already armed its
                // wake, and the cancel has just disarmed it: a process killed before the next pass would
                // then keep the item until the next launch. So the cancel only stands if the queue is
                // still empty after it. Any enqueue landing later arms its wake after the cancel.
                if (ledger.active().isEmpty()) return
                wakeScheduler.scheduleWake()
                continue
            }
            // Channel heads come from the FULL active list, so an in-flight head keeps blocking its
            // channel's tail: an in-flight delivery is still the oldest owed effect in it.
            val eligible: List<UploaderItem> = channelHeads(active)
            val nowMillis: Long = clock.nowEpochMillis()
            var executedAny = false
            var soonestGate: Long? = null

            for (item in eligible) {
                currentCoroutineContext().ensureActive()
                if (item.state == UploaderItemState.IN_FLIGHT && item.leaseUntilEpochMillis > nowMillis &&
                    !isClockAnomalyLease(item.leaseUntilEpochMillis, nowMillis)
                ) {
                    // A detached executor still holds an unexpired claim. Its expiry is a gate like
                    // a backoff gate, so the alarm re-triggers at it and a silently dead executor
                    // is re-handed promptly rather than on the next heartbeat.
                    soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, item.leaseUntilEpochMillis)
                    continue
                }
                // An expired lease falls through: the item is attemptable again and the handler
                // runs afresh, its hand-off idempotent by contract.
                // An attempt that failed outside its handler (a store write failing after the effect ran, a
                // consumer policy throwing) waits out a growing backoff kept in memory, across passes: the
                // same effect must not be run again at the alarm floor, pass after pass.
                val outside: OutsideFailure? = outsideFailures.value[item.id]
                if (outside != null && !outside.isDue(nowMillis)) {
                    soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, outside.notBeforeMillis)
                    continue
                }
                val handler: UploaderHandler<*>? = handlersByType[item.type]
                // P9: the one place the turn is checked, ahead of every kind of work on the item (a park, a confirming
                // delete, an attempt) and behind only the gates, which read and write nothing. The item already had its
                // turn in this call and is due again at once (a retry whose policy returned no delay, a failure whose
                // gate reads as due on this clock, a park): its next try waits for the next trigger or alarm instead of
                // repeating in this loop, or for the gate the row carries when that is later.
                if (call.hasHadTurn(item.id)) {
                    val floor: Long = saturatingAdd(nowMillis, lifecycle.failedPassRetryMillis(1))
                    val persistedGate: Long? = item.nextRunAtEpochMillis.takeIf { gate ->
                        gate > nowMillis && handler != null && !isClockAnomalyGate(gate, nowMillis, handler)
                    }
                    soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, persistedGate ?: floor)
                    continue
                }
                // P10: a row held as delivered is confirmed before anything else is asked of it: whether its type still has a
                // handler, whether its constraints hold or its gate has opened are questions about running the effect again,
                // and a delete needs none of them.
                if (ledger.holds(item)) {
                    // The effect succeeded in this process (in this engine or one it replaced) and only its
                    // confirming delete failed: repeating the effect would send it twice, so only the delete is retried.
                    val retryAt: Long? = confirmHeldInDrain(item, call)
                    if (retryAt != null) soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, retryAt)
                    executedAny = true
                    continue
                }
                if (handler == null) {
                    // Either a wiring bug or a payload from a build that had the type. Parking
                    // keeps the item visible instead of failing forever or deleting a user's data. The park is work like
                    // an attempt: it uses the item's turn, and a store that throws on it is isolated like one.
                    call.spendTurn(item.id) // P9
                    try {
                        parkAsRead(item, "no handler registered for type '${item.type}'")
                        logger.e { "Parked ${item.logName} — no handler for its type." }
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        logger.e(e) { "Parking ${item.logName} (no handler for its type) leaked a cancellation; retrying later." }
                        bumpOutsideFailure(item.id)
                    } catch (e: Throwable) {
                        logger.e(e) { "Parking ${item.logName} (no handler for its type) failed; retrying later." }
                        bumpOutsideFailure(item.id)
                    }
                    executedAny = true
                    continue
                }
                // A provider that throws on read must not abort the pass for every item behind it: the
                // item is skipped like an unsatisfied one (never attempted on an unreadable constraint),
                // and the next trigger or heartbeat asks again.
                val constraintsMet: Boolean = try {
                    constraintsSatisfied(handler)
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    logger.e(e) { "Reading a constraint of ${item.logName} leaked a cancellation; skipping it this pass." }
                    false
                } catch (e: Throwable) {
                    logger.e(e) { "Reading a constraint of ${item.logName} failed; skipping it this pass." }
                    false
                }
                if (!constraintsMet) continue
                if (item.nextRunAtEpochMillis > nowMillis &&
                    !isClockAnomalyGate(item.nextRunAtEpochMillis, nowMillis, handler)
                ) {
                    soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, item.nextRunAtEpochMillis)
                    continue
                }
                try {
                    val ran: Boolean = attemptRegistry.runUnlessMoved(item) { attempt -> attemptRunner.executeItem(item, handler, attempt) }
                    outsideFailures.update { it - item.id }
                    if (ran) {
                        call.spendTurn(item.id)
                    } else if (call.skippedAsMovedBefore(item.id)) {
                        // Skipped as "moved" twice in one call although nothing else changed it: the store's point
                        // read keeps disagreeing with its list read. Leave the item alone for now and say so; the
                        // alarm asks again, and a consistent store will have caught up by then.
                        logger.w { "Skipped ${item.logName} again — the store's getById differs from its getAllActive." }
                        soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, saturatingAdd(nowMillis, lifecycle.failedPassRetryMillis(1)))
                        continue
                    }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    logger.e(e) { "Attempt of ${item.logName} leaked a cancellation; retrying later." }
                    call.spendTurn(item.id) // P9
                    bumpOutsideFailure(item.id)
                } catch (e: Throwable) {
                    logger.e(e) { "Attempt of ${item.logName} failed outside its handler; retrying later." }
                    call.spendTurn(item.id) // P9
                    bumpOutsideFailure(item.id)
                }
                executedAny = true
            }

            if (!executedAny) {
                lifecycle.scheduleAlarm(minOfNullable(soonestGate, heldGate))
                // Work remains, gated or backing off — keep the platform wake armed so a killed
                // process still gets to deliver it.
                wakeScheduler.scheduleWake()
                return
            }
        }
    }

    /**
     * The drain's confirming retry of a row held as delivered (P7): forgets the entry on success and clears the row's
     * outside failure. A failure (a leaked cancellation included) uses the item's turn (P9) and backs off like any
     * failure outside the handler.
     *
     * @return `null` when the delete confirmed the delivery, else the time of the next try.
     */
    private suspend fun confirmHeldInDrain(item: UploaderItem, call: DrainCall): Long? {
        try {
            // P7: this is the confirming retry of a remembered delete, so it forgets on success itself.
            ledger.confirmHeld(item)
            outsideFailures.update { it - item.id }
            logger.i { "Confirmed the delivery of ${item.logName} after its delete failed earlier." }
            return null
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            logger.e(e) { "Confirming the delivery of ${item.logName} leaked a cancellation; retrying later." }
        } catch (e: Throwable) {
            logger.e(e) { "Confirming the delivery of ${item.logName} failed again; retrying later." }
        }
        call.spendTurn(item.id) // P9
        return bumpOutsideFailure(item.id)
    }

    /** What [sweepHeldOutOfRotation] found: the rows' [ids] and the soonest [gate] a delete is worth another try, if any. */
    private class HeldSweep(val ids: Set<String>, val gate: Long?)

    /**
     * Rule P7 for the rows [drainPass]'s active list never offers: a row this process holds as delivered that a verdict
     * parked (before the delivery's delete failed, or while it was failing) is still owed its delete, whatever its
     * state. Runs once per call, under the same turn and backoff rules as an active held row.
     */
    private suspend fun sweepHeldOutOfRotation(call: DrainCall): HeldSweep {
        val held: List<UploaderItem> = ledger.heldOutOfRotation()
        if (held.isEmpty()) return HeldSweep(emptySet(), null)
        val nowMillis: Long = clock.nowEpochMillis()
        var gate: Long? = null
        for (item in held) {
            currentCoroutineContext().ensureActive()
            val outside: OutsideFailure? = outsideFailures.value[item.id]
            if (outside != null && !outside.isDue(nowMillis)) {
                gate = minOfNullable(gate, outside.notBeforeMillis)
                continue
            }
            if (call.hasHadTurn(item.id)) continue
            gate = minOfNullable(gate, confirmHeldInDrain(item, call))
        }
        return HeldSweep(held.mapTo(HashSet()) { it.id }, gate)
    }

    private fun minOfNullable(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else minOf(a, b)

    /**
     * The oldest active item of each ordering channel, plus every keyless item.
     *
     * [active] is already oldest-first, so the first occurrence of a key is that channel's head.
     * Channels are scoped per handler type — two handlers returning the same raw key must not share
     * one channel, or one type's backoff would stall the other's queue. A channel is the (type, key)
     * pair, so no string of either can make two channels collide.
     */
    private fun channelHeads(active: List<UploaderItem>): List<UploaderItem> {
        val seenKeys: MutableSet<Pair<String, String>> = mutableSetOf()
        return active.filter { item ->
            val key: String = item.orderingKey ?: return@filter true
            seenKeys.add(item.type to key)
        }
    }

    /**
     * Whether a persisted backoff gate is too far in the future to be real.
     *
     * A gate can legitimately sit at most one maximum backoff ahead. Anything beyond
     * [UploaderConfig.clockAnomalyFactor] times that means the wall clock jumped **backwards** after
     * the gate was written, and honoring it would freeze the item — and everything behind it in its
     * ordering channel — for the whole duration of the jump. Such a gate is treated as runnable
     * now; the next failure writes a sane one.
     */
    private fun isClockAnomalyGate(
        gateEpochMillis: Long,
        nowMillis: Long,
        handler: UploaderHandler<*>,
    ): Boolean {
        val reachable: Long = saturatingMultiply(handler.retryPolicy.maxDelayMillis, config.clockAnomalyFactor.toLong())
        val ahead: Long = saturatingSubtract(gateEpochMillis, nowMillis)
        val anomaly: Boolean = ahead > reachable
        if (anomaly) {
            logger.w {
                "Backoff gate ${ahead}ms ahead exceeds the policy's " +
                    "reachable maximum (${reachable}ms) — treating it as a wall-clock jump and " +
                    "running now."
            }
        }
        return anomaly
    }

    /**
     * Whether a persisted lease is too far in the future to be real — the lease twin of [isClockAnomalyGate].
     * The engine never writes a lease beyond [AttemptRunner.MAX_LEASE_MILLIS] (see [AttemptRunner.clampLease]), so one further ahead than
     * [UploaderConfig.clockAnomalyFactor] times that was written before the wall clock stepped **backwards**.
     * Honouring it would freeze the item, and its ordering channel, for the whole jump; it is treated as
     * expired instead and the item is re-handed (a hand-off is idempotent by contract), which writes a sane
     * lease.
     */
    private fun isClockAnomalyLease(leaseUntilEpochMillis: Long, nowMillis: Long): Boolean {
        val reachable: Long = saturatingMultiply(AttemptRunner.MAX_LEASE_MILLIS, config.clockAnomalyFactor.toLong())
        val ahead: Long = saturatingSubtract(leaseUntilEpochMillis, nowMillis)
        val anomaly: Boolean = ahead > reachable
        if (anomaly) {
            logger.w {
                "Lease ${ahead}ms ahead exceeds the longest lease the engine " +
                    "writes (${AttemptRunner.MAX_LEASE_MILLIS}ms) — treating it as a wall-clock jump and re-handing the item."
            }
        }
        return anomaly
    }

    private fun constraintsSatisfied(handler: UploaderHandler<*>): Boolean =
        handler.constraintKeys.all { key ->
            val provider: ConstraintProvider? = providersByKey[key]
            if (provider == null) {
                // Failing open beats stalling a queue forever on a wiring typo.
                logger.e { "No ConstraintProvider for key '$key' — treating it as satisfied." }
                return@all true
            }
            provider.satisfied.value
        }

    /** See [AttemptRegistry.stampOf]. */
    internal fun runningAttemptStamp(id: String): Int? = attemptRegistry.stampOf(id)

    /** See [AttemptRegistry.awaitEnd]. */
    internal suspend fun awaitHandOff(id: String, timeoutMillis: Long): Unit {
        attemptRegistry.awaitEnd(id, timeoutMillis)
    }

    /**
     * What the engine remembers, in memory only, about an item whose attempt failed outside its handler.
     * [notBeforeMillis] is the earliest wall-clock time of the next try. Whether the handler already succeeded (or
     * dropped the item) and only the confirming delete failed is not kept here but process-wide, in
     * [DeliveredUnconfirmed], so that an engine built to replace this one knows it too.
     */
    private data class OutsideFailure(val count: Int, val notBeforeMillis: Long) {
        /**
         * Due when its time has come, or when it lies further ahead than the heartbeat (a clock stepped
         * backwards must not freeze the item for the whole jump).
         */
        fun isDue(nowMillis: Long, heartbeatMillis: Long): Boolean {
            val ahead: Long = saturatingSubtract(notBeforeMillis, nowMillis)
            return ahead <= 0 || ahead > heartbeatMillis
        }
    }

    private val outsideFailures: MutableStateFlow<Map<String, OutsideFailure>> = MutableStateFlow(emptyMap())

    private fun OutsideFailure.isDue(nowMillis: Long): Boolean =
        isDue(nowMillis, config.heartbeatInterval.inWholeMilliseconds)

    /**
     * Forgets the entries of items that left the queue, so the map is bounded by the queue itself. The rows [heldIds]
     * names are held as delivered and out of rotation: their backoff is kept, or it would restart at every pass.
     */
    private fun pruneOutsideFailures(active: List<UploaderItem>, heldIds: Set<String>) {
        if (outsideFailures.value.isEmpty()) return
        val ids: Set<String> = active.mapTo(HashSet(heldIds)) { it.id }
        outsideFailures.update { map -> map.filterKeys { it in ids } }
    }

    /** Records one more failure outside the handler for [id] and returns the time of its next try. */
    private fun bumpOutsideFailure(id: String): Long {
        val now: Long = clock.nowEpochMillis()
        var notBefore = 0L
        outsideFailures.update { map ->
            val old: OutsideFailure? = map[id]
            val count: Int = (old?.count ?: 0) + 1
            notBefore = saturatingAdd(now, lifecycle.failedPassRetryMillis(count))
            map + (id to OutsideFailure(count, notBefore))
        }
        return notBefore
    }

    /** Rule P7: see [RowLedger.confirm]. */
    private suspend fun deleteAfterEffect(item: UploaderItem): Unit {
        ledger.confirm(item)
    }

    /**
     * One upload attempt of an [UploadHandler]'s item, prepared at the moment the transport runs it —
     * see [UploadGateway.prepareAttempt]. A store failure reads as [UploadAttempt.EngineUnavailable], so
     * the executor retries instead of concluding anything.
     */
    suspend fun prepareUploadAttempt(id: String): UploadAttempt = prepareStampedUploadAttempt(id).attempt

    /**
     * [prepareUploadAttempt] with the attempt stamp of a journaled answer: the row's `attempts` as read for a
     * [UploadAttempt.Ready] attempt, else `null`.
     */
    internal suspend fun prepareStampedUploadAttempt(id: String): PreparedUpload = try {
        when (val prepared: PreparedRequest? = prepareUploadRequest(id)) {
            is PreparedRequest.Prepared ->
                PreparedUpload(UploadAttempt.Ready(prepared.request), prepared.attemptStamp, prepared.bodyLimit)

            PreparedRequest.MovedOn -> PreparedUpload(UploadAttempt.EngineUnavailable, null)
            PreparedRequest.NothingOwed, null -> PreparedUpload(UploadAttempt.NothingOwed, null)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.e(e) { "Upload attempt for id=$id could not be prepared — store failure; retry later." }
        PreparedUpload(UploadAttempt.EngineUnavailable, null)
    }

    /** What [prepareUploadRequest] found. */
    private sealed interface PreparedRequest {

        /** Upload [request]: prepared at the row's [attemptStamp], its response held to [bodyLimit]. */
        class Prepared(val request: UploadRequest, val attemptStamp: Int, val bodyLimit: Int) : PreparedRequest

        /** The row settled or was withdrawn while the handler prepared the upload. */
        data object NothingOwed : PreparedRequest

        /** The row was charged while the handler prepared the upload; the job retries and prepares the new attempt. */
        data object MovedOn : PreparedRequest
    }

    /**
     * @return the request, the row's `attempts` it was prepared at and the body limit read with them, or
     *   `null` when nothing is to be uploaded: the row is gone, no longer in flight or not an upload item, the handler dropped or
     *   parked it (applied here), or preparation failed (charged to the row as a failed attempt, guarded by
     *   its claim). The row is read again after the handler prepared, so a settlement or withdrawal that landed
     *   meanwhile is [PreparedRequest.NothingOwed] and a charge is [PreparedRequest.MovedOn]; what remains is the
     *   window from that read to the first byte, which any check-then-act has.
     */
    private suspend fun prepareUploadRequest(id: String): PreparedRequest? {
        val record: UploaderItem? = ledger.get(id)
        if (record == null || record.state != UploaderItemState.IN_FLIGHT) {
            logger.i { "Upload attempt for id=$id has nothing owed (state=${record?.state})." }
            return null
        }
        val (handler: UploadHandler<Any>, payload: Any) = uploadHandlerAndPayload(record, "upload attempt")
            ?: return null
        val context = AttemptContext(
            id = record.id,
            attempts = record.attempts,
            uniqueKey = record.uniqueKey,
            orderingKey = record.orderingKey,
            claim = record.claim,
            wasDetached = true,
        )
        val preparation: UploadPreparation = try {
            handler.prepareUpload(context, payload)
        } catch (e: CancellationException) {
            // P5: a real cancellation of the worker is rethrown here, never charged.
            currentCoroutineContext().ensureActive()
            logger.e(e) { "prepareUpload leaked a cancellation for ${record.logName}." }
            spendAttempt(record, handler, e)
            return null
        } catch (e: Throwable) {
            // P5: a failure that arrives after the worker was stopped is that stop's, not the item's.
            currentCoroutineContext().ensureActive()
            logger.e(e) { "prepareUpload failed for ${record.logName}." }
            spendAttempt(record, handler, e)
            return null
        }
        // P5: a handler that swallowed the worker's cancellation and answered anyway claims, deletes and parks nothing.
        if (!currentCoroutineContext().isActive) {
            logger.i { "Upload attempt for ${record.logName} abandoned — its worker was cancelled while the upload was prepared." }
            currentCoroutineContext().ensureActive()
        }
        return when (preparation) {
            is UploadPreparation.Proceed -> {
                val current: UploaderItem? = ledger.get(id)
                if (current == null || current.state != UploaderItemState.IN_FLIGHT) {
                    logger.i { "Upload attempt for ${record.logName} settled or withdrawn while it was being prepared; nothing owed." }
                    PreparedRequest.NothingOwed
                } else if (current.attempts != record.attempts) {
                    logger.i { "Upload attempt for ${record.logName} charged while it was being prepared; the job retries." }
                    PreparedRequest.MovedOn
                } else {
                    // A changed claim alone is a re-hand-off the same job serves: proceed.
                    PreparedRequest.Prepared(
                        preparation.request,
                        record.attempts,
                        effectiveBodyLimit(current, handler),
                    )
                }
            }
            is UploadPreparation.Drop -> {
                deleteAfterEffect(record) // P7: the same rule as the drain's own Drop, though no effect ran here
                logger.i { "Dropped ${record.logName} at upload attempt — ${preparation.reason}." }
                trigger()
                null
            }

            is UploadPreparation.Park -> {
                parkAsRead(record, preparation.reason)
                logger.w { "Parked ${record.logName} at upload attempt — ${preparation.reason}." }
                trigger()
                null
            }
        }
    }

    /**
     * Charges a failed worker-side `prepareUpload` to the item as a failed attempt, exactly as the drain's
     * own failed `prepareUpload` is. Without this a preparation that keeps failing in the worker would
     * leave the row in flight until its lease ran out, again and again, with the retry budget never moving
     * and the give-up policy never reached. Guarded by the claim the attempt was read under, so a re-hand
     * that already superseded it wins; a store failure here only leaves the lease to recover the row.
     */
    private suspend fun spendAttempt(record: UploaderItem, handler: UploadHandler<Any>, cause: Throwable) {
        try {
            attemptRunner.recordRetry(record, handler, cause, expectedState = UploaderItemState.IN_FLIGHT, expectedClaim = record.claim)
            trigger()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e(e) { "Could not record the failed preparation of ${record.logName}; the lease will recover it." }
        }
    }

    /** Routes one progress signal to the handler — see [UploadGateway.progress]. Never parks or retries. */
    @Suppress("UNCHECKED_CAST")
    suspend fun reportUploadProgress(id: String, fraction: Float) {
        if (fraction.isNaN()) return // not a fraction of anything: dropped, where a number outside 0..1 is clamped
        val record: UploaderItem = try {
            ledger.get(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e(e) { "Progress store read failed for id=$id — dropping the signal." }
            return
        } ?: return
        if (record.state != UploaderItemState.IN_FLIGHT) return
        val handler: UploadHandler<Any> = handlersByType[record.type] as? UploadHandler<Any> ?: return
        val payload: Any = try {
            handler.decodePayload(record.payload)
        } catch (e: Throwable) {
            // A decoder's own CancellationException is a decode failure; real cancellation still propagates.
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            return // the settle path parks an undecodable payload; a progress hint just drops
        }
        runIsolated(record, "onUploadProgress") { handler.onUploadProgress(payload, fraction.coerceIn(0f, 1f)) }
    }

    /**
     * Classifies and settles an upload outcome — see [UploadGateway.complete].
     *
     * @return `false` when a store failure kept the outcome from landing; the executor keeps it and
     *   retries.
     */
    suspend fun settleUpload(
        id: String,
        result: UploadResult,
        response: UploadResponse? = null,
        onSettling: (() -> Unit)? = null,
    ): Boolean = settleUploadOutcome(id, result, response, null, onSettling) != JournalSettleOutcome.NOT_LANDED

    /**
     * Settles an answer the worker did not journal (a transport failure, a failed journal write) of the attempt
     * stamped [attemptStamp]; a report of an attempt the item has moved past is not charged to the new one.
     */
    internal suspend fun settleAttempt(
        id: String,
        result: UploadResult,
        response: UploadResponse?,
        attemptStamp: Int?,
    ): JournalSettleOutcome = settleUploadOutcome(id, result, response, attemptStamp, null)

    /**
     * Settles a journaled answer of the attempt stamped [attemptStamp] (`null` for an unstamped one): the
     * built-in transports' outcome journal. A throw out of this function means the commit did not land.
     * [onSettling] is invoked only for an unstamped answer.
     */
    internal suspend fun settleJournaled(
        id: String,
        response: UploadResponse,
        attemptStamp: Int?,
        onSettling: () -> Unit,
    ): JournalSettleOutcome =
        settleUploadOutcome(id, UploadResult.Completed(response.statusCode), response, attemptStamp, onSettling)

    /**
     * Settles [result] under the item's current claim — for an upload transport's job, which is keyed by
     * item id and therefore serves every claim of the item. See [settleUploadOutcome].
     *
     * @return `false` when a store failure kept the verdict from landing, so the job reports a retry
     *   instead of failing and leaving the item in flight until its lease runs out.
     */
    suspend fun settleUnderCurrentClaim(id: String, result: SettleResult): Boolean = try {
        val record: UploaderItem? = ledger.get(id)
        if (record == null) {
            logger.i { "settle($result) for unknown id=$id — no-op." }
        } else {
            settle(id, record.claim, result)
        }
        true
    } catch (e: CancellationException) {
        // The caller runs this inside NonCancellable, where a cancellation can only be the store's own.
        if (currentCoroutineContext().isActive) {
            logger.e(e) { "Settle($result) under the current claim for id=$id: the store leaked a cancellation; the job retries." }
            false
        } else {
            throw e
        }
    } catch (e: Throwable) {
        logger.e(e) { "Settle($result) under the current claim for id=$id failed — store failure; the job retries." }
        false
    }

    /**
     * The `attempts` of [id]'s row when it exists and is in flight right now (the item is owed an upload), else
     * `null`. A store failure reads as `null`: the caller leaves the item to its lease rather than upload on a guess.
     * Lets the classic transport's job decide whether a hand-off that arrived while it was finishing still asks for
     * an upload, and only at the attempt the hand-off was made for.
     */
    internal suspend fun attemptsIfInFlight(id: String): Int? = try {
        ledger.get(id)?.takeIf { it.state == UploaderItemState.IN_FLIGHT }?.attempts
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.e(e) { "In-flight check for id=$id failed — store failure; reading as not owed." }
        null
    }

    /** Whether the store holds an item of [id]; `null` when the store cannot say. */
    suspend fun knowsItem(id: String): Boolean? = try {
        ledger.get(id) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    /**
     * The body limit the handler of [id] enforces, or `null` when the item is gone, has no upload handler or
     * the store cannot say. Lets a transport hold the outcome journal to the same limit.
     */
    suspend fun responseBodyLimitOf(id: String): Int? = try {
        val record: UploaderItem? = ledger.get(id)
        val handler: UploadHandler<*>? = record?.let { handlersByType[it.type] as? UploadHandler<*> }
        @Suppress("UNCHECKED_CAST")
        if (record == null || handler == null) null else effectiveBodyLimit(record, handler as UploadHandler<Any>)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    /**
     * The one settle path of an upload outcome. Anything thrown means a settlement that did not land, so the
     * result is [JournalSettleOutcome.NOT_LANDED]; only a real cancellation of the caller propagates. A
     * cancellation thrown while the caller is still active is a store's own (its scope, a `withTimeout` in
     * `getById`): nothing was committed.
     */
    private suspend fun settleUploadOutcome(
        id: String,
        rawResult: UploadResult,
        rawResponse: UploadResponse?,
        attemptStamp: Int?,
        onSettling: (() -> Unit)?,
    ): JournalSettleOutcome = try {
        settleUploadUnguarded(id, rawResult, rawResponse, attemptStamp, onSettling)
    } catch (e: CancellationException) {
        if (currentCoroutineContext().isActive) {
            logger.e(e) { "Upload settle($rawResult) for id=$id: the store leaked a cancellation; not landed." }
            JournalSettleOutcome.NOT_LANDED
        } else {
            throw e
        }
    } catch (e: Throwable) {
        logger.e(e) { "Upload settle($rawResult) for id=$id failed — store failure; the executor retries." }
        JournalSettleOutcome.NOT_LANDED
    }

    /**
     * Settlements of one item's upload run one at a time in this engine: read the row, classify, check the
     * stamp, `onDelivered` and commit happen under the item's lock, so a second concurrent settlement (a
     * consumer's own transport reporting twice, a delegate racing a replay) finds the row the first one left
     * and cannot run `onSettled` twice, and a park or failure the commit did not apply
     * runs none. `deleteById` returns `Unit` in the public store contract, so only serialisation can tell the
     * second Delivered that it found nothing. Lock order: the outcome journal's item lock (outer, held by a worker
     * job), then this one (inner); the engine never takes the journal's, so there is no cycle. The `onSettled` hook runs after the lock is released.
     */
    private val uploadSettleLocks: ItemLocks = ItemLocks()

    /** What the locked part of an upload settlement decided, and the `onSettled` hook it leaves for after the lock. */
    private class LockedSettle(val outcome: JournalSettleOutcome, val settled: (suspend () -> Unit)?)

    private suspend fun settleUploadUnguarded(
        id: String,
        rawResult: UploadResult,
        rawResponse: UploadResponse?,
        attemptStamp: Int?,
        onSettling: (() -> Unit)?,
    ): JournalSettleOutcome {
        val locked: LockedSettle = uploadSettleLocks.withItem(id) {
            settleUploadLocked(id, rawResult, rawResponse, attemptStamp, onSettling)
        }
        val settled: (suspend () -> Unit)? = locked.settled
        if (settled != null) {
            // The commit landed and the caller must learn that; the job's cancelled state is kept for its next
            // suspension point.
            try {
                settled()
            } catch (e: CancellationException) {
                logger.i { "onSettled skipped for id=$id: stopped right after the commit." }
            }
        }
        return locked.outcome
    }

    private suspend fun settleUploadLocked(
        id: String,
        rawResult: UploadResult,
        rawResponse: UploadResponse?,
        attemptStamp: Int?,
        onSettling: (() -> Unit)?,
    ): LockedSettle {
        val record: UploaderItem? = ledger.get(id)
        if (record == null) {
            logger.i { "Upload settle($rawResult) for unknown id=$id — no-op." }
            return LockedSettle(JournalSettleOutcome.LANDED, null)
        }
        val (handler: UploadHandler<Any>, payload: Any) = uploadHandlerAndPayload(record, "upload settle")
            ?: return LockedSettle(JournalSettleOutcome.LANDED, null)
        // The one place the body limit is enforced; with a response, the result is built from it so the
        // two cannot disagree.
        val response: UploadResponse? = rawResponse?.enforced(effectiveBodyLimit(record, handler))
        val result: UploadResult = if (response != null) UploadResult.Completed(response.statusCode) else rawResult
        // classify is feature code: a leaked cancellation or a throw becomes a Failed settlement,
        // never a lost outcome.
        val settleResult: SettleResult = try {
            handler.classify(payload, result, response)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            SettleResult.Failed(e)
        } catch (e: Throwable) {
            SettleResult.Failed(e)
        }
        // A stamped answer is stale when the row has moved past its attempt (a commit for it, or a later
        // charge, already landed) or is parked. Deliveries and drops are facts and stand; a Failed or Park
        // verdict would charge or park the wrong attempt, so it is not applied.
        val stale: Boolean =
            attemptStamp != null && (record.attempts != attemptStamp || record.state == UploaderItemState.PARKED)
        if (stale && settleResult !is SettleResult.Delivered && settleResult !is SettleResult.Drop) {
            logger.i {
                "journaled answer of attempt $attemptStamp for ${record.logName} is stale " +
                    "(item at ${record.attempts}, ${record.state}); not applied"
            }
            return LockedSettle(JournalSettleOutcome.STALE, null)
        }
        if (settleResult is SettleResult.Delivered) {
            // Before the row settles, isolated: the upload already landed, and a local failure must
            // not make it upload again.
            runIsolated(record, "onDelivered") { handler.onDelivered(payload, response) }
        }
        // Under the item's current claim: an upload transport keys its platform job by item id, so every
        // claim of the item is served by this same job, and its report is about the attempt now held.
        // A stamped answer has no pre-commit delete (its entry is deleted by the journal after this returns,
        // and a leftover is recognised by its stamp); an unstamped one is deleted by onSettling right before
        // the commit, so it cannot replay into a re-handed attempt. The server already answered, so the commit
        // is a short write of a known fact that a stop must not split from the delete (D-21: NonCancellable is
        // the high-level tool here, as in the classic worker's settle). Anything thrown inside this block,
        // a store's CancellationException included, is a commit that did not land: inside NonCancellable a
        // cancellation can only be the store's own. classify, onDelivered and onSettled stay cancellable:
        // they are feature code.
        val applied: Boolean? = withContext(NonCancellable) {
            try {
                onSettling?.invoke()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            try {
                commitSettle(id, record.claim, settleResult)
            } catch (e: Throwable) {
                logger.e(e) { "Upload settle commit for ${record.logName} failed; not landed." }
                null
            }
        }
        // A delivery or drop is a fact about the effect: the row was present at this settlement's locked read, so the
        // verdict stands even if a withdrawal deleted the row before the commit (commitSettle then reports false). Its
        // onSettled must still pair with the onDelivered already run. The lock keeps it at most once: a second
        // settlement of this item reads no row and returns before any hook. A Park or Failed the commit did not apply
        // ran nothing.
        val stands: Boolean? = when (applied) {
            null -> null
            true -> true
            false -> settleResult is SettleResult.Delivered || settleResult is SettleResult.Drop
        }
        return when (stands) {
            null -> LockedSettle(JournalSettleOutcome.NOT_LANDED, null)
            false -> {
                logger.i { "settle of ${record.logName} superseded at commit; nothing applied, no onSettled" }
                LockedSettle(JournalSettleOutcome.STALE, null)
            }

            true -> {
                if (applied == false) {
                    logger.i {
                        "${record.logName} was withdrawn while settling as $settleResult; the verdict stands, onSettled runs"
                    }
                }
                LockedSettle(JournalSettleOutcome.LANDED) {
                    runIsolated(record, "onSettled") { handler.onSettled(payload, record.attempts, settleResult) }
                }
            }
        }
    }

    /** The handler's body limit as enforced, logging a negative value once per handler type. */
    private suspend fun effectiveBodyLimit(record: UploaderItem, handler: UploadHandler<Any>): Int {
        // The getter is feature code like classify: a throw must not make an accepted upload settle as a
        // store failure and upload again, so it falls back to the default limit.
        val configured: Int = try {
            handler.maxResponseBodyBytes
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            UploadHandler.DEFAULT_MAX_RESPONSE_BODY_BYTES
        } catch (e: Throwable) {
            logger.e(e) { "${record.type}: maxResponseBodyBytes threw; using the default limit." }
            UploadHandler.DEFAULT_MAX_RESPONSE_BODY_BYTES
        }
        if (configured < 0 && record.type !in negativeLimitLogged.value) {
            negativeLimitLogged.update { it + record.type }
            logger.w { "${record.type}: maxResponseBodyBytes is $configured; treated as 0 (no body captured)." }
        }
        return configured.coerceIn(0, MAX_RESPONSE_BODY_BYTES)
    }

    private val negativeLimitLogged: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    /**
     * The upload handler for [record] and its decoded payload, or `null` when the gateway has nothing to do
     * with the row. A row whose type has a plain [UploaderHandler] is not an upload item: it is left exactly as
     * it is (its own executor settles it through [settle], and a lapsed lease re-hands it), so a stray or
     * outdated gateway call cannot strand it. A row whose type has no handler at all, or whose payload cannot be
     * decoded, is parked as read: a wiring or migration bug to keep visible, parked the same way the drain parks it.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun uploadHandlerAndPayload(record: UploaderItem, phase: String): Pair<UploadHandler<Any>, Any>? {
        val registered: UploaderHandler<*>? = handlersByType[record.type]
        val handler: UploadHandler<Any> = registered as? UploadHandler<Any> ?: run {
            if (registered != null) {
                // A plain handler's item: not this channel's, so nothing is written (the store row, its lease and
                // its claim stay as they are).
                logger.w { "Ignored $phase for ${record.logName} — its type has a plain UploaderHandler, not an UploadHandler." }
                return null
            }
            parkAsRead(record, "$phase: no handler registered for type '${record.type}'")
            logger.e { "Parked ${record.logName} — $phase for a type with no handler." }
            return null
        }
        val payload: Any = try {
            handler.decodePayload(record.payload)
        } catch (e: Throwable) {
            // A decoder's own CancellationException is a decode failure; real cancellation still propagates.
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            parkAsRead(record, "undecodable payload on $phase: ${describeFailure(e)}")
            logger.e(e) { "Parked ${record.logName} — undecodable payload on $phase." }
            return null
        }
        return handler to payload
    }

    /** Runs a handler hook, logging and continuing on failure while honouring real cancellation. */
    private suspend fun runIsolated(record: UploaderItem, hook: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            logger.e(e) { "$hook leaked a cancellation for ${record.logName}." }
        } catch (e: Throwable) {
            logger.e(e) { "$hook failed for ${record.logName}." }
        }
    }

    /**
     * Whether the engine is permanently inert: [close] has run, or the scope it was created in is cancelled.
     * Read by [UploaderEngineRegistry] so such an engine is never handed out.
     */
    internal val isClosed: Boolean
        get() = lifecycle.isClosed

    override fun close() {
        lifecycle.close()
    }
}

/** `a + b`, held at the `Long` range instead of wrapping: a gate or lease near `Long.MAX_VALUE` stays far away. */
internal fun saturatingAdd(a: Long, b: Long): Long {
    val sum: Long = a + b
    // Overflow happened exactly when both operands share a sign the sum does not.
    return if (((a xor sum) and (b xor sum)) < 0) (if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE) else sum
}

/** `a - b`, held at the `Long` range instead of wrapping: a gate far ahead of a very negative clock stays far ahead. */
internal fun saturatingSubtract(a: Long, b: Long): Long {
    val difference: Long = a - b
    // Overflow happened exactly when the operands differ in sign and the result's sign is not a's.
    return if (((a xor b) and (a xor difference)) < 0) (if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE) else difference
}

/** `a * b` for non-negative operands, held at `Long.MAX_VALUE` instead of wrapping. */
internal fun saturatingMultiply(a: Long, b: Long): Long =
    if (a != 0L && b > Long.MAX_VALUE / a) Long.MAX_VALUE else a * b
