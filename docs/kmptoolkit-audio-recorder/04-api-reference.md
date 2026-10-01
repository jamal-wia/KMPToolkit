# kmptoolkit-audio-recorder — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.audio.recorder`, its contract, and its
thread-safety.

## Factories

```kotlin
// androidMain
public fun createAudioRecorder(
    context: Context,
    config: AudioRecorderConfig = AudioRecorderConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): AudioRecorder

// iosMain
public fun createAudioRecorder(
    config: AudioRecorderConfig = AudioRecorderConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): AudioRecorder
```

Returns a recorder backed by `MediaRecorder` / `AVAudioRecorder`. The factory is per-platform rather
than `expect`/`actual` because only Android needs a `Context`; construct it in your platform layer
and pass the common `AudioRecorder` upwards.

Only `context.applicationContext` is retained, so passing an Activity cannot leak it. `config` is
fixed for the recorder's lifetime — the platform applies encoder settings at prepare time, so
changing them means a new recorder.

`coroutineContext` is the single place the module decides what runs where: the `elapsed` ticker's
and `level` meter's scope, and the `withContext` behind `prepare`/`stop`/`cancel` that keeps their
filesystem and encoder work off the caller's thread. The platform engines deliberately choose no
dispatcher of their own, so this parameter really does control all of it. It mirrors
`kmptoolkit-audio-player`'s factories, so a consumer that pins one module's background work pins the
other identically.

The returned instance holds no native resource until the first successful `prepare()`, and holds
one until `release()`.

## `AudioRecorder`

```kotlin
public interface AudioRecorder {
    public val state: StateFlow<RecorderState>
    public val elapsed: StateFlow<Duration>
    public val level: StateFlow<Float> // since 1.9.0
    public val levelSamples: Flow<Float> // since 1.9.0

    public suspend fun prepare(outputPath: String? = null): RecorderResult<String>
    public fun start(): RecorderResult<Unit>
    public fun pause(): RecorderResult<Unit>
    public fun resume(): RecorderResult<Unit>
    public suspend fun stop(): RecorderResult<RecordedFile>
    public suspend fun cancel(): RecorderResult<Unit>
    public fun release()
}
```

**Which operations suspend:** *an operation that can touch the filesystem suspends; an operation
that only moves recorder state does not.* `prepare` creates the directory and opens the file, `stop`
finalizes the container, `cancel` deletes the partial file — those three suspend, and run their I/O
on the factory's `coroutineContext`. `start`, `pause`, and `resume` are flips of the native
recorder's own state and do not. `release` is the documented exception; see below.

**Thread-safety:** the six operations plus `release` are **not** thread-safe and must be called from
one thread. `state`, `elapsed`, and `level` are `StateFlow`s and `levelSamples` is a `Flow`; all of
them are safe to collect from any thread.

### Transition table

| From \ Operation | `prepare` | `start` | `pause` | `resume` | `stop` | `cancel` |
|---|---|---|---|---|---|---|
| `Idle` | yes | no | no | no | no | no |
| `Preparing` | no | no | no | no | no | no |
| `Ready` | yes | yes | no | no | no | yes |
| `Recording` | no | no | yes | no | yes | yes |
| `Paused` | no | no | no | yes | yes | yes |
| `Completed` | yes | no | no | no | no | no |
| `Failed` | yes | no | no | no | no | no |
| `Released` | no | no | no | no | no | no |

A "no" returns `RecorderResult.Failure(RecorderError.IllegalState(state, operation))` — or
`AlreadyReleased` from `Released` — and changes nothing. `release()` is legal everywhere and never
fails.

### `state: StateFlow<RecorderState>`

The lifecycle position. Starts at `Idle`. Emits only on transitions — never on a duration tick.

### `elapsed: StateFlow<Duration>`

Time recorded into the current file, for a timer. Advances only in `Recording`, republished every
`config.durationUpdateInterval`. Freezes on `pause`, continues on `resume`, holds the final duration
after `stop`, resets to `ZERO` on `prepare`, `cancel`, and `release`.

Wall-clock time between `start` and `stop`, not a measurement of the encoded file.

### `level: StateFlow<Float>`

*Since 1.9.0.* Peak loudness of the microphone input, normalised to `0f..1f`: `0f` at or below
`config.levelFloorDbfs`, `1f` at full scale (0 dBFS), linear in decibels between. A level meter, not
an amplitude, so quiet speech is visible.

Each value is the loudest sample since the previous one, published every
`config.levelUpdateInterval`. It is not smoothed.

Measured only while `state` is `Recording` **and** at least one collector of `level` or
`levelSamples` is subscribed; a recorder nobody meters does no metering work. `0f` in every other
state, and as soon as the last collector leaves. Reading `level.value` without collecting does not
start metering. Pause, stop, cancel, and release set it to `0f` immediately, and nothing is
published after `release()`. A `pause()` the platform refuses leaves the recorder `Recording`, so
metering continues.

Being a `StateFlow`, it conflates equal consecutive values: in silence it stays at `0f` and emits
nothing, and a clamped `1f` repeats the same way. That suits a pulsing indicator; for a waveform use
`levelSamples`.

The first value after metering starts (on `start`, `resume`, or the first collector arriving) comes
one interval later, not at once. How each platform produces the number is in
[`05-platform-notes.md`](05-platform-notes.md#input-level); a scripted stand-in for tests is
`FakeAudioRecorder.emitLevel`, in [`06-testing.md`](06-testing.md).

### `levelSamples: Flow<Float>`

*Since 1.9.0.* Every sample the level meter takes, on the same `0f..1f` scale as `level`, one per
`config.levelUpdateInterval` — repeats included, so silence keeps arriving as `0f` and a waveform
keeps moving while the user is quiet. Collect this to draw a waveform and `level` to draw a meter.

Hot and not replayed: a collector receives the samples taken after it subscribed. It emits only
while `state` is `Recording` (nothing while paused, so a waveform pauses with the recording), never
after `release()`, and it does not complete. A reading the platform could not provide produces no
sample. Collecting it starts metering exactly as collecting `level` does, and the two share one
meter: collecting both does not sample the microphone twice. A collector that falls more than 64
samples behind loses the oldest. The public type is `Flow<Float>`, not `SharedFlow`, so no replay
cache is part of the contract.

A `pause()` or `stop()` that lands while a sample is being taken can leave at most one extra sample
in the stream, a real reading taken a moment before the transition, since an emitted sample cannot
be taken back. `level` is repaired to `0f` in that window; the stream is not.

### `suspend fun prepare(outputPath: String? = null): RecorderResult<String>`

Opens the microphone and the output file. Returns the resolved absolute path.

Checks, in order — first failure ends the call and moves `state` to `Failed`:

| Check | Error on failure |
|---|---|
| not released | `AlreadyReleased(PREPARE)` |
| legal in current state | `IllegalState(state, PREPARE)` |
| `RECORD_AUDIO` granted | `PermissionDenied` |
| format encodable here | `UnsupportedFormat(format)` |
| an explicit `outputPath` is absolute and non-blank | `DirectoryNotWritable(path)` |
| output has a parent directory, creatable and writable | `DirectoryNotWritable(path)` |
| free space ≥ `config.minimumFreeSpaceBytes` | `InsufficientStorage(path, required, available)` |
| platform recorder prepares | `EngineFailure(PREPARE, cause)` |

`outputPath = null` generates `<directory>/<prefix>_<epochMillis>.<extension>` from
`config.storage`. Parent directories are created. An explicit `outputPath` must be absolute — a
blank or relative one is rejected before the filesystem is touched, because on iOS
`NSURL.fileURLWithPath("")` raises an Objective-C exception that Kotlin/Native cannot catch.

Cancellable at every point, including while the directory and free-space checks are still pending:
on cancellation the native recorder is released, the file is deleted, `state` returns to `Idle`, and
`CancellationException` propagates.

Preparing from `Ready` deletes the previously prepared file that was never recorded into. Preparing
from `Completed` leaves the finished file alone.

### `fun start(): RecorderResult<Unit>`

`Ready` → `Recording`. Resets `elapsed` to zero and starts the ticker. Not legal from `Paused` —
use `resume`. On engine failure the empty file is deleted and `state` becomes `Failed`.

### `fun pause(): RecorderResult<Unit>`

`Recording` → `Paused`. Freezes `elapsed`; the file stays open. If the platform refuses,
returns `EngineFailure(PAUSE, cause)` and **the recorder stays in `Recording`** — an engine that
would not pause is still capturing.

### `fun resume(): RecorderResult<Unit>`

`Paused` → `Recording`, continuing `elapsed` from where it froze. On engine failure the recorder
stays `Paused`.

### `suspend fun stop(): RecorderResult<RecordedFile>`

`Recording` / `Paused` → `Completed`. Finalizes the file, releases the native handle and the
microphone, and returns the file with its duration. The recorder can be re-prepared without being
released.

Suspending: finalizing the container is the slowest call in the module, and it runs on the
factory's `coroutineContext`.

Not abandoned by cancellation: if the calling coroutine is cancelled, the stop still runs to the end
and `state` becomes `Completed` (or `Failed`); the caller observes its cancellation no later than its
next suspension point. A
half-finished stop would leave `state` saying `Recording` over an engine that had already stopped.

On engine failure `state` becomes `Failed(error, outputPath)` and **the partial file is kept** — a
library does not delete a user's audio because the encoder complained on close. The path is carried
on the state so you can still find the file; `cancel()` is illegal from `Failed`, so deleting it is
your call to make with your own filesystem API.

### `suspend fun cancel(): RecorderResult<Unit>`

`Ready` / `Recording` / `Paused` → `Idle`. Releases the native handle, deletes the partial file,
resets `elapsed`. Illegal from `Completed` on purpose: a finished recording is yours to keep or
delete.

Suspending: it deletes a file, and the deletion runs on the factory's `coroutineContext`. Like
`stop`, it is not abandoned by cancellation — it finishes and reaches `Idle`; the caller observes its
cancellation no later than its next suspension point.

Best-effort by design — an engine that throws while being stopped does not prevent the deletion or
the return to `Idle`, and this still returns `Success`.

### `fun release()`

Terminal disposal: releases the native handle and microphone, cancels the ticker, sets `state` to
`Released`. An in-progress recording is stopped first and **its file is kept**; call `cancel()`
first if you want it deleted. Releasing from `Ready` is the one exception: that file was never
recorded into and nothing could ever clean it up afterwards, so it is deleted.

Safe to call while a `prepare()` is still in flight — the preparation undoes itself when it
finishes, rather than resurrecting a released recorder.

**Not suspending, unlike `stop` and `cancel`, and deliberately so.** Release belongs on a teardown
path — `onCleared`, `doOnDestroy`, `deinit` — and those are exactly the places where the matching
coroutine scope has already been cancelled, so a suspending `release` would be uncallable where it
is most needed. It does its work inline on the calling thread instead; releasing while a long
recording is open is therefore the one place this library can block you.

Idempotent, never throws, never fails. After it, every operation returns `AlreadyReleased`.

## `RecorderState`

```kotlin
public sealed interface RecorderState {
    public data object Idle : RecorderState
    public data object Preparing : RecorderState
    public data class Ready(public val outputPath: String) : RecorderState
    public data class Recording(public val outputPath: String) : RecorderState
    public data class Paused(public val outputPath: String, public val elapsed: Duration) : RecorderState
    public data class Completed(public val recording: RecordedFile) : RecorderState
    public data class Failed(
        public val error: RecorderError,
        public val outputPath: String? = null,
    ) : RecorderState
    public data object Released : RecorderState
}
```

`Failed` is kept rather than reset to `Idle` so a screen rendering from `state` can show the failure
without also inspecting return values. Preparing again clears it. `Failed.outputPath` is the file the
failure left behind — currently only a failed `stop()`; `null` when the failure cleaned up after
itself.

### Extensions

```kotlin
public val RecorderState.isRecording: Boolean   // true only in Recording
public val RecorderState.isActive: Boolean      // Recording or Paused — an output file is open
public val RecorderState.outputPath: String?    // Ready/Recording/Paused/Completed/Failed, else null
```

## `RecordedFile`

```kotlin
public data class RecordedFile(
    public val path: String,
    public val duration: Duration,
)
```

An existing, closed audio file. `duration` is measured by the recorder (wall clock between `start`
and `stop`, excluding paused time), accurate to roughly one tick — not read back from the encoded
file.

## `RecorderResult`

```kotlin
public sealed interface RecorderResult<out T> {
    public data class Success<out T>(public val value: T) : RecorderResult<T>
    public data class Failure(public val error: RecorderError) : RecorderResult<Nothing>
}

public fun <T> RecorderResult<T>.getOrNull(): T?
public fun <T> RecorderResult<T>.errorOrNull(): RecorderError?
public val RecorderResult<*>.isSuccess: Boolean
```

Not `kotlin.Result`: `Result` can only carry a `Throwable`, and none of these outcomes is
exceptional. Wrapping them in synthetic exceptions would invite `getOrThrow()` and put the crash
back.

## `RecorderError`

```kotlin
public sealed interface RecorderError {
    public data object PermissionDenied : RecorderError
    public data class IllegalState(val state: RecorderState, val operation: RecorderOperation) : RecorderError
    public data class AlreadyReleased(val operation: RecorderOperation) : RecorderError
    public data class DirectoryNotWritable(val path: String, val cause: Throwable? = null) : RecorderError
    public data class InsufficientStorage(val path: String, val requiredBytes: Long, val availableBytes: Long) : RecorderError
    public data class UnsupportedFormat(val format: AudioFormat) : RecorderError
    public data class EngineFailure(val operation: RecorderOperation, val cause: Throwable? = null) : RecorderError
}

public enum class RecorderOperation { PREPARE, START, PAUSE, RESUME, STOP, CANCEL }
```

Typed causes, never display strings — mapping one onto copy in the right language is the app's job.

`EngineFailure` is always the result of a call you made — a failure that happens on its own
mid-recording is not pushed here, see [`05-platform-notes.md`](05-platform-notes.md). Its `cause` is
`null` when the platform reported failure by returning `false` rather than throwing, which
`AVAudioRecorder` mostly does; `availableBytes` is `-1` when the platform could not report
free space (in which case the check passes rather than refusing on an unknown).

## `AudioRecorderConfig`

```kotlin
public data class AudioRecorderConfig(
    public val storage: RecordingStorage = RecordingStorage(),
    public val format: AudioFormat = AudioFormat.M4A,
    public val sampleRate: Int = 44_100,
    public val channelCount: Int = 1,
    public val bitRate: Int = 128_000,
    public val durationUpdateInterval: Duration = 100.milliseconds,
    public val minimumFreeSpaceBytes: Long = 8L * 1024 * 1024,
    public val levelUpdateInterval: Duration = 50.milliseconds, // since 1.9.0
    public val levelFloorDbfs: Float = -50f,                    // since 1.9.0
)
```

Companion: `DEFAULT_SAMPLE_RATE`, `DEFAULT_CHANNEL_COUNT`, `DEFAULT_BIT_RATE`,
`DEFAULT_DURATION_UPDATE_INTERVAL`, `DEFAULT_MINIMUM_FREE_SPACE_BYTES`,
`DEFAULT_LEVEL_UPDATE_INTERVAL`, `DEFAULT_LEVEL_FLOOR_DBFS`, and `HIGH_QUALITY` (stereo 48 kHz at
256 kbit/s; the level settings stay at their defaults).

`levelUpdateInterval` is how often `level` publishes while it is being measured. `levelFloorDbfs` is
the input loudness that reads as `0f`. The `-50` default sits above typical room noise on a phone
microphone (around -55 to -45 dBFS) and below normal speech (around -30 to -10). There is no on/off
flag for metering: its cost is controlled by whether `level` is collected.

`sampleRate`, `channelCount`, `bitRate`, `durationUpdateInterval`, and `levelUpdateInterval` must be
positive; `levelFloorDbfs` must be finite and negative (so `NaN` and the infinities are rejected);
`minimumFreeSpaceBytes` must not be negative, and `0` disables the free-space check. Violations
throw `IllegalArgumentException` at construction — these are literals a developer writes, so a wrong
one is a bug to fix at the call site, not a runtime condition an app recovers from.

## `RecordingStorage`

```kotlin
public data class RecordingStorage(
    public val directoryPath: String? = null,
    public val directoryName: String? = null,
    public val fileNamePrefix: String = "recording",
)
```

- `directoryPath` — absolute directory. `null` means `<app-private files>/<directoryName>`.
- `directoryName` — subdirectory under the app-private base. `null` means the consumer's own
  application id / bundle identifier. Ignored when `directoryPath` is set.
- `fileNamePrefix` — leading part of a generated name (`<prefix>_<epochMillis>.<ext>`). Must not be
  blank and must not contain `/` or `\`.

Blank strings throw `IllegalArgumentException`; `null` selects the default.

## `AudioFormat`

```kotlin
public enum class AudioFormat(public val extension: String) { M4A("m4a"), AAC("aac"), WAV("wav") }
```

`WAV` is iOS-only; requesting it on Android fails `prepare` with `UnsupportedFormat`. There is no
MP3 — see [`03-guide.md`](03-guide.md#choosing-a-format).
