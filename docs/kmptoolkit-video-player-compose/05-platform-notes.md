# kmptoolkit-video-player-compose — Platform notes

## Android

- **Rendering.** A `SurfaceView` inside an `AndroidView`, attached with
  `Player.setVideoSurfaceView` to the Media3 player behind the `VideoPlayer`, and detached
  (`clearVideoSurfaceView`) when the view leaves the composition or the player changes. When
  `VideoPlayer(source = …)` leaves the composition, its player and the view go in the same pass:
  Compose releases the view first (it disposes in reverse order of composition), and a detach that
  did reach an already released ExoPlayer would be a no-op — releasing drops every surface. A test
  pins this. A
  `SurfaceView` rather than a `TextureView`: the platform composes it directly, which uses less
  power and keeps protected and HDR content working. The surface is sized to the picture's frame
  by Compose and clipped to the player's bounds (the same approach as Media3's own Compose
  `ContentFrame`); `Crop` relies on the platform clipping the `SurfaceView` to its parent, which it
  does on every API level this suite supports (24+).
- **Keep screen on.** `View.keepScreenOn` on that `SurfaceView`, `true` while playing. It lasts only
  while the view is attached and visible, so it never leaks past the screen.
- **Pause in background.** `ON_STOP` is the activity's `onStop`: another activity covering it, the
  home button, the screen turning off. Multi-window, where the activity stays started, keeps
  playing.
- **Default factory.** `createVideoPlayer(LocalContext.current, config)`; only the application
  context is retained by the player.
- **Permissions.** This module declares none. Through `kmptoolkit-video-player`, Media3 ExoPlayer
  merges two install-time permissions into your app's manifest:

  | Permission | Why Media3 declares it |
  |---|---|
  | `android.permission.ACCESS_NETWORK_STATE` | Detects the network type to tune adaptive streaming |
  | `android.permission.WAKE_LOCK` | For its optional wake mode (keeping the CPU on during playback) |

  Neither triggers a runtime prompt. They are pinned by a test, so a Media3 upgrade that asks for
  more fails the build here first. **`android.permission.INTERNET` is not declared** — add it to
  your app's manifest if you stream `VideoSource.Remote`.
- **Configuration changes.** A player created by `VideoPlayer(source = …)` / `rememberVideoPlayer`
  is released and recreated with the activity. Keep the player in a view model to survive them (see
  [`03-guide.md`](03-guide.md#who-owns-the-player)).

## iOS

- **Rendering.** A `UIView` hosting an `AVPlayerLayer` for the `AVPlayer` behind the
  `VideoPlayer`, through `UIKitView`. The layer follows the view's bounds without implicit
  animation, and its `videoGravity` follows the scale mode (`Fit` → `resizeAspect`, `Fill` →
  `resize`, `Crop` → `resizeAspectFill`). The view does not take touches — the Compose controls
  drawn over it do — and is hidden from VoiceOver; the controls carry the accessibility.
- **Keep screen on.** `UIApplication.idleTimerDisabled`, a process-wide switch. The first playing
  surface saves the app's own value and sets it; the last one to stop restores the saved value — so
  an app that disabled the idle timer itself keeps it disabled, and two players never fight.
- **Pause in background.** Compose Multiplatform moves the lifecycle to `STOPPED` when the app
  enters the background.
- **Default factory.** `createVideoPlayer(config = config)` with the default asset bundle. For
  bundled assets under `compose-resources`, or a custom audio session policy, provide your own
  `LocalVideoPlayerFactory` calling `createVideoPlayer(…)` with those arguments.

## Desktop

- **No default engine.** The core module ships none; an app picks one and provides its factory
  through `LocalVideoPlayerFactory` (see [`03-guide.md`](03-guide.md#desktop-engine-choice)).
  Without one, `rememberVideoPlayer` throws an `IllegalStateException` that names the fix.
- **Rendering.** Desktop engines render decoded frames into memory. The surface copies each frame
  (32-bit ARGB, alpha forced opaque) into one reused native Skia bitmap on the UI thread and
  invalidates only the draw phase, so a playing video costs no recomposition. The bitmap is
  reallocated only when the frame size changes, and freed when the surface leaves the composition.
- **Picture shape.** The picture box is sized from `videoSizeFlow` — the display size the engine
  reports, pixel aspect ratio and rotation applied — and each frame is stretched over that box.
  A frame's own pixel grid is not the display shape for an anamorphic source (1440 × 1080 stored,
  shown 16:9), so it is used to place the picture only until the engine reports a size.
- **Keep screen on.** Nothing: desktop has no idle timer an app is expected to hold.
- **Pause in background.** Compose Desktop's lifecycle stops when the window is minimized.

## What is not tested automatically

Actual video output needs a device or simulator with a decoder. What the JVM can check is: on
Android (Robolectric), a real `ExoPlayer` from `createVideoPlayer(context)` attaching to, swapping
on and detaching from the `SurfaceView`; on desktop (Skia), frames going through the whole stack to
pixels. `AVPlayerLayer` rendering is not covered; the iOS idle-timer bookkeeping is, as pure logic
(a test binary has no `UIApplication` to write to). See [`06-testing.md`](06-testing.md).
