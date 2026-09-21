# kmptoolkit-video-player — Platform notes

What differs between platforms, and what the consuming app has to declare.

## Behavior that is identical everywhere

- The state machine, every transition, the clamping rules and the release contract — they live in
  `commonMain`, above the engine, and are tested once.
- Settings (speed, volume, mute, repeat mode) are owned by the player and pushed onto every loaded
  source, whatever the engine does with its own state between loads.
- Buffering and picture-size reports are dropped when no source is loaded, so a late platform
  callback cannot resurrect a state.
- Polling runs only while `Playing`.

## Permissions — the library declares none of its own

`kmptoolkit-video-player`'s own `AndroidManifest.xml` declares no permission. What the app needs:

| What you want to do | Android | iOS |
|---|---|---|
| Play a bundled asset or a local file | nothing | nothing |
| Stream over HTTPS | `android.permission.INTERNET` | nothing |
| Stream over cleartext `http://` | `INTERNET` + a network-security config | an `NSAppTransportSecurity` exception |

## Android

**Engine:** AndroidX Media3 ExoPlayer (`media3-exoplayer`, plus `media3-exoplayer-hls` for HLS).
`media3-exoplayer` is an `api` dependency of this module, because the Compose surface attaches to
the Media3 `Player` type.

### Permissions

`INTERNET` is a normal (install-time) permission — declare it yourself to stream:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

**Media3 itself merges two permissions** into every app that depends on it, from its own manifests:
`ACCESS_NETWORK_STATE` (`media3-common`, `media3-exoplayer`) and `WAKE_LOCK` (`media3-exoplayer`).
They are not declared by this library, but adding this library brings them in transitively. This
engine never calls `ExoPlayer.setWakeMode`, so it does not use `WAKE_LOCK`; `ACCESS_NETWORK_STATE`
lets Media3 read the network type for its initial bandwidth estimate. If your app must not declare
them, remove them in your own manifest — and check Media3's release notes for what depends on
`ACCESS_NETWORK_STATE` in the version you ship:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.WAKE_LOCK" tools:node="remove" />
</manifest>
```

### Sources

| Source | What Media3 opens |
|---|---|
| `VideoSource.Asset("videos/intro.mp4")` | `asset:///videos/intro.mp4` — `src/main/assets/videos/intro.mp4`. A leading `/` is ignored; `#`, `?` and spaces in the name are encoded. |
| `VideoSource.File(path)` | a `file://` URI of `path`. |
| `VideoSource.Remote(url, headers)` | `url` through `DefaultHttpDataSource` carrying `headers` as default request properties, so they go with every request — for HLS, the playlists and every segment. Cross-protocol redirects (`https` → `http`) are not followed. |

**HLS is chosen by the URL.** Media3's `DefaultMediaSourceFactory` picks HLS when the URL path ends in
`.m3u8` (a query string after it is fine). A playlist served from a URL without that extension is
treated as a progressive file and fails to load. DASH and SmoothStreaming are not included — their
Media3 modules are not dependencies of this library.

**Cleartext `http://`** is blocked by Android's default network-security policy and fails the load
with a `PlaybackException`. Declare the host in a network-security config if you really need it.

### Threading

ExoPlayer is confined to one *application looper*. This engine uses the **main looper**: it creates
the ExoPlayer there and marshals every call onto it — a call made on the main thread runs at once, a
call from another thread is posted, in order. Nothing blocks waiting for the main thread, so
`createVideoPlayer(context)` and every transport call are safe from any thread (still one thread per
player).

The player polls the duration, playhead and buffered position from its own coroutine context
(`Dispatchers.Default` by default), which ExoPlayer would reject. The engine answers those from a
snapshot it refreshes on the main thread on every player event and on every poll, extrapolating the
playhead between refreshes from the snapshot's clock and speed — so the polled position is current to
within one main-thread hop, not one poll interval.

### One ExoPlayer per player

The ExoPlayer instance lives as long as the `VideoPlayer`: `unload()` and every `prepare()` stop it,
clear the media item and free the decoders (`ExoPlayer.stop()` releases them), but keep the instance,
so a surface attached to it stays attached. `release()` releases the ExoPlayer itself.

### Events

| ExoPlayer | Reported as |
|---|---|
| `STATE_READY` during a load | the load returns |
| `onPlayerError` during a load | the load throws the `PlaybackException` → `VideoPlayerState.Error` |
| `onPlayerError` after a load | `onFailed` → `VideoPlayerState.Error` |
| `STATE_BUFFERING` / leaving it | `isBufferingFlow` `true` / `false` (a seek buffers briefly too) |
| `onVideoSizeChanged` | `videoSizeFlow`: width × `pixelWidthHeightRatio` by height; a zero size is `null`. Media3 applies rotation before reporting. |
| `STATE_ENDED` | `Completed`. The engine also clears `playWhenReady`, so a later seek does not resume playback on its own. Never reached with `RepeatMode.One` (`REPEAT_MODE_ONE`). |

The picture size is reported once the video has a surface to render to — attach one (the Compose
surface does) before expecting `videoSizeFlow` to fill in.

### What the engine does not do

- **Audio focus is not requested**, and `setHandleAudioBecomingNoisy` is off: ExoPlayer would pause
  itself on focus loss or on headphones being unplugged without the player above knowing, leaving
  `stateFlow` at `Playing` over a paused ExoPlayer. If you need either, handle it in the app and call
  `pause()`.
- **No wake lock** — keeping the screen on while playing is the Compose surface's job.
- **Errors are Media3's.** `PlaybackException.errorCode` distinguishes network, HTTP-status, parsing
  and decoder failures; the type is Media3's `ExoPlaybackException` in practice.

## iOS

**Engine:** `AVFoundation.AVPlayer`. One `AVPlayer` per player for its whole life; each `prepare`
puts a new `AVPlayerItem` (over an `AVURLAsset`) into it, and `release` only empties it. The Compose
surface attaches an `AVPlayerLayer` to that one `AVPlayer` once and never has to re-attach.

**Assets** resolve through `NSBundle.URLForResource(name:withExtension:subdirectory:)`, exactly as
in `kmptoolkit-audio-player`, so the extension in the path matters: `VideoSource.Asset("intro.mp4")`
looks up `intro` with extension `mp4`. The lookup tries the bundle root first, then each entry of
`assetSubdirectories` in order:

```kotlin
// Compose Multiplatform puts resources under compose-resources/
val player: VideoPlayer = createVideoPlayer(assetSubdirectories = listOf("compose-resources"))
```

The default is the main bundle and no subdirectories. Pass `assetBundle` when the video ships inside
a framework's own bundle.

**Remote sources and App Transport Security.** HTTPS needs nothing. Cleartext `http://` is blocked
by ATS, and the failure shows up as `VideoPlayerState.Error` from a failed `AVPlayerItem`, not as a
policy message. If you really must, add an `NSAppTransportSecurity` → `NSExceptionDomains` entry for
that one host to `Info.plist`; prefer fixing the URL. `NSAllowsArbitraryLoadsForMedia` also exists
and is scoped to AVFoundation, but it is still a review-time smell.

**HLS** plays natively — pass the `.m3u8` URL as a `VideoSource.Remote`. No extra dependency, no
configuration. Adaptive bitrate switching is AVFoundation's own.

**HTTP headers** (`VideoSource.Remote.headers`) are passed through the `AVURLAsset` creation option
`"AVURLAssetHTTPHeaderFieldsKey"`. Be aware that **Apple has never documented this key**: it is not
declared in any public header, is used by most iOS video players, and has worked since iOS 7, but
Apple could change it without notice. It applies to the playlist and segment requests AVFoundation
makes for that asset. If you would rather not depend on it, use a signed URL (query-string token)
and pass no headers — a source with no headers creates the asset with no options at all.

**The audio session.** With `managesAudioSession = true` (the default), loading a source sets the
shared `AVAudioSession` to `AVAudioSessionCategoryPlayback` with mode `AVAudioSessionModeMoviePlayback`
and activates it — without that, a video's sound is muted by the ringer switch. The setting is
process-wide; pass `managesAudioSession = false` when the app owns the session (for example because
it also records, or mixes with other audio). Interruptions (a call, Siri, a route change) are not
observed: the player neither pauses nor reports them. The `audio` background mode is not needed —
background playback is out of scope for this module.

**Rendering.** The player itself is headless. `kmptoolkit-video-player-compose` renders it through
an `AVPlayerLayer` bound to the `AVPlayer` returned by the `@ToolkitInternalApi`
`VideoPlayer.avPlayerOrNull()`. That accessor exists for the Compose module; if you render the player
yourself with UIKit, it is the hook to use, with the understanding that it carries no compatibility
promise. AVPlayer keeps the display awake while a video plays by default
(`preventsDisplaySleepDuringVideoPlayback`).

**Main thread.** Every AVFoundation call the engine makes happens on the main thread: `prepare` hops
there, and transport calls run inline when made on the main thread (the normal case) and are
dispatched to it otherwise. `avPlayerOrNull()` must be called on the main thread. The polled values
(position, duration, buffered position) are read from values refreshed on the main thread, so the
position-polling coroutine may run anywhere.

**How state is observed.** By polling on the main thread every 50 ms while a source is loaded — not
by key-value observing, which has no Kotlin/Native binding that is safe against observing a
deallocated object (the same choice `kmptoolkit-audio-player` makes). Each tick reads:

- `AVPlayerItem.status` — a mid-playback `Failed` becomes `VideoPlayerState.Error`;
- `AVPlayer.timeControlStatus` — `WaitingToPlayAtSpecifiedRate` is reported as buffering, except
  with the "evaluating buffering rate" reason AVPlayer passes through for a few milliseconds on every
  start, which would otherwise flash a spinner even for a local file;
- `AVPlayerItem.presentationSize` — the picture size, `null` while it is zero (not yet known, or an
  audio-only source);
- `AVPlayerItem.loadedTimeRanges` — the buffered position is the end of the loaded range the
  playhead is in.

End of playback comes from `AVPlayerItemDidPlayToEndTimeNotification` and mid-playback failure also
from `AVPlayerItemFailedToPlayToEndTimeNotification`. Loading polls `AVPlayerItem.status` every 20 ms
until `ReadyToPlay` or `Failed`, which is also what makes `prepare` cancellable: cancelling it
cancels the asset's loading and leaves the player empty.

**Repeat mode.** `RepeatMode.One` is implemented by seeking back to zero and playing again when the
item reaches its end; completion is not reported while it is on. There can be a frame-or-two gap at
the loop point — `AVPlayerLooper` would be gapless but needs an `AVQueuePlayer` and item templates,
which does not fit a player that swaps its source on every `prepare`.

**Seeking** is frame-accurate (zero tolerance before and after). That is what a seek bar and a
"watched 95 %" check want; on a long remote stream it can take slightly longer than a keyframe seek.

**Playback speed** is assigned to `AVPlayer.rate` right after `play()` and immediately while playing;
setting a speed while paused does not start playback.

**Errors.** A load failure is an `IllegalStateException` whose message carries the `NSError` domain,
code and `localizedDescription` of the failed `AVPlayerItem`; an asset missing from the bundle, a
blank file path or a URL `NSURL` refuses is an `IllegalArgumentException`.

## Desktop

The core module publishes `jvm` so shared UI code compiles for a desktop build, but it ships no
desktop engine: the app picks one of two separate artifacts, and only that artifact's licence and
runtime requirements reach it. Both render decoded pictures into memory, which
`kmptoolkit-video-player-compose` draws; the app chooses once at its root through
`LocalVideoPlayerFactory` (see `docs/kmptoolkit-video-player-compose/`).

| | `kmptoolkit-video-player-vlcj` | `kmptoolkit-video-player-javafx` |
|---|---|---|
| Licence of the engine | GPL-3.0 (VLCJ) — or a commercial VLCJ licence | GPL-2.0 + Classpath Exception (OpenJFX) |
| Runtime the user needs | VLC 3.x installed, or libvlc bundled; same CPU architecture as the JVM | OpenJFX jars for the OS, added by the app |
| Formats | whatever VLC plays | MP4 (H.264/AAC), HLS and a few others |
| Remote headers | `User-Agent` and `Referer` only; any other header fails the load | none; any header fails the load |
| CPU cost of rendering | low (direct frame callback) | higher (off-screen snapshots) |

### Desktop — VLCJ

`kmptoolkit-video-player` ships no desktop engine of its own. With
`kmptoolkit-video-player-vlcj` in the desktop source set, `createVlcjVideoPlayer()` returns a
`VideoPlayer` backed by VLC through VLCJ, rendering frames into memory for
`kmptoolkit-video-player-compose` to draw. Three things to know before choosing it:

- **Licence.** VLCJ is GPL-3.0 (or commercially licensed by its author). The artifact itself is MIT
  and nothing else in the suite depends on it, but an app that ships it takes on the GPL-3.0 unless
  it holds a commercial VLCJ licence. `kmptoolkit-video-player-javafx` is the alternative for a
  closed-source app.
- **VLC 3.x must be installed** (standard locations are found on Windows, macOS and Linux) or bundled
  with the app, and its CPU architecture must match the JVM's — an Intel VLC cannot be loaded by an
  Apple-silicon JVM. `isVlcAvailable()` checks without side effects; without VLC, `prepare` settles
  on `Error(VlcUnavailableException)`.
- **Differences from Android and iOS.** No picture before the first `play()`; only the `User-Agent`
  and `Referer` headers can be sent (any other header fails the prepare with
  `IllegalArgumentException`); `bufferedPositionFlow` is the duration for local sources and the
  playhead for remote ones, since VLC does not report a buffered position; `RepeatMode.One` re-opens
  the input at the end, so a short gap is possible; assets are classpath resources.
- **Lifecycle and threads.** The libvlc instance is created by the first `prepare` and kept across
  `unload` and source switches (freed once the released player is garbage-collected); native
  teardown runs on a background thread, so `unload` and `release` never block the UI thread on a
  stalled stream. `videoSizeFlow` is the displayed size (sample aspect ratio applied), so anamorphic
  sources keep their shape.

Details: [`docs/kmptoolkit-video-player-vlcj/`](../kmptoolkit-video-player-vlcj/01-overview.md),
especially its [`05-platform-notes.md`](../kmptoolkit-video-player-vlcj/05-platform-notes.md).

### Desktop — JavaFX

`kmptoolkit-video-player-javafx` is one of the two desktop engines; add it to your `jvmMain` and
create the player with `createJavaFxVideoPlayer()`. It plays through JavaFX Media and renders every
picture into memory, which `kmptoolkit-video-player-compose` draws — no JavaFX window or node reaches
your UI.

- **Licence:** OpenJFX is GPL-2.0 with the Classpath Exception, so linking it does not put your app
  under the GPL. It is a `compileOnly` dependency of the engine: nothing reaches an app that does not
  opt in.
- **Runtime:** JDK 21+, and the OpenJFX 21+ jars (`javafx-base`, `javafx-graphics`, `javafx-media`)
  added by the app with the classifier of each OS it ships to (`mac`, `mac-aarch64`, `linux`,
  `linux-aarch64`, `win`) — or a JDK that bundles JavaFX. Nothing needs installing on the user's
  machine, except FFmpeg's `libavcodec` on Linux. A display is required; on a headless machine the
  load fails with `JavaFxVideoPlayerException.RuntimeUnavailable`. `isJavaFxMediaAvailable()` checks
  up front.
- **Formats:** MP4/M4V and HLS with H.264 or HEVC video and AAC audio; nothing else for video.
- **Sources:** `Asset` is a classpath resource path. `Remote` with `headers` is rejected with
  `JavaFxVideoPlayerException.HeadersNotSupported` — JavaFX cannot send headers, so use a signed URL.
- **Cost:** frames are copied out of an off-screen `MediaView` at up to 30 fps — about a third of a
  core at 720p and over half a core at 1080p on an M1 Pro. Prefer the VLCJ engine for high
  resolutions on low-power machines, or when you need other formats.

Details: [`kmptoolkit-video-player-javafx/05-platform-notes.md`](../kmptoolkit-video-player-javafx/05-platform-notes.md).
