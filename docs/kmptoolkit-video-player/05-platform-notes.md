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

*To be completed by the iOS engine (`AVPlayer`).*

## Desktop

*To be completed by the desktop engines (`kmptoolkit-video-player-vlcj`, `kmptoolkit-video-player-javafx`).*
