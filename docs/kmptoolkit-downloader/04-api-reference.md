# API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.downloader` and
`io.github.jamal_wia.kmptoolkit.downloader.spi`.

## `Downloader`

The interface consumers depend on.

| Member | Signature | Notes |
|---|---|---|
| `isAvailable` | `fun isAvailable(group: ResourceGroup): Boolean` | Synchronous, no I/O wait. Honours `bundledResourcesPresent`. |
| `isAvailable` | `fun isAvailable(unit: DownloadUnit): Boolean` | Never consults the bundled bypass. `false` for a unit whose `relativePath` the storage rejects. |
| `pathOf` | `fun pathOf(unit: DownloadUnit): String` | Valid only while `isAvailable(unit)` holds. Throws `InvalidDownloadUnitException` for a `relativePath` the storage rejects. |
| `downloadState` | `fun downloadState(group: ResourceGroup): StateFlow<GroupDownloadState>` | One flow per declared group. Throws `IllegalArgumentException` for an undeclared group. |
| `ensureAvailable` | `suspend fun ensureAvailable(group: ResourceGroup)` | Returns at once if already available; concurrent calls share one download. |
| `cancelDownload` | `fun cancelDownload(group: ResourceGroup)` | Safe from any thread. Also ends a running `ensureAvailable(group)` with `DownloadCancelledException`, unless its last commit has already succeeded: then the call completes and publishes `Completed`. A per-unit `ensureAvailable` running for one of the group's units is cancelled too. A request that is starting its transfer at that very instant stops it and returns to `Idle` at its next checkpoint, microseconds later; `cancelAllDownloads`, which can suspend, waits for that. |
| `cancelAllDownloads` | `suspend fun cancelAllDownloads()` | Cancels every group- and unit-level download, and ends every running `ensureAvailable` with `DownloadCancelledException` (also one a screen launched, outside the engine's scope), except a call whose last commit has already succeeded: that one completes and publishes `Completed`. It also waits for a request that is starting its transfer at that instant, so when it returns no call it cancelled shows `Downloading`, holds a transfer in the `BackgroundResourceDownloader` or has a start notification posted; do not call it blocking (`runBlocking`) from inside a port or notifier callback. The logout entry point. |
| `retryDownload` | `fun retryDownload(group: ResourceGroup)` | Non-suspend; skipped if the group's download is already managed. |
| `ensureAvailable` | `suspend fun ensureAvailable(unit: DownloadUnit)` | Concurrent calls for the SAME unit share one download. |
| `cancelDownload` | `fun cancelDownload(unit: DownloadUnit)` | Non-suspend and synchronous: the transfer is cancelled and the state is `Idle` on return. Also ends a running `ensureAvailable(unit)` with `DownloadCancelledException`, unless its commit has already succeeded: then the call completes and publishes `Completed`. Also deletes the unit's partial temp data and a leftover archive staging tree, and does so for a unit the engine never tracked, keeping only that unit's lock object, never a state flow. A request that is starting its transfer at that very instant stops it and returns to `Idle` at its next checkpoint, microseconds later; `cancelAllDownloads` waits for that. |
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

*Since 3.1.0* there is a second overload that takes a `DownloaderConfig`, placed right after
`stateStore` and required there, so a call written for the first overload — by position or by name —
can never resolve to it:

```kotlin
fun createDownloader(
    storage: DownloaderStorage,
    backgroundDownloader: BackgroundResourceDownloader,
    groups: List<ResourceGroup>,
    stateStore: DownloadStateStore,
    config: DownloaderConfig,
    notifier: DownloadNotifier = DownloadNotifier.NoOp,
    bundledResourcesPresent: Boolean = false,
    dispatchers: DownloadDispatchers = DownloadDispatchers.Default,
    logger: Logger = NoopLogger,
): Downloader
```

The first overload behaves exactly like the second with `DownloaderConfig()`.

## `DownloaderConfig` *(since 3.1.0)*

```kotlin
class DownloaderConfig(stallTimeout: Duration = DownloaderConfig.DEFAULT_STALL_TIMEOUT) {
    val stallTimeout: Duration
    companion object { val DEFAULT_STALL_TIMEOUT: Duration } // 5 minutes
}
```

A plain class (not a `data class`, so a later setting cannot break a constructor or `copy` you
compiled against) with value `equals` / `hashCode` / `toString`.

| Setting | Default | Notes |
|---|---|---|
| `stallTimeout` | `5.minutes` | How long the engine waits with **no event at all** from a transfer before it cancels it and handles the attempt as a `DownloadError.Timeout` failure. Every event restarts the timer — a repeated `Progress` included, which keeps a transfer the OS is legitimately holding alive without counting as progress. Must be positive, else `IllegalArgumentException`. `Duration.INFINITE` switches stall detection off and leaves it entirely to the transport; on iOS the only bound left is then `timeoutIntervalForResource` (7 days by default). |

## `DownloadUnit`

| Member | Type | Notes |
|---|---|---|
| `id` | `String` | Durable identity — persisted, used as a background-session id. Any string is accepted: an id that is not a plain file name (it contains `/` or NUL, or a derived name would pass 255 bytes) is stored under a hashed name, so it never collides with another unit or escapes `tmp/`. Ids must be unique ignoring case, and on iOS ignoring Unicode normalization too (an iOS volume may treat `Model` and `model`, or NFC and NFD spellings, as one file). Prefer `[A-Za-z0-9._-]`. |
| `apiPath` | `String` | Opaque; resolved by your `DownloadUrlResolver`. |
| `relativePath` | `String` | Relative to the storage's own base directory, used as written once `.`, `..` and empty segments are resolved lexically (`files/x/../a.bin` is `files/a.bin` whether or not `x` exists; a leading `/` still means inside the base). Rejected with `InvalidDownloadUnitException` when it is empty or normalizes to nothing, contains NUL, leaves the base directory, or names something the storage manages (`tmp`, `tmp/expect`, `tmp/expect-staged`, `tmp/kmptoolkit-internal`, `tmp/staging-…`, or the unit's own temp files). Any other path under `tmp/` (including `tmp/trash/…`, which 3.0.0 never used) is accepted, with a warning, and stays reserved for 4.0. |
| `tempExtension` | `String` | Default `"tmp"`. Cosmetic — the temp name is derived from `id`. |
| `format` | `ResourceFormat` | Default `Opaque`. |
| `sha256` | `Sha256?` | Default `null`. A constant expectation; checked at commit, and a mismatch downloads again. Do not set it for an object replaced in place — use `beginTempFile`. The engine reads it before a transfer and fails the unit with `DownloadError.Unknown` if the getter throws; storage reads it again at begin and commit. |
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
`Storage(message: String? = null)`, `Corrupted(message: String? = null)`,
`Unknown(message: String? = null)`. `Corrupted` is bytes that failed a check (`sha256`, the recorded
hash, a `SqliteDatabase`'s) again after one fresh download; nothing was committed. `Unknown` is also
the error for a unit whose `sha256` getter throws, reported before any transfer starts.

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

## `InvalidDownloadUnitException` *(since 3.1.0)*

`class InvalidDownloadUnitException(unit: DownloadUnit, reason: Reason) : IllegalArgumentException`,
with `enum class Reason { EMPTY, INVALID_CHARACTER, ESCAPES_BASE_DIRECTORY, RESERVED_INTERNAL_PATH }`.
A `DownloaderStorage` throws it from `getResourcePath`, `commitResource` and `deleteResource` when `unit.relativePath` can never be used safely: it is empty or
normalizes to nothing (`EMPTY`), contains NUL (`INVALID_CHARACTER`), leaves the base directory
(`ESCAPES_BASE_DIRECTORY`), or names something the storage manages itself, such as `tmp`,
`tmp/expect`, `tmp/staging-…` or the unit's own temp files, hashed or 3.0.0-named (`RESERVED_INTERNAL_PATH`). It has no
message (the library carries no user-facing text); `toString` names the unit id and the reason.

`isResourceAvailable` answers `false` and `getResourceSize` answers `0` for such a unit, and the temp-side
methods never look at `relativePath`, so `cancelDownload` of a bad unit still cleans up. Nothing outside
the base directory, and no storage-managed directory, is ever touched.

For the library's own storages (the ones the two factories return) the engine reports it before any
byte is fetched, as `DownloadFailedException` with `DownloadError.Unknown` whose `cause` is this
exception; no transfer is enqueued and the state is `Error`. A `DownloaderStorage` you wrote is not asked
before the transfer: the engine never calls its `getResourcePath` there, so it behaves as in 3.0.0 and
this exception reaches the host only from that storage's own `pathOf` / `commitResource` calls.
`DownloadError` has no subclass for it. `FakeDownloaderStorage` in `kmptoolkit-downloader-testing` does
not validate paths. A `DownloadUnit.id` is never rejected: an id
that cannot be a file name is stored under a hashed name ([`05-platform-notes.md`](05-platform-notes.md)).

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

## `Sha256`

`class Sha256` (final, private constructor): `val hex: String` — 64 lowercase hex digits.
`Sha256.parse(hex: String): Sha256` accepts either case and throws `IllegalArgumentException` for
anything that is not 64 hex digits — for constants you write yourself.
`Sha256.parseOrNull(hex: String?): Sha256?` answers `null` for `null`, blank and malformed input —
for a value from a backend, where an absent or garbled hash must never fail a download. Equality
follows the digest, whichever case it was parsed from.

## `ResolvedDownload`

`class ResolvedDownload(url: String, expectedSha256: Sha256? = null)` in the `spi` package. Final
class with `equals`/`hashCode`; `url` must not be blank. `expectedSha256` is the hash of exactly the
object behind `url` at resolve time. `toString` omits the URL's query and fragment.

## `DownloaderStorage` — shipped for you

| Member | Signature |
|---|---|
| `isResourceAvailable` | `fun isResourceAvailable(unit: DownloadUnit): Boolean` |
| `getResourcePath` | `fun getResourcePath(unit: DownloadUnit): String` |
| `getTempFilePath` | `fun getTempFilePath(unit: DownloadUnit): String` |
| `tempFileState` | `fun tempFileState(unit: DownloadUnit): TempFileState` |
| `getTempFileSize` | `fun getTempFileSize(unit: DownloadUnit): Long` |
| `beginTempFile` | `fun beginTempFile(unit: DownloadUnit, expectedSha256: Sha256?)` |
| `markTempFileComplete` | `fun markTempFileComplete(unit: DownloadUnit)` |
| `deleteTempFile` | `fun deleteTempFile(unit: DownloadUnit)` |
| `commitResource` | `suspend fun commitResource(unit: DownloadUnit)` |
| `getResourceSize` | `fun getResourceSize(unit: DownloadUnit): Long` |
| `deleteResource` | `fun deleteResource(unit: DownloadUnit)` |

`TempFileState`: `sealed interface` — `None` (no file, or an empty one), `Partial` (an unfinished
transfer, resumed from `getTempFileSize`), `Complete` (marked by `markTempFileComplete`; the only
state committed without a transfer). `markTempFileComplete` is atomic, idempotent once complete (also when
two callers mark the same file at once: the loser of the rename returns normally),
and throws when there is no file. `commitResource` throws when there is no complete file, and
throws `ResourceIntegrityException(message, cause)` — after deleting the temp file — when the bytes
fail a check, or when a `ZipArchive` whose extracted tree (after a single root directory is moved up)
lacks the marker fails the same way, before anything at the final path is touched, so a resource
already committed stays available; the engine answers that with one fresh download. All three resource methods that name a
path (`getResourcePath`, `commitResource`, `deleteResource`) throw `InvalidDownloadUnitException` for a
`relativePath` that is not usable. Commits of one unit are mutually
exclusive across every storage instance over the same base directory in the process, and a commit
that finds the resource already committed by a concurrent one (no complete file, resource
available) returns normally without touching anything. When the engine itself finds the resource already committed and a transfer of its own left a
complete temp file behind, it deletes that file instead of leaving it on disk.

`beginTempFile(unit, expectedSha256)` is called by the party that owns the writer, exactly when it
is about to write from byte 0, and replaces whatever transfer was there: afterwards `tempFileState`
is `None` and `getTempFileSize` is 0. It persists the expected hash (an explicit "none" marker when
`null`) at `tmp/expect/<id>.sha256`, written crash-safely before the old files are deleted. It throws
`IllegalArgumentException` before any side effect when `unit.sha256` and `expectedSha256` are both
set and differ, or when reading `unit.sha256` throws, and with `IllegalStateException` when the old
temp file or the record cannot be replaced. On iOS call it before every task, including one resumed
from `resumeData` (with the hash saved beside it). `commitResource` hashes the complete file once and checks every stated expectation;
`deleteTempFile` removes the data first and the record last, and keeps the record (logging a
warning, never throwing) when a data file could not be deleted; the shipped storages also remove the unit's archive extraction staging, unless a commit of the unit is running (then it is left for the next call). It is not enforced that a downloader
calls `beginTempFile` (a recordless partial still resumes); see
[`07-background-downloader.md`](07-background-downloader.md) for when to call it.

`createDownloaderStorage(context, config = DownloaderStorageConfig(), logger = NoopLogger)` on
Android; `createDownloaderStorage(config = DownloaderStorageConfig(), logger = NoopLogger)` on iOS.

`DownloaderStorageConfig(baseDirectoryName: String = "kmptoolkit_downloader")` — the one thing
about the shipped storage you might need to change, so two libraries sharing a process never
collide. The name must be one plain folder name: not blank, not `.` or `..`, and without a path
separator or NUL; anything else throws `IllegalArgumentException` from the constructor.

## `spi` package

| Port | Shape | Default |
|---|---|---|
| `DownloadUrlResolver` | `fun interface { suspend fun resolve(unit: DownloadUnit): ResolvedDownload }` | none |
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
