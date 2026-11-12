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

/**
 * The attempts the drain is running right now, by item id: the handshake between a running attempt and a withdrawal
 * (rules P1 and P2 of [DefaultUploaderEngine]). Holds no store: a re-read of the row goes through the [RowLedger].
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
     */
    suspend fun runUnlessMoved(item: UploaderItem, block: suspend (RunningAttempt) -> Unit): Boolean {
        return coroutineScope {
            lateinit var entry: RunningAttempt
            // Lazy, so `entry` is assigned before the block can run.
            val attempt: Deferred<Unit> = async(start = CoroutineStart.LAZY) { block(entry) }
            entry = RunningAttempt(attempt, item.attempts)
            running.update { it + (item.id to entry) }
            try {
                if (ledger.get(item.id) != item) {
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
                running.update { map -> if (map[item.id] === entry) map - item.id else map }
                // A lazy child that never started would keep this scope waiting for it.
                attempt.cancel()
            }
        }
    }
}
