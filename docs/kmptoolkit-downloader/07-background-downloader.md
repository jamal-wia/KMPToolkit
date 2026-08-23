# Implementing a custom `BackgroundResourceDownloader`

`BackgroundResourceDownloader` is the one thing this module does not supply, and the reason it has
no HTTP client dependency. This page is everything you need to implement it correctly.

Read it if you have not built a downloader that survives the app being backgrounded before. If your
needs are modest — a small file, no requirement to keep transferring once the app is backgrounded —
a much simpler implementation than the platform patterns below is enough; the contract is the same
either way.

## The division of labour

The engine owns **all policy**: whether to retry, how progress is scaled across a group, when a
stall counts as a failure, when the result is committed. A `BackgroundResourceDownloader`
implementation owns **none** of it — it reports what happened and stops there. That split is what
lets the engine's retry and commit logic be written once, tested once, and never re-derived per
transfer implementation.

## The contract

```kotlin
interface BackgroundResourceDownloader {
    fun enqueueDownload(unit: DownloadUnit)
    fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent>
    fun isDownloadInProgress(unit: DownloadUnit): Boolean
    fun cancelDownload(unit: DownloadUnit)
}
```

- **`enqueueDownload` is idempotent.** Enqueuing a unit already in flight joins the running
  transfer rather than starting a second one.
- **`observeProgress` must survive process death.** On relaunch, an implementation reconnects to
  whatever the OS kept running and replays its outcome — this is what lets `ensureAvailable`, called
  again after a restart, pick up a transfer that finished while the process was dead.
  `BackgroundDownloadEvent.Terminal` — `FileReady`, `Error`, `Cancelled` — must arrive **exactly
  once per attempt**.
- **No pause/resume — only cancel and re-enqueue.** Resumption via a ranged request is legitimate on
  a re-enqueue (see the storage temp-file offset below), but there is no explicit pause operation.
- **`cancelDownload` does not delete the temp file.** The engine decides that — deleting it here too
  would race a caller reading `getTempFileSize` to compute a resume offset.
- **`Error.message` is raw text, not a classified error.** The engine's own keyword-matching
  classifier turns it into a `DownloadError` in one place; do not pre-classify on your side.

## Where to write bytes

You do not need direct access to the concrete storage implementation — `DownloaderStorage` already
gives you everything:

```kotlin
val tempPath: String = storage.getTempFilePath(unit)   // where to write
val resumeFrom: Long = storage.getTempFileSize(unit)    // 0 if nothing survived
```

Open an ordinary file handle at `tempPath` in append mode, request bytes starting at `resumeFrom`
if your transport supports a byte-range request, and stream what arrives. When the transfer
finishes, emit `BackgroundDownloadEvent.FileReady(unit)` — the engine calls
`DownloaderStorage.commitResource(unit)` itself; your downloader does not need to.

Resolve the URL to fetch through `DownloadUrlResolver.resolve(unit)` on every attempt — never
cache the result, since a signed URL is typically short-lived.

## A worked skeleton (Android)

The shape most Android implementations converge on: a foreground service so the OS does not kill
the process mid-transfer, streaming with a plain HTTP client, 1% progress throttling so the engine
is not flooded, and self-commit on success so a transfer that finishes while the UI process is dead
is not lost.

```kotlin
class MyBackgroundResourceDownloader(
    private val storage: DownloaderStorage,
    private val urlResolver: DownloadUrlResolver,
) : BackgroundResourceDownloader {

    private val events = MutableSharedFlow<BackgroundDownloadEvent>(replay = 0, extraBufferCapacity = 64)
    private val inProgress = mutableSetOf<String>()

    override fun enqueueDownload(unit: DownloadUnit) {
        if (!inProgress.add(unit.id)) return // already running — join it
        // Start your foreground service / worker here, passing unit.id. Inside it:
        //   val url = urlResolver.resolve(unit)
        //   val resumeFrom = storage.getTempFileSize(unit)
        //   stream `url` (Range: bytes=$resumeFrom-) into storage.getTempFilePath(unit), append mode
        //   emit Progress(unit, fraction) as bytes arrive, throttled
        //   on success: events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
        //   on failure: events.tryEmit(BackgroundDownloadEvent.Error(unit, e.message ?: "unknown"))
        //   either way: inProgress.remove(unit.id)
    }

    override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
        events.filter { it.unit.id == unit.id }

    override fun isDownloadInProgress(unit: DownloadUnit): Boolean = unit.id in inProgress

    override fun cancelDownload(unit: DownloadUnit) {
        // Stop your service/worker for unit.id; do NOT delete the temp file here.
        inProgress.remove(unit.id)
        events.tryEmit(BackgroundDownloadEvent.Cancelled(unit))
    }
}
```

Two things worth planning for up front, both learned the hard way in the codebase this module
draws from:

- **A general-purpose HTTP client's buffering can exhaust the heap** on a large transfer once the
  app has been backgrounded and its memory budget shrinks. Streaming directly with a low-level
  connection API, writing each chunk straight to disk, avoids holding the whole response in memory.
- **Reconnect on relaunch, don't restart.** If your foreground service is killed and restarted by
  the OS, check `storage.isTempFileAvailable(unit)` / `storage.getTempFileSize(unit)` before
  re-enqueuing — a resumable transfer that instead starts from zero every relaunch defeats half the
  point of this module.

## A worked skeleton (iOS)

The shape most iOS implementations converge on: one background `NSURLSession` per unit, whose
session identifier carries the unit's `id` so a relaunch can reconnect to it, and a delegate that
copies the OS's own completed-download temp file to the path `DownloaderStorage.getTempFilePath`
names.

```swift
final class MyBackgroundResourceDownloader {
    // Session identifier: "<bundleId>.download.\(unit.id)" — carries the unit id so
    // application(_:handleEventsForBackgroundURLSession:completionHandler:) can find the right
    // session again after relaunch and replay its outcome as the matching BackgroundDownloadEvent.
}
```

Kotlin/Native interop for the `URLSessionDownloadDelegate` side is the same pattern as any
Kotlin/Native + `NSURLSession` bridge; nothing about it is specific to this module beyond emitting
`BackgroundDownloadEvent`s from the delegate callbacks and copying the delegate's own temp file to
`storage.getTempFilePath(unit)` on `didFinishDownloadingTo`.

## Prove it, informally

There is no shipped `BackgroundResourceDownloaderContract` — see
[`06-testing.md`](06-testing.md) for why. The properties worth testing yourself:

- Enqueuing a unit already in flight does not start a second transfer.
- `observeProgress` for a unit with no activity emits nothing (the engine's own stall timeout is
  what turns silence into an `Error`, not this port).
- Exactly one `Terminal` event per attempt — never zero, never two.
- `cancelDownload` does not touch the temp file.
