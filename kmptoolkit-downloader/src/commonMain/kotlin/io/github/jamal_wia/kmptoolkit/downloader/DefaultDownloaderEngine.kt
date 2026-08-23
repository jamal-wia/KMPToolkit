package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadNotifier
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thrown when no [BackgroundDownloadEvent] arrives within [DefaultDownloaderEngine.STALL_TIMEOUT].
 * Not a [CancellationException] — it must not interfere with structured concurrency.
 */
private class DownloadResourceStallException(
    unit: DownloadUnit,
) : RuntimeException("Download stalled for $unit — no events received")

/**
 * The engine: everything about *getting* a resource that is the same regardless of which resource
 * it is — serializing duplicate requests, scaling progress across a group, deciding when a failure
 * is worth retrying, committing what arrived, and telling the user.
 *
 * It holds no knowledge of the host's catalogue. [groups] is the host's full set of
 * [ResourceGroup]s — the source of the per-group state flows and mutexes, and the sweep list for
 * cancelling everything at logout, the one operation that must reach groups nobody asked about.
 *
 * **Identity.** Groups and units are identified by [ResourceGroup.key] and [DownloadUnit.id],
 * never by object identity — a host may construct a fresh instance per call and everything still
 * refers to the same download.
 *
 * @param bundledResourcesPresent when true, every group reports available and nothing group-level
 *   downloads — for builds that ship those assets inside the package instead of fetching them.
 *   Deliberately not consulted by the per-unit surface: a runtime-catalogue unit is never bundled.
 */
internal class DefaultDownloaderEngine(
    private val storage: DownloaderStorage,
    private val notifier: DownloadNotifier,
    private val backgroundDownloader: BackgroundResourceDownloader,
    private val stateStore: DownloadStateStore,
    private val bundledResourcesPresent: Boolean,
    private val groups: List<ResourceGroup>,
    dispatchers: DownloadDispatchers,
    private val logger: Logger,
) : Downloader {

    // App-scoped: drives a notification-shade Retry's ensureAvailable so the group state reflects it
    // even with no UI caller awaiting. SupervisorJob so one failed retry doesn't cancel the scope.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    // Both maps are keyed by group.key and built eagerly from the declared list: the group set is
    // fixed at construction, so no lazy-creation locking is needed, and keying by the STRING (not
    // the object) means an equal-but-reconstructed group instance still lands on the same state and
    // the same mutex. An undeclared group fails loudly in groupKeyOf below — a wiring mistake, not
    // a case to silently accommodate with a mutex conjured on demand.
    private val groupStates: Map<String, MutableStateFlow<GroupDownloadState>> =
        groups.associate { group: ResourceGroup ->
            group.key to MutableStateFlow<GroupDownloadState>(GroupDownloadState.Idle)
        }
    private val downloadMutexes: Map<String, Mutex> =
        groups.associate { group: ResourceGroup -> group.key to Mutex() }

    override fun isAvailable(group: ResourceGroup): Boolean {
        if (bundledResourcesPresent) return true
        return group.units.all { unit: DownloadUnit ->
            storage.isResourceAvailable(unit)
        }
    }

    override fun isAvailable(unit: DownloadUnit): Boolean =
        storage.isResourceAvailable(unit)

    override fun pathOf(unit: DownloadUnit): String =
        storage.getResourcePath(unit)

    override fun downloadState(group: ResourceGroup): StateFlow<GroupDownloadState> =
        groupStates.getValue(groupKeyOf(group))

    override suspend fun ensureAvailable(group: ResourceGroup) {
        if (isAvailable(group)) {
            logger.i { "ensureAvailable($group) — already available, returning immediately" }
            return
        }
        val groupState: MutableStateFlow<GroupDownloadState> =
            groupStates.getValue(groupKeyOf(group))
        logger.i { "ensureAvailable($group) — NOT available, waiting for mutex..." }
        downloadMutexes.getValue(groupKeyOf(group)).withLock {
            logger.i { "ensureAvailable($group) — mutex acquired" }
            if (isAvailable(group)) {
                logger.i { "ensureAvailable($group) — became available while waiting for mutex" }
                return
            }

            val units: List<DownloadUnit> = group.units

            // Count only units that require an actual network download.
            // Units that are already available or have an uncommitted temp file
            // are excluded so progress is always scaled over the real download work.
            // This prevents cases like a two-unit group where one unit is already on disk
            // causing the other to only reach 50 % before reporting Completed.
            val downloadCount: Int = units.count { unit ->
                !storage.isResourceAvailable(unit) &&
                    !storage.isTempFileAvailable(unit)
            }

            var downloadIndex = 0
            for (unit: DownloadUnit in units) {
                if (storage.isResourceAvailable(unit)) continue

                // Recovery: download completed in background but not yet committed
                if (storage.isTempFileAvailable(unit)) {
                    logger.i {
                        "RECOVERY: Temp file found for $unit " +
                            "(size=${storage.getTempFileSize(unit)} bytes), committing..."
                    }
                    commitUnitAndNotify(group = group, groupState = groupState, unit = unit)
                    continue
                }
                logger.i { "Unit $unit needs download (no temp file, not available)" }

                val progressBase: Float =
                    if (downloadCount > 0) downloadIndex.toFloat() / downloadCount else 0f
                val progressScale: Float =
                    if (downloadCount > 0) 1f / downloadCount else 1f
                downloadIndex++

                downloadUnitWithRetry(
                    group = group,
                    groupState = groupState,
                    unit = unit,
                    progressBase = progressBase,
                    progressScale = progressScale,
                )
            }
        }
    }

    /**
     * Cancels every unit of [group].
     * Safe to call from any thread — does not depend on internal mutable state.
     */
    override fun cancelDownload(group: ResourceGroup) {
        group.units.forEach { unit: DownloadUnit ->
            backgroundDownloader.cancelDownload(unit)
            clearStallCount(unit)
            safeDeleteTempFile(unit)
        }
        notifier.remove(group)
        groupStates.getValue(groupKeyOf(group)).value = GroupDownloadState.Idle
        logger.i { "Download cancelled for $group" }
    }

    override suspend fun cancelAllDownloads() {
        groups.forEach { group: ResourceGroup ->
            cancelDownload(group)
        }
        // Per-unit downloads aren't reachable through the group list — a unit named by a server
        // catalogue has an id space only known at runtime (see DownloadUnit's own doc). Cancel every
        // unit this engine has tracked state for, mirroring the group loop above; a download left
        // running past logout would keep transferring under just-revoked credentials. Calls the
        // suspend helper directly (awaited here, not fire-and-forget via the public cancelDownload(unit)
        // overload) — its state write must be visible before cancelChildren() below runs, see that
        // helper's own doc for why going through the non-suspend overload lost the race in production.
        unitStatesLock.withLock { unitStates.values.map { it.unit } }.forEach { unit: DownloadUnit ->
            cancelUnitDownloadAwaitingState(unit)
        }
        // Cancel any in-flight retry coroutine on our own scope (a notification-shade Retry) so it can't
        // re-enqueue a download after logout; the scope itself stays usable for later downloads.
        scope.coroutineContext.cancelChildren()
        logger.i { "All downloads cancelled" }
    }

    /**
     * Re-enqueues the units of [group] that are not yet available. Already-committed ones are
     * skipped, so a group where only one unit failed does not re-fetch what already succeeded.
     */
    override fun retryDownload(group: ResourceGroup) {
        // Already being managed by an active ensureAvailable() (its flow surfaces the result) — don't
        // launch a redundant one.
        if (downloadMutexes.getValue(groupKeyOf(group)).isLocked) {
            logger.i { "Retry skipped for $group — a download is already being managed" }
            return
        }
        notifier.remove(group)
        // Drive the retry through ensureAvailable on the engine's OWN scope so the group state
        // reflects the full lifecycle (progress -> completion) even when the trigger is the
        // notification-shade Retry (no UI caller). A fire-and-forget enqueue left the state stuck at
        // 0% there, because nothing fed the transfer's progress into it. ensureAvailable resumes from
        // the partial temp file just like the in-app Retry, and its mutex serialises with any UI
        // download.
        scope.launch {
            try {
                ensureAvailable(group)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "Retry failed for $group" }
            }
        }
    }

    /**
     * Validates that [group] was declared to the engine and returns its key. Failing loudly here —
     * rather than conjuring state on demand — surfaces a wiring mistake (a group missing from the
     * host's declared list) on first use instead of letting it half-work.
     */
    private fun groupKeyOf(group: ResourceGroup): String {
        require(group.key in groupStates) {
            "ResourceGroup '${group.key}' was not declared to the engine — pass it in the groups list"
        }
        return group.key
    }

    /**
     * Downloads a single [unit] with automatic retry on error:
     * - Always retries if the previous attempt made progress
     *   (received at least one [BackgroundDownloadEvent.Progress]).
     * - Retries up to [MAX_STALL_RETRIES] additional times without progress as a fallback.
     * - Stall counter persists via [DownloadStateStore] to survive process death.
     */
    private suspend fun downloadUnitWithRetry(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        unit: DownloadUnit,
        progressBase: Float,
        progressScale: Float,
    ) {
        // Start or attach to an existing background download
        val inProgress: Boolean = backgroundDownloader.isDownloadInProgress(unit)
        logger.i {
            "downloadUnitWithRetry($unit) — inProgress=$inProgress, " +
                "progressBase=$progressBase, progressScale=$progressScale"
        }
        if (!inProgress) {
            groupState.value = GroupDownloadState.Downloading(progress = progressBase)
            notifier.showProgress(group = group, progress = progressBase)
            backgroundDownloader.enqueueDownload(unit)
        }

        while (true) {
            val result: DownloadAttemptResult = awaitBackgroundDownloadCompletion(
                group = group,
                groupState = groupState,
                unit = unit,
                progressBase = progressBase,
                progressScale = progressScale,
            )

            val eventName: String? = result.terminalEvent::class.simpleName
            logger.i {
                "awaitCompletion result for $unit: event=$eventName, " +
                    "madeProgress=${result.madeProgress}"
            }
            when (result.terminalEvent) {
                is BackgroundDownloadEvent.FileReady -> {
                    logger.i { "FileReady for $unit — committing..." }
                    commitUnitAndNotify(group = group, groupState = groupState, unit = unit)
                    clearStallCount(unit)
                    return
                }

                is BackgroundDownloadEvent.Cancelled -> {
                    logger.i { "Cancelled for $unit" }
                    clearStallCount(unit)
                    groupState.value = GroupDownloadState.Idle
                    notifier.remove(group)
                    safeDeleteTempFile(unit)
                    throw DownloadCancelledException(group)
                }

                is BackgroundDownloadEvent.Error -> {
                    val errorMessage: String = result.terminalEvent.message
                    logger.e { "Error for $unit: $errorMessage" }
                    if (shouldRetry(unit = unit, madeProgress = result.madeProgress)) {
                        logger.i { "Retrying download for $unit (error: $errorMessage)" }
                        backgroundDownloader.enqueueDownload(unit)
                        continue
                    }
                    clearStallCount(unit)
                    logger.e { "Download failed for $unit: $errorMessage" }
                    val downloadError: DownloadError = errorMessage.toDownloadError()
                    groupState.value = GroupDownloadState.Error(error = downloadError)
                    notifier.showError(group = group, error = downloadError)
                    safeDeleteTempFile(unit)
                    throw DownloadFailedException(unit = unit, error = downloadError)
                }
            }
        }
    }

    private data class DownloadAttemptResult(
        val terminalEvent: BackgroundDownloadEvent.Terminal,
        val madeProgress: Boolean,
    )

    /**
     * Suspends until the background download for [unit] emits a terminal event.
     * Maps unit-level progress to the overall group progress using [progressBase] and [progressScale].
     *
     * Uses **inactivity timeout** (stall detection) instead of a fixed wall-clock timeout:
     * - [transformLatest] restarts the inner block on each new event, cancelling the previous [delay].
     * - If no event arrives within [STALL_TIMEOUT], the delay completes and throws
     *   [DownloadResourceStallException].
     * - A slow-but-progressing download (receiving Progress events) will never stall-timeout.
     * - Primary network/transport timeouts are handled by the [BackgroundResourceDownloader].
     *
     * Returns a [DownloadAttemptResult] containing the terminal event and whether any progress
     * was observed during this attempt — used by [downloadUnitWithRetry] to decide on retry.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitBackgroundDownloadCompletion(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        unit: DownloadUnit,
        progressBase: Float,
        progressScale: Float,
    ): DownloadAttemptResult {
        var madeProgress = false

        val terminalEvent: BackgroundDownloadEvent.Terminal = try {
            backgroundDownloader.observeProgress(unit)
                .onEach { event: BackgroundDownloadEvent ->
                    if (event is BackgroundDownloadEvent.Progress) {
                        madeProgress = true
                        val scaledProgress: Float = progressBase + event.fraction * progressScale
                        // Guard: never report progress lower than the current value.
                        // On retry, the new attempt starts at fraction=0 before real
                        // progress is reported — without this check the UI would
                        // jump back to progressBase (e.g. 50% for a second unit).
                        val currentProgress: Float =
                            (groupState.value as? GroupDownloadState.Downloading)
                                ?.progress ?: 0f
                        if (scaledProgress > currentProgress) {
                            groupState.value =
                                GroupDownloadState.Downloading(progress = scaledProgress)
                            notifier.showProgress(group = group, progress = scaledProgress)
                        }
                    }
                }
                .transformLatest { event: BackgroundDownloadEvent ->
                    emit(event)
                    // Resets on each upstream emission; fires only if no new event arrives.
                    delay(STALL_TIMEOUT)
                    throw DownloadResourceStallException(unit)
                }
                .filterIsInstance<BackgroundDownloadEvent.Terminal>()
                .first()
        } catch (e: DownloadResourceStallException) {
            logger.e(e) { e.message.orEmpty() }
            backgroundDownloader.cancelDownload(unit)
            // Return as Error so the retry loop in downloadUnitWithRetry can handle it.
            // This is a transport timeout — surface as such for the user-facing UI.
            BackgroundDownloadEvent.Error(
                unit = unit,
                message = e.message ?: "Download stalled (timeout)",
            )
        } catch (e: CancellationException) {
            // The background download continues even if the coroutine is cancelled
            // (e.g., user navigated away). Do NOT cancel the notification —
            // the transfer is still running under the platform's own machinery.
            logger.i { "Detached from background download for $unit (coroutine cancelled: ${e.message})" }
            throw e
        }

        return DownloadAttemptResult(
            terminalEvent = terminalEvent,
            madeProgress = madeProgress,
        )
    }

    /**
     * Returns `true` if the download should be retried.
     * - Always retries when [madeProgress] is `true` (stall counter is reset).
     * - Without progress, retries up to [MAX_STALL_RETRIES] times as a fallback.
     */
    private fun shouldRetry(unit: DownloadUnit, madeProgress: Boolean): Boolean {
        if (madeProgress) {
            clearStallCount(unit)
            return true
        }
        val stallCount: Int = incrementStallCount(unit)
        logger.i { "No progress for $unit (stall $stallCount/$MAX_STALL_RETRIES)" }
        return stallCount <= MAX_STALL_RETRIES
    }

    private suspend fun commitUnitAndNotify(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        unit: DownloadUnit,
    ) {
        logger.i {
            "commitUnitAndNotify($unit) — " +
                "isAvailable=${storage.isResourceAvailable(unit)}, " +
                "tempAvailable=${storage.isTempFileAvailable(unit)}"
        }
        try {
            // Guard: the platform downloader may have already committed the resource (self-commit,
            // so a transfer that finishes while the UI process is dead is not lost).
            // Skip commitResource() to avoid failing on an already-consumed temp file.
            if (storage.isResourceAvailable(unit)) {
                logger.i { "Resource unit $unit already committed, skipping commit" }
            } else {
                logger.i { "Committing resource $unit..." }
                storage.commitResource(unit = unit)
                // Verify the resource is actually available after commit — prevents
                // silent infinite re-download loops when extraction succeeds but
                // produces files at unexpected paths.
                if (!storage.isResourceAvailable(unit)) {
                    error(
                        "Resource $unit not available after commit — " +
                            "extraction may have produced unexpected file structure"
                    )
                }
            }
            // Only report Completed when ALL units of the group are done.
            // Emit progress=1f first so the UI always shows 100 % before transitioning.
            logger.i { "After commit, isAvailable($group)=${isAvailable(group)}" }
            if (isAvailable(group)) {
                // NonCancellable from here to the terminal publish: the file is already on disk, so
                // this is bookkeeping, and a caller cancelling mid-way (navigating off the screen
                // during the settle delay) must not strand the group at Downloading(1f) with an
                // ongoing notification still posted — the next ensureAvailable short-circuits on
                // isAvailable and would never come back to finish the hand-off.
                withContext(NonCancellable) {
                    groupState.value = GroupDownloadState.Downloading(progress = 1f)
                    delay(timeMillis = COMPLETION_SETTLE_DELAY_MILLIS)
                    groupState.value = GroupDownloadState.Completed
                    // Cancel any lingering ongoing progress notification before posting the terminal
                    // "completed": on rate-limiting notification implementations, a single post that
                    // flips a rapidly-updated ongoing notification to non-ongoing is not reliably
                    // honored, so an explicit remove + fresh post guarantees the shade ends on
                    // "completed", not a stuck bar.
                    notifier.remove(group = group)
                    notifier.showCompleted(group = group)
                }
            }
            logger.i { "Resource unit $unit committed successfully" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorMessage: String = e.message ?: "Unknown error"
            logger.e(e) { "Failed to commit resource $unit: $errorMessage" }
            val downloadError: DownloadError = DownloadError.Storage(errorMessage)
            groupState.value = GroupDownloadState.Error(error = downloadError)
            notifier.showError(group = group, error = downloadError)
            safeDeleteTempFile(unit)
            throw DownloadFailedException(unit = unit, error = downloadError, cause = e)
        }
    }

    private fun safeDeleteTempFile(unit: DownloadUnit) {
        try {
            storage.deleteTempFile(unit)
        } catch (e: Exception) {
            logger.e(e) { "Failed to delete temp file for $unit: ${e.message}" }
        }
    }

    // -- Per-unit API --------------------------------------------------------------

    /** A tracked unit: the state flow plus the unit itself, so a sweep can address the download. */
    private class UnitEntry(
        val unit: DownloadUnit,
        val state: MutableStateFlow<UnitDownloadState>,
    )

    // Both maps grow with the runtime-constructed unit set, unlike the group maps above — such a
    // unit's id isn't known until a manifest is fetched, so there is no fixed set to build these
    // from upfront. Keyed by unit.id, never by the object: two equal-id instances must land on the
    // same state and the same mutex regardless of how the host implemented equals. Guarded by their
    // own lock rather than made ConcurrentHashMap: that type is JVM-only and this class is
    // commonMain (also runs on Kotlin/Native).
    private val unitStatesLock: Mutex = Mutex()
    private val unitStates: MutableMap<String, UnitEntry> = mutableMapOf()
    private val unitMutexesLock: Mutex = Mutex()
    private val unitMutexes: MutableMap<String, Mutex> = mutableMapOf()

    private suspend fun unitStateFlow(unit: DownloadUnit): MutableStateFlow<UnitDownloadState> =
        unitStatesLock.withLock {
            unitStates.getOrPut(unit.id) {
                // Seeded from disk, so the very first value a collector sees already answers
                // "is this here from an earlier session?" without a separate storage read.
                UnitEntry(
                    unit = unit,
                    state = MutableStateFlow(
                        if (storage.isResourceAvailable(unit)) {
                            UnitDownloadState.Available
                        } else {
                            UnitDownloadState.Idle
                        },
                    ),
                )
            }.state
        }

    private suspend fun unitMutex(unit: DownloadUnit): Mutex =
        unitMutexesLock.withLock { unitMutexes.getOrPut(unit.id) { Mutex() } }

    // A cold flow, not a direct exposure of the cached MutableStateFlow: the cache entry is created
    // lazily (get-or-put), which needs unitStatesLock — a suspend operation this function's own
    // signature can't perform directly. Wrapping the lookup in flow{} defers it to collection time,
    // which IS a suspend context.
    override fun unitDownloadStateFlow(unit: DownloadUnit): Flow<UnitDownloadState> = flow {
        emitAll(unitStateFlow(unit))
    }

    override suspend fun ensureAvailable(unit: DownloadUnit) {
        if (storage.isResourceAvailable(unit)) {
            markUnitOnDisk(unit)
            return
        }
        unitMutex(unit).withLock {
            if (storage.isResourceAvailable(unit)) {
                markUnitOnDisk(unit)
                return
            }
            if (storage.isTempFileAvailable(unit)) {
                commitUnit(unit)
                return
            }
            downloadUnitOnly(unit)
        }
    }

    /**
     * Records that [unit] is on disk without a transfer having run. [UnitDownloadState.Completed]
     * is deliberately preserved: it means "downloaded this process", and a redundant
     * ensureAvailable after that fact must not erase the distinction observers key off.
     */
    private suspend fun markUnitOnDisk(unit: DownloadUnit) {
        val stateFlow: MutableStateFlow<UnitDownloadState> = unitStateFlow(unit)
        if (stateFlow.value !is UnitDownloadState.Completed) {
            stateFlow.value = UnitDownloadState.Available
        }
    }

    override fun cancelDownload(unit: DownloadUnit) {
        scope.launch { cancelUnitDownloadAwaitingState(unit) }
    }

    // The state write needs unitStatesLock (see unitStateFlow), so it can't run inline in the
    // non-suspend cancelDownload(unit) above — that overload dispatches it onto scope via launch
    // instead. cancelAllDownloads calls this directly (awaited, not fire-and-forget) so the write is
    // guaranteed visible before it cancels scope's children — going through the non-suspend overload
    // raced the state write against that very cancellation and reliably lost in production.
    private suspend fun cancelUnitDownloadAwaitingState(unit: DownloadUnit) {
        backgroundDownloader.cancelDownload(unit)
        clearStallCount(unit)
        safeDeleteTempFile(unit)
        unitStateFlow(unit).value = UnitDownloadState.Idle
    }

    override suspend fun hasActiveUnitDownload(): Boolean {
        val states: List<MutableStateFlow<UnitDownloadState>> =
            unitStatesLock.withLock { unitStates.values.map { it.state } }
        return states.any { stateFlow: MutableStateFlow<UnitDownloadState> ->
            stateFlow.value is UnitDownloadState.Downloading
        }
    }

    /**
     * Downloads [unit] alone — no group blending, and no stall-retry loop (unlike
     * [downloadUnitWithRetry]): the per-unit path serves small files a user can simply tap
     * "Download" again for, so one stall timeout surfacing as an error is enough robustness at
     * that size, unlike the larger bundles the retry loop exists for. A platform progress
     * notification may still appear if the [BackgroundResourceDownloader] posts one on its own, but
     * this function does not talk to the [notifier] itself.
     *
     * Every outcome is BOTH published to the unit's state flow and, for cancel/failure, thrown —
     * the same contract as the group path, so the two `ensureAvailable` overloads never differ in
     * whether the caller hears about a failure.
     */
    private suspend fun downloadUnitOnly(unit: DownloadUnit) {
        val stateFlow: MutableStateFlow<UnitDownloadState> = unitStateFlow(unit)
        if (!backgroundDownloader.isDownloadInProgress(unit)) {
            stateFlow.value = UnitDownloadState.Downloading(progress = 0f)
            backgroundDownloader.enqueueDownload(unit)
        }

        val terminalEvent: BackgroundDownloadEvent.Terminal = try {
            backgroundDownloader.observeProgress(unit)
                .onEach { event: BackgroundDownloadEvent ->
                    if (event is BackgroundDownloadEvent.Progress) {
                        stateFlow.value = UnitDownloadState.Downloading(progress = event.fraction)
                    }
                }
                .transformLatest { event: BackgroundDownloadEvent ->
                    emit(event)
                    delay(STALL_TIMEOUT)
                    throw DownloadResourceStallException(unit)
                }
                .filterIsInstance<BackgroundDownloadEvent.Terminal>()
                .first()
        } catch (e: DownloadResourceStallException) {
            logger.e(e) { e.message.orEmpty() }
            backgroundDownloader.cancelDownload(unit)
            BackgroundDownloadEvent.Error(
                unit = unit,
                message = e.message ?: "Download stalled (timeout)",
            )
        }

        settleUnitDownload(unit = unit, stateFlow = stateFlow, terminalEvent = terminalEvent)
    }

    /**
     * Turns [terminalEvent] into the unit's final state and, for cancel/failure, the matching
     * exception — every outcome is published to the flow BEFORE it is thrown, so an observer and a
     * caller in a try/catch never disagree about what happened.
     */
    private suspend fun settleUnitDownload(
        unit: DownloadUnit,
        stateFlow: MutableStateFlow<UnitDownloadState>,
        terminalEvent: BackgroundDownloadEvent.Terminal,
    ) {
        when (terminalEvent) {
            is BackgroundDownloadEvent.FileReady -> commitUnit(unit)
            is BackgroundDownloadEvent.Cancelled -> {
                clearStallCount(unit)
                stateFlow.value = UnitDownloadState.Idle
                safeDeleteTempFile(unit)
                throw DownloadCancelledException(group = unit.group, unit = unit)
            }

            is BackgroundDownloadEvent.Error -> {
                clearStallCount(unit)
                val downloadError: DownloadError = terminalEvent.message.toDownloadError()
                stateFlow.value = UnitDownloadState.Error(downloadError)
                safeDeleteTempFile(unit)
                throw DownloadFailedException(unit = unit, error = downloadError)
            }
        }
    }

    private suspend fun commitUnit(unit: DownloadUnit) {
        val stateFlow: MutableStateFlow<UnitDownloadState> = unitStateFlow(unit)
        try {
            if (!storage.isResourceAvailable(unit)) {
                storage.commitResource(unit)
            }
            if (!storage.isResourceAvailable(unit)) {
                error("Resource $unit not available after commit")
            }
            // Same reasoning as the group path: once the file is on disk the terminal publish is
            // bookkeeping and must not be lost to a caller cancelling mid-way — the next
            // ensureAvailable would short-circuit on isAvailable and never set Completed.
            withContext(NonCancellable) {
                clearStallCount(unit)
                stateFlow.value = UnitDownloadState.Completed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Failed to commit resource $unit: ${e.message}" }
            val downloadError: DownloadError = DownloadError.Storage(e.message)
            stateFlow.value = UnitDownloadState.Error(downloadError)
            safeDeleteTempFile(unit)
            throw DownloadFailedException(unit = unit, error = downloadError, cause = e)
        }
    }

    // -- Stall counter (persisted through the DownloadStateStore port) --------------

    private fun getStallCount(unit: DownloadUnit): Int =
        stateStore.readInt(stallCountKey(unit), 0)

    private fun incrementStallCount(unit: DownloadUnit): Int {
        val newCount: Int = getStallCount(unit) + 1
        stateStore.writeInt(stallCountKey(unit), newCount)
        return newCount
    }

    private fun clearStallCount(unit: DownloadUnit) {
        stateStore.remove(stallCountKey(unit))
    }

    private fun stallCountKey(unit: DownloadUnit): String = "download_stall_count_${unit.id}"

    private companion object {
        /**
         * If no [BackgroundDownloadEvent] arrives within this duration,
         * the download is considered stalled. Resets on every event.
         *
         * 5 minutes is generous — even at 1 KB/s with 8 KB chunks,
         * a Progress event arrives every ~8 seconds.
         */
        private val STALL_TIMEOUT: Duration = 5.minutes

        /** Max retries without any progress before giving up. */
        private const val MAX_STALL_RETRIES = 2

        /** Lets the UI render a full progress bar before it is replaced by the completed state. */
        private const val COMPLETION_SETTLE_DELAY_MILLIS = 300L
    }
}

/**
 * Classifies a platform-emitted [BackgroundDownloadEvent.Error.message] into a [DownloadError].
 *
 * The platform contract yields only a string — what a failing transfer reports differs per platform
 * and per layer — so this has to keyword-match. Everything downstream is typed, which is the point:
 * the string handling is confined to this one function.
 */
/** `http 503`, `http error: 500`, `status 502` — a 5xx wherever a downloader put it in its message. */
private val SERVER_STATUS: Regex = Regex("(?:http|status)\\D{0,10}(5\\d\\d)\\b")

private fun String.toDownloadError(): DownloadError {
    val lowered: String = lowercase()
    return when {
        "404" in lowered || "not found" in lowered -> DownloadError.NotFound

        "401" in lowered ||
            "403" in lowered ||
            "unauthorized" in lowered ||
            "forbidden" in lowered -> DownloadError.Unauthorized

        // Both a typical Android and a typical iOS downloader phrase a status failure as
        // "HTTP <code> ..." / "HTTP error: <code>", never as a bare leading digit — so match the
        // code where it actually appears, and hand it through so a host can show it.
        SERVER_STATUS.containsMatchIn(lowered) ->
            DownloadError.Server(statusCode = SERVER_STATUS.find(lowered)?.groupValues?.get(1)?.toIntOrNull())

        "timeout" in lowered ||
            "timed out" in lowered ||
            "stalled" in lowered -> DownloadError.Timeout

        "unable to resolve" in lowered ||
            "no address" in lowered ||
            "network" in lowered ||
            "no internet" in lowered -> DownloadError.NoConnection

        else -> DownloadError.Unknown(this)
    }
}
