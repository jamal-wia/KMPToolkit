package io.github.jamal_wia.kmptoolkit.uploader

/**
 * Optional, ready-made executor for the common case of [AttemptResult.Detached]: a multipart HTTP
 * upload that must keep going after the process dies. Android ships two on `WorkManager` —
 * `createWorkManagerUploadTransport` and, for an [UploadHandler], `createWorkManagerUploadHandlerTransport`
 * — and iOS ships `createBackgroundUploadTransport` on a background `NSURLSession`. See
 * `docs/kmptoolkit-uploader/08-upload-transport.md`.
 *
 * A handler that uploads calls [launch] from [UploaderHandler.execute] and returns
 * `AttemptResult.Detached(transport.leaseMillis)`:
 *
 * ```kotlin
 * override suspend fun execute(context: AttemptContext, payload: AvatarUpload): AttemptResult {
 *     transport.launch(context.id, UploadRequest(url = payload.url, fields = payload.fields))
 *     return AttemptResult.Detached(transport.leaseMillis)
 * }
 * ```
 *
 * The transport reports the outcome to [UploaderEngine.settle] itself once the platform executor
 * finishes — your handler is not called again for this attempt. [launch] must be idempotent per
 * item id: a re-hand after a lease expiry (the previous executor may have died silently) must join
 * an already-running delivery rather than start a second one.
 */
public interface UploadTransport {

    /** The lease to pass as `AttemptResult.Detached(leaseMillis)`; the engine honours at most 24 hours of it. */
    public val leaseMillis: Long

    /** Starts (or rejoins) the upload for the outbox item [itemId]. */
    public fun launch(itemId: String, request: UploadRequest)

    /**
     * [launch], told whether this is a re-hand after an expired lease.
     *
     * A re-hand is the one moment a transport must be careful: the previous executor may have
     * *finished* without managing to report, and starting the upload again would deliver it twice. A
     * transport that can find out — iOS's background session, whose completion events are buffered
     * until the app asks for them — waits for that answer before starting afresh. [UploadHandler]
     * calls this overload, passing [AttemptContext.wasDetached].
     *
     * The default ignores [isRehandOff] and calls [launch], so a transport written before this member
     * existed keeps its behaviour.
     *
     * @since 1.5.0
     */
    public fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
        launch(itemId, request)
    }

    /**
     * Cancels the delivery of [itemId] if this transport is running it — in this process or, where the
     * platform keeps the transfer outside it, one an earlier process started. A no-op for an id it is
     * not running, so calling it for an item that already finished, or never launched, is safe.
     *
     * A cancelled delivery settles nothing: whoever cancels decides what happens to the item, and
     * [Uploader.cancel] has already removed its row by the time it calls this.
     *
     * Best effort, by nature: a transfer whose last byte already left may have been accepted by the
     * server, and no cancellation can take that back. An idempotency key — the item id — is what makes
     * a later re-send of the same effect harmless.
     *
     * Must not throw for a runtime condition (a platform scheduler that is unavailable); log and return.
     *
     * @since 2.0.0
     */
    public fun cancel(itemId: String)
}

/**
 * A transport that wants to know the attempt an upload belongs to: the row's `attempts` when the engine handed
 * it off. The iOS background transport stamps its session task with it, so a completed answer that reaches the
 * outcome journal in a later process still says which attempt it answers (see [OutcomeJournal]). Internal: a
 * transport of a consumer is launched through the public [UploadTransport.launch] as before.
 */
internal interface AttemptStampedTransport : UploadTransport {

    /** [UploadTransport.launch] for the item's attempt number [attemptStamp] (`UploaderItem.attempts`). */
    fun launch(itemId: String, isRehandOff: Boolean, attemptStamp: Int, request: UploadRequest)
}

/** Launches through [AttemptStampedTransport] when this transport is one, else through the public overload. */
internal fun UploadTransport.launchAttempt(itemId: String, isRehandOff: Boolean, attemptStamp: Int, request: UploadRequest) {
    if (this is AttemptStampedTransport) {
        launch(itemId, isRehandOff, attemptStamp, request)
    } else {
        launch(itemId, isRehandOff, request)
    }
}
