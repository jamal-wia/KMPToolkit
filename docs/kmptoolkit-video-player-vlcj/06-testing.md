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

`./gradlew :kmptoolkit-video-player-vlcj:jvmTest` runs two groups:

**Always run — no VLC needed.**

- `VlcMediaResolverTest`: files, classpath assets (in a directory and inside a jar, including the
  temporary copy and its deletion), remote URLs, the header mapping and every rejection — missing
  file, blank path, directory, missing asset, blank URL, unsupported header, header with a line
  break.
- `FrameConversionTest`: RV32 → opaque ARGB (padding byte ignored, buffer position ignored, pixel
  count honoured) and the volume mapping.
- `VlcjVideoEngineWithoutVlcTest`: with a discovery that finds nothing, `load` fails with
  `VlcUnavailableException` without calling the listener; source errors are reported before VLC is
  looked for; transport, settings and getters before a load are no-ops; `release` is idempotent
  before a load, after a failed one, and followed by another load.

**Real VLC — skipped (not failed) when `isVlcAvailable()` is false.**

`VlcjVideoEngineTest` plays the checked-in clip
`src/jvmTest/resources/video/clip-64x48-2s.mp4` (64×48, 2 s, H.264, about 2 KB) and a local HTTP
server (`com.sun.net.httpserver`). It covers: load of a file and of an asset with duration and
picture size; a file VLC cannot demux and an HTTP 404 failing with `VlcPlaybackException`; the
`User-Agent` and `Referer` headers arriving at the server; cancelling a load against a server that
never answers, then loading again; frames of the right size, opaque, with an advancing playhead;
pause holding the playhead and a seek while paused; a seek before the first `start`; completion
reported exactly once with the playhead at the end; starting again after completion; looping with
no completion and frames continuing across the loop point; speed and volume before and during
playback; release during playback — idempotent, no callback afterwards, frames cleared; load after
release; a new load silencing the previous source; a failed load leaving nothing playable.

The tests run with `--aout=dummy`, so they make no sound and need no audio device.

### Running the real-VLC tests

They need a VLC 3.x whose architecture matches the JDK Gradle runs tests with. Check the Gradle test
report: when every `VlcjVideoEngineTest` case shows as *skipped*, VLC was not loadable. On an
Apple-silicon Mac the usual reason is an Intel-only VLC in `/Applications` — replace it with the
Apple Silicon or Universal build. A VLC outside the standard locations can be pointed at with
`-Djna.library.path=...` on the test JVM (`tasks.withType<Test> { systemProperty(...) }` in a local,
uncommitted init script).

The clip was generated with:

```sh
ffmpeg -f lavfi -i testsrc=size=64x48:rate=10 -t 2 -c:v libx264 -preset veryslow -crf 40 \
  -pix_fmt yuv420p -movflags +faststart clip-64x48-2s.mp4
```
