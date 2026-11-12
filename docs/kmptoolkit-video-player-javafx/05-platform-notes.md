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

- **JDK 25 or newer.** OpenJFX 27 is compiled with `--release 25`; the suite's own `jvm` artifacts
  are compiled for an older Java level, but the OpenJFX runtime they need is not.
- **OpenJFX 27 or newer**, added by the app per OS — see
  [`02-getting-started.md`](02-getting-started.md#2-add-the-openjfx-runtime). Classifiers published
  on Maven Central: `mac`, `mac-aarch64`, `linux`, `linux-aarch64`, `win`.
- **A display.** The JavaFX toolkit needs a graphics environment even though the engine never opens
  a window. On a headless Linux server `Platform.startup` fails with *Unable to open DISPLAY*, which
  the engine reports as `RuntimeUnavailable`.
- JavaFX on the classpath (rather than the module path) logs one warning at startup,
  *"Unsupported JavaFX configuration: classes were loaded from 'unnamed module'"*. It is harmless for
  media playback and is what every Compose Desktop app sees.

## Formats

From the [OpenJFX 27 media documentation](https://openjfx.io/javadoc/27/javafx.media/javafx/scene/media/package-summary.html):

| Container | Video | Audio |
|---|---|---|
| MP4 / M4V (`.mp4`, `.m4v`) | H.264/AVC, H.265/HEVC | AAC |
| HLS (`.m3u8`: MPEG-2 TS or fMP4 segments) | H.264/AVC, H.265/HEVC (fMP4) | AAC, MP3 |
| MP3, WAV, AIFF, M4A | — (audio only) | MP3, PCM, AAC |

Protocols: `file:`, `http:`, `https:`, `jar:` (which is how a classpath `Asset` inside a jar plays). A class loader that does not percent-encode its
URLs (`File.toURL()`, plugin loaders) is repaired for `file:` and `jar:file:` alike: the jar's own URI is
encoded and the entry is encoded as a path. A name with a character outside the Basic Multilingual Plane (an emoji)
is repaired the same way: the JDK's class loader encodes it one UTF-16 surrogate at a time, which is not valid UTF-8 and
which JavaFX rejects, so the pairs are joined and the URI is encoded again as UTF-8.

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
renders it with `Node.snapshot` into a reused `WritableImage` and copies the pixels into a new
`IntArray`. That is one scene-graph render, one GPU→CPU readback and one full-frame copy per
displayed frame, on the JavaFX application thread — on top of the decode JavaFX does anyway.

The pulse runs only while there is a picture to copy: it stops for audio-only sources (no picture,
so no per-frame cost at all) and once a paused picture has settled, and starts again on `start()`, a
seek, or a picture that appears later.

The array is new for every frame on purpose: the consumer holds the latest frame and draws it on its
own schedule, so an array handed back to the engine could be overwritten mid-draw and tear. The cost
is one short-lived allocation per displayed frame (`width × height × 4` bytes — about 3.7 MB at 720p,
8.3 MB at 1080p), which the JVM's garbage collector reclaims cheaply.

Measured on an Apple M1 Pro (10 cores), JDK 21, OpenJFX 21.0.12 (before the move to OpenJFX 27; not
re-measured since), H.264 30 fps test clips, process
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
  `Platform.startup` if nothing has started JavaFX yet, and tolerates an app that already did. That
  can take up to 10 s, so `prepare` does it — and resolves the source against the disk — on
  `Dispatchers.IO`, never on the thread that called it; `isJavaFxMediaAvailable()` blocks its caller, except on the JavaFX application thread, where an
  already running toolkit is confirmed at once.
  Only
  when the engine itself started the toolkit does it call `Platform.setImplicitExit(false)` — it
  never opens a window, and without that JavaFX could shut down when a window of the app's closes.
  An app that runs its own JavaFX windows keeps its own exit policy.
  Callers that were waiting meanwhile each try again in turn, so while the thread stays
  unresponsive, N concurrent first calls can take up to N x 10 s; the bound is per attempt, not per
  caller.
- **The toolkit stays up.** `release()` frees the player, not the toolkit; it is process-wide and
  shared with the rest of the app. If the app calls `Platform.exit()` (or JavaFX exits on its own
  when the app's last JavaFX window closes and implicit exit is on), players stop working and
  further transport calls are ignored; a `prepare` made after that fails with
  `RuntimeUnavailable` instead of waiting for the ready timeout. An explicit `Platform.exit()` is
  seen at once. An implicit exit and a dead JavaFX thread give OpenJFX's own API no signal, so they
  are seen through the thread (where it ends with the toolkit) or through a heartbeat block that the
  JavaFX thread has not run for 2 s. Silence counts only while an implicit exit is possible at all
  (implicit exit on; never when the engine started the toolkit, since it switches implicit exit
  off), and never while the JavaFX thread is running the engine's own work, such as building a
  player on a cold start. A thread kept busy for 2 s by the app's own work while implicit exit is on
  is still taken for an exited one until it answers: that load fails with `RuntimeUnavailable`,
  which is not remembered, and the next load works. The first call or load after such an exit therefore takes up to
  2 s to fail, later ones fail at once, and a thread that was only stalled and then recovers counts
  as running again. A load in flight when the toolkit ends fails within about 2 s as well. Where
  JavaFX is hosted on a thread that outlives it (inside Swing or SWT), the heartbeat, with implicit exit on, is the only
  signal.
- **Where work happens.** Every `MediaPlayer` call runs on the JavaFX application thread, in the
  order the calls were made from whichever threads made them (a call made on that thread runs at
  once only when nothing queued from another thread is still waiting to run); the position, duration and buffered position the core module polls are cached there, so polling never
  blocks. Opening a source (`new Media(uri)`, which may block on the network) runs on
  `Dispatchers.IO`. A seek still queued behind a newer seek is skipped, because the newer one
  decides the position.
- **Where events come from.** State callbacks and frames are produced on the JavaFX application
  thread; the core module's flows make that invisible to collectors.

## Behaviour notes

- **Start-up latency.** On macOS, JavaFX takes up to about a second after `play()` before the
  position starts moving; the position simply stays put briefly.
- **Seeking** snaps to the nearest key frame JavaFX chooses and may land a little before the
  requested position. The position reports the requested one as soon as `seekTo` returns and keeps
  it until the seek has reached the JavaFX application thread: a playhead reading JavaFX took before
  the seek, but delivered after it because that thread was busy, never puts the old position back.
  JavaFX then seeks asynchronously, so the one reading after that — about 100 ms later — can still
  show the old playhead before the new one arrives.
- **Looping** (`RepeatMode.One`) uses JavaFX's own `cycleCount = INDEFINITE`, so JavaFX restarts
  the source itself, and `Completed` is never reported while it is on.
- **End of media.** JavaFX leaves its own status at `PLAYING` when the last cycle ends; the engine
  pauses it there, so a seek from `Completed` stays `Paused` — in the state and in JavaFX — until
  `play()`.
- **Open files after release.** JavaFX gives a `Media` no way to be closed: the file or connection
  it opened stays open after `release()` until the garbage collector reclaims it. Under the usual
  descriptor limits this matters only for an app that loads thousands of sources between two
  collections.
- **Playback speed.** On macOS, `MediaPlayer.play()` and the start of the next cycle of a looping
  source begin at normal speed whatever rate JavaFX holds, so a speed set while paused, or one set
  before a loop wraps, would be lost. On a resume at a speed other than 1 the engine seeks the native
  player to where it already stands (unless a seek is already pending) and gives the speed right after
  `play()`: a rate change right after a plain `play()` can freeze the playhead for good (about one resume
  in four in the measurements), while one right after a native seek did not in any run. The shared player
  gives a speed it changed while paused only after `start()`, so the engine does the same for a speed that
  arrives within about 600 ms of a plain `play()` (the speed was changed while paused or ready, or right
  after a play): it seeks the native player to where it stands, issues `play()` again and gives the speed
  right after, and a speed that follows `start()` immediately replaces the one `start()` was about to give,
  whichever thread made the calls (on the FX thread, where `start()` runs at once and the next call queues,
  the pending speed is given two FX tasks later, so the speed that follows still gets in front of it and the
  native player sees one away-and-back, not three rate changes next to `play()`).
  After a loop wrap the speed is given again as one change once the playhead has wrapped and moved about
  200 ms, and no sooner than 300 ms after a seek made meanwhile (a rate change within about 100 ms of a
  native seek can freeze the playhead too); the reported time of a loop's second cycle can hold still for
  about 300 ms and then catch up.
  A rate that is not finite and positive, or a NaN volume, is ignored at the engine (the player never
  passes one).
- **Buffering** is reported from JavaFX's `STALLED` status; the buffered position comes from
  `bufferProgressTime`, which is meaningful for progressive HTTP sources (for a local file expect it
  to cover the whole duration).
- **Picture size** is the size JavaFX reports for the media (`Media.width` / `height`).
