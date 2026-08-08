package io.github.jamal_wia.kmptoolkit.outbox

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.outbox.spi.WakeScheduler
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How the Android wake layer schedules its work.
 *
 * @param uniqueWorkName the WorkManager unique-work name the wake job is enqueued under. `null`
 *   (the default) derives it from the application id as `<applicationId>.outbox.wake`, so two apps
 *   built from this library — or an app running two independent queues, once you give the second
 *   one its own name — never collide on WorkManager's global namespace.
 * @param requiresNetwork whether the wake job waits for connectivity before running. `true` by
 *   default, because a queued effect is a network send in almost every case, and waking a process
 *   with no network only to fail every item wastes the user's battery. Set it to `false` if your
 *   handlers deliver over something else.
 * @param initialBackoff WorkManager's own retry backoff after the wake job reports
 *   `Result.retry()` — that is, after the queue did not empty within [drainBudget]. Distinct from
 *   the per-item [RetryPolicy], which paces individual effects. Must be at least 10 seconds,
 *   WorkManager's floor.
 * @param drainBudget how long [OutboxDrainWorker] keeps the process alive waiting for the queue to
 *   empty before reporting `Result.retry()`. Keep it well under WorkManager's ~10-minute
 *   foregroundless limit. Must be positive.
 * @param engineWait how long the worker waits for [OutboxEngineRegistry] to hold a started engine.
 *   It only ever matters when WorkManager dispatches before `Application.onCreate` has finished
 *   wiring things up. Must be positive.
 * @throws IllegalArgumentException if [uniqueWorkName] is blank, or a duration is out of range.
 */
public data class WorkManagerWakeConfig(
    val uniqueWorkName: String? = null,
    val requiresNetwork: Boolean = true,
    val initialBackoff: Duration = DEFAULT_INITIAL_BACKOFF,
    val drainBudget: Duration = DEFAULT_DRAIN_BUDGET,
    val engineWait: Duration = DEFAULT_ENGINE_WAIT,
) {
    init {
        require(uniqueWorkName == null || uniqueWorkName.isNotBlank()) {
            "uniqueWorkName must not be blank; pass null to derive it from the application id."
        }
        require(initialBackoff >= MIN_INITIAL_BACKOFF) {
            "initialBackoff must be >= $MIN_INITIAL_BACKOFF (WorkManager's floor), was $initialBackoff."
        }
        require(drainBudget.isPositive()) { "drainBudget must be positive, was $drainBudget." }
        require(engineWait.isPositive()) { "engineWait must be positive, was $engineWait." }
    }

    /** The defaults, so you can override one and keep the rest. */
    public companion object {

        /** Default [initialBackoff]: 30 seconds. */
        public val DEFAULT_INITIAL_BACKOFF: Duration = 30.seconds

        /** Default [drainBudget]: one minute. */
        public val DEFAULT_DRAIN_BUDGET: Duration = 1.minutes

        /** Default [engineWait]: 5 seconds. */
        public val DEFAULT_ENGINE_WAIT: Duration = 5.seconds

        /** WorkManager rejects a backoff below this. */
        public val MIN_INITIAL_BACKOFF: Duration = 10.seconds

        /** Suffix appended to the application id when [uniqueWorkName] is `null`. */
        public const val WORK_NAME_SUFFIX: String = ".outbox.wake"
    }
}

/** The unique-work name actually used: the configured one, or `<applicationId>.outbox.wake`. */
internal fun WorkManagerWakeConfig.resolveWorkName(applicationId: String): String =
    uniqueWorkName ?: (applicationId + WorkManagerWakeConfig.WORK_NAME_SUFFIX)

/**
 * Creates the Android wake adapter: a WorkManager unique work request that survives process death,
 * so a queue left behind by a killed app is delivered without the user reopening it.
 *
 * `ExistingWorkPolicy.KEEP` makes the engine's frequent `scheduleWake()` calls cheap no-ops while a
 * wake is already armed or running, which matters because the engine arms one per enqueue.
 *
 * The work runs [OutboxDrainWorker], which finds the engine through [OutboxEngineRegistry] — so
 * register the engine after starting it, or the wake job has nothing to wait on. See
 * `docs/kmptoolkit-outbox/05-platform-notes.md`.
 *
 * @param context any context; the application context is extracted internally, so passing an
 *   Activity does not leak it.
 * @param config how the work is scheduled.
 * @param logger where scheduling failures are reported. Defaults to [NoopLogger].
 * @return a [WakeScheduler] to hand to [createOutboxEngine].
 */
public fun createWorkManagerWakeScheduler(
    context: Context,
    config: WorkManagerWakeConfig = WorkManagerWakeConfig(),
    logger: Logger = NoopLogger,
): WakeScheduler = WorkManagerWakeScheduler(context.applicationContext, config, logger)

internal class WorkManagerWakeScheduler(
    private val context: Context,
    private val config: WorkManagerWakeConfig,
    private val logger: Logger,
) : WakeScheduler {

    private val workName: String = config.resolveWorkName(context.packageName)

    override fun scheduleWake() {
        // The contract forbids throwing from here: this runs inside the caller's enqueue, and
        // WorkManager can refuse (an uninitialized WorkManager in a test, a process with no
        // provider). A wake that could not be armed only means delivery waits for the next launch.
        runCatching {
            val constraints: Constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (config.requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED,
                )
                .build()
            val request = OneTimeWorkRequestBuilder<OutboxDrainWorker>()
                .setConstraints(constraints)
                // The worker is constructed by the platform after a process restart and cannot see
                // this config object, so the two durations it needs travel with the request.
                .setInputData(
                    workDataOf(
                        OutboxDrainWorker.DRAIN_BUDGET_MILLIS_KEY to
                            config.drainBudget.inWholeMilliseconds,
                        OutboxDrainWorker.ENGINE_WAIT_MILLIS_KEY to
                            config.engineWait.inWholeMilliseconds,
                    ),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    config.initialBackoff.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
        }.onFailure { failure ->
            logger.w(failure) { "Could not arm the outbox wake; delivery falls back to app launch." }
        }
    }

    override fun cancelWake() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName) }
            .onFailure { failure ->
                logger.d { "Could not cancel the outbox wake: ${failure.message}" }
            }
    }
}

/**
 * The wake job itself: it keeps the process alive while the engine drains, and does no queue work
 * of its own.
 *
 * That division is deliberate. By the time WorkManager runs this, your `Application.onCreate` has
 * already constructed and started the engine; draining from here as well would put two drains in
 * the same process and break the engine's single-flight guarantee. So the worker waits for the
 * registered engine and calls [OutboxEngine.awaitDrained] — holding WorkManager's execution grant
 * open — and reports `Result.retry()` if the queue has not emptied within the budget, which
 * re-wakes with backoff until the engine's own `cancelWake` (queue empty) removes the work.
 *
 * WorkManager instantiates this reflectively; it needs no manifest entry of your own. It is public
 * only because WorkManager's default factory must be able to see the class.
 *
 * A worker is constructed by the platform after a process restart and cannot see your object
 * graph, so the two durations it needs travel in the request's input data — put there by
 * [createWorkManagerWakeScheduler] from your [WorkManagerWakeConfig].
 */
public class OutboxDrainWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /**
     * Waits for a registered engine, then keeps this worker alive until the queue empties.
     *
     * @return `Result.success()` when the queue emptied, `Result.retry()` when it did not — or
     *   when no started engine appeared, in which case the outcome is genuinely unknown and
     *   retrying is the only safe answer.
     */
    override suspend fun doWork(): Result {
        val engineWait: Duration = inputData
            .getLong(
                ENGINE_WAIT_MILLIS_KEY,
                WorkManagerWakeConfig.DEFAULT_ENGINE_WAIT.inWholeMilliseconds,
            )
            .milliseconds
        val engine: OutboxEngine = OutboxEngineRegistry.await(engineWait) ?: return Result.retry()
        val drainBudget: Duration = inputData
            .getLong(
                DRAIN_BUDGET_MILLIS_KEY,
                WorkManagerWakeConfig.DEFAULT_DRAIN_BUDGET.inWholeMilliseconds,
            )
            .milliseconds
        return if (engine.awaitDrained(drainBudget)) Result.success() else Result.retry()
    }

    /** Input-data keys this worker reads. */
    public companion object {

        /**
         * `Long` input-data key: how long, in milliseconds, to wait for the queue to empty before
         * reporting `Result.retry()`. Absent means [WorkManagerWakeConfig.DEFAULT_DRAIN_BUDGET].
         */
        public const val DRAIN_BUDGET_MILLIS_KEY: String = "kmptoolkit_outbox_drain_budget_millis"

        /**
         * `Long` input-data key: how long, in milliseconds, to wait for a registered engine.
         * Absent means [WorkManagerWakeConfig.DEFAULT_ENGINE_WAIT].
         */
        public const val ENGINE_WAIT_MILLIS_KEY: String = "kmptoolkit_outbox_engine_wait_millis"
    }
}
