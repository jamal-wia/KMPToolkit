package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxPlayback
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * The resume family of the JavaFX model tier (task J10): the F twins, in virtual time on the composed and watched
 * scripted player ([composeAll]), of the real-media classes that pin how a speed other than 1 is given to a native
 * player that is resumed, played for the first time or played from the end. Each test names its real-media twin; the
 * property the twin checks on real media (a moving playhead, a kept speed) is checked here too, and the freeze the twin
 * can only see as a standing playhead is checked as the hazard monitor's verdict (`hazards.frozen`, and the end-of-test
 * check of [FxModelScope.watchHazards]) together with the exact call-log times at which the speed reaches the decoder.
 *
 * Real-media twins:
 * - `JavaFxResumeAtSpeedR5AdversarialTest` (A13): [aResumeAtASpeedOfFourGivesTheSpeedOnceTheDecoderRanForAMoment],
 *   [aResumeAtNormalSpeedGivesNoRateAtAll], [aPauseAndResumeAtASpeedOfTwoKeepsTheSpeed] and
 *   [thirtySeededResumesAtASpeedMoveThePlayheadOnEveryTime];
 * - `JavaFxResumeSettlesAtSpeedR5FixerTest` (B2): [aResumeAtASpeedOfTwoOrFourCarriesThePlayheadPastTheSpeedChange];
 * - `JavaFxResumeKeepsPositionR10AdversarialTest`: [aResumeAfterAPauseContinuesFromThePausedPositionWhateverSpeedWasSetMeanwhile];
 * - `JavaFxSpeedThroughCoreR6AdversarialTest` and `JavaFxSpeedInReadyThroughCoreR6AdversarialTest` (A16, through the
 *   shared player on [NeverDispatcher]): the `...ThroughThePlayer` tests;
 * - the idle gap (W-IDLE) and the fresh-play window (W-PLAY after a plain play) have no class of their own on real media;
 *   their tests are the contract of `FxPlayback.start` and `FxPlayback.setSpeed` as the KDoc and the ruling state it.
 *
 * Documented times (all in virtual milliseconds, jitter off unless a test says so): a resume at a speed plays at once and
 * gives the speed as one away-and-back when the playhead has moved 200 ms at normal speed on the ticks of the playhead
 * (every 100 ms, the first tick after the status PLAYING only marks where it stands), which is 300 ms after the play; a
 * resume within the idle gap (200 ms) of a rate written while not playing delays its play to the end of the gap plus 1 ms.
 *
 * Mutants that turn this file red (temporary local patches, see the report): M-R5 (a plain resume gives the nudge at
 * once), M-IDLE (the idle gap is not waited out), M-S6 (a speed right after a plain play is written at once).
 */
class ResumeFamilyModelTest {

    // ---- rigs ----

    private class EngineRig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
        val hazards: HazardModel get() = composed.hazards
        val time: TimeModel get() = composed.time
    }

    private class PlayerRig(val engine: JavaFxVideoEngine, val backend: ComposedBackend, val player: VideoPlayer) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
        val hazards: HazardModel get() = composed.hazards
        val time: TimeModel get() = composed.time
    }

    private fun FxModelScope.withEngine(body: EngineRig.() -> Unit) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            EngineRig(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    private fun FxModelScope.withPlayer(config: VideoPlayerConfig = VideoPlayerConfig(), body: PlayerRig.() -> Unit) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        val player: VideoPlayer = videoPlayer(engine, config)
        prepare(player)
        try {
            assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "state ${player.stateFlow.value}")
            PlayerRig(engine, backend, player).body()
        } finally {
            player.release()
            advanceBy(50)
        }
    }

    /** `player.prepare` driven the way [FxModelScope.load] drives an engine load: the IO hop is real, the FX queue is drained here. */
    private fun FxModelScope.prepare(player: VideoPlayer) {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
        val deadline: Long = System.nanoTime() + PREPARE_BOUND_MS * NANOS_PER_MILLI_LONG
        runBlocking {
            val preparing = async { player.prepare(VideoSource.Asset(TestClip.ASSET)) }
            while (!preparing.isCompleted) {
                host.runUntilIdle()
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
                if (!preparing.isCompleted && System.nanoTime() - deadline > 0L) {
                    preparing.cancel()
                    throw AssertionError("prepare did not finish within $PREPARE_BOUND_MS ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            preparing.await()
        }
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / NANOS_PER_MILLI

    /** Advances in steps of [stepMs] virtual ms until [condition] holds, at most [limitMs]; returns the virtual ms waited. */
    private fun FxModelScope.advanceUntil(limitMs: Long = 10_000L, stepMs: Long = 10L, condition: () -> Boolean): Long {
        var waited = 0L
        while (!condition()) {
            check(waited < limitMs) { "the condition did not hold within $limitMs virtual ms ($host)" }
            advanceBy(stepMs)
            waited += stepMs
        }
        return waited
    }

    private fun ScriptedNativePlayer.rateWritesAfter(t: Double): List<NativeCallEntry> =
        log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs > t }

    private fun assertAt(expected: Double, actual: Double, message: String) =
        assertEquals(expected, actual, TIME_TOLERANCE_MS, message)

    /**
     * The shared setup of the R5 classes: the speed set on a fresh engine, a start, [playMs] of play, a pause and [pauseMs]
     * of rest. Returns where the playhead stands.
     */
    private fun EngineRig.pausedAtSpeed(scope: FxModelScope, speed: Float, playMs: Long = 400L, pauseMs: Long = 300L): Double {
        engine.setSpeed(speed)
        engine.start()
        scope.advanceBy(playMs)
        assertTrue(native.positionMs > 250.0, "the clip advanced before the pause, was ${native.positionMs}\n${native.log}")
        engine.pause()
        scope.advanceBy(pauseMs)
        return native.positionMs
    }

    // ---- R5 resume at a speed (A13 and the B2 settle class) ----

    /**
     * Twin of `a resume at a speed of four moves the playhead on every time`. The resume plays at once (the speed was
     * written long before, so no idle gap applies), keeps the property at 4 and gives it as one away-and-back 300 ms after
     * the play, outside W-PLAY (100 ms). The playhead moves on from the paused position.
     */
    @Test
    fun aResumeAtASpeedOfFourGivesTheSpeedOnceTheDecoderRanForAMoment() = fxModel {
        withEngine {
            val paused: Double = pausedAtSpeed(this@fxModel, 4f)
            val calledAt: Double = nowMs()
            engine.start()
            advanceBy(500)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            assertAt(calledAt, playAt, "the resume plays at once\n${native.log}")
            val rates: List<NativeCallEntry> = native.rateWritesAfter(playAt)
            assertEquals(listOf(1.0, 4.0), rates.map { it.arg }, "one away-and-back\n${native.log}")
            for (rate: NativeCallEntry in rates) {
                assertAt(playAt + 300.0, rate.tMs, "the speed is given 300 ms after the play, outside W-PLAY\n${native.log}")
            }
            assertTrue(native.positionMs > paused + 150.0, "the playhead moved on, stood at ${native.positionMs} from $paused")
            advanceBy(2_000)
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(4.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
        }
    }

    /** Twin of `a resume at normal speed moves the playhead on every time`: no rate is written at all, the playhead moves. */
    @Test
    fun aResumeAtNormalSpeedGivesNoRateAtAll() = fxModel {
        withEngine {
            val paused: Double = pausedAtSpeed(this@fxModel, 1f)
            val calledAt: Double = nowMs()
            engine.start()
            advanceBy(500)

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            assertAt(calledAt, playAt, "the resume plays at once\n${native.log}")
            assertEquals(emptyList(), native.rateWritesAfter(playAt).map { it.arg }, "no rate after the resume\n${native.log}")
            // 500 ms after the play, less the 30 ms status lag: the decoder ran 470 ms at normal speed.
            assertTrue(native.positionMs >= paused + 450.0, "the playhead moved at normal speed, stood at ${native.positionMs} from $paused")
            advanceBy(2_000)
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(1.0, hazards.effectiveRate)
        }
    }

    /**
     * Twin of `a pause and resume at a speed of two keeps the speed`: after the resume has settled (the speed written),
     * the decoder moves the playhead at twice normal speed, measured on the native position over 600 ms (the real class
     * asserts more than 1.5 on a window timed at playhead changes; the native position needs no such timing).
     */
    @Test
    fun aPauseAndResumeAtASpeedOfTwoKeepsTheSpeed() = fxModel {
        withEngine {
            val paused: Double = pausedAtSpeed(this@fxModel, 2f)
            engine.start()
            val playAt: Double = nowMs()
            advanceBy(600)
            assertTrue(native.positionMs > paused + 600.0, "the playhead moved on after the resume: ${native.positionMs} from $paused")
            val from: Double = time.nativePositionMs
            advanceBy(600)
            val slope: Double = (time.nativePositionMs - from) / 600.0

            assertEquals(listOf(1.0, 2.0), native.rateWritesAfter(playAt).map { it.arg }, native.log.tail())
            assertTrue(slope >= 1.99, "after a resume the playhead moved $slope ms per ms at a speed of 2\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    /**
     * Twin of `a resume at a speed of two carries the playhead past the speed change` and of its speed of four: the
     * playhead passes paused + 1200 within 1 s of the resume (the real class allows 3 s on a decoder it cannot see).
     */
    @Test
    fun aResumeAtASpeedOfTwoOrFourCarriesThePlayheadPastTheSpeedChange() {
        for (speed: Float in listOf(2f, 4f)) {
            fxModel {
                withEngine {
                    val paused: Double = pausedAtSpeed(this@fxModel, speed)
                    engine.start()
                    val waited: Long = advanceUntil(limitMs = 3_000L, stepMs = 20L) { native.positionMs > paused + 1_200.0 }
                    assertTrue(waited <= 1_000L, "at a speed of $speed the playhead passed paused + 1200 only after $waited ms\n${native.log}")
                    assertFalse(hazards.frozen, native.log.tail())
                    assertEquals(speed.toDouble(), hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
                }
            }
        }
    }

    /**
     * Twin of the 6 + 6 fresh engines of A13, and of the "30 seeded resumes" of the design (5.2): 30 resumes at a speed of
     * 4, 1 or 2 with a seeded length of play and of rest, each in its own model. The playhead moves on in every one, the
     * decoder never freezes, and the speed arrives. The host's timer jitter stays off: `VirtualFxHost` applies it to the
     * scripted player's own timers too, and `PictureModel.scheduleNext` then asks for a negative delay (reported to the lead).
     * The seed (see `-Dkmptoolkit.test.seed`) and the trial are in every failure.
     */
    @Test
    fun thirtySeededResumesAtASpeedMoveThePlayheadOnEveryTime() {
        val master = Random(VirtualFxHost.defaultSeed())
        val failures: MutableList<String> = mutableListOf()
        repeat(RESUME_TRIALS) { trial: Int ->
            val trialSeed: Long = master.nextLong()
            val speed: Float = listOf(4f, 1f, 2f)[trial % 3]
            val playMs: Long = 350L + master.nextInt(0, 150)
            val pauseMs: Long = 100L + master.nextInt(0, 800)
            try {
                fxModel(seed = trialSeed) {
                    withEngine {
                        val paused: Double = pausedAtSpeed(this@fxModel, speed, playMs, pauseMs)
                        engine.start()
                        advanceUntil(limitMs = 2_500L, stepMs = 20L) { native.positionMs > paused + 150.0 }
                        advanceBy(1_000)
                        assertFalse(hazards.frozen, native.log.tail())
                        assertEquals(speed.toDouble(), hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
                    }
                }
            } catch (t: Throwable) {
                failures += "trial $trial (speed $speed play $playMs pause $pauseMs seed $trialSeed): ${t.message?.lineSequence()?.firstOrNull()}"
            }
        }
        assertEquals(emptyList(), failures, "of $RESUME_TRIALS resumes (master seed ${VirtualFxHost.defaultSeed()})")
    }

    // ---- the idle gap (W-IDLE) ----

    /**
     * A play right after a rate written while paused would freeze the decoder, so `start` delays it to the end of the gap
     * (200 ms) plus 1 ms: the paused playhead does not move meanwhile, and the speed is then given like any resume's.
     */
    @Test
    fun aResumeRightAfterASpeedWrittenWhilePausedWaitsOutTheIdleGap() = fxModel {
        withEngine {
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(100)
            val before: Double = native.positionMs
            val writtenAt: Double = nowMs()
            engine.setSpeed(3f)
            engine.start()
            advanceBy(150)
            assertEquals(before, native.positionMs, "the paused playhead stands still during the gap\n${native.log}")
            assertEquals(0, native.log.calls(NativeCall.PLAY).count { it.tMs > writtenAt }, "no play inside the gap\n${native.log}")
            advanceBy(2_000)

            val written: NativeCallEntry = native.log.calls(NativeCall.SET_RATE).first { it.effective && it.arg == 3.0 }
            assertAt(writtenAt, written.tMs, "the speed was written while paused\n${native.log}")
            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            assertAt(writtenAt + 201.0, playAt, "the play comes at the end of the idle gap plus 1 ms\n${native.log}")
            assertTrue(playAt - written.tMs >= HazardConfig.DEFAULT_IDLE_MS, "the play came ${playAt - written.tMs} ms after the rate")
            assertEquals(listOf(1.0, 3.0), native.rateWritesAfter(playAt).map { it.arg }, "then the usual away-and-back\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(3.0, hazards.effectiveRate)
            assertTrue(native.positionMs > before + 1_000.0, "the playhead moved on, was ${native.positionMs} from $before")
        }
    }

    /** Only the rest of the gap is waited: a resume 120 ms after the write plays 201 ms after it, and one 300 ms after it plays at once. */
    @Test
    fun theIdleGapIsMeasuredFromTheRateWrittenWhilePaused() = fxModel {
        withEngine {
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(100)
            val writtenAt: Double = nowMs()
            engine.setSpeed(3f)
            advanceBy(120)
            engine.start()
            advanceBy(500)
            assertAt(writtenAt + 201.0, native.log.calls(NativeCall.PLAY).last().tMs, "a late resume waits only the rest of the gap\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())

            engine.pause()
            advanceBy(100)
            val secondWriteAt: Double = nowMs()
            engine.setSpeed(2f)
            advanceBy(300)
            val calledAt: Double = nowMs()
            engine.start()
            advanceBy(500)
            assertAt(calledAt, native.log.calls(NativeCall.PLAY).last().tMs, "past the gap the play is not delayed (written at $secondWriteAt)\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    /** A pause inside the gap cancels the delayed play: nothing plays afterwards, the playhead stands. */
    @Test
    fun aPauseInsideTheIdleGapCancelsTheDelayedPlay() = fxModel {
        withEngine {
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(100)
            val writtenAt: Double = nowMs()
            engine.setSpeed(3f)
            engine.start()
            advanceBy(50)
            engine.pause()
            val standing: Double = native.positionMs
            advanceBy(1_000)

            assertEquals(0, native.log.calls(NativeCall.PLAY).count { it.tMs > writtenAt }, "the delayed play never ran\n${native.log}")
            assertEquals(standing, native.positionMs, "the playhead stands still\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    // ---- the fresh-play window (a speed right after a plain play) ----

    /**
     * A speed given within 600 ms of a plain play is given the way a resume gives it: not at once, but 200 ms of playhead
     * movement after the first tick that follows the call, never inside W-PLAY, and as one change.
     */
    @Test
    fun aSpeedGivenWithinTheFreshPlayWindowIsGivenAfterTheDecoderRanForAMoment() {
        // gap after the plain play, and the tick (100 ms grid from the play) at which the wait for 200 ms of movement starts
        for ((gapMs: Long, firstTickMs: Long) in listOf(0L to 100L, 120L to 200L, 250L to 300L, 550L to 600L)) {
            fxModel {
                withEngine {
                    engine.start()
                    advanceBy(1_000)
                    engine.pause()
                    advanceBy(300)
                    val calledAt: Double = nowMs()
                    engine.start()
                    advanceBy(gapMs)
                    engine.setSpeed(3f)
                    advanceBy(2_000)

                    val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
                    assertAt(calledAt, playAt, "the resume at the speed in force plays at once\n${native.log}")
                    val rates: List<NativeCallEntry> = native.rateWritesAfter(playAt)
                    assertEquals(listOf(3.0), rates.map { it.arg }, "gap $gapMs: the speed reached the native player once\n${native.log}")
                    assertAt(playAt + firstTickMs + 200.0, rates.single().tMs, "gap $gapMs: given 200 ms of movement after the first tick\n${native.log}")
                    assertTrue(rates.single().tMs - playAt >= HazardConfig.DEFAULT_PLAY_MS, "gap $gapMs: inside W-PLAY\n${native.log}")
                    assertFalse(hazards.frozen, native.log.tail())
                    assertEquals(3.0, hazards.effectiveRate)
                }
            }
        }
    }

    /** After the fresh window (600 ms) the speed is written at once, as a plain change to a decoder that runs. */
    @Test
    fun aSpeedGivenAfterTheFreshPlayWindowIsWrittenAtOnce() = fxModel {
        withEngine {
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(300)
            engine.start()
            advanceBy(650)
            val calledAt: Double = nowMs()
            engine.setSpeed(3f)
            advanceBy(300)

            val rates: List<NativeCallEntry> = native.rateWritesAfter(native.log.calls(NativeCall.PLAY).last().tMs)
            assertEquals(listOf(3.0), rates.map { it.arg }, native.log.tail())
            assertAt(calledAt, rates.single().tMs, "written at once\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
            assertEquals(3.0, hazards.effectiveRate)
        }
    }

    // ---- R10: a resume continues from the paused position (through the shared player) ----

    /**
     * Twin of `a resume after a pause continues from the paused position whatever speed was set meanwhile`: the same six
     * speed pairs, four trials each with a seeded length of rest. Stricter than the real class (it allows the resume
     * point 300 ms back): no native seek is issued by the resume and the playhead never reads below the paused position.
     */
    @Test
    fun aResumeAfterAPauseContinuesFromThePausedPositionWhateverSpeedWasSetMeanwhile() {
        val master = Random(VirtualFxHost.defaultSeed())
        val failures: MutableList<String> = mutableListOf()
        var runs = 0
        val pairs: List<Pair<Float?, Float?>> = listOf(null to null, null to 0.5f, null to 2f, 2f to 0.5f, 2f to null, 0.5f to 1f)
        for ((whilePlaying: Float?, whilePaused: Float?) in pairs) {
            repeat(4) { n: Int ->
                runs++
                val trialSeed: Long = master.nextLong()
                val label = "speeds playing=$whilePlaying paused=$whilePaused #$n seed $trialSeed"
                try {
                    fxModel(seed = trialSeed) { resumeKeepsPosition(whilePlaying, whilePaused, 400L + master.nextInt(0, 400)) }
                } catch (t: Throwable) {
                    failures += "$label: ${t.message?.lineSequence()?.firstOrNull()}"
                }
            }
        }
        assertEquals(emptyList(), failures, "of $runs resumes (master seed ${VirtualFxHost.defaultSeed()})")
    }

    private fun FxModelScope.resumeKeepsPosition(speedWhilePlaying: Float?, speedWhilePaused: Float?, pausedForMs: Long) {
        withPlayer {
            player.play()
            advanceUntil { engine.positionMs() > 1_500L }
            if (speedWhilePlaying != null) {
                player.setPlaybackSpeed(speedWhilePlaying)
                advanceBy(150)
            }
            player.pause()
            val at: Long = (player.stateFlow.value as VideoPlayerState.Paused).currentPosition
            advanceBy(pausedForMs)
            if (speedWhilePaused != null) player.setPlaybackSpeed(speedWhilePaused)
            val seeksBefore: Int = native.log.calls(NativeCall.SEEK).size
            player.play()
            var lowest: Long = Long.MAX_VALUE
            repeat(60) {
                advanceBy(10)
                lowest = minOf(lowest, engine.positionMs())
            }
            val first: Long = engine.positionMs()
            assertTrue(first + 300L >= at, "paused at $at and 600 ms after the resume the playhead reads $first\n${native.log}")
            assertTrue(lowest >= at, "paused at $at and the playhead read $lowest after the resume\n${native.log}")
            assertEquals(seeksBefore, native.log.calls(NativeCall.SEEK).size, "the resume issued a native seek\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    // ---- S6 and the speed in Ready, through the shared player (A16) ----

    /**
     * Twin of `a speed changed while paused is played after the next play, through the player`: the shared player's
     * `play()` calls `engine.start()` and only then `engine.setSpeed(speed)`, so the speed is written right after a plain
     * play: the fresh-play window gives it 300 ms after the play (one change, outside W-PLAY), and the decoder then moves
     * the playhead at twice normal speed.
     */
    @Test
    fun aSpeedChangedWhilePausedIsPlayedAfterTheNextPlayThroughThePlayer() = fxModel {
        withPlayer {
            player.play()
            advanceUntil { engine.positionMs() > 250L }
            player.pause()
            advanceBy(300)
            val paused: Long = engine.positionMs()
            player.setPlaybackSpeed(2f)
            val calledAt: Double = nowMs()
            player.play()
            val waited: Long = advanceUntil(limitMs = 3_500L, stepMs = 5L) { engine.positionMs() > paused + 600L }
            assertTrue(waited <= 1_000L, "the playhead passed paused + 600 only after $waited ms\n${native.log}")

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            assertAt(calledAt, playAt, "the play is not delayed\n${native.log}")
            val rates: List<NativeCallEntry> = native.rateWritesAfter(playAt)
            assertEquals(listOf(2.0), rates.map { it.arg }, "the speed reached the native player once\n${native.log}")
            assertAt(playAt + 300.0, rates.single().tMs, "given 300 ms after the play\n${native.log}")
            advanceBy(300)
            val from: Double = time.nativePositionMs
            advanceBy(600)
            val slope: Double = (time.nativePositionMs - from) / 600.0
            assertTrue(slope >= 1.99, "moved $slope ms per ms at a speed of 2 (paused at $paused)\n${native.log}")
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    /**
     * Twin of `a speed changed in Ready is played from the first play, through the player` (in both A16 classes): the
     * clip plays at 4x from the first play and completes within a second of virtual time (3000 ms at 4x are 750 ms plus
     * the status lag; at normal speed it would take over 3 s). The first play of a clip standing at the start gives the
     * speed in the same FX task as the play (its seek, play and away-and-back, before the decoder has started).
     */
    @Test
    fun aSpeedChangedInReadyIsPlayedFromTheFirstPlayThroughThePlayer() = fxModel {
        withPlayer(FOUR_TIMES) {
            player.setPlaybackSpeed(4f)
            val calledAt: Double = nowMs()
            player.play()
            val waited: Long = advanceUntil(limitMs = 2_200L, stepMs = 5L) { player.stateFlow.value is VideoPlayerState.Completed }
            assertTrue(waited <= 1_000L, "completed only after $waited ms at a speed of 4: ${player.stateFlow.value}\n${native.log}")

            val rates: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs >= calledAt }
            assertEquals(listOf(4.0), rates.map { it.arg }, "the speed reached the native player once\n${native.log}")
            assertAt(calledAt, rates.single().tMs, "given with the first play\n${native.log}")
            assertEquals(4.0, hazards.effectiveRate)
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    /**
     * Twin of `a speed changed while Completed is played by the next play, through the player`: after a completion at 4x
     * a speed of 2 is set and `play()` starts over; the playhead passes 600 ms and then moves at twice normal speed.
     */
    @Test
    fun aSpeedChangedWhileCompletedIsPlayedByTheNextPlayThroughThePlayer() = fxModel {
        withPlayer(FOUR_TIMES) {
            player.setPlaybackSpeed(4f)
            player.play()
            advanceUntil(limitMs = 8_000L, stepMs = 5L) { player.stateFlow.value is VideoPlayerState.Completed }
            player.setPlaybackSpeed(2f)
            player.play()
            val waited: Long = advanceUntil(limitMs = 3_500L, stepMs = 5L) { engine.positionMs() > 600L }
            assertTrue(waited <= 1_500L, "the playhead passed 600 only after $waited ms after a play from Completed\n${native.log}")
            val from: Double = time.nativePositionMs
            advanceBy(600)
            val slope: Double = (time.nativePositionMs - from) / 600.0

            assertTrue(slope >= 1.99, "moved $slope ms per ms at a speed of 2 after a play from Completed\n${native.log}")
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    /** Twin of `the player and the engine agree on the speed in use after play` (in both A16 classes). */
    @Test
    fun thePlayerAndTheEngineAgreeOnTheSpeedInUseAfterPlay() = fxModel {
        withPlayer {
            player.setPlaybackSpeed(2f)
            player.play()
            advanceUntil { engine.positionMs() > 200L }
            // The speed is given to the native player once play() has run and the decoder has run for a moment.
            advanceBy(400)
            val inspected: FxPlayback.Inspection? = inspect()
            assertEquals(2.0, inspected?.rate, "the rate property after a play at a speed of 2 (state ${player.stateFlow.value})\n${native.log}")
            assertEquals(2.0, native.rate)
            assertEquals(2.0, hazards.effectiveRate)
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    private fun PlayerRig.inspect(): FxPlayback.Inspection? = runBlocking {
        val inspecting = async { engine.inspectPlayback() }
        while (!inspecting.isCompleted) {
            backend.host.let { (it as VirtualFxHost).runUntilIdle() }
            yield()
        }
        inspecting.await()
    }

    private companion object {
        const val NANOS_PER_MILLI: Double = 1_000_000.0
        const val NANOS_PER_MILLI_LONG: Long = 1_000_000L

        /** The virtual clock is in nanoseconds and the log in milliseconds, with rounding at the 7th decimal. */
        const val TIME_TOLERANCE_MS: Double = 0.01

        const val RESUME_TRIALS: Int = 30

        /** The shared player clamps a speed to 3 by default; the real classes ask for 4 and read 3, these tests really give 4. */
        val FOUR_TIMES: VideoPlayerConfig = VideoPlayerConfig(maxPlaybackSpeed = 4f)

        const val PREPARE_BOUND_MS: Long = 10_000L
    }
}
