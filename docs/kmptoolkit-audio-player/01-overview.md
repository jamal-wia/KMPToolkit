# kmptoolkit-audio-player — Overview

A headless audio player for shared Kotlin code: one `AudioPlayer` interface over
`android.media.MediaPlayer` and `AVFoundation.AVPlayer`, a typed `PlayerState` flow with the
playhead in it, and an explicit release contract.

## The problem it solves

Playing a sound from common code means writing the same thing twice — a native handle, a set of
platform callbacks, a position poller, and a state machine mapping all of it onto something a UI can
render. The two copies then drift, and they drift in the least visible way possible: they behave
identically until the edge case where they do not.

This module writes the state machine **once**, in `commonMain`, and leaves each platform with
nothing but API translation:

```kotlin
val player: AudioPlayer = createAudioPlayer(context)          // androidMain
val player: AudioPlayer = createAudioPlayer()                 // iosMain

player.prepare(AudioSource.Remote("https://example.com/voice.m4a"))
player.play()

player.stateFlow.collect { state: PlayerState ->
    render(progress = state.progress, playing = state.isPlaying)
}

player.release()
```

Everything that has a behavior worth arguing about — when `Playing` becomes `Paused`, what a seek
past the end does, what happens to a call that arrives after teardown — lives above the platform
seam and is covered by common tests. `PlaybackEngine`, the seam itself, has no state of its own.

## What this is **not**

The donor module this was ported from carried a lot that does not belong in a published library.
None of it survived, and the omissions are deliberate:

- **Not a media session, notification, or background-playback stack.** No `MediaSession`, no
  `MediaBrowserService`, no lock-screen controls, no now-playing metadata, no `AVAudioSession`
  interruption or route-change handling. Playback continues in the background only as far as the
  platform lets it with no session declared. An app that needs a real background player needs
  Media3 `MediaSession` on Android and its own session handling on iOS — this module deliberately
  does not pretend to be that.
- **Not ExoPlayer/Media3, and no path to it out of the box.** The built-in engines are `MediaPlayer`
  and `AVPlayer`, so adding this module costs a consumer no megabytes of transitive media
  dependency. If you need Media3's format support, DRM, or gapless playback, implement
  `PlaybackEngine` over it yourself and keep this state machine — that is exactly what the interface
  is public for.
- **Not a downloader or a cache.** The donor shipped an `AudioCacheDirectory` that created a folder,
  measured it, and emptied it. It is gone: none of it is playback, the player never called it, and a
  directory-management helper that happens to live in an audio artifact is how a "core" module turns
  into a junk drawer. Download the file with your HTTP client, put it wherever your app keeps files,
  and hand the path over as `AudioSource.File`.
- **Not a recorder.** Playback only. Nothing here asks for `RECORD_AUDIO`, and the library declares
  no permission at all — see [`05-platform-notes.md`](05-platform-notes.md).
- **Not a mixer.** One instance plays one source. Two overlapping sounds means two players, and
  what happens when both play at once is the platform's mixing behavior, not something this module
  arbitrates.
- **Not a JVM/desktop player.** The donor had a JVM engine whose `prepare()` always failed —
  a compiling placeholder. This library targets Android and iOS only, so there is nothing to
  placehold.
- **Not a UI, and no user-facing text.** `PlayerState.Error` carries a `Throwable`, never a sentence.
  What that failure looks like on screen, and in which language, is the consuming app's decision.
- **Not a DI module.** No Koin, no Hilt. The donor shipped three `AudioPlayerModule` files; the
  public API here is an interface plus a factory function you wrap in whatever container you already
  use.
- **Not thread-safe for concurrent transport calls.** Drive one player from one thread. The flows
  are safe to collect anywhere, and `release()` cannot double-free — but `play()` racing `seekTo()`
  from two threads is not a supported thing to do.

## What you get

| Type | Role |
|---|---|
| `AudioPlayer` | The interface: two flows, the transport controls, `release()`/`close()` |
| `PlayerState` | `Idle`, `Preparing`, `Ready`, `Playing`, `Paused`, `Completed`, `Error(cause)` |
| `AudioSource` | `Asset`, `File`, `Remote` — where the bytes live |
| `AudioPlayerConfig` | Polling interval and playback-speed bounds |
| `PlaybackEngine` | The platform seam; implement it to bring your own backend |
| `createAudioPlayer(...)` | Factory — `Context` on Android, bundle options on iOS |

## When to use it

Use it when shared code needs to play a sound, a voice message, or a track, and the surrounding app
should not have to care which platform it is on: a chat feature playing voice notes, a language app
playing pronunciation, a UI playing short cues.

If you need background playback with lock-screen controls, reach for Media3 and its iOS counterpart
directly — you would end up fighting this module's deliberate lack of a media session.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a working player in five minutes
- [`03-guide.md`](03-guide.md) — lifecycle, error handling, seeking, custom engines
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — permissions, cleartext HTTP, audio sessions
- [`06-testing.md`](06-testing.md) — testing your code against `AudioPlayer` without a device
