# kmptoolkit-video-player-compose — Testing

Testing screens that use these composables, without a device, a decoder or a video file.

## No fixture artifact of its own

This module adds no test double: what a screen test needs to fake is the **player**, and
`kmptoolkit-video-player-testing` already provides a scriptable engine to build one with the real
state machine (see [`kmptoolkit-video-player`'s testing page](../kmptoolkit-video-player/06-testing.md)).
Pass that player to `VideoPlayer(player = …)`, or provide a factory returning it:

```kotlin
@Test
fun `the workout screen shows the replay button at the end`() = runComposeUiTest {
    val engine = FakeVideoPlaybackEngine()
    val player: VideoPlayer = createVideoPlayer(engine = engine)
    setContent {
        CompositionLocalProvider(LocalVideoPlayerFactory provides VideoPlayerFactory { player }) {
            WorkoutScreen(url = "https://example.test/a.mp4")
        }
    }
    // Drive the engine to completion, then assert on your screen.
}
```

A player over a fake engine has no platform picture, so the surface draws nothing — the rest of
the UI (controls, progress callbacks, pause in background) behaves exactly as in production.
(`FakeVideoPlaybackEngine` is the testing artifact's name for its engine; check that page for the
exact API.)

## Finding the controls

The controls have no text, so find them by accessibility label — which also checks you passed
labels at all:

```kotlin
setContent { VideoPlayer(player) { DefaultVideoControls(labels = MyLabels) } }
onNodeWithContentDescription(MyLabels.play!!).performClick()
```

The seek bar exposes `ProgressBarRangeInfo` over `0f..1f` and a `SetProgress` action:

```kotlin
onNodeWithContentDescription("Seek").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
```

## Auto-hide timing

The controls hide `controlsAutoHideDelayMs` after the last interaction while playing, counted on
the composition's clock. In a test, take the clock over:

```kotlin
mainClock.autoAdvance = false
setContent { VideoPlayer(player, controlsAutoHideDelayMs = 3_000L) }
mainClock.advanceTimeBy(3_000L + 1_000L) // the delay, then the fade-out
onNodeWithContentDescription("Pause").assertDoesNotExist()
```

In a test that is not about timing, pass a long delay so nothing hides mid-assertion.

## How this module tests itself

- **Pure functions** (`commonTest`, all targets): time and speed formatting, the next speed, the
  scale-mode geometry, the seek-bar hit mapping (including RTL).
- **UI suites** (`src/uiTest`, compiled into both `androidUnitTest` with Robolectric and `jvmTest`
  with Skia). The player is the library's own — `createVideoPlayer(FakeVideoPlaybackEngine(…))`
  from `kmptoolkit-video-player-testing`, a test dependency only — with its position poll parked on
  a test dispatcher, so nothing races the test. Covered: every control reaching the engine; the
  centre button deciding on the player's current state, not on the last composed one; transport
  disabled with nothing loaded and after a failure; show, hide, auto-hide and `setInteracting`
  holding it off; the seek bar's single seek on release, ignoring position updates while dragged,
  holding the target after, the scrub time in the position label, and a drag that is cancelled —
  by the platform, by the bar being disabled, or by the player failing under the finger — leaving
  nothing frozen and seeking nothing; custom slots; `pauseOnBackground` at `ON_STOP`, off, and not
  resuming; `rememberVideoPlayer` preparing per source, a superseded prepare cancelled and never
  starting playback (exactly one start, for the newer source), `autoPlay`, unloading on `null`,
  releasing on dispose; progress reporting only with a known duration; surface sizing per scale
  mode and keep-screen-on only while playing. A test-only seam replaces the platform surface in
  these suites; the real surfaces have their own tests below.
- **Desktop** (`jvmTest`): the frame surface draws, replaces, reallocates, fits and clears frames
  (checked on captured pixels); end to end, a real player over an engine that also renders frames
  reaches the screen through `VideoPlayerSurface` and `VideoPlayer(source = …)`, swapping players
  switches the frame source, and an anamorphic picture is drawn by its reported display size under
  `Fit` and `Crop`; a missing factory fails with a message naming the fix.
- **Android** (`androidUnitTest`): with real ExoPlayers from `createVideoPlayer(context)`, the
  `SurfaceView` is present and sized, keeps the screen on only while playing, swapping players
  moves the surface from one ExoPlayer to the other, leaving the composition detaches, and
  `VideoPlayer(source = …)` leaving the composition releases its player without touching a
  released ExoPlayer; the merged manifest's permissions are pinned.
- **iOS** (`iosTest`): the idle-timer reference counting — two surfaces, the app's own value
  restored by the last one — as pure logic.

Not covered, because it needs a real decoder and display: pictures actually coming out of Media3
and `AVPlayerLayer`. Check those on a device.
