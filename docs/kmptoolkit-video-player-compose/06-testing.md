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
  with Skia): every control reaching the player; show, hide and auto-hide; the seek bar's
  single seek on release, ignoring position updates while dragged and holding the target after;
  custom slots; `pauseOnBackground` at `ON_STOP`, off, and not resuming; `rememberVideoPlayer`
  preparing per source, cancelling a superseded prepare, `autoPlay`, unloading on `null`,
  releasing on dispose; progress reporting only with a known duration; surface sizing per scale
  mode and keep-screen-on only while playing. A test-only seam replaces the platform surface there,
  since a fake player has nothing to attach.
- **Desktop** (`jvmTest`): the frame surface draws, replaces, reallocates, fits and clears frames
  (checked on captured pixels); a missing factory fails with a message naming the fix.
- **Android** (`androidUnitTest`): the merged manifest's permissions are pinned.

Not covered, because it needs a real decoder and display: attaching Media3 to the `SurfaceView`,
`AVPlayerLayer` rendering, and the iOS idle-timer bookkeeping. Check those on a device.
