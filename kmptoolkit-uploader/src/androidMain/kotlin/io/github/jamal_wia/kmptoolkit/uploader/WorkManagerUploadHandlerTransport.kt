package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Creates the Android [UploadTransport] for an [UploadHandler]: one `WorkManager` job per item, whose
 * persisted data is **the item id and nothing else**.
 *
 * The job asks [UploadGateway.prepareAttempt] for the request when it runs, reports progress, and
 * settles through [UploadGateway.complete] — so the handler's `prepareUpload`, `classify` and hooks run
 * in the engine, per item. Compared with [createWorkManagerUploadTransport]:
 *
 * - **No request at rest.** URL, headers — an `Authorization` token — and field values are never
 *   written to `WorkManager`'s database, and a request larger than `WorkManager`'s 10 KB data limit is
 *   not a problem.
 * - **A re-run after the item settled uploads nothing**: the job finds nothing owed.
 * - **Per-handler classification**, instead of one process-wide classifier.
 * - **Progress** in whole percents, to [UploadHandler.onUploadProgress].
 * - **A failed hand-off is reported**, not swallowed: [UploadTransport.launch] throws when the job
 *   cannot be enqueued, and the engine retries the item under its policy.
 *
 * Unique work keyed by the item id with `ExistingWorkPolicy.KEEP` makes a re-hand while the job is
 * alive a no-op, and re-enqueues after a dead one. The job's outcome reaches the engine or the job is
 * retried by `WorkManager` — an outcome is never dropped.
 *
 * Works only with items whose handler is an [UploadHandler]; anything else is parked at run time.
 *
 * @param config names, timeouts and lease — the same [UploadTransportConfig] as the classifier-based
 *   transport. Pin [UploadTransportConfig.uniqueWorkNamePrefix] and [UploadTransportConfig.workTag] if
 *   jobs enqueued by an earlier version of your app must keep matching.
 * @since 1.5.0
 */
public fun createWorkManagerUploadHandlerTransport(
    context: Context,
    config: UploadTransportConfig,
    logger: Logger = NoopLogger,
): UploadTransport = WorkManagerUploadHandlerTransport(context.applicationContext, config, logger)

internal class WorkManagerUploadHandlerTransport(
    private val context: Context,
    private val config: UploadTransportConfig,
    private val logger: Logger,
    private val journal: OutcomeJournal = androidOutcomeJournal(context, logger),
) : UploadTransport {

    override val leaseMillis: Long = config.leaseMillis

    init {
        // Outcomes a dead process journaled and nobody collected: replay them to the engine, or delete the
        // expired ones. The engine may not be registered yet, so this waits for it off the caller's thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                journal.sweep(gatewaySettle(config.engineWait))
            }.onFailure { failure -> logger.w(failure) { "The upload outcome journal sweep failed." } }
        }
    }

    override fun launch(itemId: String, request: UploadRequest) {
        launch(itemId, isRehandOff = false, request = request)
    }

    override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
        val constraints: Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (config.requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val work: OneTimeWorkRequest = OneTimeWorkRequestBuilder<UploadHandlerWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    UploadHandlerWorker.ITEM_ID_KEY to itemId,
                    UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to config.engineWaitMillis,
                    UploadHandlerWorker.CONNECT_TIMEOUT_MILLIS_KEY to config.connectTimeoutMillis,
                    UploadHandlerWorker.READ_TIMEOUT_MILLIS_KEY to config.readTimeoutMillis,
                ),
            )
            // Paces WorkManager's own re-run of a killed or engine-less job; delivery retries are paced
            // by the handler's RetryPolicy in the engine.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, config.workBackoff.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .addTag(config.resolveWorkTag(context.packageName))
            .build()
        logger.i { "Upload hand-off — item=$itemId, rehand=$isRehandOff" }
        WorkManager.getInstance(context).enqueueUniqueWork(
            config.resolveUniqueWorkName(context.packageName, itemId),
            ExistingWorkPolicy.KEEP,
            work,
        )
    }

    override fun cancel(itemId: String) {
        // A cancelled item settles nothing, so an outcome journaled for it must not be replayed later.
        journal.delete(itemId)
        // A running job's coroutine is cancelled, which stops the body mid-stream; a queued one never runs.
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(config.resolveUniqueWorkName(context.packageName, itemId))
        }.onFailure { failure -> logger.w(failure) { "Could not cancel the upload of item=$itemId" } }
    }
}

/**
 * One upload attempt for [createWorkManagerUploadHandlerTransport]: prepare through the gateway,
 * upload, report the raw outcome.
 *
 * WorkManager instantiates this reflectively; it needs no manifest entry of your own. It is public
 * because WorkManager's default factory must see it, and open for exactly one purpose: an app
 * migrating from its own worker can keep that worker's class name — which jobs already enqueued on
 * installed devices name — as a subclass that reads the item id from its own input key through
 * [readItemId].
 *
 * @since 1.5.0
 */
public open class UploadHandlerWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /**
     * @return `Result.success()` once the outcome reached the engine or nothing was owed;
     *   `Result.retry()` when no engine was registered, or its store failed, to prepare or settle; `Result.failure()` for
     *   input data with no item id.
     */
    override suspend fun doWork(): Result {
        val itemId: String = readItemId(inputData) ?: return Result.failure()
        val engineWait = inputData.getLong(
            ENGINE_WAIT_MILLIS_KEY,
            UploadTransportConfig.DEFAULT_ENGINE_WAIT.inWholeMilliseconds,
        ).milliseconds

        // An outcome journaled by an earlier run of this job — the process died between the server's answer
        // and the engine's settlement — is replayed instead of uploading again.
        val journal: OutcomeJournal = androidOutcomeJournal(applicationContext)
        when (journal.replay(itemId, gatewaySettle(engineWait))) {
            ReplayOutcome.Replayed -> return Result.success()
            ReplayOutcome.Kept -> return Result.retry()
            ReplayOutcome.NoEntry, ReplayOutcome.Discarded -> Unit
        }

        val request: UploadRequest = when (val attempt: UploadAttempt = UploadGateway.prepareAttempt(itemId, engineWait)) {
            UploadAttempt.EngineUnavailable -> return Result.retry()
            UploadAttempt.NothingOwed -> return Result.success()
            is UploadAttempt.Ready -> attempt.request
        }
        // The handler's body limit, asked now while the engine is known to be reachable: the journal keeps no
        // more of the body than the handler asked for.
        val bodyLimit: Int = UploadGateway.responseBodyLimit(itemId, engineWait) ?: MAX_RESPONSE_BODY_BYTES

        val outcome: MultipartOutcome = uploadReportingProgress(itemId, request)
        // The server's answer, when there was one, goes to the handler with the status. An outcome that
        // did not reach the engine must not be lost: re-run. It is written to disk first, so a re-run in a
        // new process replays it instead of uploading again; without the journal (a write that failed) the
        // re-run prepares first, so an item that settled meanwhile uploads nothing — but one still in flight
        // uploads again, which is why the handler's onDelivered must tolerate a repeat. The body stays in
        // this process and the journal: it is never put into WorkManager's data.
        val response: UploadResponse? = outcome.response
        val landed: Boolean = if (response != null) {
            val journaled: Boolean = journal.record(itemId, response, bodyLimit)
            if (journaled) {
                journal.settleRecorded(itemId, response, gatewaySettle(engineWait))
            } else {
                UploadGateway.complete(itemId, response, engineWait)
            }
        } else {
            UploadGateway.complete(itemId, outcome.result, engineWait)
        }
        return if (landed) Result.success() else Result.retry()
    }

    /** The item this job uploads. Override only to read an input key an earlier worker of yours used. */
    protected open fun readItemId(inputData: Data): String? = inputData.getString(ITEM_ID_KEY)

    /**
     * Uploads while forwarding whole-percent progress. The blocking write loop only drops fractions into
     * a conflated channel and a companion coroutine forwards them, so reporting never stalls the
     * transfer; the forwarder is joined before returning, so every progress signal lands before the
     * settle.
     */
    private suspend fun uploadReportingProgress(itemId: String, request: UploadRequest): MultipartOutcome = coroutineScope {
        val fractions: Channel<Float> = Channel(Channel.CONFLATED)
        val forwarder: Job = launch { for (fraction in fractions) UploadGateway.progress(itemId, fraction) }
        try {
            // CoroutineWorker runs on Dispatchers.Default; a blocking transfer of minutes belongs on IO.
            withContext(Dispatchers.IO) {
                val transfer: Job = coroutineContext.job
                performMultipartUploadCapturing(
                    request = request,
                    connectTimeoutMillis = inputData.getInt(
                        CONNECT_TIMEOUT_MILLIS_KEY,
                        UploadTransportConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                    ),
                    readTimeoutMillis = inputData.getInt(
                        READ_TIMEOUT_MILLIS_KEY,
                        UploadTransportConfig.DEFAULT_READ_TIMEOUT_MILLIS,
                    ),
                    onWholePercent = { fraction -> fractions.trySend(fraction) },
                    isCancelled = { !transfer.isActive },
                )
            }
        } finally {
            fractions.close()
            forwarder.join()
        }
    }

    /** Input-data keys this worker reads. */
    public companion object {

        /** `String` input-data key: the uploader item id. */
        public const val ITEM_ID_KEY: String = "kmptoolkit_uploader_item_id"

        /** `Long` input-data key: milliseconds to wait for a registered engine. */
        public const val ENGINE_WAIT_MILLIS_KEY: String = "kmptoolkit_uploader_engine_wait_millis"

        /** `Int` input-data key: the HTTP connect timeout in milliseconds. */
        public const val CONNECT_TIMEOUT_MILLIS_KEY: String = "kmptoolkit_uploader_connect_timeout_millis"

        /** `Int` input-data key: the HTTP read timeout in milliseconds. */
        public const val READ_TIMEOUT_MILLIS_KEY: String = "kmptoolkit_uploader_read_timeout_millis"
    }
}
