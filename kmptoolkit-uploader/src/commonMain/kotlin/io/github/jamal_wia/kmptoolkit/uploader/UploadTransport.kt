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

    /** The lease to pass as `AttemptResult.Detached(leaseMillis)`. */
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
     * Cancels every delivery this transport is running. Safe to over-cancel: a still-owed item's
     * row survives, and the engine re-hands it on the next drain or lease expiry.
     */
    public fun cancelAll()
}
