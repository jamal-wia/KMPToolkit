## Desktop — VLCJ

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

Details: [`docs/kmptoolkit-video-player-vlcj/`](../kmptoolkit-video-player-vlcj/01-overview.md),
especially its [`05-platform-notes.md`](../kmptoolkit-video-player-vlcj/05-platform-notes.md).
