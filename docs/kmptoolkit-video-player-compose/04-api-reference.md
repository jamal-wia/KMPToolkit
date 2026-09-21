# kmptoolkit-video-player-compose — API reference

Package: `io.github.jamal_wia.kmptoolkit.video.player.compose`

This file mirrors the committed ABI dumps at `kmptoolkit-video-player-compose/api/`. If they
disagree, the dump is authoritative and this file is a bug.

All composables must be called from composition (main thread). None of them throws for a player
in any state, including a released one: they read its flows and call its transport methods, which
the core contract makes safe in every state.

## Players

### `VideoPlayer(player = …)`

```kotlin
@Composable
public fun VideoPlayer(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    scaleMode: VideoScaleMode = VideoScaleMode.Fit,
    keepScreenOn: Boolean = true,
    pauseOnBackground: Boolean = true,
    backgroundColor: Color = Color.Black,
    controlsAutoHideDelayMs: Long = VideoControlsDefaults.AutoHideDelayMs,
    controls: @Composable VideoControlsScope.() -> Unit = { DefaultVideoControls() },
)
```

The picture (`VideoPlayerSurface`) with `backgroundColor` behind it and `controls` over it; a tap on
the picture calls `VideoControlsScope.toggleControls()`. Runs `PauseOnBackgroundEffect(player,
pauseOnBackground)` and `rememberVideoControlsScope(player, controlsAutoHideDelayMs)`.

- Sizes like `VideoPlayerSurface`: fills its bounds; an unbounded dimension follows the picture ratio.
- **Never releases `player`.**
- `controlsAutoHideDelayMs` must be positive — `IllegalArgumentException` otherwise.
- `controls = {}` draws no controls.

### `VideoPlayer(source = …)`

```kotlin
@Composable
public fun VideoPlayer(
    source: VideoSource,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
    onProgress: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
    scaleMode: VideoScaleMode = VideoScaleMode.Fit,
    keepScreenOn: Boolean = true,
    pauseOnBackground: Boolean = true,
    backgroundColor: Color = Color.Black,
    controlsAutoHideDelayMs: Long = VideoControlsDefaults.AutoHideDelayMs,
    config: VideoPlayerConfig = VideoPlayerConfig(),
    controls: @Composable VideoControlsScope.() -> Unit = { DefaultVideoControls() },
)
```

`rememberVideoPlayer(source, autoPlay, config)` rendered with the overload above. **Releases its
player when it leaves the composition.**

- `onProgress(positionMs, durationMs)` — on the main thread, whenever the pair changes, and only
  while the duration is positive. `null` (the default) collects nothing.
- Throws `IllegalStateException` on desktop when no `LocalVideoPlayerFactory` is provided.

### `rememberVideoPlayer`

```kotlin
@Composable
public fun rememberVideoPlayer(
    source: VideoSource?,
    autoPlay: Boolean = false,
    config: VideoPlayerConfig = VideoPlayerConfig(),
): VideoPlayer
```

| Aspect | Contract |
|---|---|
| Creation | Once, on first composition, through `LocalVideoPlayerFactory.current` or else the platform factory. `config` and the factory are read then and never again. |
| Source | Each `source` (by `equals`) is prepared in a coroutine of the composition; a newer one cancels an older prepare still in flight. `null` calls `unload()`. |
| `autoPlay` | `play()` after a prepare returns, unless that prepare was superseded. Read when the prepare returns, so changing it applies to the next source. |
| Release | `release()` when the call leaves the composition. Do not release it yourself or use it afterwards. |
| Failure | A failed load is the player's `Error` state; nothing is thrown. |
| Missing engine | `IllegalStateException` on desktop without a provided factory, with a message naming `LocalVideoPlayerFactory` and the engine artifacts. |

### `VideoPlayerFactory`, `LocalVideoPlayerFactory`

```kotlin
public fun interface VideoPlayerFactory {
    public fun create(config: VideoPlayerConfig): VideoPlayer
}

public val LocalVideoPlayerFactory: ProvidableCompositionLocal<VideoPlayerFactory?> // default null
```

`create` must return a new, unshared player each call; the caller owns and releases it. `null`
means the platform factory: `createVideoPlayer(LocalContext.current, config)` on Android,
`createVideoPlayer(config = config)` on iOS, none on desktop.

## Surface

### `VideoPlayerSurface`

```kotlin
@Composable
public fun VideoPlayerSurface(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    scaleMode: VideoScaleMode = VideoScaleMode.Fit,
    keepScreenOn: Boolean = true,
)
```

The picture only. Never changes the player's state; never releases it.

- **Size**: both dimensions bounded → the bounds. One unbounded → derived from
  `videoSizeFlow`'s aspect ratio, or the minimum constraint until it is known. Both unbounded → the
  minimum constraints.
- **Picture**: sized by `scaleMode` inside those bounds, centred, clipped to them. The ratio is
  `videoSizeFlow`'s — the display shape, so an anamorphic source is right on every platform; on
  desktop each frame is stretched over that picture box, and only while no size is known is a
  frame placed by its own pixel ratio.
- **Platform**: Android `SurfaceView` attached to the Media3 player; iOS `AVPlayerLayer` with
  `videoGravity` from `scaleMode`; desktop the frames of a memory-rendering engine. A player with no
  such platform object (a fake, a custom engine) shows nothing.
- **`keepScreenOn`**: only while `stateFlow` is `Playing`. Android `View.keepScreenOn`; iOS
  `UIApplication.idleTimerDisabled`, shared by all surfaces and restored to the app's own value when
  the last one stops; desktop nothing.

### `VideoScaleMode`

`Fit` — whole picture, bars around it. `Fill` — stretched to the bounds. `Crop` — covers the bounds,
overflow cropped.

## Controls state

### `VideoControlsScope`

A `@Stable` interface, implemented by this library only (members may be added in a minor
release). Implementing it requires opting in to `@InternalForInheritanceVideoControlsApi` — it is
`@SubclassOptInRequired`, and an implementation outside the library is not covered by any
compatibility promise. Using a scope needs no opt-in. Every property is backed by snapshot state.

| Property | Contract |
|---|---|
| `player: VideoPlayer` | The player |
| `playerState: VideoPlayerState` | `stateFlow`'s value |
| `isPlaying: Boolean` | `playerState is Playing` |
| `positionMs: Long` | `playbackPositionFlow`'s value |
| `durationMs: Long` | the state's duration, `0` while unknown |
| `bufferedPositionMs: Long` | `bufferedPositionFlow`'s value |
| `isBuffering: Boolean` | `isBufferingFlow`'s value |
| `isMuted: Boolean`, `volume: Float`, `playbackSpeed: Float`, `repeatMode: RepeatMode`, `videoSize: VideoSize?` | the matching flows' values |
| `controlsVisible: Boolean` | `true` initially and whenever playback stops running; `false` after the auto-hide delay while playing without interaction, or on `hideControls()` |

| Function | Contract |
|---|---|
| `showControls()` | Visible, and restarts the countdown |
| `hideControls()` | Hidden now |
| `toggleControls()` | Hide if visible, show otherwise |
| `setInteracting(Boolean)` | While `true`, no auto-hide; restarts the countdown on every call |
| `play()`, `pause()`, `seekTo(ms)`, `replay()`, `setMuted(b)`, `setVolume(v)`, `setPlaybackSpeed(s)`, `setRepeatMode(m)` | The player's method, then restart the countdown |
| `togglePlayPause()` | `pause()` while playing, `replay()` when completed, `play()` otherwise — decided on the player's current state, not the mirrored one |
| `seekBy(deltaMs)` | `seekForward(deltaMs)`, or `seekBackward(-deltaMs)` when negative |
| `toggleMute()` | `setMuted(!player.isMutedFlow.value)` |

### `rememberVideoControlsScope`

```kotlin
@Composable
public fun rememberVideoControlsScope(
    player: VideoPlayer,
    autoHideDelayMs: Long = VideoControlsDefaults.AutoHideDelayMs,
): VideoControlsScope
```

One scope per `player`, collecting its flows while composed and running the auto-hide countdown.
`autoHideDelayMs` is passed through the platform's accessibility recommendation
(`AccessibilityManager.calculateRecommendedTimeoutMillis`), which may lengthen it to "never" while a
screen reader is on. `IllegalArgumentException` when not positive.

### `PauseOnBackgroundEffect`

```kotlin
@Composable
public fun PauseOnBackgroundEffect(player: VideoPlayer, enabled: Boolean = true)
```

At `Lifecycle.Event.ON_STOP` of `LocalLifecycleOwner`, calls `player.pause()` if it is `Playing`.
Never resumes. `enabled = false` observes nothing.

## Ready-made controls

### `DefaultVideoControls`

```kotlin
@Composable
public fun VideoControlsScope.DefaultVideoControls(
    modifier: Modifier = Modifier,
    colors: VideoControlsColors = VideoControlsDefaults.colors(),
    dimensions: VideoControlsDimensions = VideoControlsDefaults.dimensions(),
    labels: VideoControlsLabels = VideoControlsLabels(),
    textStyle: TextStyle = VideoControlsDefaults.ControlTextStyle,
    onFullscreenClick: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    speeds: List<Float> = VideoControlsDefaults.Speeds,
    seekStepMs: Long = DEFAULT_SEEK_AMOUNT_MS,
    showSeekButtons: Boolean = true,
    showMuteButton: Boolean = true,
    showSpeedButton: Boolean = true,
    showTime: Boolean = true,
    formatSpeed: (Float) -> String = VideoControlsDefaults::formatSpeed,
)
```

Fills its parent. While `controlsVisible` (fading in and out): a `colors.scrim` layer; centred, the
seek-back button, the centre button (`ReplayButton` when `Completed`, `PlayPauseButton` otherwise)
and the seek-forward button; along the bottom, the position (the drag position while scrubbing), a
`VideoSeekBar`, the duration, `MuteButton`, `SpeedButton` and — only when `onFullscreenClick` is
not `null` — `FullscreenButton`. Always, while buffering or `Preparing`: a `BufferingIndicator` in
the centre in place of the centre button. Transport controls are disabled while the state is not
playable (`Idle`, `Preparing`, `Error`); mute is always enabled.

## Building blocks

Every block takes plain values and callbacks, draws with `VideoControlsColors`, and is announced by
the matching `VideoControlsLabels` field (`null` → role only).

| Composable | Parameters beyond `modifier`, `colors`, `labels` | Behaviour |
|---|---|---|
| `VideoControlButton(icon, onClick, contentDescription, enabled, buttonSize, iconSize)` | — | Round `buttonSize` touch target drawing `icon` tinted `content` / `disabledContent` |
| `PlayPauseButton(isPlaying, onClick, enabled, buttonSize, iconSize)` | defaults to the centre sizes | Pause icon + `labels.pause` while playing, play icon + `labels.play` otherwise |
| `ReplayButton(onClick, enabled, buttonSize, iconSize)` | centre sizes | `labels.replay` |
| `SeekButton(forward, onClick: (deltaMs) -> Unit, stepMs, enabled, buttonSize, iconSize)` | `stepMs` = 10 s | Calls `onClick(+stepMs)` or `onClick(-stepMs)`; `labels.seekForward` / `seekBackward` |
| `MuteButton(isMuted, onClick, enabled, buttonSize, iconSize)` | — | `labels.unmute` while muted, `labels.mute` otherwise |
| `FullscreenButton(isFullscreen, onClick, buttonSize, iconSize)` | — | Calls `onClick` only; `labels.exitFullscreen` / `enterFullscreen` |
| `SpeedButton(speed, onSpeedChange, speeds, enabled, formatSpeed, buttonSize, textStyle)` | — | Shows `formatSpeed(speed)`; a tap calls `onSpeedChange` with the next entry of `speeds` (wrapping; a speed not in the list moves to the first entry above it). `IllegalArgumentException` for empty `speeds`. `labels.playbackSpeed` |
| `VideoSeekBar(positionMs, durationMs, onSeek, bufferedPositionMs, enabled, onScrub, height, trackHeight, thumbRadius, contentDescription)` | no `labels` — `contentDescription` | See below |
| `VideoTimeText(timeMs, referenceDurationMs = timeMs, textStyle)` | no `labels` | `formatVideoTime(timeMs, referenceDurationMs)` in `colors.content` |
| `BufferingIndicator(size, strokeWidth, contentDescription)` | no `labels` | Spinning arc; indeterminate progress semantics |

### `VideoSeekBar`

- Drag: moves the handle only; `onScrub(position)` during the drag, then `onScrub(null)` and
  **one** `onSeek(position)` when the finger lifts. `onScrub(null)` without a seek when cancelled —
  by the platform, or because the bar stops being draggable mid-drag (`enabled` turns `false`,
  `durationMs` drops to `0`, as on a failure or a new source preparing) or leaves the composition.
  The handle then returns to `positionMs`; nothing stays frozen at the finger's last position.
- Tap: `onSeek(tapped position)`.
- While dragging, `positionMs` changes are ignored. After a seek, the handle stays at the target
  until `positionMs` next changes.
- Accessibility: `ProgressBarRangeInfo(fraction, 0f..1f)` and a `SetProgress` action that seeks.
- Disabled (no gestures, no `SetProgress`, `disabled()` semantics) while `durationMs <= 0` or
  `!enabled`.
- Mirrored in RTL.

## Styling types

`VideoControlsColors(content, disabledContent, scrim, seekTrack, seekBuffered, seekProgress,
seekThumb, bufferingIndicator)`, `VideoControlsDimensions(buttonSize, iconSize, centerButtonSize,
centerIconSize, seekBarHeight, seekTrackHeight, seekThumbRadius, bufferingIndicatorSize,
bufferingIndicatorStrokeWidth, contentPadding)` and `VideoControlsLabels(play, pause, replay,
seekForward, seekBackward, mute, unmute, seekBar, playbackSpeed, enterFullscreen, exitFullscreen,
buffering)` — plain `@Immutable` classes (not data classes), all properties `val`. Every label
defaults to `null`.

### `VideoControlsDefaults`

| Member | Value |
|---|---|
| `AutoHideDelayMs: Long` | `3_000` |
| `Speeds: List<Float>` | `0.5, 0.75, 1, 1.25, 1.5, 2` |
| `ControlTextStyle: TextStyle` | 12 sp, medium weight |
| `colors(…)` | White content, 38 % white disabled, 40 % black scrim, 24 % / 48 % white track / buffered, white progress, thumb and spinner |
| `dimensions(…)` | 48 dp buttons with 24 dp icons, 72 dp centre button with 44 dp icon, 32 dp seek bar with 4 dp track and 6 dp thumb, 48 dp / 4 dp spinner, 8 dp padding |
| `formatSpeed(Float): String` | Rounded to two decimals, trailing zeros dropped, `.` separator, `×` suffix: `1×`, `1.5×`, `0.75×` |

### `VideoControlsIcons`

`Play`, `Pause`, `Replay`, `SeekForward`, `SeekBackward`, `VolumeOn`, `VolumeOff`,
`EnterFullscreen`, `ExitFullscreen` — 24×24 dp `ImageVector`s drawn in black, built lazily; tint
them when drawing.

## Functions

### `formatVideoTime`

```kotlin
public fun formatVideoTime(timeMs: Long, referenceDurationMs: Long = timeMs): String
```

`m:ss`, or `h:mm:ss` when `timeMs` or `referenceDurationMs` reaches one hour. Rounds down to the
whole second; negative input formats as `0:00`. Digits and colons only.
