# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with one addition: **before
1.0.0**, a breaking API change is called out explicitly under its own `Breaking` heading rather than
silently folded into `Changed`, since minor version bumps are not yet a compatibility guarantee.

## [Unreleased]

## [3.1.0]

### Breaking

- `kmptoolkit-uploader` (iOS): the deprecated three-parameter `BackgroundUploadConfig` constructor no
  longer has default arguments (it had them in 3.0.0). Only a library precompiled against 3.0.0 that
  calls it with arguments omitted, for example `BackgroundUploadConfig()`, must be recompiled against
  3.1.0. Kotlin source code, Swift code and apps are unaffected.

### Added

- `kmptoolkit-uploader`: an `UploadHandler` now receives what the server answered. The new
  `UploadResponse` (status code, headers with lowercase names, the start of the body, and
  `isBodyTruncated`) reaches the handler in `classify(payload, result, response)` and
  `onDelivered(payload, response)` for every answer — a 2xx, but also a 4xx or 5xx, whose body usually
  says why. `response` is `null` only when the server never answered. Header names that differ only in
  case are merged (values joined with `", "`, which is lossy for `Set-Cookie`: do not read cookies from
  it), and the headers are capped at 64 K characters of names and values together, the rest dropped.
  - `UploadHandler.maxResponseBodyBytes` (default `DEFAULT_MAX_RESPONSE_BODY_BYTES`, 64 KiB) caps the
    body. The engine enforces it: the value is clamped to 0..1 MiB, a body over it is cut and flagged
    with `isBodyTruncated`, and a limit of 0 delivers headers and status without a body. Truncation
    never causes a retry.
  - `UploadGateway.complete(itemId, response, engineWait)` lets a custom transport, and
    `kmptoolkit-uploader-testing`, report the full answer; the status-only `complete(itemId, result)`
    stays for a transport failure.
  - Both built-in handler transports read the response: Android from `HttpURLConnection` (the error
    stream for a 4xx/5xx), iOS from the background session's delegate. The body is never put into
    `WorkManager`'s data. An iOS body that arrives while the app is dead reaches the handler only when
    `BackgroundUploadRelaunch.handleEvents` is wired.
  - See `docs/kmptoolkit-uploader/03-guide.md` for the recommended recipe: the server answers `202`
    with a job id, and the client sends the item id as an `Idempotency-Key`.
- `kmptoolkit-uploader`: `BackgroundUploadConfig` (iOS) gains `requestTimeout` and `resourceTimeout`,
  applied to the background session's `timeoutIntervalForRequest` and `timeoutIntervalForResource`.
  The defaults are the system's own (60 s and 7 days), so behaviour does not change. Zero, negative
  and infinite values are rejected. The request timeout is an idle timeout, and a background session
  retries silently after it until the resource timeout runs out, so `resourceTimeout` bounds
  re-uploads; see `docs/kmptoolkit-uploader/08-upload-transport.md`. The three-parameter constructor
  stays as a deprecated overload (warning level, no default arguments), so Swift code calling
  `BackgroundUploadConfig(sessionIdentifierPrefix:lease:rehandFlushWindow:)` still compiles. In Kotlin,
  `BackgroundUploadConfig()` and any call that leaves a parameter out or names a timeout resolve to the
  new constructor; only a call that supplies exactly the three old arguments (positionally or by name)
  and no timeout lands on the deprecated one, with a warning. See Breaking for the one binary
  consequence.
- `kmptoolkit-downloader`: `DownloaderConfig` with `stallTimeout` (default
  `DownloaderConfig.DEFAULT_STALL_TIMEOUT`, 5 minutes; `Duration.INFINITE` turns stall detection off)
  and a `createDownloader` overload that takes it, placed right after `stateStore`. The existing
  overload is unchanged and behaves like `DownloaderConfig()`. A new guide,
  `docs/kmptoolkit-downloader/07-background-downloader.md`, states the contract for a
  `BackgroundResourceDownloader`: subscribe before enqueue, replay rules, the message phrasing the
  error classifier reads, what happens to the partial file, the iOS keep-alive recipe, the Android 15
  `dataSync` limit, and a thread-safe sample.

### Changed

- `kmptoolkit-uploader`: `UploadHandler.classify(payload, result)` is no longer abstract. It is now
  `open` and defaults to `defaultUploadClassification(result)`, so a new handler need not implement
  it; handlers written for 3.0 keep overriding it unchanged. This is the one intentional modifier
  change in the ABI dump, and it is binary and source compatible. The legacy
  `createWorkManagerUploadTransport` (global classifier) stays status-only.
- `kmptoolkit-downloader`: a failed transfer now keeps its partial temp file, so the next
  `ensureAvailable` resumes from it. The engine deletes it only on a user cancel, on bytes that fail
  their integrity check, on a `NotFound` (404/410) failure, or when the downloader reports `Cancelled`.
  Before, a final failure deleted it, which defeated resuming after a lost connection.
- `kmptoolkit-downloader`: retry policy of the group path. An attempt counts as progress only when it
  reports a fraction beyond the furthest any earlier attempt reported; a run of failures restarts on
  such progress and otherwise ends after three attempts. `NotFound` and `Unauthorized` are never
  retried, and each re-enqueue waits 2 s, doubling up to 30 s. Before, any error after any `Progress`
  was retried at once and without limit.
- `kmptoolkit-downloader`: an HTTP status in an error message is read only when stated as
  `HTTP <code>` or `status <code>` (and a stated status wins over the words "unauthorized" and
  "forbidden"): 404 and 410 are `NotFound`, 401 and 403 `Unauthorized`, 5xx `Server(code)`, any other
  status `Unknown`. A bare number in an id, URL or byte count is no longer taken for a status, and a
  stall is always `Timeout`. A message that does not state the status that way (for example
  `404 Not Found` or `HTTP response code: 403`) is now `Unknown`: retried on the group surface, and its
  partial file is kept. Phrase port errors as documented in `07-background-downloader.md`.
- `kmptoolkit-downloader`: the engine subscribes to a transfer's events before it calls
  `enqueueDownload` and drops what the subscription delivers before the enqueue (a replay of an earlier
  attempt). An event flow that completes without a terminal event fails the unit as
  `DownloadError.Unknown` at once instead of being waited out as a stall.
- `kmptoolkit-downloader`: stall detection is armed when the engine subscribes, so a transfer that never
  emits any event is declared stalled after `stallTimeout` instead of waiting forever. A transport that
  is legitimately silent (iOS waiting for connectivity) must re-emit its current `Progress`, or the
  host sets `stallTimeout = Duration.INFINITE`.
- `kmptoolkit-downloader`: `Downloader.cancelDownload(unit)` is synchronous: the transfer is cancelled
  and the state is `Idle` when it returns, so a request made right after starts a fresh transfer. A
  `BackgroundResourceDownloader` must report `isDownloadInProgress == false` after its `cancelDownload`
  and must not replay a finished transfer's terminal event after a new `enqueueDownload`.
  `cancelDownload(group)` also cancels a per-unit `ensureAvailable` running for one of the group's
  units; a call whose last commit has already succeeded is not cancelled and publishes `Completed`.
- `kmptoolkit-audio-player` (iOS): the default `coroutineContext` of `createAudioPlayer` is now
  `Dispatchers.Main` (it was `Default`), so `AVPlayer` is only touched from the main thread. Position
  updates need a free main thread; pass `Dispatchers.Default` to restore the 3.0 behavior.
  `AudioPlayer.prepare` is documented as having no built-in deadline.
- `kmptoolkit-notification`: `post` can now return `Coalesced` for any notification, not only a
  progress update, when a `cancel` or a newer post of the same id overtook it (per `Notifier` instance);
  nothing is posted then. Before, `Coalesced` meant only a redundant progress frame, so a caller that
  treats the result of a non-progress post as `Posted` or an error now sees a new answer.
- Docs: the `kmptoolkit-scheduler` platform notes describe the real iOS cap behaviour;
  `kmptoolkit-location` documents that iOS updates stop while the app is suspended and what background
  location needs; `kmptoolkit-language` documents that `onLanguageChanged` runs
  under the holder lock. Also corrected: the hijri getting-started imports, the `RELEASING.md`
  configuration-cache note, version-catalog comments and a stale Robolectric comment.

### Fixed

- `kmptoolkit-uploader`: an upload whose answer arrived but whose settlement was lost no longer uploads
  again. The Android and iOS handler transports write the completed outcome (status, headers, capped
  body) to a small file before settling and delete it once the engine has the answer; a worker restart,
  an iOS relaunch or a re-hand after a lease expiry replays that file through `UploadGateway.complete`
  instead of re-uploading. The file is deleted on `cancel` and after 7 days, an orphan sweep runs when
  the transport is created, and an entry of an unknown format version is ignored. No public API change.
  The journal keeps the body only up to the handler's `maxResponseBodyBytes` (on iOS, after a brief
  window in which the whole captured body is on disk) and replays at most once per item. The response
  body now rests briefly on disk, in no-backup storage on Android and a backup-excluded Application
  Support directory (`NSFileProtectionCompleteUntilFirstUserAuthentication`) on iOS; see
  `docs/kmptoolkit-uploader/05-platform-notes.md`. The legacy `createWorkManagerUploadTransport` is
  unchanged.
- `kmptoolkit-uploader`: further settlement and transport fixes.
  - Android: the classic `UploaderUploadWorker` no longer loses an upload's outcome when the app's
    `classify` throws, when the store refuses the settle, or when WorkManager stops the job right after
    the server answered; a persistent fault no longer re-uploads the file every lease.
  - A failing worker-side `prepareUpload` now spends retry budget like the drain's own, and one item
    failing outside its handler no longer aborts the drain pass for the items behind it.
  - Android: a blocked or stalled body write is dropped by a watchdog on job cancellation or when the
    read timeout passes without body progress. The clock starts when the body starts, so a slow connect
    keeps its own connect timeout. The response read stops at the read timeout and keeps the bytes read
    so far, flagged `isBodyTruncated`; a `maxResponseBodyBytes` getter that throws falls back to the
    default limit instead of reading as a store failure.
  - iOS: a buffered completion arriving while a re-hand writes its body no longer lets the hand-off
    create a task on an invalidated session, and the drained callback runs once. A replacement
    background session opens only after the previous session of the same item reported itself invalid,
    and cancelling the item meanwhile withdraws the hand-off.
- `kmptoolkit-uploader-testing`: `FakeUploader` is thread-safe and its result hook may call back into
  the fake.
- `kmptoolkit-uploader-sqldelight`: `UploaderStorage.close()` called on the database thread throws
  instead of deadlocking, and a second `close()` is always a no-op (also on iOS, where a thread id can
  be reused). The transaction-await deadlock and the close order are documented.
- `kmptoolkit-downloader`: a complete download whose commit failed for a reason other than its bytes
  (a full disk, an archive that would not extract) was deleted; it is now kept and the next
  `ensureAvailable` commits it without downloading again.
- `kmptoolkit-downloader`: a terminal event emitted while enqueueing (through a flow without replay)
  was lost, so the attempt stalled and a finished temp file could be deleted. A complete temp file is
  now committed instead of being re-downloaded or deleted after an `Error` or a stall, and an attach to
  a transfer that ended before the engine subscribed no longer hangs. A flow that completes on a
  leftover terminal event of an earlier transfer no longer leaves `ensureAvailable` waiting for a stall
  (forever with `stallTimeout = Duration.INFINITE`).
- `kmptoolkit-downloader`: an exception from a port the host implements (`enqueueDownload`,
  `isDownloadInProgress`, the event flow, `DownloadStateStore`) escaped `ensureAvailable` raw and left
  the group at `Downloading`; it now ends as a `DownloadFailedException` carrying the cause. A
  `DownloadNotifier` that throws is logged and no longer fails the download.
- `kmptoolkit-downloader`: `cancelDownload(group)`, `cancelDownload(unit)` and `cancelAllDownloads()`
  now end a running `ensureAvailable` with `DownloadCancelledException` at once, also while it waits
  out a retry backoff, between units of a group, or during a commit. Before, such a call carried on and
  re-enqueued a download the user had cancelled (including after logout). A cancel during a commit is
  reported as a cancel, not as a `Storage` error with an error notification, and no longer deletes the
  temp file under a commit in flight; a late `Progress` can no longer bring a cancelled group back to
  `Downloading`.
- `kmptoolkit-downloader`: the group loop and the per-unit surface no longer transfer and commit the
  same unit at the same time, a group call no longer downloads again a unit the per-unit surface
  committed meanwhile, and a redundant per-unit `ensureAvailable` no longer overwrites `Completed` with
  `Available`. A group whose last unit was committed by the per-unit surface now ends as `Completed`
  (not `Downloading` with its notification posted), also when the cancel arrives after the last commit.
- `kmptoolkit-scheduler` (iOS): `schedule` no longer reports `Exact` for a request iOS would silently
  discard past its 64-pending-notification cap; with 64 other requests pending it returns
  `Failed(PlatformError)` and adds nothing (re-scheduling a pending id still succeeds). The check and
  the add run under one process-wide lock, and `cancel` and `cancelAll` take the same lock, so a cancel
  can no longer be overtaken by an in-flight schedule.
- `kmptoolkit-notification`: a progress frame becomes the coalescing baseline only after the platform
  accepted the post, so retrying the same progress after a failed post is posted instead of returning
  `Coalesced` (Android and iOS). A `cancel` is ordered against in-flight posts, so it is no longer
  followed by a post it should have cancelled, and a post (progress or not) overtaken by a newer post
  for the same id, or by a `cancel`, is dropped.
  On iOS, an add rejected because authorization was revoked after the check returns `PermissionDenied`
  instead of `Failed`.
- `kmptoolkit-permission`: `PermissionRequestFlow` no longer stays in `Requesting` after the caller is
  cancelled (it goes back to `Idle`, and the next `refresh()` reads the real choice), and a stale
  refresh or start result no longer overwrites newer state. A special-access
  request from a background dispatcher no longer returns early while the Settings screen is open, a
  request cancelled before its dialog is posted no longer shows the dialog, and `observe` subscribes to
  resolved requests before its first status read on any dispatcher, so it no longer misses a request
  that resolves as collection starts. A pending request whose activity finished is abandoned (also when
  the next activity resumes before the finished one is destroyed), so it no longer blocks later
  requests. The iOS "when in use" location request no longer waits forever when no prompt appears.
- `kmptoolkit-activity`: a resume listener cancelled during a dispatch is no longer called for that
  resume.
- `kmptoolkit-storage`: on Android, the secure store caches its key per alias for the whole process and
  drops a key the platform rejects. A keystore fault no longer makes it delete its alias and every
  stored secret on a transient fault (API 33+) or on any key lookup after the first in the process;
  the delete-and-regenerate recovery runs only on the first key resolution, where on API 24-32 a
  keystore fault still looks like an unrecoverable key. A transient fault neither drops the cached key
  nor re-resolves it. A read reports such a fault, including a transient decrypt failure, as
  `Unavailable` (before, a decrypt failure was `Undecryptable` and the value was reported as lost), and
  a write reports a transient cipher fault as `OperationFailed`; stored values are kept.
  `DeviceIdProvider.current()` no longer replaces the stored id when the store is only
  temporarily unavailable.
- `kmptoolkit-audio-player`: a cancelled middle prepare could let the next load (or unload's engine
  release) start while an earlier load was still unwinding; an error reported right after prepared is no
  longer lost (`Error` wins over `Ready`); an Android error that arrives after a load was cancelled no
  longer flashes `Error`; iOS end-of-item notifications already in flight no longer mark a newly loaded
  source `Completed`.
- `kmptoolkit-audio-recorder`: `release()` during an in-flight prepare no longer drives the engine from
  two threads or opens the microphone after release; a low-storage reading taken before `pause()` no
  longer ends the paused recording; meter samples no longer overlap stop and release; a throw from a
  platform call before the engine runs now yields `EngineFailure` instead of leaving the recorder in
  `Preparing`; a failed or cancelled prepare no longer deletes a file that already existed at an
  explicit `outputPath`.
- `kmptoolkit-video-player-javafx`: the engine's `isJavaFxMediaAvailable()` and first `prepare()` no
  longer stall for 10 s and report the runtime unavailable for the rest of the process when called on
  the JavaFX thread (a busy thread is not remembered as unavailable, a runtime whose thread check
  fails stays unavailable instead of throwing), `prepare()` after the toolkit has exited fails with
  `RuntimeUnavailable` within the ready timeout, and the frame pulse stops for audio-only sources and
  once a paused picture has settled.
- `kmptoolkit-video-player-vlcj`: the engine no longer overwrites a seek target with libvlc's pre-seek
  clock, so quick repeated seeks step from the target.
- `kmptoolkit-video-player`: the Android engine no longer lets a position refresh posted before an
  off-thread `seekTo` or `pause` undo it, and the iOS engine no longer clears a newer seek's target
  when an older seek to the same position is interrupted.
- `kmptoolkit-video-player-compose`: `VideoSeekBar` releases its handle when the bar becomes inactive
  while a seek the player dropped was pending.
- `kmptoolkit-hardware-keys`: the dialog-window key policy stays in force while any
  `DialogWindowHardwareKeyEffect` in that window is composed, even when the one that decorated the
  window leaves composition first.
- `kmptoolkit-logging`: a sink's `CancellationException` is rethrown only after every sink has been
  offered the event.
- `kmptoolkit-systembars`: `release()` is atomic with concurrent pushes (ids are never reused),
  icon-style samples never overlap, and a new `onSampled` lambda no longer restarts sampling.
- `kmptoolkit-location`: on Android, `observeLocation()` sends the cached fix before registering its
  listeners, so a live fix is never overtaken by an older cached one; on iOS,
  `withSystemServicesPrompt` keeps at most one alert request pending.

## [3.0.0]

### Breaking

- `kmptoolkit-storage`: the API is now `suspend`. `KeyValueStorage.get`, `put`, `remove` and
  `clear`, `getStringOrNull`, every typed accessor (`getInt`, `putBoolean`, `getLongOr`, …) and
  `DeviceIdProvider.current()` are `suspend` functions, and each is main-safe: the store runs its
  own `SharedPreferences`, Keystore, `NSUserDefaults`, Keychain or file I/O on `Dispatchers.IO`.
  Call sites move into a coroutine; a custom `KeyValueStorage` implementation marks its four
  overrides `suspend`. `InMemoryKeyValueStorage` in `kmptoolkit-storage-testing` follows, so tests
  that use it run inside `runTest`. The module now depends on `kotlinx-coroutines-core`
  (as `implementation`; no coroutines type is in a public signature).

- `kmptoolkit-permission`: redesigned around one `PermissionController` for every grant an app asks
  for. The `Permission` enum, `PermissionHandler`, `SpecialPermission` and
  `SpecialPermissionHandler` are replaced by `PermissionController` and an open `Permission` base
  with three sealed families: `CommonPermission` (both platforms, neutral names),
  `AndroidPermission` (runtime permissions, special access, roles, device admins, and a raw
  `Runtime(name)`) and `IosPermission`. Android-only accesses no longer sit in common code.
  - `createPermissionHandler*` and `createSpecialPermissionHandler*` become one
    `createPermissionController(context, storage, activityAccess, host, systemScreenLauncher,
    config, logger)` on Android and `createPermissionController(options, logger)` on iOS.
    `PermissionConfig` becomes `AndroidPermissionConfig`; `AppDetailsScreen` and
    `SpecialPermissionScreen` become `PermissionScreen(permission)`;
    `Denied(shouldShowRationale)` becomes `Denied(explainFirst)`.
  - `PermissionRequestHost` loses the single-string `launch`; the list `launch` and a new
    `launchForResult` are both abstract.
  - Status semantics: approximate location (both platforms) and provisional notifications (iOS)
    are now `Limited`, not `Granted` (use `isUsable`); iOS restricted is `Restricted`, not
    `PermanentlyDenied`; an undeclared permission, a missing iOS usage description or
    `BluetoothScan` without `neverForLocation` is `Unavailable` (on iOS this replaces the app being
    terminated); notifications below API 33 report `areNotificationsEnabled()` instead of always
    `Granted`.
  - A special-access request now waits until the user is back in the app and returns the re-read
    status. Its default launcher is `callerTask`; a kiosk app passes `SeparateTask`.
  - Stored 2.x refusal flags keep their keys and are read unchanged.
  - `kmptoolkit-permission-testing`: `RecordingPermissionHandler` and
    `RecordingSpecialPermissionHandler` are replaced by `FakePermissionController`.
  - The module now depends on `kmptoolkit-core` (`api`) and `androidx.activity`.
  See `docs/kmptoolkit-permission/07-migrating-to-3.md`.
- `kmptoolkit-notification`: `createNotifier` takes a `PermissionController`. On Android,
  notifications are posted under the string id as the platform tag instead of untagged under its
  hash. `notificationIdOf` is removed: a foreground service names its `startForeground` number with
  `ForegroundNotificationId(id, platformId)`, which `buildForegroundNotification` takes and
  `createNotifier(…, foregroundIds)` registers, so `post` and `cancel` of that id update the
  service's notification in place. Until 4.0, `cancel(id)` and the first `post(id)` per process also
  remove the copy 2.x left under the old hashed number — see "Updating from 2.x" in the guide.
- `kmptoolkit-uploader`: the executor contract and the store SPI changed to close its races.
  `UploaderEngine.settle(id, claim, result)` takes the claim from the new `AttemptContext.claim`;
  `Failed` and `Park` from a superseded claim are ignored, `Delivered` and `Drop` apply from any
  claim. `UploaderItem` gains `claim`. In `UploaderStore`, `markInFlight` is a compare-and-set on
  the expected state and claim and returns `Boolean`; `recordFailure` always takes the expected
  state and claim plus a `FailureDisposition`; `park` is guarded the same way and returns
  `Boolean`; `insertReplace` returns the item it superseded and `deleteByTag` the items it removed.
  Every applied guarded write advances `claim` by one. Custom stores need a `claim` column — see
  `docs/kmptoolkit-uploader/07-custom-store.md`. `UploaderStoreContract` runs 46 checks.
- `kmptoolkit-uploader-sqldelight`: schema version 2 adds the `claim` column. Standalone storage
  migrates itself; a shared-driver consumer runs `uploaderDatabaseSchema.migrate(driver, 1, 2)`
  from a migration of their own.
- `kmptoolkit-audio-recorder`: a generated file name is now
  `<prefix>_<epochMillis>_<token>.<ext>`, with a 32-hex-character random token (see Fixed).
- `kmptoolkit-flashlight`: all `Flashlight` instances in a process share one blink loop, so any
  instance's `start` or `stop` drives the one torch (see Fixed).
- `kmptoolkit-activity`: an `addOnActivityResumedListener` subscription made off the main thread is
  told about the current activity by a post to the main thread, no longer synchronously on the
  caller's thread (see Fixed).
- `kmptoolkit-systembars`: on iOS, `setKeepScreenOn(false)` restores the idle-timer value the app
  had before the first hold instead of always writing `false`. The module now depends on
  `kmptoolkit-core` on iOS.

### Added

- `kmptoolkit-permission`: `PermissionStatus.Limited`, `Restricted` and `Unavailable`, `isUsable`,
  and flow states `Limited` and `Unavailable`; batch `request(Collection)` sharing one Android
  dialog; serialized requests; `observe` and `openSettings(permission)`;
  `ActivityResultPermissionRequestHost`, which needs no wiring and survives activity recreation.
  New entries — common: `PhotoLibrary`, `PhotoLibraryAdd`, `Contacts`, `Calendar`,
  `CalendarWriteOnly`, `MotionActivity`, `BluetoothAdvertise`; Android: `NearbyWifiDevices`,
  `LocalNetwork`, `BodySensors`, `BodySensorsBackground`, `Runtime(name)`, `Role` / `Role.Home`,
  `DeviceAdmin`, `AccessibilityService`; iOS: `Tracking`, `SpeechRecognition`, `Reminders`,
  `FocusStatus`, `IosPermissionOptions` (Provisional, CriticalAlert) and
  `presentLimitedPhotoLibraryPicker`.
- `kmptoolkit-core`: `SharedIdleTimer` (iOS, `@ToolkitInternalApi`), a reference-counted holder of
  `UIApplication.idleTimerDisabled` shared by the suite's modules.

### Changed

- The Android target of every module is built with the Android Gradle Plugin's Kotlin
  Multiplatform library plugin (`com.android.kotlin.multiplatform.library`) instead of
  `com.android.library`. Coordinates (`kmptoolkit-<module>-android`), dependencies and scopes are
  unchanged; the Android publication now has a single variant (Gradle module metadata names it
  `android*` instead of `release*` and adds `org.gradle.libraryelements=aar`).
- Dependency updates that reach a consumer's graph: Kotlin 2.4.20 → 2.4.21, Compose Multiplatform
  1.12.1 → 1.12.2 (Compose modules), SQLDelight 2.4.0 → 2.4.1 (`kmptoolkit-uploader-sqldelight`).
  OpenJFX 27 → 27.0.1 is `compileOnly` in `kmptoolkit-video-player-javafx`; apps using that engine
  should supply 27.0.1. `kmptoolkit-logging-overlay` pins Compose Multiplatform material3 to 1.9.0,
  the latest stable.
- Consumer minimums, now stated explicitly: Kotlin 2.4.21 for iOS consumers (Kotlin/Native
  libraries are not forward compatible) and Kotlin 2.4 for Android/JVM; iOS deployment target
  15.0; Android compileSdk 37 and Android Gradle Plugin 9.1.0 (required by androidx.core 1.19.1),
  minSdk 24; desktop JDK 21 — the `jvm` bytecode level is now pinned to 21 rather than inherited
  from the build JDK — and JDK 25 for `kmptoolkit-video-player-javafx`.
- Build: Gradle 9.8.2; Android host tests run with `testAndroidHostTest` (was
  `testDebugUnitTest`); the unused kotlinx-serialization and atomicfu catalog entries are removed.

### Fixed

- `kmptoolkit-storage`: concurrent first use of a `SecureKeyValueStorage` on Android could make
  stored values permanently unreadable. Looking the Keystore alias up and generating its key were
  two unordered calls, and generating under an existing alias replaces it, so two overlapping first
  uses — two coroutines, or two stores over the same `StorageConfig` — could each generate a key;
  values encrypted under the replaced one read back as `Undecryptable`, and a store left holding
  the replaced key kept writing under it until the process ended. Recovery from an unrecoverable
  key (a changed device lock) had the same race and could delete the fresh replacement key. Key
  resolution now runs under one process-wide `Mutex` and re-checks the Keystore inside it. Present
  since 1.0.0. A second *process* of the same app is still not ordered — see
  `docs/kmptoolkit-storage/05-platform-notes.md`.
- `kmptoolkit-storage`: concurrent first calls to `DeviceIdProvider.current()` could each generate
  and return a different id while only one was persisted. The read-generate-write now runs under a
  process-wide `Mutex`, so every caller gets the stored id.
- `kmptoolkit-storage`: on iOS, two concurrent first `put`s of the same key in the secure store made
  one of them fail with `OperationFailed` (`errSecDuplicateItem`). The losing write now updates the
  item instead.
- `kmptoolkit-permission`: Android `check` was documented as never suspending, but it suspends
  since the storage became `suspend`. `InstallPackages` below API 26 reported the device-wide
  "Unknown sources" switch, `FullScreenNotifications` below API 34 reported granted without the
  manifest declaration, and `MediaManagement` below API 31 reported granted; all now read
  `Unavailable`. Requesting an iOS permission without its usage description terminated the app.
- `kmptoolkit-downloader`: two commits of the same unit no longer run at once — the group and
  per-unit surfaces, two engines over one storage directory, or a self-committing
  `BackgroundResourceDownloader` could overlap. On iOS the late commit could delete the resource
  just committed; on Android it failed with a spurious `DownloadError.Storage`; archive commits
  wiped each other's staging extraction. Commits are serialized per unit across the process, a
  commit that finds the unit already committed returns normally, and on iOS a file is moved into
  place with an atomic rename.
- `kmptoolkit-uploader`: a drain claim made from a stale read no longer revives an item a detached
  executor just parked or re-hands one under a fresh backoff gate, and a failure that spends the
  retry budget parks or drops in the same write, so no attempt goes out past `maxAttempts`. A late
  `settle(Park)` landing while the drain runs the item itself is no longer overwritten back to
  pending. An executor whose lease expired and whose item was re-handed can no longer return it to
  pending, spend an attempt or park it by reporting late, and a duplicate `Failed` after a re-claim
  in the same millisecond no longer spends a second attempt.
- `kmptoolkit-uploader`: an enqueue landing between the drain's empty read and its `cancelWake` no
  longer has its platform wake cancelled; the drain re-checks and re-arms.
- `kmptoolkit-uploader`: `cancelByTag` and `ConflictPolicy.REPLACE` now cancel the upload of every
  row they remove, including one enqueued and handed off while they ran. Before, it could reach the
  server after a logout wipe.
- `kmptoolkit-uploader`: on iOS, a hand-off no longer joins a background session that a relaunch
  opened only to drain events, or one whose upload had already completed. Either left the item in
  flight, uploading nothing, until its lease expired.
- `kmptoolkit-scheduler`: on Android, two overlapping `schedule` calls for one id no longer arm one
  call's time carrying the other call's content. Arming and cancelling are serialized process-wide.
- `kmptoolkit-audio-recorder`: two recorders preparing in the same millisecond, or after the clock
  stepped back, no longer share one generated path — the second recording overwrote the first, and
  a failed second `prepare()` deleted the first's file.
- `kmptoolkit-audio-recorder`: on iOS, one recorder's `release()` (or a second recorder's failed
  `prepare()`) no longer deactivates the shared `AVAudioSession` under another recorder that is
  still recording. Session holds are counted across the process.
- `kmptoolkit-video-player`, `kmptoolkit-video-player-javafx`, `kmptoolkit-video-player-vlcj`:
  transport calls reach ExoPlayer, AVPlayer, JavaFX and libVLC in the order they were made across
  threads. A `play()` from a background thread followed by a main-thread `pause()` could leave the
  video playing while `stateFlow` said `Paused`, and volume, speed or repeat could end on the older
  value. With VLCJ and `RepeatMode.One`, a `pause()` right after the restart at the end was dropped.
- `kmptoolkit-video-player-javafx`: after `seekTo`, the position no longer briefly jumps back to the
  old playhead. A playhead reading JavaFX took before the seek but delivered after it, because the FX
  thread was busy, overwrote the seek target, and a `seekForward()` or `seekBackward()` in that
  window stepped from the wrong position. The engine keeps the target until the seek has reached the
  FX thread.
- `kmptoolkit-systembars`, `kmptoolkit-video-player-compose`: on iOS, `ScreenWakeLockController`
  and playing video surfaces no longer undo each other's `UIApplication.idleTimerDisabled` — the
  screen could sleep mid-video, or the timer stay disabled for good. Both share
  `SharedIdleTimer`.
- `kmptoolkit-systembars`: `ScreenWakeLockController.setKeepScreenOn` lets the most recent call win
  on Android and iOS; a background `true` still queued for the main thread could land after the
  screen's `false`. On Android, a `SystemBarsController` change made from a background thread no
  longer overwrites a later main-thread change on the window.
- `kmptoolkit-activity`: the immediate replay to a listener added off the main thread now runs on
  the main thread, as documented, and a `release()` racing a new subscription can no longer leave
  that listener held.
- `kmptoolkit-notification`: on Android, two ids with equal `String.hashCode()` (for example `"Aa"`
  and `"BB"`) no longer replace or cancel each other, and their taps and buttons no longer share one
  `PendingIntent` carrying the other's extras. The channel cache is safe under concurrent `post`s.
- `kmptoolkit-language`: `AppLanguageHolder.setLanguage` is serialized across threads; two
  concurrent calls could leave the platform locale on one language while `languageFlow` and the
  persisted choice recorded the other. `onLanguageChanged` may itself call `setLanguage`.
- `kmptoolkit-flashlight`: a `stop()` on one `Flashlight` instance now ends a pattern another
  instance started; before, the torch kept blinking.
- `kmptoolkit-biometric`: the `launchEnrollment` throttle is one atomic check-and-stamp; two calls
  on two threads could both open a settings screen.
- `kmptoolkit-location-testing`: `FakeLocationProvider` is documented as single-threaded, like the
  suite's other doubles; only `emit` is safe from any thread.
- Release process: 2.2.1 added public API (four `SpecialPermission` entries) in a patch release,
  which semver reserves for a minor. `RELEASING.md` now checks that any new public symbol, including
  a new enum or sealed entry, ships in at least a minor.

## [2.2.1]

### Added

- `kmptoolkit-permission`: four `SpecialPermission` entries — `MEDIA_MANAGEMENT` (`MANAGE_MEDIA`,
  API 31+), `INSTALL_UNKNOWN_APPS` (`REQUEST_INSTALL_PACKAGES`; the device-wide "Unknown sources"
  switch below API 26), `FULL_SCREEN_INTENT` (`USE_FULL_SCREEN_INTENT`, API 34+) and
  `KEEP_PERMISSIONS_WHEN_UNUSED` (the exemption from removing an unused app's permissions, API 30+).
  Each is reported granted below the API level that introduces it, and is a no-op on iOS. An
  exhaustive `when` over `SpecialPermission` without an `else` needs the four new branches.

## [2.2.0]

### Breaking

- `kmptoolkit-audio-recorder`: a recording the system ends is no longer silent, which changes
  four promises of the API.
  - **`RecorderState` has a new case, `Interrupted`, and `RecorderError` a new case,
    `RecordingLost`.** The ABI dump only gains symbols, but an exhaustive `when` over either sealed
    type without an `else` stops compiling: add a branch (for `Interrupted`, usually the same
    handling as `Completed` plus the reason; for `RecordingLost`, offer the file or discard it).
  - **`RecorderError.EngineFailure` is only ever the answer to a call you made.** It used to be
    documented as the place a failure that happened on its own "surfaces at the next operation".
    That failure is now reported through `Interrupted` or `RecordingLost` while it happens, and
    `state` no longer stays `Recording` with `elapsed` still counting. Code that waited for a
    `stop()` failure to learn the microphone was gone now sees a state change first.
  - **`cancel()` is legal from a `Failed` that carries an output path** (a failed `stop()`, a lost
    recording) and deletes that file; it was illegal there. From a `Failed` without a path it is
    still illegal. `stop()` from `Interrupted` returns the interrupted file and leaves the state
    unchanged; `cancel()` from `Interrupted` deletes it.
  - **`IllegalState` can now come from a race with a system event, not only from a wiring bug.**
    While the system's event is being finalized, `state` still shows the old value and `start()`,
    `pause()` and `resume()` return `IllegalState` with it; `prepare()`, `stop()` and `cancel()`
    suspend (never blocking a thread) until the finalization lands and then decide again — so a
    `stop()` that waited for a finalization that *failed* returns `IllegalState` against
    `Failed(RecordingLost)`. Do not `error(...)` on `IllegalState` from a user's tap; read `state`.

- **`ToolkitInternalApi` moved to `kmptoolkit-core`.** The marker is now
  `io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi`; it is no longer declared in
  `kmptoolkit-video-player` (the old import stops compiling). Only code that opted in to the
  suite's internal API is affected — change the import. `kmptoolkit-video-player` exposes the new
  artifact as an `api` dependency, so no build file change is needed.

### Added

- `kmptoolkit-core`, a new **internal support artifact** (`io.github.jamal-wia:kmptoolkit-core`,
  Android, iOS and `jvm`, on the BOM). It holds `@ToolkitInternalApi` and the `StateMachineLock`
  that the players and the recorder serialize their state machines with, previously copied into
  `kmptoolkit-video-player` and `kmptoolkit-audio-player`. It is not meant to be depended on
  directly and carries no compatibility promise. Docs: `docs/kmptoolkit-core/`.
- `kmptoolkit-audio-recorder`: **a recording the system ends is reported while it happens.**
  - `RecorderState.Interrupted(recording, reason)`: the system ended the recording and the file
    is finalized and playable, with `elapsed` frozen at the moment it ended. `RecorderError.
    RecordingLost(reason, cause)`, carried by `Failed` with the file kept: it could not be
    finalized (an unfinalized M4A usually does not play, an AAC file usually does).
    `InterruptionReason`: `AudioSessionInterrupted` (iOS interruption, or suspension),
    `MicrophoneSilenced` (Android 10+, debounced about 400 ms, `elapsed` frozen where the
    silencing began; not detectable on API 24-28), `StorageLow`, `EngineDied(platformCode)`.
    Only the first event of a recording counts, and a `stop()` or `cancel()` that is already
    running always wins over an event.
  - A **free-space watchdog**: while recording, free space is polled at most every two seconds and
    a volume below the reserve (half of `minimumFreeSpaceBytes`, between 2 MiB and the minimum
    itself) ends the recording as `Interrupted(StorageLow)` while the file can still be closed;
    on Android `setMaxFileSize` is set from the free space at prepare as a backstop.
    `minimumFreeSpaceBytes = 0` switches both off. The poll interval counts per recording, not per
    resume. A size limit below 64 KiB is not passed to `MediaRecorder` (it rejects anything up to
    1 KiB); the limit is omitted and `prepare()` still fails only below the minimum.
  - `FakeAudioRecorder.simulateInterruption(reason)` and `simulateRecordingLost(reason, cause)`
    (`kmptoolkit-audio-recorder-testing`), and the fake follows the new transition rows.
  - Docs: the new section "When the system ends a recording" in
    `docs/kmptoolkit-audio-recorder/03-guide.md` and `05-platform-notes.md`.

### Changed

- `kmptoolkit-audio-player` and `kmptoolkit-video-player` use the shared `StateMachineLock`
  instead of a private copy each. No behaviour change.
- `kmptoolkit-audio-recorder`: every state transition now runs under that same lock and the slow
  finalize of a long file no longer holds it, so a system event, a tick and your own call cannot
  interleave. The public contract is unchanged: call the operations from one thread.
- `kmptoolkit-audio-recorder`: a `Job` in the `coroutineContext` given to `createAudioRecorder` is
  now ignored (see Fixed).

### Fixed

- `kmptoolkit-audio-recorder`: `release()` while `stop()` was finalizing the file drove the platform
  recorder a second time, from the caller's thread, concurrently with the first. The finalizer now
  owns the recorder until it is done. The recording a `stop()` returned in that case also reported a
  zero duration.
- `kmptoolkit-audio-recorder` (iOS): a recording interrupted by a call used to end as `Completed`
  with a duration that included the interruption; it is now `Interrupted`.
- `kmptoolkit-audio-recorder` (iOS): errors from `AVAudioSession.setCategory` / `setActive` were
  discarded and surfaced later as a misleading `START` failure; they now fail `prepare()` as
  `EngineFailure(PREPARE, cause)`.
- `kmptoolkit-audio-recorder` (iOS): `release()` only stopped the recorder when it reported
  `recording == true`, which an interrupted recorder does not; it now always stops it.
- `kmptoolkit-audio-recorder`: a late tick or level sample computed before a pause, stop or release
  could overwrite what that transition published; they are now published under the lock.

- A `Job` in the `coroutineContext` given to `createAudioPlayer`, `createVideoPlayer` or
  `createAudioRecorder` replaced the instance's own job: `release()` then cancelled the caller's job
  (a `viewModelScope`'s, say), and in the recorder a finalization racing `release()` was cancelled
  with it, skipping `engine.release()` — the microphone stayed held and every later call waited
  forever. The context's `Job` is now ignored; the rest of the context is used as before.
- `kmptoolkit-audio-recorder`: a `stop()` or system finalization could leave the recorder waiting
  forever if anything unexpected escaped it; all finalizations now go through one path that always
  ends. `release()` no longer touches the platform recorder while a finalization owns it, and the
  platform recorder is released on the worker, not under the lock.
- `kmptoolkit-audio-recorder`: the free-space poll restarted on every resume, so a user pausing and
  resuming more often than the poll interval was never checked; it now counts per recording.
- `kmptoolkit-audio-recorder`: a free-space size limit of up to 1 KiB made `setMaxFileSize` throw
  and surfaced as a misleading `EngineFailure(PREPARE)`; such a limit is no longer set.
- `kmptoolkit-audio-recorder` (Android, iOS): platform callbacks are delivered only to the listener
  they were installed for; replacing a listener no longer registers a second silence monitor or
  leaves a late callback reaching the new listener, and an old iOS recorder's late callback cannot
  end a newer recording.
- `kmptoolkit-audio-recorder-testing`: a scripted `stop()` failure now keeps the path on `Failed`,
  so `cancel()` deletes the file as with the real recorder.
- `kmptoolkit-core`: when the block of `StateMachineLock.exclusive` throws, a failure of a queued
  action no longer replaces it; it is attached as a suppressed exception.

## [2.1.0]

### Breaking

- `kmptoolkit-downloader`: the hash a download must have can now come from the backend, per
  download, instead of only from a constant on the unit. Three public signatures change.
  - **`DownloadUnit.sha256` is `Sha256?`**, not `String?`. Build the value with
    `Sha256.parse("...")` (throws for anything that is not 64 hex digits, either case) or
    `Sha256.parseOrNull(...)`.
  - **`DownloadUrlResolver.resolve(unit)` returns `ResolvedDownload`**, not `String`:
    `ResolvedDownload(url, expectedSha256 = null)`. Wrap the URL you returned before.
  - **`DownloaderStorage.beginTempFile(unit, expectedSha256)` is a new abstract member** with no
    default. A custom `DownloaderStorage` must implement it: record the hash, replace the temp
    file, throw `IllegalArgumentException` before any side effect when it contradicts
    `unit.sha256`, and check the recorded hash at `commitResource`.
  - **`FakeDownloaderStorage` (`kmptoolkit-downloader-testing`) is strict:**
    `markTempFileComplete` throws `IllegalStateException` for a unit that has had no
    `beginTempFile` since its last delete or commit, so a test of a downloader that forgot the
    call fails. It is deliberately stricter than the real storages. `commitResource` now also
    throws unless the unit's state is `Complete`, and consumes the temp file on success.
  - A `BackgroundResourceDownloader` should call `storage.beginTempFile(unit,
    resolved.expectedSha256)` whenever it starts writing from byte zero. It must not call it on a
    `206` resume — it deletes the partial file — and without the call only `DownloadUnit.sha256` is
    checked. On iOS it calls it before every task, including one resumed from `resumeData` (with
    the hash saved beside it). The decision table is in
    `docs/kmptoolkit-downloader/07-background-downloader.md`.
  - Imports: `Sha256` is in `io.github.jamal_wia.kmptoolkit.downloader`, `ResolvedDownload` in
    `io.github.jamal_wia.kmptoolkit.downloader.spi`.

### Added

- `kmptoolkit-downloader`: `Sha256`, a validated digest type with `parse` and `parseOrNull`.
- `kmptoolkit-downloader`: `ResolvedDownload`, whose `toString` leaves out the URL's query and
  fragment because those usually carry a signature, and masks any `user:password@` part.
- `kmptoolkit-downloader`: `DownloaderStorage.beginTempFile`. The expected hash is persisted next
  to the temp file (`tmp/expect/<id>.sha256`), survives process death, is checked at commit together with
  `DownloadUnit.sha256` before extraction, a database check or the move, and is dropped with the
  temp file. A record that exists but cannot be read fails as an integrity failure, never as "no
  check". A partial with no record resumes and is checked against `unit.sha256` only.
- `kmptoolkit-downloader-testing`: `FakeDownloaderStorage.beganWith`, `begunIds` and
  `failIntegrityFor`.

### Fixed

- `kmptoolkit-downloader` (iOS): committing a ZIP archive whose `relativePath` is nested (for example
  `archives/pages`) failed when the parent directory did not exist yet, because neither the move nor
  the copy fallback creates it. The parent is now created first, as Android already did.
- `kmptoolkit-downloader`: a malformed constant hash (a `DownloadUnit.sha256` getter that throws)
  is detected before the transfer, as `DownloadError.Unknown`, instead of after the whole download
  on every attempt. A transfer already running for that unit is cancelled; its temp file is kept.
- `kmptoolkit-downloader` (iOS): when moving a finished download into place failed and the copy
  fallback failed too, the temp file was deleted anyway, silently destroying the download. The
  copy's result is now checked: the temp file and its hash record stay and the commit throws.
- `kmptoolkit-downloader` (iOS): an I/O error while hashing a file is now reported as such instead
  of as a hash mismatch.

## [2.0.0]

### Breaking

- `kmptoolkit-downloader`: a partial temp file is no longer committed as the finished resource.
  Before, `ensureAvailable` treated any non-empty temp file as a download that had finished in the
  background, so a transfer cut short by process death, a reboot or a swipe-away was moved into
  place truncated, reported `Completed`, and stayed "available" for good. Worse, a second
  `ensureAvailable` while a transfer was still running (its first caller had left the screen)
  committed the file the transfer was still writing. Partial and complete are now separate states,
  and only a complete one is committed without a transfer; a partial one is attached to while its
  transfer runs and resumed otherwise.
  - **`DownloaderStorage`:** `isTempFileAvailable(unit): Boolean` is replaced by
    `tempFileState(unit): TempFileState` (`None`, `Partial`, `Complete`), and
    `markTempFileComplete(unit)` is new — an atomic rename to `tmp/<id>.<ext>.complete`.
    `commitResource` now commits only a complete temp file and throws otherwise. A custom
    `DownloaderStorage` must implement both new members and keep that rule.
  - **`BackgroundResourceDownloader` implementations** should call
    `storage.markTempFileComplete(unit)` once the last byte is written and the size checked against
    `Content-Length` / `Content-Range`, before emitting `FileReady` or committing on their own. The
    engine marks the file itself when it observes `FileReady`, so an implementation that does not
    keeps working while the app watches, but a transfer that finished while the process was dead is
    resumed instead of committed. Its resume asks for a range starting at the file's full size;
    treat the server's `416` with a matching `Content-Range` total as "already complete". See
    `docs/kmptoolkit-downloader/07-background-downloader.md`.
  - **A temp file left by 1.x is partial**, since 1.x never recorded completeness, and is resumed
    rather than committed on the first `ensureAvailable` after the upgrade.
  - **`DownloadError.Corrupted`** is a new subclass, so an exhaustive `when` needs a branch for it.
  - **`ResourceFormat.SqliteDatabase` failures** are now `DownloadError.Corrupted` after one fresh
    download, not `DownloadError.Storage` at once.
- `kmptoolkit-downloader-testing`: `FakeDownloaderStorage` implements the new members; what a
  transfer left behind is set through `FakeDownloaderStorage.tempFileStates`.
- `kmptoolkit-uploader`: `UploadTransport.cancelAll()` is replaced by `cancel(itemId)`. One transport
  is shared by every handler an app registers on it, so withdrawing one upload with `cancelAll()`
  also aborted the others' — and on Android it did not even stop the bytes of the one meant (see
  Fixed). There is no "cancel everything" replacement; withdraw by item or by tag through `Uploader`
  (see Added). A custom `UploadTransport` must implement `cancel(itemId)` for its own deliveries,
  as a no-op for an id it is not running.
- `kmptoolkit-uploader`: `Uploader` has two new members, `cancel` and `cancelByTag`, so a custom
  `Uploader` implementation or fake must implement them.
- `kmptoolkit-uploader-testing`: `RecordingUploadTransport.cancelAllCount` is replaced by `cancels`,
  the ids passed to `cancel` in order.

### Added

- `kmptoolkit-downloader`: `DownloadUnit.sha256`, an optional SHA-256 (64 hex digits, default
  `null`) that `commitResource` checks before anything reaches the final path — before the move,
  the extraction or the database check. It catches what completeness cannot: bytes damaged in
  transit, and a file stitched from two versions when the remote resource changed between an
  interrupted transfer and its resume. Hashing streams the file once, at commit.
- `kmptoolkit-downloader`: `ResourceIntegrityException`, thrown by `commitResource` (after it
  deletes the temp file) when the bytes fail a check. The engine answers it with one fresh download
  and then reports `DownloadError.Corrupted`; any other commit failure is still
  `DownloadError.Storage` at once, without spending a download.

- `kmptoolkit-uploader`: `Uploader.cancel(id)` withdraws one queued effect in any state. It deletes the
  row, then cancels that one upload on its `UploadHandler`'s transport. Other items are untouched,
  other handlers' uploads included. `Uploader.cancelByTag(tag)` does the same for every item of a tag
  and replaces `UploaderStore.deleteByTag` as the logout wipe, which removed rows but left their
  uploads running. A hand-off racing a withdrawal cancels its own upload once it sees the row gone
  after launching, so neither order lets a withdrawn item upload. Bytes the server already received
  cannot be recalled; the docs say so.
- `kmptoolkit-uploader`: an Android upload stops mid-body when it is cancelled.
  `createBackgroundUploadTransport` on iOS cancels an upload an earlier process started, reaching it
  through its session identifier.
- `kmptoolkit-uploader-testing`: `FakeUploader.cancelled` and `FakeUploader.cancelledByTag` record
  withdrawals.

### Fixed

- `kmptoolkit-uploader`: a cancelled Android upload no longer reaches the server. The multipart write
  is blocking and never checked its job, so cancelling a running `WorkManager` job sent the whole body
  and discarded only the outcome. The item, still owed, was then uploaded a second time after its
  lease. The write now checks its job before every chunk. A cancelled transfer drops the connection
  without closing the chunked body, so the server never receives a complete request.
- `kmptoolkit-uploader`: `ConflictPolicy.REPLACE` cancels the superseded item's running upload instead
  of letting it finish next to the new one.
- `kmptoolkit-uploader`: the iOS transport no longer risks creating an upload task on a session that a
  concurrent cancellation has just invalidated, an Objective-C exception. The check and the task
  creation now happen under the same lock.
- `kmptoolkit-downloader`: a `ResourceFormat.SqliteDatabase` check now rejects bytes that are not a
  database. It only opened the file, and SQLite reads the header lazily, so garbage opened fine and
  was committed whenever the unit named no row-count table. The check now reads the schema.

## [1.9.0]

### Added

- `kmptoolkit-audio-recorder`: `AudioRecorder.level`, a `StateFlow<Float>` with the live peak input
  loudness, `0f` to `1f`, and `AudioRecorder.levelSamples`, a `Flow<Float>` of every sample the
  meter takes, for a messenger-style waveform. Use `level` for a pulsing indicator and
  `levelSamples` for a waveform: `level` is a `StateFlow`, so it conflates equal consecutive values
  and in silence emits nothing, while `levelSamples` keeps delivering the repeated `0f`, one per
  interval. The scale is linear in decibels between `AudioRecorderConfig.levelFloorDbfs` (`0f`) and
  full scale (`1f`), so quiet speech is visible, and each value is the loudest input since the
  previous one. It is not smoothed.
  - **Measured only while `Recording` and collected.** A recorder whose `level` and `levelSamples`
    nobody collects does no metering work, and the two share one meter when both are collected.
    `level` is `0f` in every other state, and as soon as the last collector leaves. Pause, stop,
    cancel, and release reset it at once, `levelSamples` emits nothing while paused, and nothing is
    published after `release()`. `levelSamples` is hot and not replayed, and a collector more than
    64 samples behind loses the oldest.
  - **New settings:** `AudioRecorderConfig.levelUpdateInterval` (default 50 ms) and `levelFloorDbfs`
    (default `-50`), with `DEFAULT_LEVEL_UPDATE_INTERVAL` and `DEFAULT_LEVEL_FLOOR_DBFS`. They are
    appended after `minimumFreeSpaceBytes`, so existing positional calls keep compiling. There is no
    on/off flag: collecting `level` or `levelSamples` is the switch.
  - **Platforms:** Android reads `MediaRecorder.getMaxAmplitude()`; iOS enables `AVAudioRecorder`
    metering in `prepare()` and reads the loudest `peakPowerForChannel`. Both are peaks, so the same
    voice draws the same bar. See `docs/kmptoolkit-audio-recorder/05-platform-notes.md`.
- `kmptoolkit-audio-recorder-testing`: `FakeAudioRecorder.level`, `FakeAudioRecorder.levelSamples`
  and `FakeAudioRecorder.emitLevel`, which sets the level and emits a sample (repeats included)
  while the fake is `Recording` and is ignored otherwise. The level returns to `0f` on every
  transition out of `Recording`, without emitting a sample. `levelSamples` is hot, as on the real
  recorder.

`AudioRecorder` gains two members: a class implementing it outside this library (rather than using
`FakeAudioRecorder`) must add `level` and `levelSamples`. Code compiled against 1.8.x must be
recompiled, because adding parameters to the data class `AudioRecorderConfig` changes the JVM
signatures of its constructor and `copy`; source code needs no change.

### Changed

- **`kmptoolkit-video-player-javafx` now requires OpenJFX 27 and JDK 25+ in the consuming app.**
  OpenJFX 27 is compiled with `--release 25`, so it cannot load on an older JDK. The artifact still
  declares OpenJFX `compileOnly`, so nothing changes in its POM; update the `javafx-*` jars you add
  yourself, and the JDK your app runs on. See
  `docs/kmptoolkit-video-player-javafx/02-getting-started.md`.
- Dependency updates that reach a consumer's resolved graph (old to new): Kotlin 2.4.10 to 2.4.20,
  Compose Multiplatform 1.11.1 to 1.12.1 (the Compose modules), AndroidX Core KTX 1.19.0 to 1.19.1
  (`kmptoolkit-hardware-keys`, `-notification`, `-permission`, `-systembars`), `androidx.media`
  1.7.0 to 1.8.0 (`kmptoolkit-notification`), WorkManager 2.11.2 to 2.12.0 (`kmptoolkit-uploader`),
  AndroidX SQLite 2.7.0 to 2.7.1 (`kmptoolkit-downloader`, iOS), SQLDelight 2.3.2 to 2.4.0
  (`kmptoolkit-uploader-sqldelight`). `androidx.biometric` stays at 1.2.0-alpha05.
- Compiling with Kotlin 2.4.20 adds a JVM no-argument constructor to the config classes whose
  parameters all have defaults and include a `Duration`: `AudioRecorderConfig`,
  `BiometricGateOptions`, `NotificationConfig`, `UploadTransportConfig`, `UploaderConfig` and
  `WorkManagerWakeConfig`. It is additive: Java callers can now write `new AudioRecorderConfig()`,
  and no existing signature changed.
- `kmptoolkit-uploader-sqldelight`: the generated `KmpToolkitUploaderDatabase.Companion` gains
  `allTableNames()`, added by SQLDelight 2.4.0 to every generated database. The class was already
  public API of this module; nothing existing changed.

## [1.8.0] - 2026-09-23

### Added

- `kmptoolkit-permission`: `Permission.BLUETOOTH_SCAN` — discovering nearby Bluetooth devices, with
  `check`, `request` and `observe` like every other entry. Its fold onto `PermissionStatus`:
  - **Android 12 (API 31) and later:** `BLUETOOTH_SCAN` alone. The manifest must declare it with
    `android:usesPermissionFlags="neverForLocation"`; without that flag Android withholds scan results
    from an app that lacks fine location, so the entry reads `NotDetermined`, a request shows nothing
    and records nothing, and the handler logs one warning naming the attribute. It never asks for
    location on these API levels.
  - **Below API 31:** scan results need location, so the entry *is* `LOCATION` — the same status, the
    location dialog on a request, and a refusal remembered as a refusal of `LOCATION`. It does not
    report `Granted` merely because there is no Bluetooth runtime grant there.
  - **iOS:** the same `CBManager` authorization as `BLUETOOTH_CONNECT`; a request of either updates an
    observation of the other.

  This reverses a documented decision: `05-platform-notes.md` listed Bluetooth scanning among the
  permissions with no mapping. **An exhaustive `when` over `Permission` needs the new entry**; the
  change is binary-compatible. See `docs/kmptoolkit-permission/05-platform-notes.md` for the manifest
  declarations, and the preconditions a status cannot report (Bluetooth and, below API 31, location
  services switched on).

## [1.7.1] - 2026-09-22

### Fixed

- `kmptoolkit-video-player` (iOS): a seek could be undone for a moment. `AVPlayer` finishes a seek
  asynchronously and answers its old `currentTime()` until then, and any refresh in that window —
  `play()` right after the rewind from `Completed`, or a position-poll tick after `seekTo` — read
  that old position back. `play()` from `Completed` could therefore report `Playing` at the end of the
  source instead of at 0, and a seek bar could jump back briefly after a seek. The seek target is now
  reported until `AVPlayer` has completed the seek. No API change.

## [1.7.0] - 2026-09-22

### Added

- `kmptoolkit-video-player` — a headless `VideoPlayer` for shared code, with the contract of
  `kmptoolkit-audio-player` (`prepare` suspends and never throws on failure, a newer `prepare`
  replaces an older one, transport calls outside a playable state are ignored, `release` is
  idempotent and final) plus what a video needs: `volumeFlow`/`isMutedFlow`, `RepeatMode`,
  `isBufferingFlow`, `bufferedPositionFlow` and `videoSizeFlow`. Sources are `Asset`, `File` and
  `Remote` with optional HTTP headers and a `RemoteFormat` hint (`Auto`, `Progressive`, `Hls` — the
  last for an HLS URL without `.m3u8`, which Android cannot recognise on its own); they are plain
  value classes, and `Remote.toString()` redacts header values. Every call is safe from any thread:
  calls, the position poll and the engine's events are serialized, so none overwrites a state
  another just wrote. `VideoPlayer` is implemented by the library only, enforced with
  `@SubclassOptInRequired(ToolkitInheritanceApi::class)`. One state machine in common code drives a
  `VideoPlaybackEngine`: Media3 ExoPlayer (with HLS) on Android and `AVPlayer` on iOS. The engine SPI
  is public for a consumer's own engine; its `release()` frees the loaded source, and its
  `dispose()` (a no-op by default) frees what the engine keeps across sources, called once from
  `VideoPlayer.release()`. Also publishes
  `jvm` so shared UI compiles for desktop; the desktop engine is a separate artifact. Media3 merges
  `ACCESS_NETWORK_STATE` and `WAKE_LOCK` into the consuming app's manifest; `INTERNET` stays the
  app's to declare.
- `kmptoolkit-video-player-testing` — `FakeVideoPlaybackEngine`, a scriptable engine for testing
  code that consumes `VideoPlayer` without a device or a video file.
- `kmptoolkit-video-player-compose` — `VideoPlayerSurface` (scale modes `Fit`/`Fill`/`Crop`,
  keep-screen-on while playing) on Android, iOS and desktop, and `VideoPlayer` with ready-made
  controls — tap to toggle, auto-hide, pause when the host goes to the background — whose every part
  can be replaced: a `controls` slot over `VideoControlsScope` (used freely; implementing it is
  opt-in with the core's `@ToolkitInheritanceApi`, as it may grow), public building blocks
  (`PlayPauseButton`, `VideoSeekBar`, `VideoTimeText`, `MuteButton`, `SpeedButton`,
  `FullscreenButton`, …), and plain `VideoControlsColors` / `VideoControlsDimensions` /
  `VideoControlsLabels` objects. No user-facing text: accessibility labels come from the app.
  `rememberVideoPlayer` creates, prepares and releases a player; `LocalVideoPlayerFactory` lets a
  desktop app choose its engine once at the root.
- `kmptoolkit-video-player-vlcj` — a JVM-only desktop engine on VLCJ: any format VLC plays, frames
  rendered into memory for the Compose surface; one libvlc instance per player, kept across sources
  and freed by `release()`. VLCJ is GPL-3.0 and needs VLC 3.x installed (or
  bundled) for the JVM's CPU architecture; `isVlcAvailable()` checks without crashing.
- `kmptoolkit-video-player-javafx` — a JVM-only desktop engine on JavaFX Media: nothing to install
  beyond the OpenJFX jars the app adds per OS (GPL-2.0 + Classpath Exception), fewer formats, and a
  higher CPU cost because frames are captured by off-screen snapshots.
- `kmptoolkit.library.jvm` — a build convention for JVM-only modules.
- `kmptoolkit-activity`: `SystemScreenLauncher`, the one place that decides how a module opens a
  system screen and which task it lands in. Presets: `SystemScreenLauncher.SeparateTask` (a task of
  its own, `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`) and
  `SystemScreenLauncher.callerTask(activityAccess)` (your task, from the resumed activity, falling
  back to a separate task when there is none or when called off the main thread). A launcher of your own receives one
  `SystemScreenRequest` per logical request — every candidate intent, the application context and an
  open `SystemScreenKind` — and `startFirstResolvable` tries the candidates in order. See
  `docs/kmptoolkit-activity/03-guide.md`.
- `kmptoolkit-location` (Android): `createLocationProviderWithLauncher(context, systemScreenLauncher,
  config, logger)`, whose `openLocationSettings()` hands the screen to the `SystemScreenLauncher` you
  pass — once per call, with its candidates and the new kind `LocationSettingsScreen`; `false` or a
  throwing launcher is logged as "could not open" and never reaches the caller. For an ordinary app,
  `createLocationProviderWithLauncher(context, SystemScreenLauncher.callerTask(activityAccess))` opens
  the screen on the app's own task, so Back returns to it. `createLocationProvider(context, config,
  logger)` keeps its signature (and stays the only function of that name, so
  `::createLocationProvider` still resolves) and opens the screen with `SeparateTask`. The module now
  depends on `kmptoolkit-activity` on Android. Binary- and source-compatible. See
  `docs/kmptoolkit-location/03-guide.md`.
- `kmptoolkit-permission` (Android): a choice of how Settings screens open, through
  `SystemScreenLauncher`. Two new factories take the launcher:
  `createSpecialPermissionHandlerWithLauncher(context, systemScreenLauncher, logger = NoopLogger)` and
  `createPermissionHandlerWithLauncher(context, host, storage, activityAccess, systemScreenLauncher,
  config = PermissionConfig(), logger = NoopLogger)`. They have names of their own, so the existing
  `createSpecialPermissionHandler` stays a single function and `::createSpecialPermissionHandler`
  still compiles as an untyped reference. For an ordinary app,
  `createSpecialPermissionHandlerWithLauncher(context, SystemScreenLauncher.callerTask(activityAccess))`
  opens the special-permission screens on the app's own task. New kinds
  `SpecialPermissionScreen(permission)` and `AppDetailsScreen`. The existing factories keep their
  signatures and defaults: `createSpecialPermissionHandler(context, logger)` uses `SeparateTask`, and
  both `createPermissionHandler` overloads use `callerTask` on their tracker. See
  `docs/kmptoolkit-permission/03-guide.md`.
- `kmptoolkit-biometric` (Android): `createBiometricGateWithLauncher(context, systemScreenLauncher,
  config, options, activityAccess)`, which takes a `SystemScreenLauncher` for `launchEnrollment()`
  and optionally the app's `ActivityAccess` to host the prompt; for an ordinary (non-kiosk) app,
  `SystemScreenLauncher.callerTask(activityAccess)` opens enrolment on the app's own task. And
  `createBiometricGate(context, activityAccess, config, options)`, which hosts the prompt through the
  app's tracker, its `isTracked` predicate honoured, and still opens enrolment with `SeparateTask`.
  `BiometricEnrollmentScreen` is the `SystemScreenKind` of the enrolment request. Each non-throttled
  `launchEnrollment()` calls the launcher once with one or more candidate screens; `false` or a
  throwing launcher is reported as `UNAVAILABLE`, and a throttled call never reaches it. A kiosk app
  opens its lock-task allowlist window from such a launcher — see
  `docs/kmptoolkit-biometric/03-guide.md`. The module now exposes `kmptoolkit-activity` as an `api`
  dependency on Android, and hosts its prompt through that module's activity tracker instead of a
  private copy of it.

### Changed

- `kmptoolkit-permission` (Android), behaviour of the existing factories:
  - Each different Settings screen now gets its own task and Recents card; 1.6.0 collapsed them into
    one Settings card. Opening the same screen again brings its task forward with the screen on top.
    The battery-optimisation dialog (`IGNORE_BATTERY_OPTIMIZATIONS`) gets its own task too, instead
    of joining the Settings task.
  - `requestViaSettings` tries a fallback screen when the device lacks the app's own page: exact
    alarms, overlay and write-settings the same action without the `package:` URI, all-files access
    `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`, and the battery-optimisation dialog
    `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. Where 1.6.0 answered `false`, such a device now
    shows the permission's list.
  - `openAppSettings()`: a start that fails from the activity is no longer retried from the
    application context (the failures it could hit — no such screen, not exported — fail the same way
    there), and a `singleInstance` activity, or a call off the main thread, now opens the page in a
    task of its own.
  - Binary- and source-compatible: no existing signature changed.
- `kmptoolkit-location` and `kmptoolkit-biometric` (Android): as for permission, the location
  settings screen and the enrolment screen now get their own task and Recents card, and opening the
  same screen again brings its task forward with the screen on top.
- `kmptoolkit-biometric` (Android): `launchEnrollment()` of the existing factories now starts the
  screen from the application context, not from the resumed activity; on a multi-display device it
  may therefore open on the default display rather than the activity's.
- `kmptoolkit-biometric` (Android): any `Exception` thrown while starting the enrolment screen now
  maps to `BiometricEnrollmentLaunch.UNAVAILABLE`; up to 1.6.0 only `ActivityNotFoundException` and
  `SecurityException` did, and anything else crashed the caller.

### Fixed

- `kmptoolkit-audio-player`: the position poll could overwrite a state written at the same moment on
  another thread. A poll tick that had read `Playing` just before the platform reported the end (or
  just before `pause()`) wrote its stale `Playing` over `Completed` (or `Paused`), and the player then
  claimed to be playing until the next transport call. Every transition — transport calls, poll
  ticks and the engine's end/failure events — is now serialized inside the player, and a concurrent
  `release()` can no longer free the engine twice. No API change.
- `kmptoolkit-location` (Android): `openLocationSettings()` started Settings with bare
  `FLAG_ACTIVITY_NEW_TASK`, which joins any background Settings task (one opened from a deep link,
  say), so leaving the screen could land the user on a stale Settings page instead of the app.
  `createLocationProvider(context, config, logger)` now opens it with
  `SystemScreenLauncher.SeparateTask` (`NEW_TASK | NEW_DOCUMENT`, a task of its own). A screen that
  could not be opened is still only logged; `openLocationSettings()` never throws.
- `kmptoolkit-permission`: `SpecialPermissionHandler.requestViaSettings` started every Settings screen
  with a bare `FLAG_ACTIVITY_NEW_TASK`, which brings forward a Settings task left in the background
  (opened from a notification or a quick-settings long-press, say) and pushes the screen onto it, so
  Back landed on that stale Settings page instead of the app. The screens now open with
  `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`, in a task of their own. The same fix applies
  to `PermissionHandler.openAppSettings()` when it has no activity to start from; with one, it still
  opens from that activity with no task flags.
- `kmptoolkit-biometric`: `launchEnrollment()` on Android could land the user on an unrelated
  Settings page. It started the screen with `FLAG_ACTIVITY_NEW_TASK` alone, which reuses a Settings
  task left in the background (one opened from a deep link, say), so Back or the end of the wizard
  returned to that stale page instead of the app. The existing factories now open it with
  `SystemScreenLauncher.SeparateTask` (`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`, from the
  application context), a task of its own, as the KDoc always promised. On a two-pane Settings (large
  screens) the management screen (`COMBINED_BIOMETRICS_SETTINGS`) started in a new task can still be
  handed to the Settings homepage — the enrolment wizard is not; if that matters and the app is not a
  kiosk, pass `SystemScreenLauncher.callerTask(activityAccess)` to `createBiometricGateWithLauncher`.
  The existing factories keep their signatures.

## [1.6.0] - 2026-09-16

### Added

- `kmptoolkit-hijri` — `HijriDate` and `LocalDate.toHijriDate()`, converting a civil date to the
  Umm al-Qura calendar. Android goes through `android.icu.util.IslamicCalendar` with the
  calculation type pinned to `ISLAMIC_UMALQURA` rather than left to the locale; iOS goes through
  `NSCalendar(NSCalendarIdentifierIslamicUmmAlQura)` rather than `Calendar.current`, so the
  answer does not follow the device's calendar setting. Both read in UTC, which is what keeps
  the conversion date-to-date. Desktop goes through the JDK's `java.time.chrono.HijrahDate`,
  which ships as Umm al-Qura and nothing else, so `jvm` is published too and a screen shared
  with a desktop build compiles. One contract object in `commonTest` is run by all three
  platform test classes, so the three readings of the tables are checked against each other.
  No test double is published — the function is pure.

## [1.5.0] - 2026-09-13

1.4.0 was never published; its changes ship in this release and are listed here.

### Added

- `kmptoolkit-hardware-keys`, a new Compose module: `DialogWindowHardwareKeyEffect()` keeps the app's
  hardware-key policy in force inside Compose dialog windows, bottom sheets and focusable popups on
  Android, where an Activity's `onKeyDown` never sees keys, and `DialogWindowHardwareKeyPolicy`
  registers that policy once for the process. A no-op on iOS; also publishes `jvm`, so shared dialog
  code compiles for desktop.
- `kmptoolkit-storage`: typed accessors — `getInt` / `getLong` / `getBoolean`, their `putInt` /
  `putLong` / `putBoolean` counterparts, and `getStringOr` / `getIntOr` / `getLongOr` / `getBooleanOr`
  with a default. One encoding for every caller (decimal strings, `"true"`/`"false"`); a stored
  value that does not parse is a `GET` failure rather than an absent key.
- `kmptoolkit-storage` and `kmptoolkit-storage-testing` also publish `jvm`, so shared code taking a
  `KeyValueStorage` compiles for desktop. `createKeyValueStorage(directory, config)` is a plain store
  in one properties file with atomic writes; there is no secure store on desktop.
- `kmptoolkit-permission`: `Permission.LOCATION`, `LOCATION_BACKGROUND`, `MEDIA_AUDIO` and
  `BLUETOOTH_CONNECT`, each with a stated fold onto `PermissionStatus` on both platforms. Location is
  requested as fine and coarse in one dialog, through a new multi-permission
  `PermissionRequestHost.launch(List, ...)` (default body: a one-element list goes to the single
  `launch`, anything larger is not launched). **An exhaustive `when` over `Permission` needs the new
  entries**; the change is binary-compatible. iOS apps linking the module link CoreLocation,
  CoreBluetooth and MediaPlayer — see `05-platform-notes.md` for the purpose strings.
- `kmptoolkit-permission`: `PermissionHandler.observe(permission)` — the status now and on every
  change, re-read on activity resume (Android), on becoming active (iOS) and after a request. The
  member has a default body emitting the current status once. `RecordingPermissionHandler` emits on
  every scripted change.
- `kmptoolkit-biometric`: `BiometricGateOptions` and a `createBiometricGate(context, config, options)`
  overload (and an iOS `createBiometricGate(config, options)`, where the options change nothing) —
  the weak sensor tier (`BiometricStrength.WEAK`, e.g. camera face unlock), one sensor attempt per
  call (`singleAttempt`: the first non-match ends the prompt with `Rejected`), and an enrolment
  throttle. New `BiometricGate` members with default bodies: `authenticate(prompt,
  requireExplicitConfirmation)` for a per-call confirming tap, and `launchEnrollment()`, which on
  Android opens the enrolment wizard or — for a user already enrolled, for whom the wizard closes
  itself — the biometrics management screen. `ScriptedBiometricGate` records both.
- `kmptoolkit-uploader`: `UploadHandler`, an `UploaderHandler` whose delivery is a multipart upload.
  `prepareUpload` runs at hand-off and again when the platform starts the upload, `classify` turns the
  raw outcome into a settlement with the payload at hand, and `onUploadProgress` / `onDelivered` /
  `onSettled` hooks run isolated. `UploadGateway` is how a transport prepares, reports progress and
  settles such an item. `UploadTransport.launch(itemId, isRehandOff, request)` has a default body.
- `kmptoolkit-uploader` (Android): `createWorkManagerUploadHandlerTransport` and `UploadHandlerWorker` —
  the WorkManager job stores only the item id, so no request or `Authorization` header is persisted in
  WorkManager's database, a re-run of a settled item uploads nothing, and progress is reported. The
  worker is open so an app can keep an earlier worker's class name for jobs already queued on devices.
- `kmptoolkit-uploader` (iOS): `createBackgroundUploadTransport` — one background `NSURLSession` per
  item that keeps uploading after the app is killed — and `BackgroundUploadRelaunch.handleEvents` for
  the app delegate's `handleEventsForBackgroundURLSession`.
- `kmptoolkit-uploader-testing`: `RecordingUploadTransport`.
- `kmptoolkit-audio-player`: `AudioPlayer.unload()` frees the loaded source's native handle but keeps
  the player usable, for a player that outlives the screens borrowing it — `release()` stays the
  permanent teardown. The member has a default body calling `stop()`, so the change is
  binary-compatible and an existing implementation keeps compiling — unless it already declares a
  `fun unload()` of its own, which then needs `override`. A decorator should forward it.
- `kmptoolkit-scheduler` (Android): a `createAlarmScheduler(context, handlerProvider, config)`
  overload. Handlers are looked up when an alarm fires instead of being handed over at creation, so a
  handler that needs the scheduler itself no longer forms a construction cycle in a DI container.
- `kmptoolkit-notification`: `NotificationOptions` and a `Notifier.post(id, notification, options)`
  overload — `alertOnce` (turn it off for a reminder that must sound on every re-post), a
  `dismissAction` sent when the notification is swiped away, the media layout's collapsed button row,
  and per-action icons. Android only; iOS ignores them. The new member has a default body calling the
  two-argument `post`, so existing implementations keep compiling (one that already declares a
  `post(String, LocalNotification, NotificationOptions)` of its own needs `override`); a decorator
  must forward it.
  `RecordingNotifier` records the options on `PostedNotification.options`.
- `kmptoolkit-notification` (Android): `notificationIdOf(id)`, `buildForegroundNotification(...)` and
  `NotificationChannels.ensure` / `delete`. A foreground service starts with a notification rendered
  exactly as `post` renders it — same buttons, same dismissal, same tap target — and later posts under
  the same id update it. Channels can be created at startup and retired. The module now depends on
  `androidx.media` (implementation scope) for the media layout.
- `kmptoolkit-location` (iOS): `LocationProvider.withSystemServicesPrompt()`. Its
  `promptToEnableService()` asks iOS to show its own "Turn On Location Services" alert and answers
  `PROMPTED`. `FakeLocationProvider` gains `servicePromptAnswer` and `promptCount`.
- `kmptoolkit-permission` (Android): a `createPermissionHandler(context, host, storage, activityAccess,
  ...)` overload, for an app that already owns an `ActivityAccess`. `kmptoolkit-activity` becomes an
  `api` dependency accordingly.
- Documentation: `kmptoolkit-location` explains why it stays off Google Play Services and gives a
  complete recipe for a `FusedLocationProviderClient` decorator, including the in-place "turn on
  location" dialog.

### Fixed

- `kmptoolkit-uploader` (iOS): `BackgroundTaskWakeScheduler.handleWake` calls `onDone` even when the
  drain throws, so iOS always hears that the background task finished.
- `kmptoolkit-permission` (Android): on Android 11+, backing out of the **first** permission dialog —
  back, or a tap outside it — returned `PermanentlyDenied`, and every later `request` skipped the
  dialog, although Android would still show it. The handler now also remembers whether the user ever
  refused through the dialog (a second key, `<prefix>.rationale.<PERMISSION>`), so a dismissal reads
  `NotDetermined`. A request whose answer arrives before the activity is resumed waits for it before
  reading the rationale, and no permanent verdict is drawn when there is no activity to ask.
  Behaviour changes that follow from it:
  - The asked flag is now written as `dialog-shown`. A flag an earlier version wrote (`true`) keeps
    being read as those versions read it, so a permission already recorded as permanently denied
    stays so; the fix applies once the user grants it, or on a fresh install.
  - A permission the user refused before the app ever asked — in system settings — or one missing
    from the manifest now reads `NotDetermined`, and `request` returns at once: without a dialog
    Android reports no rationale, exactly as for a dismissal.
  - `check` now writes the refusal flag the first time Android reports a rationale, once per
    permission.
- `kmptoolkit-audio-player`: a `prepare` that arrived while another was still loading broke both — on
  Android the first call never returned, on iOS it failed with an error that overwrote the second
  one's state. Loads are now serialized: the newer one cancels the older, waits for it to unwind, and
  owns the outcome; the replaced call returns normally.
- `kmptoolkit-audio-player` (iOS): the playback speed was not applied when playback started or
  resumed, because the rate was pushed only while `AVPlayer` already reported itself playing.
- `kmptoolkit-audio-player`: `play()` from `Completed` starts over from the beginning on both
  platforms, as `MediaPlayer` already did; `AVPlayer` used to sit at the end in a `Playing` state. The
  documentation said it resumed at the end.
- `kmptoolkit-audio-player` (Android): a load cancelled while `MediaPlayer` was still attaching its
  source leaked the handle, and an error reported right after "prepared" could resume the load twice.
- `kmptoolkit-audio-recorder`: cancelling `prepare` while its storage checks were pending left the
  recorder in `Preparing`, from which nothing — `prepare` included — was legal again. A `stop` or
  `cancel` whose caller was cancelled mid-way left the state `Recording` over a stopped engine; both
  now run to completion; the caller observes its cancellation afterwards.
- `kmptoolkit-scheduler` (Android): `schedule` threw when `AlarmManager` refused an alarm — Android 12+
  caps an app at 500 — despite promising never to throw for a platform refusal. It now returns
  `Failed(PlatformError)`.
- `kmptoolkit-notification`: a progress update whose title, body or buttons changed was coalesced
  like any other frame in the same bucket, leaving stale text on screen until the bar crossed into
  the next one. The bucket now holds back only frames that differ in nothing but the percentage; the
  rate limit (`minProgressInterval`) still applies to every determinate frame.
- `kmptoolkit-notification` (Android): below API 33 a post consulted the `PermissionHandler` for
  `POST_NOTIFICATIONS`, which does not exist there, so a handler answering "denied" silenced every
  notification on older devices. The check is skipped below 33.
- `kmptoolkit-location` (iOS): `openLocationSettings()` reached `UIApplication` on the calling thread;
  it now hops to the main thread, as the "any thread" contract promises.
- `kmptoolkit-location` (iOS): `observeLocation()` held its `CLLocationManager` delegate only weakly,
  so after a garbage collection updates could stop while the flow stayed open.
- `kmptoolkit-activity` (Android): a `withActivity` on another thread that found a finishing activity
  could clear an activity that resumed at the same moment.
- `kmptoolkit-scheduler` (Android): a handler provider throwing a checked exception crashed the process
  instead of dropping the alarm, as documented.
- Documentation: `kmptoolkit-activity` said an untracked activity resuming over a tracked one left the
  tracked activity reachable underneath. The tracked activity is paused while covered, so
  `withActivity` answers `null` until it resumes. `kmptoolkit-notification` said the iOS notifier
  overrides the three-argument `post`; it keeps the default.
- Documentation: `kmptoolkit-biometric` said `ComponentActivity` can host the prompt. It is
  `FragmentActivity`'s superclass — and a Compose activity's default base — so `authenticate` from it
  returns `NoPromptHost`. `kmptoolkit-audio-player` said the playback audio session keeps audio going
  at screen lock; that needs the `audio` background mode.

## [1.3.0] - 2026-09-13

### Added

- `kmptoolkit-haptics`: `HapticFeedback.isAvailable` answers whether the device can play haptics at
  all **without** playing anything — `perform` only reports `UNAVAILABLE` after it has already
  tried, which is too late for a decision such as "does this attention cue have any way to reach the
  user". Android reads `hasVibrator()`, iOS answers `true` (UIKit cannot tell), `noOpHapticFeedback()`
  answers `false`. The member has a default getter returning `true`, so the change is
  binary-compatible and an existing implementation keeps compiling — unless it already declares a
  property named `isAvailable`, which then needs `override`. A decorator should forward it. `RecordingHapticFeedback` gains a matching
  `isAvailable` property and a `(result, isAvailable)` constructor.
- `kmptoolkit-haptics` (Android): `HapticAttribution` and a `createHapticFeedback(context,
  attribution)` overload. `TOUCH` is what every instance did until now and remains the default;
  `NONE` plays the unattributed `vibrate(effect)`, which the user's touch-feedback switch does not
  silence — for a vibration that is not a reaction to a touch, and for an app adopting the module
  whose vibrations were unattributed before. The single-argument factory is unchanged. Purely
  additive.
- Documentation: `kmptoolkit-downloader` explains how an app that already stored downloads in the
  same layout under its own directory name keeps them — `DownloaderStorageConfig(baseDirectoryName =
  ...)` — and what happens to its users if it does not. Pinned by new Robolectric tests; no code
  change.

### Fixed

- `kmptoolkit-flashlight`: the promise that `Flashlight` is safe to call from any thread did not
  hold. The running blink job lived in a plain field, so two `start`s racing from different threads
  could each install a loop, leaving one blinking that no `stop` could reach — a torch that never
  goes dark. A new pattern's first flash could also be cut short by the previous loop's final "off",
  after either a replacing `start` or a `stop` just before it. Both platforms now share one blink loop
  that swaps the job atomically and makes every job wait for the one it replaced to finish; nothing
  in the public API changes. On iOS the torch is also looked up once per `start` instead of on every
  switch.
- `kmptoolkit-accelerometer` (iOS): two concurrent collections of one instance broke each other.
  `CMMotionManager` has a single update handler, so the second collection replaced the first one's,
  and whichever ended first called `stopAccelerometerUpdates` and silenced the other. An instance now
  runs one update stream that starts with the first collection, stops with the last, and delivers
  every sample to all of them — which is what Android already did.
- `kmptoolkit-accelerometer` (Android): a `samplingInterval` below 4 µs reached
  `SensorManager.registerListener` as `0`–`3`, which the platform reads as its `SENSOR_DELAY_*`
  constants — 3 µs meant `SENSOR_DELAY_NORMAL`, 200 ms — and one longer than `Int.MAX_VALUE` µs
  overflowed into a negative period. Short and non-positive intervals now request
  `SENSOR_DELAY_FASTEST`, and long ones are capped.
- Documentation: `kmptoolkit-accelerometer` and `kmptoolkit-proximity` said sensor callbacks arrive
  on the collecting thread's looper. With no `Handler` passed, `SensorManager` delivers them on the
  main looper. `kmptoolkit-proximity` also said an absent sensor's `observe()` never completes; the
  always-absent iOS implementation completes at once, which is now stated.

## [1.2.0] - 2026-09-13

### Added

- `kmptoolkit-language` and `kmptoolkit-language-compose` now also publish a **`jvm` (desktop)
  target**. `AppLanguage` is a type consumers put in the public API of their shared modules — a
  settings repository, a language picker, a screen's state — and a module compiled for desktop could
  not mention it at all while it resolved on only Android and iOS. Unlike the system-bars desktop
  target this is not a no-op: `applyLanguageGlobally` sets the JVM default locale for every category,
  `AppLanguage.System` restores the operating system's language, and `getSystemLanguageCode` reads it
  from the `user.*` system properties, which `Locale.setDefault` does not rewrite. `AppLocale`
  re-pins the default if something else moved it and keys the composition on the language code,
  since a desktop string resource is resolved only when its call composes. The desktop-target
  exception and its bar are restated in `docs/01-architecture.md` § "Desktop targets". Purely
  additive.

### Fixed

- `kmptoolkit-language-compose`: on Android, `AppLocale` provided `LocalContext` as the bare result of
  `createConfigurationContext`. On an activity that delegates to the underlying `ContextImpl`, so the
  context content saw was not the activity or a wrapper around it, and carried the device-default
  theme instead of the activity's: `LocalActivity` and every find-the-activity walk returned `null`
  under `AppLocale`, and an Android View hosted in Compose (a video player, a map) was inflated under
  the wrong theme. It is now the host context wrapped, with only its resources localized — the
  activity stays at the end of the chain and its theme stays in effect.
- Documentation: Android controllers must be created before the first activity resumes, and the
  docs never said so. The activity tracker learns which activity is current only from
  `onActivityResumed`, and Android offers no public way to ask for one that resumed before
  registration — so a controller created later, such as a lazy DI singleton first resolved by the
  theme during composition (which on Android runs after `onResume`), could not style the window on
  screen until the next resume. The contract is now stated on `createSystemBarsController`,
  `createScreenWakeLockController` and `createActivityAccess`, and in both getting-started guides;
  `ControllerCreationOrderTest` pins it, including the bounded recovery on the next resume.
- Documentation: the Android platform notes recommended the no-argument `enableEdgeToEdge()`, which
  installs a translucent navigation-bar scrim on API 26–28 and turns contrast enforcement back on for
  API 29+. Because this module deliberately never calls `enableEdgeToEdge`, following the docs left a
  faint band behind the navigation bar on three-button devices. The notes now give the two lines that
  avoid it.
- Documentation: the iOS platform notes now cover SwiftUI apps, whose window root is a hosting
  controller that never asks an embedded Compose view controller about the status bar. They show
  making the host the root from a `UIWindowSceneDelegate`, and warn off the app-delegate-owned window,
  which leaves the UIScene lifecycle and builds the UI on background launches. The note on activity
  recreation, which claimed a replay to an activity resumed before the controller existed, now agrees
  with the creation-order contract, as does the matching KDoc in `kmptoolkit-activity`.
- KDoc: `applyLanguageGlobally` said `AppLanguageHolder` applies only on a `setLanguage` that changes
  the language; it applies on every call. `StatusBarLuminanceProbe` pointed screens at
  `setStatusBarStyle` / `setNavigationBarStyle`, which do not exist in this API.

## [1.1.0] - 2026-09-12

### Added

- `kmptoolkit-systembars`: `IosSystemBarsController.prefersHomeIndicatorAutoHidden`, to be returned
  from your host's override of the same name. iOS has no navigation bar, so
  `SystemBarsVisibility.isNavigationBarVisible` was inert there; it now drives the home indicator,
  which is the nearest thing a cross-platform "hide the bottom bar" claim can mean on that platform —
  `SystemBarsVisibility.Immersive` hides both bars on Android and the status bar plus the home
  indicator on iOS. The controller invalidates it alongside the status bar on every configuration
  change. Added as a **default interface member**, so no existing implementation of
  `IosSystemBarsController` needs to change. Purely additive.
- New `kmptoolkit-activity` module: `ActivityAccess`, `ActivitySubscription` and
  `createActivityAccess(application, isTracked)` — scoped access to the resumed Android activity,
  with no getter to leak through and a weak reference cleared by the framework's own lifecycle
  callbacks. `kmptoolkit-systembars` and `kmptoolkit-permission` each carried a private copy of this
  code; both now depend on the one artifact, which changes neither module's public API.

  It is the suite's first **Android-only** artifact. Not because iOS support is unfinished, but
  because UIKit has no counterpart to an `Activity` — an iOS `actual` here could only be an
  interface that compiles and does nothing. Depend on it from `androidMain`. The bar a second
  Android-only module would have to clear is in `docs/01-architecture.md` § "One module is
  Android-only".
- `kmptoolkit-systembars`: `createSystemBarsController(activityAccess, initialConfig)` and
  `createScreenWakeLockController(activityAccess)`, so a consumer can say *which* activities the
  controller is allowed to act on. The `Context` overloads track every activity in the process,
  which is right when the bars belong to whichever window the user is looking at — and wrong as soon
  as the process hosts a window the app does not own the appearance of. An in-process picker or
  sign-in activity would otherwise be handed the configuration the app last set, including a
  fullscreen one a screen underneath had claimed, and a keep-screen-awake flag would follow the user
  into it. `createActivityAccess(application) { it is MainActivity }` fixes both. Purely additive;
  the `Context` overloads are unchanged and still track everything.
- `kmptoolkit-language`: `AppLanguage(code)` and `isRightToLeft(code)`, deriving reading direction
  from the writing system instead of making every consumer state it. Which languages an app offers
  is a product decision this module stays out of; which direction Arabic reads in is not, and
  `isLtr = true` typed next to `"fa"` lays Persian out left-to-right with nothing failing. An
  explicit script subtag outranks the language (`az-Arab` is right-to-left, `az` is not), and an
  unrecognised tag is reported left-to-right — the safe direction to be wrong in. The two-argument
  constructor still works and still wins. Purely additive.
- `kmptoolkit-systembars`: `createHeadlessSystemBarsController()`, a controller with the full layer
  model and no window behind it, in the **main** artifact rather than the testing one. `@Preview`
  functions compile into production source, so a screen that resolves a controller — or uses
  `SystemBarsEffect` — cannot reach `RecordingSystemBarsController` from a `testImplementation`
  artifact, and every consumer with previews was left hand-rolling the same empty
  `SystemBarsController`. It is headless rather than inert: overrides stack and release exactly as
  on a device, so a preview reading `config` back sees a real answer. It is also what the `jvm`
  target's `createSystemBarsController()` returns. Purely additive.
- `kmptoolkit-systembars-testing` now also publishes a **`jvm` target**, matching
  `kmptoolkit-systembars`. The fixtures are pure Kotlin with no platform code; without this, the
  shared phone-and-desktop UI tree the desktop exception exists for could not resolve the double
  from `commonTest`. Purely additive.
- `kmptoolkit-systembars-testing`: `RecordingSystemBarsController`, a `SystemBarsController` double
  that layers overrides exactly as the real controller does and records every configuration that
  would have reached a window. `activeOverrideCount` exists so a teardown test can assert a screen
  left no layer behind. Not thread-safe, deliberately — see
  `docs/kmptoolkit-systembars/06-testing.md`.
- `kmptoolkit-systembars` now also publishes a **`jvm` (desktop) target** — the only module in the
  suite that does. The module's premise is that a screen states what it wants from the bars without
  knowing where it runs, so a Compose Multiplatform app sharing one UI tree between phone and
  desktop could not compile that tree at all while the types resolved on only two of its three
  targets. Desktop has no system bars, so every platform call in `jvmMain` is a no-op
  (`createSystemBarsController()` and `createScreenWakeLockController()` are the JVM factories);
  the layer stack — per-axis ownership, restore-by-removal, no lost update under concurrency — is
  `commonMain` and behaves identically, so `config` always holds what the shared tree asked for.
  Purely additive: no existing target, artifact or symbol changes. See
  `docs/01-architecture.md` § "One module publishes a desktop target".

- `kmptoolkit-systembars`: `ScreenWakeLockController`, a keep-screen-awake primitive
  (`Window.FLAG_KEEP_SCREEN_ON` / `UIApplication.idleTimerDisabled`) unrelated to the bars, created
  with `createScreenWakeLockController(context)` (Android) / `createScreenWakeLockController()`
  (iOS). Purely additive — `SystemBarsController` and every other existing symbol is unchanged.
- `kmptoolkit-systembars-testing`: new module holding `RecordingScreenWakeLockController`, a
  `ScreenWakeLockController` double for `testImplementation`.
- `kmptoolkit-systembars`: `AutoSystemBarsIconStyle`, an opt-in composable that samples the pixels
  drawn under both bars and derives a contrasting icon style for each, and
  `StatusBarLuminanceProbe` / `createStatusBarLuminanceProbe()` for triggering an on-demand
  re-sample. Ported from Tahfeez's `AutoSystemBarsIconStyle`, adapted to publish its derived style
  as one `SystemBarsOverride` this composable pushes and updates in place (this module's existing
  layered-override model) rather than writing a flat controller's icon-style setters directly, so a
  screen's own `SystemBarsEffect` still wins any axis it explicitly claims. Requires a new
  `androidx.lifecycle:lifecycle-runtime-compose` dependency in `commonMain`, needed to pause
  sampling while the host is backgrounded. Purely additive — every existing symbol is unchanged.
- New `kmptoolkit-language` module: `AppLanguageHolder`, `AppLanguage` (a BCP-47 code and a reading
  direction — no fixed language list, no display names), `AppLanguageCatalog` /
  `createAppLanguageCatalog`, `applyLanguageGlobally()` / `getSystemLanguageCode()`, and Android's
  `localizedContext()` and `LocalizedApplicationResources`. Ported from Tahfeez's `core/language`,
  generalized to carry no fixed language list or user-facing text and to depend on no storage or DI
  framework of its own.

  `AppLanguageCatalog` is the module's answer to the questions that need the *set* of supported
  languages to answer, without the library knowing how many an app has: which language to serve a
  device whose own language you do not offer (whole tag first, then primary subtag, so `pt-BR` finds
  your `pt`), what string to persist for a selection, and what a previously persisted string means
  today. You build one from your own list; display names and flags stay with you. `systemId` — the
  persisted identity of "follow the device" — is a defaulted parameter rather than a constant, so an
  app with existing values on disk can keep them.

  `LocalizedApplicationResources` (Android) is the piece most easily missed: Android rebuilds the
  process-global default locale from the *Application's* resources on every configuration delivery,
  so without it the app silently reverts to the device language on any change that does not recreate
  the activity — a 180° rotation, a window resize inside the same size bucket, an external display.
  Install it by overriding `Application.getResources()`; see
  `docs/kmptoolkit-language/05-platform-notes.md`.

  `AppLanguageHolder.setLanguage` applies the platform locale on **every** call, including one
  passing the language already in effect. The platform default is shared state the OS itself
  rewrites, so re-asserting it is the point; `languageFlow` and `onLanguageChanged` remain
  change-only.
- New `kmptoolkit-language-compose` module: `AppLocale` and `Modifier.mirrorOnRtl()` /
  `mirrorOnLtr()`. Split from `kmptoolkit-language` so the base module stays plain Kotlin.

  `AppLocale(language, resolvedLanguage, content)` takes the selection *and* the resolved
  language: the first is what the platform locale is pinned to — passing `AppLanguage.System` pins
  the device's language rather than whatever it is set to today — and only `isLtr` is read from the
  second. `resolvedLanguage` has no default on purpose: defaulting it to `language` would let
  `AppLocale(selected) { … }` compile and then read `AppLanguage.System.isLtr`, which is a
  placeholder, laying an Arabic device on "follow system" out left-to-right with nothing to show for
  it. Its platform halves differ, deliberately: Android re-pins the process default synchronously
  before `content` composes and provides a localized `LocalConfiguration`/`LocalContext`, so a
  language change keeps the subtree's remembered state; iOS keys the composition on the language
  code, which is the only mechanism available there and does not. See
  `docs/kmptoolkit-language-compose/05-platform-notes.md`.
- `kmptoolkit-permission`: `SpecialPermission` and `SpecialPermissionHandler`, for Android's
  "special access" grants that have no in-app request dialog — `EXACT_ALARM`, `OVERLAY`,
  `WRITE_SETTINGS`, `ALL_FILES_ACCESS`, `USAGE_STATS_ACCESS`, `IGNORE_BATTERY_OPTIMIZATIONS`,
  `NOTIFICATION_LISTENER_ACCESS`, `DO_NOT_DISTURB_ACCESS`. Created with
  `createSpecialPermissionHandler(context, logger)` (Android) / `createSpecialPermissionHandler()`
  (iOS); every entry is always granted, with nothing to open, on iOS. Ported from Tahfeez's
  `core/permission`, adapted to this module's factory-function convention (no Koin). Purely
  additive — `PermissionHandler` and every other existing symbol is unchanged.
- `kmptoolkit-permission-testing`: `RecordingSpecialPermissionHandler`, a `SpecialPermissionHandler`
  double for `testImplementation`.
- `kmptoolkit-location`: `LocationServicePrompt` and `LocationProvider.promptToEnableService()` (a
  **default interface method**, so no existing `LocationProvider` implementation — including a
  consumer's own — needs to change). Both factory-built providers only ever report `ALREADY_ON` /
  `UNSUPPORTED`: an in-place resolution dialog needs Google Play Services on Android, which this
  module deliberately does not depend on, and iOS exposes no equivalent API at all — see
  `docs/kmptoolkit-location/05-platform-notes.md`. Purely additive.

- `kmptoolkit-uploader`: `UploadTransport`, a ready-made executor for `AttemptResult.Detached`
  covering the common case — a plain multipart HTTP upload that must keep going after the process
  dies. `createWorkManagerUploadTransport` (Android) turns `execute()` into one `WorkManager` job
  per item (unique-keyed, so a re-hand joins rather than duplicates), streams the multipart body
  from disk without buffering it, and reports the outcome to whichever `UploaderEngine` is
  registered through the existing `UploaderEngineRegistry`. `UploadRequest`/`UploadField` are
  encoded into WorkManager's own primitive `Data` by hand — no new serialization dependency.
  `classify: (UploadResult) -> SettleResult` (default: `defaultUploadClassification`) lets you map
  HTTP outcomes onto the queue's vocabulary yourself. No iOS transport yet — a background
  `NSURLSession` needs your app's own `AppDelegate` to forward
  `application(_:handleEventsForBackgroundURLSession:completionHandler:)`, the same OS-mandated
  cooperation the existing iOS wake scheduler already requires for `BGTaskScheduler`; see
  `docs/kmptoolkit-uploader/08-upload-transport.md`. Purely additive — every existing symbol,
  including `AttemptResult.Detached` itself, is unchanged.

### Changed

- `kmptoolkit-systembars`: `DialogWindowSystemBarsEffect` now collects the controller's configuration
  lifecycle-aware (`collectAsStateWithLifecycle`) instead of unconditionally, matching
  `AutoSystemBarsIconStyle` — a backgrounded dialog window is neither read from nor written to, and
  the configuration is re-read on the way back to `STARTED`. No API change.

### Fixed

- `kmptoolkit-systembars`: a released `SystemBarsOverrideHandle` could release or overwrite a layer
  belonging to a *later* override. Override ids were derived from the layers currently on the stack,
  so an empty stack restarted the sequence and a dead handle came back to life aliasing whatever was
  pushed next. Reaching it needed the stack to drain in between — one screen leaving, another
  arriving, and the first then releasing or updating its handle a second time — at which point the
  arriving screen's bars were silently cleared or rewritten. Ids are now part of the same atomic
  state as the stack itself and are never reused, including across `SystemBarsController.release()`.
  `RecordingSystemBarsController` was already correct here and now has the matching parity cases.
- `kmptoolkit-location`: the iOS `LocationProvider` now creates and starts every `CLLocationManager`
  on the main queue. Previously a caller reaching `getCurrentLocation()` / `observeLocation()` from
  `Dispatchers.Default` (a Kotlin/Native worker thread, which has no run loop) could see the manager
  created there too — CoreLocation delivers delegate callbacks on the run loop of the thread that
  created the manager, so on such a thread neither a fix nor a failure would ever arrive, and the
  call would suspend forever rather than time out or fail.

## [1.0.2] - 2026-09-04

No public API change. Re-release of `1.0.1`, whose Maven Central deployment never completed
because the CI signing step failed on a misconfigured repository secret (see `RELEASING.md`
§ Troubleshooting). Every `kmptoolkit-*` artifact and the BOM are published at this version.

## [1.0.1] - 2026-08-29

### Removed

- **Breaking:** `kmptoolkit-coroutines` and `kmptoolkit-coroutines-testing` are no longer part of
  the suite. `AppDispatchers` was a three-field dispatcher-seam interface whose only internal
  consumer, `kmptoolkit-session`, used exactly one of those fields — and `kotlinx-coroutines-test`'s
  own `TestDispatcher` already covers what `TestAppDispatchers` existed to provide, with no custom
  type needed.
- **Breaking:** `kmptoolkit-session` and `kmptoolkit-session-testing` are no longer part of the
  suite. `SessionManager`, `SessionCleaner`, `SessionRevoker`, and the rest of the module's public
  API are gone, along with the `RecordingSessionCleaner`/`RecordingSessionRevoker` test doubles;
  no other module in the suite depended on either artifact.
- **Breaking:** `kmptoolkit-settings` is no longer part of the suite. `AppSettings`, `FontScale`,
  `ThemeMode`, `LanguageTag`, `LanguageApplier`, and the rest of the module's public API are gone.
  It was the one module that prescribed *what settings an app should have* instead of exposing a
  platform capability, and it had no dependents inside the suite.
- **Breaking:** `kmptoolkit-platform` and `kmptoolkit-platform-testing` are no longer part of the
  suite: `ConnectivityObserver`, `DeviceInfo`, `FilePicker`, `ScreenWakeLock`, `CrashLogStore`,
  `UrlOpener`, `ReducedMotionProbe`, and the public `ActivityAccess`/`ActivitySubscription`/
  `createActivityTracker` API are all gone, along with every fake in `kmptoolkit-platform-testing`.
  Nothing in the suite used the capabilities beyond `ActivityAccess`; that one is now a private
  implementation detail duplicated inside each of its three consumers instead — see the `Changed`
  entry below.

### Changed

- **Breaking:** `kmptoolkit-outbox`, `kmptoolkit-outbox-testing`, and `kmptoolkit-outbox-sqldelight`
  are renamed to `kmptoolkit-uploader`, `kmptoolkit-uploader-testing`, and
  `kmptoolkit-uploader-sqldelight`. The package moves from
  `io.github.jamal_wia.kmptoolkit.outbox` to `io.github.jamal_wia.kmptoolkit.uploader`, and every
  public type is renamed to match — `Outbox` to `Uploader`, `OutboxItem` to `UploaderItem`,
  `OutboxEngine` to `UploaderEngine`, `OutboxConfig` to `UploaderConfig`, `OutboxStore` to
  `UploaderStore`, `OutboxHandler` to `UploaderHandler`, `OutboxClock` to `UploaderClock`,
  `OutboxEngineRegistry` to `UploaderEngineRegistry`, `FakeOutbox` to `FakeUploader`,
  `InMemoryOutboxStore` to `InMemoryUploaderStore`, `OutboxStoreContract` to
  `UploaderStoreContract`, `SqlDelightOutboxStore` to `SqlDelightUploaderStore`, and the rest of
  each module's API accordingly. `kmptoolkit-outbox-sqldelight`'s table and index names change from
  `kmptoolkit_outbox_item*` to `kmptoolkit_uploader_item*` — an existing on-disk database's queue
  table is not carried forward automatically; a consumer with data already committed under the old
  name is responsible for migrating it. There is no compatibility alias for the old coordinates,
  package, or type names.
- **Breaking:** `createBiometricGate` and `createPermissionHandler` (Android) no longer take an
  `activityAccess: ActivityAccess` parameter, and `createSystemBarsController` (Android) takes a
  `context: Context` in its place. This follows from removing `kmptoolkit-platform`, above:
  `ActivityAccess` could no longer be a type the three modules share, so each now tracks the
  currently resumed activity with its own private, weakly-held tracker instead of the caller
  building one with `createActivityTracker(application)` and passing it to all three. A consumer
  using all three modules now gets three `Application.ActivityLifecycleCallbacks` registrations
  instead of one, which is harmless but was worth naming.
- Every module now publishes exactly two Apple targets, `iosArm64` and `iosSimulatorArm64`. The
  legacy Intel-simulator target `iosX64` is no longer declared anywhere: it is superseded by
  `iosSimulatorArm64` on Apple-silicon Macs, Compose Multiplatform 1.11+ publishes no artifact for
  it, and dropping it cuts roughly a fifth off the suite's published-file count — which matters
  against Maven Central's per-namespace monthly limits (see `RELEASING.md`). No public declaration
  changed: the `.klib.api` diffs are confined to their `Targets:` header line. Consumers building
  for the Intel iOS simulator cannot use this suite.

## [1.0.0] - 2026-08-23

### Added

- `kmptoolkit-coroutines` — `AppDispatchers` dispatcher seam and its `DefaultAppDispatchers`
  production implementation.
- `kmptoolkit-coroutines-testing` — `TestAppDispatchers` double, published separately so
  `kotlinx-coroutines-test` stays off consumers' runtime classpath.
- `kmptoolkit-logging` — `Logger` / `LogLevel` / `LogSink` / `LoggerFactory`. No global logger
  state and no third-party logging dependency; bridge to Kermit, Timber or a crash reporter by
  writing a `LogSink`.
- `kmptoolkit-haptics` and `kmptoolkit-haptics-testing` — `HapticFeedback` reporting a typed
  `HapticResult` rather than throwing or failing silently. Vibrations are attributed as
  `USAGE_TOUCH`, so the user's system haptics settings actually govern them.
- `kmptoolkit-flashlight` and `kmptoolkit-flashlight-testing` — a `Flashlight` interface that
  blinks the camera torch in one of two `FlashPattern` rhythms, built as a deliberate mirror of
  `kmptoolkit-haptics`: same best-effort contract, same shipped test fake. Needs no permission on
  either platform — `CameraManager.setTorchMode` on Android requires none at all, and iOS only
  gates a capture session behind camera permission, which this module never opens — and every call
  degrades to a silent no-op on hardware with no flash unit rather than throwing.
- `kmptoolkit-audio-player` and `kmptoolkit-audio-player-testing` — `AudioPlayer` over a pluggable
  `PlaybackEngine` SPI, so the state machine lives in common code and is testable without a device.
  `MediaPlayer` and `AVPlayer` engines ship with it.
- `kmptoolkit-audio-recorder` and `kmptoolkit-audio-recorder-testing` — `AudioRecorder` with an
  8×6 transition table where every illegal call is inert and typed rather than thrown. Operations
  that can touch the filesystem suspend; operations that only move recorder state do not.
- `kmptoolkit-scheduler` and `kmptoolkit-scheduler-testing` — exact-time one-shot local alarms.
  A missing `SCHEDULE_EXACT_ALARM` downgrades to an inexact alarm and says so through
  `AlarmScheduleResult.Inexact` instead of failing or downgrading silently.
- `kmptoolkit-storage` and `kmptoolkit-storage-testing` — plain and encrypted key-value storage
  plus `DeviceIdProvider`. Encryption uses AndroidKeyStore and `Cipher` directly rather than Tink,
  which keeps roughly a megabyte out of every consumer. The iOS Keychain queries are built with
  `CFDictionaryCreateMutable`, not a bridged Kotlin `Map`, which iOS 26 rejects with `errSecParam`.
- `kmptoolkit-platform` and `kmptoolkit-platform-testing` — connectivity, device info, reduced
  motion, URL opener, file picker, screen wake lock, and a crash log store. Connectivity reports a
  tri-state so a missing `ACCESS_NETWORK_STATE` is never mistaken for being offline.
- `kmptoolkit-logging-overlay` — an on-screen log overlay wired through `kmptoolkit-logging`'s
  `LogSink`. Debug builds only: bounded buffer, no PII filtering, gating is the consumer's job.
- `kmptoolkit-permission` and `kmptoolkit-permission-testing` — a headless rationale → request →
  settings flow. The catalog is a closed enum of the three permissions whose mapping is exercised
  on both platforms; location and photo library are excluded because iOS models them in ways the
  status type cannot honestly express.
- `kmptoolkit-biometric` and `kmptoolkit-biometric-testing` — a biometric gate returning typed
  outcomes that distinguish no-hardware, not-enrolled, transient lockout and permanent lockout.
  Prompt copy has no defaults, so the library's words cannot ship by accident.
- `kmptoolkit-settings` — font scale, theme mode and app language over `kmptoolkit-storage`,
  exposed as `StateFlow`s. The font scale is a validated multiplier rather than a fixed set of
  named steps and the language is a canonicalised BCP 47 `LanguageTag` rather than an enum, so
  neither the donor app's typography scale nor its language list ships to anyone. A failed write
  leaves the flow untouched and returns a typed error instead of showing a choice that reverts at
  the next launch, and everything that could not be read is reported in `SettingsLoad.problems`.
  `LanguageApplier` applies the language through the framework `LocaleManager` on Android 13+, the
  process locale defaults below it, and `AppleLanguages` on iOS — without an `androidx.appcompat`
  dependency.
- `kmptoolkit-systembars` — Compose system-bar control built as a base configuration plus a stack
  of per-axis override layers, so leaving a screen removes exactly that screen's contribution
  instead of restoring a snapshot that may since have gone stale.
- `kmptoolkit-notification` and `kmptoolkit-notification-testing` — local notifications. A
  `NotificationChannelSpec` keeps Android's concept and documents per field what iOS actually does
  with it, rather than pretending a `UNNotificationCategory` is a channel: a category declares
  action buttons and has no name, sound, importance, or mute switch. Posting returns the first
  reason a user would not have seen the notification.
- `kmptoolkit-session` and `kmptoolkit-session-testing` — session state plus a `SessionCleaner`
  fan-out that runs every cleaner even when others fail, reports failures instead of swallowing
  them, and ends the session regardless. A failing `SessionRevoker` never blocks local teardown,
  so signing out works offline.
- `kmptoolkit-bom` — pins every artifact to one version. Its constraint list is derived from the
  projects that exist rather than maintained by hand.
- `kmptoolkit-outbox` and `kmptoolkit-outbox-testing` — a transactional outbox: a durable effect
  queue with per-handler retry policies, strict FIFO ordering channels, constraint gating, and
  detached delivery under a self-expiring lease. **Storage is an SPI, not a dependency** — the
  module ships no database at all, which is the one thing that made the donor's otherwise
  well-tested engine unreusable. `kmptoolkit-outbox-testing` ships a complete `InMemoryOutboxStore`
  and `OutboxStoreContract`, a runnable check of every invariant a store must hold, so a custom
  store can prove itself rather than be reviewed.
- `kmptoolkit-outbox-sqldelight` — the reference implementation of the outbox storage SPI, over
  SQLite. Ordering uses an `AUTOINCREMENT` sequence rather than a timestamp, so two enqueues in the
  same millisecond keep their order and a rowid freed by a delivered item is never reused beneath a
  waiting one. It passes all 30 checks of `OutboxStoreContract` unmodified on both platforms.
- `kmptoolkit-accelerometer` and `kmptoolkit-accelerometer-testing` — raw accelerometer readings
  as a cold `Flow<AccelerometerSample>`, registering the platform sensor on collection and
  releasing it on cancellation rather than on an explicit `close()`. Both platform factories take
  a `samplingInterval: Duration` (200 ms default on each) instead of a hardcoded rate, and iOS
  scales Core Motion's readings from g to m/s² so both platforms report the same unit.
  `kmptoolkit-accelerometer-testing` ships `ScriptedAccelerometer`, which replays a canned sample
  list per collector and counts registrations so a test can prove a collector was actually
  released.
- `kmptoolkit-location` and `kmptoolkit-location-testing` — platform-agnostic access to the
  device's geographic position: a one-shot suspend fun for a single fix, a `Flow` for continuous
  updates, and a suspend check for the device-wide location service toggle, independent of the
  app's permission. Raw coordinates only — no caching, no permission UI. The Android side is plain
  `android.location.LocationManager` rather than Play Services' `FusedLocationProviderClient`, the
  same reasoning that keeps `kmptoolkit-storage` off Tink: no other module here depends on Play
  Services, and the fix-quality gain was not worth becoming the first one that does. Both
  platforms cap a single-fix request with a timeout, so a device with no signal cannot suspend
  `getCurrentLocation` forever.
- `kmptoolkit-proximity` and `kmptoolkit-proximity-testing` — a proximity sensor seam:
  `ProximitySensor` reports a cold, event-driven `Flow<Boolean>`, and `ProximityRule.isNear` folds
  a raw distance into it by comparing against the smaller of a five-centimetre threshold and the
  sensor's own maximum range, since most units are binary and answer only `0` or their own
  maximum. iOS has no proximity API to build on at all — Core Motion exposes none, and
  `UIDevice`'s own monitoring is iPhone-only and tied to blanking the screen — so there
  `isAvailable` is permanently `false` rather than a gap this module could close later.
- `kmptoolkit-downloader` and `kmptoolkit-downloader-testing` — a resumable background-download
  engine for the assets an app can't ship inside its binary. **The platform transfer itself is an
  SPI, not a dependency** — the module ships no HTTP client, only storage (Android and iOS) and the
  retry/commit state machine around it. Nothing about an in-flight download is persisted except a
  stall counter: "is a transfer running" is answered by the transfer SPI, "is it done" by the file
  on disk, so a process crash needs no recovery pass — the next `ensureAvailable` call re-derives
  everything from those two questions and resumes from whatever temp file survived. A resource is
  also verified, not merely present, before it counts as committed: a `ZipArchive` unit proves
  itself by an extraction marker, and a `SqliteDatabase` unit can carry its own expected row count
  for the engine to check the file against. `kmptoolkit-downloader-testing` ships `FakeDownloader`
  and `FakeDownloaderStorage`.
- Repository infrastructure: composite `build-logic` with `kmptoolkit.library` /
  `kmptoolkit.compose` / `kmptoolkit.publish` / `kmptoolkit.androidtest` convention plugins,
  version catalog, Maven Central publishing via the vanniktech plugin, `explicitApi()` +
  ABI validation, CI publish workflow.
