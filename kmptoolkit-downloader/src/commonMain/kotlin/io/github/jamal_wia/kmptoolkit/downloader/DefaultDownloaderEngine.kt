package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadNotifier
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thrown when no [BackgroundDownloadEvent] arrives within the stall timeout. Not a
 * [CancellationException] — it must not interfere with structured concurrency.
 *
 * Deliberately carries nothing about the unit: a unit's `toString()` is host-defined text and the
 * engine never reads an error back out of a message it built itself — a stall is reported as
 * [DownloadError.Timeout] directly, see [toDownloadError].
 */
private class DownloadResourceStallException : RuntimeException("Download stalled — no events received")

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
 * @param config the engine's tunables; today the stall timeout ([DownloaderConfig.stallTimeout]).
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
    private val config: DownloaderConfig = DownloaderConfig(),
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

    // One cancel epoch per group, bumped first thing by cancelDownload(group). A running
    // ensureAvailable captures it when it is called and compares it at every point where it could
    // otherwise carry on for a cancel it never heard about (see CancelToken). Immutable map, built
    // upfront like the two above, so reading it needs no lock.
    private val groupCancelEpochs: Map<String, MutableStateFlow<Int>> =
        groups.associate { group: ResourceGroup -> group.key to MutableStateFlow(0) }

    /**
     * A cancel request seen from one running call: the epoch of a surface (a group, or a unit) when
     * the call started, against the epoch now. The one mechanism by which a `cancelDownload` reaches
     * a call that has no transfer running to deliver a `Cancelled` event (waiting out a retry
     * backoff, committing, between units, starting a transfer).
     */
    private class CancelToken(private val epoch: StateFlow<Int>) {
        private val captured: Int = epoch.value

        /** True once a cancel was requested after this token was taken. */
        val isCancelled: Boolean get() = epoch.value != captured

        /** Suspends until a cancel is requested. */
        suspend fun awaitCancel() {
            epoch.first { it != captured }
        }
    }

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
        // Taken before the mutex: a cancel that arrives while this call is still queued behind
        // another one cancels it too.
        val token = CancelToken(groupCancelEpochs.getValue(groupKeyOf(group)))
        logger.i { "ensureAvailable($group) — NOT available, waiting for mutex..." }
        downloadMutexes.getValue(groupKeyOf(group)).withLock {
            logger.i { "ensureAvailable($group) — mutex acquired" }
            if (isAvailable(group)) {
                logger.i { "ensureAvailable($group) — became available while waiting for mutex" }
                return
            }
            try {
                downloadMissingUnits(group, groupState, token)
            } catch (e: CancellationException) {
                // The caller's scope was cancelled. When that is also a cancelDownload / logout,
                // cancelDownload could not clean up behind a running call (it never deletes under
                // one), so the call does it on its way out.
                if (token.isCancelled) {
                    withContext(NonCancellable) { cleanUpCancelledGroup(group, groupState) }
                }
                throw e
            }
        }
    }

    private suspend fun downloadMissingUnits(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        token: CancelToken,
    ) {
        throwIfGroupCancelled(group, groupState, token)
        val units: List<DownloadUnit> = group.units

        // Count only units that require an actual network download.
        // Units that are already available or have a complete temp file awaiting commit
        // are excluded so progress is always scaled over the real download work.
        // This prevents cases like a two-unit group where one unit is already on disk
        // causing the other to only reach 50 % before reporting Completed.
        val downloadCount: Int = units.count { unit ->
            !storage.isResourceAvailable(unit) &&
                storage.tempFileState(unit) != TempFileState.Complete
        }

        var downloadIndex = 0
        for (unit: DownloadUnit in units) {
            if (storage.isResourceAvailable(unit)) continue
            // After the on-disk skip: a cancel that arrives once the last unit is committed has
            // nothing left to stop (see commitUnitAndNotify).
            throwIfGroupCancelled(group, groupState, token)

            // A unit recovered from a complete temp file is not in downloadCount. If its bytes
            // then fail their check it downloads unplanned, over the whole bar rather than
            // past its end — the never-backwards guard keeps the bar from jumping.
            val planned: Boolean = storage.tempFileState(unit) != TempFileState.Complete
            val progressBase: Float =
                if (planned && downloadCount > 0) downloadIndex.toFloat() / downloadCount
                else 0f
            val progressScale: Float =
                if (planned && downloadCount > 0) 1f / downloadCount else 1f
            if (planned) downloadIndex++

            // The group loop and the per-unit surface take different mutexes, so without this
            // they could transfer and commit one unit id at the same time. Order is always
            // group, then unit; the unit surface takes only the unit mutex.
            unitMutex(unit).withLock {
                // The unit surface may have committed this unit while the loop waited for its
                // mutex: transferring it again would download a finished resource a second time
                // and leave an orphan temp file. Publish the group's completion if that was the
                // last missing unit.
                if (storage.isResourceAvailable(unit)) {
                    logger.i { "Unit $unit was committed by the unit surface meanwhile" }
                    publishGroupCompletedIfAvailable(group, groupState)
                    return@withLock
                }
                fetchAndCommitGroupUnit(
                    group = group,
                    groupState = groupState,
                    unit = unit,
                    progressBase = progressBase,
                    progressScale = progressScale,
                    token = token,
                )
            }
        }
        // The unit surface may have committed the last missing unit between the previous unit's
        // commit (which then saw the group incomplete and published nothing) and the loop's
        // on-disk check of that unit. Nothing else would publish Completed, and the next call
        // short-circuits on isAvailable, so the group would stay at Downloading. This also covers a
        // cancel that landed in that window (the state is then Idle although everything is on disk).
        if (groupState.value !is GroupDownloadState.Completed) {
            publishGroupCompletedIfAvailable(group, groupState)
        }
    }

    /**
     * Ends a call whose group was cancelled while it ran: the temp files and failure counts of the
     * whole group go (the call owns them while it runs, so this is after any commit in flight and
     * serialises with it), the notification is removed and the state is Idle. A commit that already
     * succeeded leaves its resource in place.
     */
    private fun cleanUpCancelledGroup(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        heldUnit: DownloadUnit? = null,
    ) {
        group.units.forEach { unit: DownloadUnit ->
            clearStallCount(unit)
            deleteTempFileUnlessUnitBusy(unit, heldUnit)
        }
        notifyRemove(group)
        groupState.value = GroupDownloadState.Idle
    }

    /**
     * Deletes the temp file of [unit] unless a per-unit call is working on it (it may be committing
     * it; that call is cancelled too and removes the file itself). [heldUnit] is the unit whose
     * mutex the caller already holds: the mutex is not reentrant, so it cannot be probed.
     */
    private fun deleteTempFileUnlessUnitBusy(unit: DownloadUnit, heldUnit: DownloadUnit?) {
        if (unit.id == heldUnit?.id) {
            safeDeleteTempFile(unit)
            return
        }
        val mutex: Mutex = unitMutex(unit)
        if (mutex.tryLock()) {
            try {
                safeDeleteTempFile(unit)
            } finally {
                mutex.unlock()
            }
        }
    }

    /**
     * The checkpoint of a running group call: cleans up and throws when the group was cancelled.
     * [heldUnit] is the unit whose mutex the caller holds, if any.
     */
    private fun throwIfGroupCancelled(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        token: CancelToken,
        heldUnit: DownloadUnit? = null,
    ) {
        if (!token.isCancelled) return
        logger.i { "Download of $group cancelled while running" }
        cleanUpCancelledGroup(group, groupState, heldUnit)
        throw DownloadCancelledException(group)
    }

    /**
     * Cancels every unit of [group].
     * Safe to call from any thread — does not depend on internal mutable state.
     */
    override fun cancelDownload(group: ResourceGroup) {
        val key: String = groupKeyOf(group)
        // First, so a running call sees it at its next checkpoint however the rest of this goes.
        groupCancelEpochs.getValue(key).update { it + 1 }
        // A per-unit call on one of the group's units is cancelled with it (and removes its own
        // temp file), so a group cancel never deletes a file a unit commit is reading.
        group.units.forEach { unit: DownloadUnit ->
            unitEntries.value[unit.id]?.cancelEpoch?.update { it + 1 }
        }
        // tryLock, not lock: this function cannot suspend. With no running call the cleanup is done
        // here, under the mutex so a new request waits for it. A running call owns the temp files
        // (a commit in flight reads them) and deletes them itself at its next checkpoint.
        val mutex: Mutex = downloadMutexes.getValue(key)
        val ownsGroup: Boolean = mutex.tryLock()
        try {
            group.units.forEach { unit: DownloadUnit ->
                cancelTransfer(unit)
                clearStallCount(unit)
                if (ownsGroup) deleteTempFileUnlessUnitBusy(unit, heldUnit = null)
            }
            notifyRemove(group)
            groupStates.getValue(key).value = GroupDownloadState.Idle
        } finally {
            if (ownsGroup) mutex.unlock()
        }
        logger.i { "Download cancelled for $group" }
    }

    override suspend fun cancelAllDownloads() {
        groups.forEach { group: ResourceGroup ->
            cancelDownload(group)
        }
        // Per-unit downloads aren't reachable through the group list — a unit named by a server
        // catalogue has an id space only known at runtime (see DownloadUnit's own doc). Cancel every
        // unit this engine has tracked state for, mirroring the group loop above; a download left
        // running past logout would keep transferring under just-revoked credentials. The unit
        // cancel is synchronous, so its effects are in place before cancelChildren() below runs.
        unitEntries.value.values.forEach { entry: UnitEntry -> cancelUnitNow(entry) }
        // Every epoch is bumped: a start not in this snapshot registers after the bumps and sees
        // them at its first check, so it never enqueues; one that is in it is waited for below.
        val inFlight: Set<TransferStart> = transferStarts.value
        // Cancel any in-flight retry coroutine on our own scope (a notification-shade Retry) so it can't
        // re-enqueue a download after logout; the scope itself stays usable for later downloads.
        scope.coroutineContext.cancelChildren()
        // A start in flight leaves its window only after its post-enqueue check has stopped its
        // transfer and taken its state back. Only the snapshot is awaited, so requests made after
        // this cancel can never hold it up.
        if (inFlight.isNotEmpty()) {
            transferStarts.first { current: Set<TransferStart> -> inFlight.none { it in current } }
        }
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
        notifyRemove(group)
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
     * Brings one unit of [group] onto disk. A [TempFileState.Complete] temp file is committed
     * straight away — a transfer that finished while nobody observed it. Anything else transfers
     * first: [downloadUnitWithRetry] attaches to a transfer still running, or enqueues one that
     * resumes from a [TempFileState.Partial] file. A partial file is never committed, however
     * large: a transfer killed mid-way leaves one that exists and is not empty, and committing it
     * handed the consumer a truncated resource with no error.
     *
     * Bytes that fail their integrity check ([ResourceIntegrityException]) are downloaded
     * [MAX_INTEGRITY_RETRIES] more times before the failure is reported.
     */
    private suspend fun fetchAndCommitGroupUnit(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        unit: DownloadUnit,
        progressBase: Float,
        progressScale: Float,
        token: CancelToken,
    ) {
        try {
            transferAndCommitGroupUnit(group, groupState, unit, progressBase, progressScale, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadFailedException) {
            throw e
        } catch (e: DownloadCancelledException) {
            throw e
        } catch (e: Exception) {
            failOnPortFailure(group, groupState, unit, e)
        }
    }

    private suspend fun transferAndCommitGroupUnit(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        unit: DownloadUnit,
        progressBase: Float,
        progressScale: Float,
        token: CancelToken,
    ) {
        failOnCatalogueError(group, groupState, unit)
        var integrityFailures = 0
        while (true) {
            throwIfGroupCancelled(group, groupState, token, unit)
            if (storage.tempFileState(unit) == TempFileState.Complete) {
                logger.i { "Complete temp file found for $unit, committing..." }
            } else {
                logger.i {
                    "Unit $unit needs a transfer (tempFileState=${storage.tempFileState(unit)})"
                }
                downloadUnitWithRetry(
                    group = group,
                    groupState = groupState,
                    unit = unit,
                    progressBase = progressBase,
                    progressScale = progressScale,
                    token = token,
                )
            }
            val committed: Boolean = commitUnitAndNotify(
                group = group,
                groupState = groupState,
                unit = unit,
                lastAttempt = integrityFailures >= MAX_INTEGRITY_RETRIES,
                token = token,
            )
            if (committed) return
            integrityFailures++
            logger.e {
                "Integrity check failed for $unit — downloading again " +
                    "($integrityFailures/$MAX_INTEGRITY_RETRIES)"
            }
        }
    }

    /**
     * Checks the unit's catalogue entry before anything is transferred: reads
     * [DownloadUnit.sha256], then asks a shipped storage whether the relativePath is usable. A
     * getter that throws — a host that built the value with [Sha256.parse] in a `get() =` from a
     * malformed constant — or a [DownloadUnit.relativePath] the shipped storage rejects
     * with [InvalidDownloadUnitException] fails the unit here, as [DownloadError.Unknown] and without
     * enqueueing a transfer, instead of after the whole download on every attempt. Not an integrity
     * failure: downloading again cannot fix a catalogue. A transfer that is already running is
     * cancelled, since its bytes could not be committed; the temp file is deliberately kept, because
     * the bytes are not at fault and a corrected catalogue resumes from them.
     */
    private suspend fun failOnCatalogueError(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        unit: DownloadUnit,
    ) {
        val failure: DownloadFailedException = catalogueFailure(unit) ?: return
        logger.e(failure) { "The catalogue entry of $unit is unusable: ${failure.cause}" }
        cancelRunningTransfer(unit)
        groupState.value = GroupDownloadState.Error(error = failure.error)
        notifyError(group, failure.error)
        throw failure
    }

    /** The per-unit twin of the group overload above. */
    private suspend fun failOnCatalogueError(unit: DownloadUnit) {
        val failure: DownloadFailedException = catalogueFailure(unit) ?: return
        logger.e(failure) { "The catalogue entry of $unit is unusable: ${failure.cause}" }
        cancelRunningTransfer(unit)
        unitStateFlow(unit).value = UnitDownloadState.Error(failure.error)
        throw failure
    }

    /** Stops a transfer of [unit] still running; never touches its temp file. */
    private fun cancelRunningTransfer(unit: DownloadUnit) {
        guardPort("Checking for a running transfer of $unit") {
            if (backgroundDownloader.isDownloadInProgress(unit)) backgroundDownloader.cancelDownload(unit)
        }
    }

    private fun catalogueFailure(unit: DownloadUnit): DownloadFailedException? {
        try {
            unit.sha256
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DownloadFailedException(
                unit = unit,
                error = DownloadError.Unknown(e.message ?: "DownloadUnit.sha256 cannot be read"),
                cause = e,
            )
        }
        // Only a storage that implements RelativePathChecking is asked: any other (a host's own) is
        // never called here, so it behaves exactly as in 3.0.0.
        val reason: InvalidDownloadUnitException.Reason =
            (storage as? RelativePathChecking)?.relativePathViolation(unit) ?: return null
        return DownloadFailedException(
            unit = unit,
            error = DownloadError.Unknown("DownloadUnit.relativePath is invalid: $reason"),
            cause = InvalidDownloadUnitException(unit, reason),
        )
    }

    /**
     * Transfers a single [unit] until its temp file is complete, with automatic retry on error:
     * - Never retries [DownloadError.NotFound] or [DownloadError.Unauthorized]: the same request
     *   would get the same answer, so the failure is reported at once.
     * - Retries any other failure up to [MAX_STALL_RETRIES] times in a row. An attempt that
     *   advanced past the furthest fraction any earlier attempt of this call reported (the
     *   high-water mark) starts that count afresh; a repeated or lower [BackgroundDownloadEvent.Progress]
     *   is not progress, however many arrive.
     * - Waits an exponentially growing, capped time before every re-enqueue ([retryBackoff]).
     * - The failure count persists via [DownloadStateStore] to survive process death.
     */
    private suspend fun downloadUnitWithRetry(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        unit: DownloadUnit,
        progressBase: Float,
        progressScale: Float,
        token: CancelToken,
    ) {
        // Start or attach to an existing background download
        val inProgress: Boolean = backgroundDownloader.isDownloadInProgress(unit)
        logger.i {
            "downloadUnitWithRetry($unit) — inProgress=$inProgress, " +
                "progressBase=$progressBase, progressScale=$progressScale"
        }
        // The call's first transfer announces itself at progressBase, once, just before it is
        // enqueued: a retry keeps the bar where it was.
        var announced = false
        val announceStart: suspend () -> Unit = {
            if (!announced) {
                announced = true
                // One atomic write that gives way to a cancel: a start that lost to a cancel must
                // not publish Downloading over the cancel's Idle (nor post a notification).
                var published = false
                groupState.update { current: GroupDownloadState ->
                    published = !token.isCancelled
                    if (published) GroupDownloadState.Downloading(progress = progressBase) else current
                }
                if (published) notifyProgress(group, progressBase)
            }
        }
        // A cancel that lands while the transfer is starting: take the start back (the state and
        // the notification), see transferStarts.
        val undoStart: () -> Unit = {
            if (announced) {
                groupState.value = GroupDownloadState.Idle
                notifyRemove(group)
            }
        }

        val progressMark = ProgressMark()
        var failedAttempts = 0
        // The first attempt attaches to a transfer already running, or starts one; every later one
        // starts a new transfer. Either way the attempt subscribes to the events before it enqueues.
        var enqueueThisAttempt: Boolean = !inProgress
        while (true) {
            val result: DownloadAttemptResult = awaitBackgroundDownloadCompletion(
                group = group,
                groupState = groupState,
                unit = unit,
                progressBase = progressBase,
                progressScale = progressScale,
                progressMark = progressMark,
                enqueue = enqueueThisAttempt,
                token = token,
                beforeEnqueue = announceStart,
                undoStart = undoStart,
            )
            // Whatever ended the attempt, a cancel requested meanwhile ends the call.
            throwIfGroupCancelled(group, groupState, token, unit)

            val eventName: String? = result.terminalEvent::class.simpleName
            logger.i {
                "awaitCompletion result for $unit: event=$eventName, " +
                    "madeProgress=${result.madeProgress}"
            }
            when (result.terminalEvent) {
                is BackgroundDownloadEvent.FileReady -> {
                    logger.i { "FileReady for $unit" }
                    markCompleteOnFileReady(unit)
                    clearStallCount(unit)
                    return
                }

                is BackgroundDownloadEvent.Cancelled -> {
                    logger.i { "Cancelled for $unit" }
                    clearStallCount(unit)
                    groupState.value = GroupDownloadState.Idle
                    notifyRemove(group)
                    safeDeleteTempFile(unit)
                    throw DownloadCancelledException(group)
                }

                is BackgroundDownloadEvent.Error -> {
                    val errorMessage: String = result.terminalEvent.message
                    logger.e { "Error for $unit: $errorMessage" }
                    // The transfer may have finished and its FileReady been lost (a downloader that
                    // emitted it with nobody listening, or one that failed while reporting it): a
                    // complete temp file is committed, never re-downloaded or deleted.
                    if (tempFileIsComplete(unit)) {
                        logger.i { "Temp file of $unit is complete despite the error, committing it" }
                        clearStallCount(unit)
                        return
                    }
                    val downloadError: DownloadError =
                        if (result.stalled) DownloadError.Timeout else errorMessage.toDownloadError()
                    if (downloadError.isWorthRetrying() &&
                        shouldRetry(unit = unit, madeProgress = result.madeProgress)
                    ) {
                        failedAttempts++
                        val backoff: Duration = retryBackoff(failedAttempts)
                        logger.i { "Retrying download for $unit in $backoff (error: $errorMessage)" }
                        // A cancel during the wait ends it at once; waiting it out and then
                        // re-enqueueing is what undid a cancel or a logout.
                        withTimeoutOrNull(backoff) { token.awaitCancel() }
                        throwIfGroupCancelled(group, groupState, token, unit)
                        enqueueThisAttempt = true
                        continue
                    }
                    clearStallCount(unit)
                    logger.e { "Download failed for $unit: $errorMessage" }
                    groupState.value = GroupDownloadState.Error(error = downloadError)
                    notifyError(group, downloadError)
                    deleteTempFileAfterFailure(unit, downloadError)
                    throw DownloadFailedException(unit = unit, error = downloadError)
                }
            }
        }
    }

    /**
     * The furthest unit-level fraction any attempt of one [downloadUnitWithRetry] call has
     * reported. Only a [BackgroundDownloadEvent.Progress] beyond it counts as progress — a transfer
     * that restarts and re-reports where it already was has not moved.
     */
    private class ProgressMark {
        var highWater: Float = 0f
    }

    private data class DownloadAttemptResult(
        val terminalEvent: BackgroundDownloadEvent.Terminal,
        val madeProgress: Boolean,
        /** True when [terminalEvent] is the engine's own stall error, not one the downloader sent. */
        val stalled: Boolean,
    )

    /**
     * Suspends until the background download for [unit] emits a terminal event. Maps unit-level
     * progress to the overall group progress using [progressBase] and [progressScale].
     *
     * Returns a [DownloadAttemptResult] containing the terminal event and whether this attempt
     * advanced [progressMark] — used by [downloadUnitWithRetry] to decide on retry. [enqueue] says
     * whether this attempt starts the transfer (false: it attaches to one already running); see
     * [awaitTerminalEvent] for the subscription order and the stall detection.
     */
    private suspend fun awaitBackgroundDownloadCompletion(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        unit: DownloadUnit,
        progressBase: Float,
        progressScale: Float,
        progressMark: ProgressMark,
        enqueue: Boolean,
        token: CancelToken,
        beforeEnqueue: suspend () -> Unit,
        undoStart: () -> Unit,
    ): DownloadAttemptResult {
        var madeProgress = false

        val wait: TerminalWait = awaitTerminalEvent(
            unit = unit,
            enqueue = enqueue,
            token = token,
            beforeEnqueue = beforeEnqueue,
            undoStart = undoStart,
        ) { event ->
            // Progress that arrives after a cancel (buffered ahead of its Cancelled, or from a
            // call that has not noticed yet) must not bring the group back to Downloading.
            if (token.isCancelled) return@awaitTerminalEvent
            if (event.fraction > progressMark.highWater) {
                progressMark.highWater = event.fraction
                madeProgress = true
            }
            val scaledProgress: Float = progressBase + event.fraction * progressScale
            // Guard: never report progress lower than the current value.
            // On retry, the new attempt starts at fraction=0 before real
            // progress is reported — without this check the UI would
            // jump back to progressBase (e.g. 50% for a second unit).
            var advanced = false
            groupState.update { current: GroupDownloadState ->
                val currentProgress: Float =
                    (current as? GroupDownloadState.Downloading)?.progress ?: 0f
                advanced = scaledProgress > currentProgress && !token.isCancelled
                if (advanced) GroupDownloadState.Downloading(progress = scaledProgress) else current
            }
            if (advanced) notifyProgress(group, scaledProgress)
        }

        return DownloadAttemptResult(
            terminalEvent = wait.terminalEvent,
            madeProgress = madeProgress,
            stalled = wait.stalled,
        )
    }

    /** One attempt's terminal event, and whether it is the engine's own stall error. */
    private class TerminalWait(
        val terminalEvent: BackgroundDownloadEvent.Terminal,
        val stalled: Boolean,
    )

    /**
     * Subscribes to [BackgroundResourceDownloader.observeProgress] for [unit], THEN (when [enqueue])
     * starts the transfer, and suspends until a terminal event arrives. The order matters: a
     * downloader that publishes through a `MutableSharedFlow` without replay drops whatever it emits
     * with no subscriber, and a transfer that finishes inside `enqueueDownload` — a download that is
     * already whole, a failure to begin — would otherwise lose its terminal event and the attempt
     * would only end by stalling. The collection is started undispatched so it is registered before
     * [BackgroundResourceDownloader.enqueueDownload] is called, and buffers everything after that.
     *
     * - **Starting.** Whatever the subscription delivered before the enqueue is dropped: with the
     *   undispatched start that can only be a replay of an earlier attempt, and taking its terminal
     *   for this attempt's outcome would end a transfer nobody has started yet (or burn the retry
     *   budget on one real failure). Events emitted inside or after the enqueue are kept. When the
     *   flow had already ended by then (it completed on that leftover), the engine subscribes again
     *   right after the enqueue.
     * - **Attaching** ([enqueue] false). The subscription is registered first and the decision is
     *   re-checked after it: a transfer that ended between the caller's look and the subscription
     *   has had its terminal event dropped by a downloader without replay, so a complete temp file
     *   is taken as the transfer's `FileReady` and anything else starts a transfer. A replayed
     *   outcome of a transfer that is still listed as running is kept — that is the relaunch case.
     * - **Cancelling.** A cancel requested ([token]) before the enqueue means nothing is started; one
     *   that lands during it stops the transfer just started. Either way the result is a
     *   [BackgroundDownloadEvent.Cancelled], which the caller turns into its cancelled outcome.
     * - **No terminal.** A flow that completes without one fails the attempt at once
     *   ([IllegalStateException] as the cause) instead of being waited out as a stall — with
     *   `Duration.INFINITE` it would never end.
     *
     * Uses **inactivity timeout** (stall detection) instead of a fixed wall-clock timeout:
     * - The timer is armed at subscription, so a transfer silent from the start stalls too.
     *   [transformLatest] restarts the inner block on each new event, cancelling the previous
     *   [delay]. A repeated [BackgroundDownloadEvent.Progress] restarts it like any other event.
     * - If no event arrives within [DownloaderConfig.stallTimeout], the delay completes and throws
     *   [DownloadResourceStallException]; the transfer is cancelled and the attempt ends as a stall.
     * - A slow-but-progressing download (receiving Progress events) will never stall-timeout.
     * - Primary network/transport timeouts are handled by the [BackgroundResourceDownloader].
     *
     * [beforeEnqueue] runs just before a transfer is started (the caller's "Downloading" publish);
     * [onProgress] runs for each [BackgroundDownloadEvent.Progress].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitTerminalEvent(
        unit: DownloadUnit,
        enqueue: Boolean,
        token: CancelToken,
        beforeEnqueue: suspend () -> Unit,
        undoStart: () -> Unit,
        onProgress: suspend (BackgroundDownloadEvent.Progress) -> Unit,
    ): TerminalWait = coroutineScope {
        val events: Channel<Any> = Channel(Channel.UNLIMITED)
        fun subscribe(): Job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                backgroundDownloader.observeProgress(unit).collect { event: BackgroundDownloadEvent ->
                    events.send(event)
                }
                // Always a marker element (never a closed or failed channel: transformLatest below
                // drops what it has emitted but not yet delivered when its upstream fails, which
                // would lose a terminal event already on its way, and a closed channel would also
                // refuse the cancel marker). Whether the end of the flow matters is decided by the
                // consumer, after the drain: a terminal the drain removed does not count.
                events.send(ObserveCompleted)
            } catch (e: CancellationException) {
                events.close()
                throw e
            } catch (e: Throwable) {
                events.close(e)
            }
        }
        var subscription: Job = subscribe()
        var cancelWatcher: Job? = null
        try {
            var startTransfer: Boolean = enqueue
            if (!startTransfer && !backgroundDownloader.isDownloadInProgress(unit)) {
                if (tempFileIsComplete(unit) || storage.isResourceAvailable(unit)) {
                    logger.i { "The transfer of $unit ended before the engine subscribed; its file is complete" }
                    return@coroutineScope TerminalWait(BackgroundDownloadEvent.FileReady(unit), stalled = false)
                }
                logger.i { "The transfer of $unit ended before the engine subscribed, starting it again" }
                startTransfer = true
            }
            if (startTransfer) {
                // The start is registered before its first token check and released after its last
                // one, so cancelAllDownloads can wait for a start that is in flight (see
                // transferStarts).
                val ticket = TransferStart()
                transferStarts.update { current: Set<TransferStart> -> current + ticket }
                try {
                    if (token.isCancelled) return@coroutineScope cancelledWait(unit)
                    beforeEnqueue()
                    if (token.isCancelled) {
                        undoStart()
                        return@coroutineScope cancelledWait(unit)
                    }
                    // Nothing is suspended between this drain and the enqueue, so what is dropped here
                    // was delivered at subscription (a replay), never by this attempt's transfer.
                    var subscriptionEnded = false
                    while (true) {
                        val dropped: Any = events.tryReceive().getOrNull() ?: break
                        if (dropped === ObserveCompleted) subscriptionEnded = true
                    }
                    backgroundDownloader.enqueueDownload(unit)
                    // A flow that ended on a leftover terminal (a per-unit state flow that completes on
                    // its last outcome) has nothing left to say: subscribe again, now that the transfer
                    // exists, and a port that replays its current state delivers this attempt's.
                    if (subscriptionEnded) subscription = subscribe()
                    if (token.isCancelled) {
                        // The cancel came after the check above: it either saw this transfer (and
                        // stopped it) or did not (and this stops it). cancelDownload bumps the epoch
                        // before it cancels the port, so one of the two always happens.
                        cancelTransfer(unit)
                        undoStart()
                        return@coroutineScope cancelledWait(unit)
                    }
                } finally {
                    transferStarts.update { current: Set<TransferStart> -> current - ticket }
                }
            }
            // A cancel ends the wait at once instead of leaving it to the port to report a
            // Cancelled (a port that never does would otherwise hold the call until it stalls).
            // Started after the drain above, which would otherwise discard its marker.
            cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                token.awaitCancel()
                events.trySend(CancelRequested)
            }
            var terminalSeen = false
            val terminalEvent: BackgroundDownloadEvent.Terminal = try {
                events.receiveAsFlow()
                    .map<Any, Any?> { it }
                    // A null first element arms the stall timer at subscription, so a transfer
                    // that never emits anything is stalled after the timeout like any other.
                    .onStart { emit(null) }
                    .onEach { event: Any? ->
                        if (event is BackgroundDownloadEvent.Progress) onProgress(event)
                    }
                    // The end of the flow after a terminal needs no handling (and must not disturb
                    // the terminal on its way); only an end with none seen is a failure.
                    .filter { event: Any? ->
                        if (event is BackgroundDownloadEvent.Terminal) terminalSeen = true
                        event !== ObserveCompleted || !terminalSeen
                    }
                    .transformLatest { event: Any? ->
                        if (event === CancelRequested) {
                            emit(BackgroundDownloadEvent.Cancelled(unit))
                            return@transformLatest
                        }
                        if (event === ObserveCompleted) {
                            throw IllegalStateException(
                                "observeProgress completed without a terminal event for $unit",
                            )
                        }
                        if (event != null) emit(event)
                        // Resets on each upstream emission; fires only if no new event arrives.
                        delay(config.stallTimeout)
                        throw DownloadResourceStallException()
                    }
                    .filterIsInstance<BackgroundDownloadEvent.Terminal>()
                    .first()
            } catch (e: DownloadResourceStallException) {
                logger.e(e) { "${e.message.orEmpty()} for $unit" }
                cancelTransfer(unit)
                // Returned as an Error so the retry loop can handle it, flagged as a stall so it is
                // reported as a timeout without reading the text back.
                return@coroutineScope TerminalWait(
                    terminalEvent = BackgroundDownloadEvent.Error(
                        unit = unit,
                        message = e.message ?: "Download stalled (timeout)",
                    ),
                    stalled = true,
                )
            } catch (e: CancellationException) {
                // The background download continues even if the coroutine is cancelled
                // (e.g., user navigated away). Do NOT cancel the notification —
                // the transfer is still running under the platform's own machinery.
                logger.i { "Detached from background download for $unit (coroutine cancelled: ${e.message})" }
                throw e
            }
            TerminalWait(terminalEvent = terminalEvent, stalled = false)
        } finally {
            cancelWatcher?.cancel()
            subscription.cancel()
            events.cancel()
        }
    }

    /** One request that is starting a transfer; plain class, so two starts are never equal. */
    private class TransferStart

    /**
     * The starts in flight: from just before a request's first pre-enqueue cancel check to just
     * after its post-enqueue one (which stops the transfer a cancel beat and takes the start's
     * state back). cancelAllDownloads waits for the starts that were in flight when it bumped the
     * epochs, so when it returns nothing it cancelled still shows Downloading or holds a transfer.
     */
    private val transferStarts: MutableStateFlow<Set<TransferStart>> = MutableStateFlow(emptySet())

    /** Queued when a cancel of the unit's group (or of the unit) is requested while it is awaited. */
    private object CancelRequested

    /** Queued by the subscription when the event flow ends. */
    private object ObserveCompleted

    private fun cancelledWait(unit: DownloadUnit): TerminalWait =
        TerminalWait(terminalEvent = BackgroundDownloadEvent.Cancelled(unit), stalled = false)

    /** True when the storage holds a finished download of [unit]; a read that fails is "no". */
    private fun tempFileIsComplete(unit: DownloadUnit): Boolean = try {
        storage.tempFileState(unit) == TempFileState.Complete
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(e) { "Cannot read the temp file state of $unit: ${e.message}" }
        false
    }

    /**
     * Counts this failed attempt and returns `true` while the run of failures is within
     * [MAX_STALL_RETRIES] retries. An attempt that advanced the high-water mark
     * ([madeProgress]) restarts the run, so it counts as the first failure of a new one — a
     * transfer that really moves forward is allowed to keep going, one that only repeats itself is
     * stopped after three attempts.
     */
    private fun shouldRetry(unit: DownloadUnit, madeProgress: Boolean): Boolean {
        if (madeProgress) clearStallCount(unit)
        val failures: Int = incrementStallCount(unit)
        logger.i {
            "Failed attempt for $unit (progress=$madeProgress, failures in a row $failures, " +
                "retries allowed $MAX_STALL_RETRIES)"
        }
        return failures <= MAX_STALL_RETRIES
    }

    /** What retrying cannot fix: the same request would get the same answer. */
    private fun DownloadError.isWorthRetrying(): Boolean =
        this != DownloadError.NotFound && this != DownloadError.Unauthorized

    /**
     * How long to wait before the [failedAttempts]-th re-enqueue of one call: [RETRY_BACKOFF_BASE]
     * doubled for every failure so far, capped at [RETRY_BACKOFF_CAP]. Counts every failure of the
     * call, progress or not, so a server that keeps failing a little further along is not hammered.
     */
    private fun retryBackoff(failedAttempts: Int): Duration {
        var backoff: Duration = RETRY_BACKOFF_BASE
        repeat(failedAttempts - 1) {
            backoff = (backoff * 2).coerceAtMost(RETRY_BACKOFF_CAP)
        }
        return backoff
    }

    /**
     * Commits [unit] and, once the whole group is on disk, publishes completion. Returns false when
     * the bytes failed their integrity check and another download is allowed ([lastAttempt] false);
     * the temp file is already deleted then. Every other failure is published and thrown, and leaves
     * the temp file in place (see [failGroupCommit]).
     */
    private suspend fun commitUnitAndNotify(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        unit: DownloadUnit,
        lastAttempt: Boolean,
        token: CancelToken,
    ): Boolean {
        logger.i {
            "commitUnitAndNotify($unit) — " +
                "isAvailable=${storage.isResourceAvailable(unit)}, " +
                "tempFileState=${storage.tempFileState(unit)}"
        }
        try {
            // Guard: the platform downloader may have already committed the resource (self-commit,
            // so a transfer that finishes while the UI process is dead is not lost).
            // Skip commitResource() to avoid failing on an already-consumed temp file.
            if (storage.isResourceAvailable(unit)) {
                logger.i { "Resource unit $unit already committed, skipping commit" }
                dropStrayTempFile(unit)
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
            // A cancel that landed during the commit leaves the resource in place. While units are
            // still missing it ends the call; once the group is complete there is nothing left to
            // stop and the truth is published (a cancel after the last commit has no effect).
            if (!isAvailable(group)) throwIfGroupCancelled(group, groupState, token, unit)
            // Only report Completed when ALL units of the group are done.
            // Emit progress=1f first so the UI always shows 100 % before transitioning.
            logger.i { "After commit, isAvailable($group)=${isAvailable(group)}" }
            publishGroupCompletedIfAvailable(group, groupState)
            logger.i { "Resource unit $unit committed successfully" }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadCancelledException) {
            throw e
        } catch (e: ResourceIntegrityException) {
            // A commit that failed because the cancel took its file is a cancel, not an error.
            throwIfGroupCancelled(group, groupState, token, unit)
            logger.e(e) { "Resource $unit failed its integrity check: ${e.message}" }
            safeDeleteTempFile(unit)
            if (!lastAttempt) return false
            failGroupCommit(group, groupState, unit, DownloadError.Corrupted(e.message), e)
        } catch (e: Exception) {
            throwIfGroupCancelled(group, groupState, token, unit)
            val errorMessage: String = e.message ?: "Unknown error"
            logger.e(e) { "Failed to commit resource $unit: $errorMessage" }
            failGroupCommit(group, groupState, unit, DownloadError.Storage(errorMessage), e)
        }
    }

    /**
     * Publishes the group's completion when every unit is on disk; does nothing otherwise.
     */
    private suspend fun publishGroupCompletedIfAvailable(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
    ) {
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
                notifyRemove(group)
                notifyCompleted(group)
            }
        }
    }

    /**
     * Publishes and throws a commit failure. Deletes nothing: an integrity failure has already
     * deleted its bytes by the time it gets here, and every other failure ([DownloadError.Storage]:
     * a full disk, an unwritable path, an archive that would not extract) is not the bytes' fault —
     * the complete download stays so the next `ensureAvailable` commits it without a transfer once
     * the cause is fixed.
     */
    private suspend fun failGroupCommit(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        unit: DownloadUnit,
        downloadError: DownloadError,
        cause: Exception,
    ): Nothing {
        groupState.value = GroupDownloadState.Error(error = downloadError)
        notifyError(group, downloadError)
        throw DownloadFailedException(unit = unit, error = downloadError, cause = cause)
    }

    /**
     * [BackgroundDownloadEvent.FileReady] is itself the statement that the transfer finished, so a
     * temp file the downloader did not mark is marked here. Only recovery after process death,
     * where no FileReady is observed, depends on the downloader having marked it.
     *
     * A failure to mark is logged and left to the commit that follows, which finds no complete file
     * and reports it as [DownloadError.Storage] through the usual path.
     */
    private fun markCompleteOnFileReady(unit: DownloadUnit) {
        try {
            if (storage.tempFileState(unit) == TempFileState.Partial) {
                storage.markTempFileComplete(unit)
            }
        } catch (e: Exception) {
            logger.e(e) { "Failed to mark the temp file of $unit complete: ${e.message}" }
        }
    }

    // -- Calls into the host's ports ---------------------------------------------------
    //
    // A port is host code and can throw (a notification manager refusing a post, a foreground
    // service that may not start from the background, a key-value store that fails). None of that
    // may leave a group stuck at Downloading or reach the caller as a raw exception it was never
    // promised: a call whose failure cannot change the outcome is logged and swallowed here, and
    // anything else ends the unit as a DownloadFailedException through [failOnPortFailure].

    /** Runs a call whose failure must not change the outcome; logs and swallows it. */
    private inline fun guardPort(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "$what threw: ${e.message}" }
        }
    }

    // A failed notification must never fail the download it was describing (DownloadNotifier says
    // so), so a notifier that throws is logged and the download carries on.
    private suspend fun notifyProgress(group: ResourceGroup, progress: Float) =
        guardPort("DownloadNotifier.showProgress($group)") { notifier.showProgress(group, progress) }

    private suspend fun notifyCompleted(group: ResourceGroup) =
        guardPort("DownloadNotifier.showCompleted($group)") { notifier.showCompleted(group) }

    private suspend fun notifyError(group: ResourceGroup, error: DownloadError) =
        guardPort("DownloadNotifier.showError($group)") { notifier.showError(group, error) }

    private fun notifyRemove(group: ResourceGroup) =
        guardPort("DownloadNotifier.remove($group)") { notifier.remove(group) }

    private fun cancelTransfer(unit: DownloadUnit) =
        guardPort("BackgroundResourceDownloader.cancelDownload($unit)") {
            backgroundDownloader.cancelDownload(unit)
        }

    /**
     * Ends a group unit whose attempt was broken by a port that threw ([cause]): a transfer that
     * is still running is cancelled, the state becomes [GroupDownloadState.Error] with
     * [DownloadError.Unknown] — the engine cannot say more than the host's own message — and the
     * caller gets a [DownloadFailedException] carrying [cause]. The temp file stays.
     */
    private suspend fun failOnPortFailure(
        group: ResourceGroup,
        groupState: MutableStateFlow<GroupDownloadState>,
        unit: DownloadUnit,
        cause: Exception,
    ): Nothing {
        logger.e(cause) { "A port threw during the download of $unit: ${cause.message}" }
        cancelRunningTransfer(unit)
        clearStallCount(unit)
        val error: DownloadError = DownloadError.Unknown(portFailureMessage(cause))
        groupState.value = GroupDownloadState.Error(error = error)
        notifyError(group, error)
        throw DownloadFailedException(unit = unit, error = error, cause = cause)
    }

    /** The per-unit twin of the group overload above. */
    private fun failOnPortFailure(
        unit: DownloadUnit,
        stateFlow: MutableStateFlow<UnitDownloadState>,
        cause: Exception,
    ): Nothing {
        logger.e(cause) { "A port threw during the download of $unit: ${cause.message}" }
        cancelRunningTransfer(unit)
        clearStallCount(unit)
        val error: DownloadError = DownloadError.Unknown(portFailureMessage(cause))
        stateFlow.value = UnitDownloadState.Error(error)
        throw DownloadFailedException(unit = unit, error = error, cause = cause)
    }

    private fun portFailureMessage(cause: Exception): String =
        cause.message ?: cause::class.simpleName ?: "A port threw"

    /**
     * What a transfer that ended in [error] leaves behind. The partial file is the user's progress,
     * so it stays for the next [ensureAvailable] to resume from, unless the failure says those bytes
     * are of no use: [DownloadError.NotFound] means the object is gone from the server, so whatever
     * was downloaded of it can never be completed. A lost connection, a timeout, a 5xx, an
     * [DownloadError.Unauthorized] that a refreshed session fixes, or an unclassified failure all
     * keep it. The other deletions are not failures of the transfer: a user cancel, and bytes that
     * fail their integrity check at commit.
     */
    private fun deleteTempFileAfterFailure(unit: DownloadUnit, error: DownloadError) {
        if (error == DownloadError.NotFound) {
            safeDeleteTempFile(unit)
        } else {
            logger.i { "Keeping the temp file of $unit after $error, so a retry resumes from it" }
        }
    }

    /**
     * Called when [unit] is already committed and this call skips the commit: another commit (a
     * second engine over the same directory, or a platform downloader that commits on its own)
     * finalized it first. A transfer of this call may still have finished on its own, and its
     * [TempFileState.Complete] temp file would stay on disk for ever, since nothing consumes it
     * any more. It is dropped here; a [TempFileState.Partial] file belongs to a transfer that is
     * still running and is left alone. Best effort, like every other temp file delete.
     */
    private fun dropStrayTempFile(unit: DownloadUnit) {
        if (storage.tempFileState(unit) == TempFileState.Complete) {
            logger.i { "Dropping the temp file of $unit, which was committed by another commit" }
            safeDeleteTempFile(unit)
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

    /**
     * A tracked unit: the state flow, the unit itself (so a sweep can address the download) and the
     * unit's cancel epoch (see [CancelToken]).
     */
    private class UnitEntry(
        val unit: DownloadUnit,
        val state: MutableStateFlow<UnitDownloadState>,
        val cancelEpoch: MutableStateFlow<Int> = MutableStateFlow(0),
    )

    // Both maps grow with the runtime-constructed unit set, unlike the group maps above — such a
    // unit's id isn't known until a manifest is fetched, so there is no fixed set to build these
    // from upfront. Keyed by unit.id, never by the object: two equal-id instances must land on the
    // same state and the same mutex regardless of how the host implemented equals. Each is an
    // immutable map in a StateFlow with a compare-and-set get-or-put, so a lookup never suspends
    // (cancelDownload(unit) is not a suspend function and must act at once) and the type works in
    // commonMain on Kotlin/Native, which ConcurrentHashMap would not.
    private val unitEntries: MutableStateFlow<Map<String, UnitEntry>> = MutableStateFlow(emptyMap())
    private val unitMutexes: MutableStateFlow<Map<String, Mutex>> = MutableStateFlow(emptyMap())

    private fun unitEntry(unit: DownloadUnit): UnitEntry {
        unitEntries.value[unit.id]?.let { return it }
        // Seeded from disk, so the very first value a collector sees already answers
        // "is this here from an earlier session?" without a separate storage read. Built outside
        // the compare-and-set, which may run its lambda more than once.
        val candidate = UnitEntry(
            unit = unit,
            state = MutableStateFlow(
                if (storage.isResourceAvailable(unit)) {
                    UnitDownloadState.Available
                } else {
                    UnitDownloadState.Idle
                },
            ),
        )
        unitEntries.update { entries: Map<String, UnitEntry> ->
            if (unit.id in entries) entries else entries + (unit.id to candidate)
        }
        return unitEntries.value.getValue(unit.id)
    }

    private fun unitStateFlow(unit: DownloadUnit): MutableStateFlow<UnitDownloadState> =
        unitEntry(unit).state

    private fun unitMutex(unit: DownloadUnit): Mutex {
        unitMutexes.value[unit.id]?.let { return it }
        val candidate = Mutex()
        unitMutexes.update { mutexes: Map<String, Mutex> ->
            if (unit.id in mutexes) mutexes else mutexes + (unit.id to candidate)
        }
        return unitMutexes.value.getValue(unit.id)
    }

    // A cold flow, not a direct exposure of the cached MutableStateFlow: the cache entry is created
    // on first collection, so merely asking about a unit does not start tracking it.
    override fun unitDownloadStateFlow(unit: DownloadUnit): Flow<UnitDownloadState> = flow {
        emitAll(unitStateFlow(unit))
    }

    override suspend fun ensureAvailable(unit: DownloadUnit) {
        if (storage.isResourceAvailable(unit)) {
            markUnitOnDisk(unit)
            return
        }
        val entry: UnitEntry = unitEntry(unit)
        // Taken before the mutex, like the group call's.
        val token = CancelToken(entry.cancelEpoch)
        unitMutex(unit).withLock {
            if (storage.isResourceAvailable(unit)) {
                markUnitOnDisk(unit)
                return
            }
            try {
                throwIfUnitCancelled(unit, entry.state, token)
                fetchAndCommitUnit(unit, token)
            } catch (e: CancellationException) {
                if (token.isCancelled) {
                    withContext(NonCancellable) { cleanUpCancelledUnit(unit, entry.state) }
                }
                throw e
            }
        }
    }

    /** What a cancelled unit call leaves behind: no temp file, no failure count, state Idle. */
    private fun cleanUpCancelledUnit(unit: DownloadUnit, stateFlow: MutableStateFlow<UnitDownloadState>) {
        clearStallCount(unit)
        safeDeleteTempFile(unit)
        stateFlow.value = UnitDownloadState.Idle
    }

    /** The per-unit twin of [throwIfGroupCancelled]. */
    private fun throwIfUnitCancelled(
        unit: DownloadUnit,
        stateFlow: MutableStateFlow<UnitDownloadState>,
        token: CancelToken,
    ) {
        if (!token.isCancelled) return
        logger.i { "Download of $unit cancelled while running" }
        cleanUpCancelledUnit(unit, stateFlow)
        throw DownloadCancelledException(group = unit.group, unit = unit)
    }

    /**
     * The per-unit twin of [fetchAndCommitGroupUnit]: commits a [TempFileState.Complete] temp file
     * straight away, transfers first otherwise (attaching to a running transfer or resuming a
     * partial file, never committing one), and downloads [MAX_INTEGRITY_RETRIES] more times when
     * the bytes fail their integrity check.
     */
    private suspend fun fetchAndCommitUnit(unit: DownloadUnit, token: CancelToken) {
        try {
            transferAndCommitUnit(unit, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadFailedException) {
            throw e
        } catch (e: DownloadCancelledException) {
            throw e
        } catch (e: Exception) {
            failOnPortFailure(unit, unitStateFlow(unit), e)
        }
    }

    private suspend fun transferAndCommitUnit(unit: DownloadUnit, token: CancelToken) {
        failOnCatalogueError(unit)
        val stateFlow: MutableStateFlow<UnitDownloadState> = unitStateFlow(unit)
        var integrityFailures = 0
        while (true) {
            throwIfUnitCancelled(unit, stateFlow, token)
            if (storage.tempFileState(unit) != TempFileState.Complete) {
                downloadUnitOnly(unit, token)
            }
            if (commitUnit(unit, lastAttempt = integrityFailures >= MAX_INTEGRITY_RETRIES, token = token)) return
            integrityFailures++
            logger.e {
                "Integrity check failed for $unit — downloading again " +
                    "($integrityFailures/$MAX_INTEGRITY_RETRIES)"
            }
        }
    }

    /**
     * Records that [unit] is on disk without a transfer having run. [UnitDownloadState.Completed]
     * is deliberately preserved: it means "downloaded this process", and a redundant
     * ensureAvailable after that fact must not erase the distinction observers key off. One atomic
     * update, so a commit publishing Completed between a read and a write cannot be overwritten.
     */
    private fun markUnitOnDisk(unit: DownloadUnit) {
        unitStateFlow(unit).update { current: UnitDownloadState ->
            if (current is UnitDownloadState.Completed) current else UnitDownloadState.Available
        }
    }

    /**
     * Synchronous: the transfer is cancelled and the state is Idle when this returns, so a request
     * made right after it starts clean instead of racing a cancel that is still on its way.
     */
    override fun cancelDownload(unit: DownloadUnit) {
        val entry: UnitEntry? = unitEntries.value[unit.id]
        if (entry != null) {
            cancelUnitNow(entry)
            return
        }
        // Never tracked, so there is no state to reset. Release what the platform and the disk may
        // hold, without creating an entry (a state flow that would then live as long as the engine);
        // only the unit's lock object is created, so the delete below is guarded exactly like the
        // tracked path's. A request on another thread creates its entry BEFORE it locks the unit:
        // either it holds the lock now (tryLock fails, nothing is deleted under its commit, and the
        // late cancel below bumps its epoch), or it waits behind this delete and starts clean.
        val mutex: Mutex = unitMutex(unit)
        val ownsUnit: Boolean = mutex.tryLock()
        try {
            cancelTransfer(unit)
            clearStallCount(unit)
            if (ownsUnit) safeDeleteTempFile(unit)
        } finally {
            if (ownsUnit) mutex.unlock()
        }
        unitEntries.value[unit.id]?.let { late: UnitEntry -> cancelUnitNow(late) }
    }

    private fun cancelUnitNow(entry: UnitEntry) {
        val unit: DownloadUnit = entry.unit
        // First, so a running call sees it at its next checkpoint (see cancelDownload(group)).
        entry.cancelEpoch.update { it + 1 }
        val mutex: Mutex = unitMutex(unit)
        val ownsUnit: Boolean = mutex.tryLock()
        try {
            cancelTransfer(unit)
            clearStallCount(unit)
            // A running call deletes the temp file itself, after any commit in flight.
            if (ownsUnit) safeDeleteTempFile(unit)
            entry.state.value = UnitDownloadState.Idle
        } finally {
            if (ownsUnit) mutex.unlock()
        }
    }

    override suspend fun hasActiveUnitDownload(): Boolean {
        val states: List<MutableStateFlow<UnitDownloadState>> =
            unitEntries.value.values.map { it.state }
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
    private suspend fun downloadUnitOnly(unit: DownloadUnit, token: CancelToken) {
        val stateFlow: MutableStateFlow<UnitDownloadState> = unitStateFlow(unit)
        val enqueue: Boolean = !backgroundDownloader.isDownloadInProgress(unit)

        val wait: TerminalWait = awaitTerminalEvent(
            unit = unit,
            enqueue = enqueue,
            token = token,
            // One atomic write that gives way to a cancel (see the group's announceStart).
            beforeEnqueue = {
                stateFlow.update { current: UnitDownloadState ->
                    if (token.isCancelled) current else UnitDownloadState.Downloading(progress = 0f)
                }
            },
            // The call holds the unit's mutex, so no other call owns this state.
            undoStart = { stateFlow.value = UnitDownloadState.Idle },
        ) { event ->
            // One atomic write that gives way to a cancel (see the group handler).
            stateFlow.update { current: UnitDownloadState ->
                if (token.isCancelled) current else UnitDownloadState.Downloading(progress = event.fraction)
            }
        }
        // Whatever ended the attempt, a cancel requested meanwhile ends the call.
        throwIfUnitCancelled(unit, stateFlow, token)

        settleUnitDownload(
            unit = unit,
            stateFlow = stateFlow,
            terminalEvent = wait.terminalEvent,
            stalled = wait.stalled,
        )
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
        stalled: Boolean,
    ) {
        when (terminalEvent) {
            is BackgroundDownloadEvent.FileReady -> markCompleteOnFileReady(unit)
            is BackgroundDownloadEvent.Cancelled -> {
                clearStallCount(unit)
                stateFlow.value = UnitDownloadState.Idle
                safeDeleteTempFile(unit)
                throw DownloadCancelledException(group = unit.group, unit = unit)
            }

            is BackgroundDownloadEvent.Error -> {
                clearStallCount(unit)
                // A finished download whose FileReady was lost is committed, never deleted.
                if (tempFileIsComplete(unit)) {
                    logger.i { "Temp file of $unit is complete despite the error, committing it" }
                    return
                }
                val downloadError: DownloadError =
                    if (stalled) DownloadError.Timeout else terminalEvent.message.toDownloadError()
                stateFlow.value = UnitDownloadState.Error(downloadError)
                deleteTempFileAfterFailure(unit, downloadError)
                throw DownloadFailedException(unit = unit, error = downloadError)
            }
        }
    }

    /** The per-unit twin of [commitUnitAndNotify], with the same return contract. */
    private suspend fun commitUnit(unit: DownloadUnit, lastAttempt: Boolean, token: CancelToken): Boolean {
        val stateFlow: MutableStateFlow<UnitDownloadState> = unitStateFlow(unit)
        try {
            if (!storage.isResourceAvailable(unit)) {
                storage.commitResource(unit)
            } else {
                dropStrayTempFile(unit)
            }
            if (!storage.isResourceAvailable(unit)) {
                error("Resource $unit not available after commit")
            }
            // A cancel that arrives once the commit succeeded has nothing left to stop: Completed
            // is the truth.
            // Same reasoning as the group path: once the file is on disk the terminal publish is
            // bookkeeping and must not be lost to a caller cancelling mid-way — the next
            // ensureAvailable would short-circuit on isAvailable and never set Completed.
            withContext(NonCancellable) {
                clearStallCount(unit)
                stateFlow.value = UnitDownloadState.Completed
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadCancelledException) {
            throw e
        } catch (e: ResourceIntegrityException) {
            throwIfUnitCancelled(unit, stateFlow, token)
            logger.e(e) { "Resource $unit failed its integrity check: ${e.message}" }
            safeDeleteTempFile(unit)
            if (!lastAttempt) return false
            failUnitCommit(unit, stateFlow, DownloadError.Corrupted(e.message), e)
        } catch (e: Exception) {
            throwIfUnitCancelled(unit, stateFlow, token)
            logger.e(e) { "Failed to commit resource $unit: ${e.message}" }
            failUnitCommit(unit, stateFlow, DownloadError.Storage(e.message), e)
        }
    }

    /** The per-unit twin of [failGroupCommit], with the same rule about the temp file. */
    private fun failUnitCommit(
        unit: DownloadUnit,
        stateFlow: MutableStateFlow<UnitDownloadState>,
        downloadError: DownloadError,
        cause: Exception,
    ): Nothing {
        stateFlow.value = UnitDownloadState.Error(downloadError)
        throw DownloadFailedException(unit = unit, error = downloadError, cause = cause)
    }

    // -- Stall counter (persisted through the DownloadStateStore port) --------------

    private fun getStallCount(unit: DownloadUnit): Int =
        stateStore.readInt(stallCountKey(unit), 0)

    private fun incrementStallCount(unit: DownloadUnit): Int {
        val newCount: Int = getStallCount(unit) + 1
        stateStore.writeInt(stallCountKey(unit), newCount)
        return newCount
    }

    /** Never throws: a counter that could not be cleared is stale at worst, never a failed download. */
    private fun clearStallCount(unit: DownloadUnit) {
        guardPort("Clearing the failure count of $unit") { stateStore.remove(stallCountKey(unit)) }
    }

    private fun stallCountKey(unit: DownloadUnit): String = "download_stall_count_${unit.id}"

    private companion object {
        /** Max retries in a row of a failing transfer (three attempts) before giving up. */
        private const val MAX_STALL_RETRIES = 2

        /** The wait before the first retry; doubles for each further failure of one call. */
        private val RETRY_BACKOFF_BASE: Duration = 2.seconds

        /** The longest wait before a retry. */
        private val RETRY_BACKOFF_CAP: Duration = 30.seconds

        /**
         * Fresh downloads after bytes fail their integrity check, before [DownloadError.Corrupted].
         * One: a transient corruption is fixed by it, and a persistent one — a stale hash, a broken
         * upload — is not fixed by any number, so more would only spend the user's data.
         */
        private const val MAX_INTEGRITY_RETRIES = 1

        /** Lets the UI render a full progress bar before it is replaced by the completed state. */
        private const val COMPLETION_SETTLE_DELAY_MILLIS = 300L
    }
}

/**
 * An HTTP status as a downloader words it: `HTTP 503`, `HTTP error: 500`, `HTTP/1.1 404`,
 * `status 502`, `status code: 410`. The status word must start a word and the digits must follow it
 * directly (only `error` / `status` / `code` and separators in between), so a three-digit run inside
 * a URL (`https://cdn/assets/40123.zip`), an id or a byte count is never taken for a status.
 * Written so no whitespace run can be split between two quantifiers: matching stays linear in the
 * message length.
 */
private val HTTP_STATUS: Regex =
    Regex("\\b(?:http(?:/\\d(?:\\.\\d)?)?|status)(?:\\s+(?:error|status|code)){0,2}\\s*(?:[:=]\\s*)?(\\d{3})\\b")

/**
 * Classifies a platform-emitted [BackgroundDownloadEvent.Error.message] into a [DownloadError].
 *
 * The platform contract yields only a string — what a failing transfer reports differs per platform
 * and per layer — so this has to match text. Everything downstream is typed, which is the point: the
 * string handling is confined to this one function.
 *
 * A status is read only where a downloader states it (see [HTTP_STATUS], and
 * `docs/kmptoolkit-downloader/07-background-downloader.md` for the phrasing to use): 404 and 410 are
 * [DownloadError.NotFound], 401 and 403 [DownloadError.Unauthorized], 5xx [DownloadError.Server].
 * Any other status (416, 429, ...) is [DownloadError.Unknown] with the raw text. A bare number
 * elsewhere in the message means nothing. The engine's own stall never comes through here — it is
 * [DownloadError.Timeout] directly.
 */
private fun String.toDownloadError(): DownloadError {
    val lowered: String = lowercase()
    val status: Int? = HTTP_STATUS.find(lowered)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        status == 404 || status == 410 -> DownloadError.NotFound

        // The wording is a fallback for a message that states no status: next to a stated 5xx or
        // 416 it is only text ("HTTP 503: upstream said Forbidden").
        status == 401 || status == 403 ||
            (status == null && ("unauthorized" in lowered || "forbidden" in lowered)) ->
            DownloadError.Unauthorized

        status != null && status in 500..599 -> DownloadError.Server(statusCode = status)

        status != null -> DownloadError.Unknown(this)

        "timeout" in lowered ||
            "timed out" in lowered -> DownloadError.Timeout

        "unable to resolve" in lowered ||
            "no address" in lowered ||
            "network" in lowered ||
            "no internet" in lowered -> DownloadError.NoConnection

        else -> DownloadError.Unknown(this)
    }
}
