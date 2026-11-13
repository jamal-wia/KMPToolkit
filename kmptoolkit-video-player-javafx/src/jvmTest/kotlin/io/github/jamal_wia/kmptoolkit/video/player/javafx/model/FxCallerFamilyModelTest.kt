package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.EngineEvent
import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxPlayback
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.RecordingListener
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * The FX-caller family of the JavaFX model tier (task J11b): the F twins, in virtual time on the composed and watched
 * scripted player ([composeAll]), of the real-media classes that pin the ORDER of the native calls an app produces. The
 * order depends on who calls: from a plain thread every call of `FxPlayback` is queued in call order, but from the FX
 * application thread (a button handler, the main way this engine is used) `FxQueue.post` runs a block inline when nothing
 * is queued, so `start()` runs inline and queues its nudge, and a `setSpeed` right after it either replaces the pending
 * nudge or, queued behind it, writes a third rate change next to a `play()` (suspect S13). Every test names its real-media
 * twin; the property the twin checks on real media (a moving playhead, the speed asked for, a kept seek) is checked here
 * too, and the freeze the twin can only see as a standing playhead is checked as the hazard monitor's verdict
 * (`hazards.frozen`, and the end-of-test check of [FxModelScope.watchHazards]) together with the exact native call log: the
 * rate sequence and the virtual time each rate reaches the decoder.
 *
 * Real-media twins:
 * - `JavaFxFxThreadCallerR7AdversarialTest` (A17, three tests): [aSpeedChangedWhilePausedThenPlayFromTheFxThreadPlaysOnAtThatSpeed],
 *   [aSpeedChangedAfterCompletionAtAnotherSpeedThenReplayFromTheFxThreadPlaysAtTheNewSpeed] and
 *   [playSpeedAndSeekBackToBackFromTheFxThreadKeepEveryCall];
 * - `JavaFxFxThreadNudgeR8FixerTest` (B2, two tests): [aReplayFromTheFxThreadAtANewSpeedGivesOneAwayAndBackToTheNewSpeed] and
 *   [aSpeedGivenOnTheFxThreadRightAfterAResumeOfAPausedPlayerReplacesThePendingNudge];
 * - `JavaFxSpeedAndSeekOrderR7AdversarialTest` (A20, six tests): the six `...Order...` tests below, one per real test, named
 *   after it.
 * The S13 table ([everyFxThreadSequenceGivesTheNativePlayerAtMostOneAwayAndBackToTheSpeedGivenLast]) has no class of its own
 * on real media: it is the contract of `FxPlayback.start` as its comment states it.
 *
 * Documented times (virtual milliseconds, jitter off): the nudge of `start` is given two FX tasks after the play, which is
 * at the instant of the play when nothing else is due, and a speed given inside the window of a native seek (300 ms) is
 * written at the end of the window or later, once the playhead has moved; a resume at a speed gives it 300 ms after the play.
 *
 * Mutants that turn this file red (temporary local patches, see the report): M-S13 (one `postLater` instead of two in the
 * nudge of `FxPlayback.start`).
 */
class FxCallerFamilyModelTest {

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
        // The player pushes its volume, loop mode and speed after the prepare: let them run, so that an `onFx` block is the
        // FX thread with nothing queued (the case a button handler is in), not a block queued behind them.
        advanceBy(0)
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

    private fun PlayerRig.inspect(): FxPlayback.Inspection? = runBlocking {
        val inspecting = async { engine.inspectPlayback() }
        while (!inspecting.isCompleted) {
            backend.host.let { (it as VirtualFxHost).runUntilIdle() }
            yield()
        }
        inspecting.await()
    }

    private fun EngineRig.inspect(): FxPlayback.Inspection? = runBlocking {
        val inspecting = async { engine.inspectPlayback() }
        while (!inspecting.isCompleted) {
            backend.host.let { (it as VirtualFxHost).runUntilIdle() }
            yield()
        }
        inspecting.await()
    }

    /** Plays the loaded clip at 4x to its end: the engine's `Completed` event arrived. */
    private fun EngineRig.completeAtFourTimes(scope: FxModelScope) {
        val listener = RecordingListener()
        engine.setListener(listener)
        engine.setSpeed(4f)
        engine.start()
        scope.advanceUntil(limitMs = 5_000L, stepMs = 5L) { listener.events.any { it is EngineEvent.Completed } }
    }

    /** The rate writes that changed the native player since the log held [mark] entries, oldest first. */
    private fun ScriptedNativePlayer.rateWritesSince(mark: Int): List<NativeCallEntry> =
        log.entries.drop(mark).filter { it.call == NativeCall.SET_RATE && it.effective }

    private fun assertAt(expected: Double, actual: Double, message: String) =
        assertEquals(expected, actual, TIME_TOLERANCE_MS, message)

    // ---- A17: the three sequences of FxThreadCallerR7 (S13) ----

    /**
     * Twin of `a speed changed while paused then play, called from the FX thread, plays on at that speed`. The shared player
     * stores a speed set while Paused, and its `play()` calls `engine.start()` and then `engine.setSpeed(2)` in one FX task.
     * `start` resumes at the rate in force (1.0): a plain play, which leaves the speed to the fresh-play window of
     * `setSpeed`, so the speed is given as one away-and-back 300 ms after the play, outside W-PLAY, and the decoder then
     * moves the playhead at twice normal speed (the real class accepts a slope from 1.5 to 3.0 on a window timed at playhead
     * changes; the native position is read exactly here).
     */
    @Test
    fun aSpeedChangedWhilePausedThenPlayFromTheFxThreadPlaysOnAtThatSpeed() = fxModel {
        withPlayer {
            player.play()
            advanceUntil { engine.positionMs() > 300L }
            player.pause()
            advanceBy(250)
            assertTrue(player.stateFlow.value is VideoPlayerState.Paused, "state ${player.stateFlow.value}")
            val paused: Double = native.positionMs
            val mark: Int = native.log.size
            val calledAt: Double = nowMs()
            onFx {
                player.setPlaybackSpeed(2f)
                player.play()
            }
            val waited: Long = advanceUntil(limitMs = 3_500L, stepMs = 5L) { engine.positionMs() >= paused + 600L }
            assertTrue(waited <= 1_000L, "the playhead passed paused + 600 only after $waited ms\n${native.log}")

            val playAt: Double = native.log.calls(NativeCall.PLAY).last().tMs
            assertAt(calledAt, playAt, "the play ran inline on the FX thread\n${native.log}")
            // The property held 1.0 (the speed was only stored while Paused), so the away write is no change: the nudge is
            // 1.0 (no effect) then 2.0, and exactly one change reaches the decoder.
            val rates: List<NativeCallEntry> = native.log.entries.drop(mark).filter { it.call == NativeCall.SET_RATE }
            assertEquals(listOf(1.0, 2.0), rates.map { it.arg }, "one away-and-back to the speed asked for\n${native.log}")
            assertEquals(listOf(2.0), native.rateWritesSince(mark).map { it.arg }, "one rate change reached the decoder\n${native.log}")
            for (rate: NativeCallEntry in rates) assertAt(playAt + 300.0, rate.tMs, "given 300 ms after the play\n${native.log}")
            advanceBy(300)
            val from: Double = time.nativePositionMs
            advanceBy(600)
            val slope: Double = (time.nativePositionMs - from) / 600.0
            assertTrue(slope >= 1.99 && slope <= 2.01, "the playhead moved $slope ms per ms, expected 2\n${native.log}")
            assertEquals(2.0, inspect()?.rate, "the rate property reads 2.0\n${native.log}")
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    /**
     * Twin of `a speed changed after completion at another speed then replay, called from the FX thread, plays at the new
     * speed` (the test that stood at 0 in about 3 of 8 trials): `replay()` on the FX thread runs `seekTo(0)`, `start()` and
     * `setSpeed(2)` in one task. `start` runs inline and queues its nudge for two tasks later, and the `setSpeed` right
     * after it runs inline too and replaces the pending nudge, so the decoder gets one away-and-back to the NEW speed and the
     * replaced speed of 4 is never given. Given a nudge of one hop (M-S13), the old speed 1.0, then 4.0, then 2.0 would reach
     * the decoder right after `play()`.
     */
    @Test
    fun aSpeedChangedAfterCompletionAtAnotherSpeedThenReplayFromTheFxThreadPlaysAtTheNewSpeed() = fxModel {
        withPlayer(FOUR_TIMES) {
            player.setPlaybackSpeed(4f)
            player.play()
            advanceUntil(limitMs = 8_000L, stepMs = 5L) { player.stateFlow.value is VideoPlayerState.Completed }
            val mark: Int = native.log.size
            val calledAt: Double = nowMs()
            onFx {
                player.setPlaybackSpeed(2f)
                player.replay()
            }
            val waited: Long = advanceUntil(limitMs = 3_500L, stepMs = 5L) { engine.positionMs() >= 600L }
            assertTrue(waited <= 1_500L, "the playhead passed 600 only after $waited ms after a replay\n${native.log}")

            val seek: NativeCallEntry = native.log.entries.drop(mark).first { it.call == NativeCall.SEEK }
            assertAt(calledAt, seek.tMs, "the seek ran inline\n${native.log}")
            val rates: List<NativeCallEntry> = native.rateWritesSince(mark)
            assertEquals(listOf(1.0, 2.0), rates.map { it.arg }, "away and back to the NEW speed, the replaced 4.0 never given\n${native.log}")
            for (rate: NativeCallEntry in rates) assertAt(calledAt, rate.tMs, "given with the play, two FX tasks later\n${native.log}")
            val from: Double = time.nativePositionMs
            advanceBy(600)
            val slope: Double = (time.nativePositionMs - from) / 600.0
            assertTrue(slope >= 1.99 && slope <= 2.01, "the playhead moved $slope ms per ms, expected 2\n${native.log}")
            assertEquals(2.0, inspect()?.rate, "the rate property reads 2.0\n${native.log}")
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    /**
     * Twin of `play, speed and seek called back to back from the FX thread keep every call`: `setPlaybackSpeed(2)` in Ready
     * (stored), `seekTo(1000)` (a native seek, inline), `play()` (inline, a play right after the seek) and the speed of 2 by
     * the shared player's `play()`. The seek must not be undone: the playhead never reads below 900, the clip completes
     * within 4 s, and the speed is written no sooner than 300 ms after the seek (the seek window of `setSpeed`), once.
     */
    @Test
    fun playSpeedAndSeekBackToBackFromTheFxThreadKeepEveryCall() = fxModel {
        withPlayer {
            val calledAt: Double = nowMs()
            onFx {
                player.setPlaybackSpeed(2f)
                player.seekTo(1_000L)
                player.play()
            }
            var lowest: Double = Double.MAX_VALUE
            var waited = 0L
            while (waited < 4_000L && player.stateFlow.value !is VideoPlayerState.Completed) {
                lowest = minOf(lowest, engine.positionMs().toDouble())
                advanceBy(5)
                // A native seek lands a moment after the call (the model's seek latency): the native playhead is read after it.
                if (waited > 0L) lowest = minOf(lowest, native.positionMs)
                waited += 5L
            }
            assertTrue(lowest >= 900.0, "a seek to 1000 made before play() on the FX thread left the playhead at $lowest\n${native.log}")
            assertTrue(player.stateFlow.value is VideoPlayerState.Completed, "ended in ${player.stateFlow.value} at ${engine.positionMs()}\n${native.log}")

            val seek: NativeCallEntry = native.log.calls(NativeCall.SEEK).single { it.tMs >= calledAt }
            assertEquals(1_000.0, seek.arg, "the native seek went to 1000\n${native.log}")
            val rates: List<NativeCallEntry> = native.rateWritesSince(0)
            assertEquals(listOf(2.0), rates.map { it.arg }, "the speed reached the native player once\n${native.log}")
            assertTrue(
                rates.single().tMs - seek.tMs >= HazardConfig.DEFAULT_SEEK_RUN_MS - ROUNDING_MS,
                "the speed was written ${rates.single().tMs - seek.tMs} ms after the seek, inside W-SEEK-RUN\n${native.log}",
            )
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    // ---- B2: the two sequences of FxThreadNudgeR8 (S13) ----

    /**
     * Twin of `a replay called from the FX thread at a new speed gives the native player one away and back to the new speed`
     * (4 trials on real media; here the clip completes at 4x, then [pauseAfterEndMs] pass, 0 to 250 ms, before the replay):
     * after `seekTo(0)`, `start()` and `setSpeed(2)` in one FX task the native rate writes are exactly 1.0 then 2.0, never
     * the replaced 4.0.
     */
    @Test
    fun aReplayFromTheFxThreadAtANewSpeedGivesOneAwayAndBackToTheNewSpeed() {
        for (pauseAfterEndMs: Long in listOf(0L, 37L, 250L)) {
            fxModel {
                withEngine {
                    completeAtFourTimes(this@fxModel)
                    advanceBy(pauseAfterEndMs)
                    val mark: Int = native.log.size
                    val calledAt: Double = nowMs()
                    onFx {
                        engine.seekTo(0L)
                        engine.start()
                        engine.setSpeed(2f)
                    }
                    advanceBy(1_000)

                    val rates: List<NativeCallEntry> = native.rateWritesSince(mark)
                    assertEquals(
                        listOf(1.0, 2.0), rates.map { it.arg },
                        "pause $pauseAfterEndMs: away and back to the NEW speed, the replaced 4.0 never given\n${native.log}",
                    )
                    for (rate: NativeCallEntry in rates) assertAt(calledAt, rate.tMs, "pause $pauseAfterEndMs: given with the play\n${native.log}")
                    assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
                    assertFalse(hazards.frozen, native.log.tail())
                    assertTrue(native.positionMs > 800.0, "the playhead moved on, stands at ${native.positionMs}\n${native.log}")
                }
            }
        }
    }

    /**
     * Twin of `a speed given on the FX thread right after a resume of a paused player replaces the pending nudge`: a speed of
     * 2 written to a loaded player that was never started, then `start()` and `setSpeed(3)` in one FX task. The start of a
     * clip standing at the start issues a native seek and a play and pends the nudge at 2; the `setSpeed` right after it
     * replaces the nudge, so the decoder gets one away-and-back to 3 and no rate change to 2.
     */
    @Test
    fun aSpeedGivenOnTheFxThreadRightAfterAResumeOfAPausedPlayerReplacesThePendingNudge() = fxModel {
        withEngine {
            engine.setSpeed(2f)
            assertEquals(2.0, inspect()?.rate, "the speed was written to the paused player")
            val mark: Int = native.log.size
            val calledAt: Double = nowMs()
            onFx {
                engine.start()
                engine.setSpeed(3f)
            }
            advanceBy(1_000)

            val rates: List<NativeCallEntry> = native.rateWritesSince(mark)
            assertEquals(listOf(1.0, 3.0), rates.map { it.arg }, "away and back to the NEW speed, no change to the replaced one\n${native.log}")
            for (rate: NativeCallEntry in rates) assertAt(calledAt, rate.tMs, "given with the play\n${native.log}")
            assertEquals(3.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    // ---- A20: the six tests of SpeedAndSeekOrderR7 ----

    /**
     * Twin of `a seek made while paused is not undone by the next play at a changed speed` (6 trials on real media): the
     * seek to 2000 (a key frame) made while paused is the playhead after the next play at 2x; the speed work must not seek
     * the native player back. The playhead never reads below 1800, the clip completes, the native seek is the only one.
     */
    @Test
    fun aSeekMadeWhilePausedIsNotUndoneByTheNextPlayAtAChangedSpeedOrder() = fxModel {
        withPlayer {
            player.play()
            advanceUntil { engine.positionMs() > 500L }
            player.pause()
            advanceBy(200)
            val mark: Int = native.log.size
            player.setPlaybackSpeed(2f)
            player.seekTo(2_000L)
            player.play()
            val lowest: Double = lowestUntilCompleted(this, 4_500L)
            assertTrue(lowest >= 1_800.0, "after a seek to 2000 while paused and a play at 2x the playhead read $lowest\n${native.log}")
            assertTrue(player.stateFlow.value is VideoPlayerState.Completed, "ended in ${player.stateFlow.value}\n${native.log}")

            val seeks: List<NativeCallEntry> = native.log.entries.drop(mark).filter { it.call == NativeCall.SEEK }
            assertEquals(listOf(2_000.0), seeks.map { it.arg }, "the only native seek is the caller's\n${native.log}")
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    /**
     * Twin of `a seek made right after play at a changed speed still wins`: speed 2 in Ready, `play()` then `seekTo(2000)`
     * from another thread (three queued tasks in call order). The seek to 2000 is the last native seek, the playhead never
     * reads below 1800 after it, and the clip completes.
     */
    @Test
    fun aSeekMadeRightAfterPlayAtAChangedSpeedStillWinsOrder() = fxModel {
        withPlayer {
            player.setPlaybackSpeed(2f)
            player.play()
            player.seekTo(2_000L)
            val lowest: Double = lowestUntilCompleted(this, 4_500L)
            assertTrue(lowest >= 1_800.0, "a seek to 2000 right after play() at 2x left the playhead at $lowest\n${native.log}")
            assertTrue(player.stateFlow.value is VideoPlayerState.Completed, "ended in ${player.stateFlow.value}\n${native.log}")
            assertEquals(2_000.0, native.log.calls(NativeCall.SEEK).last().arg, "the caller's seek is the last one\n${native.log}")
            assertEquals(2.0, hazards.effectiveRate, "the speed reached the decoder\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    /**
     * Twin of `the speed given last wins over the speed an earlier play left in the engine`: completed at 4x, then a speed
     * of 2 and `replay()` from another thread. The decoder gets one away-and-back to 2 (never to the old 4), the rate
     * property reads 2 and the playhead moves at twice normal speed.
     */
    @Test
    fun theSpeedGivenLastWinsOverTheSpeedAnEarlierPlayLeftInTheEngineOrder() = fxModel {
        withPlayer(FOUR_TIMES) {
            player.setPlaybackSpeed(4f)
            player.play()
            advanceUntil(limitMs = 8_000L, stepMs = 5L) { player.stateFlow.value is VideoPlayerState.Completed }
            val mark: Int = native.log.size
            player.setPlaybackSpeed(2f)
            player.replay()
            advanceUntil(limitMs = 3_500L, stepMs = 5L) { engine.positionMs() in 300L..2_000L }
            val from: Double = time.nativePositionMs
            advanceBy(600)
            val slope: Double = (time.nativePositionMs - from) / 600.0

            assertEquals(listOf(1.0, 2.0), native.rateWritesSince(mark).map { it.arg }, "away and back to the speed given last\n${native.log}")
            assertEquals(2.0, inspect()?.rate, "the rate property reads 2.0 after a replay at a speed changed from 4 to 2\n${native.log}")
            assertTrue(slope >= 1.99 && slope <= 2.01, "the playhead moved $slope ms per ms after a replay at a speed of 2\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    /**
     * Twin of `a pause right after a play at a speed holds the playhead and the next play moves on`: speed 4 in Ready, play,
     * pause once Playing, rest. The paused playhead does not move at all (the real class allows 150 ms), the next play
     * moves it past held + 600 within 3.5 s or completes the clip, and no window is violated.
     */
    @Test
    fun aPauseRightAfterAPlayAtASpeedHoldsThePlayheadAndTheNextPlayMovesOnOrder() = fxModel {
        withPlayer(FOUR_TIMES) {
            player.setPlaybackSpeed(4f)
            player.play()
            advanceUntil { player.stateFlow.value is VideoPlayerState.Playing }
            player.pause()
            advanceUntil { player.stateFlow.value is VideoPlayerState.Paused }
            advanceBy(300)
            val held: Double = native.positionMs
            advanceBy(500)
            assertEquals(held, native.positionMs, "a paused player's playhead moved\n${native.log}")
            assertEquals(held.toLong(), engine.positionMs(), "the engine reports the held position")
            player.play()
            val waited: Long = advanceUntil(limitMs = 3_500L, stepMs = 10L) {
                native.positionMs >= held + 600.0 || player.stateFlow.value is VideoPlayerState.Completed
            }
            assertTrue(waited <= 1_500L, "after pause at $held and a second play the playhead stood at ${native.positionMs} for $waited ms\n${native.log}")
            assertFalse(hazards.frozen, native.log.tail())
        }
    }

    /**
     * Twin of `a speed changed just after a loop wrap replaces the speed that wrap deferred`: a looping clip at 4x, driven
     * to its first wrap, then a speed of 2 after a gap of 0 to 250 ms. The wrap deferred the speed of 4 (the wrap restarts
     * the native player at normal speed); the newer speed wins, however soon it comes: [OBSERVE_AFTER_WRAP_MS] after the wrap
     * (the real class waits 1.9 s, because JavaFX restarts a looping source after about a second; the model has no such gap, so
     * its second wrap at 2x comes 1.78 s after the first and the check is made before it) the rate property reads 2, the
     * decoder plays at 2, the speed of 4 is not written after the wrap, and no rate is written within 300 ms of the
     * wrap (the wrap is a native seek).
     */
    @Test
    fun aSpeedChangedJustAfterALoopWrapReplacesTheSpeedThatWrapDeferredOrder() {
        for (gapMs: Long in listOf(0L, 50L, 150L, 250L)) {
            fxModel {
                withPlayer(FOUR_TIMES) {
                    player.setRepeatMode(RepeatMode.One)
                    player.setPlaybackSpeed(4f)
                    player.play()
                    var wrapAt: Double = Double.NaN
                    composed.loop.onWrap { wrapAt = nowMs() }
                    var steps = 0
                    while (composed.loop.cycle < 2) {
                        check(steps++ < MAX_STEPS) { "the first wrap did not come ($host)" }
                        check(step()) { "the host ran dry before the first wrap ($host)" }
                    }
                    advanceBy(gapMs)
                    val mark: Int = native.log.size
                    player.setPlaybackSpeed(2f)
                    advanceBy(OBSERVE_AFTER_WRAP_MS - gapMs)

                    assertEquals(2.0, native.rate, "gap $gapMs: the rate property reads 2.0 after a speed of 2 was given right after the wrap\n${native.log}")
                    assertEquals(2.0, hazards.effectiveRate, "gap $gapMs: the speed reached the decoder\n${native.log}")
                    val written: List<NativeCallEntry> = native.rateWritesSince(mark)
                    assertEquals(listOf(2.0), written.map { it.arg }, "gap $gapMs: the speed given last reached the decoder once\n${native.log}")
                    assertTrue(written.none { it.arg == 4.0 }, "gap $gapMs: the replaced speed of 4 was written after the wrap\n${native.log}")
                    assertTrue(
                        written.all { it.tMs - wrapAt >= HazardConfig.DEFAULT_SEEK_RUN_MS - ROUNDING_MS },
                        "gap $gapMs: a rate was written ${written.first().tMs - wrapAt} ms after the wrap\n${native.log}",
                    )
                    assertFalse(hazards.frozen, native.log.tail())
                }
            }
        }
    }

    /**
     * Twin of `a speed changed a short while after a play keeps the playhead moving at that speed` (delays 250 and 400 ms; the
     * real class also has 150 ms, which the model flags as a W-SEEK-RUN violation on the current code: a `Question:` of the
     * report, no test here until the advisor rules): a speed of 2 given that long after a plain play (inside the fresh-play window, so it is given as a nudge after the
     * decoder ran for a moment, never inside W-PLAY) keeps the playhead moving, reads 2 as the rate property and reaches the
     * decoder exactly once.
     */
    @Test
    fun aSpeedChangedAShortWhileAfterAPlayKeepsThePlayheadMovingAtThatSpeedOrder() {
        for (delayMs: Long in listOf(250L, 400L)) {
            fxModel {
                withPlayer {
                    player.play()
                    advanceBy(delayMs)
                    val at: Double = native.positionMs
                    val mark: Int = native.log.size
                    player.setPlaybackSpeed(2f)
                    val waited: Long = advanceUntil(limitMs = 3_500L, stepMs = 5L) {
                        native.positionMs >= at + 700.0 || player.stateFlow.value is VideoPlayerState.Completed
                    }
                    assertTrue(waited <= 1_000L, "delay $delayMs: the playhead stood at ${native.positionMs} from $at after a speed of 2 for $waited ms\n${native.log}")
                    assertEquals(listOf(2.0), native.rateWritesSince(mark).map { it.arg }, "delay $delayMs: the speed reached the decoder once\n${native.log}")
                    assertEquals(2.0, inspect()?.rate, "delay $delayMs: the rate property reads 2.0\n${native.log}")
                    assertEquals(2.0, hazards.effectiveRate, "delay $delayMs: the speed reached the decoder\n${native.log}")
                    assertFalse(hazards.frozen, native.log.tail())
                }
            }
        }
    }

    /** Polls the engine's playhead (and the native one) every 5 virtual ms until the player completes or [boundMs] pass; the lowest reading seen. */
    private fun lowestUntilCompleted(rig: PlayerRig, boundMs: Long): Double {
        var lowest: Double = Double.MAX_VALUE
        var waited = 0L
        // The scope is the one the test body runs in: the host is the rig's.
        val host: VirtualFxHost = rig.backend.host as VirtualFxHost
        while (waited < boundMs && rig.player.stateFlow.value !is VideoPlayerState.Completed) {
            lowest = minOf(lowest, rig.engine.positionMs().toDouble())
            host.advanceBy(5)
            waited += 5L
        }
        return lowest
    }

    // ---- S13: the order of the native calls, whoever calls ----

    /**
     * The contract of the nudge in `FxPlayback.start`, as its comment states it: whoever calls (an FX task or another
     * thread), a speed given right after `start()` replaces the speed the start pended, so the native player gets, after the
     * play, at most one away-and-back, to the speed given last, and never the replaced speed. Each sequence is a set of
     * calls made in one FX task (`onFx`, the inline path of `FxQueue.post`) or from the test thread (three queued tasks), on
     * a clip completed at 4x (a replay) or standing at the start (a first play). The decoder never freezes in any of them.
     */
    @Test
    fun everyFxThreadSequenceGivesTheNativePlayerAtMostOneAwayAndBackToTheSpeedGivenLast() {
        val sequences: List<Triple<String, Boolean, Boolean>> = buildList {
            for (fromFx: Boolean in listOf(true, false)) {
                for (afterCompletion: Boolean in listOf(true, false)) {
                    add(Triple("from ${if (fromFx) "the FX thread" else "another thread"} " +
                        if (afterCompletion) "after a completion at 4x: seekTo(0), start, setSpeed(2)" else "on a fresh clip with 3x set: start, setSpeed(2)", fromFx, afterCompletion))
                }
            }
        }
        for ((label: String, fromFx: Boolean, afterCompletion: Boolean) in sequences) {
            fxModel {
                withEngine {
                    if (afterCompletion) {
                        completeAtFourTimes(this@fxModel)
                    } else {
                        engine.setSpeed(3f)
                        assertEquals(3.0, inspect()?.rate, "the speed was written to the paused player")
                    }
                    val mark: Int = native.log.size
                    val calls: () -> Unit = {
                        if (afterCompletion) engine.seekTo(0L)
                        engine.start()
                        engine.setSpeed(2f)
                    }
                    if (fromFx) onFx(calls) else calls()
                    advanceBy(1_500)

                    val rates: List<Double> = native.rateWritesSince(mark).map { checkNotNull(it.arg) }
                    assertEquals(listOf(1.0, 2.0), rates, "$label: one away-and-back to the speed given last\n${native.log}")
                    assertEquals(2.0, hazards.effectiveRate, "$label: the speed reached the decoder\n${native.log}")
                    assertFalse(hazards.frozen, "$label\n${native.log.tail()}")
                }
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI: Double = 1_000_000.0
        const val NANOS_PER_MILLI_LONG: Long = 1_000_000L

        /** The virtual clock is in nanoseconds and the log in milliseconds, with rounding at the 7th decimal. */
        const val TIME_TOLERANCE_MS: Double = 0.01

        /** The virtual clock is in nanoseconds and the log in milliseconds: 300 ms can read 299.99999999999994. */
        const val ROUNDING_MS: Double = 0.001

        /** The shared player clamps a speed to 3 by default; the real classes ask for 4 and read 3, these tests really give 4. */
        val FOUR_TIMES: VideoPlayerConfig = VideoPlayerConfig(maxPlaybackSpeed = 4f)

        const val PREPARE_BOUND_MS: Long = 10_000L
        const val MAX_STEPS: Int = 100_000

        /** How long after the first wrap the loop case is judged: after the deferred speed is given (about 550 ms), before the second wrap (1775 ms). */
        const val OBSERVE_AFTER_WRAP_MS: Long = 1_500L
    }
}
