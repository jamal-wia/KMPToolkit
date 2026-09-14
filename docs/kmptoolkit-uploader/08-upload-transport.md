# The built-in upload transports

A ready-made executor for the one shape of detached delivery almost every app eventually needs: a
multipart HTTP upload that must keep going after the process dies. Read this if you are about to
write your own `AttemptResult.Detached` executor for exactly that — you probably do not need to.

There are two ways to use it, and for new code the first is the one to pick.

| | `UploadHandler` + a handler transport | a plain handler + `createWorkManagerUploadTransport` |
|---|---|---|
| Platforms | Android (`createWorkManagerUploadHandlerTransport`) and iOS (`createBackgroundUploadTransport`) | Android only |
| What the platform job stores | Android: the item id. iOS: the request, which a background session needs up front | the whole request, headers included |
| Request built | Android: when the upload actually runs. iOS: at hand-off | when the item is handed off |
| Classification | per handler, with the payload | one process-wide function |
| Progress | whole percents to the handler | none |
| A failed hand-off | retried under the handler's policy | logged and swallowed; the lease recovers it |
| Hooks | `onDelivered`, `onSettled`, `onUploadProgress` | none |

Both are exactly `AttemptResult.Detached` plus a settlement underneath — the contract in
[`03-guide.md`](03-guide.md#detached-delivery-for-work-that-outlives-an-attempt). Neither adds a
serialization dependency.

## `UploadHandler`: describe the upload, let the transport run it

```kotlin
class AvatarUploadHandler(transport: UploadTransport, private val auth: Auth) :
    UploadHandler<AvatarUpload>(transport) {

    override val type: String = "avatar_upload"
    override fun encodePayload(payload: AvatarUpload): String = json.encodeToString(payload)
    override fun decodePayload(raw: String): AvatarUpload = json.decodeFromString(raw)

    override suspend fun prepareUpload(context: AttemptContext, payload: AvatarUpload): UploadPreparation {
        if (!File(payload.path).exists()) return UploadPreparation.Park("source file is gone")
        return UploadPreparation.Proceed(
            UploadRequest(
                url = "https://api.example.com/avatar",
                headers = mapOf("Authorization" to "Bearer ${auth.token()}"),
                fields = listOf(UploadField.File("file", "avatar.jpg", "image/jpeg", payload.path)),
            ),
        )
    }

    override suspend fun classify(payload: AvatarUpload, result: UploadResult): SettleResult =
        when {
            result is UploadResult.Completed && result.statusCode == 401 -> {
                auth.refresh()
                SettleResult.Failed()
            }
            else -> defaultUploadClassification(result)
        }

    override suspend fun onDelivered(payload: AvatarUpload) = profiles.markAvatarUploaded(payload.userId)

    override suspend fun onSettled(payload: AvatarUpload, attempts: Int, result: SettleResult) {
        if (result is SettleResult.Delivered) File(payload.path).delete()
    }
}
```

What the engine does with it:

1. **Hand-off.** On a drain, `execute` — which `UploadHandler` implements for you — calls
   `prepareUpload`. `Proceed` goes to the transport's `launch(itemId, isRehandOff, request)` and the
   item is claimed under the transport's lease; `Drop` and `Park` finish the item there. A transport
   that throws — the platform scheduler is unavailable — fails this attempt only, under the handler's
   retry policy.
2. **Run.** When the platform actually starts the upload, the transport asks
   `UploadGateway.prepareAttempt(itemId)`, which calls `prepareUpload` **again**. That is where a
   token is fresh, and where a re-run of an item that settled meanwhile finds nothing owed and uploads
   nothing. A preparation that throws here leaves the item in flight; the lease recovers it.
3. **Progress.** Each whole percent goes to `onUploadProgress` through `UploadGateway.progress`.
   Best effort, never after the item has settled.
4. **Settle.** The raw `UploadResult` goes to `UploadGateway.complete`: `classify` decides, `onDelivered`
   runs *before* the row is removed for a delivery, the item settles, then `onSettled` runs. A thrown
   `classify` settles as `Failed`; a thrown hook is logged and changes nothing — the upload already
   happened.

`UploadGateway` reaches the engine through `UploaderEngineRegistry`, so **register the engine** after
creating it. An engine that is not `createUploaderEngine`'s reads as unavailable.

## Android: `createWorkManagerUploadHandlerTransport`

```kotlin
val transport: UploadTransport = createWorkManagerUploadHandlerTransport(
    context = context,
    config = UploadTransportConfig(),
)
```

One WorkManager job per item, unique-keyed by item id with `ExistingWorkPolicy.KEEP`: a re-hand while
the job is alive joins it, and after a dead job it re-enqueues. The job's input data is the item id
plus timeouts — nothing of the request, so no token at rest in WorkManager's database and no 10 KB
`Data` limit to fit under. `UploadHandlerWorker` prepares through the gateway, streams the multipart
body from disk over `HttpURLConnection`, and settles. It returns `Result.retry()` when no engine is
registered to prepare or settle, so an outcome is never dropped, and `Result.success()` otherwise.

### Taking over jobs an earlier worker of yours enqueued

WorkManager stores the worker's class name with every job. If your app enqueued upload jobs with its
own worker before adopting this module, those jobs still name that class on installed devices.
Keep the class, as a subclass that reads your old input key:

```kotlin
// Same fully qualified name as the worker your previous release shipped.
class LegacyUploadWorker(context: Context, params: WorkerParameters) : UploadHandlerWorker(context, params) {
    override fun readItemId(inputData: Data): String? = inputData.getString("item_id")
}
```

and pin `UploadTransportConfig(uniqueWorkNamePrefix = ..., workTag = ...)` to the names you used, so a
re-hand joins the old job instead of starting a second upload beside it. Remove the subclass once no
installed version can still have such a job queued.

## iOS: `createBackgroundUploadTransport`

```kotlin
val transport: UploadTransport = createBackgroundUploadTransport(
    config = BackgroundUploadConfig(sessionIdentifierPrefix = "com.example.app.uploader.upload."),
    logger = logger,
)
```

One background `NSURLSession` per item, its identifier the prefix plus the item id. The system daemon
`nsurlsessiond` keeps uploading after the app is suspended or killed. The multipart body is written to
a temporary file — background uploads must come from a file — streamed from the source files in
chunks, so a large recording is never held in memory, and removed when the upload completes.

A background session takes the whole request when the task is created, so on iOS the request is the
one `prepareUpload` returned at hand-off, not one prepared when the transfer starts, and
`nsurlsessiond` keeps its headers until the transfer ends. Size an `Authorization` token's lifetime —
or the lease — for that.

- **Idempotent launch.** A session live in this process is joined. In a new process, a re-hand
  rejoins a task the daemon is still running. A re-hand that finds nothing running waits
  `rehandFlushWindow` (3 s) for buffered completion events before starting afresh: the previous
  process may have finished without settling, and uploading again would deliver twice.
- **Cancellation.** `cancelAll` cancels the live sessions without settling them: a cancelled upload
  spends no retry budget.
- **A relaunch that brings no completion** — the result was already settled, or iOS woke the app for
  another reason — releases its session once events drain, so a later hand-off starts a fresh upload
  instead of joining an idle session.
- **Lease.** 60 minutes by default. iOS may run a background session long after the hand-off; the
  identifier rejoin keeps a shorter lease safe too.

### The relaunch hook — required

When an upload finishes while the app is not running, iOS relaunches the app and calls an app-delegate
method only your Swift code can implement. Forward it:

```swift
func application(_ application: UIApplication,
                  handleEventsForBackgroundURLSession identifier: String,
                  completionHandler: @escaping () -> Void) {
    if identifier.hasPrefix("com.example.app.uploader.upload.") {
        BackgroundUploadRelaunch.shared.handleEvents(identifier: identifier, completion: completionHandler)
    } else {
        completionHandler()
    }
}
```

`handleEvents` waits up to 10 s for your start-up code to have created the transport — it runs on
this launch path too — recreates the session, receives the buffered completion and settles the item.
The completion handler is called exactly once: when the events are drained, or after 25 s, because
iOS penalises an app that never calls it. Without this hook the outcome of an upload that finished
while the app was dead waits for the lease to expire and the item is uploaded again.

**Pin the prefix** if an earlier version of your app started background sessions under another one:
the identifier outlives the process, and a relaunch for a session nobody recognises loses its
outcome. The default is `<CFBundleIdentifier>.uploader.upload.`.

No `Info.plist` entry is needed for background sessions themselves.

## The classifier-based transport: `createWorkManagerUploadTransport`

The transport that predates `UploadHandler`, kept for code written against it. A plain handler
launches it from `execute()` and returns `Detached`:

```kotlin
val transport: UploadTransport = createWorkManagerUploadTransport(context, classify = ::defaultUploadClassification)

override suspend fun execute(context: AttemptContext, payload: AvatarUpload): AttemptResult {
    transport.launch(context.id, UploadRequest(url = payload.uploadUrl, fields = payload.fields))
    return AttemptResult.Detached(transport.leaseMillis)
}
```

Its limits are the reason `UploadHandler` exists:

- **The whole request is stored in WorkManager's `Data`** — URL, headers and field values, encoded by
  hand into parallel arrays — so an `Authorization` header sits at rest in WorkManager's database, the
  token is as old as the hand-off when the job finally runs, and the request must fit `Data`'s ~10 KB.
  File *contents* never do; only paths.
- **`classify` is one process-wide function**, registered when the transport is created, last write
  wins. The worker cannot reach your handler after a process restart, so it cannot use the payload.
- **A failed enqueue is logged and swallowed**: the item is still claimed in flight and waits for the
  15-minute lease.
- **No progress, no hooks, Android only.**

## Manifest and permissions

Nothing beyond what the wake scheduler already needs on Android. `INTERNET` is required by any app
that makes network calls at all and is not declared by this module — see
[`05-platform-notes.md`](05-platform-notes.md).
