# kmptoolkit-video-player-javafx — Platform notes

## Licence

This artifact itself is MIT, like the rest of the suite. What it links against at runtime — OpenJFX
(`javafx-base`, `javafx-graphics`, `javafx-media`) — is **GPL-2.0 with the Classpath Exception**, the
same terms as the OpenJDK class library. The exception means that linking OpenJFX into your
application does not put your application under the GPL; you still have to meet the GPL's terms for
OpenJFX itself if you redistribute it (ship its licence, offer its source — OpenJFX's source is
public). `javafx-media`'s native libraries include third-party components (a trimmed GStreamer,
LGPL), listed in OpenJFX's own legal notices.

Because OpenJFX is a `compileOnly` dependency here, neither the licence nor the jars reach an app
that does not add this artifact *and* the OpenJFX runtime itself. The core `kmptoolkit-video-player`
module has no desktop engine and no such dependency.

Not legal advice — if licensing matters to your distribution, read the licences.

## Runtime requirements

- **JDK 21 or newer.** The suite's `jvm` artifacts are compiled for Java 21.
- **OpenJFX 21 or newer**, added by the app per OS — see
  [`02-getting-started.md`](02-getting-started.md#2-add-the-openjfx-runtime). Classifiers published
  on Maven Central: `mac`, `mac-aarch64`, `linux`, `linux-aarch64`, `win`.
- **A display.** The JavaFX toolkit needs a graphics environment even though the engine never opens
  a window. On a headless Linux server `Platform.startup` fails with *Unable to open DISPLAY*, which
  the engine reports as `RuntimeUnavailable`.
- JavaFX on the classpath (rather than the module path) logs one warning at startup,
  *"Unsupported JavaFX configuration: classes were loaded from 'unnamed module'"*. It is harmless for
  media playback and is what every Compose Desktop app sees.

## Formats

From the [OpenJFX 21 media documentation](https://openjfx.io/javadoc/21/javafx.media/javafx/scene/media/package-summary.html):

| Container | Video | Audio |
|---|---|---|
| MP4 / M4V (`.mp4`, `.m4v`) | H.264/AVC, H.265/HEVC | AAC |
| HLS (`.m3u8`: MPEG-2 TS or fMP4 segments) | H.264/AVC, H.265/HEVC (fMP4) | AAC, MP3 |
| MP3, WAV, AIFF, M4A | — (audio only) | MP3, PCM, AAC |

Protocols: `file:`, `http:`, `https:`, `jar:` (which is how a classpath `Asset` inside a jar plays).

Per OS, decoding is done by the platform:

- **macOS** — AVFoundation. H.264 and AAC always; HEVC on hardware/OS versions that support it.
- **Windows** — Media Foundation. HEVC depends on the HEVC codec being installed in Windows.
- **Linux** — the system's FFmpeg libraries (`libavcodec` / `libavformat`), which the app's users
  must have installed (they are on virtually every desktop distribution, but not on minimal images).
  Without them, video fails with `MediaException(MEDIA_UNSUPPORTED)`.

What an unsupported source does:

- An unknown container or protocol — `MediaException` from `prepare`, straight away.
- A file JavaFX starts to parse but cannot decode — on macOS JavaFX may report **nothing at all**;
  the engine fails the load with `JavaFxVideoPlayerException.LoadTimedOut` after 30 s.
- For MP4 over HTTP, the `moov` atom must be at the start of the file (`-movflags +faststart`), or
  JavaFX stalls until the whole file has downloaded.

## CPU cost

JavaFX Media has no public "give me the decoded frame" callback. The engine therefore keeps an
off-screen `MediaView`, and on each JavaFX pulse (throttled to at most 30 per second while playing)
renders it with `Node.snapshot` into a reused `WritableImage` and copies the pixels into one of two
reused `IntArray`s. That is one scene-graph render, one GPU→CPU readback and one full-frame copy per
displayed frame, on the JavaFX application thread — on top of the decode JavaFX does anyway.

Measured on an Apple M1 Pro (10 cores), JDK 21, OpenJFX 21.0.12, H.264 30 fps test clips, process
CPU over 8 s of playback; "1 fps" copies one frame per second and approximates decode-only cost:

| Resolution | Copying 1 fps | Copying 30 fps | Cost of the copy |
|---|---|---|---|
| 640×360 | 11 % of a core | 24 % | ≈ 13 % of a core |
| 1280×720 | 10 % | 43 % | ≈ 33 % of a core |
| 1920×1080 | 12 % | 69 % | ≈ 57 % of a core |

These exclude the consumer's own drawing (the Compose surface turns each frame into a bitmap),
which scales with resolution too. Paused playback costs nothing: the engine copies only while
playing, and briefly after a seek.

Rule of thumb: up to 720p this engine is comfortable on any recent machine. For 1080p and above on
low-power hardware, or several players at once, prefer `kmptoolkit-video-player-vlcj`, whose
decoder writes straight into memory without a render-and-read-back step.

## Threading and the JavaFX toolkit

- **Starting the toolkit.** The first `prepare` (or `isJavaFxMediaAvailable()`) calls
  `Platform.startup` if nothing has started JavaFX yet, and tolerates an app that already did. Only
  when the engine itself started the toolkit does it call `Platform.setImplicitExit(false)` — it
  never opens a window, and without that JavaFX could shut down when a window of the app's closes.
  An app that runs its own JavaFX windows keeps its own exit policy.
- **The toolkit stays up.** `release()` frees the player, not the toolkit; it is process-wide and
  shared with the rest of the app. If the app calls `Platform.exit()`, players stop working and
  further transport calls are ignored.
- **Where work happens.** Every `MediaPlayer` call runs on the JavaFX application thread; the
  position, duration and buffered position the core module polls are cached there, so polling never
  blocks. Opening a source (`new Media(uri)`, which may block on the network) runs on
  `Dispatchers.IO`.
- **Where events come from.** State callbacks and frames are produced on the JavaFX application
  thread; the core module's flows make that invisible to collectors.

## Behaviour notes

- **Start-up latency.** On macOS, JavaFX takes up to about a second after `play()` before the
  position starts moving; the position simply stays put briefly.
- **Seeking** snaps to the nearest key frame JavaFX chooses and may land a little before the
  requested position.
- **Looping** (`RepeatMode.One`) uses JavaFX's own `cycleCount = INDEFINITE`, so JavaFX restarts
  the source itself, and `Completed` is never reported while it is on.
- **Buffering** is reported from JavaFX's `STALLED` status; the buffered position comes from
  `bufferProgressTime`, which is meaningful for progressive HTTP sources (for a local file expect it
  to cover the whole duration).
- **Picture size** is the size JavaFX reports for the media (`Media.width` / `height`).
