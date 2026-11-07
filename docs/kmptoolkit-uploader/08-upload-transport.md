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
| Server's response (status, headers, body) | yes, since 3.1.0 | status only |

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

    override suspend fun classify(payload: AvatarUpload, result: UploadResult, response: UploadResponse?): SettleResult =
        when {
            result is UploadResult.Completed && result.statusCode == 401 -> {
                auth.refresh()
                SettleResult.Failed()
            }
            else -> defaultUploadClassification(result)
        }

    override suspend fun onDelivered(payload: AvatarUpload, response: UploadResponse?) {
        // response is the server's answer: here a 202 with the job id in a Location header.
        profiles.markAvatarUploaded(payload.userId, jobId = response?.header("location"))
    }

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
4. **Settle.** What the transport saw goes to `UploadGateway.complete` — with the server's
   `UploadResponse` when it answered, with a bare `UploadResult.TransportFailure` when it did not.
   The engine then:
   1. builds the `UploadResult` from the response (`Completed(response.statusCode)`), so the two cannot
      disagree, and trims the body to the handler's `maxResponseBodyBytes` — the one place the limit is
      enforced, setting `isBodyTruncated`;
   2. calls the three-argument `classify(payload, result, response)`, which by default calls the
      two-argument `classify`, which by default is `defaultUploadClassification`;
   3. for a delivery, calls `onDelivered(payload, response)` *before* the row is removed — by default
      the one-argument `onDelivered`;
   4. settles the item, then calls `onSettled`.

   A thrown `classify` settles as `Failed`; a thrown hook is logged and changes nothing — the upload
   already happened, so a failing `onDelivered` also loses the response.

   | Hook | `response` |
   |---|---|
   | `classify(payload, result, response)` | the answer for every status; `null` for a `TransportFailure`, or for a transport that used the status-only `complete` |
   | `onDelivered(payload, response)` | the answer; `null` only for a status-only `complete` |
   | `onSettled` | not passed; keep what you need from `onDelivered` |

   The gateway carries no claim, and needs none: it settles under the item's **current** claim.
   `launch` is idempotent per item id — one unique WorkManager job, one background `NSURLSession` task
   — so a re-hand-off rejoins the same job rather than starting another, and every claim of the item is
   served by it. The rule `UploaderEngine.settle` applies to a superseded claim's report is for
   executors that are not unique per item; a transport you write must keep `launch`'s contract, or the
   gateway would take one executor's report for another's.

`UploadGateway` reaches the engine through `UploaderEngineRegistry`, so **register the engine** after
creating it. An engine that is not `createUploaderEngine`'s reads as unavailable.

### Reading the response

`UploadResponse` carries the status, the headers (lowercase names), a copy of the start of the body and
`isBodyTruncated`; see [`04-api-reference.md`](04-api-reference.md#uploadresponse-since-310) and the
recipe in [`03-guide.md`](03-guide.md#reading-what-the-server-answered) — `202` plus a job id, the item
id as `Idempotency-Key`, `classify` on the status, `onDelivered` persisting idempotently. The body is
capped by `UploadHandler.maxResponseBodyBytes` (default 64 KiB, at most 1 MiB, `0` for none); an
over-limit body is cut and flagged, never retried. Do not parse a truncated body.

### The outcome journal

The built-in handler transports keep the server's answer on disk between the moment they have it and the
moment the engine has settled it (a small file per item, in no-backup storage on Android and in a
backup-excluded Application Support directory on iOS). If the process dies in between, the next run
**replays that answer through `UploadGateway.complete` instead of uploading again** — the Android
worker at the start of `doWork`, the iOS transport in a hand-off, in `handleEvents` and once at
creation (on iOS only a re-hand is owed a replay). The file holds the body only up to the handler's
`maxResponseBodyBytes` (on iOS, after a brief window in which the whole captured body is on disk; see
[`05-platform-notes.md`](05-platform-notes.md#the-servers-response)). It is deleted just before the engine commits the settlement, on `cancel`, and after 7 days; an item the
engine no longer knows settles nothing and its file is deleted. The response body therefore rests
briefly on disk; see [`05-platform-notes.md`](05-platform-notes.md#the-servers-response). Your own
transport has no journal: keep your own record if it needs one.

## Writing your own transport

A transport for an `UploadHandler` implements `UploadTransport` and reports through `UploadGateway`:

```kotlin
class MyTransport(private val http: HttpClient) : UploadTransport {
    override val leaseMillis: Long = 15 * 60_000L
    override fun launch(itemId: String, request: UploadRequest) { /* start or rejoin, idempotent per itemId */ }
    override fun cancel(itemId: String) { /* stop that item's transfer, if running */ }

    private suspend fun finish(itemId: String, reply: HttpReply?) {
        if (reply == null) {
            UploadGateway.complete(itemId, UploadResult.TransportFailure("no connection"))
        } else {
            UploadGateway.complete(
                itemId,
                UploadResponse(reply.status, reply.headers, reply.bodyPrefix(), isBodyTruncated = reply.hasMore),
            )
        }
    }
}
```

- Read at most 1 MiB of the body (set `isBodyTruncated` when you stop with more to read); the engine
  applies the handler's smaller limit itself, so a transport need not know it.
- Report a server answer with `complete(itemId, response)`; report "the server never answered" with
  `complete(itemId, UploadResult.TransportFailure(...))`. The status-only `complete(itemId,
  UploadResult.Completed(code))` still works and gives the handler a `null` response.
- A body you could not read completely does not make the answer a transport failure: report the status
  with `isBodyTruncated = true`, or an accepted upload would be sent again.
- `complete` returns `false` when no engine is registered or its store failed: keep the outcome and
  report it again. An unknown or already settled id is a no-op that returns `true`.
- Keep `launch` idempotent per item id; the gateway settles under the item's current claim.

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
body from disk over `HttpURLConnection`, reads the response, and settles. It returns `Result.retry()` when no engine is
registered to prepare or settle, so an outcome is never dropped, and `Result.success()` otherwise.

The response is read in the same attempt, before the connection is dropped: headers without the status
line, and the body from `errorStream` for a status of 400 and above (no stream is an empty body) or
`inputStream` below it, at most 1 MiB. The body stays in the process — it is never put into
`WorkManager`'s data. Reading the body stops once the read timeout has passed in total (at most one more
read timeout for a read already waiting) or, between reads, when the job is cancelled, and a body that
cannot be read completely — the connection broke, or the time ran out —
is reported as truncated with the bytes read so far, not as a transport failure. A `WorkManager` worker has about 10 minutes and this module does not call
`setForeground`, so keep the upload and the server's answer short.

`cancel(itemId)` cancels that item's unique work and deletes its journaled outcome, if any — and nothing else. A job already uploading stops
mid-body: the write loop checks its job before every chunk and drops the connection without sending
the chunked body's terminator, so the server never receives a complete request. Only a cancel that
lands after the last byte left is too late, and then the server may already have the upload.

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

The delegate accumulates the response body as the session delivers it (at most 1 MiB, converted once
when the task completes) and builds the response from the final `NSHTTPURLResponse` before the session
is invalidated; only headers whose names and values are strings are kept. **A force-quit cancels
background transfers** — iOS does not continue an upload for an app the user swiped away.

A background session takes the whole request when the task is created, so on iOS the request is the
one `prepareUpload` returned at hand-off, not one prepared when the transfer starts, and
`nsurlsessiond` keeps its headers until the transfer ends. Size an `Authorization` token's lifetime —
or the lease — for that.

- **Idempotent launch.** An upload an earlier hand-off in this process started is joined. A session
  whose upload already completed is replaced rather than joined, and one that only a relaunch opened
  is taken over and checked for a running task — so a hand-off never joins a session that has nothing
  to upload and leaves the item in flight until its lease expires. In a new process, a re-hand
  rejoins a task the daemon is still running. A re-hand that finds nothing running waits
  `rehandFlushWindow` (3 s) for buffered completion events before starting afresh: the previous
  process may have finished without settling, and uploading again would deliver twice.
- **Cancellation.** `cancel(itemId)` cancels that item's session without settling it. An upload an
  earlier process started is reached through its session identifier, which carries the item id, so a
  cancel after a relaunch still finds it.
- **A relaunch that brings no completion** — the result was already settled, or iOS woke the app for
  another reason — releases its session once events drain, so a later hand-off starts a fresh upload
  instead of joining an idle session.
- **Lease.** 60 minutes by default. iOS may run a background session long after the hand-off; the
  identifier rejoin keeps a shorter lease safe too.

### Timeouts

`BackgroundUploadConfig(requestTimeout = 60.seconds, resourceTimeout = 7.days)` sets the session's
`timeoutIntervalForRequest` and `timeoutIntervalForResource`. The defaults are the system's own values,
so behaviour is unchanged unless you set them; both must be positive and finite.

- **`requestTimeout` is an idle timeout.** Apple's documentation describes it as how long a task waits
  for additional data to arrive before giving up, with the timer reset whenever new data arrives. It is
  not a bound on the whole upload: a slow but steady transfer never trips it, and neither does a long
  upload. It trips when the connection goes silent — including while the server works on the request
  after the last byte was sent and has not started answering.
- **A background session retries instead of failing.** After an idle timeout `nsurlsessiond` silently
  re-sends the upload, and keeps doing so until `resourceTimeout` runs out. The handler sees no failure
  in between, and the server may receive the same upload more than once. `resourceTimeout` is therefore
  the bound on re-uploads: shorten it if you would rather fail an upload than send it again for days.
  Make the server idempotent (the item id is the recommended `Idempotency-Key`).
- **Keep the lease below `resourceTimeout`.** The lease is when the engine re-hands an item that has not
  settled; the transport joins the task `nsurlsessiond` is still running (a running task is found
  through the session identifier), so an expired lease is safe, not a second upload. Once
  `resourceTimeout` has passed iOS fails the transfer, and only then is a re-hand a fresh upload.
- **Long server work.** Do not hold the upload response open while the server works: the connection sits
  idle and trips the timeout and its retry. Answer `202` with a job id and finish the work behind it, as
  in the recipe in [`03-guide.md`](03-guide.md).

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
while the app was dead waits for the lease to expire and the item is uploaded again — and the response
body, which iOS delivers to the delegate only after the relaunch, never reaches your handler.

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

Its worker settles under the item's current claim, as the gateway does and for the same reason: the
job is unique per item id, so it serves every claim of the item. Like the gateway, it reaches only an
engine made by `createUploaderEngine`, and retries the job otherwise.

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
- **Status only.** Its worker never reads the response body or headers: the global `classify` sees an
  `UploadResult` and nothing else. Use `UploadHandler` to read the server's answer.
- **The engine cannot cancel its uploads.** A plain handler does not expose its transport, so
  `Uploader.cancel` removes the row but leaves the job running. Call `transport.cancel(id)` yourself.
  It stops the job mid-body, as the handler transport's does.

## Manifest and permissions

Nothing beyond what the wake scheduler already needs on Android. `INTERNET` is required by any app
that makes network calls at all and is not declared by this module — see
[`05-platform-notes.md`](05-platform-notes.md).
