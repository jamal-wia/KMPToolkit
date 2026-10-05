# kmptoolkit-audio-recorder — Testing

Testing the code *around* a recorder: a view model, a presenter, a Decompose component.

## The fixture module

`FakeAudioRecorder` ships in a separate artifact, consumed under `testImplementation`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-audio-recorder:<version>")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-audio-recorder-testing:<version>")
        }
    }
}
```

It is a separate artifact for the reason given in
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts):
a fixture inside the production module would ship to every consumer's runtime classpath. It depends
on `kmptoolkit-audio-recorder` with `api`, so you get both types from one line.

## Why you need a double at all

`AudioRecorder`'s real implementations talk to `MediaRecorder` and `AVAudioRecorder`. Neither works
in a JVM unit test, neither can be made to fail on demand, and both need a microphone that CI does
not have. So the code you actually want to test — "does the stop button produce a saved note?",
"what does the screen show when permission is refused?" — has no way to run without a stand-in.

## `FakeAudioRecorder`

```kotlin
@Test
fun `stopping saves the note`() = runTest {
    val recorder = FakeAudioRecorder()
    val viewModel = VoiceNoteViewModel(recorder)

    viewModel.onRecordClicked()
    recorder.advanceElapsed(3.seconds)
    viewModel.onStopClicked()

    assertEquals(3.seconds, recorder.completedRecordings.single().duration)
}
```

`prepare`, `stop`, and `cancel` are `suspend` on the fake too, so a test that drives it directly
needs `runTest` — but they resolve immediately, with no dispatcher and no I/O to wait on.

It enforces the same transition table as the real recorder, so a test that passes against it is
testing behavior the real recorder also has. What it adds is control:

| Knob | Effect |
|---|---|
| `advanceElapsed(duration)` | moves `elapsed` forward. Time never passes on its own — no scheduler, no virtual clock, exact assertions |
| `emitLevel(value)` | sets `level` **and** emits `value` on `levelSamples`, repeats included, as if the microphone had just peaked there. `value` must be within `0f..1f`; ignored unless the fake is `Recording` |
| `permissionGranted = false` | `prepare()` fails with `PermissionDenied` |
| `failNextOperationWith = error` | the next otherwise-legal operation fails with that `RecorderError`, then the knob clears |
| `simulateInterruption(reason)` | ends the recording the way the system would, with the file intact — `Recording`/`Paused` → `Interrupted(RecordedFile(path, elapsed), reason)`, `Ready` → `Failed(RecordingLost(reason), null)` with the empty file deleted. Returns whether it applied |
| `simulateRecordingLost(reason, cause)` | the same, when the file could **not** be finalized: `Failed(RecordingLost(reason, cause), path)` with the file kept (`Ready` → no path, file deleted, and the cause dropped, as the real recorder has none to report there) |

and observation:

| Property | Records |
|---|---|
| `preparedPaths` | every path `prepare` opened, in order |
| `deletedPaths` | every path thrown away: by `cancel`, by re-preparing over an unused file, by a scripted `prepare`/`start` failure, and by `simulateInterruption`/`simulateRecordingLost` from `Ready`. A failed `stop` keeps its file: it is carried on the `Failed` state, and `cancel()` then deletes it |
| `completedRecordings` | every `RecordedFile` produced by `stop` |
| `releaseCount` | whether the code under test released the recorder — at most `1`, since `release` is idempotent |

Both `simulate…` calls return `false` and do nothing in any other state, after `release()`, or for
`InterruptionReason.MicrophoneSilenced` unless the fake is `Recording` — nothing is captured while
paused or merely prepared, so the real recorder does not raise it there either. They freeze
`elapsed`, return `level` to `0f` and do not consume `failNextOperationWith`. The fake follows the
same table afterwards: `stop()` from `Interrupted` returns `Success(recording)` and keeps the state,
`cancel()` from `Interrupted` or from a `Failed` with a path deletes the file (recorded in
`deletedPaths`) and returns to `Idle`, `prepare()` leaves the interrupted file alone.

```kotlin
@Test
fun `a call during a recording keeps what was said`() = runTest {
    val recorder = FakeAudioRecorder()
    val viewModel = VoiceNoteViewModel(recorder)
    viewModel.onRecordClicked()
    recorder.advanceElapsed(8.seconds)

    recorder.simulateInterruption(InterruptionReason.AudioSessionInterrupted)

    assertEquals(8.seconds, viewModel.savedSegments.single().duration)
}
```

`advanceElapsed` only moves time while the fake is `Recording`, matching the real recorder, and is
ignored elsewhere so a test can advance unconditionally between steps. `emitLevel` follows the same
rule, because a real recorder reports no level while paused or stopped. `level` returns to `0f`
after every transition out of `Recording` (pause, stop, cancel, release, a scripted failure), and
`start` and `resume` leave it at `0f` until the next `emitLevel`. Those resets are not samples, so
`levelSamples` carries only what `emitLevel` emitted. Because `level` is a `StateFlow` it shows
equal repeats once; `levelSamples` delivers each call, which is what a waveform test should collect.
The stream is hot, as on the real recorder: a sample emitted while nobody collects is gone, so start
collecting before emitting:

```kotlin
@Test
fun `the waveform keeps growing while the user is silent`() = runTest {
    val recorder = FakeAudioRecorder()
    val samples = mutableListOf<Float>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        recorder.levelSamples.collect { samples += it }
    }
    recorder.prepare()
    recorder.start()

    recorder.emitLevel(0.8f)
    recorder.emitLevel(0f)
    recorder.emitLevel(0f)
    recorder.emitLevel(0f)

    assertEquals(listOf(0.8f, 0f, 0f, 0f), samples)
}
```

### Testing an error path

```kotlin
@Test
fun `a full disk is surfaced to the user`() = runTest {
    val recorder = FakeAudioRecorder()
    recorder.failNextOperationWith = RecorderError.InsufficientStorage(
        path = "/notes",
        requiredBytes = 8 * 1024 * 1024,
        availableBytes = 512,
    )
    val viewModel = VoiceNoteViewModel(recorder)

    viewModel.onRecordClicked()

    assertEquals(VoiceNoteUi.NeedsSpace, viewModel.ui.value)
}
```

An illegal transition does **not** consume `failNextOperationWith` — it is refused as
`IllegalState` first, and the scripted error is still armed for the next legal call.

### Testing that you released it

```kotlin
@Test
fun `the component releases the recorder when it is destroyed`() {
    val recorder = FakeAudioRecorder()
    val component = RecordComponent(lifecycle, recorder)

    lifecycle.destroy()

    assertEquals(1, recorder.releaseCount)
}
```

This is the one leak the module cannot prevent for you — worth a test in any screen that owns a
recorder.

## What the fake does not do

- **No file is written.** `preparedPaths` and `completedRecordings` are strings and values; nothing
  exists on disk. Code that reads the recorded file back needs its own seam.
- **No filesystem checks.** `DirectoryNotWritable` and `InsufficientStorage` never occur on their
  own; script them with `failNextOperationWith`.
- **No format validation.** `UnsupportedFormat` likewise.
- **Not safe to call from several threads**, exactly like the recorder it replaces.
- **No subscription-driven metering.** The real recorder samples the microphone only while `level`
  or `levelSamples` has a collector. The fake has no engine whose work could be spared, so
  `emitLevel` takes effect whether or not anyone is collecting. Test the idle-when-unobserved
  behavior against the real recorder's suite, not this fake.
- **No `Preparing` state.** `prepare` suspends to match the interface but completes at once, so the
  fake never sits in `RecorderState.Preparing`. If your code branches on that state, test it against
  the real recorder's suite instead.

## Testing the module itself

The production module's own suite is in `commonTest` and runs on both the JVM and the iOS simulator.
It drives the real state machine against fakes for the two platform seams (the native engine and the
filesystem), which is what makes the whole contract — illegal transitions, permission refusal, an
unwritable directory, a full disk, cancellation mid-preparation, double release, use after release —
assertable without a device.

`androidUnitTest` adds Robolectric coverage for the Android-specific pieces that are not a
pass-through call: the real `Context`-backed filesystem, an assertion that `RECORD_AUDIO` is absent
from the merged manifest and that a missing grant produces `PermissionDenied` rather than a crash,
and the engine's event wiring — `ShadowMediaRecorder` exposes the error and info listeners the
engine registered, so the tests invoke them directly, check the code mapping, and check that the
listeners are removed before `stop()` and on `release()`. `AudioRecordingMonitor` has no shadow and
its glue is deliberately thin; the debounce behind it is tested in common code. `iosTest` posts
`AVAudioSessionInterruptionNotification` and the media-services reset through `NSNotificationCenter`
with the real `userInfo` shape, calls the recorder delegate directly, and asserts that observers are
removed afterwards. A real `AVAudioRecorder` cannot prepare in the simulator test host, so the
strong-delegate wiring and the finalize-after-interruption behaviour are confirmed on a device, not
here.

```bash
./gradlew :kmptoolkit-audio-recorder:build checkKotlinAbi
./gradlew :kmptoolkit-audio-recorder:testDebugUnitTest :kmptoolkit-audio-recorder:iosSimulatorArm64Test
./gradlew :kmptoolkit-audio-recorder-testing:testDebugUnitTest :kmptoolkit-audio-recorder-testing:iosSimulatorArm64Test
```

`FakeAudioRecorder` restates the transition table rather than sharing the production state machine —
Kotlin's `internal` does not cross a module boundary, and exposing the internal engine seam publicly
just to share it would put implementation detail into the published ABI. The cost is that the two
could drift, so the fake carries its own suite derived from the same documented table, and any
change to the table has to be made in both places and shows up as a failure in one suite if it is
not.
