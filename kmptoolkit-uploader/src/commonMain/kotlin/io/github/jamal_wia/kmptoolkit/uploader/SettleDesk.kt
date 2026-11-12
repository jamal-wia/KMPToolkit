package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Everything an executor or a transport reports back to the engine: [settle] and its commit, the upload channel's
 * settle path (one at a time per item, under the item lock), the entry points of the built-in transports' outcome
 * journal, the worker-side preparation of an upload attempt, classification and the handler's hooks, and the small
 * reads those transports make (progress, in-flight check, body limit).
 *
 * Rules this class owns (see the class KDoc of [DefaultUploaderEngine] for the protocol):
 * - **Verdict staleness.** A Park or a Failed stands only from the attempt the engine still runs ([isCurrentFor]); a
 *   delivery or a drop is a fact and stands from any claim. A stamped upload answer is stale when its item has moved
 *   past it. Whether a verdict may be applied to a row held as delivered (P10) is not decided here: [RowLedger.park]
 *   and [RowLedger.recordFailure] refuse it.
 * - **D-21.** The commit of a settled upload runs under `NonCancellable`; `classify`, `onDelivered` and `onSettled`
 *   stay cancellable (they are feature code).
 * - **P5, worker side.** A throw or an answer from `prepareUpload` that arrives after the worker's cancellation
 *   charges nothing and applies no Drop or Park (the drain-side call site is [AttemptRunner]).
 *
 * Holds no store and no transport: a row is read, parked, confirmed and charged through the [RowLedger] and
 * [AttemptRunner.recordRetry].
 */
internal class SettleDesk(
    private val ledger: RowLedger,
    private val handlersByType: Map<String, UploaderHandler<*>>,
    private val attemptRunner: AttemptRunner,
    private val logger: Logger,
    private val trigger: () -> Unit,
) {

    /** See [UploaderEngine.settle]. */
    suspend fun settle(id: String, claim: Long, result: SettleResult) {
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
                    ledger.confirm(record) // P7
                    logger.i { "Settled ${record.logName} as delivered." }
                    true
                }

                is SettleResult.Drop -> {
                    ledger.confirm(record) // P7
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
            val parked: Boolean = ledger.park(record, "no handler registered for type '${record.type}'")
            logger.e { "Parked ${record.logName} on settle — no handler for its type." }
            return parked
        }
        // Guarded by the state and claim the record carried when we read it: if the drain re-handed the
        // item in between, the claim moved and this write no-ops instead of clobbering the fresh one.
        return attemptRunner.recordRetry(record = record, handler = handler, cause = result.cause) != null
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
                ledger.confirm(record) // P7: the same rule as the drain's own Drop, though no effect ran here
                logger.i { "Dropped ${record.logName} at upload attempt — ${preparation.reason}." }
                trigger()
                null
            }

            is UploadPreparation.Park -> {
                ledger.park(record, preparation.reason)
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
            ledger.park(record, "$phase: no handler registered for type '${record.type}'")
            logger.e { "Parked ${record.logName} — $phase for a type with no handler." }
            return null
        }
        val payload: Any = try {
            handler.decodePayload(record.payload)
        } catch (e: Throwable) {
            // A decoder's own CancellationException is a decode failure; real cancellation still propagates.
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            ledger.park(record, "undecodable payload on $phase: ${describeFailure(e)}")
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
}
