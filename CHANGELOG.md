# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with one addition: **before
1.0.0**, a breaking API change is called out explicitly under its own `Breaking` heading rather than
silently folded into `Changed`, since minor version bumps are not yet a compatibility guarantee.

## [Unreleased]

### Added

- `kmptoolkit-files` (Android, iOS, `jvm`): file-system primitives for shared code behind one `FileAccess`
  interface - atomic writes with a chosen durability (`Buffered`, `Synced`, `SyncedWithDirectory`, iOS
  `F_FULLFSYNC`), no-replace writes and moves, moves across volumes, bounded reads, recursive delete and tree size
  that never follow symbolic links, age-based leftover sweeps, free space at the nearest existing ancestor - with
  typed `FileError` results instead of exceptions; `KeyedMutex` and `PathLocks` for per-key coroutine locking;
  `Paths`, `FileNames` and `PathKey` with Windows rules and Unicode-safe case folding; `AppDirectories` for the
  app's private roots with backup exclusion; `withIo` for cancellable use from coroutines.
- `kmptoolkit-files-testing`: `FakeFileAccess`, an in-memory file system that runs the real engine, with fault
  injection, crash and power-loss simulation; `FileAccessContract` to check any `FileAccess` decorator.

### Changed

- `kmptoolkit-storage` (`jvm`) now depends on `kmptoolkit-files` (its atomic write and per-file lock). No store file
  name, file format or directory layout changes.
- `kmptoolkit-storage` (`jvm`): when the file system refuses an atomic rename, a write now fails with
  `StorageError.OperationFailed` instead of replacing the file non-atomically. On Windows, a replace blocked by
  another process holding the file open is retried for up to about 210 ms.
- `kmptoolkit-storage` (`jvm`, Windows): a store whose name is a reserved device name (`CON`, `NUL`, `COM1`...) can
  no longer be written (reads still work); 4.x wrote it on Windows 11 only.
- `kmptoolkit-storage` (`jvm`): the temporary file of a write is named `<file name><digits>.tmp` as before; for a store
  file name longer than 232 UTF-8 bytes the file name in it is shortened (cut to 215 bytes, then `~` and 16 hex
  characters of its SHA-256), so every store name that fits a file name stays writable.

## [4.0.0]

Source compatible with 3.0.x, except for implementations of the opt-in `ToolkitInheritanceApi` interface
`VideoPlayer`, which must implement the new abstract `playbackSpeedRange` (see Added), and binary
compatible apart from the one recompilation case named under Breaking; the behavior changes of this
release are listed there.

### Breaking

- `kmptoolkit-audio-player` (iOS): the default `coroutineContext` of `createAudioPlayer` is now
  `Dispatchers.Main` (it was `Default`), so `AVPlayer` is only touched from the main thread. Position
  updates need a free main thread; pass `Dispatchers.Default` to restore the 3.0 behavior.
  `AudioPlayer.prepare` is documented as having no built-in deadline.
- `kmptoolkit-audio-player` and `kmptoolkit-video-player`: a source whose length is not known
  (duration `0`) that plays to its end now completes as `Completed(duration = where it ended)` with
  the playhead left there, instead of `Completed(0)` with the playhead thrown back to `0`, and that
  length then counts as known for the loaded source. `FakePlaybackEngine.completePlayback()` and
  `FakeVideoPlaybackEngine.completePlayback()` leave the playhead in place when `durationMs` is `0`.
- `kmptoolkit-audio-player` and `kmptoolkit-video-player`: `AudioPlayerConfig` and
  `VideoPlayerConfig` throw `IllegalArgumentException` for an infinite `minPlaybackSpeed` or
  `maxPlaybackSpeed`, which no platform can reproduce (NaN was already rejected). The signatures are
  unchanged.
- `kmptoolkit-audio-player`: the `PlaybackEngine.seekTo` contract. While `durationMs()` is `0` the
  target can be anything up to `Long.MAX_VALUE`, so a custom engine must saturate it to what its
  platform accepts instead of truncating it (the Android engine caps it at `Int.MAX_VALUE`).
  `FakePlaybackEngine` rejects a negative `durationMs`, in its constructor and its setter.
- `kmptoolkit-audio-recorder`: `RecordingStorage` rejects a `directoryName` that starts with `/` or
  contains a `.` or `..` segment, and a relative `directoryPath`; such values could place recordings
  outside the app-private directory. `FakeAudioRecorder.prepare` refuses a blank or relative
  explicit path like the real recorder and requires an absolute `directory`.
- `kmptoolkit-downloader`: committing a `ZipArchive` is stricter, and behaves the same on Android
  and iOS. The archive must end with a self-consistent end of central directory record, checked
  before anything is extracted, so an archive that is cut short, including one cut inside the header
  of an entry, fails. Entry names must be valid UTF-8 (an archive written in a legacy code page used
  to fail on Android and be extracted under mangled names on iOS). On iOS, an archive with a
  truncated or corrupt entry, an unsupported compression method, an encrypted entry, a stored entry
  with a data descriptor, a ZIP64 layout or an entry name that leaves the extraction directory used
  to commit with that entry skipped or cut short; it now fails. When the single root directory is
  moved up, a child whose name exists beside it as a file fails the commit instead of overwriting
  that file (Android). Such an archive now fails with `ResourceIntegrityException` and its temp file
  is deleted, so the engine downloads it once more and then reports `DownloadError.Corrupted`;
  Android used to report `DownloadError.Storage` and retried the same file on every request. Empty
  directory entries of a `ZipArchive` are now extracted. No API signature changes.
- `kmptoolkit-downloader`: an archive whose extracted tree lacks the unit's marker no longer
  replaces an available resource: the commit fails with `ResourceIntegrityException` (one fresh
  download, then `DownloadError.Corrupted`) and the old resource stays. A first commit of such an
  archive reports `Corrupted` instead of `Storage`.
- `kmptoolkit-downloader`: a `SqliteDatabase` resource without the SQLite file header, such as an
  empty or very short body, now fails the commit as corrupt instead of committing as an empty
  database. The row-count check now binds the meta key as a value, so a key that was escaped by hand
  (`ayah''s`) must be written plainly.
- `kmptoolkit-downloader`: a `relativePath` containing `..` that stays inside the base directory
  (such as `files/x/../a.bin`) can now be committed. The shipped storages use the lexically
  normalized path for every file operation, and `getResourcePath` returns that normalized path.
- `kmptoolkit-downloader-testing`: `FakeDownloaderStorage` mirrors the real storages in two more
  places: `markTempFileComplete` throws `IllegalStateException` when there is no temp file, and a
  successful `commitResource` makes the unit available. A test that relied on either old behavior
  must seed `tempFileStates` and stop adding the id itself.
- `kmptoolkit-language`: `createAppLanguageCatalog` rejects a `supported` list whose codes differ
  only by `_` versus `-` (`ar_EG` and `ar-EG`), or only by an extension or a legacy language code
  (`iw` and `he` were already rejected), as repeated codes.
- `kmptoolkit-language`: `isRightToLeft` and `AppLanguage(code)` report right-to-left for regional
  languages whose default script is right-to-left (`arz`, `ary`, `apc`, `azb`, `pnb`, `skr`, `mzn`,
  `glk`, `lrc`, `bgn`, `rhg` and 24 more, 35 in total); they were laid out left-to-right, so
  `AppLanguage(code).isLtr` flips for those codes.
- `kmptoolkit-language`: `resolveSystemLanguage()` matches the device tag by truncation
  (`zh-Hans-CN`, then `zh-Hans`, then `zh`), infers the Chinese script from the region (`zh-TW` is
  `zh-Hant`), then takes a same-language, same-script entry (`pt-BR` finds your `pt-PT`,
  `zh-Hans-CN` finds your `zh-CN`) before the fallback, and ignores `-u-` and `-x-` extensions
  (Android 14 regional preferences). A device that used to get the bare language can now get a more
  specific entry you offer, and a device that used to get the fallback can now get a match.
- `kmptoolkit-scheduler`: `AlarmSchedulerConfig` throws `IllegalArgumentException` when `alarmIdKey`
  or `alarmTypeKey` equals one of the module's own extra names (`kmptoolkit_alarm_fire_at`,
  `kmptoolkit_alarm_title`, `kmptoolkit_alarm_body`, `kmptoolkit_alarm_channel`,
  `kmptoolkit_alarm_payload`); such a config used to corrupt or drop alarms.
- `kmptoolkit-storage`: `put` of a key or value containing an unpaired surrogate answers
  `Failure(OperationFailed)` instead of `Success` followed by a lost or altered value (all shipped
  stores and `InMemoryKeyValueStorage`). A desktop store file that is not valid UTF-8 now fails
  every operation and is never rewritten, instead of having its undecodable characters replaced on
  the next write.
- `kmptoolkit-systembars`: `AutoSystemBarsIconStyle` rejects `intervalMs <= 0` with
  `IllegalArgumentException`; such a value used to spin the main thread once sampling started.
- `kmptoolkit-uploader` (iOS): the deprecated three-parameter `BackgroundUploadConfig` constructor no
  longer has default arguments (it had them in 3.0.0). Only a library precompiled against 3.0.0 that
  calls it with arguments omitted, for example `BackgroundUploadConfig()`, must be recompiled against
  4.0.0. Kotlin source code, Swift code and apps are unaffected.
- `kmptoolkit-uploader`: the engine honours a lease for at most 24 hours. A longer
  `AttemptResult.Detached` or `UploadTransport.leaseMillis` is shortened to 24 hours, and the item
  is re-handed after a day (the hand-off must already be idempotent). An in-flight item stored by an
  earlier version with a lease more than `clockAnomalyFactor` x 24 hours ahead is re-handed at the
  first drain after upgrading.
- `kmptoolkit-uploader-sqldelight`: a corrupt database file no longer silently recreates an empty
  queue on Android. `createUploaderStorage` now fails with `UploaderStorageException(OPEN)` and
  leaves the file untouched, the same as iOS. Handle it at launch (see
  `docs/kmptoolkit-uploader-sqldelight/03-guide.md`) or set
  `UploaderDatabaseConfig.recreateOnCorruption = true` to get the old behavior. Binaries
  compiled against 3.0.0 keep linking, including calls that omit every argument
  (`UploaderDatabaseConfig()`, `copy()`): the 3.0.0 constructor and `copy(name)` stay as hidden
  declarations with their 3.0.0 default arguments.
- `kmptoolkit-uploader-sqldelight`: an item whose text (payload, keys or tag) exceeds 1 MiB in UTF-8
  is refused with `UploaderStorageException`; such a row could not be read back on Android and made
  the whole queue unreadable.
- `kmptoolkit-uploader-testing`: `UploaderStoreContract` checks twelve more invariants (exact tag
  and unique-key matching, verbatim values, whole-row equality after guarded writes, absolute
  counters, insertion order under falling creation times, live observation, tail revival of a parked
  key, exact point reads, snapshot lists, one-winner races); a custom store that passed the earlier
  checks may now fail one.

### Added

- `kmptoolkit-activity`: `ActivityAccess.forEachResumedActivity` runs a block with every tracked
  activity resumed right now (split screen, freeform, activity embedding), earliest first. Its
  default body runs the block with `withActivity`'s activity, so existing implementations keep
  compiling.
- `kmptoolkit-downloader`: `DownloaderConfig` with `stallTimeout` (default
  `DownloaderConfig.DEFAULT_STALL_TIMEOUT`, 5 minutes; `Duration.INFINITE` turns stall detection off)
  and a `createDownloader` overload that takes it, placed right after `stateStore`. The existing
  overload is unchanged and behaves like `DownloaderConfig()`. A new guide,
  `docs/kmptoolkit-downloader/07-background-downloader.md`, states the contract for a
  `BackgroundResourceDownloader`: subscribe before enqueue, replay rules, the message phrasing the
  error classifier reads, what happens to the partial file, the iOS keep-alive recipe, the Android 15
  `dataSync` limit, and a thread-safe sample.
- `kmptoolkit-downloader`: `InvalidDownloadUnitException(unit, reason)` with `Reason { EMPTY,
  INVALID_CHARACTER, ESCAPES_BASE_DIRECTORY, RESERVED_INTERNAL_PATH }`. The shipped storages throw
  it for a `DownloadUnit.relativePath` that cannot be used safely; see the `relativePath` entry
  under Changed.
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
- `kmptoolkit-uploader-sqldelight`: `UploaderDatabaseConfig.recreateOnCorruption` (default `false`).
  When `true`, `createUploaderStorage` deletes a database file it recognises as corrupt, including
  one damaged below a valid header, and opens an empty queue, on Android and iOS. This is what 3.0.0
  did on Android; see Breaking for the new default.
- `kmptoolkit-uploader-testing`: `UploaderStoreContract` gains twelve checks; see Breaking.
- `kmptoolkit-video-player`: `VideoPlayer.playbackSpeedRange`, the configured speed range, so a
  speed menu can offer only rates the player accepts. It is a new member of an interface that can
  only be implemented by opting in to `ToolkitInheritanceApi`; such an implementation must add it.

### Changed

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
- `kmptoolkit-downloader`: a `relativePath` that is empty or normalizes to nothing, contains NUL,
  escapes the base directory, or points at `tmp`, `tmp/expect`, `tmp/expect-staged`,
  `tmp/kmptoolkit-internal`, `tmp/staging-...` or the unit's own temp files now fails the unit
  before any transfer with `DownloadFailedException(DownloadError.Unknown)` whose cause is
  `InvalidDownloadUnitException`; `isAvailable` is `false` and `pathOf` throws. These paths never
  worked or lay outside the documented layout (they wiped the base directory, files outside it, or
  other units' records). Other paths under `tmp/` still work and log a warning, and `tmp/trash/...`
  stays accepted.
- `kmptoolkit-location-testing`: `FakeLocationProvider.observeLocation()` delivers a fix equal to
  the previous one again (and a repeated `null`), as the real providers do; it used to drop it.
- `kmptoolkit-notification`: `post` can now return `Coalesced` for any notification, not only a
  progress update, when a `cancel` or a newer post of the same id overtook it (per `Notifier` instance);
  nothing is posted then. Before, `Coalesced` meant only a redundant progress frame, so a caller that
  treats the result of a non-progress post as `Posted` or an error now sees a new answer.
- `kmptoolkit-notification` and `kmptoolkit-scheduler` (Android): `Notifier.cancel`,
  `Notifier.cancelAll`, `AlarmScheduler.cancel` and `AlarmScheduler.cancelAll` no longer throw when
  the platform refuses (a `SecurityException` or `IllegalStateException`): the refusal is logged,
  the notification or alarm may stay, and `cancelAll` continues with the remaining ids.
- `kmptoolkit-uploader`: `UploadHandler.classify(payload, result)` is no longer abstract. It is now
  `open` and defaults to `defaultUploadClassification(result)`, so a new handler need not implement
  it; handlers written for 3.0 keep overriding it unchanged. This is the one intentional modifier
  change in the ABI dump, and it is binary and source compatible. The legacy
  `createWorkManagerUploadTransport` (global classifier) stays status-only.
- `kmptoolkit-uploader`: an `enqueue` whose caller is cancelled while its store write is in progress
  now completes that write: the item is queued and its wake armed. A caller cancelled before the
  write starts still writes nothing. `UploaderStore` states that a call may report its caller's
  cancellation before or after its write commits, or not at all.
- `kmptoolkit-uploader`: `InMemoryUploaderStore` never notices a cancelled caller, also when calls
  contend for its lock; before, a contended call could be refused before writing.
- `kmptoolkit-uploader`: `UploaderItem.lastError` is truncated to about 4096 characters.
- `kmptoolkit-uploader` (Android): a progress hook still running 2 s after the transfer is cancelled
  is no longer awaited (Android and iOS), so a hook that ignores cancellation cannot hold up the
  settlement; the answer is journaled before that wait. The server's status line and headers must
  arrive within twice `readTimeoutMillis` after the body is sent; a server that trickles them longer
  fails the attempt as a transport failure.
- `kmptoolkit-uploader` (Android): a 2xx or 3xx status the server sends before the request body is
  complete, and then closes the connection, is reported as `Completed(status)` with its response
  instead of a retryable transport failure that uploads the file again.
- `kmptoolkit-video-player-javafx`: a queued seek that a newer seek supersedes is skipped, so a
  burst of transport calls no longer keeps the JavaFX application thread busy for seconds.
- Docs: the `kmptoolkit-scheduler` platform notes describe the real iOS cap behaviour;
  `kmptoolkit-location` documents that iOS updates stop while the app is suspended and what background
  location needs; `kmptoolkit-language` documents that `onLanguageChanged` runs
  under the holder lock. Also corrected: the hijri getting-started imports, the `RELEASING.md`
  configuration-cache note, version-catalog comments and a stale Robolectric comment.
  Also documented: that `AlarmSchedulerConfig.alarmIntentScheme`, `alarmIdKey` and `alarmTypeKey`
  must stay stable across app versions (alarms armed under an earlier value cannot be cancelled or
  replaced); the per-id state of the downloader engine, the stable-id requirement, how to release
  abandoned units and what `DownloaderStorage.deleteTempFile` cleans up; the migration of 3.0.0 temp
  files; that a second `createBackgroundUploadTransport` call replaces the relaunch target and does
  not stop the first transport; the lifetime of factory-created activity trackers, of a pending
  permission request after cancellation and of the alarm handler provider; what the biometric prompt
  gate releases after an outcome; that the iOS audio player never deactivates `AVAudioSession` and
  when `AudioPlayer.release()` takes effect during a load; that the iOS video player leaves
  `AVAudioSession` active after `release()`; the iOS `hostViewController` retain cycle of
  `kmptoolkit-systembars` and one wake-lock controller per screen.
- Docs, later review: that a withdrawal or enqueue whose store write has started waits for that
  write whatever the caller's timeout (only the store bounds it), that `close()` does not wait for a
  running attempt, so a handler that ignores cancellation can see its item run again by the next
  engine, as after a process kill, and that `UploaderStore.markInFlight` should store the lease
  exactly; that a `kmptoolkit-uploader` `onSettled` runs at most once and only for an applied
  settlement, that there is one upload job per item, and what an unreadable journal entry waits for;
  that `DownloadUnit.tempExtension` is part of the temp file's name and must be the same for every
  instance of one id; that a desktop store file that cannot be parsed fails every operation and is
  never replaced; that `kmptoolkit-scheduler` `cancel` and `cancelAll` remove only alarms that have
  not fired yet, what a refused cancel leaves behind, and that `AlarmFailure.PlatformError.message`
  may be a library diagnostic; that `AlarmSchedulerConfig.alarmIdKey` and `alarmTypeKey` must stay
  stable across app versions; that an `ActivityAccess` must outlive the controllers built on it, and
  that a special-access permission request can return before the user leaves Settings when another
  window of the app is recreated; that logging sinks must not log through their own factory; and
  that `uninstall()` of the hardware-key policy leaves a window already composed with the policy it
  read.
- Internal: synchronization in `kmptoolkit-activity`, `kmptoolkit-audio-player`,
  `kmptoolkit-audio-recorder`, `kmptoolkit-flashlight`, `kmptoolkit-permission`,
  `kmptoolkit-scheduler`, `kmptoolkit-uploader`, `kmptoolkit-uploader-sqldelight` and
  `kmptoolkit-video-player` now uses `kotlinx.coroutines` tools (`CompletableDeferred`,
  `MutableStateFlow` compare-and-set) instead of `Atomic*` and `@Volatile` fields where the code
  allows it, and the places that keep a low-level primitive say why. No API or behavior change
  except where listed under Fixed.

### Fixed

- `kmptoolkit-activity`: `ActivitySubscription.cancel()` and `ActivityAccess.release()` are a strict
  stop on every thread: once they return, the listener is never called again, and they wait for a
  delivery already running on the main thread (cancelling from inside a listener never deadlocks). A
  second or concurrent `release()` no longer returns before the listeners are stopped, a listener
  added while `release()` runs is never called, and both calls drop the listener itself so a handle
  the caller keeps no longer pins what it captured. Before, a resume or replay racing a cancel from
  another thread could still reach the listener.
- `kmptoolkit-activity`: a subscription handle you keep no longer pins a released access, and a
  resume that was already in flight when `release()` ran no longer puts its activity back. A
  listener that throws during the immediate replay of `addOnActivityResumedListener` is no longer
  left subscribed without a handle (the exception still reaches the caller). With two windows of the
  app resumed at once (split screen, freeform), the access no longer reports no activity after the
  later window pauses; it answers with the window that is still resumed.
- `kmptoolkit-audio-player`: a cancelled middle prepare could let the next load (or unload's engine
  release) start while an earlier load was still unwinding; an error reported right after prepared is no
  longer lost (`Error` wins over `Ready`); an Android error that arrives after a load was cancelled no
  longer flashes `Error`; iOS end-of-item notifications already in flight no longer mark a newly loaded
  source `Completed`.
- `kmptoolkit-audio-player`: an Android callback of the previous source's `MediaPlayer` that arrives
  late no longer fails or completes the source that replaced it. On iOS, a failed or cancelled
  `prepare` releases its `AVPlayer`, item and end-of-item observer at once instead of holding them
  until the next load, unload or release.
- `kmptoolkit-audio-player`: `seekForward` and `seekBackward` saturate (clamp to the end or the
  start) instead of overflowing, and no longer rewind a source whose length is not known yet.
  `play()` after a seek onto the end starts over like `replay()`. `Playing` and `Paused` pick up a
  length the platform reports after playback started and never reset it to `0`; a length once known
  is kept by `play`, `stop`, completion and a seek out of `Completed`, an absolute seek is clamped
  to it, and a `Ready` player picks up a length learned after the load at a seek.
  `setPlaybackSpeed(Float.NaN)` is ignored, infinities clamp to the range ends, the initial rate is
  clamped into the configured range, and on Android a playback rate the device refuses no longer
  escapes `play()` or `setPlaybackSpeed()` or leaves audio playing behind a stale state.
- `kmptoolkit-audio-player`: `prepare` on a released player reports one stable `Error` instead of a
  new one per call, so a consumer that retries on `Error` no longer loops forever. A load that the
  engine ends with a `CancellationException` (its own timeout) is reported as `PlayerState.Error`
  instead of being thrown into a caller that was not cancelled, and a blank `AudioSource.File` path
  is reported as `PlayerState.Error` instead of reaching the platform, where on iOS it would end the
  process. On Android, a skip past the end of a source whose length is not known yet no longer wraps
  around to an earlier position.
- `kmptoolkit-audio-player-testing`: `FakePlaybackEngine.advancePositionBy` saturates, moves the
  playhead in a source of unknown length (`durationMs = 0`) instead of pinning it to `0`, and
  neither it nor `advancePositionTo` produces a negative position; a load, failed or not, stops the
  previous source.
- `kmptoolkit-audio-recorder`: `release()` during an in-flight prepare no longer drives the engine from
  two threads or opens the microphone after release; a low-storage reading taken before `pause()` no
  longer ends the paused recording; meter samples no longer overlap stop and release; a throw from a
  platform call before the engine runs now yields `EngineFailure` instead of leaving the recorder in
  `Preparing`; a failed or cancelled prepare no longer deletes a file that already existed at an
  explicit `outputPath`.
- `kmptoolkit-audio-recorder`: a recorder released while its `prepare()` was still opening the file
  no longer deletes the finished recording of a newer recorder that reuses the same explicit
  `outputPath`; a prepare deletes the file only while it still owns the path. A file that already
  existed there is still never deleted.
- `kmptoolkit-audio-recorder`: every deletion of a session file is fenced by the in-process claim on
  its output path. A released recorder's finalization (a `cancel()` still running at `release()`,
  the cleanup after the system ends a prepared recording), a prepared recorder's `release()` or
  re-`prepare()` and a failed `start()` no longer delete a file that a newer recorder took over on
  the same explicit `outputPath`. A later `prepare()` on the same path that fails or is cancelled no
  longer takes the path away from a recorder that is still prepared or interrupted, and a takeover
  that succeeded is final even when another `prepare()` on the path was still running. Paths spelled
  differently (repeated separators, `.` and `..`, letter case) are recognised as the same path.
- `kmptoolkit-audio-recorder`: `cancel()` deletes the file even when the worker executor refuses
  work, instead of reporting success and leaving it on disk. `prepare()` on a recorder whose worker
  context refuses work reports `EngineFailure(PREPARE)` instead of throwing a
  `CancellationException` into an active caller. On iOS, a `directoryPath` that is a regular file is
  reported as `DirectoryNotWritable`, and a failed `prepareToRecord()` no longer deletes a file the
  library did not create. The free-space watchdog keeps polling every two seconds while recording
  when `durationUpdateInterval` is longer, instead of slowing down to the timer's interval.
- `kmptoolkit-audio-recorder-testing`: `FakeAudioRecorder.release()` from `Ready` records the
  discarded file in `deletedPaths`, only a scripted `EngineFailure` of `prepare` is recorded there,
  and the fake generates `/dir/name` for a `directory` with a trailing separator or the root
  directory instead of a path with `//`.
- `kmptoolkit-biometric` (Android): the prompt's lifecycle is one atomic state. Cancelling
  `authenticate` at any moment, from any thread, either stops the sheet from appearing or dismisses
  it, and no result is delivered after a cancel. Neither the prompt nor the caller's callback (so
  not the activity either) is retained once an outcome is delivered, so a rotation during a prompt
  no longer pins the old activity. `authenticate` no longer stays suspended when the activity saves
  its state between the prompt being posted and shown; it returns `BiometricResult.NoPromptHost`.
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
- `kmptoolkit-downloader`: a late transfer of an engine whose unit another engine over the same
  directory, or the platform downloader, had already committed no longer leaves its complete temp
  file on disk; it is deleted.
- `kmptoolkit-downloader`: `cancelDownload(unit)` and `cancelDownload(group)` also remove the
  archive extraction directory an interrupted commit left behind (Android and iOS), but not while a
  commit of that unit is running; the directory is then reclaimed by the next cancel, commit or temp
  file delete, including after a process kill. The recursive delete no longer holds the process-wide
  commit lock registry, so other units' commits are not stalled. Cancelling a unit the engine has
  never tracked neither makes it track the unit nor deletes the temp data of a request that starts
  at that moment.
- `kmptoolkit-downloader`: unit ids containing `/`, NUL or an over-long name no longer collide with
  other units' files, escape `tmp/` or fail with `ENAMETOOLONG`: they are stored under a hashed name
  in `tmp/kmptoolkit-internal/`. Their 3.0.0 temp files are migrated on first use, with a move that
  never replaces an existing destination and only for files that existed before the first 4.0.0 use
  of the base directory (recorded by a marker file), so a resource another unit later commits at
  such a path is never moved. Ids that are plain file names keep their exact paths, and a
  `relativePath` equal to the unit's own 3.0.0 temp path is rejected.
- `kmptoolkit-downloader`: case variants of one id (`Model`, `model`) no longer commit or detach a
  staging tree concurrently on a case-insensitive volume, and `DownloaderStorageConfig` rejects `.`
  and `..` as `baseDirectoryName` (`IllegalArgumentException`), which would have placed the storage
  outside the platform's storage root.
- `kmptoolkit-downloader`: `markTempFileComplete` is idempotent when two callers race (the loser
  returns normally instead of throwing), `tempFileState` no longer answers `None` for an instant
  while a finished transfer is being marked complete, and `deleteTempFile` and `getResourceSize` no
  longer throw `AssertionError` (in test JVMs) when two threads clean up at once.
- `kmptoolkit-downloader`: `cancelDownload(unit)` no longer skips the removal of a leftover archive
  staging tree because other units are committing or cancelling at that moment. A
  `cancelDownload(unit)` racing a request of a never-tracked unit ends the request with
  `DownloadCancelledException` instead of `DownloadFailedException(Storage)`, and
  `cancelAllDownloads` no longer returns while a request it cancelled is still starting its
  transfer: on return no unit or group shows `Downloading` and the port holds none of the cancelled
  transfers.
- `kmptoolkit-downloader`: the error-message classifier no longer takes quadratic time on a long run
  of whitespace after "status" or "http", and a negative or overflowing failure count in the host's
  `DownloadStateStore` no longer turns the three-attempt limit into endless retries. A `Progress`
  fraction outside 0..1 from a `BackgroundResourceDownloader` no longer reaches the state flows or
  `DownloadNotifier.showProgress`: NaN and infinity are ignored and other values clamped, and an
  infinite fraction no longer stops later progress from counting toward the retry limit.
- `kmptoolkit-downloader` (iOS): archives written by a streaming ZIP writer (entries with a data
  descriptor, as `java.util.zip.ZipOutputStream` and most server libraries produce) are extracted
  completely; before, those entries were written empty and the rest of the archive was dropped
  without an error. A file name that merely contains `..` (`release..notes.txt`) is extracted
  instead of skipped, and a full disk during extraction fails the commit instead of leaving a
  truncated file. On Android, an entry named like `a/../inner.txt` is extracted as `inner.txt`
  instead of failing with `FileNotFoundException`.
- `kmptoolkit-downloader`: moving an archive's single root directory up no longer loses files when
  the root holds an entry of its own name or one named like an empty top-level directory (iOS
  deleted them, Android left them one level too deep), and iOS now moves the root up when a file
  sits beside it, as Android always did. A `SqliteDatabase` row-count check whose table name is an
  SQL keyword or contains spaces, or whose meta key contains an apostrophe, no longer reports a
  valid database as corrupt.
- `kmptoolkit-downloader`: on a case-insensitive volume, reserved `tmp/` names and per-unit commit
  locks fold case with the full Unicode mapping, so spellings such as `ſtaging-x`, `STRASSE` and
  `straße`, or `ẞ`, `ß` and `ss`, are no longer told apart.
- `kmptoolkit-hardware-keys`: the dialog-window key policy stays in force while any
  `DialogWindowHardwareKeyEffect` in that window is composed, even when the one that decorated the
  window leaves composition first.
- `kmptoolkit-hardware-keys` (Android): a dialog window whose key-policy effect was never disposed
  is no longer pinned by a global map.
- `kmptoolkit-hardware-keys` (Android): the policy runs once per key in a dialog window however
  other libraries decorate and undecorate its callback around the effect, and an effect entering
  after another library put back an older callback is served again. With a replaced policy, the most
  recently composed effect's policy decides and leaves with it, and the effect leaves a window whose
  callback was cleared alone instead of throwing. No unhandled-key listener entry is left on the
  view after each composition outside a dialog: one platform listener per view is shared and removed
  by the last effect.
- `kmptoolkit-language`: `isRightToLeft` and `AppLanguage(code)` read the script only from the
  BCP-47 script position, so `ar-u-nu-latn` is right-to-left and `en-u-nu-arab` left-to-right. A
  code written with an underscore (`ar_EG`) is applied as that locale (`applyLanguageGlobally`,
  `localizedContext`, `AppLocale`) and matched by the catalog; it used to be laid out right-to-left
  under the root locale.
- `kmptoolkit-language`: `createAppLanguageCatalog` copies `supported`, so mutating the caller's
  list no longer changes `catalog.supported` while lookups kept the original, and
  `createAppLanguageCatalog(emptyList())` with the default `fallback` throws the documented
  `IllegalArgumentException` instead of `NoSuchElementException`.
- `kmptoolkit-location`: on Android, `observeLocation()` sends the cached fix before registering its
  listeners, so a live fix is never overtaken by an older cached one; on iOS,
  `withSystemServicesPrompt` keeps at most one alert request pending.
- `kmptoolkit-location` (Android): `observeLocation()` removes the already-registered provider
  listener when a later provider registration fails.
- `kmptoolkit-location`: on Android API 24-29 the app no longer crashes with `AbstractMethodError`
  when a location provider is switched on or off, or reports a status, while `observeLocation()` is
  collected or `getCurrentLocation()` waits for a fix (present since 1.0.0).
- `kmptoolkit-location`: on Android an app holding only `ACCESS_COARSE_LOCATION` gets fixes from the
  network provider; on Android 10 and lower the GPS provider's refusal used to make
  `getCurrentLocation()` return `null` and `observeLocation()` emit `null` and end (present since
  1.0.0).
- `kmptoolkit-location`: on Android `getCurrentLocation()` answers with a cached fix held by any
  enabled provider (the freshest one, judged by the fix's monotonic age) instead of waiting out
  `singleFixTimeoutMillis` and returning `null` when the GPS provider is on without a fix, and the
  first value of `observeLocation()` is the cached fix that is youngest by the device's monotonic
  clock; after a wall-clock correction it could be an older fix stamped with a later time.
  `observeLocation()` starts on `null`, not on a stale cached fix, when every tracked provider is
  switched off.
- `kmptoolkit-location`: `observeLocation()` is conflated, so a collector that was busy ends on the
  newest position instead of a stale one.
- `kmptoolkit-logging`: a sink's `CancellationException` is rethrown only after every sink has been
  offered the event.
- `kmptoolkit-logging`: a log sink that throws an `AssertionError`, a `NotImplementedError`
  (`TODO()`) or, on Android, a `LinkageError` no longer crashes the caller or starves the sinks
  after it; fatal errors (`StackOverflowError`, `OutOfMemoryError`) still propagate. The logger
  never clears the caller's interrupt status: a thread interrupted when it logs stays interrupted,
  also when a sink consumed the interrupt and rethrew it wrapped, and a sink's bare
  `InterruptedException` has the flag set again after every sink had the event, also when a later
  sink's `Error` propagates (JVM and Android).
- `kmptoolkit-logging`: `Logger.logTimed` logs its timing line when the block leaves the enclosing
  function with a non-local `return`; that line used to be lost. `logTimed` is inline, so the fix
  reaches a call site when it is recompiled.
- `kmptoolkit-logging-overlay`: an expanded row no longer carries over to the unrelated record that
  reuses its id after `clear()`. `LogOverlayState.asLogSink()` keeps the record, with a placeholder
  for the stack trace, when the throwable cannot render itself, and no longer throws
  `StackOverflowError` for a throwable with a very deep cause chain, including a chain of exceptions
  that compare equal (it records the first 64 causes). Records written concurrently carry elapsed
  stamps that never decrease along the list, and `LogOverlayPanel` scrolls to a record that arrives
  while the list shows its top.
- `kmptoolkit-notification`: a progress frame becomes the coalescing baseline only after the platform
  accepted the post, so retrying the same progress after a failed post is posted instead of returning
  `Coalesced` (Android and iOS). A `cancel` is ordered against in-flight posts, so it is no longer
  followed by a post it should have cancelled, and a post (progress or not) overtaken by a newer post
  for the same id, or by a `cancel`, is dropped.
  On iOS, an add rejected because authorization was revoked after the check returns `PermissionDenied`
  instead of `Failed`.
- `kmptoolkit-notification`: progress coalescing no longer trusts a frame that was never shown. When
  posts of one id overlap and fail, a retry is not judged redundant against such a frame, a failed
  newer frame no longer drops an older frame still being posted, a frame that gave up because a
  newer one overtook it is neither remembered as shown nor revived when the newer one fails, and two
  iOS frames refused by the notification center leave no baseline. Baselines of ids that went quiet,
  bookkeeping of unfinished frames and the 2.x sweep cache are released over time instead of growing
  with every distinct id. On Android, a refused legacy sweep no longer turns a shown post into
  `Failed`, and a refused or dropped frame no longer changes where the frame on the tray opens the
  app.
- `kmptoolkit-notification` (Android): the tap target of a frame is written only after the platform
  has accepted it, whatever order concurrent posts of one id were built in, so a frame that is
  dropped (overtaken by a newer or a terminal frame) or refused no longer changes where the
  notification already on the tray opens the app, and the progress baseline is the frame that last
  reached the tray. `Notifier.cancel` and `cancelAll` also order against a `post` that is still
  checking the notification permission: that post answers `Coalesced` instead of showing a cancelled
  notification afterwards. On iOS, a post cancelled while the notification center was answering no
  longer lets an older frame's progress suppress the next update.
- `kmptoolkit-notification` and `kmptoolkit-scheduler` (Android): ids that differ only in unpaired
  surrogates (equal hash codes included) shared `PendingIntent` keys or the alarm data URI; they are
  now distinct. Keys of other ids are unchanged, and an alarm armed by 3.0.x for an id with an
  unpaired surrogate is still replaced by `schedule` and removed by `cancel`.
- `kmptoolkit-permission`: `PermissionRequestFlow` no longer stays in `Requesting` after the caller is
  cancelled (it goes back to `Idle`, and the next `refresh()` reads the real choice), and a stale
  refresh or start result no longer overwrites newer state. A special-access
  request from a background dispatcher no longer returns early while the Settings screen is open, a
  request cancelled before its dialog is posted no longer shows the dialog, and `observe` subscribes to
  resolved requests before its first status read on any dispatcher, so it no longer misses a request
  that resolves as collection starts. A pending request whose activity finished is abandoned (also when
  the next activity resumes before the finished one is destroyed), so it no longer blocks later
  requests. The iOS "when in use" location request no longer waits forever when no prompt appears.
- `kmptoolkit-permission`: `PermissionRequestFlow.reset()`, `rationaleDismissed()` and
  `settingsDeclined()` are atomic and a request's outcome is published with a compare-and-set, so
  concurrent calls can no longer overwrite a running request or start a second one. On Android, a
  request queued behind one that was cancelled while its system dialog was still up waits (at most
  30 seconds) for that dialog to be answered and then shows its own; if the stale dialog is still up
  after the wait, it fails like a refused launch (before, it returned the unchanged status at once
  with nothing shown).
- `kmptoolkit-permission`: `PermissionRequestFlow` no longer lets a stale `refresh()` overwrite the
  state of a `start()` that began and was cancelled while it was reading, and it returns to `Idle`
  when the controller's `request` throws instead of staying `Requesting` for good (the exception
  still reaches the caller).
- `kmptoolkit-permission` (Android): the default dialog host no longer keeps an activity the system
  destroyed (to recreate it later) while its dialog is up (the answer still reaches the request
  through the recreated activity), and it drops the registration of every activity a request was
  shown from when it ends, including a covered activity and an answer parked in a recreated one. The
  answer to a dialog that was up when the process died no longer resolves the first request made
  after the app is restored. Another activity of the app that opens and finishes while a permission
  dialog is up no longer ends the request, and the activity the dialog came from finishing behind
  another one now ends it instead of blocking the host.
- `kmptoolkit-permission` (Android): `request(permissions)` no longer shows the same Android
  permission twice when two entries stand for it on the running API level (a Bluetooth scan below
  API 31 is location) and the user refuses; it waits for an activity at most once per call. A role
  dialog interrupted by its activity finishing is no longer recorded as the user's refusal (two
  interruptions no longer read as `PermanentlyDenied`), and an entry whose permission string was
  refused for good through another entry (`PhotoLibrary` and `AudioLibrary` below API 33, `Calendar`
  and `CalendarWriteOnly`) now reads `PermanentlyDenied` instead of asking again and showing
  nothing; `observe` of such an entry re-reads after that request instead of at the next resume.
- `kmptoolkit-permission` (Android): a flag the storage cannot read, write or remove is reported to
  the logger (a warning once per flag) instead of being swallowed, and still treated as not set. A
  platform call that throws while reading a runtime permission (the notification switch below API
  33, the device-policy, rationale or protection-level lookup) no longer escapes `check`, `request`
  or `observe`; it reads as the status the platform cannot contradict (notifications off, not
  restricted, rationale unknown, unknown permission) and is logged once.
- `kmptoolkit-permission` (Android): a request made right after another one was answered (from a
  caller on `Dispatchers.Main.immediate` such as `viewModelScope`, in the same coroutine or in one
  batch) now shows its dialog, role or device-admin screen instead of reporting that it could not be
  shown, and a special-access request made that way no longer returns before the user has left for
  Settings and come back. The controller waits (up to a second, once per call) for an activity of
  the app to be resumed before it puts system UI on screen, so in a backgrounded app a request can
  now take up to a second to be reported as not shown.
- `kmptoolkit-scheduler` (iOS): `schedule` no longer reports `Exact` for a request iOS would silently
  discard past its 64-pending-notification cap; with 64 other requests pending it returns
  `Failed(PlatformError)` and adds nothing (re-scheduling a pending id still succeeds). The check and
  the add run under one process-wide lock, and `cancel` and `cancelAll` take the same lock, so a cancel
  can no longer be overtaken by an in-flight schedule.
- `kmptoolkit-scheduler` (Android): `AlarmScheduler.schedule` reports any `RuntimeException` from
  `AlarmManager` as `Failed`, a replacement the platform refuses leaves the armed alarm exactly as
  it was, `cancelAll` copies its ids first, and a `schedule` call cancelled while waiting for the
  platform lock is cancelled instead of returning `Failed`.
- `kmptoolkit-scheduler` (Android): an id keeps up to four `PendingIntent` slots, and every
  superseded one is cancelled by the next successful `schedule`, so a replacement the platform
  refuses never rewrites the content of an alarm that stays armed after an earlier replacement's old
  alarm could not be cancelled. When every slot of an id is occupied by alarms whose cancel was
  refused, `schedule` answers `Failed(PlatformError)` and leaves the armed alarm untouched, and
  `cancel(id)` frees the slots.
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
- `kmptoolkit-storage`: `JvmKeyValueStorage.clear()` no longer deletes the in-flight temporary file
  of another store in the same directory whose file name starts with this store's file name.
- `kmptoolkit-storage`: desktop key-value stores whose names a case-insensitive volume treats as one
  file (letter case, Unicode form, `ß` and `ss`, Greek sigma forms, `ẞ`) share one lock, so they no
  longer lose each other's writes on macOS and Windows.
- `kmptoolkit-storage`: `DeviceIdProvider` no longer waits for an unrelated store: a stored id is
  read without a lock, and only creating an id is serialized. On Android, a plain store value ending
  in a line break no longer comes back with trailing spaces after the process restarts (the file now
  holds one U+E000 after such a value; a value already damaged by 3.0.0 is returned as it is).
- `kmptoolkit-systembars`: `release()` is atomic with concurrent pushes (ids are never reused),
  icon-style samples never overlap, and a new `onSampled` lambda no longer restarts sampling.
- `kmptoolkit-systembars` (Android): `Context`-based `createSystemBarsController` and
  `createScreenWakeLockController` share one activity tracker per `Application`, so creating and
  releasing controllers no longer leaves a lifecycle callback behind each. A wake-lock controller
  subscribes to resumes only while it has a flag to apply, and releasing it also clears
  `FLAG_KEEP_SCREEN_ON` from an earlier window of the app that the hold had put it on.
- `kmptoolkit-systembars` (Android): releasing a screen wake lock clears `FLAG_KEEP_SCREEN_ON` from
  every window the hold put it on at once, including an earlier window and one still resumed beside
  another (split screen), and a hold taken while two windows are already resumed flags both. A lock
  released by one controller no longer clears the flag while another controller still holds it on
  the same window. A released controller never writes to the window again, queued writes included.
- `kmptoolkit-systembars-testing`: `RecordingSystemBarsController` records no platform write after
  `release()`, matching the real controller; `config` still follows later writes.
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
- `kmptoolkit-uploader`: engine hardening. `enqueue` arms the drain trigger and the platform wake
  even when its caller is cancelled after the row committed; `awaitDrained` returns `true` for an
  empty queue with any timeout (zero included) and returns `false` at the timeout when the store
  keeps failing to read, instead of throwing; a `ConstraintProvider` that throws keeps only its own
  item waiting and no longer stops the pass for other handlers; a `Logger` that throws no longer
  stops the drain (the line is lost, the work is not); cancelling the engine from inside a handler
  stops the pass even over a store that never suspends.
- `kmptoolkit-uploader`: an engine whose owning scope is cancelled without `close()`, or that is
  already closed, is unregistered from `UploaderEngineRegistry` and never handed out, and
  `UploaderEngineRegistry.await` finds a registered engine with a zero wait.
  `RetryPolicy.maxDelayMillis = Long.MAX_VALUE` and `AttemptResult.Detached(Long.MAX_VALUE)` no
  longer overflow, and a lease written before a backwards wall-clock step no longer freezes its item
  and ordering channel for the whole jump.
- `kmptoolkit-uploader` (iOS): a session dropped without a task (a cancel of an item that is not
  uploading, a hand-off that fails before creating its task, an idle relaunch session) no longer
  leaves its progress forwarder suspended for the life of the process. A "finished events" signal
  that arrives before the relaunch completion handler is registered, and a second registration on
  the same session, no longer fall back to the 25 s safety timeout. A relaunch wake on a session
  that stays live no longer hands the OS completion handler back before that wake's events are
  delivered; each wake waits for its own signal. An early signal is remembered on a session the wake
  opens; on a session still live from an earlier wake it can land before the wake starts and is
  dropped, so that wake waits for the settlement or the 25 s timeout, as before.
- `kmptoolkit-uploader`: `cancel`, `cancelByTag` and an `enqueue(REPLACE)` whose caller is cancelled
  while the call runs (a cleared `viewModelScope` during a logout wipe, say) still stop the running
  attempt and cancel the upload of every item they removed; once begun, the removal and its side
  effects complete together. An item the drain had already read is no longer attempted after such a
  call returns, and a plain handler's attempt running for a withdrawn item is cancelled (no attempt
  spent) instead of holding up the queue. An upload withdrawn while it was being handed off is
  cancelled right after its launch, also over a store that switches dispatchers (the SQLDelight
  one). An item that settled or was withdrawn while its handler prepared the upload is no longer
  uploaded.
- `kmptoolkit-uploader`: an attempt cancelled by a withdrawal or by `close()` records nothing when
  its handler answers the cancellation with `Retry`, `Park` or `Drop`. A `Success` is always
  recorded, even when `close()` lands while the delivered item's row is being removed, so the next
  engine does not run the effect again. A `Detached` answer given just as `close()` lands is
  recorded under its lease (unless the item was withdrawn): the next engine waits for it and then
  re-hands it with `wasDetached = true`, instead of running it at once as a first attempt. After
  `close()`, an engine whose `prepareUpload` ignored the cancellation, or whose claim finished after
  `close()` returned, no longer launches the upload; the claim is released, but only if the row
  still carries that exact lease, so the late release of a closed engine never resets a claim a
  replacement engine made on the same item (which let the item upload twice). A `close()` while a
  claim was being written no longer leaves the item in flight for a whole lease.
- `kmptoolkit-uploader`: a `Logger` that throws (a `CancellationException` or anything else) no
  longer breaks `enqueue`, `close` or the drain, nor the Android and iOS transports, wake schedulers
  or the outcome journal. A `ConstraintProvider` whose flow fails when collected no longer reaches
  the scope's uncaught-exception handler; its items are still checked on every pass. An exception
  whose `message` getter throws no longer stops a failed attempt from being recorded.
- `kmptoolkit-uploader`: a drain pass that fails (for example on a locked database) is retried after
  a growing delay (from `minAlarmDelay`, at most `heartbeatInterval`) instead of waiting for the
  heartbeat, and an attempt that fails outside its handler backs off instead of repeating every
  `minAlarmDelay`. An effect whose confirming delete failed is not executed again by the same
  process; only the delete is retried. A retry with a zero backoff is no longer re-run inside the
  same drain pass, and a store whose `getById` disagrees with `getAllActive` no longer makes the
  drain re-read the queue in a loop (the item is skipped and logged).
- `kmptoolkit-uploader`: gates and leases are compared without overflow at extreme clock readings,
  `ExponentialBackoffRetryPolicy` saturates at `maxDelayMillis` instead of wrapping to a zero delay
  for a very large `baseDelayMillis`, ordering channels of two handler types whose type and key
  happen to concatenate alike no longer block each other, and a `CancellationException` thrown by
  `decodePayload` parks the item instead of retrying it forever (real cancellation still
  propagates).
- `kmptoolkit-uploader` (Android): with `createWorkManagerUploadHandlerTransport`, a retry handed
  off while the item's previous job was still finishing is no longer dropped. `WorkManager` still
  reported that job running, so the hand-off was ignored and the item waited in flight until its
  lease expired. The running job now uploads such a retry itself before it returns, and a job that
  has already finished is replaced by a fresh one. A second job for the same item (WorkManager
  reports a stopped job finished while its transfer or settlement still runs) waits for the first
  instead of uploading the file again. The legacy `createWorkManagerUploadTransport` is unchanged.
- `kmptoolkit-uploader` (Android): an upload's journaled answer is deleted only after its settlement
  is committed and carries the attempt it answers, so a store failure, a cancellation thrown by the
  store, a stop of the job or a process death during the settlement no longer makes the file upload
  again, and a leftover answer of an attempt already settled no longer replaces the next upload. An
  answer whose journal entry cannot be read back, or reads back damaged or expired, is settled from
  memory instead of being treated as handled; an unreadable entry at the start of a job is waited
  for while the job has run fewer than 3 times. A status the server sent before WorkManager stopped
  the job during the response body is kept and settled by the re-run, and the classic worker's
  settlement write is no longer cut off after `engineWait`. The first answers journaled on a fresh
  install no longer go unprotected when two items finish at once, and a blocking `onUploadProgress`
  no longer delays other items' settlements.
- `kmptoolkit-uploader`: `onSettled` no longer runs for a park or failure that a newer settlement
  had already superseded, runs once when two settlements of one item race, and still runs for a
  delivery withdrawn mid-settlement. A damaged outcome-journal file too large to be a journal entry
  is deleted instead of failing every attempt of its item.
- `kmptoolkit-uploader` (Android): multipart field and file names are escaped (`"`, CR, LF) as
  browsers do, and CR and LF are removed from a part's content type (also on iOS). A directory named
  as an upload source is refused before the server is contacted, a `304` (and 1xx, 204, 205) is
  never flagged as a truncated body, a redirect whose headers declare an empty body is not flagged
  `isBodyTruncated`, and a 401 or 407 whose body the stack could not deliver is flagged truncated
  instead of reported as empty. An upload whose source file fails to read mid-body is dropped
  without completing the request; before, the server could receive the truncated body as a complete
  request. A server that sends a status line and then never finishes its headers fails the attempt
  as a transport failure instead of being taken as its answer.
- `kmptoolkit-uploader` (iOS): an upload whose connection broke after the server's status arrived
  (the body was cut short, or a timeout fired while it arrived) now reports that status with
  `isBodyTruncated = true`, as on Android, instead of a transport failure, so an accepted file is no
  longer uploaded again. An item whose id is not a plain file name (it contains `/`, for instance,
  or is very long) now uploads; before, writing its temporary body failed on every attempt.
- `kmptoolkit-uploader`: a worker stopped while `prepareUpload` ran is no longer charged a failed
  attempt, whatever kind of store is used.
- `kmptoolkit-uploader`: a `cancel` whose store delete fails leaves an item that is still queued,
  together with its running attempt and upload, untouched, and throws; calling it again withdraws
  the item. A `REPLACE` enqueue stops and cancels the superseded item before it arms the wake, so a
  `WakeScheduler` that throws (against its contract) no longer leaves that upload running.
- `kmptoolkit-uploader-sqldelight`: `UploaderStorage.close()` called on the database thread throws
  instead of deadlocking, and a second `close()` is always a no-op (also on iOS, where a thread id can
  be reused). The transaction-await deadlock and the close order are documented.
- `kmptoolkit-uploader-sqldelight`: `createUploaderStorage` (iOS) closes a driver that was opened
  but failed its first probe (a corrupt file, a failed migration) before it throws
  `UploaderStorageException`, and `UploaderStorage.close()` releases the database thread even when
  closing the driver throws.
- `kmptoolkit-uploader-sqldelight`: using a closed storage fails with `UploaderStorageException`,
  never with a `CancellationException`, and the store keeps a row within its 1 MiB limit when a
  failure text is written after the insert (the text is cut to fit).
- `kmptoolkit-uploader-testing`: `FakeUploader` is thread-safe and its result hook may call back into
  the fake.
- `kmptoolkit-uploader-testing`: `MutableUploaderClock` and `RecordingWakeScheduler` are safe to use
  from several threads.
- `kmptoolkit-video-player`: the Android engine no longer lets a position refresh posted before an
  off-thread `seekTo` or `pause` undo it, and the iOS engine no longer clears a newer seek's target
  when an older seek to the same position is interrupted.
- `kmptoolkit-video-player`: a `prepare` no longer starts a third load while the first is still
  unwinding when a waiting prepare is superseded and then cancelled, and no longer calls
  `engine.release()` after `dispose()` when its caller is cancelled right after `release()`. On
  Android, a position read made right after `seekTo` or `pause` from another thread no longer
  returns the pre-call position while a player refresh is in progress.
- `kmptoolkit-video-player`: a length once known is kept by `play`, `stop`, `replay`, completion and
  the `RepeatMode.One` restart instead of falling back to `0` when the platform briefly reports
  none, an absolute seek is clamped to it, and a `Ready` player picks up a length learned after the
  load at a seek. A duration the platform reports after playback started replaces the `0` of "not
  known yet" in `Playing` and `Paused`, and a later `0` never overwrites it. `seekForward` and
  `seekBackward` on a source of unknown length skip relative to the playhead instead of rewinding to
  `0`, `play()` with the playhead on the end of the source starts over from `0`, and a stall
  reported after the end is not shown as buffering. The playback rate a player starts with is
  clamped into the configured range.
- `kmptoolkit-video-player`: the next `prepare` always frees the previous source first, including
  one that failed during playback, and a failure, stall or picture size the engine reports for a
  source being dropped can no longer become the next `prepare`'s outcome. `release()` disposes the
  engine and resets to `Idle` even when the engine's `release()` throws, `unload()` cannot leave the
  next `prepare` waiting forever, and a setting the engine refuses at the end of `prepare` ends in
  `Error` instead of leaving the player `Preparing`. `media3PlayerOrNull()` is `null` from the
  moment `release()` has returned, also while a load is still unwinding.
- `kmptoolkit-video-player`: an end-of-source report that arrives just after a seek away from the
  end no longer marks the player `Completed`, and with `RepeatMode.One` a completion while paused
  rewinds without starting playback. A `stateFlow` consumer on an immediate dispatcher that pauses,
  seeks, unloads or releases from inside an update no longer leaves a running position poll, a stale
  playhead or a stale stall behind.
- `kmptoolkit-video-player` (Android, Media3): the engine no longer calls its listener after
  `release()` returned, even for an event already queued behind a busy main looper; a failure or
  stall it reports after a load succeeded but before `prepare` publishes `Ready` is no longer lost
  (the player settles on `Error`, or shows buffering with `Ready`), and it ignores a playback rate
  that is not finite and positive.
- `kmptoolkit-video-player-compose`: `VideoSeekBar` releases its handle when the bar becomes inactive
  while a seek the player dropped was pending.
- `kmptoolkit-video-player-compose`: `rememberVideoPlayer` and `VideoPlayer(source = ...)` release
  their player when the composition that created it is discarded before it was applied (for example
  a dropped lazy-list prefetch), not only when it leaves the composition. `VideoPlayer(source = ...,
  autoPlay = true)` no longer starts playing when the source finishes loading while the app is in
  the background (with `pauseOnBackground`).
- `kmptoolkit-video-player-compose`: `VideoPlayerSurface` no longer throws from layout for a picture
  of an extreme shape (for example 1:10000); its measured sizes are capped at 32 766 px.
  `VideoSeekBar` refuses a NaN accessibility progress instead of throwing, and the default speed
  button offers only speeds inside the player's configured range, so it no longer dead-ends below
  the default speeds' maximum.
- `kmptoolkit-video-player-javafx`: the engine's `isJavaFxMediaAvailable()` and first `prepare()` no
  longer stall for 10 s and report the runtime unavailable for the rest of the process when called on
  the JavaFX thread (a busy thread is not remembered as unavailable, a runtime whose thread check
  fails stays unavailable instead of throwing), `prepare()` after the toolkit has exited fails with
  `RuntimeUnavailable` within the ready timeout, and the frame pulse stops for audio-only sources and
  once a paused picture has settled.
- `kmptoolkit-video-player-javafx`: a load fails at once, and `isJavaFxMediaAvailable()` answers
  `false`, when `javafx.media` is not on the classpath (also when called on the JavaFX thread) or
  after the toolkit has exited (the host's last window closed and its implicit exit, or a dead
  JavaFX thread, took it down), instead of waiting out the ready timeout. A silent heartbeat counts
  as an exit only while implicit exit is on and never while that thread runs the engine's own work,
  such as building the first player on a cold start, so a busy thread no longer fails a load. A
  player released just as its looping or ending source reaches the end no longer makes JavaFX throw
  a `NullPointerException` on its application thread.
- `kmptoolkit-video-player-javafx` and `kmptoolkit-video-player-vlcj`: a playback rate that is not
  finite and positive and a NaN volume are ignored, as in the Media3 engine (the VLC engine no
  longer throws on a NaN volume), and a `VideoSource.Asset` whose class loader returns a `file:` URL
  that is not percent-encoded (a space, a non-ASCII character or `%` in the path) plays instead of
  failing with `URISyntaxException` or `IllegalArgumentException`.
- `kmptoolkit-video-player-testing`: `FakeVideoPlaybackEngine.advancePositionBy` saturates instead
  of wrapping around, and the `releaseCount` documentation names the release on a re-prepare.
- `kmptoolkit-video-player-vlcj`: the engine no longer overwrites a seek target with libvlc's pre-seek
  clock, so quick repeated seeks step from the target.
- `kmptoolkit-video-player-vlcj`: a player dropped without `release()` while a source was loaded
  frees its native media player when garbage-collected, not only the libvlc instance, and libvlc's
  callbacks can no longer be freed while the native player can still call them (a player that is
  still playing is not collectable; release it). Temporary copies of jar-packed assets are deleted
  explicitly instead of through `File.deleteOnExit()`, which grew a JDK-global set by one entry per
  load; a copy that cannot be deleted when its source closes (on Windows, while still held open) is
  retried by the shutdown hook, and disposing an engine while its own teardown claims the last open
  player no longer throws `NoSuchElementException`.
- `kmptoolkit-video-player-vlcj`: a seek made before playback began, or between `start()` and the
  first output, is held over stale clock readings and applied once output exists, and a seek made
  while a looping source restarts is applied once the restarted source has output, instead of being
  sent to libvlc before there is anything to seek in. The engine keeps the playhead where a source
  of unknown length ended instead of reporting `0`.
- `kmptoolkit-video-player-vlcj`: the engine no longer calls into libvlc while holding a lock its
  event callbacks need; libvlc 3 makes a seek wait for a running event callback, so a seek could
  hang the calling thread, and the player's release after it, for good. A restart made right after a
  source ended, from any thread, is queued behind VLCJ's own reset of the ended media, so it is no
  longer lost while the player reports `Playing`.
- `kmptoolkit-video-player-vlcj`: a speed outside libvlc's 1/32..32 plays at the nearest bound
  instead of handing libvlc a rate it does not play (the clock could stand still while the player
  reported `Playing`), and a seek target is capped so libvlc's microsecond conversion cannot
  overflow (a skip far past the end of a source of unknown length was ignored by libvlc).

### Known issues

- `kmptoolkit-downloader`: when two engines with separate storage instances over the same base directory request the same unit at almost the same time, one may start a second transfer after the first has finished but before it is committed. The resource is always committed whole, but a full-size partial temp file of that unit can stay in `tmp/` until `cancelDownload(unit)`. With a downloader that restarts from byte zero (`beginTempFile`), one caller can instead fail once with `DownloadError.Storage`; retrying succeeds. A single engine per directory is not affected. Fixed in 4.1.0.

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
