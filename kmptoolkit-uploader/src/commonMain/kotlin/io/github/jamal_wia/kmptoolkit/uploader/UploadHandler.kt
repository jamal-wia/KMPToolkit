package io.github.jamal_wia.kmptoolkit.uploader

/**
 * An [UploaderHandler] whose delivery is one multipart HTTP upload, run by an [UploadTransport]
 * outside the handler — and outside the process, when the platform allows it.
 *
 * Where a plain handler calls a transport and settles itself, this one only *describes* the upload,
 * and every decision around it stays in one place in your code:
 *
 * - **[prepareUpload] runs when the upload actually starts**, not only when the item is first handed
 *   off. A transport that supports it — the `WorkManager` handler transport — persists nothing but the
 *   item id and asks [UploadGateway.prepareAttempt] for the request right before the transfer. So a
 *   token in a header is fresh when it is used and never sits in a scheduler's database, a request
 *   larger than a platform job's data limit is not a problem, and a re-run of an item that was settled
 *   meanwhile finds nothing owed and uploads nothing. The iOS background transport cannot: a background
 *   `NSURLSession` needs the whole request up front, so it uses the one prepared at hand-off, and the
 *   system daemon keeps its headers for the life of the transfer.
 *   Return [UploadPreparation.Drop] or [UploadPreparation.Park] from it to finish an item that should
 *   no longer upload — a deleted source file, a submission the server already has.
 * - **[classify] turns the raw outcome into a [SettleResult]**, with the payload and the server's
 *   [UploadResponse] at hand: a 401 can refresh a token and fail for a retry, a 409 can drop, a 413 can
 *   park, a 422 can read which field the server rejected.
 * - **Hooks** react to the outcome: [onUploadProgress] for a progress bar, [onDelivered] for a local
 *   write that must happen before the row disappears — it also receives the server's response, so a
 *   created resource's id or a job id can be saved there — and [onSettled] for everything after it.
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
 *     override suspend fun onDelivered(payload: AvatarUpload, response: UploadResponse?) {
 *         // The server answered 202 with a job id; keep it. Idempotent: this may run twice.
 *         val jobId: String? = response?.header("location") ?: response?.let { parseJobId(it.body()) }
 *         if (jobId != null) avatars.saveJobId(payload.id, jobId)
 *     }
 * }
 * ```
 *
 * The transport must be one that settles through [UploadGateway]; `createWorkManagerUploadTransport`,
 * which settles through a single global classifier instead, is not.
 *
 * `classify` is optional: without an override, [defaultUploadClassification] decides.
 *
 * @param transport runs the upload. Its [UploadTransport.leaseMillis] is the lease every hand-off
 *   is claimed under.
 * @since 1.5.0
 */
public abstract class UploadHandler<P : Any>(
    internal val transport: UploadTransport,
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
     * The largest response body, in bytes, this handler wants to see in [classify] and [onDelivered].
     * Defaults to [DEFAULT_MAX_RESPONSE_BODY_BYTES]; read each time a response is settled.
     *
     * The engine is the one place that enforces it: the effective limit is this value clamped to
     * `0..1 MiB` — a negative value counts as `0` (and is logged once), a value above 1 MiB as 1 MiB, and a
     * getter that throws counts as the default.
     * A body longer than the limit is cut to it and [UploadResponse.isBodyTruncated] is set; that never
     * causes a retry. `0` captures no body at all, but the status and headers are still delivered.
     *
     * @since 4.0.0
     */
    public open val maxResponseBodyBytes: Int get() = DEFAULT_MAX_RESPONSE_BODY_BYTES

    /**
     * What the upload's raw [result] means for the item, given what the server [response] said. See
     * [defaultUploadClassification] for a reasonable baseline. A thrown exception settles the item as
     * [SettleResult.Failed] — so **never throw because a body is malformed**: decide on the status, and
     * treat a body you cannot read as absent.
     *
     * This is the hook the engine calls, for every outcome. [response] is `null` exactly when the server
     * never answered — [result] is then [UploadResult.TransportFailure] — or when a custom transport
     * reported through the status-only [UploadGateway.complete]. Whenever it is not `null`, [result] is
     * `UploadResult.Completed(response.statusCode)`.
     *
     * The default calls the two-argument [classify], so a handler written for 3.0 keeps working
     * unchanged.
     *
     * @since 4.0.0
     */
    public open suspend fun classify(payload: P, result: UploadResult, response: UploadResponse?): SettleResult =
        classify(payload, result)

    /**
     * What the upload's raw [result] means for the item, without the server's response. Kept for
     * handlers written before 4.0.0, and no longer abstract: the default is
     * [defaultUploadClassification]. Override the three-argument [classify] to see the response.
     * A thrown exception settles the item as [SettleResult.Failed].
     */
    public open suspend fun classify(payload: P, result: UploadResult): SettleResult =
        defaultUploadClassification(result)

    /**
     * Upload progress of [payload], from 0 to 1. Best effort: whole percents only, possibly
     * skipping some, and never after the item has settled. Must not block: a hook still running 2 seconds
     * after the transfer ended is cancelled and no longer awaited, so it cannot hold up the settlement;
     * one that ignores cancellation runs on in the background. Do not block a thread here. On Android the hook
     * runs on a small dedicated pool, so a blocked thread delays other items' progress, never a settlement.
     */
    public open suspend fun onUploadProgress(payload: P, fraction: Float) {}

    /**
     * Called when [classify] said [SettleResult.Delivered], **before** the item's row is removed — the
     * place for a local write that marks the domain object as sent, so the two cannot disagree after a
     * crash in between.
     *
     * [response] is what the server answered: parse it and persist what the app needs — a created
     * resource's id, a job id — here. It may run twice for one item (a settle that did not land is
     * retried), so make the write idempotent, keyed by something of the payload. An exception thrown
     * here is logged and does **not** stop the settlement, so the response is then lost: keep it simple
     * and make a failure here rare. [response] is `null` only when a custom transport reported through
     * the status-only [UploadGateway.complete].
     *
     * The default calls the one-argument [onDelivered], so a handler written for 3.0 keeps working.
     *
     * @since 4.0.0
     */
    public open suspend fun onDelivered(payload: P, response: UploadResponse?) {
        onDelivered(payload)
    }

    /**
     * Called when [classify] said [SettleResult.Delivered], **before** the item's row is removed.
     * Kept for handlers written before 4.0.0; override the two-argument [onDelivered] to see the
     * server's response.
     */
    public open suspend fun onDelivered(payload: P) {}

    /**
     * Called after the item settled as [result]. [attempts] is the retry count the item had when it
     * was handed off — `0` for a first attempt. The row is already updated, so deleting the uploaded
     * source file here cannot strand an item that is still owed.
     *
     * Runs at most once, after the item's row was committed, in the process that committed it. A delivery or drop runs it
     * once its [onDelivered] (if any) ran, even when the item was withdrawn during the settlement; a park or failure
     * report that a newer settlement superseded runs no hook. A stop of
     * the platform job, an engine close or a process death right after the commit can skip it, and nothing
     * calls it later. Do not make it the only place that frees a resource such as the uploaded source file;
     * reconcile leftovers at start, or free on [onDelivered] (at least once, before the commit) where that fits.
     */
    public open suspend fun onSettled(payload: P, attempts: Int, result: SettleResult) {}

    /**
     * Prepares the request and hands it to the transport; the transport settles through [UploadGateway].
     *
     * The engine does not call this: it runs the same steps itself so it can claim the item's lease
     * **before** the transport launches. This implementation is for code that drives a handler directly.
     */
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

    /** Constants of [UploadHandler]. */
    public companion object {

        /**
         * The default of [maxResponseBodyBytes]: 64 KiB, room for a JSON answer or a validation error.
         *
         * @since 4.0.0
         */
        public const val DEFAULT_MAX_RESPONSE_BODY_BYTES: Int = 65_536
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
