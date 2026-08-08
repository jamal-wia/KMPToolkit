# kmptoolkit-audio-recorder — Getting started

A working recording in five minutes.

## 1. Add the dependency

```kotlin
// shared/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-audio-recorder:<version>")
        }
        commonTest.dependencies {
            // optional — see 06-testing.md
            implementation("io.github.jamal-wia:kmptoolkit-audio-recorder-testing:<version>")
        }
    }
}
```

With the BOM, drop the version:

```kotlin
implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
implementation("io.github.jamal-wia:kmptoolkit-audio-recorder")
```

## 2. Declare the permission — in **your** app, not here

This library declares no Android permission and adds no `Info.plist` key. Add both yourself:

```xml
<!-- androidApp/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

```xml
<!-- iosApp/Info.plist -->
<key>NSMicrophoneUsageDescription</key>
<string>Records your voice notes.</string>
```

Without the `Info.plist` key, iOS terminates the app the first time it touches the microphone —
that one is not a `RecorderError`, it is a hard requirement of the platform. See
[`05-platform-notes.md`](05-platform-notes.md).

## 3. Create the recorder in your platform layer

The factory differs per platform because Android needs a `Context` and iOS does not. Everything
above it works against the common `AudioRecorder` interface.

```kotlin
// androidMain
val recorder: AudioRecorder = createAudioRecorder(context)
```

```kotlin
// iosMain
val recorder: AudioRecorder = createAudioRecorder()
```

No DI framework is involved. If you use one, wrap the factory:

```kotlin
val audioModule = module {
    single<AudioRecorder> { createAudioRecorder(androidContext()) }
}
```

## 4. Record something

```kotlin
suspend fun record(recorder: AudioRecorder): RecordedFile? {
    // Ask for RECORD_AUDIO with your own permission flow first — prepare only reports the grant,
    // it never requests it.
    when (val prepared = recorder.prepare()) {
        is RecorderResult.Success -> Unit
        is RecorderResult.Failure -> {
            handle(prepared.error)
            return null
        }
    }

    recorder.start()
    delay(3.seconds)          // in a real app: until the user stops
    return recorder.stop().getOrNull()
}
```

`prepare()`, `stop()`, and `cancel()` suspend; `start()`, `pause()`, and `resume()` do not. The rule
is that an operation which can touch the filesystem suspends — see
[`01-overview.md`](01-overview.md) — so a `suspend` in the signature is your warning that a call
does I/O, and the absence of one is a promise that it does not.

`prepare()` with no argument generates a path for you — `<app-private files>/<your app id>/
recording_<timestamp>.m4a`. Pass one explicitly if you want to choose:
`recorder.prepare(outputPath = "/…/take-1.m4a")`.

## 5. Show it on screen

```kotlin
val scope: CoroutineScope = rememberCoroutineScope()
val state: RecorderState by recorder.state.collectAsState()
val elapsed: Duration by recorder.elapsed.collectAsState()

when (state) {
    is RecorderState.Recording -> RecordingUi(elapsed, onStop = { scope.launch { recorder.stop() } })
    is RecorderState.Completed -> PlaybackUi((state as RecorderState.Completed).recording)
    is RecorderState.Failed -> ErrorUi((state as RecorderState.Failed).error)
    else -> IdleUi(onRecord = { scope.launch { record(recorder) } })
}
```

`onStop` needs the `scope.launch` because `stop()` suspends — that is the point of the rule: writing
it makes it visible that tapping stop finalizes a file rather than flipping a flag.

`state` changes only on real transitions; `elapsed` is the one that ticks. Collect them separately
so the timer does not invalidate the rest of the screen.

## 6. Release it

```kotlin
override fun onDestroy() {
    recorder.release()
    super.onDestroy()
}
```

The recorder holds the microphone and a native handle until you do. Nothing releases it for you.
`release()` is deliberately **not** suspending so it can be called from exactly this kind of
teardown, where the screen's coroutine scope is already gone. See
[`03-guide.md`](03-guide.md#ownership-and-release) for where this belongs in a Decompose component,
a ViewModel, or a Swift view model.

## Next

- [`03-guide.md`](03-guide.md) — pause/resume, error handling, storage layout, common mistakes
- [`06-testing.md`](06-testing.md) — testing the code you just wrote without a microphone
