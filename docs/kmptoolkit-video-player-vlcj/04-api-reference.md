# kmptoolkit-video-player-vlcj — API reference

Package `io.github.jamal_wia.kmptoolkit.video.player.vlcj`. JVM only. Every symbol below is public
API and covered by the module's ABI dump (`kmptoolkit-video-player-vlcj/api/`); anything not listed
is `internal` and may change in any release. The `VideoPlayer` these functions return is documented
in the core module's [`04-api-reference.md`](../kmptoolkit-video-player/04-api-reference.md).

No VLCJ type appears in this API: VLCJ is a runtime dependency of the artifact, not a compile-time
one of your code.

---

## `createVlcjVideoPlayer`

```kotlin
public fun createVlcjVideoPlayer(
    config: VideoPlayerConfig = VideoPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
    vlcArgs: List<String> = emptyList(),
): VideoPlayer
```

Creates a `VideoPlayer` backed by VLC, in `VideoPlayerState.Idle`.

| Parameter | Contract |
|---|---|
| `config` | The shared tunables — position refresh interval, speed range. |
| `coroutineContext` | Hosts the player's position-polling coroutine only. |
| `vlcArgs` | Extra libvlc arguments for the libvlc instance this player creates (copied). Empty: VLC's defaults. |

- **Never throws, never touches VLC.** VLC is located and loaded on the first `prepare`; if that
  fails, the prepare settles on `Error(VlcUnavailableException)`.
- **Resources.** The player owns one libvlc instance (created by the first `prepare` and kept
  across `unload`, a replacing `prepare` and a cancelled one) and one native media player per loaded
  source. `unload()` and `release()` free the media player at once; the libvlc instance is freed
  once the released player is garbage-collected (see [`03-guide.md`](03-guide.md) § "Lifecycle and
  threading").
- **Rendering.** Frames are decoded to memory as 32-bit ARGB and exposed to
  `kmptoolkit-video-player-compose` through the core module's `@ToolkitInternalApi`
  `frameSourceOrNull()`, which returns non-null for players created here.
- **Threading.** As for every `VideoPlayer`: drive it from one thread, collect its flows anywhere.
  VLC's own threads never call into your code except through those flows. Nothing slow runs on the
  calling thread: locating and loading libvlc and opening the source run on `Dispatchers.IO`, and
  native teardown on a background thread of the player's own.

## `isVlcAvailable`

```kotlin
public fun isVlcAvailable(): Boolean
```

Whether libvlc can be found **and loaded** by this JVM. Never throws.

- Runs VLCJ's native discovery: the standard install locations of the OS, `VLC_PLUGIN_PATH`,
  `jna.library.path` (see [`05-platform-notes.md`](05-platform-notes.md)).
- `false` also when a libvlc is found but cannot be loaded — most often a VLC built for another CPU
  architecture than the JVM.
- A `true` result is cached for the process (libvlc stays loaded); a `false` one is not, so a VLC
  installed while the app runs is found by the next call.
- Blocking. Instant after the first success; a call that has to search may take tens of
  milliseconds, so make it once per screen or at startup, off any latency-critical path.

## `VlcUnavailableException`

```kotlin
public class VlcUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException
```

The cause in `VideoPlayerState.Error` when VLC cannot be used: libvlc not found, built for another
architecture, or failing to initialise (for example with a `vlcArgs` entry libvlc rejects). `cause`
carries the underlying `LinkageError` or VLCJ exception when there is one. Its message is for logs,
not for users.

## `VlcPlaybackException`

```kotlin
public class VlcPlaybackException(message: String) : IllegalStateException
```

The cause in `VideoPlayerState.Error` when VLC reports it cannot open or play a source: parsing
failed (unreachable host, HTTP error, unknown format), a local source has no playable track, or VLC
raised its error event during playback. VLC gives no further detail — run with
`vlcArgs = listOf("-vvv")` and read VLC's log to find out why.

## Failures reported with standard types

Detected before VLC is involved, so they carry a precise type:

| Type | When |
|---|---|
| `java.io.FileNotFoundException` | `VideoSource.File` missing, a directory, or unreadable; `VideoSource.Asset` not on the classpath; either path blank. |
| `IllegalArgumentException` | `VideoSource.Remote` with a blank URL, a header other than `User-Agent`/`Referer`, or a header value containing a line break. |

## Engine behaviour behind the shared API

For reference when reading the core module's contract — details in [`03-guide.md`](03-guide.md):

| Shared API | Under VLC |
|---|---|
| `prepare` | Waits for libvlc's parser (local + network); no frame decoded until `play()`. |
| `setVolume(v)` | VLC volume `round(v × 100)` %, never above 100. |
| `setPlaybackSpeed` | VLC rate. |
| `RepeatMode.One` | VLCJ repeat; the input is re-opened at the end, possibly with a short gap. |
| `bufferedPositionFlow` | Duration for local sources, playhead for remote ones. |
| `videoSizeFlow` | First video track's displayed size after parsing (sample aspect ratio and rotation applied). The decoder's stored picture size is used only when parsing found no size (some network streams). |
