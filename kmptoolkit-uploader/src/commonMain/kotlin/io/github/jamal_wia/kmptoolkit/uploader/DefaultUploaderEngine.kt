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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
 * This class is the facade: it keeps every name the platform entry points and the tests call (each forwards, with an
 * unchanged signature), wires the components and runs the drain pass itself (the scheduling of channels, gates,
 * alarms and outside failures). It holds no store, no transport call and no settle logic. The store is out of reach
 * **by construction** (the constructor hands it to the ledger and keeps nothing); upload cancels, upload launches and
 * the process-wide memories are kept to their owners by the review gate below, not by the compiler:
 *
 * | Component | Owns | Rules |
 * |---|---|---|
 * | [RowLedger] | the only holder of the [UploaderStore] and the [TransactionRunner]; the only code that touches [DeliveredUnconfirmed] and [ClaimsInDoubt] | P2 (fact writes), P3, P7, P10; serves P1 (the row writes) |
 * | [Withdrawals] | the side effects of a removed row; the only caller of `UploadTransport.cancel` | P1 order, P8 |
 * | [AttemptRegistry] | the attempts the drain runs and their handshake with a withdrawal | P11 (serves P1, P2) |
 * | [AttemptRunner] | one admitted attempt: the handler run, the upload hand-off (the engine's only launch of an upload), the decision what an answer means; decides, never writes | P4, P5 |
 * | [SettleDesk] | `settle` and its commit, the upload settle path and journal entry points, the worker-side preparation, classification and the handler's hooks, the transports' small reads | verdict staleness, D-21 |
 * | [EngineLifecycle] | scope and job, `start` and `close`, the drain loop's channel, the alarm, the stop job of a manual drain, the registry entry, the release wake | P6, D-31 |
 * | [DrainCall] | the turns of one call of `drain()` | P9 |
 *
 * The review gate: `rg 'store\.'`, `rg 'transport\.'` and `rg 'DeliveredUnconfirmed\.|ClaimsInDoubt\.'` over this
 * file find nothing outside KDoc, and over the whole of `commonMain` `transport.cancel` appears only in [Withdrawals]
 * and the engine's launch of an upload (`launchAttempt` of the hand-off) only in [AttemptRunner]. (An [UploadHandler]'s
 * own `execute` launches the classic path's transport itself; that is the handler's code, not the engine's.)
 *
 * Owner of each rule, exactly one: P1 [Withdrawals], P2 [RowLedger], P3 [RowLedger], P4 [AttemptRunner], P5
 * [AttemptRunner], P6 [EngineLifecycle], P7 [RowLedger], P8 [Withdrawals], P9 [DrainCall], P10 [RowLedger], P11
 * [AttemptRegistry]. A component that is not the owner only calls it, supplies the state it reads, or carries the
 * rule's comment at one more call site: the ledger's row writes behind P1, [EngineLifecycle.isClosed] behind P4, the
 * one turn check of this class's pass for P9, the one skip of this class's pass for P11, the worker-side preparation
 * of [SettleDesk] for P5, and [AttemptRunner] choosing which answers are facts for P2.
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
 *   turn for it (for an in-flight row, once its lease has ended) only retries the delete, and the call that reported
 *   the effect still throws the store's failure (a repeated `settle` is harmless). The funnel is for a row whose
 *   effect is a fact; a withdrawal (P8) and the drain's own confirming retry are not calls to it: the first never
 *   remembers or forgets, the second is the retry itself.
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
 *   drain asks nothing else of a held row before it retries its delete: the check for it follows the gates (an
 *   unexpired lease of an in-flight row, the in-memory backoff of a failure outside the handler) and the turn and
 *   precedes the handler lookup, the constraints and the backoff gate. The order of the two does not matter: a
 *   verdict that landed **first** (the row is PARKED, out of the active list the drain reads) is rescued too, because
 *   a row held as delivered is confirmed in whatever state it is: once per call, ahead of the empty-queue check,
 *   [RowLedger.heldOutOfRotation] names the parked held rows and the drain retries their delete under the same turn
 *   and backoff rules, keeps an alarm armed for them when the queue is otherwise empty (the platform wake stays
 *   disarmed: the memory dies with the process), and `settle` triggers a pass even when its delete throws. The sweep
 *   reads one row per remembered entry, except an entry whose row it found absent: that one is read again only once
 *   per [UploaderConfig.heartbeatInterval] (at the first sweep, and at once when the clock steps backwards), so a
 *   withdrawn row or another store's row costs a read per heartbeat and not one per call. A row that comes back after
 *   it read absent is therefore rescued within two heartbeat intervals of the last re-check (the cadence reads the
 *   clock at the start of a pass and the heartbeat is a monotonic delay, so a tick can fall just short of one interval
 *   and leave the re-check to the next); until then it is PARKED, which is not run, and a verdict on it is refused.
 *   *Pinned by:* EngineBossRound4SuspectsAdversarialTest (bv4-S3a, S3b), EngineSplit1P10AdversarialTest and
 *   EngineSplit1HeldRescueAdversarialTest (a verdict that came first).
 * - **P11, One attempt per item.** The engine never starts an attempt for an item while it is still running one for
 *   that item, whichever pass asks: a `drain()` called from inside the attempt (a handler, an upload preparation), two
 *   manual calls, or a manual call next to the engine's own loop. [AttemptRegistry.runUnlessMoved] registers an attempt
 *   only when none is registered for the id, in one atomic step, and answers [Admission.BUSY] otherwise; the pass that
 *   asked skips the item in [drainPass] without using its turn (P9), arming a gate or counting it as work, and the
 *   pass that owns the attempt sweeps again after it ends or, when that pass is cancelled, the next trigger or
 *   heartbeat does. So the registry holds at most one entry per id and a
 *   withdrawal (P1, P2) always finds the attempt that is running. It does not make an overlapping `drain()` safe for
 *   everything else: a manual `drain()` on a started engine still breaks single-flight, and two engines over one store
 *   stay the P6 residual.
 *   *Pinned by:* RegressReentrantDrainAdversarialTest and RegressReentrancyAdversarialTest.
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

    /** What an executor or a transport reports back: settle, the upload settle path, the worker-side preparation. */
    private val settleDesk: SettleDesk = SettleDesk(ledger, handlersByType, attemptRunner, this.logger, lifecycle::trigger)

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
        settleDesk.settle(id, claim, result)
    }

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
                // handler, whether its constraints hold or its backoff gate has opened are questions about running the
                // effect again, and a delete needs none of them.
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
                        ledger.park(item, "no handler registered for type '${item.type}'")
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
                    val admission: Admission = attemptRegistry.runUnlessMoved(item) { attempt -> attemptRunner.executeItem(item, handler, attempt) }
                    // P11: another pass is running this item's attempt. This one skips it without spending the turn,
                    // arming a gate or counting it as work; the pass that owns the attempt sweeps again after it ends.
                    if (admission == Admission.BUSY) continue
                    outsideFailures.update { it - item.id }
                    if (admission == Admission.RAN) {
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

    /** When the held sweep last re-read the entries it had found absent (`null`: never), see [takeHeldRecheck]. */
    private val lastHeldRecheckMillis: MutableStateFlow<Long?> = MutableStateFlow(null)

    /**
     * Whether this sweep re-reads the remembered entries whose row it found absent: at the first sweep, once per
     * [UploaderConfig.heartbeatInterval] after, and at once when the clock reads earlier than the last re-check (a step
     * backwards). A manual drain racing the engine's loop may cost one extra full sweep.
     */
    private fun takeHeldRecheck(): Boolean {
        val now: Long = clock.nowEpochMillis()
        val heartbeat: Long = config.heartbeatInterval.inWholeMilliseconds
        var due = false
        lastHeldRecheckMillis.update { last ->
            due = last == null || now < last || saturatingSubtract(now, last) >= heartbeat
            if (due) now else last
        }
        return due
    }

    /** What [sweepHeldOutOfRotation] found: the rows' [ids] and the soonest [gate] a delete is worth another try, if any. */
    private class HeldSweep(val ids: Set<String>, val gate: Long?)

    /**
     * Rule P7 for the rows [drainPass]'s active list never offers: a row this process holds as delivered that a verdict
     * parked (before the delivery's delete failed, or while it was failing) is still owed its delete, whatever its
     * state. Runs once per call, under the same turn and backoff rules as an active held row; the entries it found
     * absent are re-read once per heartbeat ([takeHeldRecheck]).
     */
    private suspend fun sweepHeldOutOfRotation(call: DrainCall): HeldSweep {
        val held: List<UploaderItem> = ledger.heldOutOfRotation(recheckAbsent = takeHeldRecheck())
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

    /** See [SettleDesk.prepareUploadAttempt]. */
    suspend fun prepareUploadAttempt(id: String): UploadAttempt = settleDesk.prepareUploadAttempt(id)

    /** See [SettleDesk.prepareStampedUploadAttempt]. */
    internal suspend fun prepareStampedUploadAttempt(id: String): PreparedUpload =
        settleDesk.prepareStampedUploadAttempt(id)

    /** See [SettleDesk.reportUploadProgress]. */
    suspend fun reportUploadProgress(id: String, fraction: Float) {
        settleDesk.reportUploadProgress(id, fraction)
    }

    /** See [SettleDesk.settleUpload]. */
    suspend fun settleUpload(
        id: String,
        result: UploadResult,
        response: UploadResponse? = null,
        onSettling: (() -> Unit)? = null,
    ): Boolean = settleDesk.settleUpload(id, result, response, onSettling)

    /** See [SettleDesk.settleAttempt]. */
    internal suspend fun settleAttempt(
        id: String,
        result: UploadResult,
        response: UploadResponse?,
        attemptStamp: Int?,
    ): JournalSettleOutcome = settleDesk.settleAttempt(id, result, response, attemptStamp)

    /** See [SettleDesk.settleJournaled]. */
    internal suspend fun settleJournaled(
        id: String,
        response: UploadResponse,
        attemptStamp: Int?,
        onSettling: () -> Unit,
    ): JournalSettleOutcome = settleDesk.settleJournaled(id, response, attemptStamp, onSettling)

    /** See [SettleDesk.settleUnderCurrentClaim]. */
    suspend fun settleUnderCurrentClaim(id: String, result: SettleResult): Boolean =
        settleDesk.settleUnderCurrentClaim(id, result)

    /** See [SettleDesk.attemptsIfInFlight]. */
    internal suspend fun attemptsIfInFlight(id: String): Int? = settleDesk.attemptsIfInFlight(id)

    /** See [SettleDesk.knowsItem]. */
    suspend fun knowsItem(id: String): Boolean? = settleDesk.knowsItem(id)

    /** See [SettleDesk.responseBodyLimitOf]. */
    suspend fun responseBodyLimitOf(id: String): Int? = settleDesk.responseBodyLimitOf(id)

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
