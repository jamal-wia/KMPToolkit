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
- **You declare a transfer finished: `storage.markTempFileComplete(unit)`.** Call it when the last
  byte is written and flushed, before you emit `FileReady` or commit on your own. It is the only
  thing that makes a temp file `Complete`, and only a complete file is ever committed without a
  transfer — a file that merely exists, however large, is `Partial` and is resumed. The engine marks
  the file itself when it observes `FileReady`, so a downloader that forgets still works while the
  app is watching; what it loses is recovery after process death, where nobody observed the event
  and the file is resumed instead of committed.
- **You begin a transfer's hash record: `storage.beginTempFile(unit, expectedSha256)`.** Call it
  when you are about to write from byte 0 — see the decision table below. Without it a partial still
  resumes and commits, but only `DownloadUnit.sha256` is checked.
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
finishes, flush and close the file, call `storage.markTempFileComplete(unit)`, then emit
`BackgroundDownloadEvent.FileReady(unit)` — the engine calls
`DownloaderStorage.commitResource(unit)` itself; your downloader does not need to.

**Check the length before you mark.** You are the one party that saw the response, so you are the
one that can tell a whole file from a connection that closed early: the total from `Content-Length`
(plus `resumeFrom`, on a `206`) or from `Content-Range`'s `/<total>` must equal the file's size.
On a mismatch, emit `Error` and leave the file partial — the engine's retry resumes it. Two answers
on a resume need care:

- **`416 Range Not Satisfiable`** to `Range: bytes=<size>-` usually means the file was already
  whole — a transfer that finished while nothing marked it, such as one left by a version of this
  library before 2.0.0. If `Content-Range: bytes */<total>` equals the file's size, mark it
  complete and emit `FileReady`; otherwise delete it and start over.
- **`200` instead of `206`** means the server ignored the range and is sending the whole file:
  truncate the temp file and write from zero, or the result is two files glued together.

If the remote file can change between an interrupted transfer and its resume, send `If-Range` with
the first response's `ETag`. The hash is the backstop: it is recorded when the transfer began, so a
file stitched from two versions fails at commit against the hash of the first one. A constant
`DownloadUnit.sha256` would be wrong for an object replaced in place — its hash changes with every
replacement — so take the hash from the resolve response instead (below). Check that your origin
honours `If-Range`; one that does not may still honour `If-Match` on a `Range` request, answering
`412` — treat that as a restart with a fresh resolve.

Resolve the URL to fetch through `DownloadUrlResolver.resolve(unit)` on every attempt — never
cache the result, since a signed URL is typically short-lived. It returns a `ResolvedDownload`
whose `expectedSha256` describes exactly the object behind that URL.

### When to call `beginTempFile`

Call `storage.beginTempFile(unit, resolved.expectedSha256)` exactly when you are about to write the
temp file from byte zero, by the party that owns the writer:

| Situation | Call `beginTempFile`? |
|---|---|
| Fresh start: no temp file | Yes, with the hash of this resolve |
| `200` in reply to a `Range` request (range ignored, whole body follows) | Yes — you are restarting from zero; use the hash of the response you are now reading |
| `416` whose total does not match the partial file | Yes, before restarting from zero |
| `206` resume | No — the bytes continue an object whose hash was recorded when it began; a hash first seen on a resume is not filled in |
| Joining a transfer that is already running | No |
| iOS: reconnecting to a session on relaunch, or resuming from `resumeData` | No |
| iOS: `didFinishDownloadingTo` | No — the record was written when the task was created |

It replaces any partial or complete file, leaving the unit in `TempFileState.None`, and it throws
`IllegalArgumentException` before touching anything if `DownloadUnit.sha256` and the expected hash
are both set and differ. On iOS, call it right after the resolve that produces the URL of a fresh
`NSURLSessionDownloadTask`, before the task is resumed.

## A worked skeleton (Android)

The shape most Android implementations converge on: a foreground service so the OS does not kill
the process mid-transfer, streaming with a plain HTTP client, 1% progress throttling so the engine
is not flooded, and marking the temp file complete on success so a transfer that finishes while the
UI process is dead is committed — not resumed — on the next `ensureAvailable`.

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
        //   val resolved = urlResolver.resolve(unit)          // ResolvedDownload(url, expectedSha256)
        //   val resumeFrom = storage.getTempFileSize(unit)
        //   if (resumeFrom == 0L) storage.beginTempFile(unit, resolved.expectedSha256)
        //   stream resolved.url (Range: bytes=$resumeFrom-) into storage.getTempFilePath(unit),
        //     append mode; on a 200 reply to a Range, or a 416 with a different total, call
        //     storage.beginTempFile(unit, resolved.expectedSha256) again and write from zero
        //   emit Progress(unit, fraction) as bytes arrive, throttled
        //   on success: check the size against Content-Length / Content-Range, then
        //     storage.markTempFileComplete(unit)
        //     events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
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
  the OS, check `storage.tempFileState(unit)` / `storage.getTempFileSize(unit)` before
  re-enqueuing — a resumable transfer that instead starts from zero every relaunch defeats half the
  point of this module, and a `Complete` file needs no transfer at all.

## A worked skeleton (iOS)

The shape most iOS implementations converge on: one background `NSURLSession` per unit, whose
session identifier carries the unit's `id` so a relaunch can reconnect to it, and a delegate that
copies the OS's own completed-download temp file to the path `DownloaderStorage.getTempFilePath`
names and then marks it complete. The task is created from a `ResolvedDownload`, and
`storage.beginTempFile(unit, resolved.expectedSha256)` is called once, when a fresh task is created
— not on reconnect, not for `resumeData`, not in the delegate.

```swift
final class MyBackgroundResourceDownloader {
    // Session identifier: "<bundleId>.download.\(unit.id)" — carries the unit id so
    // application(_:handleEventsForBackgroundURLSession:completionHandler:) can find the right
    // session again after relaunch and replay its outcome as the matching BackgroundDownloadEvent.
}
```

Kotlin/Native interop for the `URLSessionDownloadDelegate` side is the same pattern as any
Kotlin/Native + `NSURLSession` bridge; nothing about it is specific to this module beyond emitting
`BackgroundDownloadEvent`s from the delegate callbacks and, in `didFinishDownloadingTo`, copying
the delegate's own temp file to `storage.getTempFilePath(unit)` and calling
`storage.markTempFileComplete(unit)` before that callback returns — the OS deletes its file as soon
as it does. Check the response's status code there too: a `URLSession` download task "finishes"
with an error page's body just as happily as with the resource.

## Prove it, informally

There is no shipped `BackgroundResourceDownloaderContract` — see
[`06-testing.md`](06-testing.md) for why. The properties worth testing yourself:

- Enqueuing a unit already in flight does not start a second transfer.
- `observeProgress` for a unit with no activity emits nothing (the engine's own stall timeout is
  what turns silence into an `Error`, not this port).
- Exactly one `Terminal` event per attempt — never zero, never two.
- `cancelDownload` does not touch the temp file.
- A transfer cut short — a closed connection, a short body — leaves the temp file `Partial` and
  emits `Error`; only a whole file is marked complete, and always before `FileReady`.
