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
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * Unique work keyed by the item id: a hand-off while the item's job is still serving it is absorbed, and that
 * job serves it before it returns; a hand-off after the job finished serving replaces it with a fresh one
 * (`ExistingWorkPolicy.REPLACE`), so a retry is never left waiting for the lease because `WorkManager` still
 * reported the old job running. Hand-offs are tracked per process; across processes the lease is the bound. The
 * job's outcome reaches the engine or the job is retried by `WorkManager` — an outcome is never dropped. One job per item runs at a time in a process: a job
 * that starts while another of the same item still runs (WorkManager reports a stopped job finished while its
 * transfer or settlement continues) waits for it.
 *
 * Works only with items whose handler is an [UploadHandler]; anything else is parked at run time.
 *
 * @param config names, timeouts and lease — the same [UploadTransportConfig] as the classifier-based
 *   transport. Pin [UploadTransportConfig.uniqueWorkNamePrefix] and [UploadTransportConfig.workTag] if
 *   jobs enqueued by an earlier version of your app must keep matching.
 * @param logger where hand-offs and failures are reported. A logger that throws is ignored; it never stops a
 *   hand-off or a job.
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
    logger: Logger,
    private val journal: OutcomeJournal = androidOutcomeJournal(context, logger, config.journalDirectoryName),
) : UploadTransport {

    /** A logger that throws is ignored: it never stops a hand-off or the sweep. */
    private val logger: Logger = logger.nonThrowing()

    override val leaseMillis: Long = config.leaseMillis

    /**
     * The startup sweep: outcomes a dead process journaled and nobody collected are replayed to the engine, or the
     * expired ones deleted. The engine may not be registered yet, so this waits for it off the caller's thread.
     * Joined only by tests.
     */
    internal val sweep: Job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        runCatching {
            journal.sweep(gatewaySettle(config.engineWait))
        }.onFailure { failure -> this@WorkManagerUploadHandlerTransport.logger.w(failure) { "The upload outcome journal sweep failed." } }
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
            .setInputData(uploadHandlerInputData(itemId, config))
            // Paces WorkManager's own re-run of a killed or engine-less job; delivery retries are paced
            // by the handler's RetryPolicy in the engine.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, config.workBackoff.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .addTag(config.resolveWorkTag(context.packageName))
            .build()
        // Decided atomically with the serving job's close: see UploadHandOffLedger.
        val policy: ExistingWorkPolicy = UploadHandOffLedger.shared.policyForHandOff(itemId)
        logger.i { "Upload hand-off — item=$itemId, rehand=$isRehandOff, policy=$policy" }
        WorkManager.getInstance(context).enqueueUniqueWork(
            config.resolveUniqueWorkName(context.packageName, itemId),
            policy,
            work,
        )
    }

    override fun cancel(itemId: String) {
        // A cancelled item settles nothing, so an outcome journaled for it must not be replayed later.
        journal.delete(itemId)
        UploadHandOffLedger.shared.forget(itemId)
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

        // A job enqueued by an earlier version carries no journal directory name: the default is the name it used.
        val journalDirectoryName: String = resolveJournalDirectoryName(
            inputData.getString(JOURNAL_DIRECTORY_NAME_KEY),
            createLoggerFactory().logger("UploadHandlerWorker"),
        )
        val journal: OutcomeJournal = androidOutcomeJournal(applicationContext, directoryName = journalDirectoryName)
        val ledger: UploadHandOffLedger = UploadHandOffLedger.shared
        // Counted before waiting for the item's lock: a job that waits will still read the row.
        ledger.jobStarted(itemId)
        try {
            // One job per item at a time in this process: a job that starts while another of this item is still
            // running (WorkManager reports a stopped job finished while its transfer or commit still runs) waits for
            // it, then replays what it left or finds nothing owed, instead of uploading the file a second time and
            // racing its journal entry. Inside, only the held item's operations are used: the lock is not reentrant.
            return journal.exclusively(itemId) {
                var result: Result
                // A hand-off that arrived after this iteration read the row (MISSED) is served here, by the same job:
                // WorkManager may still report this job running, and would drop the hand-off's own job. Only a success
                // finishes the job; a retry keeps its WorkSpec (and its run count) for WorkManager's own re-run.
                do {
                    val seen: Long = ledger.handOffsSeen(itemId)
                    result = serveOnce(itemId, engineWait)
                } while (result is Result.Success &&
                    ledger.finishServing(itemId, seen, isStopped) == UploadHandOffLedger.Finish.MISSED
                )
                result
            }
        } finally {
            ledger.jobEnded(itemId)
        }
    }

    /** One serving iteration, run with the item's lock held: replay what is journaled, or prepare, upload and settle. */
    private suspend fun OutcomeJournal.HeldItem.serveOnce(itemId: String, engineWait: Duration): Result {
        // The item's lock is held by the caller (see doWork); only the held item's operations are used here.
        // An outcome journaled by an earlier run of this job — the process died between the server's answer
        // and the engine's settlement — is replayed instead of uploading again. A leftover of an attempt that
        // was already settled is recognised by its stamp and dropped, and the job uploads.
        when (replay(gatewaySettle(engineWait))) {
            ReplayOutcome.Replayed -> return Result.success()
            ReplayOutcome.Kept -> return Result.retry()
            // "Not readable" never means "handled": wait for it over a few runs, then give it up.
            ReplayOutcome.Unreadable -> {
                if (runAttemptCount < UNREADABLE_ENTRY_RUNS) return Result.retry()
                delete()
            }
            ReplayOutcome.NoEntry, ReplayOutcome.Discarded, ReplayOutcome.Stale -> Unit
        }

        val prepared: PreparedUpload = UploadGateway.prepareStampedAttempt(itemId, engineWait)
        val attemptStamp: Int? = prepared.attemptStamp
        val request: UploadRequest = when (val attempt: UploadAttempt = prepared.attempt) {
            UploadAttempt.EngineUnavailable -> return Result.retry()
            UploadAttempt.NothingOwed -> return Result.success()
            is UploadAttempt.Ready -> attempt.request
        }
        // The handler's body limit, read together with the row the attempt was prepared from: the journal
        // keeps no more of the body than the handler asked for.
        val bodyLimit: Int = prepared.bodyLimit

        // The answer is journaled the moment the transfer returns, before anything waits on the progress
        // forwarder: a slow hook may lose progress, never the answer.
        var journaled = false
        val outcome: MultipartOutcome = uploadReportingProgress(itemId, request) { answered ->
            val answer: UploadResponse? = answered.response
            if (answer != null) journaled = record(answer, bodyLimit, attemptStamp)
        }
        // The server's answer, when there was one, goes to the handler with the status. An outcome that
        // did not reach the engine must not be lost: re-run. It is written to disk first, so a re-run in a
        // new process replays it instead of uploading again; without the journal (a write that failed) the
        // re-run prepares first, so an item that settled meanwhile uploads nothing — but one still in flight
        // uploads again, which is why the handler's onDelivered must tolerate a repeat. The body stays in
        // this process and the journal: it is never put into WorkManager's data. Every settlement carries the
        // attempt stamp, so a late or duplicate report is never charged to a newer attempt.
        val response: UploadResponse? = outcome.response
        val landed: Boolean = if (response != null) {
            if (journaled) {
                settleRecorded(response, attemptStamp, bodyLimit, gatewaySettle(engineWait))
            } else {
                UploadGateway.completeAttempt(itemId, outcome.result, response, attemptStamp, engineWait)
            }
        } else {
            UploadGateway.completeAttempt(itemId, outcome.result, null, attemptStamp, engineWait)
        }
        return if (landed) Result.success() else Result.retry()
    }

    /** The item this job uploads. Override only to read an input key an earlier worker of yours used. */
    protected open fun readItemId(inputData: Data): String? = inputData.getString(ITEM_ID_KEY)

    /**
     * Uploads while forwarding whole-percent progress. The blocking write loop only drops fractions into
     * a conflated channel and a companion coroutine forwards them, so reporting never stalls the
     * transfer. [onAnswer] runs on the transfer's thread the moment it returns, before the forwarder is
     * awaited, so the caller can put the answer on disk first. The forwarder is then given
     * [PROGRESS_FLUSH_TIMEOUT] to drain, so progress signals land before the settle unless the hook takes
     * longer than that: the hook is then cancelled and no longer awaited, because a progress hook must never
     * hold up the settlement.
     * The forwarder runs on a small dedicated view of the IO pool, never on the worker's own threads, so a hook
     * that blocks its thread delays other items' progress at most, never a settlement or a transfer.
     */
    private suspend fun uploadReportingProgress(
        itemId: String,
        request: UploadRequest,
        onAnswer: (MultipartOutcome) -> Unit,
    ): MultipartOutcome {
        val fractions: Channel<Float> = Channel(Channel.CONFLATED)
        // Outside the job's structured scope on purpose: a hook that ignores cancellation, or blocks a
        // thread, keeps running in the background but no longer delays the settlement.
        // Never the worker's Dispatchers.Default: a hook that blocks its thread then costs other items' progress at
        // most, never a settlement or a transfer.
        val progressScope = CoroutineScope(PROGRESS_HOOKS + SupervisorJob())
        val forwarder: Job = progressScope.launch { for (fraction in fractions) UploadGateway.progress(itemId, fraction) }
        try {
            // CoroutineWorker runs on Dispatchers.Default; a blocking transfer of minutes belongs on IO.
            val readTimeoutMillis: Int = inputData.getInt(
                READ_TIMEOUT_MILLIS_KEY,
                UploadTransportConfig.DEFAULT_READ_TIMEOUT_MILLIS,
            )
            return withWriteWatchdog(readTimeoutMillis) { watch ->
                withContext(Dispatchers.IO) {
                    val transfer: Job = coroutineContext.job
                    performMultipartUploadCapturing(
                        request = request,
                        connectTimeoutMillis = inputData.getInt(
                            CONNECT_TIMEOUT_MILLIS_KEY,
                            UploadTransportConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                        ),
                        readTimeoutMillis = readTimeoutMillis,
                        onWholePercent = { fraction -> fractions.trySend(fraction) },
                        isCancelled = { !transfer.isActive },
                        watch = watch,
                    // A stop that lands while the body is read must not throw away a status the server already
                    // sent: withContext discards a result whose job was cancelled, so the answer is journaled
                    // here, on the transfer's thread (a synchronous file write belongs on IO), right when the
                    // transfer returns.
                    ).also(onAnswer)
                }
            }
        } finally {
            fractions.close()
            // Bounded: a hook still running after the flush window loses the remaining progress and is
            // cancelled, never awaited again; the inner finally also cancels it when the job itself is
            // stopped during the join.
            try {
                withTimeoutOrNull(PROGRESS_FLUSH_TIMEOUT) { forwarder.join() }
            } finally {
                progressScope.cancel()
            }
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

        /**
         * `String` input-data key: the name of the outcome journal's directory
         * ([UploadTransportConfig.journalDirectoryName]). Absent in jobs enqueued by versions before 5.0.0; the worker
         * then uses [UploadTransportConfig.DEFAULT_JOURNAL_DIRECTORY_NAME].
         * @since 5.0.0
         */
        public const val JOURNAL_DIRECTORY_NAME_KEY: String = "kmptoolkit_uploader_journal_directory_name"
    }
}

/**
 * An answer on disk that cannot be read is waited for while the job has run fewer than this many times (the job's
 * run count, WorkManager's `runAttemptCount`, which also counts retries for other reasons); then it is given up and
 * the item uploads again, the documented at-least-once fallback.
 */
private const val UNREADABLE_ENTRY_RUNS: Int = 3

/** Threads the progress hooks of all jobs share; a shared view of the IO pool, not a lock. */
private const val PROGRESS_HOOK_THREADS: Int = 4

private val PROGRESS_HOOKS: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(PROGRESS_HOOK_THREADS)

/** How long the worker waits for [UploadHandler.onUploadProgress] to drain after the transfer ended. */
private val PROGRESS_FLUSH_TIMEOUT: kotlin.time.Duration = 2.seconds

/** The input data of the job that serves [itemId]: the item id and the settings the worker reads back. */
internal fun uploadHandlerInputData(itemId: String, config: UploadTransportConfig): Data = workDataOf(
    UploadHandlerWorker.ITEM_ID_KEY to itemId,
    UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to config.engineWaitMillis,
    UploadHandlerWorker.CONNECT_TIMEOUT_MILLIS_KEY to config.connectTimeoutMillis,
    UploadHandlerWorker.READ_TIMEOUT_MILLIS_KEY to config.readTimeoutMillis,
    UploadHandlerWorker.JOURNAL_DIRECTORY_NAME_KEY to config.journalDirectoryName,
)

/**
 * The journal directory a job's input data names: [raw] when it is a plain file name, the default when it is absent
 * (a job enqueued by 4.x), and the default with a warning naming the rejected value when it is not a plain name. Never
 * silent: a second directory would split the journal. The warning goes to the platform log: the worker has no
 * consumer-supplied logger.
 */
internal fun resolveJournalDirectoryName(raw: String?, logger: Logger): String {
    if (raw == null) return UploadTransportConfig.DEFAULT_JOURNAL_DIRECTORY_NAME
    if (FileNames.isPlainName(raw)) return raw
    logger.w { "The journal directory name '$raw' in the job's input data is not a plain file name; using the default '${UploadTransportConfig.DEFAULT_JOURNAL_DIRECTORY_NAME}'." }
    return UploadTransportConfig.DEFAULT_JOURNAL_DIRECTORY_NAME
}
