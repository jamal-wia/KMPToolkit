## Desktop — JavaFX

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
