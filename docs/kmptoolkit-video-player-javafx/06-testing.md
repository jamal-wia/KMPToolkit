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

The playback logic of the engine (`FxPlayback`: the order of native calls, the waits that keep the macOS decoder from
freezing, the loop point, the end of the clip) is tested in four tiers, plus an opt-in set of probes. The tiers differ in
what they prove and in what can make them flaky, which is why they run in different Gradle tasks.

| Tier | What it is | Where | Task | Runs |
|---|---|---|---|---|
| F (model) | The real `FxPlayback` and the engine over a scripted native player on a virtual clock: no media, no sleep, no real thread, every random choice from a printed seed. Asserts the contract exactly (the instants of the native calls, the speed that reaches the decoder, no frozen playhead, no violated fragile window), including seeded fuzzers and exhaustive short interleavings. | `src/jvmTest/.../javafx/model/` | `jvmTest` | on every run of the gate; seconds |
| C (conformance) | One test per promise the engine makes about the real `MediaPlayer` (`JavaFxNativePlayerConformanceTest`, C1 to C13) and `JavaFxReferenceModelDifferentialR10AdversarialTest`, seeded public calls against a reference model on real media. Keeps the scripted native player honest. | `src/jvmTest/.../javafx/` | `jvmTest` | on every run of the gate |
| K (real-media gate, kept) | Everything else that needs a real toolkit, real threads or real disposal: lifecycle, toolkit start and exit, source resolution, odd paths, jar and temp files, hostile media, huge pictures, leaks, storms in child JVMs, the release at the loop point, every frame owning its array, and the cases of `JavaFxVideoEngineTest`. Also the real-media sequencing classes that have no complete deterministic twin yet. | `src/jvmTest/.../javafx/` | `jvmTest` | on every run of the gate; minutes |
| R (real-media soak) | The real-media sequencing classes whose contract an F test asserts exactly (speed after a seek, a resume, a loop wrap, calls from the FX thread, random operation sequences, a seek at the end of the clip, a stale playhead read under load). They measure with wall-clock sleeps on a real decoder, so their verdict depends on the machine; a red there is checked against the F twin first, never rerun until green. | `src/jvmTest/.../javafx/` | `jvmRealMediaTest` | before a release, after a change to `FxPlayback`, `JavaFxNativePlayer`, `FxThread` or `FxQueue`, and on an OpenJFX, macOS or JDK upgrade |
| H (hazard probes) | Statistical probes (40 rounds each) of the native player's fragile windows after a play, a seek, a loop wrap and an idle rate write. They test the operating system against the assumptions of the F tier, not this code; they skip unless asked. | `JavaFxNativeHazardCharacterizationTest` | `jvmRealMediaTest` with `-Pkmptoolkit.test.hazards=true` | when the F tier's model parameters change, and on an OpenJFX or macOS upgrade |

The two lists that split the module are explicit class names in `kmptoolkit-video-player-javafx/build.gradle.kts`
(`realMediaSoakClasses` and `hazardProbeClasses`), not a pattern: `jvmTest` excludes exactly those classes, so a new test
class lands in the gate until someone writes its name into the soak list, and `jvmRealMediaTest` runs exactly those
classes. Both tasks fail before they start when a listed name has no compiled class, so a renamed class cannot drop out
of a tier unnoticed. `jvmRealMediaTest` uses one fork, shares the classpath and the system properties of `jvmTest`, and is
not part of `check`.

A class moves from the gate to the soak only when every scenario of it has an F twin, the twin's family mutation was
red three times out of three on the production code (and green three times once restored), and the class has no
unexplained failure rate when run alone.

The real-media classes (tiers C, K and R) run the real engine against real JavaFX Media:

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
- **Playback speed across a loop and a resume (tier R)** — `JavaFxLoopSpeedR5AdversarialTest` (a speed of 4
  holds in the second cycle of a looping source) and `JavaFxResumeAtSpeedR5AdversarialTest` (a pause
  and start at a speed other than 1 never freezes the playhead, six fresh engines each; at 2 the speed
  is measured once the resume has settled; D-16 moved that window). The freeze is a race, so a run of 30
  resumes each at 2 and 4 was also made when the fix landed (0 of 60; the old code froze 7 of 30).
- **Speed of a short looping clip (tier F)** — `ShortLoopSpeedD2Boss1ModelTest` pins the limit stated in
  `05-platform-notes.md`: a 1.2 s loop at a speed of 2 reaches 2 in every cycle after the first, and a 300 ms loop plays
  on at normal speed with no fragile window violated, no freeze, and the speed held in exactly one holder.
- **Interrupted first start** — `InterruptedStartD2Boss1Test`: an interrupted first `isJavaFxMediaAvailable()` is not
  remembered, keeps the caller's interrupt status, and a retried first start still keeps implicit exit off for a toolkit the
  engine started.
- **Resume at a loop wrap (tier F, tier R)** — `ResumeAtWrapD2Boss1B6ModelTest`: a looping clip at a speed other than 1
  paused where its loop restarted and started again (0 to 450 ms after the pause in the first test) resumes with no native seek
  of its own and gets its speed after about 200 ms of movement; started 600 ms after the wrap, with the player still paused
  there, it takes the same settle path (until 5.0.0 it took the seek path, which held the real playhead; the state flag `atLoopRestart` replaced the
  500 ms window). `LoopRestartStateD2Boss2FixModelTest` pins the state: a start 0.55 s, 1.5 s and 3 s after a pause at the wrap
  takes the settle path (no seek, one play, the speed after 200 ms of movement, no fragile window violated); a caller's
  `seekTo(0)` after a wrap clears the state, so the next resume near the start takes the seek path; the first play and a
  replay take the seek path too. `FreshSpeedAfterWrapBoss2W1ModelTest` pins that a speed given right after a plain resume at a
  loop restart is given without a native seek. `AroundTheWrapD2Boss2ModelTest` pins the orderings around a wrap (a seek, a
  speed, looping off, a second resume, a release between the wrap and the next play).
  `JavaFxResumeAtWrapD2Boss1B6RealMediaTest` plays the same on the real decoder, 60 trials, at most 1 held, with no pause, with a
  pause of 0.6 s and with a pause of 3 s between the pause at the wrap and the start (the old seek path held in 6 to 11 of 60, and
  at the 500 ms window's edge in 5 of 60 at 0.6 s and 5 of 30 at 3 s; each of the three tests takes about 3 to 5 minutes);
  knobs `-Pkmptoolkit.test.b6.b6Trials` and `-Pkmptoolkit.test.b6.b6MaxHeld`.
- **Repeat mode switched at the end of a clip (tier F, tier K, tier C)** — `CycleDecisionD2Boss3FixModelTest`: JavaFX decides
  loop-or-end in one FX task and runs the engine's end handler in a later one, and the model delivers the same two steps
  (`onCycleDecision`, then `onEndOfMedia`). `RepeatMode.One` switched on between them gives `Completed` once, with no wrap
  branch (no native rate of 1.0, no loop-restart state), and the next `start()` restarts from the start and loops at the following
  end; switched off between a loop's decision and its end handler gives `Completed`; a normal loop, a normal end and a replay are
  unchanged; a decision event that never came falls back to the current setting, and an earlier end's snapshot never outlives its
  end handler. `JavaFxRepeatToggleAtEndD2Boss3Test` runs the race on the real decoder (a `currentCount` listener posts the
  `setLooping` call into the gap): `Completed`, and the next play loops; the control (the mode set again to what it was) and the
  switch off stay green. C13 of the conformance class pins the order the fix relies on: `currentCount` changes and `onCycleDecision`
  fires before `onEndOfMedia`, at a plain end and at every wrap.
- **Freeze after a resume (tier R)** — `JavaFxResumeSettlesAtSpeedR5FixerTest`: after a pause and a start at 2 and at
  4 (six fresh engines each, or `-Pkmptoolkit.test.freeze.trials` for a longer run) the playhead passes where it
  paused plus 1200 ms within 3 s, so a playhead that freezes after the speed is given fails it.
- **Speed through the shared player (tier R)** — `JavaFxSpeedThroughCoreR6AdversarialTest` (a speed changed while
  paused, ready or completed is played from the next `play()` at that speed and the playhead keeps moving;
  the player gives the speed after `start()`, which no engine-level test above reaches) and
  `JavaFxSeekAtSpeedFreezeR6AdversarialTest` (a seek just after a loop wrap at a speed of 2 does not leave
  the playhead standing). Both are races: `-Pkmptoolkit.test.r6.trials` lengthens a run.
- **Calls from the FX thread (tier R)** — `JavaFxFxThreadCallerR7AdversarialTest` (the same speed scenarios driven from a
  button handler: the playhead moves on at the speed asked for) and `JavaFxFxThreadNudgeR8FixerTest` (a listener
  on the native rate property: a replay at a new speed from the FX thread gives the native player one
  away-and-back to the new speed, never the replaced old one).
- **Resume keeps the playhead (tier R)** — `JavaFxResumeKeepsPositionR10AdversarialTest`: after a pause and a speed
  change (while playing, while paused, or both) the playhead 600 ms after `play()` is not more than 300 ms
  below the paused position (a native seek would land on the key frame before it: a pause at 1540 ms resumed
  at 1000 ms with the 3 s clip, whose key frames are a second apart).
- **Remote sources that fail (tier K)** — `RemoteSourceFailuresD2ScoutDocsRealMediaTest`, against a local server on
  127.0.0.1 with a ready timeout of 3 s, 15 s for the 404 that macOS reports after about 5 s (macOS only, skipped
  elsewhere): an HTTP 404 is a `MediaException` of type `MEDIA_UNAVAILABLE`; an `https` URL against a server that only speaks plain HTTP, a connection reset while the clip
  loads and a server that answers a `Range` request with `200` each end in `LoadTimedOut`; the same clip served with
  `206` becomes ready (the control). They pin the documented kind of failure, not a duration. Beside them,
  `RemoteSourcesD2ScoutRealMediaTest` pins the other remote cases (ranges, a redirect, a faststart clip, a slow server).
- **Unreadable file** — `SourceUrisUnreadableFileR5Test`: a `VideoSource.File` the process cannot read
  fails with `FileNotFoundException` before JavaFX is involved (JavaFX itself took 4 s to fail it).
- **Reading JavaFX back.** Volume, rate and status have no getter on the engine contract; an
  internal `inspectPlayback()` reads them from the `MediaPlayer` on the FX thread for the tests.

Run the tiers with (one block per command; every one of them with `-Pjavafx.required=true`, so that a machine where
JavaFX cannot run fails instead of skipping):

The deterministic F tier alone, no media and no exclusive machine needed:

```sh
./gradlew :kmptoolkit-video-player-javafx:jvmTest -Pjavafx.required=true --tests 'io.github.jamal_wia.kmptoolkit.video.player.javafx.model.*'
```

The default gate (F, C and K). It plays real media, so run it alone on an idle machine. Measured on an Apple-silicon
Mac (J16: the 28 classes of the F tier and 57 others, 717 tests): about sixteen minutes in all, which is more than one
command should take, so split it with `--tests` into groups of class names that each stay under ten minutes (the
F tier and the first half of the K and C classes took 400 s; the second half took 546 s; the slowest classes are
`JavaFxReleaseAtLoopPointAdversarialTest` at about 160 s, `JavaFxSpeedAndSeekOrderR7AdversarialTest` at about 110 s,
`JavaFxHostileMediaR7AdversarialTest` at about 80 s and `JavaFxOddNamesPlaybackR9AdversarialTest` at about 70 s):

```sh
./gradlew :kmptoolkit-video-player-javafx:jvmTest -Pjavafx.required=true --rerun --tests 'io.github.jamal_wia.kmptoolkit.video.player.javafx.model.*' --tests 'io.github.jamal_wia.kmptoolkit.video.player.javafx.<OneClass>' --tests '...'
```

`jvmTest` is cached like any Gradle test task, so a second run on unchanged inputs does not run again: `--rerun` makes it
run (it is the flag to use for every gate run; `jvmRealMediaTest` never reuses a result and needs no flag).

The real-media soak (R), the classes of `realMediaSoakClasses`. It plays real media and is timing-sensitive, so
run it alone on an idle machine, and split it with `--tests` when one command would take longer than about ten minutes
(the fourteen classes of J16 together took about twelve minutes on an Apple-silicon Mac, and the 60-trial pin of the loop
wrap, `JavaFxResumeAtWrapD2Boss1B6RealMediaTest`, adds about three, so it is split in practice):

```sh
./gradlew :kmptoolkit-video-player-javafx:jvmRealMediaTest -Pjavafx.required=true
```

One soak class, for example after a change that touches its twin:

```sh
./gradlew :kmptoolkit-video-player-javafx:jvmRealMediaTest -Pjavafx.required=true --tests 'io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxLoopSpeedR5AdversarialTest'
```

The hazard probes (H), about ten minutes, alone on an idle machine:

```sh
./gradlew :kmptoolkit-video-player-javafx:jvmRealMediaTest -Pjavafx.required=true -Pkmptoolkit.test.hazards=true --tests 'io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxNativeHazardCharacterizationTest'
```

`JavaFxResumeAtSpeedR5AdversarialTest` "a pause and resume at a speed of two keeps the speed" was red in four runs in ten
before 5.0.0 (slopes 0.81 to 0.96 at a 1-minute load of 5.8 to 7.8): its window was anchored on the reported time after
the pause, which can lag the decoder by about 0.4 to 0.55 s, so it opened before the speed was given. It now opens 600 ms of
media after the second change of the reported time after the resume and lasts 400 ms. A red of it now is new: match it
against its F twin (`aPauseAndResumeAtASpeedOfTwoKeepsTheSpeed`) and rerun it alone on an idle machine.

A red in the soak is not rerun until it is green: reproduce it on the F tier first (the twin of the class asserts the
same contract on a virtual clock), and when the F tier cannot reproduce it, treat it as a gap of the model and check it
with the hazard probes. The CI workflow does not run `jvmTest` or `jvmRealMediaTest`; run the gate locally on a machine
with a display before changing the engine.

Knobs of the long runs (trial counts, seed ranges, soak length, `-Pkmptoolkit.test.hazards=true`) are Gradle
properties that start with `kmptoolkit.test.`, for example `-Pkmptoolkit.test.r6.trials=30`. The build hands each
of them to the test JVM, and to the child JVMs that some classes start, as a system property of the same name, and
prints them once when the test task starts; a value exported in the shell is not read.
