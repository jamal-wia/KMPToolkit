package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.KeepResult
import io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The only holder of the [UploaderStore] and the [TransactionRunner] of one engine, and the only code that touches the
 * process-wide [DeliveredUnconfirmed] and [ClaimsInDoubt]. Every write to a row is a typed transition offered here, so
 * a write that skips the rule it belongs to cannot be written outside this class: the engine, the drain and the
 * settle paths hold a ledger, never a store.
 *
 * Rules this class owns (see the class KDoc of [DefaultUploaderEngine] for the protocol):
 * - **P2, facts outlive the attempt:** the fact writes ([confirm], [detach]) are plain suspend calls here; the caller
 *   runs them under `NonCancellable`.
 * - **P3, claim ownership:** [claim] and its release are one transition; a release writes only a row that proves the
 *   claim is this hand-off's.
 * - **P7, confirm:** [confirm] is the one delete of a row whose effect ran; [confirmHeld] is the drain's confirming
 *   retry; both forget the delivered-but-unconfirmed memory, nothing else does.
 * - **P10, a fact outranks a verdict:** [park] and [recordFailure], the two verdict writers, refuse a row this process
 *   holds as delivered and run the confirming delete instead.
 * - **P1, withdrawal writes:** [withdraw] and [withdrawByTag] remove rows and return them, never touching the memory.
 *
 * It decides nothing about attempts or about upload cancellation: the side effects of a removal belong to
 * [Withdrawals], which the engine calls with the rows a transition returned.
 */
internal class RowLedger(
    private val store: UploaderStore,
    private val transactionRunner: TransactionRunner,
    private val logger: Logger,
) {

    // ---- reads -----------------------------------------------------------------------------------------------------

    /** The row of [id], or `null`. */
    suspend fun get(id: String): UploaderItem? = store.getById(id)

    /** Every row not finished, oldest first. */
    suspend fun active(): List<UploaderItem> = store.getAllActive()

    /** The rows of [type] as the store reports them. */
    fun observe(type: String): Flow<List<UploaderItem>> = store.observeByType(type)

    // ---- enqueue ---------------------------------------------------------------------------------------------------

    /** What an enqueue removed, reported even when the call throws after its write committed. */
    class Superseded {
        var row: UploaderItem? = null
    }

    /**
     * Inserts [record] under [policy], inside the transaction runner and not cancellable: a store that notices a
     * cancelled caller (one that hops to a database thread, say) would otherwise throw after committing.
     *
     * The row a REPLACE supersedes, or a KEEP supersedes (a PARKED holder: both policies treat a parked conflict the
     * same way), is written to [superseded] by the insert itself rather than read first: under a runner that is not a
     * real transaction (Direct, the default), an item enqueued between such a read and the insert would lose its row
     * without its upload ever being cancelled.
     *
     * @return whether [record] was inserted (`false`: a KEEP refused by a pending or in-flight holder).
     */
    suspend fun enqueue(record: UploaderItem, policy: ConflictPolicy, superseded: Superseded): Boolean =
        transactionRunner.inTransaction {
            withContext(NonCancellable) {
                when (policy) {
                    ConflictPolicy.KEEP -> when (val kept: KeepResult = store.insertKeep(record)) {
                        KeepResult.Kept -> false
                        is KeepResult.Inserted -> {
                            superseded.row = kept.superseded
                            true
                        }
                    }

                    ConflictPolicy.REPLACE -> {
                        superseded.row = store.insertReplace(record)
                        true
                    }
                }
            }
        }

    // ---- withdrawals (P1) ------------------------------------------------------------------------------------------

    /**
     * What [withdraw] did: [row] was read first, [failure] is the store's failure of the delete (rethrown by the
     * caller after the side effects), and [stillQueued] is whether a failed delete provably left the row there.
     */
    class Withdrawn(val row: UploaderItem, val stillQueued: Boolean, val failure: Throwable?)

    /**
     * Removes the row of [id]. P1: a caller cancelled before the write starts removes nothing; once it has started,
     * the write runs to the end. P8, not P7: a withdrawal removes a row whose effect is not a fact, so it never
     * remembers or forgets the delivered memory. A delete that committed and then failed must not orphan the upload,
     * but one that changed nothing must not stop a live one: only a row provably still queued reports
     * [Withdrawn.stillQueued]. A read that itself fails counts as "not provably there", the conservative answer.
     *
     * @return `null` when there is no such row.
     */
    suspend fun withdraw(id: String): Withdrawn? {
        val record: UploaderItem = store.getById(id) ?: return null
        currentCoroutineContext().ensureActive()
        // Row first: a transport job that starts after this finds nothing owed.
        val failure: Throwable? = try {
            withContext(NonCancellable) { store.deleteById(id) }
            null
        } catch (e: Throwable) {
            e
        }
        val stillQueued: Boolean = failure != null &&
            try {
                withContext(NonCancellable) { store.getById(id) } != null
            } catch (e: Throwable) {
                false
            }
        return Withdrawn(record, stillQueued, failure)
    }

    /**
     * Removes every row tagged [tag] and returns them (a read made first would miss an item enqueued and handed off
     * in between). A caller cancelled before the wipe starts removes nothing; once started it is non-cancellable so
     * its list always comes back.
     */
    suspend fun withdrawByTag(tag: String): List<UploaderItem> {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) { store.deleteByTag(tag) }
    }

    // ---- confirmed deliveries (P7) ---------------------------------------------------------------------------------

    /**
     * Rule P7, the one place a row is deleted because its effect ran or was dropped (delivered, or dropped). A delete
     * that fails is remembered as such, per process ([DeliveredUnconfirmed]): the effect already ran in this process,
     * so the drain, this engine's or a replacement's, retries only the delete and never the handler, and a delete
     * that succeeds forgets the entry. The failure is rethrown to the caller. Deleting an absent row is a no-op.
     */
    suspend fun confirm(item: UploaderItem) {
        try {
            store.deleteById(item.id)
        } catch (e: Throwable) {
            DeliveredUnconfirmed.remember(item)
            throw e
        }
        DeliveredUnconfirmed.forget(item)
    }

    /** Whether this process holds [item] as delivered whose confirming delete failed (a P7 memory entry). */
    fun holds(item: UploaderItem): Boolean = DeliveredUnconfirmed.matches(item)

    /**
     * The rows this process holds as delivered, whose confirming delete is still owed, and that [active] never offers:
     * the PARKED ones (a verdict parked the row before, or while, the delivery's delete failed). Rule P7 confirms a held
     * row in whatever state it is, and the drain reads only the active rows, so it asks here as well.
     *
     * One point read per remembered entry. An entry whose row is gone (a withdrawal keeps its entry on purpose) or whose
     * row carries another fingerprint (another store of this process) is skipped and never forgotten; a read that fails
     * is skipped for this call. Only a cancellation of the caller is rethrown.
     */
    suspend fun heldOutOfRotation(): List<UploaderItem> {
        val held: MutableList<UploaderItem> = ArrayList()
        for (id in DeliveredUnconfirmed.ids()) {
            val row: UploaderItem? = try {
                store.getById(id)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                logger.e(e) { "Reading the held row $id leaked a cancellation; skipping it this call." }
                null
            } catch (e: Throwable) {
                logger.e(e) { "Reading the held row $id failed; skipping it this call." }
                null
            }
            if (row != null && row.state == UploaderItemState.PARKED && holds(row)) held += row
        }
        return held
    }

    /**
     * The drain's confirming retry of a remembered delete: forgets on success itself, throws the store's failure (the
     * row stays held).
     */
    suspend fun confirmHeld(item: UploaderItem) {
        store.deleteById(item.id)
        DeliveredUnconfirmed.forget(item)
    }

    // ---- verdicts --------------------------------------------------------------------------------------------------

    /**
     * Parks [record] if it is still in the state and claim it was read in. Every park the engine makes follows a read,
     * and a settle can land between the two on an executor's own coroutine. Rule P10: a row this process holds as
     * delivered is not parked (the confirming delete runs instead).
     *
     * @return whether the park was applied.
     */
    suspend fun park(record: UploaderItem, reason: String?): Boolean {
        if (holds(record)) return confirmInstead(record, "park")
        return store.park(record.id, boundedFailureText(reason), record.state, record.claim)
    }

    /**
     * Writes the failure of one attempt of [record], guarded by [expectedState] and [expectedClaim].
     *
     * Rule P10: a row this process holds as delivered takes no verdict, whatever its disposition (a charge, a park or
     * a drop would bury a delivery, or spend a retry on one): the confirming delete runs instead.
     *
     * @return whether the store applied it (`false`: the row moved, or P10 refused it).
     */
    suspend fun recordFailure(
        record: UploaderItem,
        attempts: Int,
        nextRunAtEpochMillis: Long,
        lastError: String?,
        expectedState: UploaderItemState,
        expectedClaim: Long,
        disposition: FailureDisposition,
    ): Boolean {
        if (holds(record)) return confirmInstead(record, "$disposition verdict")
        return store.recordFailure(
            id = record.id,
            attempts = attempts,
            nextRunAtEpochMillis = nextRunAtEpochMillis,
            lastError = boundedFailureText(lastError),
            expectedState = expectedState,
            expectedClaim = expectedClaim,
            disposition = disposition,
        )
    }

    /**
     * Rule P10: a verdict met a row held as delivered. Runs the confirming retry of its delete (P7: forgets on
     * success, stays held on failure, where the drain's next turn for it retries again) and reports the verdict as not
     * applied. Never throws, apart from the caller's own cancellation.
     */
    private suspend fun confirmInstead(held: UploaderItem, verdict: String): Boolean {
        try {
            confirmHeld(held)
            logger.i { "A $verdict for ${held.logName} was not applied: its delivery is confirmed (the row is deleted)." }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            logger.e(e) { "A $verdict for ${held.logName} was not applied; confirming its delivery leaked a cancellation." }
        } catch (e: Throwable) {
            logger.e(e) { "A $verdict for ${held.logName} was not applied: its delivery stays held, the drain retries the delete." }
        }
        return false
    }

    // ---- claims (P3) -----------------------------------------------------------------------------------------------

    /**
     * Claims [item] for a detached executor under [leaseUntilEpochMillis], only as read: a fast executor may already
     * have settled it, and its Park or Drop must stand.
     *
     * @return whether the claim was written.
     */
    suspend fun detach(item: UploaderItem, leaseUntilEpochMillis: Long): Boolean = store.markInFlight(
        id = item.id,
        leaseUntilEpochMillis = leaseUntilEpochMillis,
        expectedState = item.state,
        expectedClaim = item.claim,
    )

    /** What [claim] did. */
    enum class ClaimResult {
        /** The claim was written and the engine is open: launch. */
        CLAIMED,

        /** The row moved since the pass read it; nothing was written. */
        MISSED,

        /** The claim was written but the engine closed meanwhile; it was released (P4). */
        ABANDONED,
    }

    /**
     * Rule P3: claims [item] for a hand-off whose claim number is [contextClaim], under a lease reserved in
     * [ClaimsInDoubt] near [leaseUntilCandidate] (the lease is this hand-off's ownership token, unique among the
     * unresolved claims of the item), guarded by the state and claim the pass read. A claim whose outcome is unknown
     * (the store threw) or whose engine closed meanwhile ([isClosed]) is released to what the pass read, once the row
     * proves the claim is this hand-off's. Rethrows the store's failure.
     */
    suspend fun claim(
        item: UploaderItem,
        contextClaim: Long,
        leaseUntilCandidate: Long,
        isClosed: () -> Boolean,
    ): ClaimResult {
        val leaseUntil: Long = ClaimsInDoubt.reserve(item.id, leaseUntilCandidate)
        try {
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
                release(item, contextClaim, leaseUntil)
                throw e
            }
            if (written && isClosed()) {
                // P4: a claim that finished after close() is released, never launched.
                release(item, contextClaim, leaseUntil)
                return ClaimResult.ABANDONED
            }
            return if (written) ClaimResult.CLAIMED else ClaimResult.MISSED
        } finally {
            ClaimsInDoubt.drop(item.id, leaseUntil)
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
    private suspend fun release(item: UploaderItem, claim: Long, leaseUntil: Long) {
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
                // The item is startable again: wake the engine that replaced this one, which saw the claim live.
                ClaimsInDoubt.announceRelease()
            }
        } catch (e: Throwable) {
            logger.w(e) { "Releasing the interrupted claim of ${item.logName} failed; its lease runs out instead." }
        }
    }

    /**
     * Registers [wake] to run when a claim is released by any engine of this process (a closed engine's release wakes
     * the engine that replaced it, D-31). The callback must be a thread-safe, non-throwing send.
     */
    fun onClaimReleased(wake: () -> Unit): DisposableHandle = ClaimsInDoubt.onRelease(wake)
}

/** The name an item carries in log lines. */
internal val UploaderItem.logName: String
    get() = "$type/${uniqueKey ?: id}"
