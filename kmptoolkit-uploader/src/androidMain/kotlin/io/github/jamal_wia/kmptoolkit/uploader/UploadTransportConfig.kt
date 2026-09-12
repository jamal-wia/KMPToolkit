package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How the Android upload transport schedules and runs its work.
 *
 * @param uniqueWorkNamePrefix the WorkManager unique-work name prefix each upload is enqueued under
 *   (suffixed with the item id, so each item gets its own idempotent join point). `null` — the
 *   default — derives it from the application id as `<applicationId>.uploader.upload.`, so two apps
 *   built from this library never collide on WorkManager's global namespace.
 * @param workTag the tag [UploadTransport.cancelAll] cancels by. `null` derives
 *   `<applicationId>.uploader.upload`.
 * @param requiresNetwork whether the job waits for connectivity before running. `true` by default.
 * @param leaseMillis the lease reported to [UploadTransport.leaseMillis] — size it to your uploads'
 *   worst-case completion time. Must be positive.
 * @param workBackoff WorkManager's own retry backoff after a job reports `Result.retry()` — distinct
 *   from the handler's [RetryPolicy], which paces delivery attempts, not job restarts. Must be at
 *   least 10 seconds, WorkManager's floor.
 * @param engineWait how long the worker waits for [UploaderEngineRegistry] to hold a started engine,
 *   and for a registered classifier, before giving up and reporting `Result.retry()`. Only matters
 *   when WorkManager dispatches before your bootstrap has finished. Must be positive.
 * @param connectTimeoutMillis passed to `HttpURLConnection.setConnectTimeout`. Must be positive.
 * @param readTimeoutMillis passed to `HttpURLConnection.setReadTimeout`. Must be positive.
 * @throws IllegalArgumentException if [uniqueWorkNamePrefix] or [workTag] is blank, or a duration or
 *   timeout is out of range.
 */
public data class UploadTransportConfig(
    val uniqueWorkNamePrefix: String? = null,
    val workTag: String? = null,
    val requiresNetwork: Boolean = true,
    val leaseMillis: Long = DEFAULT_LEASE_MILLIS,
    val workBackoff: Duration = DEFAULT_WORK_BACKOFF,
    val engineWait: Duration = DEFAULT_ENGINE_WAIT,
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) {
    init {
        require(uniqueWorkNamePrefix == null || uniqueWorkNamePrefix.isNotBlank()) {
            "uniqueWorkNamePrefix must be null or non-blank, was '$uniqueWorkNamePrefix'."
        }
        require(workTag == null || workTag.isNotBlank()) { "workTag must be null or non-blank, was '$workTag'." }
        require(leaseMillis > 0) { "leaseMillis must be > 0, was $leaseMillis." }
        require(workBackoff >= MIN_WORK_BACKOFF) {
            "workBackoff must be >= $MIN_WORK_BACKOFF (WorkManager's floor), was $workBackoff."
        }
        require(engineWait.isPositive()) { "engineWait must be positive, was $engineWait." }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be > 0, was $connectTimeoutMillis." }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be > 0, was $readTimeoutMillis." }
    }

    public companion object {
        public val DEFAULT_LEASE_MILLIS: Long = 15.minutes.inWholeMilliseconds
        public val DEFAULT_WORK_BACKOFF: Duration = 30.seconds
        public val DEFAULT_ENGINE_WAIT: Duration = 5.seconds
        public const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 30_000
        public const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 60_000
        public val MIN_WORK_BACKOFF: Duration = 10.seconds
        public const val WORK_NAME_PREFIX_SUFFIX: String = ".uploader.upload."
        public const val WORK_TAG_SUFFIX: String = ".uploader.upload"
    }
}

internal fun UploadTransportConfig.resolveUniqueWorkName(applicationId: String, itemId: String): String =
    (uniqueWorkNamePrefix ?: (applicationId + UploadTransportConfig.WORK_NAME_PREFIX_SUFFIX)) + itemId

internal fun UploadTransportConfig.resolveWorkTag(applicationId: String): String =
    workTag ?: (applicationId + UploadTransportConfig.WORK_TAG_SUFFIX)

internal val UploadTransportConfig.engineWaitMillis: Long get() = engineWait.inWholeMilliseconds
internal fun Long.asEngineWaitDuration(): Duration = this.milliseconds
