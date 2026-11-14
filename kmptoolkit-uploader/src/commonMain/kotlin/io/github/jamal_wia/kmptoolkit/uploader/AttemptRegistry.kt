package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.i
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** An attempt the drain is running for one item, and whether a withdrawal has stopped it. */
internal class RunningAttempt(val job: Job, val attempts: Int) {
    val withdrawn: MutableStateFlow<Boolean> = MutableStateFlow(false)
}

/** What [AttemptRegistry.runUnlessMoved] did with the attempt it was asked to run. */
internal enum class Admission {
    /** The attempt was admitted and ran to its end (a withdrawn attempt included: its row is gone). */
    RAN,

    /** The item moved since the pass read it; the attempt never began. */
    MOVED,

    /** The engine is already running an attempt for this item (rule P11); the attempt never began. */
    BUSY,
}

/**
 * The attempts the drain is running right now, by item id: the handshake between a running attempt and a withdrawal
 * (rules P1 and P2 of [DefaultUploaderEngine]), and the owner of rule P11, **one attempt per item**: the engine never
 * starts an attempt for an item while it is still running one for that item, whichever pass asks (a `drain()` called
 * from inside the attempt, two manual calls, a manual call next to the engine's own loop). The registry holds at most
 * one entry per id, so a withdrawal always finds the attempt that is running. Holds no store: a re-read of the row
 * goes through the [RowLedger].
 */
internal class AttemptRegistry(
    private val ledger: RowLedger,
    private val logger: Logger,
) {

    private val running: MutableStateFlow<Map<String, RunningAttempt>> = MutableStateFlow(emptyMap())

    /**
     * The `attempts` of the attempt the engine is running for [id] right now, or `null` when none runs. A transport
     * launched from a handler's `execute` always runs inside that item's attempt, so this is the attempt that launch
     * belongs to.
     */
    fun stampOf(id: String): Int? = running.value[id]?.attempts

    /**
     * Waits, at most [timeoutMillis], until the engine runs no attempt for [id]. A transport that a plain handler
     * launches from `execute` can settle (or read the row) before the attempt wrote its `markInFlight`; once the
     * attempt has ended the row is in flight under the next claim (or the claim missed), and a settle under the
     * row's current claim is then correct. Returns at once when no attempt runs.
     */
    suspend fun awaitEnd(id: String, timeoutMillis: Long) {
        if (id !in running.value) return
        withTimeoutOrNull(timeoutMillis) { running.first { id !in it } }
    }

    /**
     * Stops the attempt the engine is running for [id], if any: marks it withdrawn and cancels it, without
     * waiting for it. Called after the store call that removed the row, so that either the attempt registered
     * before this call and is cancelled by it, or it registers afterwards and its re-read of the row finds the
     * row gone ([runUnlessMoved]). Neither side takes a lock: both steps are atomic on their own.
     */
    fun stop(id: String) {
        running.value[id]?.let { attempt ->
            attempt.withdrawn.value = true
            attempt.job.cancel()
        }
    }

    /**
     * Runs [block], the attempt for [item], unless the item moved since the pass read it, and cancels it when a
     * withdrawal removes the item while it runs. The attempt registers first and re-reads its row second; a
     * withdrawal removes the row first and looks for a registered attempt second, so one of the two always sees
     * the other and no attempt of a removed item begins after the withdrawal returned. A withdrawn attempt
     * records nothing: its row is gone, or a transaction that rolled the withdrawal back restored it unspent.
     *
     * Rule P11: the registration inserts only when no attempt is registered for the item, in one atomic step. When one
     * is, the answer is [Admission.BUSY] at once: nothing is registered, re-read or started, and the pass that asked
     * leaves the item to the pass that runs it, which sweeps again after the attempt ends.
     */
    suspend fun runUnlessMoved(item: UploaderItem, block: suspend (RunningAttempt) -> Unit): Admission {
        return coroutineScope {
            lateinit var entry: RunningAttempt
            // Lazy, so `entry` is assigned before the block can run.
            val attempt: Deferred<Unit> = async(start = CoroutineStart.LAZY) { block(entry) }
            entry = RunningAttempt(attempt, item.attempts)
            var registered = false
            running.update { map ->
                registered = item.id !in map
                if (registered) map + (item.id to entry) else map
            }
            if (!registered) {
                // A lazy child that never started would keep this scope waiting for it.
                attempt.cancel()
                logger.i { "Attempt of ${item.logName} not started — the engine is already running one for it." }
                return@coroutineScope Admission.BUSY
            }
            try {
                if (ledger.get(item.id) != item) {
                    logger.i { "Attempt of ${item.logName} skipped — the item moved since this pass read it." }
                    return@coroutineScope Admission.MOVED
                }
                try {
                    attempt.await()
                } catch (e: CancellationException) {
                    // close() or a cancelled scope still propagates from here.
                    currentCoroutineContext().ensureActive()
                    if (entry.withdrawn.value) {
                        logger.i { "Attempt of ${item.logName} stopped — the item was withdrawn." }
                        return@coroutineScope Admission.RAN
                    }
                    throw e // a leaked cancellation: the drain's catch handles it as before
                }
                Admission.RAN
            } finally {
                running.update { map -> if (map[item.id] === entry) map - item.id else map }
                // A lazy child that never started would keep this scope waiting for it.
                attempt.cancel()
            }
        }
    }
}
