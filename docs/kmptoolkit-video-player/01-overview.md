# kmptoolkit-video-player — Overview

A headless video player for shared Kotlin code: one `VideoPlayer` interface over Media3 ExoPlayer on
Android and `AVPlayer` on iOS (and a desktop engine of your choice on JVM), a typed
`VideoPlayerState` flow, separate flows for buffering, buffered position, picture size, speed,
volume, mute and repeat mode, and an explicit release contract.

## The problem it solves

A video screen in shared code needs the same things on every platform — load a URL, play, pause,
seek, show a spinner while the stream stalls, size the picture, report how much was watched — and
each platform spells every one of them differently. Written per platform, the two copies drift in
exactly the places that are hardest to notice: what `stop()` means, whether a seek on a finished
video restarts it, whether a late callback after the screen closed resurrects the state.

This module writes the state machine **once**, in `commonMain`, and leaves each platform engine with
nothing but API translation:

```kotlin
val player: VideoPlayer = createVideoPlayer(context)          // androidMain
val player: VideoPlayer = createVideoPlayer()                 // iosMain

player.prepare(VideoSource.Remote("https://cdn.example.com/lesson-3/master.m3u8"))
player.play()

player.stateFlow.collect { state: VideoPlayerState ->
    render(progress = state.progress, playing = state.isPlaying)
}

player.release()
```

It is **headless**: it plays and reports, and draws nothing. Put the picture on screen with
`kmptoolkit-video-player-compose` (a surface, or a surface with ready-made controls), or render the
flows into your own UI.

The transport contract is `kmptoolkit-audio-player`'s, on purpose — the same seven states, the same
`prepare` that never throws, the same release rules — so code that handles one player reads the same
against the other.

## What this is **not**

- **Not a UI.** No view, no composable, no controls — those live in
  `kmptoolkit-video-player-compose`, so a consumer that draws its own UI never pulls Compose in.
- **Not a media session or a background player.** No `MediaSession`, no notification, no
  lock-screen controls, no picture-in-picture, no playback with the app in the background. Out of
  scope for this release.
- **Not a DRM, subtitle, track-selection or casting stack.** One source, its default tracks. An app
  that needs Widevine/FairPlay, caption tracks, audio-language switching or Chromecast needs the
  platform player directly.
- **Not a downloader or a cache.** The library never downloads, caches or deletes a source. Download
  the file yourself and pass its path as `VideoSource.File`.
- **Not a fullscreen implementation.** Whether the video takes over the screen, and how, is the app's
  navigation decision. The Compose controls only expose a fullscreen button that calls back into your
  code.
- **Not an audio-focus or interruption manager.** The player does not request audio focus, does not
  pause when headphones are unplugged, and does not observe `AVAudioSession` interruptions. Those are
  app-wide policies; see [`05-platform-notes.md`](05-platform-notes.md).
- **Not a DI module, and no user-facing text.** A factory function you wrap in your own container;
  `VideoPlayerState.Error` carries a `Throwable`, never a sentence.
- **Not a place to race your own calls.** Every call is safe from any thread and is one atomic
  transition, serialized with the position poll and with the platform's own events, so nothing can
  overwrite a state another transition just wrote and `release()` cannot double-free. Which of two
  calls made at once from two threads lands first is still up to them; a screen drives its player
  from one thread.
- **Not a desktop engine.** The JVM target exists so shared UI code compiles for desktop; the engine
  there is your choice of `kmptoolkit-video-player-vlcj` or `kmptoolkit-video-player-javafx`, each a
  separate artifact so its licence reaches only the apps that opt in.

## What you get

| Type | Role |
|---|---|
| `VideoPlayer` | The interface: nine flows, the transport controls, settings, `unload()`, `release()`/`close()` |
| `VideoPlayerState` | `Idle`, `Preparing`, `Ready`, `Playing`, `Paused`, `Completed`, `Error(cause)` |
| `VideoSource` | `Asset`, `File`, `Remote(url, headers, format)` — where the video lives |
| `VideoSize` | The decoded picture size, already rotated and pixel-aspect corrected |
| `RepeatMode` | `Off` (stop at the end) or `One` (loop the source) |
| `VideoPlayerConfig` | Polling interval and playback-speed bounds |
| `VideoPlaybackEngine` | The platform seam; implement it to bring your own backend |
| `createVideoPlayer(...)` | Factory — `Context` on Android, bundle options on iOS, an engine anywhere |

## When to use it

Use it when shared code plays video and the surrounding app should not care which platform it runs
on: a course app's lesson videos, an exercise demo that loops, a product clip in a feed, a "watched
95%" check that unlocks the next step.

If you need DRM, background playback or casting, use Media3 and AVKit directly — you would be
fighting this module's deliberate scope.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a playing video in five minutes
- [`03-guide.md`](03-guide.md) — lifecycle, errors, buffering, settings, custom engines
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — permissions, HLS, threading, per-platform behavior
- [`06-testing.md`](06-testing.md) — testing your code against `VideoPlayer` without a device
