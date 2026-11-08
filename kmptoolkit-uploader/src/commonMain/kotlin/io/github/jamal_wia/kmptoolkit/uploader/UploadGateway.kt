package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a platform upload executor talks to the engine about an [UploadHandler]'s item, from wherever
 * the platform runs it — a `WorkManager` worker in a restarted process, an iOS background-session
 * delegate after a relaunch.
 *
 * The executor needs nothing but the item id. It asks for the request right before the transfer, may
 * report progress, and reports the raw outcome; the handler's own [UploadHandler.prepareUpload],
 * [UploadHandler.classify] and hooks run inside the engine.
 *
 * Every call reaches the engine registered in [UploaderEngineRegistry]. An engine not built by
 * `createUploaderEngine` cannot be reached this way and reads as unavailable: a decorator around the engine,
 * registered in its place, serves wake jobs only, so register the engine itself when you use an [UploadHandler].
 *
 * The built-in transports use this for you. Call it yourself only when writing a transport of your
 * own for an [UploadHandler] — a test transport over a mock HTTP client, for instance.
 *
 * @since 1.5.0
 */
public object UploadGateway {

    /** How long [prepareAttempt] and [complete] wait for a registered engine by default. */
    public val DEFAULT_ENGINE_WAIT: Duration = 60.seconds

    /**
     * The request for the attempt about to run for [itemId], prepared now by its handler.
     *
     * @return [UploadAttempt.Ready] to upload; [UploadAttempt.NothingOwed] when the item is gone or no
     *   longer in flight, or when the handler dropped or parked it, or when preparing failed
     *   (the failure is charged to the item as a failed attempt under its retry policy) — in every one of those cases, upload
     *   nothing; [UploadAttempt.EngineUnavailable] when no engine registered within [engineWait], or its
     *   store failed to read the item, in which case the executor should retry later rather than
     *   conclude anything.
     */
    public suspend fun prepareAttempt(itemId: String, engineWait: Duration = DEFAULT_ENGINE_WAIT): UploadAttempt =
        prepareStampedAttempt(itemId, engineWait).attempt

    /**
     * [prepareAttempt] with the attempt stamp (the row's `attempts` when the attempt is [UploadAttempt.Ready])
     * the built-in workers journal their answer with.
     */
    internal suspend fun prepareStampedAttempt(
        itemId: String,
        engineWait: Duration = DEFAULT_ENGINE_WAIT,
    ): PreparedUpload {
        val engine: DefaultUploaderEngine = awaitEngine(engineWait)
            ?: return PreparedUpload(UploadAttempt.EngineUnavailable, null)
        return engine.prepareStampedUploadAttempt(itemId)
    }

    /**
     * Reports upload progress of [itemId] to its handler's [UploadHandler.onUploadProgress]. Best
     * effort: dropped silently when no engine is registered right now, the item has settled, or the
     * payload cannot be decoded. [fraction] is clamped to 0..1.
     */
    public suspend fun progress(itemId: String, fraction: Float) {
        (UploaderEngineRegistry.current as? DefaultUploaderEngine)?.reportUploadProgress(itemId, fraction)
    }

    /**
     * Settles [itemId] with the raw [result] and no server response — for a [UploadResult.TransportFailure],
     * or a transport that cannot read the response (use [complete] with an [UploadResponse] otherwise):
     * the handler classifies it, [UploadHandler.onDelivered]
     * runs for a delivery, the item settles, then [UploadHandler.onSettled] runs.
     *
     * It settles under the item's current claim, which is why it takes none: [UploadTransport.launch] is
     * idempotent per item id, so every claim of the item — the first hand-off and each re-hand-off — is
     * served by one platform job, and its report is about the attempt the item is held under.
     *
     * @return `false` when no engine registered within [engineWait], or its store failed while settling —
     *   the outcome did not land, and the executor should keep it and retry. A later retry of an item that
     *   settled meanwhile is harmless; one that did not settle uploads again, so [UploadHandler.onDelivered]
     *   must tolerate running twice.
     */
    public suspend fun complete(
        itemId: String,
        result: UploadResult,
        engineWait: Duration = DEFAULT_ENGINE_WAIT,
    ): Boolean {
        val engine: DefaultUploaderEngine = awaitEngine(engineWait) ?: return false
        return engine.settleUpload(itemId, result)
    }

    /**
     * Settles [itemId] with what the server answered: the engine builds `UploadResult.Completed(response.statusCode)`
     * from it, the handler classifies it with [response] in hand ([UploadHandler.classify]), and
     * [UploadHandler.onDelivered] receives it for a delivery. Everything else is as for the status-only
     * [complete]. A transport that never got an answer reports through [complete] with
     * [UploadResult.TransportFailure] instead.
     *
     * The engine trims the body to the handler's [UploadHandler.maxResponseBodyBytes] — one enforcement
     * point, so a transport hands over what it read (at most 1 MiB, with
     * [UploadResponse.isBodyTruncated] set if it had to stop there) and need not know the limit.
     *
     * @return as [complete]; an unknown or already settled [itemId] is a no-op that returns `true`.
     * @since 4.0.0
     */
    public suspend fun complete(
        itemId: String,
        response: UploadResponse,
        engineWait: Duration = DEFAULT_ENGINE_WAIT,
    ): Boolean {
        val engine: DefaultUploaderEngine = awaitEngine(engineWait) ?: return false
        return engine.settleUpload(itemId, UploadResult.Completed(response.statusCode), response)
    }

    /**
     * [complete] with an [UploadResponse], for the built-in transports' outcome journal: [onSettling] runs
     * just before the engine commits the settlement, after the handler's hooks, for an unstamped answer only.
     * [attemptStamp] is the attempt the answer belongs to; see [JournalSettleOutcome].
     */
    internal suspend fun completeJournaled(
        itemId: String,
        response: UploadResponse,
        attemptStamp: Int?,
        engineWait: Duration,
        onSettling: () -> Unit,
    ): JournalSettleOutcome {
        val engine: DefaultUploaderEngine = awaitEngine(engineWait) ?: return JournalSettleOutcome.NOT_LANDED
        return engine.settleJournaled(itemId, response, attemptStamp, onSettling)
    }

    /**
     * [complete] for an answer the worker did not journal, stamped with the attempt it belongs to
     * ([attemptStamp]); a report of an attempt the item has moved past is not charged to the new one.
     *
     * @return `false` when no engine registered within [engineWait] or the commit did not land.
     */
    internal suspend fun completeAttempt(
        itemId: String,
        result: UploadResult,
        response: UploadResponse?,
        attemptStamp: Int?,
        engineWait: Duration,
    ): Boolean {
        val engine: DefaultUploaderEngine = awaitEngine(engineWait) ?: return false
        return engine.settleAttempt(itemId, result, response, attemptStamp) != JournalSettleOutcome.NOT_LANDED
    }

    /** The body limit of [itemId]'s handler, or `null` when no engine answers within [engineWait] or the item is gone. */
    internal suspend fun responseBodyLimit(itemId: String, engineWait: Duration): Int? =
        awaitEngine(engineWait)?.responseBodyLimitOf(itemId)

    private suspend fun awaitEngine(wait: Duration): DefaultUploaderEngine? =
        UploaderEngineRegistry.await(wait) as? DefaultUploaderEngine
}

/**
 * What [UploadGateway.prepareAttempt] found for one attempt.
 *
 * @since 1.5.0
 */
public sealed interface UploadAttempt {

    /** Upload [request] now. */
    public data class Ready(val request: UploadRequest) : UploadAttempt

    /** Upload nothing: the item is settled, finished by its handler, or not ready to prepare. */
    public data object NothingOwed : UploadAttempt

    /** No engine to ask, or its store could not read the item. Retry later; do not treat the item as settled. */
    public data object EngineUnavailable : UploadAttempt
}

/**
 * An [UploadAttempt], the attempt stamp its answer is journaled with and the body limit read with it (both
 * meaningful only for [UploadAttempt.Ready]; the stamp is `null` otherwise). A row charged while its handler
 * prepared the upload comes back as [UploadAttempt.EngineUnavailable], so the job retries and prepares the new attempt.
 */
internal class PreparedUpload(
    val attempt: UploadAttempt,
    val attemptStamp: Int?,
    val bodyLimit: Int = MAX_RESPONSE_BODY_BYTES,
)
