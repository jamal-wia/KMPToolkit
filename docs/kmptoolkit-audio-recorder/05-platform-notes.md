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
on iOS. Add that infrastructure in your app if you need it. What happens without it differs, and
the library reports it: see "When the system ends a recording" below.

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
- **System events are reported.** `setOnErrorListener` and `setOnInfoListener` are installed once
  `prepare()` succeeds and removed before `stop()` and in `release()`; on Android 10+ an
  `AudioRecordingMonitor` callback reports a silenced input too. See "When the system ends a
  recording" below.
- **Free space** comes from `File.usableSpace` on the output directory, which respects per-user
  quotas. A directory that does not exist, or a path that throws, is reported as unknown, and an
  unknown skips the check rather than failing the recording — `usableSpace` answers `0` for a
  missing path, which would otherwise be indistinguishable from a genuinely full volume.
- **Directory creation** uses `File.mkdirs()` and then checks `isDirectory && canWrite()`. A
  `SecurityException` from a path outside the sandbox is exactly the "not writable" answer, so it is
  reported as `DirectoryNotWritable`, not thrown.
- **Scoped storage.** The default output is app-private (`Context.getFilesDir()`), which needs no
  storage permission. Pointing `RecordingStorage.directoryPath` at shared storage is your call, and
  brings that platform's permission rules with it.

## iOS

- **`AVAudioSession` is shared process state.** `prepare()` sets the category to
  `AVAudioSessionCategoryPlayAndRecord` and activates the session; `release()` (and the release
  inside `stop()` / `cancel()`) deactivates it with
  `AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation`, so a backgrounded music app resumes
  promptly rather than whenever it notices. Deactivation happens on every failure path too,
  including one where the recorder was never constructed — an active `PlayAndRecord` session left
  behind would duck every other app's audio for the rest of the process. If your app manages the
  session centrally, be aware that this module touches it. The category is not configurable;
  `PlayAndRecord` rather than `Record` so an app that plays the recording back does not have to
  fight the module over the session.
- **Interruptions are observed.** An `AVAudioRecorderDelegate` (held by a strong field — the
  recorder's own `delegate` property is weak) and observers for
  `AVAudioSessionInterruptionNotification` and `AVAudioSessionMediaServicesWereResetNotification`
  report what the platform does on its own. They are removed in `release()` and before `stop()`,
  so a recording you ended is never reported as one the system ended. See "When the system ends a
  recording" below.
- **Session activation errors are not swallowed.** The `NSError` of `setCategory` and `setActive` is
  captured; a session the system refuses to configure fails `prepare()` as
  `EngineFailure(PREPARE, cause)` with the platform's description, instead of failing later as a
  misleading `START` failure.
- **`release()` always stops the recorder.** An interrupted `AVAudioRecorder` reports
  `recording == false` while still holding its file open, so the recorder is stopped
  unconditionally rather than only when it claims to be recording.
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
  not account for iOS's "purgeable" space — it can under-report what is actually available. A path
  the platform cannot answer for is reported as unknown, and an unknown never blocks a recording.
- **`AVAudioRecorder`'s initializer is failable.** It returns `nil` with an `NSError` for settings
  CoreAudio rejects — an unusual sample rate and channel-count combination, say. Kotlin/Native binds
  it as non-null, so the `NSError` is captured explicitly and folded into
  `EngineFailure(PREPARE, cause)` rather than being left to crash on first use.
- **The bundle identifier** is the default subdirectory name. In a unit-test host or a command-line
  binary, where `CFBundleIdentifier` is absent, it falls back to `recordings`.

## When the system ends a recording

*Since 2.2.0.* The recorder moves `state` on its own when the platform ends a recording, to
`Interrupted` (file finalized) or `Failed(RecordingLost)` (not). Before 2.2.0 such a failure was
deliberately not surfaced and appeared only when `stop()` failed, with the state stuck on
`Recording` and `elapsed` still counting; that is no longer so. The common behaviour — first event
wins, a running `stop()`/`cancel()` wins over an event, `elapsed` frozen, `level` zero — is in
[`03-guide.md`](03-guide.md#when-the-system-ends-a-recording). What differs per platform:

### Android

- **Sources.** `MediaRecorder.OnErrorListener` (`MEDIA_ERROR_SERVER_DIED`,
  `MEDIA_RECORDER_ERROR_UNKNOWN` → `EngineDied`), `OnInfoListener` (`MAX_FILESIZE_REACHED` →
  `StorageLow`; `MAX_DURATION_REACHED` cannot fire, no duration limit is ever set), and — API 29+ —
  `AudioRecordingMonitor`. The callbacks arrive on the Looper of the thread that created the
  recorder or the main Looper; the library never depends on which.
- **Backgrounding records silence; it does not stop the recording.** Since Android 10 an app that
  captures from the background without a microphone foreground service keeps its `MediaRecorder`
  running and gets silence. The library reports this as `Interrupted(MicrophoneSilenced)` once the
  input has stayed silenced for about 400 ms, with `elapsed` frozen where the silencing began. A
  call or another app with capture priority does the same.
- **API 24–28 cannot detect it.** There is no silencing callback below API 29. A recording
  silenced there simply records silence; nothing is reported. On those versions keep the app in the
  foreground or run the microphone foreground service the platform asks for.
- **Focus callbacks are not the answer.** `AudioManager` audio focus is about playback; it does not
  tell a recorder that its input was taken. Earlier versions of these notes suggested watching it —
  do not.
- **Free space.** `setMaxFileSize(usable − reserve)` is set at prepare as a backstop, and free space
  is also polled at most every two seconds while recording. Below the reserve the recording is
  finalized as `Interrupted(StorageLow)` while there is still room to close the file. The reserve is
  half of `minimumFreeSpaceBytes`, but at least 2 MiB (an MPEG-4 file's index grows with the
  recording and a very long one needs a few hundred kilobytes, so 2 MiB is a margin) and never more
  than `minimumFreeSpaceBytes` itself. `minimumFreeSpaceBytes = 0` switches the poll and the
  size limit off. **UNVERIFIED:** whether `MediaRecorder` leaves a finalized file after
  `MAX_FILESIZE_REACHED` and whether `stop()` throws afterwards is to be confirmed on devices. If
  `stop()` fails, the outcome is `Failed(RecordingLost(StorageLow, cause))`.
- **M4A vs AAC durability.** An MPEG-4 (M4A) file keeps its index at the end and is usually
  unplayable if it could not be finalized; an AAC (ADTS) file is a sequence of independent frames
  and usually plays up to the point it was cut. If crash-safety of long recordings matters more
  than the container, record `AudioFormat.AAC`.

### iOS

- **Sources.** The recorder's delegate (`audioRecorderEncodeErrorDidOccur`, and
  `audioRecorderDidFinishRecording(successfully: false)` that our own `stop()` did not cause →
  `EngineDied`); `AVAudioSessionInterruptionNotification` with type *began* →
  `AudioSessionInterrupted` (a call, Siri, an alarm, another app); an interruption that *ended* is
  ignored — nothing resumes by itself; `AVAudioSessionMediaServicesWereResetNotification` →
  `EngineDied`.
- **Suspension.** An app suspended while recording without the `audio` background mode is
  interrupted with reason *appWasSuspended*, but iOS delivers that notification **only when the
  app returns** to the foreground. A long recording in the background therefore needs the `audio`
  background mode, or must be stopped when the app goes to the background; otherwise the first the
  library hears of it is on return, as `Interrupted(AudioSessionInterrupted)`. The duration is
  measured up to the moment the library *observed* the interruption, on return — nothing in the
  module knows when the suspension began — so it can include the time the app was suspended (a
  stretch with no audio in the file), and whether the monotonic clock advances during suspension is
  unverified. A consumer that needs it exact should stop the recording when the app goes to the
  background.
- **UNVERIFIED — finalizing after an interruption.** `AVAudioRecorder.stop()` never throws, and
  whether it produces a valid, playable file after the system deactivated the session is **to be
  confirmed on a device**. The library calls it and reports `Interrupted` if it returns; if the
  file turns out to be unplayable the honest outcome is `RecordingLost`, and no API change is
  needed — only this note.
- **Free space** is polled from `NSFileSystemFreeSize` at most every two seconds and ends the
  recording as `Interrupted(StorageLow)` below the reserve, exactly as on Android; there is no
  platform size limit to set.
- **M4A vs AAC durability** is the same as on Android: prefer `AudioFormat.AAC` for long recordings
  when a file that survives being cut matters more than the container.

## Input level

`AudioRecorder.level` and `AudioRecorder.levelSamples` are built from one reading per platform,
converted to dBFS and mapped onto `0f..1f` by the same common code, so the two platforms draw the
same bar for the same voice. The mapping is `0f` at or below `levelFloorDbfs`, `1f` at 0 dBFS, and
linear in decibels between; `NaN` reads as silence.

- **Android.** The reading is `MediaRecorder.getMaxAmplitude()`: the peak 16-bit sample, `0..32767`,
  across all channels, **since the previous call**. The first call after recording starts only
  primes that window, which is why the recorder discards it and publishes the first value one
  interval later. The amplitude is converted with `20 * log10(amplitude / 32767)`, so `0` is digital
  silence (minus infinity) and `32767` is 0 dBFS. The call throws outside the recording state; the
  engine turns that into "no answer" rather than letting it escape, since it can land just after a
  pause, stop, or release.
- **iOS.** `AVAudioRecorder.meteringEnabled` is set to `true` in `prepare()` and left on. It is a
  flag on a pipeline that runs anyway and costs nothing measurable, and it avoids toggling it on a
  live recorder. Whether anything is read is decided by the recorder: `updateMeters()` and
  `peakPowerForChannel` are called only while `level` or `levelSamples` is collected. The value
  published is the maximum of `peakPowerForChannel` over the configured channel count, already in
  dBFS.
- **Peak, not average.** iOS also offers `averagePowerForChannel`. It is not used, because
  Android's reading is a peak, and the same voice would otherwise draw a visibly different bar on
  each platform.
- **Devices differ.** Automatic gain control and the microphone's own sensitivity are applied before
  either platform's meter, so the same sound can read a few decibels apart across devices. The
  default floor leaves room for that; tune `levelFloorDbfs` if your users' rooms are unusually loud
  or quiet.
- **No permission of its own.** The meter reads the recording that `RECORD_AUDIO` / microphone
  access already allows; there is nothing extra to declare.

## Behavior that is identical on both platforms

Everything else, because it lives in common code and is covered by one shared suite that runs on
both targets: the transition table, permission and storage pre-checks, path generation, elapsed-time
accounting across pause and resume, the dBFS-to-`0f..1f` mapping and subscription-driven metering of
`level` and `levelSamples`, deletion of abandoned files, retention of completed ones, cancellation
of an in-flight `prepare`, release idempotency, the fact that no public method throws, and
everything that follows an event once the engine has reported it: the debounce of a silenced input,
the free-space watchdog, which state each event produces, that a running `stop()`/`cancel()` wins,
and that a `release()` during finalization drives the platform recorder once. Only the mapping of
platform codes and the registration of the callbacks are platform code, with their own tests.
