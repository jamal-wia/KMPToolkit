# kmptoolkit-video-player-vlcj — Testing

## Testing code that uses this engine

Don't use this engine in your unit tests. Code that drives a `VideoPlayer` is tested with the fake
engine from `kmptoolkit-video-player-testing` — see the core module's
[`06-testing.md`](../kmptoolkit-video-player/06-testing.md). That keeps your tests independent of
whether VLC is installed on the CI machine, and of real time.

What is left for this artifact is the thin wiring in your desktop source set — the call to
`createVlcjVideoPlayer()` and your reaction to `isVlcAvailable()` being `false`. For the latter,
put the check behind a function your code takes as a parameter:

```kotlin
class DesktopVideoScreenModel(
    private val vlcAvailable: () -> Boolean = ::isVlcAvailable,
) { /* ... */ }

@Test
fun `without VLC the install hint is shown`() {
    val model = DesktopVideoScreenModel(vlcAvailable = { false })
    // assert the hint state
}
```

No `-testing` artifact exists for this module: there is nothing VLC-specific left to fake once the
player itself is faked.

If you do want an end-to-end test against real VLC, skip it where VLC is missing instead of failing:

```kotlin
@BeforeTest
fun requireVlc() {
    org.junit.Assume.assumeTrue("VLC not available", isVlcAvailable())
}
```

## This module's own tests

`./gradlew :kmptoolkit-video-player-vlcj:jvmTest` runs two groups.

**Always run — no VLC needed.** The engine talks to libvlc only through a small internal seam
(`VlcRuntime` / `VlcNativePlayer` / `VlcPlayerCallbacks` in `VlcBackend.kt`); the production side of
it (`VlcjRuntime`) is a one-to-one translation onto VLCJ with no logic, so every rule of the engine is
tested against `FakeVlcRuntime`, which keeps VLC's threading shape (events on an "event thread",
`submit` on a separate "task thread") and records each native call with its thread.

- `VlcjVideoEngineSessionTest` — the engine's session logic: parsed duration and display size;
  parse failure, timeout and a local source without tracks; cancelling a load that waits for the
  parser; one libvlc instance across loads, releases and failed loads, freed by `dispose` after its
  players on the teardown thread, and retried after a failed creation; pause ignored and seek
  remembered before the first start; the pending seek and the settings applied once output starts,
  on the task thread; settings during playback; buffering reported only once loaded and only on
  change; completion once, not while looping; a seek after the end staying paused until `start`;
  playback errors; a listener calling back into the engine from `onCompleted` or another callback
  (re-submitted, never run on the event thread); the parsed display size winning over the decoder's
  stored size, which is only a fallback; a fresh pixel array per frame; after `release` no callback,
  frame or native call; `release` returning while libvlc is still stopping a stalled input, with
  stop → release on the teardown thread; `release` from inside a callback; a new load silencing the
  old source; the temporary asset copy deleted after its player is released, and when a cancellation
  lands right after resolution or during discovery; nothing slow on the calling thread.
- `VlcjVideoPlayerFactoryTest` — the player `createVlcjVideoPlayer` builds: never throws, exposes a
  frame source, touches neither discovery nor libvlc before `prepare`; without VLC, `prepare` settles
  on `Error(VlcUnavailableException)`; a seek after completion leaves the player `Paused` with VLC not
  playing; the frame source survives `unload`.
- `VlcjVideoEngineWithoutVlcTest` — with a discovery that finds nothing, `load` fails with
  `VlcUnavailableException` without calling the listener; source errors are reported before VLC is
  looked for; transport, settings and getters before a load are no-ops; `release` is idempotent.
- `VlcMediaResolverTest`: files, classpath assets (in a directory and inside a jar, including the
  temporary copy and its deletion), remote URLs, the header mapping and every rejection — missing
  file, blank path, directory, missing asset, blank URL, unsupported header, header with a line
  break.
- `DisplaySizeTest`: sample aspect ratio (DVD 16:9, HDV) and quarter-turn orientation.
- `FrameConversionTest`: RV32 → opaque ARGB (padding byte ignored, buffer position ignored, pixel
  count honoured) and the volume mapping.

**Real VLC — skipped when `isVlcAvailable()` is false**, failed instead with `-Pvlc.required=true`.

`VlcjVideoEngineTest` plays the checked-in clip
`src/jvmTest/resources/video/clip-64x48-2s.mp4` (64×48, 2 s, H.264, video only, about 2 KB) and a
local HTTP server (`com.sun.net.httpserver`). It covers: load of a file and of an asset with duration
and picture size; a file VLC cannot demux and an HTTP 404 failing with `VlcPlaybackException`; the
`User-Agent` and `Referer` headers arriving at the server; cancelling a load against a server that
never answers, then loading again; frames of the right size, opaque, with an advancing playhead;
pause holding the playhead and a seek while paused; a seek before the first `start`; completion
reported exactly once with the playhead at the end; starting again after completion; a seek after
completion staying paused until `start`; looping with no completion and frames continuing across the
loop point; a speed set before playback and one changed during it, read back from libvlc (the clip
has no audio track, so libvlc has no volume to read back — the volume path is covered by the session
tests); release during playback — idempotent, no callback afterwards, frames cleared; load after
release; a new load silencing the previous source; a failed load leaving nothing playable.

The tests run with `--aout=dummy`, so they make no sound and need no audio device.

### Running the real-VLC tests

They need a VLC 3.x whose architecture matches the JDK Gradle runs tests with. When every
`VlcjVideoEngineTest` case shows as *skipped* in the test report, VLC was not loadable. On a machine
or CI job that has VLC installed on purpose, run with

```sh
./gradlew :kmptoolkit-video-player-vlcj:jvmTest -Pvlc.required=true
```

so a broken VLC setup fails the build instead of passing as a run of skips. On an Apple-silicon Mac
the usual reason for the skip is an Intel-only VLC in `/Applications` — replace it with the Apple
Silicon or Universal build. A VLC outside the standard locations can be pointed at with
`-Djna.library.path=...` on the test JVM (`tasks.withType<Test> { systemProperty(...) }` in a local,
uncommitted init script).

The clip was generated with:

```sh
ffmpeg -f lavfi -i testsrc=size=64x48:rate=10 -t 2 -c:v libx264 -preset veryslow -crf 40 \
  -pix_fmt yuv420p -movflags +faststart clip-64x48-2s.mp4
```
