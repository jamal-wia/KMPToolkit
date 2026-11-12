# kmptoolkit-video-player-javafx — Testing

## Testing your code

This artifact ships no test double, and needs none: what your code depends on is the core module's
`VideoPlayer`, and `kmptoolkit-video-player-testing` provides a scriptable fake of that — see
[`kmptoolkit-video-player/06-testing.md`](../kmptoolkit-video-player/06-testing.md). Keep the call
to `createJavaFxVideoPlayer()` at the edge of the app (where the desktop engine is chosen) and
inject the `VideoPlayer`; your screen logic then tests against the fake on every platform.

What is worth testing on the JVM side is the engine choice itself — for example that the app falls
back when `isJavaFxMediaAvailable()` is `false`. Put that decision behind your own
`() -> Boolean` so a test can answer it either way; do not rely on the real check in unit tests, since
its answer depends on the machine (a display, the OpenJFX jars) and is remembered for the process (a permanent failure only).

## How this module tests itself

The module's `jvmTest` runs the real engine against real JavaFX Media:

- **A checked-in clip** — `src/jvmTest/resources/red-64x48-3s.mp4`, 64×48 solid red, 3 s,
  H.264 baseline + silent AAC, about 4 KB. Solid red lets a test assert that frames carry the
  picture, not a black or transparent placeholder. It was generated with:

  ```sh
  ffmpeg -f lavfi -i "color=c=red:s=64x48:r=15:d=3" -f lavfi -i "anullsrc=r=22050:cl=mono" -t 3 \
    -c:v libx264 -profile:v baseline -pix_fmt yuv420p -crf 35 -g 15 \
    -c:a aac -b:a 16k -movflags +faststart -shortest red-64x48-3s.mp4
  ```

- **Skipped, not failed, where JavaFX cannot run** — unless asked not to. Every real-engine test
  calls `assumeJavaFxMedia()`, so on a headless CI machine they are reported as skipped. Pass
  `-Pjavafx.required=true` and a machine where JavaFX cannot run fails them instead: use it wherever
  the real-engine tests are meant to run, so they cannot all be skipped without anyone noticing.
- **Always run, JavaFX or not:** header rejection, missing asset/file, blank input, an unavailable
  runtime (through an internal seam), an asset whose class loader returns a `file:` or `jar:file:` URL that is not
  percent-encoded resolving to a valid URI of that file (and, with real JavaFX, a clip played out of a jar in such a folder), calls before any load; that `prepare` starts the toolkit off
  the thread that called it (a runtime seam that blocks); and, in a class loader that cannot see
  OpenJFX at all, that `createJavaFxVideoPlayer()` creates a player without touching JavaFX, that
  `frameSourceOrNull()` is non-null, and that `prepare` settles on
  `Error(JavaFxVideoPlayerException.RuntimeUnavailable)` — twice, the failure being remembered.
- **Covered on the real engine:** loading from classpath and from a file, duration and picture
  size, a non-video file and an unreachable URL failing (and leaving nothing behind), frames of the
  right size and content, a first frame without playing, play/pause/seek, the position holding a
  seek's target against playhead readings JavaFX queued before the seek, completion once, restart
  after completion, a seek after completion staying paused (in JavaFX's own status too) and `start`
  resuming from there, looping without completion, settings given before load and changed after it
  read back from JavaFX (volume, rate, cycle count) and taking effect (double speed measured
  against the wall clock, also after a pause), release idempotency, no listener call or frame after
  release, load after release, replacing a source, cancelling a load mid-way, release during a
  load, and every frame owning its pixel array. Through the whole `VideoPlayer`: the public factory,
  the frame source staying the engine across sources, and `Completed` → seek → `Paused` → `play`.
- **Playback speed across a loop and a resume** — `JavaFxLoopSpeedR5AdversarialTest` (a speed of 4
  holds in the second cycle of a looping source) and `JavaFxResumeAtSpeedR5AdversarialTest` (a pause
  and start at a speed other than 1 never freezes the playhead, six fresh engines each; at 2 the speed
  is measured once the resume has settled; D-16 moved that window). The freeze is a race, so a run of 30
  resumes each at 2 and 4 was also made when the fix landed (0 of 60; the old code froze 7 of 30).
- **Freeze after a resume** — `JavaFxResumeSettlesAtSpeedR5FixerTest`: after a pause and a start at 2 and at
  4 (six fresh engines each, or `KMPTOOLKIT_FREEZE_TRIALS` for a longer run) the playhead passes where it
  paused plus 1200 ms within 3 s, so a playhead that freezes after the speed is given fails it.
- **Unreadable file** — `SourceUrisUnreadableFileR5Test`: a `VideoSource.File` the process cannot read
  fails with `FileNotFoundException` before JavaFX is involved (JavaFX itself took 4 s to fail it).
- **Reading JavaFX back.** Volume, rate and status have no getter on the engine contract; an
  internal `inspectPlayback()` reads them from the `MediaPlayer` on the FX thread for the tests.

Run it with:

```sh
./gradlew :kmptoolkit-video-player-javafx:jvmTest -Pjavafx.required=true
```

The suite takes about 40 s, most of it real playback time. The CI workflow does not run `jvmTest`;
run it locally on a machine with a display before changing the engine.
