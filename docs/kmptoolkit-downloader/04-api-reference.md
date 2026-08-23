# API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.downloader` and
`io.github.jamal_wia.kmptoolkit.downloader.spi`.

## `Downloader`

The interface consumers depend on.

| Member | Signature | Notes |
|---|---|---|
| `isAvailable` | `fun isAvailable(group: ResourceGroup): Boolean` | Synchronous, no I/O wait. Honours `bundledResourcesPresent`. |
| `isAvailable` | `fun isAvailable(unit: DownloadUnit): Boolean` | Never consults the bundled bypass. |
| `pathOf` | `fun pathOf(unit: DownloadUnit): String` | Valid only while `isAvailable(unit)` holds. |
| `downloadState` | `fun downloadState(group: ResourceGroup): StateFlow<GroupDownloadState>` | One flow per declared group. Throws `IllegalArgumentException` for an undeclared group. |
| `ensureAvailable` | `suspend fun ensureAvailable(group: ResourceGroup)` | Returns at once if already available; concurrent calls share one download. |
| `cancelDownload` | `fun cancelDownload(group: ResourceGroup)` | Safe from any thread. |
| `cancelAllDownloads` | `suspend fun cancelAllDownloads()` | Cancels every group- and unit-level download. The logout entry point. |
| `retryDownload` | `fun retryDownload(group: ResourceGroup)` | Non-suspend; skipped if the group's download is already managed. |
| `ensureAvailable` | `suspend fun ensureAvailable(unit: DownloadUnit)` | Concurrent calls for the SAME unit share one download. |
| `cancelDownload` | `fun cancelDownload(unit: DownloadUnit)` | Non-suspend; state write is dispatched onto the engine's own scope. |
| `hasActiveUnitDownload` | `suspend fun hasActiveUnitDownload(): Boolean` | True while any per-unit download is `Downloading`. |
| `unitDownloadStateFlow` | `fun unitDownloadStateFlow(unit: DownloadUnit): Flow<UnitDownloadState>` | First emission is seeded from disk (`Available` or `Idle`). |

**Suspension rule:** an operation whose outcome must be visible before the caller proceeds
suspends; one that only starts or records something does not.

**Failure contract, identical on both surfaces:** `ensureAvailable` throws
`DownloadCancelledException` on cancel and `DownloadFailedException` once retries are exhausted;
both also publish the same outcome to the matching state flow.

## `createDownloader`

```kotlin
fun createDownloader(
    storage: DownloaderStorage,
    backgroundDownloader: BackgroundResourceDownloader,
    groups: List<ResourceGroup>,
    stateStore: DownloadStateStore,
    notifier: DownloadNotifier = DownloadNotifier.NoOp,
    bundledResourcesPresent: Boolean = false,
    dispatchers: DownloadDispatchers = DownloadDispatchers.Default,
    logger: Logger = NoopLogger,
): Downloader
```

Only `storage`, `backgroundDownloader`, `groups` and `stateStore` have no sensible default. Returns
a `Downloader` ready to use immediately — there is no separate `start()`.

## `DownloadUnit`

| Member | Type | Notes |
|---|---|---|
| `id` | `String` | Durable identity — persisted, used as a background-session id. |
| `apiPath` | `String` | Opaque; resolved by your `DownloadUrlResolver`. |
| `relativePath` | `String` | Relative to the storage's own base directory. |
| `tempExtension` | `String` | Default `"tmp"`. Cosmetic — the temp name is derived from `id`. |
| `format` | `ResourceFormat` | Default `Opaque`. |
| `isDirectoryResource` | `Boolean` | Derived: `true` iff `format is ZipArchive`. |
| `group` | `ResourceGroup` | Which group's notification and progress this unit is filed under. |

## `ResourceFormat`

`sealed interface`: `Opaque`, `ZipArchive(availabilityMarker: String)`,
`SqliteDatabase(rowCountTable: String? = null, declaredRowCountMetaKey: String? = null)`. See
[`03-guide.md`](03-guide.md) for what commit does for each.

## `ResourceGroup`

| Member | Type | Notes |
|---|---|---|
| `key` | `String` | Stable identity — notification tag, action payload. |
| `units` | `List<DownloadUnit>` | May be empty for a group that only titles per-unit notifications. |

## `DownloadError`

`sealed class`: `NoConnection`, `Timeout`, `NotFound`, `Unauthorized`, `Server(statusCode: Int?)`,
`Storage(message: String? = null)`, `Unknown(message: String? = null)`.

## `GroupDownloadState` / `UnitDownloadState`

| `GroupDownloadState` | | `UnitDownloadState` | |
|---|---|---|---|
| `Idle` | | `Idle` | not on disk, nobody has asked |
| `Downloading(progress: Float)` | | `Available` | on disk, nothing transferred this process |
| `Completed` | | `Downloading(progress: Float)` | |
| `Error(error: DownloadError)` | | `Completed` | finished downloading THIS process |
| | | `Error(error: DownloadError)` | |

Neither carries a `group` / `unit` field — the flow you collected is the identity.

## `DownloadCancelledException` / `DownloadFailedException`

| Type | Members |
|---|---|
| `DownloadCancelledException` | `group: ResourceGroup`, `unit: DownloadUnit?` (null on the group surface) |
| `DownloadFailedException` | `unit: DownloadUnit`, `error: DownloadError`, `cause: Throwable?` |

## `BackgroundResourceDownloader` — **the SPI you implement**

| Member | Signature |
|---|---|
| `enqueueDownload` | `fun enqueueDownload(unit: DownloadUnit)` |
| `observeProgress` | `fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent>` |
| `isDownloadInProgress` | `fun isDownloadInProgress(unit: DownloadUnit): Boolean` |
| `cancelDownload` | `fun cancelDownload(unit: DownloadUnit)` |

`BackgroundDownloadEvent`: `Progress(unit, fraction: Float)`, and the terminals
`FileReady(unit)`, `Error(unit, message: String)`, `Cancelled(unit)`. See
[`07-background-downloader.md`](07-background-downloader.md).

## `DownloaderStorage` — shipped for you

| Member | Signature |
|---|---|
| `isResourceAvailable` | `fun isResourceAvailable(unit: DownloadUnit): Boolean` |
| `getResourcePath` | `fun getResourcePath(unit: DownloadUnit): String` |
| `getTempFilePath` | `fun getTempFilePath(unit: DownloadUnit): String` |
| `isTempFileAvailable` | `fun isTempFileAvailable(unit: DownloadUnit): Boolean` |
| `getTempFileSize` | `fun getTempFileSize(unit: DownloadUnit): Long` |
| `deleteTempFile` | `fun deleteTempFile(unit: DownloadUnit)` |
| `commitResource` | `suspend fun commitResource(unit: DownloadUnit)` |
| `getResourceSize` | `fun getResourceSize(unit: DownloadUnit): Long` |
| `deleteResource` | `fun deleteResource(unit: DownloadUnit)` |

`createDownloaderStorage(context, config = DownloaderStorageConfig(), logger = NoopLogger)` on
Android; `createDownloaderStorage(config = DownloaderStorageConfig(), logger = NoopLogger)` on iOS.

`DownloaderStorageConfig(baseDirectoryName: String = "kmptoolkit_downloader")` — the one thing
about the shipped storage you might need to change, so two libraries sharing a process never
collide.

## `spi` package

| Port | Shape | Default |
|---|---|---|
| `DownloadUrlResolver` | `fun interface { suspend fun resolve(unit: DownloadUnit): String }` | none |
| `DownloadNotifier` | `showProgress` / `showCompleted` / `showError` (suspend), `remove` (not) | `DownloadNotifier.NoOp` |
| `DownloadStateStore` | `readInt` / `writeInt` / `remove` | none — see [`03-guide.md`](03-guide.md) |
| `DownloadDispatchers` | `val io`, `val default: CoroutineDispatcher` | `DownloadDispatchers.Default` |

## `DownloaderRegistry`

| Member | Signature |
|---|---|
| `current` | `val current: StateFlow<Downloader?>` |
| `register` | `fun register(downloader: Downloader)` |
| `await` | `suspend fun await(timeout: Duration = 10.seconds): Downloader?` |
| `clear` | `fun clear()` |
