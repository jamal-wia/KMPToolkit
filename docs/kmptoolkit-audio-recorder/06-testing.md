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
val recorder = FakeAudioRecorder()
val viewModel = VoiceNoteViewModel(recorder)

viewModel.onRecordClicked()
recorder.advanceElapsed(3.seconds)
viewModel.onStopClicked()

assertEquals(3.seconds, recorder.completedRecordings.single().duration)
```

It enforces the same transition table as the real recorder, so a test that passes against it is
testing behavior the real recorder also has. What it adds is control:

| Knob | Effect |
|---|---|
| `advanceElapsed(duration)` | moves `elapsed` forward. Time never passes on its own — no scheduler, no virtual clock, exact assertions |
| `permissionGranted = false` | `prepare()` fails with `PermissionDenied` |
| `failNextOperationWith = error` | the next otherwise-legal operation fails with that `RecorderError`, then the knob clears |

and observation:

| Property | Records |
|---|---|
| `preparedPaths` | every path `prepare` opened, in order |
| `deletedPaths` | every path thrown away by `cancel` or by re-preparing over an unused file |
| `completedRecordings` | every `RecordedFile` produced by `stop` |
| `releaseCount` | whether the code under test released the recorder — at most `1`, since `release` is idempotent |

`advanceElapsed` only moves time while the fake is `Recording`, matching the real recorder, and is
ignored elsewhere so a test can advance unconditionally between steps.

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
- **Not thread-safe**, exactly like the recorder it replaces.

## Testing the module itself

The production module's own suite is in `commonTest` and runs on both the JVM and the iOS simulator.
It drives the real state machine against fakes for the two platform seams (the native engine and the
filesystem), which is what makes the whole contract — illegal transitions, permission refusal, an
unwritable directory, a full disk, cancellation mid-preparation, double release, use after release —
assertable without a device.

`androidUnitTest` adds Robolectric coverage for the one Android-specific piece that is not a
pass-through call: the real `Context`-backed filesystem, plus an assertion that `RECORD_AUDIO` is
absent from the merged manifest and that a missing grant produces `PermissionDenied` rather than a
crash.

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
