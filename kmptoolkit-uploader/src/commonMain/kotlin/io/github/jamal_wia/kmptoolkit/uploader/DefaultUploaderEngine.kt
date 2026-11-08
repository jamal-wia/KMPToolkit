package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.uploader.spi.WakeScheduler
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The engine implementation. See [UploaderEngine] for the contract and [createUploaderEngine] for how
 * it is built; this class is internal so the public surface stays an interface plus a factory.
 *
 * Three structural decisions are worth knowing when reading it:
 *
 * - **Single-flight through a conflated channel.** [start] launches exactly one drain coroutine
 *   that reads [triggerChannel]. Every wake-up path — enqueue, constraint transition, alarm,
 *   heartbeat, an explicit [trigger] — sends to that channel, so a burst collapses into one pass
 *   and two drains never overlap.
 * - **Backoff lives in the rows, not in memory.** A failed attempt persists its attempt count and
 *   its next-run gate; the drain skips gated rows and arms a one-shot alarm for the soonest one.
 *   The drain never sleeps while holding the queue, and a retry budget survives process death.
 * - **Attempts run in a child coroutine.** The drain runs each attempt in a child of its own, so a withdrawal
 *   ([cancel], [cancelByTag], a REPLACE) can cancel the running attempt of the item it removes.
 * - **Its own child scope.** Everything is launched in [engineScope], a child of the caller's
 *   scope, so [close] can stop the engine without cancelling a scope it does not own — while
 *   cancelling the caller's scope still stops the engine.
 */
internal class DefaultUploaderEngine(
    private val store: UploaderStore,
    handlers: List<UploaderHandler<*>>,
    scope: CoroutineScope,
    private val constraintProviders: List<ConstraintProvider>,
    private val transactionRunner: TransactionRunner,
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

    private val providersByKey: Map<String, ConstraintProvider> =
        constraintProviders.associateBy { it.key }.also { byKey ->
            require(byKey.size == constraintProviders.size) {
                "Duplicate ConstraintProvider keys: " +
                    constraintProviders.groupBy { it.key }.filterValues { it.size > 1 }.keys
            }
        }

    private val engineJob: Job = SupervisorJob(scope.coroutineContext[Job])

    private val engineScope: CoroutineScope = CoroutineScope(scope.coroutineContext + engineJob)

    init {
        // An engine whose owning scope is cancelled without close() is as dead as a closed one: leaving it in
        // the registry would hand a drain that never runs to every platform entry point, and keep the engine,
        // its store and its handlers reachable from a global.
        engineJob.invokeOnCompletion { UploaderEngineRegistry.unregister(this) }
    }

    /** Conflated wake-up channel: many [trigger] calls collapse into a single pending signal. */
    private val triggerChannel: Channel<Unit> = Channel(capacity = Channel.CONFLATED)

    /** One-shot backoff alarm. Touched only from the single drain coroutine, so no guard needed. */
    private var alarmJob: Job? = null

    private val started: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val closed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override fun start() {
        if (closed.value) {
            logger.w { "start() on a closed uploader engine — ignored." }
            return
        }
        if (!started.compareAndSet(expect = false, update = true)) return

        engineScope.launch {
            // Local to this one coroutine, so it needs no synchronisation.
            var consecutiveFailures = 0
            while (true) {
                triggerChannel.receive()
                // The engine's whole liveness rests on this one coroutine, so nothing may kill it.
                // A transient store failure, or a CancellationException leaked by a handler's own
                // withTimeout, would otherwise stall the queue forever while the heartbeat keeps
                // feeding a channel nobody reads. Real cancellation still propagates via
                // ensureActive(). A pass that fails re-arms itself with a growing delay, so a locked
                // database is retried in milliseconds rather than at the next heartbeat.
                try {
                    drain()
                    consecutiveFailures = 0
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    logger.e(e) { "Drain aborted by a leaked cancellation; retrying shortly." }
                    consecutiveFailures++
                    scheduleAlarm(saturatingAdd(clock.nowEpochMillis(), failedPassRetryMillis(consecutiveFailures)))
                } catch (e: Throwable) {
                    logger.e(e) { "Drain failed; queue intact, retrying shortly." }
                    consecutiveFailures++
                    scheduleAlarm(saturatingAdd(clock.nowEpochMillis(), failedPassRetryMillis(consecutiveFailures)))
                }
            }
        }

        constraintProviders.forEach { provider ->
            // Built inside flow {} so that even a `satisfied` getter that throws is caught here, and ending in
            // catch so a flow that fails on collect never reaches the scope's uncaught-exception handler. The
            // subscription is only a latency optimisation: every pass reads the value (guarded) and the
            // heartbeat keeps triggering passes, so a subscription that ended costs latency, never work.
            flow { emitAll(provider.satisfied) }
                .filter { it }
                .onEach {
                    logger.d { "Constraint '${provider.key}' satisfied — triggering a drain." }
                    trigger()
                }
                .catch { e ->
                    logger.e(e) {
                        "Constraint '${provider.key}' stopped emitting; its items are still checked on every pass and heartbeat."
                    }
                }
                .launchIn(engineScope)
        }

        engineScope.launch {
            while (true) {
                delay(config.heartbeatInterval)
                trigger()
            }
        }

        trigger() // pick up whatever the previous process left behind
    }

    override fun trigger() {
        triggerChannel.trySend(Unit)
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
        // The item a REPLACE supersedes comes back from the replacement itself rather than from a read
        // made first: under a TransactionRunner that is not a real transaction (Direct, the default), an
        // item enqueued between such a read and the replacement would lose its row without its upload
        // ever being cancelled. One handed off before the replacement lands is cancelled here; one whose
        // hand-off is still launching cancels itself, finding its row gone right after the launch.
        var superseded: UploaderItem? = null
        // A caller already cancelled when the write would start writes nothing. Once the write has
        // started it is made non-cancellable: a store that notices a cancelled caller (one that hops to a
        // database thread, say) would otherwise throw after committing, and the superseded item's attempt
        // and upload would be left running for a row that is gone.
        currentCoroutineContext().ensureActive()
        val inserted: Boolean = try {
            transactionRunner.inTransaction {
                withContext(NonCancellable) {
                    when (conflictPolicy) {
                        ConflictPolicy.KEEP -> store.insertKeep(record)
                        ConflictPolicy.REPLACE -> {
                            superseded = store.insertReplace(record)
                            true
                        }
                    }
                }
            }
        } finally {
            // Persist-first for the wake too: even if the process dies before the drain runs, the
            // platform wake is already armed for the debt that was just persisted. This sits in a finally
            // because a caller cancelled (or a store failing) after the row committed but before the call
            // returned cannot tell whether the row landed, and a committed row with no armed wake and no
            // trigger would wait for some unrelated one. Arming for a row that did not land is harmless:
            // the next drain pass finds the queue empty and disarms. Neither call suspends or throws.
            trigger()
            wakeScheduler.scheduleWake()
            // The superseded item's side effects sit in the same finally, after the write: a real
            // transaction runner reports the caller's cancellation on return even though the transaction
            // committed, and the stop and the cancel must still happen. A parked item has no upload to
            // cancel; only an upload handler's items have one at all. Whatever the handler kind, an attempt
            // the engine is running for the superseded item is stopped. None of this suspends.
            superseded?.let { old ->
                stopAttempt(old.id)
                if (handler is UploadHandler<*> && old.state != UploaderItemState.PARKED) {
                    cancelUpload(old, reason = "superseded by ${record.logName}")
                }
            }
        }
        if (inserted) {
            logger.d { "Enqueued ${record.logName}." }
        } else {
            logger.d { "Enqueue of ${record.logName} kept the already-queued item." }
        }
        return record.id.takeIf { inserted }
    }

    override fun observe(type: String): Flow<List<UploaderItem>> = store.observeByType(type)

    override suspend fun cancel(id: String) {
        val record: UploaderItem? = store.getById(id)
        if (record == null) {
            logger.i { "cancel for unknown id=$id — no-op." }
            return
        }
        // A caller cancelled before the write starts removes nothing; once it has started, the write and
        // both side effects run to the end whatever happens to the caller (a cleared viewModelScope during
        // a logout wipe, say).
        currentCoroutineContext().ensureActive()
        try {
            // Row first: a transport job that starts after this finds nothing owed.
            withContext(NonCancellable) { store.deleteById(id) }
        } finally {
            // Also after a store failure: a delete that committed and then failed must not orphan the
            // upload. One that did not commit costs what a rolled-back withdrawal costs: the attempt re-runs
            // on the next pass and the upload resumes at its lease. None of this suspends.
            stopAttempt(id)
            cancelUpload(record, reason = "withdrawn")
            trigger() // an emptied queue lets the drain disarm the wake
        }
    }

    override suspend fun cancelByTag(tag: String) {
        // The rows come back from the wipe itself rather than from a read made first: an item enqueued
        // and handed off between such a read and the delete would lose its row while its upload ran on —
        // after a logout wipe, under the next account. Parked items have no upload to cancel.
        // A caller cancelled before the wipe starts removes nothing; once started, the wipe is
        // non-cancellable so its list always comes back and every stop and cancel below runs.
        currentCoroutineContext().ensureActive()
        val removed: List<UploaderItem> = withContext(NonCancellable) { store.deleteByTag(tag) }
        removed.forEach { record -> stopAttempt(record.id) }
        val owed: List<UploaderItem> = removed.filter { it.state != UploaderItemState.PARKED }
        owed.forEach { record -> cancelUpload(record, reason = "withdrawn by tag") }
        logger.i { "Withdrew every item tagged '$tag' (${owed.size} active)." }
        trigger()
    }

    /**
     * Cancels [record]'s upload when its handler is an [UploadHandler], whose transport the engine
     * knows. Called for any state: cancelling an id the transport is not running is a no-op, and a
     * pending item may be mid-hand-off.
     */
    private fun cancelUpload(record: UploaderItem, reason: String) {
        val handler: UploadHandler<*> = handlersByType[record.type] as? UploadHandler<*> ?: run {
            logger.i { "Withdrew ${record.logName} — $reason." }
            return
        }
        try {
            handler.transport.cancel(record.id)
            logger.i { "Withdrew ${record.logName} and cancelled its upload — $reason." }
        } catch (e: Throwable) {
            // The row is already gone; a transport that throws must not fail the caller's withdrawal.
            logger.e(e) { "Withdrew ${record.logName}, but cancelling its upload failed — $reason." }
        }
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
        store.getAllActive().isEmpty()
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
        val record: UploaderItem? = store.getById(id)
        if (record == null) {
            // Late, duplicate, or superseded settle — the debt is no longer this executor's.
            logger.i { "settle($result) for unknown id=$id — no-op." }
            return false
        }
        val applied: Boolean = when (result) {
            // Facts about the effect, so they stand from any claim: a superseded executor that delivered
            // has still delivered, and re-handing the item would send it twice.
            is SettleResult.Delivered -> {
                store.deleteById(id)
                logger.i { "Settled ${record.logName} as delivered." }
                true
            }

            is SettleResult.Drop -> {
                store.deleteById(id)
                logger.i { "Settled ${record.logName} as dropped — ${result.reason}." }
                true
            }

            // Verdicts on one attempt, so they stand only from the attempt the engine still runs.
            is SettleResult.Park -> if (record.isCurrentFor(claim, result)) {
                val parked: Boolean = store.park(id, boundedFailureText(result.reason), record.state, record.claim)
                if (parked) {
                    logger.w { "Settled ${record.logName} as parked — ${result.reason}." }
                } else {
                    logger.i { "Park settle for ${record.logName} superseded mid-settle — no-op." }
                }
                parked
            } else {
                false
            }

            is SettleResult.Failed -> if (record.isCurrentFor(claim, result)) settleFailed(record, result) else false
        }
        // Delivered may have emptied the queue (let the drain disarm the wake); Failed armed a
        // backoff gate (let the drain schedule an alarm for it).
        trigger()
        return applied
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
        return recordRetry(record = record, handler = handler, cause = result.cause)
    }

    /**
     * Parks [record] if it is still in the state and claim it was read in. Every park the engine makes
     * follows a read, and a settle can land between the two on an executor's own coroutine.
     */
    private suspend fun parkAsRead(record: UploaderItem, reason: String?): Boolean =
        store.park(record.id, boundedFailureText(reason), record.state, record.claim)

    override suspend fun drain() {
        // Per call of drain(): ids whose attempt already ran in it, and ids already skipped once as "moved". An
        // item gets one turn per pass of work; a second turn waits for the next trigger or alarm, so neither a
        // zero backoff nor a store whose point read disagrees with its list read can keep one call spinning.
        val ranThisDrain: MutableSet<String> = HashSet()
        val movedThisDrain: MutableSet<String> = HashSet()
        while (true) {
            // A store whose calls never suspend gives cancellation no other place to be seen: without this a
            // close() (or a cancelled scope) from inside a handler would not stop the pass.
            currentCoroutineContext().ensureActive()
            val active: List<UploaderItem> = store.getAllActive()
            pruneOutsideFailures(active)
            if (active.isEmpty()) {
                cancelAlarm()
                wakeScheduler.cancelWake() // nothing owed — disarm the platform wake
                // An enqueue that landed between the read above and that cancel has already armed its
                // wake, and the cancel has just disarmed it: a process killed before the next pass would
                // then keep the item until the next launch. So the cancel only stands if the queue is
                // still empty after it. Any enqueue landing later arms its wake after the cancel.
                if (store.getAllActive().isEmpty()) return
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
                val handler: UploaderHandler<*>? = handlersByType[item.type]
                if (handler == null) {
                    // Either a wiring bug or a payload from a build that had the type. Parking
                    // keeps the item visible instead of failing forever or deleting a user's data.
                    parkAsRead(item, "no handler registered for type '${item.type}'")
                    logger.e { "Parked ${item.logName} — no handler for its type." }
                    executedAny = true
                    continue
                }
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
                // An attempt that failed outside its handler (a store write failing after the effect ran, a
                // consumer policy throwing) waits out a growing backoff kept in memory, across passes: the
                // same effect must not be run again at the alarm floor, pass after pass.
                val outside: OutsideFailure? = outsideFailures.value[item.id]
                if (outside != null && !outside.isDue(nowMillis)) {
                    soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, outside.notBeforeMillis)
                    continue
                }
                if (outside != null && outside.deliveredUnconfirmed) {
                    // The effect succeeded in this process and only its confirming delete failed: repeating the
                    // effect would send it twice, so only the delete is retried.
                    try {
                        store.deleteById(item.id)
                        outsideFailures.update { it - item.id }
                        logger.i { "Confirmed the delivery of ${item.logName} after its delete failed earlier." }
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        logger.e(e) { "Confirming the delivery of ${item.logName} leaked a cancellation; retrying later." }
                        soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, bumpOutsideFailure(item.id))
                    } catch (e: Throwable) {
                        logger.e(e) { "Confirming the delivery of ${item.logName} failed again; retrying later." }
                        soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, bumpOutsideFailure(item.id))
                    }
                    executedAny = true
                    continue
                }
                // One item's failure must not abort the pass: the items behind it would not run, no alarm
                // would be armed for their gates, and the same row would be first again on the next trigger.
                if (item.id in ranThisDrain) {
                    // It already ran in this call and is due again at once: a retry whose policy returned no delay.
                    // Its next try waits for the next trigger or alarm instead of re-running in this loop.
                    soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, saturatingAdd(nowMillis, failedPassRetryMillis(1)))
                    continue
                }
                try {
                    val ran: Boolean = attemptUnlessMoved(item) { attempt -> executeItem(item, handler, attempt) }
                    outsideFailures.update { it - item.id }
                    if (ran) {
                        ranThisDrain += item.id
                    } else if (!movedThisDrain.add(item.id)) {
                        // Skipped as "moved" twice in one call although nothing else changed it: the store's point
                        // read keeps disagreeing with its list read. Leave the item alone for now and say so; the
                        // alarm asks again, and a consistent store will have caught up by then.
                        logger.w { "Skipped ${item.logName} again — the store's getById differs from its getAllActive." }
                        soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, saturatingAdd(nowMillis, failedPassRetryMillis(1)))
                        continue
                    }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    logger.e(e) { "Attempt of ${item.logName} leaked a cancellation; retrying later." }
                    bumpOutsideFailure(item.id)
                } catch (e: Throwable) {
                    logger.e(e) { "Attempt of ${item.logName} failed outside its handler; retrying later." }
                    bumpOutsideFailure(item.id)
                }
                executedAny = true
            }

            if (!executedAny) {
                scheduleAlarm(soonestGate)
                // Work remains, gated or backing off — keep the platform wake armed so a killed
                // process still gets to deliver it.
                wakeScheduler.scheduleWake()
                return
            }
        }
    }

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
     * The engine never writes a lease beyond [MAX_LEASE_MILLIS] (see [clampLease]), so one further ahead than
     * [UploaderConfig.clockAnomalyFactor] times that was written before the wall clock stepped **backwards**.
     * Honouring it would freeze the item, and its ordering channel, for the whole jump; it is treated as
     * expired instead and the item is re-handed (a hand-off is idempotent by contract), which writes a sane
     * lease.
     */
    private fun isClockAnomalyLease(leaseUntilEpochMillis: Long, nowMillis: Long): Boolean {
        val reachable: Long = saturatingMultiply(MAX_LEASE_MILLIS, config.clockAnomalyFactor.toLong())
        val ahead: Long = saturatingSubtract(leaseUntilEpochMillis, nowMillis)
        val anomaly: Boolean = ahead > reachable
        if (anomaly) {
            logger.w {
                "Lease ${ahead}ms ahead exceeds the longest lease the engine " +
                    "writes (${MAX_LEASE_MILLIS}ms) — treating it as a wall-clock jump and re-handing the item."
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

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeItem(item: UploaderItem, handler: UploaderHandler<*>, attempt: RunningAttempt) {
        if (item.schemaVersion > handler.schemaVersion) {
            // Written by a future app version and then downgraded from; mis-decoding it could send
            // something wrong.
            parkAsRead(item, "payload schema v${item.schemaVersion} > handler v${handler.schemaVersion}")
            logger.w { "Parked ${item.logName} — its payload comes from a newer schema." }
            return
        }
        val typedHandler = handler as UploaderHandler<Any>
        val payload: Any = try {
            typedHandler.decodePayload(item.payload)
        } catch (e: Throwable) {
            // Real cancellation of this scope (or of a withdrawal) still propagates; a CancellationException the
            // decoder threw on its own is a decode failure like any other and must not retry forever.
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            // Retrying cannot fix a broken payload, and deleting it would lose the user's data.
            parkAsRead(item, "undecodable payload: ${describeFailure(e)}")
            logger.e(e) { "Parked ${item.logName} — undecodable payload." }
            return
        }
        val context = AttemptContext(
            id = item.id,
            attempts = item.attempts,
            uniqueKey = item.uniqueKey,
            orderingKey = item.orderingKey,
            // The claim a hand-off from this attempt writes: markInFlight advances the row's by one.
            claim = item.claim + 1,
            // Reaching execute() while in flight means the lease expired un-settled. Telling the
            // handler makes a careful re-hand-off possible: the previous executor may have finished
            // without managing to report it.
            wasDetached = item.state == UploaderItemState.IN_FLIGHT,
        )
        if (typedHandler is UploadHandler<*>) {
            handOffUpload(item, typedHandler as UploadHandler<Any>, context, payload, attempt)
            return
        }
        // A withdrawal that landed after the attempt was admitted must stop it before any handler code runs.
        currentCoroutineContext().ensureActive()
        val outcome: AttemptResult = try {
            typedHandler.execute(context, payload)
        } catch (e: CancellationException) {
            // Real cancellation of our scope propagates; a CancellationException *leaked* by the
            // handler's own internals — an escaped withTimeout, which the handler contract even
            // recommends using — must not kill the drain, so it becomes a retry like any failure.
            currentCoroutineContext().ensureActive()
            AttemptResult.Retry(e)
        } catch (e: Throwable) {
            AttemptResult.Retry(e)
        }
        recordOutcome(item, handler, outcome, attempt)
    }

    /**
     * Called with what a handler answered once its call returned; records it by rule P2 of the class KDoc. A
     * `Success` (its effect happened) and a `Detached` (its executor was started) are facts, so they are recorded
     * under `NonCancellable` whether or not the attempt is still active, which a `close()` landing meanwhile
     * cannot interrupt. A `Detached` of a **withdrawn** attempt is the one exception: the withdrawal told its
     * handler to stop the executor. Every other answer of a cancelled attempt is not recorded: the next pass
     * reaches the same verdict by itself, and a transaction that rolled a withdrawal back restored the row
     * unspent. Ends by throwing the cancellation when the attempt was cancelled.
     */
    private suspend fun recordOutcome(
        item: UploaderItem,
        handler: UploaderHandler<*>,
        outcome: AttemptResult,
        attempt: RunningAttempt,
    ) {
        val cancelled: Boolean = !currentCoroutineContext().isActive
        when {
            // P2: the effect happened, so its row is removed whatever happens to the attempt.
            outcome is AttemptResult.Success -> recordFact(item, cancelled, "delivered") { applyOutcome(item, handler, outcome) }
            // P2: the executor was started, so the row is claimed under its lease (unless the attempt was withdrawn).
            outcome is AttemptResult.Detached && !attempt.withdrawn.value ->
                recordFact(item, cancelled, "detached") { applyOutcome(item, handler, outcome) }

            cancelled -> logger.i { "Attempt of ${item.logName} was cancelled; its answer ($outcome) is not recorded." }
            else -> applyOutcome(item, handler, outcome)
        }
        currentCoroutineContext().ensureActive()
    }

    /**
     * Runs [write] under `NonCancellable`. A failure of a live attempt propagates (the drain backs off, and a
     * failed delete is already remembered as delivered); one of an attempt that was being cancelled is logged.
     */
    private suspend fun recordFact(item: UploaderItem, cancelled: Boolean, what: String, write: suspend () -> Unit) {
        try {
            withContext(NonCancellable) { write() }
        } catch (e: Throwable) {
            if (!cancelled) throw e
            logger.e(e) { "Recording that ${item.logName} was $what failed while its attempt was being cancelled." }
        }
    }

    private suspend fun applyOutcome(
        item: UploaderItem,
        handler: UploaderHandler<*>,
        outcome: AttemptResult,
    ) {
        when (outcome) {
            is AttemptResult.Success -> {
                deleteAfterEffect(item.id)
                logger.i { "Delivered ${item.logName} after ${item.attempts} failed attempts." }
            }

            is AttemptResult.Detached -> {
                // Delivery now belongs to an external executor; the row waits under a lease. The
                // attempt counter deliberately does not move — a hand-off is not a failure, and
                // only a Failed settle spends retry budget.
                //
                // Claimed only as read: a fast executor may already have settled the item while
                // execute() was still returning, and its Park or Drop must stand.
                val claimed: Boolean = store.markInFlight(
                    id = item.id,
                    leaseUntilEpochMillis = saturatingAdd(clock.nowEpochMillis(), clampLease(outcome.leaseMillis)),
                    expectedState = item.state,
                    expectedClaim = item.claim,
                )
                if (claimed) {
                    logger.i { "Detached ${item.logName} under a ${outcome.leaseMillis}ms lease." }
                } else {
                    logger.i { "Detached ${item.logName}, but it was settled during the hand-off — no claim." }
                }
            }

            is AttemptResult.Drop -> {
                deleteAfterEffect(item.id)
                logger.i { "Dropped ${item.logName} — ${outcome.reason}." }
            }

            is AttemptResult.Park -> {
                if (parkAsRead(item, outcome.reason)) {
                    logger.w { "Parked ${item.logName} — ${outcome.reason}." }
                } else {
                    logger.i { "Park of ${item.logName} skipped — the item moved while it ran." }
                }
            }

            is AttemptResult.Retry -> recordRetry(item, handler, outcome.cause)
        }
    }

    /**
     * One attempt of an [UploadHandler]'s item. Unlike a plain [AttemptResult.Detached], the lease is
     * claimed **before** the transport launches: a platform job may start at once, and one that found the
     * item still pending would see nothing owed and upload nothing, leaving the claim that lands after it
     * idle for a whole lease.
     */
    private suspend fun handOffUpload(
        item: UploaderItem,
        handler: UploadHandler<Any>,
        context: AttemptContext,
        payload: Any,
        attempt: RunningAttempt,
    ) {
        currentCoroutineContext().ensureActive()
        val preparation: UploadPreparation = try {
            handler.prepareUpload(context, payload)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            recordRetry(item, handler, e)
            return
        } catch (e: Throwable) {
            // A cancelled attempt records nothing, whatever its handler threw after the cancellation.
            currentCoroutineContext().ensureActive()
            recordRetry(item, handler, e)
            return
        }
        // A handler that swallowed the cancellation of its attempt (a withdrawal, close()) and answered anyway:
        // nothing is claimed, launched or recorded, so a closed engine stays inert and a rolled-back
        // withdrawal's row stays unspent.
        if (!currentCoroutineContext().isActive) {
            logger.i { "Hand-off of ${item.logName} abandoned — its attempt was cancelled while the upload was prepared." }
            currentCoroutineContext().ensureActive()
        }
        val request: UploadRequest = when (preparation) {
            is UploadPreparation.Drop -> return applyOutcome(item, handler, AttemptResult.Drop(preparation.reason))
            is UploadPreparation.Park -> return applyOutcome(item, handler, AttemptResult.Park(preparation.reason))
            is UploadPreparation.Proceed -> preparation.request
        }
        val transport: UploadTransport = handler.transport
        val lease: Long = transport.leaseMillis
        if (lease <= 0) {
            // A non-positive lease would never read as in flight and re-hand on every pass.
            parkAsRead(item, "UploadTransport.leaseMillis must be > 0, was $lease")
            logger.e { "Parked ${item.logName} — non-positive transport lease." }
            return
        }
        // P3: the lease is this hand-off's ownership token, unique among the unresolved claims of the item.
        val leaseUntil: Long = ClaimsInDoubt.reserve(item.id, saturatingAdd(clock.nowEpochMillis(), clampLease(lease)))
        // Claimed only in the state and claim this pass read the item in. Between that read and here a
        // detached executor of an earlier attempt may have settled it — parked on a permanent failure,
        // or back to pending under a backoff gate — and an unguarded claim would revive it and upload it
        // again. A claim that misses launches nothing; the next pass sees the row as it now is.
        val claimed: Boolean = try {
            val written: Boolean = try {
                store.markInFlight(
                    id = item.id,
                    leaseUntilEpochMillis = leaseUntil,
                    expectedState = item.state,
                    expectedClaim = item.claim,
                )
            } catch (e: Throwable) {
                // The claim's outcome is unknown: a store that hops threads reports a close() (or a withdrawal)
                // after the write committed. A claim left in place with no launch behind it would strand the
                // item for a whole lease, so it is released to what this pass read, once the row proves the
                // claim is this hand-off's (P3).
                releaseOwnClaim(item, context.claim, leaseUntil)
                throw e
            }
            if (written && isClosed) {
                // P4: a claim that finished after close() is released, never launched.
                releaseOwnClaim(item, context.claim, leaseUntil)
                logger.i { "Hand-off of ${item.logName} abandoned — the engine closed while its claim was written." }
                return // the drain sees the close at its next check
            }
            written
        } finally {
            ClaimsInDoubt.drop(item.id, leaseUntil)
        }
        if (!claimed) {
            logger.i { "Hand-off of ${item.logName} skipped — the item moved since this pass read it." }
            return
        }
        try {
            transport.launch(item.id, context.wasDetached, request)
            logger.i { "Detached ${item.logName} under a ${lease}ms lease." }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            recordRetry(item, handler, e, expectedState = UploaderItemState.IN_FLIGHT, expectedClaim = context.claim)
            return
        } catch (e: Throwable) {
            // The platform scheduler unavailable, say: this item's attempt fails, guarded by the claim just
            // made so a settle that already landed is not overwritten.
            logger.e(e) { "Upload hand-off failed for ${item.logName}." }
            recordRetry(item, handler, e, expectedState = UploaderItemState.IN_FLIGHT, expectedClaim = context.claim)
            return
        }
        // A withdrawal, or a REPLACE, that removed the row while this attempt was being prepared found no
        // upload to cancel yet. Seen from here, after the launch, the row is gone, so cancel it now. A row
        // gone because a fast upload already settled makes this a harmless no-op.
        // The attempt's own withdrawal flag is read first: a store that notices cancellation throws from the
        // re-read of an attempt that stopAttempt has just cancelled, and the cancel below would be skipped. Every
        // engine withdrawal removes the row, then calls stopAttempt (which sets the flag), then cancels the upload
        // itself, so there is always a cancel after the last launch. The re-read covers a row removed by something
        // other than the engine and runs non-cancellable for the same reason.
        val removed: Boolean = attempt.withdrawn.value || withContext(NonCancellable) { store.getById(item.id) } == null
        if (removed) {
            cancelUpload(item, reason = "removed during its hand-off")
        }
    }

    /**
     * Puts the row of a hand-off whose claim was written, or may have been, back to the state [item] was read in, so
     * a claim without a launch behind it does not strand the item for a lease. Rule P3: the release is made only
     * after a read shows the row `IN_FLIGHT` under [claim] **and** [leaseUntil], the lease this hand-off wrote and
     * reserved in [ClaimsInDoubt]; the claim number alone is shared by every engine that read the same row, so it
     * cannot tell this claim from a replacement engine's. Non-cancellable; a release that fails is logged and
     * swallowed (the lease is the fallback, as for a process killed in the same window).
     */
    private suspend fun releaseOwnClaim(item: UploaderItem, claim: Long, leaseUntil: Long) {
        try {
            withContext(NonCancellable) {
                val row: UploaderItem? = store.getById(item.id)
                if (row == null || row.state != UploaderItemState.IN_FLIGHT ||
                    row.claim != claim || row.leaseUntilEpochMillis != leaseUntil
                ) {
                    logger.i { "Not releasing ${item.logName}: the row is not this hand-off's claim." }
                    return@withContext
                }
                if (item.state == UploaderItemState.IN_FLIGHT) {
                    // A re-hand of an expired lease: the lease is already over, so the next pass re-hands at once
                    // and still knows the previous executor may have run.
                    store.markInFlight(
                        id = item.id,
                        leaseUntilEpochMillis = item.leaseUntilEpochMillis,
                        expectedState = UploaderItemState.IN_FLIGHT,
                        expectedClaim = claim,
                    )
                } else {
                    store.recordFailure(
                        id = item.id,
                        attempts = item.attempts,
                        nextRunAtEpochMillis = item.nextRunAtEpochMillis,
                        lastError = item.lastError,
                        expectedState = UploaderItemState.IN_FLIGHT,
                        expectedClaim = claim,
                        disposition = FailureDisposition.RETRY,
                    )
                }
            }
        } catch (e: Throwable) {
            logger.w(e) { "Releasing the interrupted claim of ${item.logName} failed; its lease runs out instead." }
        }
    }

    /**
     * Records a failed attempt of [record], guarded by the state and claim it was read in — by default
     * [record]'s own; a hand-off whose launch failed passes the claim it has just written instead.
     *
     * Guarded on every path, the drain's own included. While a handler runs — `execute`, or
     * `prepareUpload` before a hand-off — a detached executor of an earlier attempt can settle the item
     * on its own coroutine, and its park must not be overwritten back to PENDING. Single-flight keeps
     * other drain passes away from the row; it does nothing about a settle.
     */
    private suspend fun recordRetry(
        record: UploaderItem,
        handler: UploaderHandler<*>,
        cause: Throwable?,
        expectedState: UploaderItemState = record.state,
        expectedClaim: Long = record.claim,
    ): Boolean {
        val attempts: Int = record.attempts + 1
        val error: String? = describeFailure(cause)
        val nextRunAt: Long = saturatingAdd(clock.nowEpochMillis(), handler.retryPolicy.backoffMillis(attempts))
        // The give-up is decided before the write and made by it, not by a second write after it: a row
        // that passed through PENDING on its way to parked or dropped could be handed off once more by
        // a drain pass landing in between — an attempt past maxAttempts, on an item already given up.
        val disposition: FailureDisposition = when (val giveUp: GiveUpPolicy = handler.retryPolicy.giveUp) {
            is GiveUpPolicy.ParkAfterAttempts ->
                if (attempts >= giveUp.maxAttempts) FailureDisposition.PARK else FailureDisposition.RETRY

            is GiveUpPolicy.DropAfterAttempts ->
                if (attempts >= giveUp.maxAttempts) FailureDisposition.DROP else FailureDisposition.RETRY

            is GiveUpPolicy.Never -> FailureDisposition.RETRY
        }
        val lastError: String? = when (disposition) {
            FailureDisposition.PARK -> "gave up after $attempts attempts; last error: $error"
            FailureDisposition.RETRY, FailureDisposition.DROP -> error
        }
        val applied: Boolean = store.recordFailure(
            id = record.id,
            attempts = attempts,
            nextRunAtEpochMillis = nextRunAt,
            lastError = boundedFailureText(lastError),
            expectedState = expectedState,
            expectedClaim = expectedClaim,
            disposition = disposition,
        )
        if (!applied) {
            logger.i { "Stale failure report for ${record.logName} — superseded, no-op." }
            return false
        }
        when (disposition) {
            FailureDisposition.PARK -> logger.e { "Parked ${record.logName} after $attempts attempts ($error)." }
            FailureDisposition.DROP -> logger.e { "Dropped ${record.logName} after $attempts attempts ($error)." }
            FailureDisposition.RETRY -> logger.w { "Retry scheduled for ${record.logName} (attempt $attempts, $error)." }
        }
        return true
    }

    /** An attempt the drain is running for one item, and whether a withdrawal has stopped it. */
    private class RunningAttempt(val job: Job) {
        val withdrawn: MutableStateFlow<Boolean> = MutableStateFlow(false)
    }

    /** The attempts the drain is running right now, by item id: the handshake with [stopAttempt]. */
    private val runningAttempts: MutableStateFlow<Map<String, RunningAttempt>> = MutableStateFlow(emptyMap())

    /**
     * Stops the attempt the engine is running for [id], if any: marks it withdrawn and cancels it, without
     * waiting for it. Called after the store call that removed the row, so that either the attempt registered
     * before this call and is cancelled by it, or it registers afterwards and its re-read of the row finds the
     * row gone ([attemptUnlessMoved]). Neither side takes a lock: both steps are atomic on their own.
     */
    private fun stopAttempt(id: String) {
        runningAttempts.value[id]?.let { running ->
            running.withdrawn.value = true
            running.job.cancel()
        }
    }

    /**
     * Runs [block], the attempt for [item], unless the item moved since the pass read it, and cancels it when a
     * withdrawal removes the item while it runs. The attempt registers first and re-reads its row second; a
     * withdrawal removes the row first and looks for a registered attempt second, so one of the two always sees
     * the other and no attempt of a removed item begins after the withdrawal returned. A withdrawn attempt
     * records nothing: its row is gone, or a transaction that rolled the withdrawal back restored it unspent.
     */
    private suspend fun attemptUnlessMoved(item: UploaderItem, block: suspend (RunningAttempt) -> Unit): Boolean {
        return coroutineScope {
            lateinit var entry: RunningAttempt
            // Lazy, so `entry` is assigned before the block can run.
            val attempt: Deferred<Unit> = async(start = CoroutineStart.LAZY) { block(entry) }
            entry = RunningAttempt(attempt)
            runningAttempts.update { it + (item.id to entry) }
            try {
                if (store.getById(item.id) != item) {
                    logger.i { "Attempt of ${item.logName} skipped — the item moved since this pass read it." }
                    return@coroutineScope false
                }
                try {
                    attempt.await()
                } catch (e: CancellationException) {
                    // close() or a cancelled scope still propagates from here.
                    currentCoroutineContext().ensureActive()
                    if (entry.withdrawn.value) {
                        logger.i { "Attempt of ${item.logName} stopped — the item was withdrawn." }
                        return@coroutineScope true
                    }
                    throw e // a leaked cancellation: the drain's catch handles it as before
                }
                true
            } finally {
                runningAttempts.update { map -> if (map[item.id] === entry) map - item.id else map }
                // A lazy child that never started would keep this scope waiting for it.
                attempt.cancel()
            }
        }
    }

    /**
     * What the engine remembers, in memory only, about an item whose attempt failed outside its handler.
     * [notBeforeMillis] is the earliest wall-clock time of the next try; [deliveredUnconfirmed] means the
     * handler already succeeded (or dropped the item) and only the confirming delete failed.
     */
    private data class OutsideFailure(val count: Int, val notBeforeMillis: Long, val deliveredUnconfirmed: Boolean) {
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

    /** Forgets the entries of items that left the queue, so the map is bounded by the queue itself. */
    private fun pruneOutsideFailures(active: List<UploaderItem>) {
        if (outsideFailures.value.isEmpty()) return
        val ids: Set<String> = active.mapTo(HashSet()) { it.id }
        outsideFailures.update { map -> map.filterKeys { it in ids } }
    }

    /** Records one more failure outside the handler for [id] and returns the time of its next try. */
    private fun bumpOutsideFailure(id: String): Long {
        val now: Long = clock.nowEpochMillis()
        var notBefore = 0L
        outsideFailures.update { map ->
            val old: OutsideFailure? = map[id]
            val count: Int = (old?.count ?: 0) + 1
            notBefore = saturatingAdd(now, failedPassRetryMillis(count))
            map + (id to OutsideFailure(count, notBefore, old?.deliveredUnconfirmed ?: false))
        }
        return notBefore
    }

    /**
     * Deletes the row of an item whose handler has finished with it (delivered, or dropped). A delete that
     * fails is remembered as such: the effect already ran in this process, so the drain retries only the
     * delete and never the handler.
     */
    private suspend fun deleteAfterEffect(id: String) {
        try {
            store.deleteById(id)
        } catch (e: Throwable) {
            outsideFailures.update { map ->
                map + (id to (map[id]?.copy(deliveredUnconfirmed = true) ?: OutsideFailure(0, 0L, true)))
            }
            throw e
        }
    }

    /**
     * How long to wait before retrying after [failures] consecutive failed passes (`>= 1`): [UploaderConfig.minAlarmDelay]
     * doubling with each one, at most [UploaderConfig.heartbeatInterval]. Saturating, so a long failure streak
     * cannot overflow.
     */
    private fun failedPassRetryMillis(failures: Int): Long {
        val ceiling: Long = config.heartbeatInterval.inWholeMilliseconds.coerceAtLeast(1L)
        var delayMillis: Long = config.minAlarmDelay.inWholeMilliseconds.coerceAtLeast(1L)
        var step = 1
        while (step < failures && delayMillis < ceiling) {
            delayMillis = saturatingMultiply(delayMillis, 2L)
            step++
        }
        return minOf(delayMillis, ceiling)
    }

    /** Arms — or re-arms — the one-shot alarm that re-triggers the drain at the soonest gate. */
    private fun scheduleAlarm(atEpochMillis: Long?) {
        alarmJob?.cancel()
        if (atEpochMillis == null) {
            alarmJob = null
            return
        }
        val delayMillis: Long = saturatingSubtract(atEpochMillis, clock.nowEpochMillis())
            .coerceAtLeast(config.minAlarmDelay.inWholeMilliseconds)
        alarmJob = engineScope.launch {
            delay(delayMillis)
            trigger()
        }
    }

    private fun cancelAlarm() {
        alarmJob?.cancel()
        alarmJob = null
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
     *   `null` when nothing is to be uploaded: the row is gone or no longer in flight, the handler dropped or
     *   parked it (applied here), or preparation failed (charged to the row as a failed attempt, guarded by
     *   its claim). The row is read again after the handler prepared, so a settlement or withdrawal that landed
     *   meanwhile is [PreparedRequest.NothingOwed] and a charge is [PreparedRequest.MovedOn]; what remains is the
     *   window from that read to the first byte, which any check-then-act has.
     */
    private suspend fun prepareUploadRequest(id: String): PreparedRequest? {
        val record: UploaderItem? = store.getById(id)
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
                val current: UploaderItem? = store.getById(id)
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
                store.deleteById(id)
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
            recordRetry(record, handler, cause, expectedState = UploaderItemState.IN_FLIGHT, expectedClaim = record.claim)
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
        val record: UploaderItem = try {
            store.getById(id)
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
        val record: UploaderItem? = store.getById(id)
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
     * The body limit the handler of [id] enforces, or `null` when the item is gone, has no upload handler or
     * the store cannot say. Lets a transport hold the outcome journal to the same limit.
     */
    suspend fun responseBodyLimitOf(id: String): Int? = try {
        val record: UploaderItem? = store.getById(id)
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
        val record: UploaderItem? = store.getById(id)
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
     * The upload handler for [record] and its decoded payload, or `null` after parking a row that has
     * no upload handler or an undecodable payload — both wiring or migration bugs to keep visible.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun uploadHandlerAndPayload(record: UploaderItem, phase: String): Pair<UploadHandler<Any>, Any>? {
        val handler: UploadHandler<Any> = handlersByType[record.type] as? UploadHandler<Any> ?: run {
            parkAsRead(record, "$phase for non-upload type '${record.type}'")
            logger.e { "Parked ${record.logName} — $phase for a type with no UploadHandler." }
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
        get() = closed.value || !engineJob.isActive

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        UploaderEngineRegistry.unregister(this)
        engineScope.cancel()
        logger.d { "Uploader engine closed." }
    }

    private val UploaderItem.logName: String
        get() = "$type/${uniqueKey ?: id}"

    private companion object {
        /**
         * The longest lease the engine writes, 24 hours. A longer one is shortened ([clampLease]): the item is
         * merely re-handed after a day, which a hand-off tolerates by contract, whereas a lease with no ceiling
         * cannot be told from one written before a backwards clock step ([isClockAnomalyLease]).
         */
        const val MAX_LEASE_MILLIS: Long = 24L * 60 * 60 * 1000

        fun clampLease(leaseMillis: Long): Long = leaseMillis.coerceAtMost(MAX_LEASE_MILLIS)
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
