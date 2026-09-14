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
 * `createUploaderEngine` cannot be reached this way and reads as unavailable.
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
     *   longer in flight, or when the handler dropped or parked it, or when preparing failed transiently
     *   (the item then stays in flight until its lease expires) — in every one of those cases, upload
     *   nothing; [UploadAttempt.EngineUnavailable] when no engine registered within [engineWait], or its
     *   store failed to read the item, in which case the executor should retry later rather than
     *   conclude anything.
     */
    public suspend fun prepareAttempt(itemId: String, engineWait: Duration = DEFAULT_ENGINE_WAIT): UploadAttempt {
        val engine: DefaultUploaderEngine = awaitEngine(engineWait) ?: return UploadAttempt.EngineUnavailable
        return engine.prepareUploadAttempt(itemId)
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
     * Settles [itemId] with the raw [result]: the handler classifies it, [UploadHandler.onDelivered]
     * runs for a delivery, the item settles, then [UploadHandler.onSettled] runs.
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
