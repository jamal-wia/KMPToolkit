package io.github.jamal_wia.kmptoolkit.outbox

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.outbox.spi.WakeScheduler
import kotlin.concurrent.AtomicInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSBundle

/**
 * How the iOS wake layer submits its background task.
 *
 * @param taskIdentifier the `BGTaskScheduler` identifier. `null` (the default) derives it from the
 *   app's bundle id as `<bundleId>.outbox.drain`. **Whatever it resolves to must also appear in
 *   your `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`, and be registered in Swift
 *   before `didFinishLaunching` returns** — iOS raises otherwise. Read the resolved value from
 *   [BackgroundTaskWakeScheduler.taskIdentifier] if you let it default.
 * @param requiresNetworkConnectivity whether iOS should hold the task until the device has
 *   network. `true` by default, for the same reason as on Android.
 * @param requiresExternalPower whether iOS should hold the task until the device is charging.
 *   `false` by default — requiring power makes an already opportunistic wake considerably rarer.
 * @param engineWait how long the wake handler waits for [OutboxEngineRegistry] to hold a started
 *   engine. Must be positive.
 * @param drainBudget how long the wake handler keeps working before reporting back. iOS grants a
 *   `BGProcessingTask` minutes, but ending early and voluntarily is much better than being killed;
 *   the queue is picked up again at the next wake or launch. Must be positive.
 * @throws IllegalArgumentException if [taskIdentifier] is blank or a duration is not positive.
 */
public data class BackgroundTaskWakeConfig(
    val taskIdentifier: String? = null,
    val requiresNetworkConnectivity: Boolean = true,
    val requiresExternalPower: Boolean = false,
    val engineWait: Duration = DEFAULT_ENGINE_WAIT,
    val drainBudget: Duration = DEFAULT_DRAIN_BUDGET,
) {
    init {
        require(taskIdentifier == null || taskIdentifier.isNotBlank()) {
            "taskIdentifier must not be blank; pass null to derive it from the bundle id."
        }
        require(engineWait.isPositive()) { "engineWait must be positive, was $engineWait." }
        require(drainBudget.isPositive()) { "drainBudget must be positive, was $drainBudget." }
    }

    /** The defaults, so you can override one and keep the rest. */
    public companion object {

        /** Default [engineWait]: 5 seconds. */
        public val DEFAULT_ENGINE_WAIT: Duration = 5.seconds

        /** Default [drainBudget]: 25 seconds. */
        public val DEFAULT_DRAIN_BUDGET: Duration = 25.seconds

        /** Suffix appended to the bundle id when [taskIdentifier] is `null`. */
        public const val TASK_IDENTIFIER_SUFFIX: String = ".outbox.drain"

        /** Used when the bundle has no identifier at all, which only happens in a test host. */
        public const val FALLBACK_BUNDLE_ID: String = "kmptoolkit"
    }
}

/**
 * The identifier actually used: the configured one, or `<bundleId>.outbox.drain`.
 *
 * @param bundleId the app's bundle identifier.
 */
internal fun BackgroundTaskWakeConfig.resolveTaskIdentifier(bundleId: String): String =
    taskIdentifier ?: (bundleId + BackgroundTaskWakeConfig.TASK_IDENTIFIER_SUFFIX)

/**
 * Creates the iOS wake adapter over `BGTaskScheduler` — the opportunistic half of the wake layer.
 *
 * iOS decides when, and whether, to run a processing task: typically while the device is idle and
 * charging, and never at a time you can predict. Treat it as an accelerator. The primary path on
 * iOS remains the launch-time drain, which happens whenever the user opens the app.
 *
 * Wiring it up takes three steps, all outside this library — see
 * `docs/kmptoolkit-outbox/05-platform-notes.md` for the code:
 * 1. list [BackgroundTaskWakeScheduler.taskIdentifier] under `BGTaskSchedulerPermittedIdentifiers`
 *    in `Info.plist`;
 * 2. register a handler for it in Swift **before `didFinishLaunching` returns**;
 * 3. forward that handler to [BackgroundTaskWakeScheduler.handleWake].
 *
 * @param config how the task is submitted.
 * @param logger where submission failures are reported. Defaults to [NoopLogger].
 * @return the scheduler; keep the reference, since [BackgroundTaskWakeScheduler.handleWake] is on
 *   it.
 */
public fun createBackgroundTaskWakeScheduler(
    config: BackgroundTaskWakeConfig = BackgroundTaskWakeConfig(),
    logger: Logger = NoopLogger,
): BackgroundTaskWakeScheduler = BackgroundTaskWakeScheduler(config, logger)

/**
 * The iOS wake adapter. Build it with [createBackgroundTaskWakeScheduler].
 *
 * Beyond the [WakeScheduler] contract it carries [handleWake], the entry point your Swift
 * `BGTaskScheduler` handler forwards to.
 *
 * `scheduleWake` is deduplicated behind an armed flag. Submitting a request is a synchronous system
 * call and the engine arms a wake on every enqueue; while a request is already pending, re-submitting
 * would only replace it with itself, so skipping is behavior-identical and much cheaper during a
 * burst. The flag clears on `cancelWake`, on a failed submit (so a later attempt retries), and when
 * the task actually launches — iOS consumes the request at that point, so the next `scheduleWake`
 * must submit a fresh one.
 */
public class BackgroundTaskWakeScheduler internal constructor(
    private val config: BackgroundTaskWakeConfig,
    private val logger: Logger,
) : WakeScheduler {

    /**
     * The resolved `BGTaskScheduler` identifier — the string that must be in `Info.plist` and in
     * your Swift registration call.
     */
    public val taskIdentifier: String = config.resolveTaskIdentifier(
        NSBundle.mainBundle.bundleIdentifier ?: BackgroundTaskWakeConfig.FALLBACK_BUNDLE_ID,
    )

    /** `1` while a request is believed to be pending. */
    private val armed: AtomicInt = AtomicInt(0)

    @OptIn(ExperimentalForeignApi::class) // the NSError out-pointer of submitTaskRequest
    override fun scheduleWake() {
        // Already armed: a request exists, or one is being submitted right now. Nothing to add.
        if (!armed.compareAndSet(expected = 0, newValue = 1)) return
        val request = BGProcessingTaskRequest(identifier = taskIdentifier).apply {
            requiresNetworkConnectivity = config.requiresNetworkConnectivity
            requiresExternalPower = config.requiresExternalPower
        }
        val submitted: Boolean = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
        if (!submitted) {
            // Expected on the simulator, and whenever background refresh is switched off by the
            // user. Disarm so a later attempt retries; the launch-time drain still delivers.
            armed.value = 0
            logger.w { "BGTaskScheduler refused the outbox wake; delivery falls back to app launch." }
        }
    }

    override fun cancelWake() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(taskIdentifier)
        armed.value = 0
    }

    /**
     * Runs the queue on behalf of an iOS background task, then reports whether it emptied.
     *
     * Call this from the Swift handler registered for [taskIdentifier], and call
     * `task.setTaskCompleted(success:)` with what [onDone] hands you. Also submit the next request
     * from Swift if you want a repeating wake — iOS consumes the request when it launches the task.
     *
     * Like the Android worker, this drains nothing itself: it waits for the registered engine and
     * lets it work, because two drains in one process would break single-flight.
     *
     * @param onDone invoked exactly once, with `true` if the queue emptied within the configured
     *   budget. Called on a background dispatcher, not the main queue.
     */
    public fun handleWake(onDone: (Boolean) -> Unit) {
        // iOS has already spent the request by launching the task.
        armed.value = 0
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val engine: OutboxEngine? = OutboxEngineRegistry.await(config.engineWait)
            onDone(engine?.awaitDrained(config.drainBudget) == true)
        }
    }
}
