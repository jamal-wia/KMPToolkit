# kmptoolkit-video-player-javafx — Guide

Everything about *driving* the player — states, seeking, repeat, volume, release — is the core
module's contract and is documented in
[`kmptoolkit-video-player/03-guide.md`](../kmptoolkit-video-player/03-guide.md). This page covers
only what is specific to the JavaFX engine.

## Choosing an engine at runtime

`isJavaFxMediaAvailable()` answers whether JavaFX Media can run here: the OpenJFX classes are on the
classpath and the JavaFX toolkit is running or could be started. Use it to pick an engine once, at
the root of the app:

```kotlin
// jvmMain
fun createDesktopVideoPlayer(): VideoPlayer? =
    if (isJavaFxMediaAvailable()) createJavaFxVideoPlayer() else null // or another engine
```

The first call starts the JavaFX toolkit when nothing has yet (see
[threading](05-platform-notes.md#threading-and-the-javafx-toolkit)) and the answer — including a
failure — is remembered for the life of the process, so later calls are cheap. `true` does not
promise that every source decodes: formats are OS-dependent (see
[`05-platform-notes.md`](05-platform-notes.md#formats)).

## Sources on desktop

| Source | Resolved as | Missing / malformed |
|---|---|---|
| `VideoSource.Asset(path)` | a **classpath resource** (`src/jvmMain/resources/…`), leading `/` optional; works from a directory and from inside a jar | `FileNotFoundException` |
| `VideoSource.File(path)` | the file at that absolute path | `FileNotFoundException` (also for a directory) |
| `VideoSource.Remote(url)` | `http://`, `https://`, or an HLS playlist — passed to JavaFX as is | `MediaException` from JavaFX |
| `VideoSource.Remote(url, headers)` with any header | — | `JavaFxVideoPlayerException.HeadersNotSupported` |

A blank path or URL is an `IllegalArgumentException`. All of these arrive the core module's usual
way — as `VideoPlayerState.Error(cause)` after `prepare`, never as a crash.

### Protected streams

JavaFX Media cannot send request headers, so the engine refuses a `Remote` that has any rather than
dropping them: a stream that needs `Authorization` would otherwise fail later with an opaque
`MediaException`, or play whatever the server serves anonymously. The fix is on the URL side — a
pre-signed URL (a token in the query string, as CDNs and object stores issue them) needs no header:

```kotlin
val url: String = api.signedVideoUrl(videoId) // your backend
player.prepare(VideoSource.Remote(url))        // no headers
```

## Errors you can see

```kotlin
when (val state = player.stateFlow.value) {
    is VideoPlayerState.Error -> when (val cause = state.cause) {
        is JavaFxVideoPlayerException.RuntimeUnavailable -> { /* no OpenJFX / no display: fall back */ }
        is JavaFxVideoPlayerException.HeadersNotSupported -> { /* use a signed URL */ }
        is JavaFxVideoPlayerException.LoadTimedOut -> { /* JavaFX never answered: treat as unplayable */ }
        is java.io.FileNotFoundException -> { /* asset or file missing */ }
        is javafx.scene.media.MediaException -> { /* JavaFX's own error; cause.type says which */ }
        else -> { /* anything else the platform threw */ }
    }
    else -> Unit
}
```

- **`LoadTimedOut`** exists because JavaFX sometimes reports *nothing*: on macOS a file it cannot
  parse leaves the player in `UNKNOWN` forever, with neither `READY` nor an error. The engine waits
  30 seconds for either and then fails the load, so `prepare` never hangs on a bad file.
- **`MediaException.type`** distinguishes, among others, `MEDIA_INACCESSIBLE` (connection refused,
  404), `MEDIA_UNSUPPORTED` (a format JavaFX does not decode) and `MEDIA_CORRUPTED`. A failure during
  playback (a network drop mid-stream) arrives the same way, as `Error(MediaException)`.

## Lifecycle

The engine follows the core contract: `release()` is idempotent and final for the listener — no
state change, buffering flag or frame arrives after it returns, even one JavaFX had already queued —
and a player that was `unload()`ed can `prepare` again. Unloading frees the native JavaFX player;
that is all there is to free, since JavaFX binds each `MediaPlayer` to a single `Media` and the next
source needs a new one anyway — nothing expensive is rebuilt per source. Neither `unload()` nor
`release()` stops the JavaFX toolkit, the only costly start-up, which happens once per process and
stays running for the next player (and for anything else in the app that uses JavaFX).

## Frames and the picture

The engine renders into memory: while playing it copies the picture at most **30 times per second**,
and while paused it copies only after something changed it (the first picture after `prepare`, a
seek). A paused player costs no copying at all. The frame handed to the Compose surface is 32-bit
ARGB at the video's own resolution, in an array of its own that the engine never writes again, so
the surface can draw it whenever it gets to it; the surface scales it (Fit / Fill / Crop).

Two consequences worth knowing:

- A 60 fps source is shown at 30 fps.
- The copy is a real CPU cost that grows with resolution — see
  [`05-platform-notes.md`](05-platform-notes.md#cpu-cost) for measurements, and consider the VLCJ
  engine for 1080p and above on low-power machines.
