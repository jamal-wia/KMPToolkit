package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The life of one engine: the owner of rule P6 (a rule of [DefaultUploaderEngine]) and the one place that knows whether
 * the engine is alive, which rule P4 reads; it owns the engine's own scope and job, the single-flight drain loop with its wake-up channel,
 * the one-shot alarm, the stop job of a manual drain, the registry entry and the release wake of a closed engine (D-31).
 *
 * It decides nothing about an item: the loop and the manual drain run the `drainPass` the engine hands them, and the
 * only thing it asks of the rows is the ledger's release announcement ([RowLedger.onClaimReleased]).
 *
 * - **Single-flight through a conflated channel.** [start] launches exactly one drain coroutine that reads the
 *   channel. Every wake-up path (enqueue, constraint transition, alarm, heartbeat, an explicit [trigger]) sends to it,
 *   so a burst collapses into one pass and two drains never overlap.
 * - **Its own child scope.** Everything is launched in a child of the caller's scope, so [close] can stop the engine
 *   without cancelling a scope it does not own, while cancelling the caller's scope still stops the engine.
 * - **P6, Bounds.** [close] never waits, and a manual [drain] is stopped by the end of the engine as by its caller.
 * - **The state behind P4.** [isClosed] is the one answer to "is this engine permanently inert", read by the registry,
 *   the drain and the hand-off's claim; the rule itself (no launch of a closed engine) is [AttemptRunner]'s.
 *
 * Moved out of the engine class unchanged (split step 2). Lines touched only for the move: the class header and
 * constructor, `owner` where the registry was given `this`, the engine's pass as a parameter of [start] and [drain], and
 * `internal` on the members the engine calls.
 */
internal class EngineLifecycle(
    private val owner: UploaderEngine,
    scope: CoroutineScope,
    private val ledger: RowLedger,
    private val constraintProviders: List<ConstraintProvider>,
    private val config: UploaderConfig,
    private val clock: UploaderClock,
    private val logger: Logger,
) {

    private val engineJob: Job = SupervisorJob(scope.coroutineContext[Job])

    private val engineScope: CoroutineScope = CoroutineScope(scope.coroutineContext + engineJob)

    init {
        // An engine whose owning scope is cancelled without close() is as dead as a closed one: leaving it in
        // the registry would hand a drain that never runs to every platform entry point, and keep the engine,
        // its store and its handlers reachable from a global.
        engineJob.invokeOnCompletion { UploaderEngineRegistry.unregister(owner) }
    }

    /** Conflated wake-up channel: many [trigger] calls collapse into a single pending signal. */
    private val triggerChannel: Channel<Unit> = Channel(capacity = Channel.CONFLATED)

    /** One-shot backoff alarm. Touched only from the single drain coroutine, so no guard needed. */
    private var alarmJob: Job? = null

    private val started: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val closed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    fun start(drainPass: suspend () -> Unit) {
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
                    drainPass()
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

        // A claim released by a closed engine wakes this one at once (close KDoc, D-31). Registered before the initial
        // trigger below: a release announced earlier has already written its row, which that pass reads as pending, and
        // one announced later triggers a pass. The callback is a send to a conflated channel: thread-safe, never throws.
        val releaseWake: DisposableHandle = ledger.onClaimReleased { trigger() }
        engineJob.invokeOnCompletion { releaseWake.dispose() }

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

    fun trigger() {
        triggerChannel.trySend(Unit)
    }

    suspend fun drain(drainPass: suspend () -> Unit) {
        // A drain() the owner calls runs its attempts in the caller's coroutine, which close() does not cancel on its
        // own, so a close landing meanwhile must stop it as it stops the engine's own pass (P2, P5): a pass scope that
        // close() (or the end of the engine's scope) cancels. A cancelled caller still gets its cancellation.
        if (isClosed) return
        try {
            coroutineScope {
                val pass: CoroutineScope = this
                // A plain child job of the engine, not a coroutine: close() (engineScope.cancel()) or the end of the
                // engine's scope cancels it synchronously, and its completion handler cancels the pass (and with it
                // the running attempt) on the thread that called close(), before close() returns. The handler only
                // calls pass.cancel(), which is thread-safe, non-throwing and non-suspending. A drain started after
                // the engine ended gets a child that is cancelled at creation, so the handler runs at registration.
                val stop: CompletableJob = Job(engineJob)
                stop.invokeOnCompletion { cause -> if (cause != null) pass.cancel() }
                try {
                    drainPass()
                } finally {
                    // Must stay in this finally: an uncompleted child would keep engineJob from ever completing.
                    stop.complete()
                }
            }
        } catch (e: CancellationException) {
            // The caller's own cancellation is rethrown; one that close() caused ends the drain normally.
            currentCoroutineContext().ensureActive()
            if (!isClosed) throw e
        }
    }

    /**
     * How long to wait before retrying after [failures] consecutive failed passes (`>= 1`): [UploaderConfig.minAlarmDelay]
     * doubling with each one, at most [UploaderConfig.heartbeatInterval]. Saturating, so a long failure streak
     * cannot overflow.
     */
    fun failedPassRetryMillis(failures: Int): Long {
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
    fun scheduleAlarm(atEpochMillis: Long?) {
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

    fun cancelAlarm() {
        alarmJob?.cancel()
        alarmJob = null
    }

    /**
     * Whether the engine is permanently inert: [close] has run, or the scope it was created in is cancelled.
     * Read by [UploaderEngineRegistry] so such an engine is never handed out.
     */
    internal val isClosed: Boolean
        get() = closed.value || !engineJob.isActive

    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        UploaderEngineRegistry.unregister(owner)
        engineScope.cancel()
        logger.d { "Uploader engine closed." }
    }
}
