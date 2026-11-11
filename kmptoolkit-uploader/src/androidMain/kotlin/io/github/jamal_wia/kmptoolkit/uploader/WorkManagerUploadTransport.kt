package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Creates the Android [UploadTransport]: one WorkManager job per outbox item, keyed by item id: `KEEP` while a job
 * serves the item (the idempotent-launch contract [UploadTransport.launch] requires), `REPLACE` once it finished
 * serving. A hand-off that arrives while the item's job is finishing is not dropped: the job serves it before it
 * returns (when the item is still owed), and a hand-off after the job finished serving replaces it with a fresh job.
 * Hand-offs are tracked per process; across processes the lease is the bound.
 *
 * `classify` is remembered process-wide (the worker that actually calls it is instantiated
 * reflectively by WorkManager and cannot see this call's closure) — the last-created transport's
 * `classify` is the one every subsequent job uses, exactly like [UploaderEngineRegistry]. Create one
 * transport per process, from the same place you create your [UploaderEngine].
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param config scheduling and HTTP timing knobs; see [UploadTransportConfig].
 * @param classify maps the transport's raw [UploadResult] onto [SettleResult]. Defaults to
 *   [defaultUploadClassification].
 * @param logger where a job that could not be enqueued, or a transport failure, is reported. A logger that
 *   throws is ignored; it never stops a hand-off or a job.
 */
public fun createWorkManagerUploadTransport(
    context: Context,
    config: UploadTransportConfig = UploadTransportConfig(),
    classify: (UploadResult) -> SettleResult = ::defaultUploadClassification,
    logger: Logger = NoopLogger,
): UploadTransport {
    UploadClassifierRegistry.register(classify)
    return WorkManagerUploadTransport(context.applicationContext, config, logger)
}

internal class WorkManagerUploadTransport(
    private val context: Context,
    private val config: UploadTransportConfig,
    logger: Logger,
) : AttemptStampedTransport {

    /** A logger that throws is ignored: it never stops a hand-off. */
    private val logger: Logger = logger.nonThrowing()

    override val leaseMillis: Long = config.leaseMillis

    override fun launch(itemId: String, request: UploadRequest) {
        // A plain handler calls this from `execute`, which always runs inside the item's attempt: the engine's
        // running attempt is the attempt this hand-off belongs to.
        val stamp: Int? = (UploaderEngineRegistry.current as? DefaultUploaderEngine)?.runningAttemptStamp(itemId)
        launch(itemId, request, attemptStamp = stamp)
    }

    override fun launch(itemId: String, isRehandOff: Boolean, attemptStamp: Int, request: UploadRequest) {
        launch(itemId, request, attemptStamp)
    }

    private fun launch(itemId: String, request: UploadRequest, attemptStamp: Int?) {
        runCatching {
            val constraints: Constraints = Constraints.Builder()
                .setRequiredNetworkType(if (config.requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                .build()
            val work: OneTimeWorkRequest = OneTimeWorkRequestBuilder<UploaderUploadWorker>()
                .setConstraints(constraints)
                .setInputData(
                    request.toWorkData(
                        itemId = itemId,
                        engineWaitMillis = config.engineWaitMillis,
                        connectTimeoutMillis = config.connectTimeoutMillis,
                        readTimeoutMillis = config.readTimeoutMillis,
                    ),
                )
                // Paces WorkManager's own re-run of a killed/gateway-blocked job; delivery retries
                // are paced by the handler's RetryPolicy in the engine, not by this.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, config.workBackoff.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .addTag(config.resolveWorkTag(context.packageName))
                .build()
            // Decided atomically with the serving job's close (see UploadHandOffLedger): a hand-off while the item's job
            // is still serving is absorbed and the job serves it before it returns; one after the job finished
            // serving replaces it, so it is never dropped because WorkManager still lists the old job as running.
            val policy: ExistingWorkPolicy = UploadHandOffLedger.shared.policyForHandOff(itemId, request, attemptStamp)
            WorkManager.getInstance(context).enqueueUniqueWork(
                config.resolveUniqueWorkName(context.packageName, itemId),
                policy,
                work,
            )
        }.onFailure { failure -> logger.w(failure) { "Could not enqueue upload for item=$itemId" } }
    }

    override fun cancel(itemId: String) {
        UploadHandOffLedger.shared.forget(itemId)
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(config.resolveUniqueWorkName(context.packageName, itemId))
        }.onFailure { failure -> logger.w(failure) { "Could not cancel the upload of item=$itemId" } }
    }
}

/**
 * The process-wide slot holding the last-registered `classify` function — the counterpart of
 * [UploaderEngineRegistry] for the one piece of [createWorkManagerUploadTransport] a
 * reflectively-instantiated [UploaderUploadWorker] cannot otherwise reach.
 */
internal object UploadClassifierRegistry {

    private val slot: MutableStateFlow<((UploadResult) -> SettleResult)?> = MutableStateFlow(null)

    fun register(classify: (UploadResult) -> SettleResult) {
        slot.value = classify
    }

    suspend fun await(timeoutMillis: Long): ((UploadResult) -> SettleResult)? {
        // Looked at first: withTimeoutOrNull never runs its block for a zero (or spent) budget, which would
        // read a classifier that is registered right now as absent.
        slot.value?.let { return it }
        return withTimeoutOrNull(timeoutMillis) { slot.filterNotNull().first() }
    }
}

/**
 * One transport attempt: perform the request described in [getInputData], report the outcome to
 * whichever [UploaderEngine] is registered.
 *
 * WorkManager instantiates this reflectively; it needs no manifest entry of your own. It is public
 * only because WorkManager's default factory must be able to see the class.
 */
public class UploaderUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val itemId: String = inputData.readItemId() ?: return Result.failure()
        val request: UploadRequest = inputData.toUploadRequestOrNull() ?: return Result.failure()
        val engineWaitMillis: Long = inputData.readEngineWaitMillis(UploadTransportConfig.DEFAULT_ENGINE_WAIT.inWholeMilliseconds)

        // The engine's own implementation, for its current-claim settle route: this job is unique per item
        // id, so it serves every claim of the item and has no claim of its own to report under.
        val engine: DefaultUploaderEngine = UploaderEngineRegistry.await(engineWaitMillis.asEngineWaitDuration())
            as? DefaultUploaderEngine
            ?: return Result.retry()
        val classify: (UploadResult) -> SettleResult = UploadClassifierRegistry.await(engineWaitMillis)
            ?: return Result.retry()

        val connectTimeoutMillis: Int =
            inputData.readConnectTimeoutMillis(UploadTransportConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS)
        val readTimeoutMillis: Int = inputData.readReadTimeoutMillis(UploadTransportConfig.DEFAULT_READ_TIMEOUT_MILLIS)

        val ledger: UploadHandOffLedger = UploadHandOffLedger.shared
        ledger.jobStarted(itemId)
        try {
            var current: UploadRequest = request
            while (true) {
                val served: Served = serveOnce(itemId, current, engine, classify, connectTimeoutMillis, readTimeoutMillis, engineWaitMillis)
                if (served.result !is Result.Success) return served.result
                // A hand-off the transport counted after this iteration's settlement began is served here, by the
                // same job: WorkManager may still list this job as running and would drop the hand-off's own job.
                // Only a success finishes the job; a retry keeps its WorkSpec for WorkManager's own re-run.
                var seen: Long = served.handOffsSeenBeforeSettle
                var next: UploadRequest? = null
                while (true) {
                    if (ledger.finishServing(itemId, seen, isStopped) != UploadHandOffLedger.Finish.MISSED) break
                    if (isStopped) break
                    // The counter first, the row second: a hand-off that lands after the row was read is counted
                    // past `now` and found by the next finishServing. A hand-off that reached the transport before
                    // the settlement committed, for the attempt just settled, finds the row delivered, withdrawn
                    // or waiting out a backoff: nothing is owed and nothing uploads. One that found the row in
                    // flight again at the attempt the hand-off was made for is the retry's own and is uploaded once,
                    // with its request. A hand-off stamped with an older attempt (a duplicate that landed during a
                    // zero-backoff commit) is not served for the newer attempt: the retry's own hand-off moves the
                    // counter and is found by the next finishServing. An unstamped hand-off (a wrapped transport)
                    // is served whenever the row is in flight.
                    val now: Long = ledger.handOffsSeen(itemId)
                    // A retry's hand-off is counted from `execute`, before the engine marks the row in flight: wait
                    // (bounded) for that attempt to end, or the row would read as not owed and the retry would
                    // wait for its lease.
                    withContext(NonCancellable) { engine.awaitHandOff(itemId, engineWaitMillis) }
                    val latest: UploadHandOffLedger.HandOff? = ledger.latestHandOff(itemId)
                    val attempts: Int? = if (latest == null) null else engine.attemptsIfInFlight(itemId)
                    if (latest != null && attempts != null && (latest.attempt == null || latest.attempt == attempts)) {
                        next = latest.request
                        break
                    }
                    seen = now
                }
                current = next ?: return served.result
            }
        } finally {
            ledger.jobEnded(itemId)
        }
    }

    /** What one serving iteration of the classic job did. */
    private class Served(val result: Result, val handOffsSeenBeforeSettle: Long)

    /** One iteration: transfer [request], classify the outcome and settle it under the item's current claim. */
    private suspend fun serveOnce(
        itemId: String,
        request: UploadRequest,
        engine: DefaultUploaderEngine,
        classify: (UploadResult) -> SettleResult,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        engineWaitMillis: Long,
    ): Served {
        // CoroutineWorker runs on Dispatchers.Default; a blocking transfer of minutes belongs on IO, polling
        // its own job so a cancelled job stops the body instead of finishing it unseen.
        val outcome: UploadResult = withWriteWatchdog(readTimeoutMillis) { watch ->
            withContext(Dispatchers.IO) {
                val transfer: Job = coroutineContext.job
                performMultipartUpload(
                    request = request,
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis,
                    isCancelled = { !transfer.isActive },
                    watch = watch,
                )
            }
        }
        // classify is the app's code: a throw is a failed attempt of this item, never a lost outcome — an
        // exception out of doWork would leave the row in flight, and the whole file would upload again once
        // the lease runs out, for as long as classify keeps throwing for that answer.
        val verdict: SettleResult = try {
            classify(outcome)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            SettleResult.Failed(e)
        } catch (e: Throwable) {
            SettleResult.Failed(e)
        }
        // Read before the commit: a hand-off counted after this point may be for the retry the commit creates,
        // and the job must find it when it closes the iteration.
        val seenBeforeSettle: Long = UploadHandOffLedger.shared.handOffsSeen(itemId)
        // The upload already happened, so the write that records it must not be lost to a stop of this job
        // (WorkManager stops a job whose constraints went away): it runs to completion instead of being
        // cancelled with the job. NonCancellable is the right tool for a short cleanup of a fact already
        // known. Unbounded, as the engine's own upload commit is: engineWait is only the wait for an engine
        // (above), and abandoning a slow commit would leave the row in flight and upload the file again.
        // A plain handler launches this job from `execute`, before the engine wrote the claim: the settle waits
        // (bounded) for that attempt to end, or its Failed or Park would read the row under the old claim and be
        // dropped as stale, leaving the item in flight with no job behind it until the lease ran out.
        val landed: Boolean = withContext(NonCancellable) {
            engine.awaitHandOff(itemId, engineWaitMillis)
            engine.settleUnderCurrentClaim(itemId, verdict)
        }
        return Served(if (landed) Result.success() else Result.retry(), seenBeforeSettle)
    }
}
