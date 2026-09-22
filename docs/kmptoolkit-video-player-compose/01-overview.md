# kmptoolkit-video-player-compose — Overview

Compose Multiplatform UI for [`kmptoolkit-video-player`](../kmptoolkit-video-player/01-overview.md):
a surface that shows a player's picture on Android, iOS and desktop, and a ready-made player with
controls in which **every part can be replaced**.

## The problem it solves

A headless `VideoPlayer` plays, pauses, seeks and reports state, but it draws nothing. Putting its
picture on screen is different on every platform — a `SurfaceView` attached to a Media3 player on
Android, an `AVPlayerLayer` on iOS, decoded frames copied into a bitmap on desktop — and a player UI
is more than a picture: controls that appear on a tap and fade away while playing, a seek bar that
does not jump back while the user drags it, a spinner while buffering, the screen kept awake, the
video paused when the app goes to the background.

This module does all of that behind two composables:

```kotlin
// Quick start: create, load, play and release a player in one line.
VideoPlayer(source = VideoSource.Remote(url), modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))

// A player your view model owns, with the default controls.
VideoPlayer(player = viewModel.player, modifier = Modifier.fillMaxSize())
```

## Every part is replaceable

The controls are a slot, not a feature toggle. From the least to the most work:

1. **Use the defaults.** `DefaultVideoControls()` — play/pause, seek ±10 s, seek bar with buffered
   track, times, mute, speed, optional fullscreen button.
2. **Restyle them.** `DefaultVideoControls(colors = …, dimensions = …, textStyle = …, speeds = …)`,
   and hide parts with `showSeekButtons`, `showMuteButton`, `showSpeedButton`, `showTime`.
3. **Compose your own layout from the same building blocks.** `PlayPauseButton`, `ReplayButton`,
   `SeekButton`, `VideoSeekBar`, `VideoTimeText`, `MuteButton`, `SpeedButton`, `FullscreenButton`,
   `BufferingIndicator`, `VideoControlButton` and the `VideoControlsIcons` are all public, and the
   default controls are built from nothing else.
4. **Write the controls entirely yourself.** The `controls` slot runs in a `VideoControlsScope`: the
   player's state as snapshot state, the show/hide state, and every action. Your Material, Cupertino
   or brand controls read and act through exactly the API the defaults use.
5. **Drop `VideoPlayer` altogether.** `VideoPlayerSurface` is the picture alone;
   `rememberVideoControlsScope` and `PauseOnBackgroundEffect` are the rest of what `VideoPlayer`
   does, for a layout of your own (controls under the picture instead of over it, say).

See [`03-guide.md`](03-guide.md#replace-any-part-of-the-controls) for an example of each.

## What this is **not**

- **Not a fullscreen implementation.** The controls offer a fullscreen button that calls your
  callback, and only when you pass one. Rotating, hiding system bars and moving the player to
  another route are app decisions.
- **Not picture-in-picture, background playback, casting, subtitles or DRM.** Out of scope for v1,
  like in the core module.
- **Not localized, and not a place for your copy.** The controls draw icons, digits (`1:05`) and a
  speed multiplier (`1.5×`) — no words. Screen-reader labels come from `VideoControlsLabels`, whose
  every field is `null` by default: pass localized strings. See
  [`03-guide.md`](03-guide.md#accessibility-labels).
- **Not a Material component.** The module depends on Compose `foundation` only; the icons are drawn
  in code. It looks neutral (white over a translucent scrim) and adopts no theme.
- **Not a desktop engine.** On desktop the surface draws whatever a memory-rendering engine hands it.
  The engine is your choice and a separate artifact — `kmptoolkit-video-player-vlcj` or
  `kmptoolkit-video-player-javafx` — provided once through `LocalVideoPlayerFactory`. See
  [`05-platform-notes.md`](05-platform-notes.md#desktop).

## What it is made of

| Symbol | Role |
|---|---|
| `VideoPlayer(player = …)` | Surface + tap-to-toggle controls + pause in background, for a player you own |
| `VideoPlayer(source = …)` | The same, with a player it creates, loads and releases itself; `onProgress` callback |
| `VideoPlayerSurface` | The picture alone, sized and fitted by `VideoScaleMode`, keeping the screen on while playing |
| `VideoScaleMode` | `Fit` (letterbox), `Fill` (stretch), `Crop` (zoom and crop) |
| `rememberVideoPlayer` | A composition-owned player: created, prepared per source, released on dispose |
| `VideoPlayerFactory` / `LocalVideoPlayerFactory` | How a player is created; how desktop picks its engine |
| `VideoControlsScope` / `rememberVideoControlsScope` | State and actions for any controls implementation |
| `DefaultVideoControls` | The ready-made controls |
| Building blocks | `PlayPauseButton`, `ReplayButton`, `SeekButton`, `VideoSeekBar`, `VideoTimeText`, `MuteButton`, `SpeedButton`, `FullscreenButton`, `BufferingIndicator`, `VideoControlButton` |
| `VideoControlsColors` / `VideoControlsDimensions` / `VideoControlsLabels` / `VideoControlsDefaults` / `VideoControlsIcons` | Styling, sizing, accessibility labels, defaults, icons |
| `PauseOnBackgroundEffect` | Pause on `ON_STOP`, for a layout of your own |
| `formatVideoTime` | `m:ss` / `h:mm:ss` |

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a playing video in five minutes
- [`03-guide.md`](03-guide.md) — ownership, sizing, replacing controls, desktop engines, lifecycle
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — Android, iOS and desktop specifics, permissions
- [`06-testing.md`](06-testing.md) — testing screens that use these composables
