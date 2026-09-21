# kmptoolkit-video-player-compose — Guide

## Who owns the player

Two overloads of `VideoPlayer`, for two ownership models.

**The composable owns it** — `VideoPlayer(source = …)`, or `rememberVideoPlayer(source)` plus
`VideoPlayer(player = …)`. Created on first composition, released when the call leaves the
composition. Simple, and right for a screen that just shows a video. The cost: on Android a
configuration change (rotation, dark mode, a language switch) recreates the activity, so the
player is released and created again, and playback restarts from the beginning.

**Your code owns it** — create it where it should live, and pass it in:

```kotlin
class WorkoutViewModel(context: Context) : ViewModel() {
    val player: VideoPlayer = createVideoPlayer(context) // iOS: createVideoPlayer()

    fun open(url: String) {
        viewModelScope.launch { player.prepare(VideoSource.Remote(url)) }
    }

    override fun onCleared() = player.release()
}

@Composable
fun WorkoutScreen(viewModel: WorkoutViewModel) {
    VideoPlayer(player = viewModel.player, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
}
```

`VideoPlayer(player = …)` never releases a player it was given. It survives rotation because the
view model does.

## Progress: the "95 % watched" check

`VideoPlayer(source = …)` takes an `onProgress` callback:

```kotlin
VideoPlayer(
    source = VideoSource.Remote(url),
    modifier = …,
    onProgress = { positionMs, durationMs ->
        if (positionMs >= durationMs * 95 / 100) viewModel.onWatched()
    },
)
```

It is called whenever the position or the duration changes — about every
`VideoPlayerConfig.positionUpdateIntervalMs` (250 ms by default) while playing — and only while the
duration is known (positive), so a live stream never reports and you never divide by zero. With a
player you own, collect `player.playbackPositionFlow` in your view model instead.

## Sizing and scale modes

`VideoPlayerSurface` (and so `VideoPlayer`) never asks for a size of its own. It fills the bounds
its modifier gives it, and places the picture inside by `VideoScaleMode`:

| Mode | Effect when ratios differ |
|---|---|
| `Fit` (default) | Whole picture visible, bars (in `backgroundColor`, black by default) around it |
| `Fill` | Stretched to the bounds, distorted |
| `Crop` | Covers the bounds, the overflow cropped |

A dimension left unbounded — `Modifier.fillMaxWidth()` inside a vertically scrolling column — is
derived from the picture's ratio once `videoSizeFlow` reports it, and is its minimum (usually `0`)
until then. To keep a layout from jumping when the picture size arrives, give a ratio up front:
`Modifier.fillMaxWidth().aspectRatio(16f / 9f)`.

## Replace any part of the controls

### Keep the defaults, restyle them

```kotlin
VideoPlayer(player) {
    DefaultVideoControls(
        colors = VideoControlsDefaults.colors(
            seekProgress = BrandColors.Accent,
            seekThumb = BrandColors.Accent,
            scrim = Color.Black.copy(alpha = 0.25f),
        ),
        dimensions = VideoControlsDefaults.dimensions(centerButtonSize = 88.dp, centerIconSize = 56.dp),
        textStyle = MaterialTheme.typography.labelMedium,
        speeds = listOf(1f, 1.5f, 2f),
        showSeekButtons = false,
        onFullscreenClick = { navigator.openFullscreen() },
    )
}
```

`VideoControlsColors`, `VideoControlsDimensions` and `VideoControlsLabels` are plain classes: build
them with `VideoControlsDefaults.colors(…)` / `.dimensions(…)` / the labels constructor, overriding
only what you need.

The speed label defaults to `1.5×` with a `.` decimal separator. For a locale that writes `1,5×`,
pass `formatSpeed = { speed -> yourFormatter.format(speed) + "×" }`.

### Compose your own layout from the building blocks

Every piece of the default controls is public and takes plain values and callbacks, so a layout of
your own is a few lines — and the `VideoControlsScope` receiver hands you the values and actions:

```kotlin
VideoPlayer(player) {
    // A single bottom bar, always visible, nothing over the picture.
    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayPauseButton(
                isPlaying = isPlaying,
                onClick = { togglePlayPause() },
                buttonSize = 48.dp,
                iconSize = 24.dp,
                labels = labels,
            )
            VideoSeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                onSeek = { seekTo(it) },
                onScrub = { setInteracting(it != null) },
                modifier = Modifier.weight(1f),
                contentDescription = labels.seekBar,
            )
            VideoTimeText(timeMs = durationMs - positionMs)
            MuteButton(isMuted = isMuted, onClick = { toggleMute() }, labels = labels)
        }
    }
}
```

The slot is placed over the whole player, so a `Box(Modifier.fillMaxSize())` inside it covers the
picture. The building blocks also work entirely outside a player — a `VideoSeekBar` in a list item,
say.

To match the default look in a button of your own, use `VideoControlButton` with a
`VideoControlsIcons` icon or your own `ImageVector`.

### Write the controls entirely yourself

Anything goes in the slot. A Material 3 version:

```kotlin
VideoPlayer(player) {
    AnimatedVisibility(visible = controlsVisible, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            FilledIconButton(onClick = { togglePlayPause() }, modifier = Modifier.align(Alignment.Center)) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(…))
            }
            Slider(
                value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                // Seeks on every change; see VideoSeekBar for seek-on-release instead.
                onValueChange = { fraction ->
                    setInteracting(true)
                    seekTo((fraction * durationMs).toLong())
                },
                onValueChangeFinished = { setInteracting(false) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
```

What the scope gives you, all backed by snapshot state:

- **State**: `player`, `playerState`, `isPlaying`, `positionMs`, `durationMs` (`0` while unknown),
  `bufferedPositionMs`, `isBuffering`, `isMuted`, `volume`, `playbackSpeed`, `repeatMode`,
  `videoSize`.
- **Visibility**: `controlsVisible`, `showControls()`, `hideControls()`, `toggleControls()`,
  `setInteracting(Boolean)` to hold the controls open (during a drag, while a menu is open).
- **Actions**: `play()`, `pause()`, `togglePlayPause()`, `seekTo(ms)`, `seekBy(deltaMs)`,
  `replay()`, `setMuted(…)`, `toggleMute()`, `setVolume(…)`, `setPlaybackSpeed(…)`,
  `setRepeatMode(…)`. Each also restarts the auto-hide countdown.

`controlsVisible` is a hint: it turns `true` whenever playback stops (paused, completed, failed)
and `false` `controlsAutoHideDelayMs` after the last interaction while playing. A tap on the
picture toggles it. Controls that should always be visible just ignore it. `{}` shows no controls
at all, while a tap still toggles the (unused) flag.

### Drop `VideoPlayer` and lay it out yourself

`VideoPlayer(player = …)` is roughly fifteen lines over public pieces. For controls *below* the
picture:

```kotlin
@Composable
fun PlayerWithBar(player: VideoPlayer) {
    PauseOnBackgroundEffect(player)
    val controls: VideoControlsScope = rememberVideoControlsScope(player)
    Column {
        VideoPlayerSurface(player, Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black))
        with(controls) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayPauseButton(isPlaying, { togglePlayPause() }, buttonSize = 48.dp, iconSize = 24.dp)
                VideoSeekBar(positionMs, durationMs, { seekTo(it) }, Modifier.weight(1f))
            }
        }
    }
}
```

## Accessibility labels

This library ships no user-facing text in any language (see
[`docs/01-architecture.md`](../01-architecture.md)). The controls draw icons, digits and a speed
multiplier; their screen-reader names come from `VideoControlsLabels`, and every field defaults to
`null`. With `null`, TalkBack and VoiceOver announce "button" and nothing more — usable, but poor.
**Pass every label, localized.** Labels name the action a control performs now: `pause` while
playing, `play` otherwise; `mute` while sound is on, `unmute` while muted; `exitFullscreen` while
`isFullscreen`.

When a screen reader is on, the platform's recommended timeout lengthens the auto-hide delay — on
Android typically to "never" — so the controls do not vanish from under a user who navigates them
one by one.

## Keep screen on, background, lifecycle

- **Keep screen on.** While the player is `Playing`, the surface keeps the display awake
  (`keepScreenOn = false` to opt out). On iOS the app's own `idleTimerDisabled` value is saved and
  restored when the last playing surface stops or leaves.
- **Pause in background.** At `ON_STOP` of `LocalLifecycleOwner`, a playing player is paused
  (`pauseOnBackground = false` to opt out). It does **not** resume on return — resuming on its own
  is rarely wanted, and the paused state already shows the play button. To resume, observe the
  lifecycle and call `player.play()` yourself.
- **Release.** Only players the composable created are released (`VideoPlayer(source = …)`,
  `rememberVideoPlayer`). A player you pass in is yours.

## Desktop engine choice

The core module ships no desktop engine; you choose one, each in its own artifact so its licence
reaches only apps that opt in:

| Artifact | Engine | Licence | Needs |
|---|---|---|---|
| `kmptoolkit-video-player-vlcj` | VLC through VLCJ | GPL-3 | VLC installed on the machine |
| `kmptoolkit-video-player-javafx` | JavaFX Media | GPL + Classpath Exception | JavaFX runtime modules |

Provide the factory once at the root of the desktop UI (see
[`02-getting-started.md`](02-getting-started.md#4-desktop-pick-the-engine-once)). Shared UI code then
calls `VideoPlayer(source = …)` or `rememberVideoPlayer` the same way on every platform. A
`LocalVideoPlayerFactory` provided on Android or iOS replaces the platform default there too — to
pass iOS factory arguments the default does not (`assetBundle`, `assetSubdirectories`), or to use a
fake in a screenshot test.

## Configuration and factories are read once

`rememberVideoPlayer` reads its `config` and the factory on first composition only: a
`VideoPlayerConfig` built inline is a new instance every recomposition, and recreating the player
for each would restart playback constantly. To switch to a different configuration, key it:
`key(config) { VideoPlayer(source = …, config = config) }`.
