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

   the system ends a recording (Recording / Paused) ──► Interrupted   [file finalized]
                                                   └──► Failed(RecordingLost)  [file not finalized]
   the system ends a prepared recording (Ready) ──► Failed(RecordingLost)  [no file: it was empty]
   Interrupted ── prepare ──► Ready      Interrupted ── stop ──► (same file, no change)
   Interrupted ── cancel ──► Idle        Failed with a file ── cancel ──► Idle

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
   `pause()` or `start()` without risk. (A `stop()` from `Interrupted` is not illegal: it returns
   the file again.)

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

        is RecorderError.RecordingLost ->
            Unit                                                      // only ever carried by state, see below

        is RecorderError.IllegalState ->
            Unit                                                      // look at error.state, see below

        is RecorderError.AlreadyReleased ->
            error("a bug in this screen's own wiring")                // never a user's fault
    }
}
```

`AlreadyReleased` means *your* code is wrong: you used a recorder you had disposed of. Treat it like
a failed assertion, not like a condition to display. `IllegalState` usually means the same — a
button wired to the wrong state — but **not always**: the system can end a recording at any moment,
and a call that races with it is refused too. `start()`, `pause()` and `resume()` return
`IllegalState` when a system event is being finalized, with the state that is still published
(`Ready`, `Recording` or `Paused`) in `error.state`; a moment later `state` moves to `Interrupted`
or `Failed`. So do not `error(...)` on `IllegalState` from a user's tap: read `state` (or just let
the next emission render), because the `Interrupted` that is about to arrive is the real answer.
`prepare()`, `stop()` and `cancel()` do not fail that way: they suspend until the finalization
lands and then act on the state it produced.

Failures also land in `state` as `RecorderState.Failed(error)`, so a screen that renders from the
flow does not have to capture return values as well. Both paths report the same error object. When a
failure leaves a file behind — a failed `stop()`, or a recording the system ended and the recorder
could not finalize — `Failed.outputPath` carries it. `cancel()` is legal from exactly those
`Failed` states and deletes the file; from a `Failed` with no path there is nothing to delete and it
stays illegal.

`RecorderError.EngineFailure` is always the answer to a call you made. A failure that happens on
its own is not reported through it: see the next section. (`RecordingLost` appears in the sample
above only because a `when` over `RecorderError` has to be exhaustive; no call returns it.)

## When the system ends a recording

A long recording can be ended by the system while your app is not calling anything: an incoming
call or Siri takes the audio session (iOS), the OS silences the microphone for a call or because
the app captures from the background (Android 10+), the volume runs out of room, or the media
service dies. The recorder notices, tries to finalize the file, and moves `state` on its own:

| `state` | The file | Meaning |
|---|---|---|
| `Interrupted(recording, reason)` | finalized and playable, holds everything up to the moment it ended (see the iOS suspension caveat below) | `recording.duration` is `elapsed` as it was frozen |
| `Failed(RecordingLost(reason, cause), outputPath)` | kept, may be unplayable | the file could not be finalized — an unfinalized M4A usually does not play, an AAC (ADTS) file usually does |
| `Failed(RecordingLost(reason), outputPath = null)` | deleted — it was empty | the recorder was prepared but never started |

`reason` is an `InterruptionReason`: `AudioSessionInterrupted` (iOS: a call, Siri, an alarm,
another app, or the app having been suspended — reported only on return, with a duration that can
include the suspended time), `MicrophoneSilenced` (Android 10+: the OS silenced
the input and it stayed silenced for a moment), `StorageLow` (free space fell below the reserve the
library keeps for finalizing), and `EngineDied(platformCode)` (the media service died or reported
an error).

```kotlin
recorder.state.collect { state ->
    when (state) {
        is RecorderState.Interrupted -> {
            segments += state.recording                 // already finalized: keep it, play it, upload it
            when (state.reason) {
                InterruptionReason.AudioSessionInterrupted -> showBanner(RecordingStopped.Call)
                InterruptionReason.MicrophoneSilenced -> showBanner(RecordingStopped.MicrophoneBusy)
                InterruptionReason.StorageLow -> showBanner(RecordingStopped.StorageLow)
                is InterruptionReason.EngineDied -> showBanner(RecordingStopped.Failure)
            }
        }
        is RecorderState.Failed -> when (val error = state.error) {
            is RecorderError.RecordingLost -> {
                // The system ended it and the file could not be closed properly.
                state.outputPath?.let { path -> offerSalvage(path) }   // may not play
                showBanner(RecordingStopped.Failure)
            }
            else -> showError(error)
        }
        else -> Unit
    }
}
```

What to do with it:

- **Save it once.** `Interrupted.recording` is the file your collector just saw, and `stop()` from
  `Interrupted` returns **the same file** — so a screen that adds `state.recording` to its list on
  `Interrupted` and again from the result of a stop button must dedupe by `recording.path`.
- **Do not rely on seeing every `Interrupted`.** `state` is a `StateFlow`, which conflates: a
  collector that is slow (or not running, as in a backgrounded screen) can miss an `Interrupted`
  that a quick `prepare()` replaced with `Preparing`/`Ready`. If you start the next segment
  right away, take the file from `state` in the same collector that observes it, or read
  `state.value` before calling `prepare()`.
- **Treat it as a finished segment.** A long recording is a series of segments: keep the file in
  `Interrupted.recording`, and call `prepare()` again for the next one when the user is ready. The
  recorder never resumes by itself when the call ends, and `resume()` is refused from
  `Interrupted`.
- **`stop()` is still safe to call.** From `Interrupted` it returns the same file as a success and
  changes nothing, so a stop button that was tapped a moment after the system ended the recording
  gets the file rather than an error.
- **To discard, call `cancel()`.** From `Interrupted` and from a `Failed` that carries a path it
  deletes the file and returns to `Idle`. `prepare()` never deletes an interrupted or lost file —
  it is yours.
- **A stop or cancel that is already running wins.** If your `stop()` or `cancel()` started before
  the system's event was handled, the event is ignored: a discard is never reported as a saved
  recording, and a normal stop is never reported as a failure. Only the first event of a recording
  counts.
- **Nothing is reported while it is not your recording.** `elapsed` freezes the moment the event
  was observed, `level` drops to `0f` and `levelSamples` stops. For `MicrophoneSilenced` the moment
  is when the silencing began, not when it was reported, so `elapsed` **steps back** by up to the
  debounce (about 400 ms) when `Interrupted` arrives: the ticker keeps publishing during the
  debounce. A timer that must never run backwards should clamp.

`Interrupted` and `RecordingLost` are new cases of two sealed types, so a `when` over `RecorderState`
or `RecorderError` without an `else` needs a branch for each. Platform requirements that keep a
recording alive (a microphone foreground service on Android, the `audio` background mode on iOS)
are still yours; this is how you learn when the platform ended one anyway — see
[`05-platform-notes.md`](05-platform-notes.md#when-the-system-ends-a-recording).

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

Two flows carry the input loudness, both between `0f` (quiet) and `1f` (full scale) and both
published every 50 ms while the recorder is `Recording` — the rate messenger waveforms draw at.
Pick by what you draw:

| You draw | Collect | Why |
|---|---|---|
| a pulsing indicator or a single bar showing the current loudness | `level` | a `StateFlow`: always has a current value and a collector sees the latest one |
| a waveform that grows as the user speaks | `levelSamples` | a plain `Flow` with every sample, repeats included |

The reason there are two is that `level` is a `StateFlow`, and a `StateFlow` drops a value equal to
its predecessor. In silence `level` sits at `0f` and emits nothing, so a waveform built from it
stops growing exactly when the user goes quiet; `levelSamples` keeps delivering the `0f`.

```kotlin
class VoiceNoteViewModel(private val recorder: AudioRecorder) : ViewModel() {

    val bars: StateFlow<List<Float>> = recorder.levelSamples
        .scan(emptyList<Float>()) { history, sample -> (history + sample).takeLast(MAX_BARS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private companion object { const val MAX_BARS = 40 }
}
```

A few things about them are worth knowing before you build on them:

- **Nothing is measured until you collect.** A recorder whose level nobody collects never reads the
  microphone's meter, so a screen that has no waveform pays nothing for the feature. Reading
  `recorder.level.value` once does not count as collecting. Collecting `level`, `levelSamples`, or
  both starts the same single meter; when the last collector of either leaves, `level` returns to
  `0f` and sampling stops, and a new collector starts it again.
- **They are `0f` or silent unless the recorder is `Recording`.** Pause, stop, cancel, and release
  reset `level` at once, so a frozen bar is never left on screen, and `levelSamples` emits nothing
  while paused, so a waveform pauses with the recording. After `release()` neither moves again.
- **`levelSamples` is hot.** A collector receives the samples taken after it subscribed; there is no
  replay, so collect it before `start()` if the first moments matter. A collector that falls more
  than 64 samples (about three seconds) behind loses the oldest ones rather than slowing the meter.
- **A value is a peak, not an average.** It is the loudest sample since the previous value, so a
  short click between two updates still shows. It is also not smoothed: a bar that jumps between
  values looks nervous, and easing it is a UI decision, so it is left to you.
- **The scale is decibels, not amplitude.** `0f` is at or below `levelFloorDbfs` (`-50` by default),
  `1f` is full scale, and the range between is linear in dB. Normal speech then lands in the upper
  half of the range and room noise stays at the bottom. Raise the floor to ignore a noisy room;
  lower it to show whispers. See [`05-platform-notes.md`](05-platform-notes.md#input-level) for how
  each platform produces the number.
- **They belong to one recorder instance.** If you create a recorder per segment, each one has its
  own `level` and `levelSamples` and starts at `0f`; a waveform that spans segments has to keep its
  own history, as the example above does.

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

`prepare`, `stop` and `cancel` suspend. `prepare` is cancellable: if the coroutine calling it is
cancelled — the screen closed mid-preparation — the recorder frees the half-open native recorder,
deletes the zero-byte file, and returns to `Idle` before `CancellationException` propagates. You do
not need to clean up after it, and the recorder is usable again afterwards.

`stop` and `cancel` are deliberately *not* abandoned by cancellation: they run to the end (a
half-finished stop would leave `state` saying `Recording` over an engine that had stopped), and the
caller sees its cancellation at its next suspension point.

Cancellation is *not* how you abandon a recording that already started: `start` is not suspending,
so there is nothing to cancel. Call `cancel()`.

`release()` during an in-flight `prepare()` is also safe: the preparation tears itself down when it
completes rather than moving a released recorder to `Ready`.

## Threading

Drive one recorder from one thread — the main thread is the usual choice. Your calls are not
synchronized against each other, so two threads calling the operations at once is a bug. What the
system raises on its own — an interruption, a dying media service — arrives on platform threads;
the recorder serializes those with your calls internally, and the slow part of one (finalizing a long
file) runs off the lock, on the factory's `coroutineContext`. A call of yours that arrives meanwhile
never blocks a thread: `prepare`, `stop` and `cancel` suspend until the finalization lands, and
`start`, `pause` and `resume` return `IllegalState` at once (see
[Handling errors](#handling-errors)).

`start()`, `pause()` and `resume()` are not suspending and are quick; `stop()` and `cancel()`
suspend and run the container's finalization (on Android, writing the MPEG-4 `moov` atom, which can
take a noticeable fraction of a second on a long recording) on that context, not on your thread, so
there is no reason to call them from a dispatcher of your own. Only `release()` works inline on the
calling thread. `state`, `elapsed`, `level`, and `levelSamples` can be collected from anywhere.

The recorder runs its `elapsed` ticker and `level` meter on `Dispatchers.Default` and needs no main
dispatcher, so it works in a plain JVM unit test without a main-dispatcher rule.

## Common mistakes

- **Reaching for `runBlocking` to call `stop()` from a click handler.** Use the scope you already
  have — `rememberCoroutineScope()`, `viewModelScope`, the component's `coroutineScope()`. Blocking
  a UI thread to wait for a file to finalize is the exact thing the suspend rule exists to prevent.
- **Calling `start()` right after `prepare()` without checking the result.** If `prepare` failed,
  `start` returns `IllegalState` and nothing records. Check, or observe `state`.
- **Expecting `stop()` to be callable twice.** After a normal stop the second returns
  `IllegalState` (the state is `Completed`); the first already produced the file. From `Interrupted`
  it is the opposite: any number of `stop()` calls return the same file.
- **Reading `elapsed` after `stop()` and expecting zero.** It holds the final duration until the
  next `prepare`. That is deliberate — it is what the "recorded 0:12" label reads.
- **Assuming `duration` is exact.** It is wall-clock time between `start` and `stop`, accurate to
  about one tick. If you need the encoded file's exact duration, read it from the file.
- **Treating `Interrupted` as a pause.** It is over: the file is closed. `resume()` is refused, and
  the next recording needs `prepare()`.
- **Deleting an interrupted file you were handed.** `Interrupted.recording` is a finished, playable
  file and it is yours; the recorder does not delete it, so keep it or call `cancel()`.
- **Treating `RecorderError.EngineFailure` as fatal.** A microphone busied by a phone call recovers;
  `prepare` again once it is free.
