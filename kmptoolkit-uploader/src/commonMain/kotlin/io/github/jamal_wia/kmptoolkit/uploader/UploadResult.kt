package io.github.jamal_wia.kmptoolkit.uploader

/** Raw outcome of one [UploadTransport] attempt, before it is mapped onto [SettleResult]. */
public sealed interface UploadResult {

    /** The server answered — [statusCode] carries the verdict (2xx, 4xx, 5xx…). */
    public data class Completed(val statusCode: Int) : UploadResult

    /** The request never completed — connectivity, timeout, a cancelled transfer. */
    public data class TransportFailure(val message: String?) : UploadResult
}

/**
 * The default mapping from [UploadResult] to [SettleResult]: any 2xx is [SettleResult.Delivered],
 * any 4xx is [SettleResult.Drop] (retrying a client error will not help, and this module has no way
 * to know whether your server would want the row parked instead), anything else — a 5xx, or a
 * [UploadResult.TransportFailure] — is [SettleResult.Failed] and re-enters the handler's
 * [RetryPolicy].
 *
 * This is a default, not a policy this module can claim to know for your API — pass your own
 * `classify` to `createWorkManagerUploadTransport` when a 404 should mean something other than
 * "drop", or when a specific status should park instead of retry.
 */
public fun defaultUploadClassification(result: UploadResult): SettleResult = when (result) {
    is UploadResult.Completed -> when (result.statusCode) {
        in 200..299 -> SettleResult.Delivered
        in 400..499 -> SettleResult.Drop("HTTP ${result.statusCode}")
        else -> SettleResult.Failed(null)
    }
    is UploadResult.TransportFailure -> SettleResult.Failed(null)
}
