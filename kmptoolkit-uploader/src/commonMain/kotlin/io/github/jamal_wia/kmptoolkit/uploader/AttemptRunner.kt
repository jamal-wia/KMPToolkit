package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * What the engine does with one attempt of one item once the drain has admitted it: runs the handler (or hands an
 * [UploadHandler]'s upload to its transport), decides what the answer means and hands the resulting transition to the
 * [RowLedger]. It **decides and never writes**: every row write is a typed transition of the ledger, every stop or
 * cancel of a removed row is [Withdrawals], and the only transport call it makes itself is the launch of an upload.
 *
 * Rules this class owns (see the class KDoc of [DefaultUploaderEngine] for the protocol):
 * - **P4, the check before the launch.** A hand-off whose claim is made after the engine closed launches nothing
 *   ([RowLedger.claim] answers `ABANDONED`).
 * - **P5, a cancelled preparation is never charged.** A throw or an answer from `prepareUpload` that arrives after the
 *   attempt's cancellation spends no attempt and applies no Drop or Park. (The worker-side preparation is the same
 *   rule at a second call site, in [SettleDesk].)
 * - **P2 is the ledger's.** [recordOutcome] only decides which answers are facts to hand over (recorded under
 *   `NonCancellable`) and which die with a cancelled attempt; the writes themselves are [RowLedger]'s.
 *
 * Moved out of the engine class unchanged (split step 2). Lines touched only for the move: the class header and
 * constructor, `internal` on the two members the engine and the settle desk call, `ledger.park` and `ledger.confirm`
 * where the engine kept one-line forwarders (`parkAsRead`, `deleteAfterEffect`), `isClosed()` as a function, and the
 * companion.
 */
internal class AttemptRunner(
    private val ledger: RowLedger,
    private val withdrawals: Withdrawals,
    private val clock: UploaderClock,
    private val logger: Logger,
    private val isClosed: () -> Boolean,
) {

    @Suppress("UNCHECKED_CAST")
    internal suspend fun executeItem(item: UploaderItem, handler: UploaderHandler<*>, attempt: RunningAttempt) {
        if (item.schemaVersion > handler.schemaVersion) {
            // Written by a future app version and then downgraded from; mis-decoding it could send
            // something wrong.
            ledger.park(item, "payload schema v${item.schemaVersion} > handler v${handler.schemaVersion}")
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
            ledger.park(item, "undecodable payload: ${describeFailure(e)}")
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
                ledger.confirm(item)
                logger.i { "Delivered ${item.logName} after ${item.attempts} failed attempts." }
            }

            is AttemptResult.Detached -> {
                // Delivery now belongs to an external executor; the row waits under a lease. The
                // attempt counter deliberately does not move — a hand-off is not a failure, and
                // only a Failed settle spends retry budget.
                //
                // Claimed only as read: a fast executor may already have settled the item while
                // execute() was still returning, and its Park or Drop must stand.
                val claimed: Boolean = ledger.detach(
                    item,
                    leaseUntilEpochMillis = saturatingAdd(clock.nowEpochMillis(), clampLease(outcome.leaseMillis)),
                )
                if (claimed) {
                    logger.i { "Detached ${item.logName} under a ${outcome.leaseMillis}ms lease." }
                } else {
                    logger.i { "Detached ${item.logName}, but it was settled during the hand-off — no claim." }
                }
            }

            is AttemptResult.Drop -> {
                ledger.confirm(item)
                logger.i { "Dropped ${item.logName} — ${outcome.reason}." }
            }

            is AttemptResult.Park -> {
                if (ledger.park(item, outcome.reason)) {
                    logger.w { "Parked ${item.logName} — ${outcome.reason}." }
                } else {
                    logger.i { "Park of ${item.logName} not applied — no-op." }
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
            cancelEarlierClaimIfDropped(item, context, recordRetry(item, handler, e))
            return
        } catch (e: Throwable) {
            // A cancelled attempt records nothing, whatever its handler threw after the cancellation.
            currentCoroutineContext().ensureActive()
            cancelEarlierClaimIfDropped(item, context, recordRetry(item, handler, e))
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
            is UploadPreparation.Drop -> {
                // P8: "nothing owed" stands even if the delete fails (the row is then held as delivered), so the
                // earlier claim's transfer of a re-handed item is cancelled either way.
                try {
                    applyOutcome(item, handler, AttemptResult.Drop(preparation.reason))
                } finally {
                    if (context.wasDetached) withdrawals.onRehandDrop(item)
                }
                return
            }
            is UploadPreparation.Park -> return applyOutcome(item, handler, AttemptResult.Park(preparation.reason))
            is UploadPreparation.Proceed -> preparation.request
        }
        val transport: UploadTransport = handler.transport
        val lease: Long = transport.leaseMillis
        if (lease <= 0) {
            // A non-positive lease would never read as in flight and re-hand on every pass.
            ledger.park(item, "UploadTransport.leaseMillis must be > 0, was $lease")
            logger.e { "Parked ${item.logName} — non-positive transport lease." }
            return
        }
        // P3: the lease is this hand-off's ownership token, unique among the unresolved claims of the item. Claimed
        // only in the state and claim this pass read the item in. Between that read and here a detached executor of an
        // earlier attempt may have settled it — parked on a permanent failure, or back to pending under a backoff gate —
        // and an unguarded claim would revive it and upload it again. A claim that misses launches nothing; the next
        // pass sees the row as it now is.
        val claimResult: RowLedger.ClaimResult = ledger.claim(
            item = item,
            contextClaim = context.claim,
            leaseUntilCandidate = saturatingAdd(clock.nowEpochMillis(), clampLease(lease)),
            isClosed = { isClosed() },
        )
        when (claimResult) {
            RowLedger.ClaimResult.ABANDONED -> {
                logger.i { "Hand-off of ${item.logName} abandoned — the engine closed while its claim was written." }
                return // the drain sees the close at its next check
            }

            RowLedger.ClaimResult.MISSED -> {
                logger.i { "Hand-off of ${item.logName} skipped — the item moved since this pass read it." }
                return
            }

            RowLedger.ClaimResult.CLAIMED -> Unit
        }
        try {
            transport.launchAttempt(item.id, context.wasDetached, context.attempts, request)
            logger.i { "Detached ${item.logName} under a ${lease}ms lease." }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            cancelEarlierClaimIfDropped(
                item, context,
                recordRetry(item, handler, e, expectedState = UploaderItemState.IN_FLIGHT, expectedClaim = context.claim),
            )
            return
        } catch (e: Throwable) {
            // The platform scheduler unavailable, say: this item's attempt fails, guarded by the claim just
            // made so a settle that already landed is not overwritten.
            logger.e(e) { "Upload hand-off failed for ${item.logName}." }
            cancelEarlierClaimIfDropped(
                item, context,
                recordRetry(item, handler, e, expectedState = UploaderItemState.IN_FLIGHT, expectedClaim = context.claim),
            )
            return
        }
        // A withdrawal, or a REPLACE, that removed the row while this attempt was being prepared found no
        // upload to cancel yet. Seen from here, after the launch, the row is gone, so cancel it now. A row
        // gone because a fast upload already settled makes this a harmless no-op.
        // The attempt's own withdrawal flag is read first: a store that notices cancellation throws from the
        // re-read of an attempt that the withdrawal has just cancelled, and the cancel below would be skipped. Every
        // engine withdrawal removes the row, then stops the attempt (which sets the flag), then cancels the upload
        // itself, so there is always a cancel after the last launch. The re-read covers a row removed by something
        // other than the engine and runs non-cancellable for the same reason.
        val removed: Boolean = attempt.withdrawn.value || withContext(NonCancellable) { ledger.get(item.id) } == null
        if (removed) {
            withdrawals.onRemovedDuringHandOff(item)
        }
    }

    /**
     * Rule P8, the re-hand half: a re-hand of an expired lease ([AttemptContext.wasDetached]) whose failure the engine
     * gave up on with a **drop** ([applied] is the disposition [recordRetry] wrote) removed the item, so the earlier
     * claim's transfer, which a re-hand that never launched did not replace, is cancelled. Only an applied write
     * counts: [applied] is `null` when the write did not apply or the attempt was cancelled (P2, P5), and a park or a
     * retry leaves the earlier transfer alone.
     */
    private fun cancelEarlierClaimIfDropped(item: UploaderItem, context: AttemptContext, applied: FailureDisposition?) {
        if (applied == FailureDisposition.DROP && context.wasDetached) {
            withdrawals.onRehandDrop(item)
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
     *
     * @return the disposition the store applied (retry, park or drop), or `null` when the write was stale and applied
     *   nothing.
     */
    internal suspend fun recordRetry(
        record: UploaderItem,
        handler: UploaderHandler<*>,
        cause: Throwable?,
        expectedState: UploaderItemState = record.state,
        expectedClaim: Long = record.claim,
    ): FailureDisposition? {
        // Saturating: a count already at the Int range's end stays there (and parks) instead of wrapping negative.
        val attempts: Int = if (record.attempts == Int.MAX_VALUE) Int.MAX_VALUE else record.attempts + 1
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
        val applied: Boolean = ledger.recordFailure(
            record = record,
            attempts = attempts,
            nextRunAtEpochMillis = nextRunAt,
            lastError = lastError,
            expectedState = expectedState,
            expectedClaim = expectedClaim,
            disposition = disposition,
        )
        if (!applied) {
            logger.i { "Failure report for ${record.logName} not applied — no-op." }
            return null
        }
        when (disposition) {
            FailureDisposition.PARK -> logger.e { "Parked ${record.logName} after $attempts attempts ($error)." }
            FailureDisposition.DROP -> logger.e { "Dropped ${record.logName} after $attempts attempts ($error)." }
            FailureDisposition.RETRY -> logger.w { "Retry scheduled for ${record.logName} (attempt $attempts, $error)." }
        }
        return disposition
    }

    internal companion object {
        /**
         * The longest lease the engine writes, 24 hours. A longer one is shortened ([clampLease]): the item is
         * merely re-handed after a day, which a hand-off tolerates by contract, whereas a lease with no ceiling
         * cannot be told from one written before a backwards clock step (the engine's lease anomaly check).
         */
        const val MAX_LEASE_MILLIS: Long = 24L * 60 * 60 * 1000

        fun clampLease(leaseMillis: Long): Long = leaseMillis.coerceAtMost(MAX_LEASE_MILLIS)
    }
}
