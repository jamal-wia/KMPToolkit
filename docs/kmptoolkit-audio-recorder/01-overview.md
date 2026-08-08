# kmptoolkit-audio-recorder — Overview

One `AudioRecorder` interface over `android.media.MediaRecorder` and `AVAudioRecorder`, with an
explicit state machine and typed errors instead of platform exceptions.

## The problem it solves

Recording a voice note is three lines of shared logic sitting on top of two native APIs that
disagree about almost everything, and both of which are strict state machines that throw when you
get the order wrong. `MediaRecorder.start()` before `prepare()` throws `IllegalStateException`;
`AVAudioRecorder.record()` quietly returns `false`. Recording without `RECORD_AUDIO` crashes on
Android. A full disk produces a truncated file that only fails when you try to play it.

This module puts one contract in front of all of that:

```kotlin
val recorder: AudioRecorder = createAudioRecorder(context) // Android; no argument on iOS

when (val prepared = recorder.prepare()) {
    is RecorderResult.Success -> recorder.start()
    is RecorderResult.Failure -> when (prepared.error) {
        RecorderError.PermissionDenied -> requestMicrophonePermission()
        is RecorderError.InsufficientStorage -> showFreeUpSpace()
        else -> showGenericProblem()
    }
}
```

Four things follow from that shape, and they are the reason the module exists:

- **Every failure is a value.** An illegal transition, a missing permission, an unwritable
  directory, a volume with no room, a codec the platform does not have — each is a `RecorderError`
  case you can branch on. Nothing in the public API throws, and the platform's own exceptions never
  reach you: they arrive wrapped as `RecorderError.EngineFailure`.
- **The lifecycle is written down.** `prepare → start → pause ⇄ resume → stop` is a table on
  `AudioRecorder`, not folklore. Calls that do not fit it are refused and leave the recorder
  untouched, so a double-tapped button cannot corrupt a recording.
- **Doomed recordings fail before the microphone opens.** Permission, format support, directory
  writability, and free space are all checked in `prepare`, so you learn about the problem before
  the user has spoken into a file that will be thrown away.
- **The signature tells you what can block.** *An operation that can touch the filesystem suspends;
  an operation that only moves recorder state does not.* So `prepare()`, `stop()`, and `cancel()`
  are `suspend`, and `start()`, `pause()`, and `resume()` are not — you never have to check the
  documentation to find out whether a call is about to do I/O. The suspending three run that work on
  the `coroutineContext` the factory was given, so they do not block your thread either.
  `release()` is the one exception, and its reason is under "What this is not" below.

Observing is two `StateFlow`s: `state` for the lifecycle, `elapsed` for a recording timer. They are
separate on purpose — `state` changes only on real transitions, so a screen that shows "recording"
is not recomposed ten times a second by a duration that is only rendered in one label.

## What this is **not**

- **Not a player.** It records; playing the file back is a different module's job (or
  `MediaPlayer`/`AVAudioPlayer` directly).
- **Not a permission requester.** It reports `RecorderError.PermissionDenied` and stops. It never
  shows a system prompt, and it never declares `RECORD_AUDIO` in its manifest — see
  [`05-platform-notes.md`](05-platform-notes.md). Asking the user is your flow, with your copy, at
  your moment.
- **Not an audio-processing pipeline.** There is no PCM buffer callback, no waveform, no amplitude
  meter, no VAD, no noise suppression, no streaming to a socket. It writes an encoded file with the
  platform's own recorder and tells you when it is done. If you need samples in memory, you need
  `AudioRecord` / `AVAudioEngine`, not this.
- **Not a background-recording service.** It does not hold a wake lock, post a foreground-service
  notification, or survive the process. Recording while the app is backgrounded is a platform
  problem with platform requirements (a foreground service on Android, an audio background mode on
  iOS) that belong to the app, not to a library.
- **Not multi-track or concurrent.** One recorder records one file at a time. Two simultaneous
  recordings mean two recorders, and the microphone will usually refuse the second.
- **Not a transcoder.** `AudioFormat` picks what the platform encoder can produce. It does not
  convert an existing file, and it does not fake a format the platform lacks: asking for WAV on
  Android returns `RecorderError.UnsupportedFormat` rather than writing AAC into a `.wav`.
- **Not thread-safe.** Drive one recorder from one thread. It is a wrapper around native objects
  that are not thread-safe either.
- **Not self-releasing.** Native handles are freed when you call `release()`, and at no other time.
  Nothing here is garbage-collected for you. `release()` is also the one operation that breaks the
  suspend rule above: it has to be callable from `onCleared`, `doOnDestroy`, or `deinit`, which are
  exactly the places where the matching coroutine scope is already cancelled and there is nothing
  left to launch in. So it does its work inline, and releasing a long open recording on the main
  thread is the single place this library can block you.
- **Not a file manager.** It creates the output directory and deletes files it is told to abandon.
  Retention, cleanup, upload, and encryption of finished recordings are yours.

## When to use it

Use it when shared Kotlin code needs to record audio to a file and you want the failure cases to be
handleable — voice notes, audio comments, a recite-and-review feature, a bug report with a spoken
description.

If you are writing a single-platform app and are happy to talk to `MediaRecorder` directly, you do
not need this indirection. Its value is the shared contract plus the error taxonomy, and both only
pay off when more than one platform or more than one caller is involved.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a recording, start to finish, in five minutes
- [`03-guide.md`](03-guide.md) — the lifecycle in practice, error handling, storage, ownership
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — `RECORD_AUDIO`, `Info.plist`, per-platform behavior
- [`06-testing.md`](06-testing.md) — `FakeAudioRecorder` and how to test around a recorder
