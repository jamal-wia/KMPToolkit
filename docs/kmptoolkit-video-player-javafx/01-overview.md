# kmptoolkit-video-player-javafx — Overview

A desktop (JVM) playback engine for [`kmptoolkit-video-player`](../kmptoolkit-video-player/01-overview.md),
built on JavaFX Media. One call gives your desktop build the same `VideoPlayer` your Android and iOS
builds already use:

```kotlin
// jvmMain
val player: VideoPlayer = createJavaFxVideoPlayer()
```

Everything after that — `prepare`, `play`, the state flow, the release contract — is the core
module's shared code, unchanged. The pictures are rendered into memory and drawn by
`kmptoolkit-video-player-compose`, so no JavaFX window or node ever reaches your UI.

## Why a separate artifact

The core module ships the desktop *types* (so a video screen in shared UI compiles for desktop) but
no desktop *engine*: every engine worth using brings a licence and a native runtime with it, and
those should reach only an app that chose them. The suite offers two, each in its own artifact:

| | `kmptoolkit-video-player-javafx` (this) | `kmptoolkit-video-player-vlcj` |
|---|---|---|
| Playback library | OpenJFX `javafx-media` | VLCJ over libVLC |
| Licence reaching your app | GPL-2.0 **with the Classpath Exception** — linking does not make your app GPL | GPL-3.0 (VLCJ) |
| What the user's machine needs | nothing: the OpenJFX jars you ship carry their native code | VLC installed |
| Formats | narrow — see below | practically everything VLC plays |
| Custom HTTP headers | **no** — rejected with a typed error | see that module's docs |
| CPU cost of getting pixels | a render + a full-frame copy per frame — see [`05-platform-notes.md`](05-platform-notes.md#cpu-cost) | decoded straight into memory |

Pick this engine when you ship MP4 (H.264/AAC) or HLS and want nothing installed on the user's
machine and no copyleft beyond the Classpath Exception. Pick VLCJ when you need broad format
support or lower CPU at high resolutions, and can ask users to install VLC.

## What it plays

JavaFX Media supports a deliberately small set of formats: MP4 / M4V with H.264 or H.265 (HEVC)
video and AAC audio, HLS (`.m3u8`) with the same codecs, and audio-only MP3, AAC, WAV and AIFF. No
VP8/VP9, AV1, MKV, WebM or MOV-only codecs. Codec availability also depends on the OS — on Linux,
H.264, HEVC and AAC are decoded by the system's FFmpeg libraries (`libavcodec`). Details and what an
unsupported source does are in [`05-platform-notes.md`](05-platform-notes.md#formats).

## What this is **not**

- **Not a JavaFX UI component.** There is no `MediaView` to put in a scene; the engine owns an
  off-screen one. If your desktop app is a JavaFX app and you want a native `MediaView`, use JavaFX
  directly — this artifact exists for Compose Desktop (or any UI that draws pixels).
- **Not a way to add headers to a stream.** JavaFX Media has no API for request headers. A
  `VideoSource.Remote` with headers fails with `JavaFxVideoPlayerException.HeadersNotSupported` rather
  than playing without them — silently dropping an `Authorization` header would produce an opaque
  HTTP failure later. Use a signed URL.
- **Not a bundler of OpenJFX.** OpenJFX is a `compileOnly` dependency. The runtime jars are
  OS-specific, and only your app knows which operating systems it ships to, so your app adds them —
  see [`02-getting-started.md`](02-getting-started.md).
- **Not a frame-exact renderer.** Frames are copied at most 30 times per second, sampled from what
  JavaFX has displayed; a 60 fps source is shown at 30 fps.
- **Not Android or iOS.** This artifact publishes `jvm` only; the mobile engines are built into the
  core module.

## Where to go next

- [`02-getting-started.md`](02-getting-started.md) — dependency, OpenJFX runtime, first video.
- [`03-guide.md`](03-guide.md) — choosing an engine at runtime, errors, lifecycle.
- [`04-api-reference.md`](04-api-reference.md) — every public symbol.
- [`05-platform-notes.md`](05-platform-notes.md) — licence, formats per OS, CPU cost, threading.
- [`06-testing.md`](06-testing.md) — testing code that uses this engine.
