package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadNotifier
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What a consumer calls to get a remote resource onto the device and to watch it happen.
 *
 * Two parallel surfaces, because the two kinds of resource genuinely differ:
 *
 * - **By [ResourceGroup]** — for a fixed bundle the app ships against (a model and a companion
 *   file it needs, a page archive and its index). One download per group at a time, aggregate
 *   progress, one notification; different groups may download in parallel.
 * - **By [DownloadUnit]** — for a catalogue whose members are only known at runtime, where several
 *   can be in flight at once and each row needs its own progress and its own cancel.
 *
 * The per-unit half is not a generalization of the group half: the group half's mutex, progress
 * scaling and notification machinery are proven for the large bundles and are left alone.
 *
 * **Failure contract — identical on both surfaces.** `ensureAvailable` throws
 * [DownloadCancelledException] when the download was cancelled and [DownloadFailedException] once
 * the engine has stopped retrying; both also publish the same outcome to the corresponding state
 * flow, so a caller may equally ignore the exception and render from state.
 */
public interface Downloader {

    /** True when every unit of [group] is present and committed on disk. Synchronous, no I/O wait. */
    public fun isAvailable(group: ResourceGroup): Boolean

    /**
     * True when [unit]'s resource is present and committed on disk. Unlike the group overload this
     * never consults the bundled-assets bypass — a runtime-catalogue unit is never bundled.
     */
    public fun isAvailable(unit: DownloadUnit): Boolean

    /**
     * Absolute path of [unit]'s committed resource — what a consumer opens after
     * [ensureAvailable] returns. Valid only while [isAvailable] holds.
     */
    public fun pathOf(unit: DownloadUnit): String

    /**
     * [group]'s download state. One flow per group, so two groups downloading in parallel never
     * overwrite each other's progress. Throws [IllegalArgumentException] for a group the host did
     * not declare to the engine — an undeclared group is a wiring mistake worth surfacing at once.
     */
    public fun downloadState(group: ResourceGroup): StateFlow<GroupDownloadState>

    /**
     * Downloads whatever of [group] is missing, suspending until it is all present. Returns at once
     * if it already is. Concurrent calls for the same group share one download rather than starting
     * two. Throws [DownloadCancelledException] / [DownloadFailedException] per the contract above.
     */
    public suspend fun ensureAvailable(group: ResourceGroup)

    /** Cancels [group]'s in-progress download, if any. Safe to call when none is running. */
    public fun cancelDownload(group: ResourceGroup)

    /**
     * Cancels every in-progress download — both group-level and per-unit. Used at logout, so
     * nothing keeps transferring under credentials that have just been revoked.
     *
     * Suspend because each unit's state reset is awaited here rather than dispatched: a caller
     * that cancels and then immediately asks [hasActiveUnitDownload] must not race the reset.
     */
    public suspend fun cancelAllDownloads()

    /**
     * Re-enqueues [group]'s missing units. Non-suspend, so it is safe from a fire-and-forget context
     * such as a notification action receiver; the caller does not wait for the result.
     */
    public fun retryDownload(group: ResourceGroup)

    /**
     * Downloads [unit] if it isn't already present, suspending until it completes, fails or is
     * cancelled. Concurrent calls for the SAME unit share one in-flight download. Progress is
     * observable independently via [unitDownloadStateFlow] — including by a caller that never
     * invoked this itself. Throws [DownloadCancelledException] / [DownloadFailedException] per the
     * contract above; the same outcome is always also published to the unit's state flow.
     */
    public suspend fun ensureAvailable(unit: DownloadUnit)

    /** Cancels [unit]'s in-progress or enqueued download, if any. Safe when none is active. */
    public fun cancelDownload(unit: DownloadUnit)

    /**
     * True while any per-unit download is currently [UnitDownloadState.Downloading]. Lets a host
     * skip incidental background work (re-syncing the catalogue a user is looking at) while a
     * download is in flight, so the list does not shift under their finger mid-transfer.
     */
    public suspend fun hasActiveUnitDownload(): Boolean

    /**
     * Observes [unit]'s state. The first value is seeded from disk: [UnitDownloadState.Available]
     * when the resource is already present, [UnitDownloadState.Idle] when it is not — so a caller
     * needs no separate storage read to tell "never asked" apart from "already downloaded".
     */
    public fun unitDownloadStateFlow(unit: DownloadUnit): Flow<UnitDownloadState>
}

/**
 * Thrown from [Downloader.ensureAvailable] when the download was cancelled — by the user or by a
 * logout — rather than failing on its own. Distinguished from a failure because a consumer usually
 * wants to fall silent on a cancel and show something on an error.
 *
 * [unit] is the specific unit whose transfer was cancelled when the per-unit surface threw this,
 * null when the group surface did.
 */
public class DownloadCancelledException(
    public val group: ResourceGroup,
    public val unit: DownloadUnit? = null,
) : RuntimeException("Download cancelled for ${unit?.id ?: group.key}")

/**
 * Thrown from [Downloader.ensureAvailable] once the engine has stopped retrying and the resource is
 * absent. Carries the same classified [error] the state flow received, so a caller in a try/catch
 * learns as much as an observer of the flow — not a bare message string.
 */
public class DownloadFailedException(
    public val unit: DownloadUnit,
    public val error: DownloadError,
    cause: Throwable? = null,
) : RuntimeException("Download failed for ${unit.id}: $error", cause)

/**
 * Creates a downloader engine.
 *
 * ```kotlin
 * val downloader: Downloader = createDownloader(
 *     storage = createDownloaderStorage(context), // or createDownloaderStorage() on iOS
 *     backgroundDownloader = myBackgroundResourceDownloader,
 *     groups = MyResourceGroup.entries,
 *     stateStore = myDownloadStateStore,
 * ).also(DownloaderRegistry::register)
 * ```
 *
 * @param storage where committed and in-progress bytes live. `createDownloaderStorage(...)` in
 *   `androidMain` / `iosMain` gives you the shipped implementation — see
 *   `docs/kmptoolkit-downloader/05-platform-notes.md`.
 * @param backgroundDownloader the platform machinery that moves bytes and keeps moving them after
 *   the app is backgrounded or killed. **The one port this module cannot supply for you** — see
 *   [io.github.jamal_wia.kmptoolkit.downloader.BackgroundResourceDownloader] and
 *   `docs/kmptoolkit-downloader/07-background-downloader.md`.
 * @param groups every [ResourceGroup] the host will ever ask about. A group missing from this list
 *   fails loudly on first use ([downloadState], [cancelDownload], [retryDownload] throw
 *   [IllegalArgumentException]; `ensureAvailable` does too, once past its availability short-circuit)
 *   rather than half-working.
 * @param stateStore the small amount of engine state that must survive process death — today, only
 *   the per-unit stall counter. Required rather than defaulted: an in-memory default would silently
 *   defeat the "worthless if it resets with the process" guarantee the counter exists for.
 * @param notifier how a download tells the user it is happening. Defaults to
 *   [DownloadNotifier.NoOp] — a consumer with no UI-facing download surface (a pure per-unit
 *   catalogue driven from its own progress rows, for instance) need not implement one.
 * @param bundledResourcesPresent when true, every declared group reports available and nothing
 *   group-level downloads — for a build that ships those assets inside the package instead of
 *   fetching them. Deliberately not consulted by the per-unit surface: a runtime-catalogue unit is
 *   never bundled. Defaults to `false`.
 * @param dispatchers the dispatchers the engine's own coroutines run on. Defaults to
 *   [DownloadDispatchers.Default]. A test substitutes a scheduler-backed implementation to run the
 *   engine, including its stall timeout, on virtual time.
 * @param logger where the engine reports what it did. Defaults to [NoopLogger]. Every enqueue,
 *   retry, stall, commit and notification hand-off is logged — pass a real logger and you can
 *   reconstruct the life of any download from the log.
 * @return a [Downloader], ready to use immediately — there is no separate `start()`.
 */
public fun createDownloader(
    storage: DownloaderStorage,
    backgroundDownloader: BackgroundResourceDownloader,
    groups: List<ResourceGroup>,
    stateStore: DownloadStateStore,
    notifier: DownloadNotifier = DownloadNotifier.NoOp,
    bundledResourcesPresent: Boolean = false,
    dispatchers: DownloadDispatchers = DownloadDispatchers.Default,
    logger: Logger = NoopLogger,
): Downloader = DefaultDownloaderEngine(
    storage = storage,
    notifier = notifier,
    backgroundDownloader = backgroundDownloader,
    stateStore = stateStore,
    bundledResourcesPresent = bundledResourcesPresent,
    groups = groups,
    dispatchers = dispatchers,
    logger = logger,
)
