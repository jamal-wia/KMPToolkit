package io.github.jamal_wia.kmptoolkit.uploader

import androidx.work.ExistingWorkPolicy
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What an item's `WorkManager` job tells the transport, and what the transport tells the job, so that a hand-off is
 * never dropped because `WorkManager` still reports the item's previous job running.
 *
 * Unique work with `ExistingWorkPolicy.KEEP` drops a hand-off while the old job is still listed as running, even when
 * that job already stopped serving. This ledger tracks, per item and per process (like [OutcomeReplayLocks]; the worker
 * is instantiated reflectively and cannot see the transport), whether a job is still serving the item:
 *
 * - a hand-off that arrives while a job is serving is counted, and the job serves it before it returns
 *   ([finishServing] answers [Finish.MISSED]);
 * - a hand-off that arrives after the item's last job finished serving is enqueued with `REPLACE`, which cancels or
 *   deletes the old job and enqueues a fresh one;
 * - every other hand-off is enqueued with `KEEP`, as before.
 *
 * Every change is one atomic read-modify-write on a [MutableStateFlow]: [launch][policyForHandOff] cannot suspend, and
 * its decision must be atomic with the job's close. No lock is held across a `WorkManager` call.
 *
 * @param nanoTime the clock for pruning, injectable for tests.
 */
internal class UploadHandOffLedger(private val nanoTime: () -> Long = System::nanoTime) {

    /**
     * One item's state: [activeJobs] jobs of this item are inside `doWork` in this process, [handOffs] hand-offs
     * arrived while one was serving, and [finishedAtNanos] is when the last job finished serving (still reported
     * running by `WorkManager`, perhaps), or `null`. [latestRequest] is the request of the newest hand-off counted
     * for a serving job, kept for a job that carries no row to read it from (the classic transport's), or `null`;
     * [latestAttempt] is the row's `attempts` that hand-off was made for, or `null` when the transport was not told.
     */
    data class Entry(
        val activeJobs: Int,
        val handOffs: Long,
        val finishedAtNanos: Long?,
        val latestRequest: UploadRequest? = null,
        val latestAttempt: Int? = null,
    )

    /** The newest counted hand-off of an item: its [request] and the [attempt] (`attempts` of the row) it is for. */
    class HandOff(val request: UploadRequest, val attempt: Int?)

    /** What a job learns when it closes an iteration that ended in success. */
    enum class Finish {
        /** A hand-off arrived after the iteration read the row: serve again. */
        MISSED,

        /** The job is the last one serving the item and leaves it: the next hand-off replaces it. */
        FINISHED,

        /** Stopped, or another job of the item is active and will read the row: `KEEP` stays right. */
        LEFT_OPEN,
    }

    private val entries: MutableStateFlow<Map<String, Entry>> = MutableStateFlow(emptyMap())

    /** The current entry of [itemId], for tests. */
    fun entry(itemId: String): Entry? = entries.value[itemId]

    /** A job of [itemId] entered `doWork`; call before waiting for the item's lock. */
    fun jobStarted(itemId: String) {
        entries.mutate { map ->
            val entry: Entry = map[itemId] ?: Entry(0, 0L, null)
            // A job that starts with no other job active begins from its own request: what an earlier job counted
            // (possibly for an item since cancelled and enqueued again) is not carried over.
            val fresh: Entry = if (entry.activeJobs == 0) entry.copy(latestRequest = null, latestAttempt = null) else entry
            map + (itemId to fresh.copy(activeJobs = fresh.activeJobs + 1, finishedAtNanos = null))
        }
    }

    /**
     * The request of the newest hand-off that was counted for [itemId]'s serving job, or `null`. Written in the same
     * atomic step as the count, so a job that sees the count sees the request.
     */
    fun latestRequest(itemId: String): UploadRequest? = entries.value[itemId]?.latestRequest

    /** The newest counted hand-off with its attempt stamp, read in one step, or `null`. */
    fun latestHandOff(itemId: String): HandOff? {
        val entry: Entry = entries.value[itemId] ?: return null
        val request: UploadRequest = entry.latestRequest ?: return null
        return HandOff(request, entry.latestAttempt)
    }

    /** The hand-off counter a serving iteration read before it looked at the row. */
    fun handOffsSeen(itemId: String): Long = entries.value[itemId]?.handOffs ?: 0L

    /**
     * Closes an iteration of a job that ended in success. [seen] is [handOffsSeen] from before that iteration;
     * [stopped] is whether `WorkManager` stopped the job.
     */
    fun finishServing(itemId: String, seen: Long, stopped: Boolean): Finish =
        entries.transact { map ->
            val entry: Entry = map[itemId] ?: return@transact map to Finish.LEFT_OPEN
            val now: Long = nanoTime()
            when {
                entry.handOffs != seen -> map to Finish.MISSED
                !stopped && entry.activeJobs == 1 ->
                    pruned(map + (itemId to entry.copy(finishedAtNanos = now)), now) to Finish.FINISHED
                else -> map to Finish.LEFT_OPEN
            }
        }

    /** The job of [itemId] left `doWork`, normally or by cancellation. */
    fun jobEnded(itemId: String) {
        entries.mutate { map ->
            val entry: Entry = map[itemId] ?: return@mutate map
            val next: Entry = entry.copy(activeJobs = (entry.activeJobs - 1).coerceAtLeast(0))
            if (next.activeJobs == 0 && next.finishedAtNanos == null) map - itemId else map + (itemId to next)
        }
    }

    /**
     * The policy for a hand-off of [itemId], deciding atomically what the entry becomes: nothing for an item without
     * a job in this process (`KEEP`); `REPLACE` once for an item whose job finished serving; otherwise the hand-off
     * is counted for the serving job and the policy is `KEEP`. A counted hand-off stores [request] and
     * [attempt] (the row's `attempts` it is for, when known) with the count.
     */
    fun policyForHandOff(itemId: String, request: UploadRequest? = null, attempt: Int? = null): ExistingWorkPolicy =
        entries.transact { map ->
            val now: Long = nanoTime()
            val entry: Entry = map[itemId] ?: return@transact pruned(map, now) to ExistingWorkPolicy.KEEP
            when {
                entry.finishedAtNanos != null -> {
                    val cleared: Entry = entry.copy(finishedAtNanos = null)
                    val next: Map<String, Entry> =
                        if (cleared.activeJobs == 0) map - itemId else map + (itemId to cleared)
                    pruned(next, now) to ExistingWorkPolicy.REPLACE
                }
                else -> {
                    // A hand-off of an older attempt (a duplicate that arrives late) is counted but never replaces
                    // the request of a newer attempt that was handed off already.
                    val older: Boolean = attempt != null && entry.latestAttempt != null && attempt < entry.latestAttempt
                    val counted: Entry = if (older) {
                        entry.copy(handOffs = entry.handOffs + 1)
                    } else {
                        entry.copy(handOffs = entry.handOffs + 1, latestRequest = request, latestAttempt = attempt)
                    }
                    map + (itemId to counted) to ExistingWorkPolicy.KEEP
                }
            }
        }

    /** [itemId] was cancelled: its finished mark is dropped; a job still unwinding keeps its count. */
    fun forget(itemId: String) {
        entries.mutate { map ->
            val entry: Entry = map[itemId] ?: return@mutate map
            if (entry.activeJobs == 0) {
                map - itemId
            } else {
                map + (itemId to entry.copy(finishedAtNanos = null, latestRequest = null, latestAttempt = null))
            }
        }
    }

    /**
     * Drops inactive entries finished long ago. Correctness never depends on it: once `WorkManager` marked the
     * job finished, `REPLACE` and `KEEP` do the same thing, so a pruned entry only turns `REPLACE` into `KEEP`.
     * It bounds the map to the items active now or finished lately.
     */
    private fun pruned(map: Map<String, Entry>, now: Long): Map<String, Entry> {
        val maxAge: Long = PRUNE_AGE.inWholeNanoseconds
        val stale: List<String> = map.filterValues { entry ->
            val finished: Long? = entry.finishedAtNanos
            entry.activeJobs == 0 && finished != null && now - finished > maxAge
        }.keys.toList()
        return if (stale.isEmpty()) map else map - stale.toSet()
    }

    private inline fun <R> MutableStateFlow<Map<String, Entry>>.transact(block: (Map<String, Entry>) -> Pair<Map<String, Entry>, R>): R {
        while (true) {
            val current: Map<String, Entry> = value
            val (next: Map<String, Entry>, result: R) = block(current)
            if (compareAndSet(current, next)) return result
        }
    }

    private inline fun MutableStateFlow<Map<String, Entry>>.mutate(block: (Map<String, Entry>) -> Map<String, Entry>) {
        transact { map -> block(map) to Unit }
    }

    internal companion object {
        /** How long a finished, inactive entry is kept. */
        val PRUNE_AGE: kotlin.time.Duration = 5.minutes

        /** The process-wide ledger shared by the handler transport and its worker. */
        val shared: UploadHandOffLedger = UploadHandOffLedger()
    }
}
