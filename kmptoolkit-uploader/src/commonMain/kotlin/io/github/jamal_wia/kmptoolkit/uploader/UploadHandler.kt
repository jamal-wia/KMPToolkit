package io.github.jamal_wia.kmptoolkit.uploader

/**
 * An [UploaderHandler] whose delivery is one multipart HTTP upload, run by an [UploadTransport]
 * outside the handler — and outside the process, when the platform allows it.
 *
 * Where a plain handler calls a transport and settles itself, this one only *describes* the upload,
 * and every decision around it stays in one place in your code:
 *
 * - **[prepareUpload] runs when the upload actually starts**, not only when the item is first handed
 *   off. A transport that supports it — the `WorkManager` handler transport, the iOS background
 *   transport — persists nothing but the item id and asks [UploadGateway.prepareAttempt] for the
 *   request right before the transfer. So a token in a header is fresh when it is used and never sits
 *   in a scheduler's database, a request larger than a platform job's data limit is not a problem,
 *   and a re-run of an item that was settled meanwhile finds nothing owed and uploads nothing.
 *   Return [UploadPreparation.Drop] or [UploadPreparation.Park] from it to finish an item that should
 *   no longer upload — a deleted source file, a submission the server already has.
 * - **[classify] turns the raw outcome into a [SettleResult]**, with the payload at hand: a 401 can
 *   refresh a token and fail for a retry, a 409 can drop, a 413 can park.
 * - **Hooks** react to the outcome: [onUploadProgress] for a progress bar, [onDelivered] for a local
 *   write that must happen before the row disappears, [onSettled] for everything after it.
 *
 * Every hook runs isolated: an exception from one is logged and does not change the settlement — the
 * upload already happened, and failing locally must not make it happen twice.
 *
 * ```kotlin
 * class AvatarUploadHandler(transport: UploadTransport, private val auth: Auth) :
 *     UploadHandler<AvatarUpload>(transport) {
 *
 *     override val type: String = "avatar_upload"
 *     override fun encodePayload(payload: AvatarUpload): String = json.encodeToString(payload)
 *     override fun decodePayload(raw: String): AvatarUpload = json.decodeFromString(raw)
 *
 *     override suspend fun prepareUpload(context: AttemptContext, payload: AvatarUpload): UploadPreparation {
 *         if (!File(payload.path).exists()) return UploadPreparation.Drop("source deleted")
 *         return UploadPreparation.Proceed(
 *             UploadRequest(
 *                 url = "https://api.example.com/avatar",
 *                 headers = mapOf("Authorization" to "Bearer ${auth.token()}"),
 *                 fields = listOf(UploadField.File("file", "avatar.jpg", "image/jpeg", payload.path)),
 *             ),
 *         )
 *     }
 *
 *     override suspend fun classify(payload: AvatarUpload, result: UploadResult): SettleResult =
 *         defaultUploadClassification(result)
 * }
 * ```
 *
 * The transport must be one that settles through [UploadGateway]; `createWorkManagerUploadTransport`,
 * which settles through a single global classifier instead, is not.
 *
 * @param transport runs the upload. Its [UploadTransport.leaseMillis] is the lease every hand-off
 *   is claimed under.
 * @since 1.5.0
 */
public abstract class UploadHandler<P : Any>(
    private val transport: UploadTransport,
) : UploaderHandler<P> {

    /**
     * Describes the upload for one attempt of [payload] — or finishes the item without one.
     *
     * Called when the engine hands the item off, and again by [UploadGateway.prepareAttempt] each time
     * a transport is about to run it. Keep it idempotent and quick: it may run several times for one
     * item, and it runs on whatever thread the transport calls from. A thrown exception is a transient
     * failure — the hand-off is retried under [retryPolicy], a preparation at run time leaves the item
     * in flight for the lease to recover.
     */
    public abstract suspend fun prepareUpload(context: AttemptContext, payload: P): UploadPreparation

    /**
     * What the upload's raw [result] means for the item. See [defaultUploadClassification] for a
     * reasonable baseline. A thrown exception settles the item as [SettleResult.Failed].
     */
    public abstract suspend fun classify(payload: P, result: UploadResult): SettleResult

    /**
     * Upload progress of [payload], from 0 to 1. Best effort: whole percents only, possibly
     * skipping some, and never after the item has settled.
     */
    public open suspend fun onUploadProgress(payload: P, fraction: Float) {}

    /**
     * Called when [classify] said [SettleResult.Delivered], **before** the item's row is removed — the
     * place for a local write that marks the domain object as sent, so the two cannot disagree after a
     * crash in between.
     */
    public open suspend fun onDelivered(payload: P) {}

    /**
     * Called after the item settled as [result]. [attempts] is the retry count the item had when it
     * was handed off — `0` for a first attempt. The row is already updated, so deleting the uploaded
     * source file here cannot strand an item that is still owed.
     */
    public open suspend fun onSettled(payload: P, attempts: Int, result: SettleResult) {}

    /** Prepares the request and hands it to the transport; the transport settles through [UploadGateway]. */
    final override suspend fun execute(context: AttemptContext, payload: P): AttemptResult =
        when (val preparation: UploadPreparation = prepareUpload(context, payload)) {
            is UploadPreparation.Drop -> AttemptResult.Drop(preparation.reason)
            is UploadPreparation.Park -> AttemptResult.Park(preparation.reason)
            is UploadPreparation.Proceed -> {
                val lease: Long = transport.leaseMillis
                if (lease <= 0) {
                    // A non-positive lease would never read as in flight and re-hand on every pass.
                    AttemptResult.Park("UploadTransport.leaseMillis must be > 0, was $lease")
                } else {
                    // A throw here — the platform scheduler unavailable — propagates to the engine,
                    // which makes it a Retry for this item alone.
                    transport.launch(context.id, context.wasDetached, preparation.request)
                    AttemptResult.Detached(lease)
                }
            }
        }
}

/**
 * What [UploadHandler.prepareUpload] decided for one attempt.
 *
 * @since 1.5.0
 */
public sealed interface UploadPreparation {

    /** Upload [request]. */
    public data class Proceed(val request: UploadRequest) : UploadPreparation

    /** Nothing to upload, and nothing owed: remove the item. */
    public data class Drop(val reason: String) : UploadPreparation

    /** Cannot upload, and retrying will not help: keep the item, visibly, for inspection. */
    public data class Park(val reason: String) : UploadPreparation
}
