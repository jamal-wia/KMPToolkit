# The built-in upload transport

`UploadTransport` is a ready-made executor for the one shape of detached delivery almost every app
eventually needs: a plain multipart HTTP upload that must keep going after the process dies. Read
this if you are about to write your own `AttemptResult.Detached` executor for exactly that — you
probably do not need to.

## What it is, and what it is not

- **It is a `UploadTransport` you launch from `execute()` instead of a hand-rolled executor.** See
  [`03-guide.md`](03-guide.md#a-ready-made-executor-for-a-multipart-http-upload) for the shape.
- **It is not a new delivery mechanism.** Under the hood it is exactly `AttemptResult.Detached` +
  `UploaderEngine.settle` — the same contract documented in [`03-guide.md`](03-guide.md#detached-delivery-for-work-that-outlives-an-attempt).
  You could write everything it does yourself; this exists so that most apps never have to.
- **It carries no serialization dependency.** `UploadRequest` is encoded into WorkManager's own
  primitive `Data` by hand (a handful of parallel `String[]` arrays) rather than through
  `kotlinx.serialization` or any other library — this module stays exactly as dependency-light as it
  was before. `Data` is capped at roughly 10 KB serialized: comfortably enough for a URL, a handful
  of headers and per-field metadata, but file *contents* never touch this encoding — only
  `UploadField.File.path`, which the worker reads from disk at upload time.

## Android: `createWorkManagerUploadTransport`

```kotlin
val transport: UploadTransport = createWorkManagerUploadTransport(
    context = context,
    config = UploadTransportConfig(),
    classify = ::defaultUploadClassification,
)
```

One WorkManager job per item, unique-keyed by item id with `ExistingWorkPolicy.KEEP` — that is what
makes `launch()` idempotent for free: a re-hand while the job is alive joins it, and after a dead job
it re-enqueues. The worker performs the request with a hand-streamed multipart body over
`HttpURLConnection` (no buffering the file in memory) and reports the outcome to whichever
`UploaderEngine` is registered through `UploaderEngineRegistry` — exactly the same registry the wake
scheduler already uses, for exactly the same reason: WorkManager instantiates the worker
reflectively, with no way to hand it a dependency.

### `classify`: yours to override

The worker cannot call back into your handler after a process restart, so classification cannot be
your handler's `classify()` method the way a hand-rolled executor's could be — it has to be a plain
function, registered once, alongside the transport itself:

```kotlin
val transport = createWorkManagerUploadTransport(
    context = context,
    classify = { result ->
        when (result) {
            is UploadResult.Completed -> if (result.statusCode == 413) SettleResult.Park("too large") else defaultUploadClassification(result)
            is UploadResult.TransportFailure -> SettleResult.Failed(null)
        }
    },
)
```

The default, [`defaultUploadClassification`](04-api-reference.md#defaultuploadclassification), is a
reasonable guess (2xx delivered, 4xx dropped, everything else a retryable failure) — not a policy
this module can claim to know for your API. Registration is process-wide and last-write-wins, the
same as `UploaderEngineRegistry`: create the transport once, from the same place you create your
engine, and pass the same instance to every handler that uploads.

### Manifest and permissions

Nothing beyond what the wake scheduler already needs. `INTERNET` is required by any app that makes
network calls at all and is not declared by this module — see
[`05-platform-notes.md`](05-platform-notes.md).

## iOS: not yet built in

There is no `createXxxUploadTransport` for iOS. A background upload that survives the app being
suspended or killed needs a background `NSURLSession` whose delegate callbacks are delivered to a
*relaunched* process — which requires your app's own `AppDelegate` (or `UIApplicationDelegate`
conformer) to implement `application(_:handleEventsForBackgroundURLSession:completionHandler:)` and
forward the call into Kotlin. That hook is OS-mandated and cannot be supplied by a library alone, on
any platform, in any language — the same reason `kmptoolkit-uploader`'s own iOS wake layer
(`BackgroundTaskWakeScheduler`) already requires you to register a `BGTaskScheduler` identifier in
`Info.plist` yourself.

Until this module ships one, write your own `UploadTransport` backed by
`URLSessionConfiguration.background(withIdentifier:)`, following the same shape
`createWorkManagerUploadTransport` does: one session task per item (identifier-keyed for the
idempotent-launch contract), reporting through `UploaderEngineRegistry.await(...)` +
`UploaderEngine.settle`. `docs/kmptoolkit-downloader/07-background-downloader.md` documents the
equivalent SPI for downloads, if you are looking for a worked shape to follow.
