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
- **`enqueueDownload` does not throw.** Report a failure to begin — including a platform refusal
  such as a foreground service that may not start from the background — as an
  `Error` event. A throw ends the call as a port failure (`DownloadFailedException` with the cause)
  instead of going through the retry and classification path.
- **`observeProgress` must survive process death.** On relaunch, an implementation reconnects to
  whatever the OS kept running and replays its outcome — this is what lets `ensureAvailable`, called
  again after a restart, pick up a transfer that finished while the process was dead.
  `BackgroundDownloadEvent.Terminal` — `FileReady`, `Error`, `Cancelled` — must arrive **exactly
  once per attempt**, and the flow must not complete before it does (see "How the engine uses your
  flow" below for what replay may and may not do).
- **No pause/resume — only cancel and re-enqueue.** Resumption via a ranged request is legitimate on
  a re-enqueue (see the storage temp-file offset below), but there is no explicit pause operation.
- **`cancelDownload` does not delete the temp file.** The engine decides that — deleting it here too
  would race a caller reading `getTempFileSize` to compute a resume offset. When it returns,
  `isDownloadInProgress(unit)` must be `false` and the cancelled transfer's `Cancelled` must not be
  replayed to a later subscriber: a request made straight after a cancel needs a clean slate, and
  the engine cannot tell a leftover from news.
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
- **`Error.message` is raw text, not a classified error.** The engine's own classifier turns it into
  a `DownloadError` in one place; do not pre-classify on your side, but do phrase it the way the
  classifier reads it (next section).

## How the engine uses your flow

For every attempt the engine **subscribes to `observeProgress(unit)` first** and only then calls
`enqueueDownload(unit)`. An event you emit while enqueueing — a file that turns out to be whole, a
failure to begin — therefore reaches it even through a `MutableSharedFlow` without replay. The
sequence, per attempt:

1. subscribe (undispatched, into a buffered channel);
2. **drop everything the subscription has delivered so far** — with nothing suspended between this
   drain and the next step, it can only be a replay of an earlier attempt;
3. `enqueueDownload(unit)`;
4. wait for the first `Terminal` event. The stall timer is armed as this wait begins, before any
   event can arrive, and every event on the way restarts it.

When the engine instead *attaches* to a transfer that `isDownloadInProgress` reports as running, it
does not enqueue and keeps what the subscription replays — that replay is how an attach hears an
outcome that arrived just before it subscribed. It then re-checks `isDownloadInProgress`: a
transfer that ended in between is committed if its temp file is complete and started again
otherwise.

### Replay rules for the implementation

- **Replay `0` is the simplest correct shape**: a plain hot flow such as `MutableSharedFlow`
  (`extraBufferCapacity` for `tryEmit`) filtered by unit id, as in the Android skeleton below.
- **Replay `1` is fine too** (a `SharedFlow(replay = 1)`, a per-unit `StateFlow` of the last event):
  what it delivers before the enqueue is dropped. But once `enqueueDownload` has been called, a
  terminal event of an *earlier* transfer must never be delivered again — clear the replay cache
  as part of `enqueueDownload`, before the new transfer can emit: `resetReplayCache()` on a
  *per-unit* `SharedFlow` (on one flow shared by all units it would clear every unit's replay), or
  set a per-unit state back to a non-terminal value. The engine cannot tell a stale `Error` from
  this attempt's. The same goes for a stale `Cancelled`: it would be taken for a user cancel and
  cost the partial. A `StateFlow` has one more limit: it **conflates equal values**, so it cannot
  carry the iOS keep-alive (below), which relies on a repeated, equal `Progress` being delivered.
- **A flow that shifts the subscription to another coroutine registers late.** `flowOn`,
  `buffer()`, `callbackFlow` and `channelFlow` start their upstream after `collect` has returned
  control, so the subscription may not be registered when `enqueueDownload` runs, and a
  replay-less source then loses what it emits in between. Either keep the flow plain, or make sure
  the registration is complete before `enqueueDownload` returns, or replay the current transfer's
  outcome.
- **A delayed replay must name its transfer.** A flow built on Room or `WorkManager`'s
  `getWorkInfosFlow` replays the stored state some time after the subscription, possibly after the
  enqueue — the engine's drain cannot catch it. Give each `enqueueDownload` a generation (a counter,
  or the work request id), carry it in the stored row, and have the flow deliver a terminal event
  only when its generation is the current one for the unit.
- **The flow must not complete on a leftover.** A flow that ends on the terminal event of an
  earlier transfer (a `StateFlow` with `transformWhile` that stops at the stored outcome, say) is
  tolerated — the engine subscribes again right after the enqueue — but a flow that completes with
  *no* terminal event seen is a bug in the port: the unit fails at once as `DownloadError.Unknown`
  ("completed without a terminal event"), not after a stall timeout. The same goes for a flow that
  throws.

### What `Error.message` must say

The classifier reads the HTTP status **first**, and only when the message *states* one:
`HTTP <code>` (also `HTTP/1.1 <code>`, `HTTP error: <code>`) or `status <code>` (also
`status code: <code>`). Write the status in one of those shapes whenever the transfer got an HTTP
answer, for instance `HTTP 503: Service Unavailable`.

| Stated status | `DownloadError` | Engine |
|---|---|---|
| `404`, `410` | `NotFound` | not retried; temp file deleted |
| `401`, `403` | `Unauthorized` | not retried; temp file kept |
| `5xx` | `Server(code)` | retried (group surface); temp file kept |
| any other (`416`, `429`, …) | `Unknown(raw message)` | retried (group surface); temp file kept |

"Retried" holds for `ensureAvailable(group)` only. `ensureAvailable(unit)` makes **one** attempt
and reports the failure at once as a `DownloadFailedException`; the partial is kept or deleted by
the same rule, so the next call resumes it.

A status beats words: `HTTP 503: upstream said Forbidden` is a `Server(503)` and is retried. Only a
message with **no** stated status is read by its words: `unauthorized` / `forbidden` is
`Unauthorized`, `timeout` / `timed out` is `Timeout`, `network` / `no internet` / `unable to
resolve` / `no address` is `NoConnection`, everything else is `Unknown`. A bare number anywhere
else — an id, a URL, a byte count — is never taken for a status, and the words `not found` alone
no longer mean `NotFound`: state `HTTP 404`. The engine's own stall never goes through the text; it
is a `Timeout` directly.

**Build the message yourself; do not pass a client's exception text through for an HTTP failure.**
A client's own wording rarely has the shape above: `HttpURLConnection`'s `Server returned HTTP
response code: 403 for URL: …` is **not** matched (the word `response` sits between `HTTP` and the
digits), so it would be `Unknown` and retried instead of `Unauthorized`; its 404 arrives as a
`FileNotFoundException` whose message is just the URL, which is then read by its words (a URL that
contains `network` becomes `NoConnection`). Write `"HTTP ${connection.responseCode}: …"` from the
response code you hold.

### What happens to the partial file

The engine **keeps** the partial temp file when a transfer fails — a lost connection, a timeout, a
5xx, a rejected request (`Unauthorized`, a `416`, any unclassified error) — so the next attempt or
`ensureAvailable` resumes it with a `Range` request. It **deletes** it in exactly these cases: the
user cancels (`cancelDownload`, either overload, any file state); the bytes fail their integrity
check at commit; the failure is `NotFound`; and a `Cancelled` event that reaches it. Emit
`Cancelled` **only** as the answer to `cancelDownload`: the engine takes any `Cancelled` for a user
cancel, deletes the temp file and the hash record and ends the call with
`DownloadCancelledException`. A transfer that stops for another reason — the system cancelled the
task, the user force-quit the app (`NSURLErrorCancelled`, -999, on iOS) — is an `Error`, and its
partial (on iOS, its `resumeData`, from `NSURLSessionDownloadTaskResumeData` in the error's
`userInfo`) is kept. A complete file
whose commit failed for another reason (a full disk) is kept and committed by the next call
without a transfer.

The engine has **no rule for a `416`**: it is an `Unknown` failure, the partial stays, and the
retry would send the same `Range` and get the same `416`. That is why the `416` is yours to resolve
inside the downloader (see "Check the length before you mark"): resolve it to "already whole" or
restart from byte zero yourself. A `416` you emit as an `Error` instead is tried three times on the group surface (once on the
unit surface) and then leaves the partial in place until a cancel deletes it.

### Silence, stalls and the stall timer

The engine arms a stall timer **when it subscribes** (before the enqueue), and every event of the
attempt restarts it, a repeated `Progress` included. If it runs out (5 minutes by default), the
engine cancels the transfer through `cancelDownload`, handles the attempt as a `Timeout` failure
and keeps the partial. So a transfer that never emits anything stalls too, and a transport that is
legitimately quiet must say so:

- the host can pass `DownloaderConfig(stallTimeout = …)` to `createDownloader`;
- `Duration.INFINITE` turns stall detection off. The engine then waits for a terminal event for
  as long as it takes, so the transport is the only bound: on Android the transport's own socket
  timeouts, on iOS only the background session's `timeoutIntervalForResource`, 7 days by default.
  A flow that never terminates holds the `ensureAvailable` call until then.

A repeated value keeps the timer alive but is not progress: the retry budget of the group surface
(three failures in a row without moving past the earlier high-water mark) is unaffected by it.

## Where to write bytes

You do not need direct access to the concrete storage implementation — `DownloaderStorage` already
gives you everything:

```kotlin
val tempPath: String = storage.getTempFilePath(unit)   // where to write
val resumeFrom: Long = storage.getTempFileSize(unit)    // 0 if nothing survived
```

Always take `tempPath` from `getTempFilePath(unit)` and never build or cache it: for a unit whose id is
not a plain file name it is under a subdirectory (`tmp/kmptoolkit-internal/`) and it moves when an app
update migrates the 3.0.0 files, so a path stored in a persisted job (for example WorkManager input
data) can point at a file that is no longer there. Read it again at the start of every attempt.

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
  complete and emit `FileReady`. Otherwise — a different total, or no `Content-Range` at all —
  restart from byte zero **yourself**: `storage.beginTempFile(unit, resolved.expectedSha256)` and
  write the whole body. Do not report it as an `Error` instead: the engine has no `416` rule, would
  retry the same `Range` and leave the partial in place until a cancel.
- **`200` instead of `206`** means the server ignored the range and is sending the whole file:
  truncate the temp file and write from zero, or the result is two files glued together.

If the remote file can change between an interrupted transfer and its resume, send `If-Range` with
the first response's `ETag`. Storage has no slot for that `ETag` yet (only the hash is recorded), so
on Android an `If-Range` validator has to be kept by your own downloader next to the temp file, and
without one a resume cannot tell a changed object from an unchanged one. The hash is the backstop: it is recorded when the transfer began, so a
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
| `200` in reply to a `Range` request (range ignored, whole body follows) | Yes — you are restarting from zero; use the hash of this attempt's resolve |
| `412` in reply to `If-Match` on a `Range` request (the object changed) | Yes, after a fresh resolve, with that resolve's hash |
| `416` whose total does not match the partial file | Yes, before restarting from zero |
| `206` resume | No — the bytes continue an object whose hash was recorded when it began; a hash first seen on a resume is not filled in |
| Joining a transfer that is already running | No |
| iOS: starting a new task | Yes, with the fresh resolve's hash, before calling `resume()` to start it |
| iOS: starting a task from `resumeData` | Yes, with the hash saved alongside the `resumeData` (the hash of the response that began it) |
| iOS: reconnecting to a task that is still running after a relaunch | No |
| iOS: `didFinishDownloadingTo` | No — the record was written before the task was started |

It replaces any partial or complete file, leaving the unit in `TempFileState.None`, and it throws
`IllegalArgumentException` before touching anything if `DownloadUnit.sha256` and the expected hash
are both set and differ (or its getter throws), and `IllegalStateException` when the old temp file
or the record cannot be replaced. Treat both as a failed attempt: emit `Error` and do not write a
byte — the engine retries with a re-resolve, and a persistent catalogue error ends as an `Unknown`
failure. It does blocking file I/O including a flush to disk, so call it off the main thread.

The temp path holds no bytes while an iOS task runs, so beginning before every task is harmless.
It is also required, because the engine calls `deleteTempFile` on a user cancel, a `NotFound`
failure and an integrity failure, which drops the record: a task resumed afterwards from saved
`resumeData` without a `beginTempFile` would commit with the record missing and its backend hash
skipped. A downloader that does not keep the hash next to the `resumeData` must discard that
`resumeData` when it cannot be sure the record survived.

Stop writing as soon as `cancelDownload` is called. The engine may delete the temp file and its
record right after; a write that lands after that leaves bytes with no record, which would later
resume or commit with only `DownloadUnit.sha256` checked.

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

    // A plain hot flow, no replay: the engine subscribes before it enqueues, so nothing is lost,
    // and there is no leftover terminal event to deliver to a later attempt.
    private val events = MutableSharedFlow<BackgroundDownloadEvent>(replay = 0, extraBufferCapacity = 64)

    // The service/worker thread, the engine and cancelDownload all touch this set: keep it in one
    // atomic cell. A plain mutableSetOf() here is a data race.
    private val inProgress = MutableStateFlow<Set<String>>(emptySet())

    override fun enqueueDownload(unit: DownloadUnit) {
        var added = false
        inProgress.update { current ->
            added = unit.id !in current
            current + unit.id
        }
        if (!added) return // already running — join it
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
        //     finish(BackgroundDownloadEvent.FileReady(unit))
        //   on failure, with the HTTP status in the text when there was one:
        //     finish(BackgroundDownloadEvent.Error(unit, "HTTP 503: ${e.message}"))
    }

    // Emit first, then drop the id: a subscriber that sees the terminal event can still ask
    // isDownloadInProgress and must not be told "gone" before it has the outcome. Either order
    // is safe for the engine; this one never reports a finished transfer as vanished.
    private fun finish(terminal: BackgroundDownloadEvent.Terminal) {
        events.tryEmit(terminal)
        inProgress.update { it - terminal.unit.id }
    }

    override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
        events.filter { it.unit.id == unit.id }

    override fun isDownloadInProgress(unit: DownloadUnit): Boolean = unit.id in inProgress.value

    override fun cancelDownload(unit: DownloadUnit) {
        // Stop your service/worker for unit.id; do NOT delete the temp file here.
        // The id goes first: once this returns isDownloadInProgress must be false. The Cancelled
        // event is not replayed (replay = 0), so a request made right after starts clean.
        inProgress.update { it - unit.id }
        events.tryEmit(BackgroundDownloadEvent.Cancelled(unit))
    }
}
```

A transfer that fails with an HTTP status says so in its message (`HTTP 503: …`); the engine reads
that status before any wording, see "What `Error.message` must say".

Three things worth planning for up front, the first two learned the hard way in the codebase this module
draws from:

- **A general-purpose HTTP client's buffering can exhaust the heap** on a large transfer once the
  app has been backgrounded and its memory budget shrinks. Streaming directly with a low-level
  connection API, writing each chunk straight to disk, avoids holding the whole response in memory.
- **Reconnect on relaunch, don't restart.** If your foreground service is killed and restarted by
  the OS, check `storage.tempFileState(unit)` / `storage.getTempFileSize(unit)` before
  re-enqueuing — a resumable transfer that instead starts from zero every relaunch defeats half the
  point of this module, and a `Complete` file needs no transfer at all.
- **Android 15 caps a `dataSync` foreground service at 6 hours** in any 24-hour window, for apps
  that **target** Android 15 (API 35) or higher. The system then calls the service's
  `onTimeout(startId, fgsType)`, which has to stop the service within a few seconds or the app
  crashes, and a new `dataSync` service cannot be started from the background until the user
  brings the app to the foreground. Handle it as a failed attempt: stop writing, flush, and emit an
  `Error` (for example `"Foreground service time limit reached"`). The engine keeps the partial.

  Mind what the engine does next. That text classifies as `Unknown`, so on the group surface the
  engine re-enqueues after 2 s and again after 4 s, and each re-enqueue tries to start a `dataSync`
  service from the background, which Android refuses (`ForegroundServiceStartNotAllowedException`).
  `enqueueDownload` must catch that and emit an `Error` rather than throw (see the contract list).
  The call then fails after three attempts, and the partial waits for an `ensureAvailable` made
  after the user brings the app to the foreground; a call made earlier fails the same way.

  A transfer that routinely needs more than 6 hours cannot be saved by a `WorkManager` worker: one
  that runs long does so through `setForeground`, which is the same foreground service, of type
  `dataSync` for a download, under the same limit (a plain worker is stopped after about ten
  minutes). The alternative is a user-initiated data transfer job (`JobInfo.Builder.setUserInitiated`,
  API 34+), which needs a user action to start it and the `RUN_USER_INITIATED_JOBS` permission. As
  with every permission, document it for your consumers; this library does not declare it.

## A worked skeleton (iOS)

The shape most iOS implementations converge on: one background `NSURLSession` per unit, whose
session identifier carries the unit's `id` so a relaunch can reconnect to it, and a delegate that
copies the OS's own completed-download temp file to the path `DownloaderStorage.getTempFilePath`
names and then marks it complete. The task is created from a `ResolvedDownload`, and
`storage.beginTempFile(unit, expectedSha256)` is called before every task is started: with the
fresh resolve's hash for a new task, and with the hash saved alongside the `resumeData` when
resuming from it. Never in the delegate's `didFinishDownloadingTo`, and never when merely
reconnecting to a task that is still running.

```swift
final class MyBackgroundResourceDownloader {
    // Session identifier: "<bundleId>.download.\(unit.id)" — carries the unit id so
    // application(_:handleEventsForBackgroundURLSession:completionHandler:) can find the right
    // session again after relaunch and replay its outcome as the matching BackgroundDownloadEvent.
}
```

### iOS: silence, keep-alive and `resumeData`

**Background sessions always wait for connectivity.** A background `NSURLSession` ignores
`waitsForConnectivity = false`: with no network the task sits in the session, and the delegate
hears nothing — no progress, no error — until a connection returns or `timeoutIntervalForResource`
(7 days by default) expires. To the engine that is silence, and the stall timer (5 minutes by
default, armed at subscription) would cancel a perfectly healthy task. There are two ways to
handle it:

- **Keep-alive (recommended).** While the system still lists the task
  (`getTasksWithCompletionHandler`), re-emit the unit's *current* `Progress` about every 60
  seconds — the last known fraction, `0` included when nothing has arrived yet.

  ```kotlin
  // In your downloader, one coroutine per running unit; stops when the task finishes or is
  // cancelled. Keep the last fraction per unit: two units can be in flight at once, and one
  // unit's fraction emitted as another's would push the engine's progress mark past what really
  // happened. The map is written on the URLSession delegate queue and read here, so update it
  // atomically (the same pattern as the thread-safe sample above).
  private val lastFractions = MutableStateFlow<Map<String, Float>>(emptyMap())

  // In the delegate callback: lastFractions.update { it + (unit.id to fraction) }
  // When the task ends:       lastFractions.update { it - unit.id }

  while (taskIsStillListedBySystem(unit)) {
      events.tryEmit(
          BackgroundDownloadEvent.Progress(unit, lastFractions.value[unit.id] ?: 0f),
      )
      delay(60.seconds)
  }
  ```

  The flow must **deliver equal values**: a `MutableSharedFlow` does, a `StateFlow` does not (it
  conflates a repeat of the same `Progress`, the timer is never restarted, and the engine cancels a
  healthy task after the stall timeout). On a `StateFlow`-backed port, merge a separate keep-alive
  `SharedFlow` into `observeProgress`, or make every keep-alive value distinct.

  A repeated value keeps the stall timer alive but is **not** progress: it does not touch the retry
  budget, and a transfer that repeats one value and then fails is still stopped after three
  attempts (on the group surface; the unit surface makes one).
- **`DownloaderConfig(stallTimeout = Duration.INFINITE)`.** No stall detection at all.

Both hold `ensureAvailable` for as long as the system holds the task, up to the 7-day
`timeoutIntervalForResource`; neither is a tighter bound. What the keep-alive adds is that a task
that vanished without a terminal event (the system no longer lists it) still stalls after
`stallTimeout` and fails, where `INFINITE` would wait for the full 7 days. Prefer the keep-alive
unless your app is routinely suspended for long periods: while it is suspended, the engine's stall
`delay` and the keep-alive `delay` are both frozen, so on resume both deadlines may have passed and
nothing orders the keep-alive's emit before the stall. Re-emit the current `Progress` as soon as
the app returns to the foreground, or choose `INFINITE` there.

**Cancel with resume data.** When `cancelDownload` is called — by the user, or by the engine after a
stall — cancel the task with `cancel(byProducingResumeData:)` and keep the data **together with the
hash** that was recorded for it (see the decision table). The next `enqueueDownload` starts a
download task from that `resumeData`, after `beginTempFile` with the saved hash. The partial lives
inside the OS, not under `getTempFilePath`, so the engine's "keep the partial after a failure" has
nothing to keep on iOS: what survives is your `resumeData`. Emit `Cancelled` only as the answer
to this call; a task that ends with `NSURLErrorCancelled` on its own (a force-quit, a system
cancel) is an `Error`, and keeps its `resumeData`. The port is not told *why* it is
cancelled: if a user cancel should start over from zero, your own user-facing cancel path discards
the `resumeData` as well. Discard it too after a `NotFound` failure, where the bytes can never be
completed.

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
  what turns silence into an `Error`, not this port) — unless you are on iOS and keep a task alive
  with a repeated `Progress`.
- Exactly one `Terminal` event per attempt — never zero, never two.
- A flow that completes never completes on the terminal event of an earlier transfer, and never
  completes with no terminal at all.
- After `enqueueDownload` for a unit, a subscriber never receives the terminal event of an earlier
  transfer (try a second `enqueueDownload` after a finished one, with a subscriber that was
  attached before and one attached after).
- After `cancelDownload`, `isDownloadInProgress` is `false` straight away and the old `Cancelled`
  is not replayed.
- Whatever the flow shape, the events of a transfer enqueued right after `observeProgress` was
  collected are delivered (collect, then enqueue, then emit from another thread).
- An `Error` message that states an HTTP status in the `HTTP <code>` form.
- `cancelDownload` does not touch the temp file.
- A transfer cut short — a closed connection, a short body — leaves the temp file `Partial` and
  emits `Error`; only a whole file is marked complete, and always before `FileReady`.
