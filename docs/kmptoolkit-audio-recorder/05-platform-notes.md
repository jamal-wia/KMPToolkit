# kmptoolkit-audio-recorder — Platform notes

What the consuming app must declare, and where the two platforms genuinely differ behind the common
interface.

## Permissions and manifest entries — your responsibility, not this library's

### Android: `RECORD_AUDIO`

**This module does not declare `RECORD_AUDIO`, and it never will.** Declare it in your own app:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

Consistent with [`../01-architecture.md`](../01-architecture.md#android-manifests): a permission in a
library manifest is merged into every consumer's app silently. `RECORD_AUDIO` is a runtime,
dangerous permission that shows up in the Play Store listing and in the app's permission screen —
adding it to an app that merely depends on this library, perhaps without recording anything, is not
a decision a library gets to make. The module has an `androidUnitTest` case that asserts the
permission is absent from the merged manifest, so this cannot regress by accident.

`RECORD_AUDIO` is also a **runtime** permission (dangerous, since API 23). Declaring it is not
enough; you must request it and the user must grant it.

**This module never requests it either.** It only reports:

```kotlin
when (recorder.prepare()) {
    is RecorderResult.Failure -> // RecorderError.PermissionDenied → run your own permission flow
    is RecorderResult.Success -> // granted
}
```

`prepare()` checks `Context.checkSelfPermission(RECORD_AUDIO)` before it touches `MediaRecorder`, so
a missing grant is a `RecorderError.PermissionDenied` value rather than the `RuntimeException`
`MediaRecorder.start()` would throw. Requesting the permission needs an Activity, a rationale
dialog, and copy in the user's language — all of which belong to the app.

The check runs on every `prepare()`, so the recover path is simply: request, then prepare again.

### iOS: `NSMicrophoneUsageDescription`

```xml
<key>NSMicrophoneUsageDescription</key>
<string>Records your voice notes.</string>
```

**Unlike the Android case, this one is not recoverable.** If the key is missing, iOS terminates the
app the moment it touches the microphone — before any Kotlin code can turn it into a
`RecorderError`. There is no fallback a library can provide; the key must be there.

`prepare()` checks `AVAudioSession.recordPermission` and reports
`RecorderError.PermissionDenied` when the user has not granted access. It does **not** call
`requestRecordPermission` — that shows a system prompt, and when that prompt appears is a product
decision.

### Background recording

Neither platform records in the background without extra setup that this library does not do: a
foreground service with `foregroundServiceType="microphone"` on Android, the `audio` background mode
on iOS. Recording stops or is interrupted when the app is backgrounded. Add that infrastructure in
your app if you need it.

## Format support

| `AudioFormat` | Android | iOS |
|---|---|---|
| `M4A` | `OutputFormat.MPEG_4` + `AudioEncoder.AAC` | `kAudioFormatMPEG4AAC` |
| `AAC` | `OutputFormat.AAC_ADTS` + `AudioEncoder.AAC` | `kAudioFormatMPEG4AAC` (container from the extension) |
| `WAV` | **unsupported** — `prepare` returns `UnsupportedFormat` | `kAudioFormatLinearPCM`, 16-bit, little-endian |

`MediaRecorder` has no linear-PCM output format. Rather than write AAC into a file named `.wav` —
which is what the code this module was ported from did — `prepare()` refuses. If you need WAV on
both platforms, record `M4A` and transcode, or use `AudioRecord` directly.

`bitRate` is ignored for `WAV`, which is uncompressed.

## Android

- **`MediaRecorder` instance per recording.** A fresh one is built in `prepare()` and destroyed in
  `release()`. Reusing one across recordings would mean driving `reset()` correctly from every state
  the platform machine can be in; a new allocation is cheaper than that class of bug.
- **`MediaRecorder(context)` on API 31+**, the deprecated no-arg constructor below it. The context
  variant lets the platform attribute the recording to the app for privacy indicators.
- **`pause()`/`resume()` exist unconditionally.** They landed in API 24, which is this library's
  `minSdk`. They still throw for output formats that do not support them, which surfaces as
  `EngineFailure(PAUSE, cause)` with the recording still running.
- **Asynchronous errors are not surfaced live.** `MediaRecorder` reports mid-recording problems (the
  microphone taken by a phone call, the encoder dying) through `setOnErrorListener`, on a platform
  thread. This module does not install one, because delivering it would mean mutating recorder state
  from a thread the single-threaded contract forbids. Such a failure surfaces at the next operation
  — usually `stop()` returning `EngineFailure(STOP, cause)`. If your app needs live notification of
  a lost microphone, watch `AudioManager`'s focus callbacks yourself.
- **Free space** comes from `File.usableSpace` on the output directory, which respects per-user
  quotas. If it cannot be determined the check is skipped rather than failing the recording.
- **Directory creation** uses `File.mkdirs()` and then checks `isDirectory && canWrite()`. A
  `SecurityException` from a path outside the sandbox is exactly the "not writable" answer, so it is
  reported as `DirectoryNotWritable`, not thrown.
- **Scoped storage.** The default output is app-private (`Context.getFilesDir()`), which needs no
  storage permission. Pointing `RecordingStorage.directoryPath` at shared storage is your call, and
  brings that platform's permission rules with it.

## iOS

- **`AVAudioSession` is shared process state.** `prepare()` sets the category to
  `AVAudioSessionCategoryPlayAndRecord` and activates the session; `release()` (and the release
  inside `stop()` / `cancel()`) deactivates it, so other audio recovers as soon as recording ends
  rather than whenever the object is collected. If your app manages the session centrally, be aware
  that this module touches it.
- **`AVAudioRecorder` reports failure by returning `false`**, and Kotlin/Native cannot catch an
  Objective-C exception at all. Every `false` is converted into a Kotlin exception internally, which
  is why `EngineFailure.cause` is often a plain `IllegalStateException` with no platform detail: that
  is genuinely all the platform said.
- **The container comes from the file extension.** `M4A` and `AAC` both encode with
  `kAudioFormatMPEG4AAC`; the `.m4a` / `.aac` extension decides how it is wrapped. Passing an
  explicit `outputPath` with an unexpected extension can therefore produce a file whose container
  does not match your `AudioFormat` — keep the extension consistent with the format.
- **The default directory is `Documents`**, which is backed up by iCloud and visible in the Files app
  if the app opts in. Point `RecordingStorage.directoryPath` at `Library/Caches` for recordings you
  do not want backed up.
- **Free space** comes from `NSFileSystemFreeSize`, which reports the volume's free space and does
  not account for iOS's "purgeable" space — it can under-report what is actually available.
- **The bundle identifier** is the default subdirectory name. In a unit-test host or a command-line
  binary, where `CFBundleIdentifier` is absent, it falls back to `recordings`.

## Behavior that is identical on both platforms

Everything else, because it lives in common code and is covered by one shared suite that runs on
both targets: the transition table, permission and storage pre-checks, path generation, elapsed-time
accounting across pause and resume, deletion of abandoned files, retention of completed ones,
cancellation of an in-flight `prepare`, release idempotency, and the fact that no public method
throws.
