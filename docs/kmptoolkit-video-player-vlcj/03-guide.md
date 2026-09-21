# kmptoolkit-video-player-vlcj — Guide

The player you get from `createVlcjVideoPlayer()` follows the shared `VideoPlayer` contract — see
the core module's [`03-guide.md`](../kmptoolkit-video-player/03-guide.md) for states, transport,
settings and lifecycle. This page covers only what VLC adds, limits or does differently.

## Sources

### `VideoSource.File`

Played in place. A path that does not exist, is a directory, or cannot be read fails the prepare
with `java.io.FileNotFoundException` before VLC is involved; a file VLC cannot demux fails it with
`VlcPlaybackException`.

### `VideoSource.Asset` — classpath resources

On desktop an asset is a **classpath resource**: `VideoSource.Asset("video/intro.mp4")` looks up
`video/intro.mp4` (a leading `/` is ignored) through the thread's context class loader, falling back
to the library's own. Put the file under `src/desktopMain/resources/` (or `src/jvmMain/resources/`)
and it is on the classpath.

VLC can only open real files, so:

- run from a build directory (Gradle `run`, the IDE), the resource *is* a file and is opened in
  place;
- packed into a jar (a distributable), it is **copied to a temporary file** for as long as that
  source stays loaded, then deleted — on the next `prepare`, on `unload`, on `release`, and in the
  worst case at JVM exit.

For large videos in a packaged app, prefer installing them next to the app and using
`VideoSource.File` — the copy costs disk and time on every prepare.

### `VideoSource.Remote` — and what VLC can send

Anything VLC can stream plays: progressive HTTP(S), HLS, and VLC's other network protocols.
The headers map is limited by VLC itself: VLC 3 has per-media options for exactly two request
headers.

| Header | Sent as |
|---|---|
| `User-Agent` | `:http-user-agent` |
| `Referer` | `:http-referrer` |

Header names are matched case-insensitively. **Any other header fails the prepare with
`IllegalArgumentException`** naming the header — deliberately, rather than dropping it: a silently
missing `Authorization` header would surface as a confusing HTTP 401 much later. For protected
streams on desktop, use a signed URL (a token in the query string), or credentials in the URL
(`https://user:password@host/...`) for Basic authentication, which VLC's HTTP module understands.

A header value containing a line break is rejected too, so a value cannot inject another option.

## Readiness and the first frame

`prepare` returns once VLC's parser has opened the source and read its tracks: the state becomes
`Ready` with the duration, and `videoSizeFlow` carries the picture size of the first video track.
**No picture is decoded before the first `play()`** — the surface stays empty (or shows your
placeholder) in `Ready`. A seek made in `Ready` is remembered and applied as playback starts.

## Looping — `RepeatMode.One`

The end of the source is handled by VLCJ's repeat support, which starts the source over when VLC
reports its end. The player never reports `Completed` while looping. The restart re-opens the
input, so there can be a short gap at the loop point — VLC does not offer a gapless loop that can be
switched on and off for a source already loaded.

## Volume

`setVolume(1f)` is VLC's 100 % — the source's own level. VLC can amplify to 200 %, but this engine
never does: `1f` means the same on every platform, and amplification clips.

Volume and rate are re-applied each time output starts, because VLC only accepts them once its
audio output exists. A volume set while nothing plays is not lost.

## Buffering and buffered position

- `isBufferingFlow` follows VLC's cache-fill events: `true` while the cache refills during playback,
  `false` once it is full, paused or stopped.
- `bufferedPositionFlow` is less informative than on Android and iOS, because VLC reports how full
  its cache is, not how far ahead it reaches. The engine reports only what is certain: **the
  duration for a local file or asset** (all of it is there), and **the playhead for a remote
  source** (at least that much has arrived). A seek bar's secondary track therefore adds nothing for
  remote sources on desktop.

## Failures

| Situation | `VideoPlayerState.Error.cause` |
|---|---|
| VLC not installed, wrong CPU architecture, or failed to start | `VlcUnavailableException` |
| File or asset missing | `java.io.FileNotFoundException` |
| Unsupported header, blank URL | `IllegalArgumentException` |
| VLC cannot open or demux the source (404, unreachable host, not a video) | `VlcPlaybackException` |
| VLC reports an error during playback | `VlcPlaybackException` |

VLC's error event carries no detail, so `VlcPlaybackException` cannot tell "404" from "not a video".
To see why, create the player with VLC's verbose log: `createVlcjVideoPlayer(vlcArgs = listOf("-vvv"))`.

A prepare against a host that accepts the connection and never answers waits, like on the other
platforms — until you cancel it (a newer `prepare`, `unload`, `release`, or cancelling the calling
coroutine). Wrap it in `withTimeout` if your screen needs a deadline.

## VLC arguments

`vlcArgs` are passed to the libvlc instance the player creates, for example:

```kotlin
createVlcjVideoPlayer(vlcArgs = listOf("--network-caching=3000", "--no-audio"))
```

An argument libvlc refuses makes every prepare fail with `VlcUnavailableException` (libvlc could not
be initialised). Each player owns one libvlc instance, created on its first prepare and kept for the
player's life — see below.

## Lifecycle and threading

Nothing here differs from the shared contract, but it is worth knowing what happens on desktop:

- **`unload`, a replacing `prepare`, a cancelled `prepare` and `release`** each close the current
  source: its native media player, its last frame, and any temporary asset copy. Every callback from
  VLC's own threads is dropped from the moment the call starts, including callbacks already queued.
- **The libvlc instance** — the expensive part, whose creation scans VLC's plugins — is created by
  the first `prepare` and **kept** across all of the above, so switching sources or unloading never
  rebuilds it. It is freed once a released player is no longer referenced and has been
  garbage-collected; drop your reference to a released player and the instance goes with it.
- **Nothing slow runs on the calling thread**, which is usually the UI thread. Locating and loading
  libvlc and opening the source run on `Dispatchers.IO`. Stopping and freeing a native player — which
  for a stalled network stream can take as long as libvlc's network timeout — runs on a background
  thread of the player's own, after the call has returned. `unload` and `release` therefore return
  at once; the call only makes the old source unreachable before returning.
