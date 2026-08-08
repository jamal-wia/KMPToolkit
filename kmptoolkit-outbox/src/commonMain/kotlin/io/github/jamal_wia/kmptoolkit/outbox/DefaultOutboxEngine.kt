package io.github.jamal_wia.kmptoolkit.outbox

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.outbox.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore
import io.github.jamal_wia.kmptoolkit.outbox.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.outbox.spi.WakeScheduler
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The engine implementation. See [OutboxEngine] for the contract and [createOutboxEngine] for how
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
 * - **Its own child scope.** Everything is launched in [engineScope], a child of the caller's
 *   scope, so [close] can stop the engine without cancelling a scope it does not own — while
 *   cancelling the caller's scope still stops the engine.
 */
internal class DefaultOutboxEngine(
    private val store: OutboxStore,
    handlers: List<OutboxHandler<*>>,
    scope: CoroutineScope,
    private val constraintProviders: List<ConstraintProvider>,
    private val transactionRunner: TransactionRunner,
    private val wakeScheduler: WakeScheduler,
    private val config: OutboxConfig,
    private val logger: Logger,
    private val clock: OutboxClock,
    private val idGenerator: () -> String,
) : OutboxEngine {

    private val handlersByType: Map<String, OutboxHandler<*>> =
        handlers.associateBy { it.type }.also { byType ->
            // A duplicate type would surface as delayed data loss rather than as an error: enqueue
            // encodes with the caller's handler, the drain decodes with whichever won the map, and
            // the mismatched payload parks as undecodable days later. Fail at construction instead.
            require(byType.size == handlers.size) {
                "Duplicate OutboxHandler types: " +
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

    /** Conflated wake-up channel: many [trigger] calls collapse into a single pending signal. */
    private val triggerChannel: Channel<Unit> = Channel(capacity = Channel.CONFLATED)

    /** One-shot backoff alarm. Touched only from the single drain coroutine, so no guard needed. */
    private var alarmJob: Job? = null

    private val started: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val closed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override fun start() {
        if (closed.value) {
            logger.w { "start() on a closed outbox engine — ignored." }
            return
        }
        if (!started.compareAndSet(expect = false, update = true)) return

        engineScope.launch {
            while (true) {
                triggerChannel.receive()
                // The engine's whole liveness rests on this one coroutine, so nothing may kill it.
                // A transient store failure, or a CancellationException leaked by a handler's own
                // withTimeout, would otherwise stall the queue forever while the heartbeat keeps
                // feeding a channel nobody reads. Real cancellation still propagates via
                // ensureActive().
                try {
                    drain()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    logger.e(e) { "Drain aborted by a leaked cancellation; retrying on next trigger." }
                } catch (e: Throwable) {
                    logger.e(e) { "Drain failed; queue intact, retrying on next trigger." }
                }
            }
        }

        constraintProviders.forEach { provider ->
            provider.satisfied
                .filter { it }
                .onEach {
                    logger.d { "Constraint '${provider.key}' satisfied — triggering a drain." }
                    trigger()
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
        handler: OutboxHandler<P>,
        payload: P,
        uniqueKey: String?,
        tag: String?,
        conflictPolicy: ConflictPolicy,
    ): String? {
        val record = OutboxItem(
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
            state = OutboxItemState.PENDING,
            attempts = 0,
            nextRunAtEpochMillis = 0L,
            createdAtEpochMillis = clock.nowEpochMillis(),
            lastError = null,
        )
        val inserted: Boolean = transactionRunner.inTransaction {
            when (conflictPolicy) {
                ConflictPolicy.KEEP -> store.insertKeep(record)
                ConflictPolicy.REPLACE -> {
                    store.insertReplace(record)
                    true
                }
            }
        }
        if (inserted) {
            logger.d { "Enqueued ${record.logName}." }
        } else {
            logger.d { "Enqueue of ${record.logName} kept the already-queued item." }
        }
        trigger()
        // Persist-first for the wake too: even if the process dies before the drain runs, the
        // platform wake is already armed for the debt that was just persisted.
        wakeScheduler.scheduleWake()
        return record.id.takeIf { inserted }
    }

    override fun observe(type: String): Flow<List<OutboxItem>> = store.observeByType(type)

    override suspend fun awaitDrained(timeout: Duration): Boolean {
        trigger()
        // Bounded by coroutine time rather than by the clock, so a test driving virtual time gets
        // the same behavior as production without also having to advance a fake wall clock.
        val drained: Boolean? = withTimeoutOrNull(timeout) {
            while (store.getAllActive().isNotEmpty()) {
                delay(config.drainPollInterval)
            }
            true
        }
        return drained == true
    }

    override suspend fun settle(id: String, result: SettleResult) {
        val record: OutboxItem? = store.getById(id)
        if (record == null) {
            // Late, duplicate, or superseded settle — the debt is no longer this executor's.
            logger.i { "settle($result) for unknown id=$id — no-op." }
            return
        }
        when (result) {
            is SettleResult.Delivered -> {
                store.deleteById(id)
                logger.i { "Settled ${record.logName} as delivered." }
            }

            is SettleResult.Drop -> {
                store.deleteById(id)
                logger.i { "Settled ${record.logName} as dropped — ${result.reason}." }
            }

            is SettleResult.Park -> {
                store.park(id, result.reason)
                logger.w { "Settled ${record.logName} as parked — ${result.reason}." }
            }

            is SettleResult.Failed -> settleFailed(record, result)
        }
        // Delivered may have emptied the queue (let the drain disarm the wake); Failed armed a
        // backoff gate (let the drain schedule an alarm for it).
        trigger()
    }

    private suspend fun settleFailed(record: OutboxItem, result: SettleResult.Failed) {
        if (record.state != OutboxItemState.IN_FLIGHT) {
            // A parked item must stay parked — reviving it would retry a known-permanent failure —
            // and a pending one is already back in the drain's hands. Only a live claim may spend
            // the retry budget.
            logger.i { "Stale Failed settle for ${record.logName} (state=${record.state}) — no-op." }
            return
        }
        val handler: OutboxHandler<*>? = handlersByType[record.type]
        if (handler == null) {
            // The same treatment the drain gives an orphaned type: keep it, keep it visible.
            store.park(record.id, "no handler registered for type '${record.type}'")
            logger.e { "Parked ${record.logName} on settle — no handler for its type." }
            return
        }
        // Guarded by the lease the record carried when we read it: if the drain re-handed the item
        // in between, the lease changed and this write no-ops instead of clobbering a fresh claim.
        recordRetry(
            record = record,
            handler = handler,
            cause = result.cause,
            expectedLeaseUntil = record.leaseUntilEpochMillis,
        )
    }

    override suspend fun drain() {
        while (true) {
            val active: List<OutboxItem> = store.getAllActive()
            if (active.isEmpty()) {
                cancelAlarm()
                wakeScheduler.cancelWake() // nothing owed — disarm the platform wake
                return
            }
            // Channel heads come from the FULL active list, so an in-flight head keeps blocking its
            // channel's tail: an in-flight delivery is still the oldest owed effect in it.
            val eligible: List<OutboxItem> = channelHeads(active)
            val nowMillis: Long = clock.nowEpochMillis()
            var executedAny = false
            var soonestGate: Long? = null

            for (item in eligible) {
                val handler: OutboxHandler<*>? = handlersByType[item.type]
                if (handler == null) {
                    // Either a wiring bug or a payload from a build that had the type. Parking
                    // keeps the item visible instead of failing forever or deleting a user's data.
                    store.park(item.id, "no handler registered for type '${item.type}'")
                    logger.e { "Parked ${item.logName} — no handler for its type." }
                    executedAny = true
                    continue
                }
                if (item.state == OutboxItemState.IN_FLIGHT && item.leaseUntilEpochMillis > nowMillis) {
                    // A detached executor still holds an unexpired claim. Its expiry is a gate like
                    // a backoff gate, so the alarm re-triggers at it and a silently dead executor
                    // is re-handed promptly rather than on the next heartbeat.
                    soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, item.leaseUntilEpochMillis)
                    continue
                }
                // An expired lease falls through: the item is attemptable again and the handler
                // runs afresh, its hand-off idempotent by contract.
                if (!constraintsSatisfied(handler)) continue
                if (item.nextRunAtEpochMillis > nowMillis &&
                    !isClockAnomalyGate(item.nextRunAtEpochMillis, nowMillis, handler)
                ) {
                    soonestGate = minOf(soonestGate ?: Long.MAX_VALUE, item.nextRunAtEpochMillis)
                    continue
                }
                executeItem(item, handler)
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
     * one channel, or one type's backoff would stall the other's queue. The `#` separator cannot
     * appear ambiguously because the type is compared as a whole prefix.
     */
    private fun channelHeads(active: List<OutboxItem>): List<OutboxItem> {
        val seenKeys: MutableSet<String> = mutableSetOf()
        return active.filter { item ->
            val key: String = item.orderingKey ?: return@filter true
            seenKeys.add("${item.type}#$key")
        }
    }

    /**
     * Whether a persisted backoff gate is too far in the future to be real.
     *
     * A gate can legitimately sit at most one maximum backoff ahead. Anything beyond
     * [OutboxConfig.clockAnomalyFactor] times that means the wall clock jumped **backwards** after
     * the gate was written, and honoring it would freeze the item — and everything behind it in its
     * ordering channel — for the whole duration of the jump. Such a gate is treated as runnable
     * now; the next failure writes a sane one.
     */
    private fun isClockAnomalyGate(
        gateEpochMillis: Long,
        nowMillis: Long,
        handler: OutboxHandler<*>,
    ): Boolean {
        val reachable: Long = handler.retryPolicy.maxDelayMillis * config.clockAnomalyFactor
        val anomaly: Boolean = gateEpochMillis - nowMillis > reachable
        if (anomaly) {
            logger.w {
                "Backoff gate ${gateEpochMillis - nowMillis}ms ahead exceeds the policy's " +
                    "reachable maximum (${reachable}ms) — treating it as a wall-clock jump and " +
                    "running now."
            }
        }
        return anomaly
    }

    private fun constraintsSatisfied(handler: OutboxHandler<*>): Boolean =
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
    private suspend fun executeItem(item: OutboxItem, handler: OutboxHandler<*>) {
        if (item.schemaVersion > handler.schemaVersion) {
            // Written by a future app version and then downgraded from; mis-decoding it could send
            // something wrong.
            store.park(
                item.id,
                "payload schema v${item.schemaVersion} > handler v${handler.schemaVersion}",
            )
            logger.w { "Parked ${item.logName} — its payload comes from a newer schema." }
            return
        }
        val typedHandler = handler as OutboxHandler<Any>
        val payload: Any = try {
            typedHandler.decodePayload(item.payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Retrying cannot fix a broken payload, and deleting it would lose the user's data.
            store.park(item.id, "undecodable payload: ${e.message}")
            logger.e(e) { "Parked ${item.logName} — undecodable payload." }
            return
        }
        val context = AttemptContext(
            id = item.id,
            attempts = item.attempts,
            uniqueKey = item.uniqueKey,
            orderingKey = item.orderingKey,
            // Reaching execute() while in flight means the lease expired un-settled. Telling the
            // handler makes a careful re-hand-off possible: the previous executor may have finished
            // without managing to report it.
            wasDetached = item.state == OutboxItemState.IN_FLIGHT,
        )
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
        applyOutcome(item, handler, outcome)
    }

    private suspend fun applyOutcome(
        item: OutboxItem,
        handler: OutboxHandler<*>,
        outcome: AttemptResult,
    ) {
        when (outcome) {
            is AttemptResult.Success -> {
                store.deleteById(item.id)
                logger.i { "Delivered ${item.logName} after ${item.attempts} failed attempts." }
            }

            is AttemptResult.Detached -> {
                // Delivery now belongs to an external executor; the row waits under a lease. The
                // attempt counter deliberately does not move — a hand-off is not a failure, and
                // only a Failed settle spends retry budget.
                store.markInFlight(item.id, clock.nowEpochMillis() + outcome.leaseMillis)
                logger.i { "Detached ${item.logName} under a ${outcome.leaseMillis}ms lease." }
            }

            is AttemptResult.Drop -> {
                store.deleteById(item.id)
                logger.i { "Dropped ${item.logName} — ${outcome.reason}." }
            }

            is AttemptResult.Park -> {
                store.park(item.id, outcome.reason)
                logger.w { "Parked ${item.logName} — ${outcome.reason}." }
            }

            is AttemptResult.Retry -> recordRetry(item, handler, outcome.cause)
        }
    }

    private suspend fun recordRetry(
        record: OutboxItem,
        handler: OutboxHandler<*>,
        cause: Throwable?,
        expectedLeaseUntil: Long? = null,
    ) {
        val attempts: Int = record.attempts + 1
        val error: String? = cause?.let { "${it::class.simpleName}: ${it.message}" }
        val nextRunAt: Long = clock.nowEpochMillis() + handler.retryPolicy.backoffMillis(attempts)
        val applied: Boolean =
            store.recordFailure(record.id, attempts, nextRunAt, error, expectedLeaseUntil)
        if (!applied) {
            logger.i { "Stale failure report for ${record.logName} — superseded, no-op." }
            return
        }
        when (val giveUp: GiveUpPolicy = handler.retryPolicy.giveUp) {
            is GiveUpPolicy.ParkAfterAttempts -> if (attempts >= giveUp.maxAttempts) {
                store.park(record.id, "gave up after $attempts attempts; last error: $error")
                logger.e { "Parked ${record.logName} after $attempts attempts ($error)." }
                return
            }

            is GiveUpPolicy.DropAfterAttempts -> if (attempts >= giveUp.maxAttempts) {
                store.deleteById(record.id)
                logger.e { "Dropped ${record.logName} after $attempts attempts ($error)." }
                return
            }

            is GiveUpPolicy.Never -> Unit
        }
        logger.w { "Retry scheduled for ${record.logName} (attempt $attempts, $error)." }
    }

    /** Arms — or re-arms — the one-shot alarm that re-triggers the drain at the soonest gate. */
    private fun scheduleAlarm(atEpochMillis: Long?) {
        alarmJob?.cancel()
        if (atEpochMillis == null) {
            alarmJob = null
            return
        }
        val delayMillis: Long = (atEpochMillis - clock.nowEpochMillis())
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

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        OutboxEngineRegistry.unregister(this)
        engineScope.cancel()
        logger.d { "Outbox engine closed." }
    }

    private val OutboxItem.logName: String
        get() = "$type/${uniqueKey ?: id}"
}
