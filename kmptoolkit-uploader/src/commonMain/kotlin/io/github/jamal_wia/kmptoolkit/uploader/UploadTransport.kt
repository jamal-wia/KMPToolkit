package io.github.jamal_wia.kmptoolkit.uploader

/**
 * Optional, ready-made executor for the common case of [AttemptResult.Detached]: a multipart HTTP
 * upload that must keep going after the process dies. Android's is `createWorkManagerUploadTransport`
 * (`WorkManager`); no iOS transport ships yet — see `docs/kmptoolkit-uploader/08-upload-transport.md`.
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
     * Cancels every delivery this transport is running. Safe to over-cancel: a still-owed item's
     * row survives, and the engine re-hands it on the next drain or lease expiry.
     */
    public fun cancelAll()
}
