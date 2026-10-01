# kmptoolkit-audio-recorder — Guide

Common scenarios, from a first recording to the edges that bite.

## The lifecycle

```
                prepare
   Idle ─────────────────────► Preparing ──► Ready ──start──► Recording ◄──resume── Paused
     ▲                             │           │                 │  │                  │
     │                             │ failure   │ cancel          │  └──────pause───────┘
     │                             ▼           │                 │ stop
     │                          Failed ────────┴──► Idle         ▼
     │                             │                          Completed
     └──────── cancel ─────────────┴──────── prepare ─────────────┘

   release (from anywhere) ──► Released   [terminal]
```

The authoritative version is the table in `AudioRecorder`'s KDoc, repeated in
[`04-api-reference.md`](04-api-reference.md#transition-table). Three rules are worth stating in
prose because they are the ones people guess wrong:

1. **`start` never resumes.** From `Paused` you call `resume`; `start` is refused. "Start" always
   means "start from zero", so a mis-wired button cannot silently append to an old recording.
2. **`cancel` is for abandoning, `release` is for disposing.** `cancel` deletes the partial file and
   returns to `Idle`, ready to record again. `release` frees the native handle forever and **keeps**
   the file — losing the screen is not a decision that the audio was unwanted. (Releasing from
   `Ready` does delete: that file holds nothing, and nothing could clean it up later.)
3. **An illegal call is inert.** It returns `RecorderError.IllegalState` and changes nothing: not
   the state, not the file, not the native recorder. You can ignore the result of a redundant
   `stop()` without risk.

## Handling errors

Every operation returns a `RecorderResult`. The interesting branch is `prepare`, which is where all
the pre-flight checks live:

```kotlin
when (val result = recorder.prepare()) {
    is RecorderResult.Success -> recorder.start()
    is RecorderResult.Failure -> when (val error = result.error) {
        RecorderError.PermissionDenied ->
            permissionRequester.request(Permission.RecordAudio)      // then prepare again

        is RecorderError.InsufficientStorage ->
            showMessage(freeUpSpace(needed = error.requiredBytes))

        is RecorderError.DirectoryNotWritable ->
            reportBug("cannot write to ${error.path}")

        is RecorderError.UnsupportedFormat ->
            fallBackTo(AudioFormat.M4A)

        is RecorderError.EngineFailure ->
            reportBug(error.cause)                                    // microphone busy, codec gone

        is RecorderError.IllegalState,
        is RecorderError.AlreadyReleased ->
            error("a bug in this screen's own wiring")                // never a user's fault
    }
}
```

`IllegalState` and `AlreadyReleased` are the two that mean *your* code is wrong rather than the
device being unhelpful. Treat them like a failed assertion, not like a condition to display.

Failures also land in `state` as `RecorderState.Failed(error)`, so a screen that renders from the
flow does not have to capture return values as well. Both paths report the same error object. When a
failure leaves a file behind — today only a failed `stop()` — `Failed.outputPath` carries it, since
`cancel()` is illegal from `Failed` and you would otherwise have no way to find it.

## Recording with pause

```kotlin
recorder.prepare()          // suspends — creates the directory, opens the file
recorder.start()            // does not — a flip of the native recorder's state
// … user taps pause
recorder.pause()            // elapsed freezes; the file stays open
// … user taps resume
recorder.resume()           // elapsed continues from where it stopped
val file = recorder.stop().getOrNull()   // suspends — finalizes the container
```

Paused time is not counted: pausing for a minute in the middle of a ten-second recording still
produces `duration == 10.seconds`. Pause is not supported by every Android output format — if the
platform refuses, `pause()` returns `RecorderError.EngineFailure` and **the recording keeps
running**, which is the honest outcome. Handle it by hiding the pause button rather than by
pretending it worked.

## Showing a live input level

`level` is a `StateFlow<Float>` between `0f` (quiet) and `1f` (full scale), published every 50 ms
while the recorder is `Recording` — the rate messenger waveforms draw at. Collect it wherever the
meter is drawn and turn each value into a bar:

```kotlin
class VoiceNoteViewModel(private val recorder: AudioRecorder) : ViewModel() {

    val bars: StateFlow<List<Float>> = recorder.level
        .scan(emptyList<Float>()) { history, level -> (history + level).takeLast(MAX_BARS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private companion object { const val MAX_BARS = 40 }
}
```

A few things about it are worth knowing before you build on it:

- **Nothing is measured until you collect.** A recorder whose `level` nobody collects never reads
  the microphone's meter, so a screen that has no waveform pays nothing for the feature. Reading
  `recorder.level.value` once does not count as collecting. When the last collector leaves, the
  value returns to `0f` and sampling stops; a new collector starts it again.
- **It is `0f` unless the recorder is `Recording`.** Pause, stop, cancel, and release all reset it at
  once, so a frozen bar is never left on screen. After `release()` it is `0f` for good.
- **A value is a peak, not an average.** It is the loudest sample since the previous value, so a
  short click between two updates still shows. It is also not smoothed: a bar that jumps between
  values looks nervous, and easing it is a UI decision, so it is left to you.
- **The scale is decibels, not amplitude.** `0f` is at or below `levelFloorDbfs` (`-50` by default),
  `1f` is full scale, and the range between is linear in dB. Normal speech then lands in the upper half
  of the range and room noise stays at the bottom. Raise the floor to ignore a noisy room; lower it to show
  whispers. See [`05-platform-notes.md`](05-platform-notes.md#input-level) for how each platform
  produces the number.
- **It belongs to one recorder instance.** If you create a recorder per segment, each one has its
  own `level` and starts at `0f`; a waveform that spans segments has to keep its own history, as
  the example above does.

```kotlin
val recorder = createAudioRecorder(
    context,
    AudioRecorderConfig(levelUpdateInterval = 33.milliseconds, levelFloorDbfs = -60f),
)
```

## Where recordings go

Nothing is hardcoded. `RecordingStorage` has three knobs and a default derived from your app:

| You set | Result |
|---|---|
| nothing | `<app-private files>/<your application id>/recording_<epochMillis>.m4a` |
| `directoryName = "voice-notes"` | `<app-private files>/voice-notes/recording_<epochMillis>.m4a` |
| `directoryPath = "/…/cache/notes"` | `/…/cache/notes/recording_<epochMillis>.m4a` |
| `fileNamePrefix = "note"` | `…/note_<epochMillis>.m4a` |
| `prepare(outputPath = "…")` | exactly that path, config ignored. Must be absolute |

```kotlin
val recorder = createAudioRecorder(
    context,
    AudioRecorderConfig(
        storage = RecordingStorage(directoryName = "voice-notes", fileNamePrefix = "note"),
        format = AudioFormat.M4A,
    ),
)
```

The app-private base is `Context.getFilesDir()` on Android and the app's `Documents` directory on
iOS. The default subdirectory is your own application id / bundle identifier — the library never
invents a namespace of its own, so two apps sharing a directory (an app group, external storage)
cannot collide. Missing directories are created by `prepare`.

Finished files are yours: nothing here deletes, rotates, or expires a `Completed` recording.

## Choosing a format

| Format | Android | iOS | Notes |
|---|---|---|---|
| `M4A` | yes | yes | AAC in MPEG-4. The default; use it unless you have a reason not to |
| `AAC` | yes | yes | Raw AAC (ADTS on Android) |
| `WAV` | **no** | yes | Uncompressed PCM. `prepare` returns `UnsupportedFormat` on Android |

There is no MP3: neither platform ships an MP3 encoder, and writing AAC into a `.mp3` file would be
a lie. Encode to MP3 on a server if you need it.

`AudioRecorderConfig.HIGH_QUALITY` bumps the encoder to stereo 48 kHz at 256 kbit/s — roughly four
times the bytes per second of the default. For speech, the default is already transparent.

## Ownership and release

The recorder holds a native handle, the microphone, and the coroutines that publish `elapsed` and
`level`. Whoever creates it must release it exactly once.

```kotlin
// Decompose — note that release() is callable here precisely because it does not suspend
class RecordComponent(componentContext: ComponentContext, private val recorder: AudioRecorder) :
    ComponentContext by componentContext {
    init { lifecycle.doOnDestroy { recorder.release() } }
}

// Android ViewModel
override fun onCleared() { recorder.release() }
```

```swift
// Swift
deinit { recorder.release() }
```

Two mistakes to avoid:

- **Releasing a shared recorder.** After `release()` the instance is dead: every call returns
  `AlreadyReleased`. If two screens share one recorder, one screen closing must not release it —
  give it an owner that outlives both, or give each screen its own.
- **Never releasing.** A recorder that is never released keeps the microphone. On Android that
  blocks every other app's recording until the process dies; on iOS the `AVAudioSession` stays
  active and other audio stays ducked.

Reuse is fine and expected: after `stop()` or `cancel()`, call `prepare()` again for the next
recording. `release()` is for disposal, not for finishing a take.

## Cancellation

`prepare` is the only suspending call, and it is cancellable. If the coroutine calling it is
cancelled — the screen closed mid-preparation — the recorder frees the half-open native recorder,
deletes the zero-byte file, and returns to `Idle` before `CancellationException` propagates. You do
not need to clean up after it, and the recorder is usable again afterwards.

Cancellation is *not* how you abandon a recording that already started: `start` is not suspending,
so there is nothing to cancel. Call `cancel()`.

`release()` during an in-flight `prepare()` is also safe: the preparation tears itself down when it
completes rather than moving a released recorder to `Ready`.

## Threading

Drive one recorder from one thread — the main thread is the usual choice. Note that `start()` and
`stop()` are not suspending but are not instant either: `stop()` in particular finalizes the
container (on Android, writing the MPEG-4 `moov` atom) and can block for a noticeable fraction of a
second on a long recording. If that matters for your frame budget, call them from your own
background dispatcher — the same one every time. `state`, `elapsed`, and `level` are
`StateFlow`s and can be collected from anywhere.

The recorder runs its `elapsed` ticker and `level` meter on `Dispatchers.Default` and needs no main
dispatcher, so it works in a plain JVM unit test without a main-dispatcher rule.

## Common mistakes

- **Reaching for `runBlocking` to call `stop()` from a click handler.** Use the scope you already
  have — `rememberCoroutineScope()`, `viewModelScope`, the component's `coroutineScope()`. Blocking
  a UI thread to wait for a file to finalize is the exact thing the suspend rule exists to prevent.
- **Calling `start()` right after `prepare()` without checking the result.** If `prepare` failed,
  `start` returns `IllegalState` and nothing records. Check, or observe `state`.
- **Expecting `stop()` to be callable twice.** The second returns `IllegalState`; the first already
  produced the file.
- **Reading `elapsed` after `stop()` and expecting zero.** It holds the final duration until the
  next `prepare`. That is deliberate — it is what the "recorded 0:12" label reads.
- **Assuming `duration` is exact.** It is wall-clock time between `start` and `stop`, accurate to
  about one tick. If you need the encoded file's exact duration, read it from the file.
- **Treating `RecorderError.EngineFailure` as fatal.** A microphone busied by a phone call recovers;
  `prepare` again once it is free.
