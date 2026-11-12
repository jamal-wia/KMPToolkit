# Platform notes

The engine itself is identical on both platforms. The wake layer is not, and the difference is
large enough to affect what you can promise a user.

## Permissions

**This module's own `AndroidManifest.xml` declares nothing.** It does, however, depend on
`androidx.work` for the Android wake layer, and WorkManager's manifest merges four permissions into
every consuming app:

| Permission | Why WorkManager needs it |
|---|---|
| `android.permission.WAKE_LOCK` | Holds the CPU awake while a job runs |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Restores scheduled work after a reboot |
| `android.permission.FOREGROUND_SERVICE` | For work a consumer chooses to run in the foreground |
| `android.permission.ACCESS_NETWORK_STATE` | Reads connectivity to honor a `NetworkType` constraint |

All four are **install-time** permissions: they appear in your Play Store listing and app-info
screen, and they never prompt the user. None can be removed — stripping one with
`tools:node="remove"` trades a listed permission for a runtime failure on somebody's device.

That set is pinned by name in this module's `LibraryManifestTest`, so a WorkManager upgrade that
starts asking for a fifth fails the build here rather than arriving silently in your app.

Notably **absent**, and asserted absent:

- `INTERNET` — the engine itself never opens a socket. `UploaderUploadWorker` does, but only if you
  opt in by calling `createWorkManagerUploadTransport` — and even then this module declares nothing:
  `INTERNET` is a normal permission every networked app already carries in its own manifest, the
  same as a handler making its own HTTP calls always required.
- Every dangerous permission (camera, microphone, storage, contacts, location, notifications).
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` — the backoff alarm is a coroutine `delay`, not an
  `AlarmManager` alarm. If you need real exact-time scheduling, that is
  [`kmptoolkit-scheduler`](../kmptoolkit-scheduler/01-overview.md).

If you do not use `createWorkManagerWakeScheduler`, the four are still merged — a manifest merge is
transitive and does not know which classes you call. Excluding the dependency is possible but then
`UploaderDrainWorker` will not resolve; if that trade matters to you, say so and it can become its own
artifact.

iOS has no permission story here at all: `BGTaskScheduler` needs an `Info.plist` entry and a
background mode, not a permission, and never prompts.

## Android — WorkManager

```kotlin
val uploader: UploaderEngine = createUploaderEngine(
    store = store,
    handlers = handlers,
    scope = applicationScope,
    wakeScheduler = createWorkManagerWakeScheduler(
        context = context,
        config = WorkManagerWakeConfig(requiresNetwork = true),
        logger = logger,
    ),
).also { it.start() }

UploaderEngineRegistry.register(uploader)
```

**How it behaves.** A non-empty queue arms one unique `OneTimeWorkRequest`, network-gated by
default, with exponential backoff. `ExistingWorkPolicy.KEEP` makes the engine's frequent arming
cheap — during a burst it arms one per enqueue, and all but the first are no-ops. An empty queue
cancels the work.

**What the job does.** Nothing to the queue. `UploaderDrainWorker` waits for the registered engine and
calls `awaitDrained`, keeping the process alive while the engine works. Draining from the worker as
well would put two drains in one process and break single-flight. If the queue has not emptied within
`drainBudget`, it returns `Result.retry()` and WorkManager re-wakes with backoff until the engine's
own `cancelWake` removes the work.

**The unique-work name** defaults to `<applicationId>.uploader.wake`. WorkManager's namespace is
global to the app, so a hardcoded name would collide between two libraries — or between two queues
in one app. Give a second queue its own `uniqueWorkName`.

**Registration is required** for the wake layer to do anything. The worker is constructed
reflectively by WorkManager, outside your object graph; without `UploaderEngineRegistry.register` it
finds nothing and returns `Result.retry()` forever.

Register the engine `createUploaderEngine` returned, not a wrapper around it: a decorator registered in its place
serves wake jobs (`awaitDrained`), but upload transports and `UploadGateway` reach only the library's own engine and
report it unavailable, so an `UploadHandler`'s uploads would retry and re-hand forever.

**Hand-offs while a job is finishing.** Both WorkManager transports serve a retry handed off while the item's job is
still running: the job uploads it before it returns, which also means one job can run several attempts in a row and is
subject to WorkManager's roughly ten-minute limit as a whole (a stopped job is re-run; the handler transport replays its
journal). A hand-off after the job finished serving replaces that job with a fresh one, so a retry with a short or zero
backoff is not dropped while WorkManager still reports the previous job running. The handler transport prepares the
attempt from the row; the classic `createWorkManagerUploadTransport` job carries its request, so the transport hands the
newest request of an absorbed hand-off to the job, and the job uploads it only when the item is still in flight: a
duplicate hand-off of an attempt that has just been settled (a delivery, a withdrawal, a retry waiting out its backoff)
uploads nothing, and one that is stamped with an older attempt than the row's (a late lease re-hand) never replaces or serves the newer attempt's request: the transport stamps each hand-off with the `attempts` of the attempt the engine is running (the public `launch` is called from `execute`, inside that attempt), and a hand-off made outside any attempt (for example one a wrapper launches later, from its own scope) carries no stamp and is served whenever the row is in flight. A job that settles or closes its iteration while `execute` has not returned yet (the engine writes the claim after `execute`) waits, bounded by `engineWait`, for that attempt to end, so a fast failure is counted and a retry's hand-off is found instead of both waiting for the lease. A classic job that WorkManager re-runs after it served a later attempt (a retry, or a stop) starts again from the request it was enqueued with and uploads without asking the row, so that older request can be re-sent: the same at-least-once class as any classic retry. Hand-offs are tracked per process; another process falls back to `KEEP` and the lease.

**Early server answers.** A server that answers and closes the connection while the request body is still unread (an early `413`, say) can lose its answer to a connection reset: the receiving stack may discard unread reply bytes when the reset arrives, and the attempt then reads as a transport failure and uploads again. Servers should drain the request before closing. This is a property of the platform's TCP and HTTP stack, not something the transport can undo.

**Reliability.** Good. WorkManager persists across reboots and runs in Doze maintenance windows. It
is not instant, and it is not meant to be.

**A failure to schedule cannot break your enqueue.** If `WorkManager.getInstance` throws — an
uninitialized WorkManager, a process without the provider — the scheduler logs it and moves on.
Delivery then falls back to the next app launch.

## iOS — BGTaskScheduler

Three steps, and all three are required. iOS raises at runtime if the identifier is not permitted,
and silently never runs the task if the handler is registered too late.

**1. `Info.plist`**

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>com.example.app.uploader.drain</string>
</array>
<key>UIBackgroundModes</key>
<array>
    <string>processing</string>
</array>
```

The default identifier is `<bundleId>.uploader.drain`. Read the resolved value from
`BackgroundTaskWakeScheduler.taskIdentifier` if you are unsure, or set `taskIdentifier` explicitly.

**2. Register the handler before `didFinishLaunching` returns**

```swift
BGTaskScheduler.shared.register(
    forTaskWithIdentifier: UploaderWiring.shared.wakeScheduler.taskIdentifier,
    using: nil
) { task in
    UploaderWiring.shared.wakeScheduler.handleWake { drained in
        task.setTaskCompleted(success: drained as! Bool)
    }
    task.expirationHandler = { task.setTaskCompleted(success: false) }
}
```

Registering after `didFinishLaunching` returns is the single most common way to end up with a wake
layer that appears to work and never fires.

**3. Submit the next request yourself if you want a repeating wake.** iOS consumes the request when
it launches the task; the engine submits a new one the next time something is enqueued or a drain
pass leaves work behind, but a queue that is *already* non-empty when the task finishes will not
re-arm on its own until then.

**Reliability.** Opportunistic, and that word is doing a lot of work. iOS decides when — typically
while the device is idle and charging — and may decide never. On the simulator, and whenever
Background App Refresh is switched off, `submitTaskRequest` simply fails; the scheduler logs it and
disarms so a later attempt can retry.

**So the primary path on iOS is the launch-time drain.** `start()` picks up everything the previous
process left behind, which happens whenever the user opens the app. The wake layer shortens the wait;
it does not replace that.

`requiresExternalPower` defaults to `false` deliberately: requiring power makes an already
opportunistic wake considerably rarer.

## iOS — background uploads

`createBackgroundUploadTransport` needs one thing from your app, and without it an upload that finishes
while the app is dead waits for its lease and is sent again: forward
`application(_:handleEventsForBackgroundURLSession:completionHandler:)` to
`BackgroundUploadRelaunch.shared.handleEvents`. See
[`08-upload-transport.md`](08-upload-transport.md#the-relaunch-hook--required). Background sessions
need no `Info.plist` entry and no background mode.

Two timeouts shape a background upload, both configurable since 4.0.0 on `BackgroundUploadConfig`:
`requestTimeout` (60 s) is an **idle** timeout, reset whenever data moves, and `resourceTimeout`
(7 days) bounds the whole transfer. A background session does not fail on an idle timeout; iOS retries
the upload silently until `resourceTimeout` runs out, so that value bounds re-uploads, and a server that
works for a long time before answering should reply `202` with a job id instead. Keep the lease below
`resourceTimeout`. Details in [`08-upload-transport.md`](08-upload-transport.md#timeouts).

## The server's response

Since 4.0.0 the handler transports hand an `UploadHandler` the response — status, headers and the start
of the body — in `classify` and `onDelivered`. What differs by platform:

- **Android.** `UploadHandlerWorker` reads the response in the same attempt, before the connection is
  dropped: `inputStream` for a status below 400, `errorStream` for 400 and above (a missing one is an
  empty body; flagged truncated when the headers declared one the stack did not deliver; a body with a declared
  `Content-Length` and no content encoding (`identity` counts as none) or chunked transfer is read up to that length, never past it). A redirect whose body the stack does not hand over is flagged truncated unless its headers declare an empty body. With a
  process-wide `java.net.Authenticator` that answers challenges, or on a plain JVM, a 401/407 body is
  unavailable and arrives empty and flagged truncated; the status still reaches `classify`. At most 1 MiB is read; the handler's `maxResponseBodyBytes` is applied by the engine.
  The body stays in the process and is never put into `WorkManager`'s data. A `WorkManager` worker has
  about **10 minutes**, and the module does not call `setForeground`: a long upload, or a server that
  answers slowly, can be stopped and retried. Keep the server's answer fast (`202` and a job id).
- **iOS.** The session delegate accumulates the body while the session delivers it, and builds the
  response when the task completes. A body that arrived while the app was dead is delivered only if
  `BackgroundUploadRelaunch.handleEvents` is wired (see
  [`08-upload-transport.md`](08-upload-transport.md#the-relaunch-hook--required)); without it the
  outcome waits for the lease and the item uploads again. A connection that breaks after the status
  arrived (or a timeout that fires while the body arrives) keeps the status and headers and reports the body
  truncated, as on Android; only a failure before any status is a transport failure. **A force-quit cancels
  background transfers**: iOS does not continue an upload for an app the user swiped away.
- **The outcome journal.** Once a handler transport has the server's answer it writes it to a small file
  *before* the engine settles the item, and deletes the file when the settlement landed. The file holds
  the status, the headers, the body and a timestamp, so **a response body rests briefly on disk**. The body is
  held to the handler's `maxResponseBodyBytes` (never above 1 MiB); a handler that sets `0` has no body
  in the file. On Android the limit is read before the upload starts; on iOS the file is first written
  as captured, because the callback cannot wait, and cut to the limit as soon as the engine is reachable. On Android it lives in `noBackupFilesDir/kmptoolkit-uploader/outcomes/<itemId>`
  — never in a cloud backup or a device transfer. On iOS it lives in
  `Application Support/kmptoolkit-uploader/outcomes/<itemId>`, excluded from backup, with the
  `NSFileProtectionCompleteUntilFirstUserAuthentication` class (a background session can finish while
  the device is locked, so a stricter class would fail exactly when the file is needed). If the process
  dies between the answer and the settlement, the next run **replays the file instead of uploading
  again**: the Android worker checks it before preparing the attempt, the iOS transport in a re-hand
  (a first hand-off finds a stale file, deletes it and uploads; so does a re-hand that finds a file stamped with an
  older attempt than its own, a late duplicate that reached the disk), in `handleEvents` and once when it is
  created. On Android the entry carries the attempt it answers and is deleted only after the engine commit
  returned, so a death or stop before the commit leaves it for the next run, and a leftover of an attempt that was
  already settled is recognised and dropped instead of replaying into the retry. On iOS the entry carries the attempt
  too (the hand-off labels the session task with it, and the system keeps the label for a task that outlives the
  process), so the same holds there: a death or stop before the commit leaves the entry for the next replay. Only the
  answer of a task started by an earlier version, which has no label, is deleted just before the commit and written
  back if it does not land (a process death in that instant uploads again: at least once). Hand the built-in transport itself to the handler: a wrapper around it is launched through the public overload, so its answers are unstamped and stay at least once. One replay per item runs at a time in a process. A replayed response reaches `classify` and
  `onDelivered` like the live one, so `onDelivered` must stay idempotent. `Uploader.cancel` deletes the
  file; so does the 7-day expiry. A file of an unknown format version or a damaged one is ignored and
  deleted. So is a file larger than 4 MiB, which no entry this journal wrote can be (it is deleted without being read). On Android a file that exists but cannot be read is waited for while the job has run fewer than 3 times (WorkManager's
  run count, which also counts retries for other reasons) and then given
  up (the item uploads again, at least once); iOS treats it as absent and uploads, for now. Nothing is journaled for a transport failure (there is no response), a write that fails
  leaves the behaviour of 3.0 (a re-upload after a lease expiry), and the legacy classifier transport
  has no journal.
- **`Set-Cookie`.** Headers repeated under one name are joined with `", "`; that cannot be undone for a
  cookie, so do not read cookies from `UploadResponse.headers`.
- **The legacy `createWorkManagerUploadTransport`** settles through one global classifier and stays
  status-only: it never reads a body.

## Both platforms

- **The wake layer is optional.** Without it, the engine's promise is "delivered while the app runs,
  or on its next launch" — which is already correct behavior, just slower.
- **Withdrawing an upload stops it, but cannot recall bytes the server already has.**
  `Uploader.cancel` stops a running Android job mid-body. It cancels an iOS background session, and
  that includes one started by an earlier process. A body that was sent whole before the cancel may
  have been accepted. See [`08-upload-transport.md`](08-upload-transport.md).
- **A wake job must never call `drain()`.** Use `awaitDrained`; single-flight belongs to the started
  engine.
- **Clocks.** Backoff gates and leases are absolute epoch millis, because they must survive process
  death. The engine detects a backwards wall-clock jump (see `UploaderConfig.clockAnomalyFactor`) and
  runs an item whose gate is unreachably far in the future rather than freezing it — and everything
  behind it in its ordering channel — for the duration of the jump. Leases get the same guard: the
  engine never writes a lease longer than 24 hours (a longer `Detached` lease or
  `UploadTransport.leaseMillis` is shortened to that), so a lease further ahead than
  `clockAnomalyFactor` times 24 hours was written before a backwards jump, is treated as expired and
  the item is re-handed — the hand-off is idempotent by contract. A backwards jump smaller than that
  bound still holds a lease for the jump, at most two days with the default factor. Backoff and lease
  arithmetic saturates at `Long.MAX_VALUE`, so a `RetryPolicy.maxDelayMillis` of `Long.MAX_VALUE`
  ("no ceiling") works. A forwards jump merely runs some items early, which is harmless.
- **A logger that throws does not stop the engine.** The engine calls the `Logger` from its drain
  coroutine and from `enqueue`; anything the logger throws, a `CancellationException` included (logging never
  suspends, so it is never the caller's cancellation), is swallowed and costs only that log line. The same holds for every logger the module takes: the Android and
  iOS transports, the wake schedulers and the outcome journal ignore a logger that throws, so a hand-off,
  a wake or a replay is never stopped by a log line.
- **Ids** are `java.util.UUID` on Android and `NSUUID` on iOS, both canonical UUID strings. Override
  `idGenerator` if your server wants a different idempotency-key shape.
  Any id works on both platforms. The outcome journal names its file after the id, so an item whose id it
  cannot use as a file name — empty, `.` or `..`, containing `/`, `\`, a space or a control character, or
  longer than 200 characters — is uploaded and settled normally but its answer is not journaled: a process
  death between the answer and the settlement uploads it again (at least once).
  On iOS, where the data container can be a case-insensitive volume (the simulator, an iOS app running on a Mac), ids that differ only by letter case
  (`Photo-1` and `photo-1`) get different files: an id keeps its own text as the name of its temporary body and its
  journal entry only when no other id can fold onto it (a canonical upper-case UUID, which is the default id, or an
  id without an upper-case letter that is not shaped like a UUID); any other id gets a hashed body name (the SHA-256 of
  its UTF-16 code units, so two ids that differ only by a lone surrogate keep apart) and an escaped journal name
  (`~` marks an escape), which the sweep maps back to the id. An id whose escaped name would pass 230 characters (more than
  about 38 accented letters or 114 upper-case ones; the file system allows 255 bytes) gets a hashed journal name instead
  and its entry starts with the id, which is where the sweep reads it from; so every id the journal accepts is journaled.
  Entries a version before 4.1.0 wrote under the id's own spelling stay readable, are replayed by the sweep and are replaced
  when the id writes again; when an id with no upper-case letter writes while such an entry of an id that differs only by
  case is on disk (`photo-1` beside a leftover `Photo-1`), that entry is first moved to its own escaped name, and `photo-1`
  never reads, deletes or replaces it. The sweep never takes the temporary file of an atomic write (`<name>.sb-<hex>-<letters>`)
  for an entry, and removes one a dead process left once it is five minutes old.
