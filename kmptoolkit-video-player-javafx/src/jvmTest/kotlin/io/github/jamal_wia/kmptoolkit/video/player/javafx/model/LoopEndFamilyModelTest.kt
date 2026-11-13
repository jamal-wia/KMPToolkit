package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import java.util.IdentityHashMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * The loop and end family on the model tier (task J12): the deterministic F twins of the real-media loop, completion,
 * replay, frame-cap and dispose classes, on the composed, watched scripted player ([composeAll]) in virtual time. Every
 * scenario runs through the real `JavaFxVideoEngine` and `FxPlayback` (and, where the real class does, the shared player
 * over [NeverDispatcher]); the end-of-test check of [fxModel] fails any scenario that violates a fragile window or that
 * the dispose part (B12) recorded a violation for, whatever it asserts itself.
 *
 * Real-media twin of each group, and what the F test asserts beyond it (never less):
 * - A14 (`JavaFxLoopSpeedR5AdversarialTest`, a speed of four at the loop point): the real class asserts that the second
 *   cycle moves the playhead at more than 2.5 ms per ms between 300 and 2300 ms of the clip, measured on the engine's reading
 *   every 15 ms for 9 s. The twin asserts that, and on the call log that the speed is given back in EVERY cycle, exactly
 *   once per cycle, no sooner than the wrap window plus the published hold plus the settle movement after the wrap, and that
 *   the decoder's own slope in that window is that of the speed (the model sees the decoder, real media only the engine).
 *   It runs under B8 (the loop wrap of [LoopModel]) with the default profile. Under the `LOAD` profile of B3 over 12 seeds
 *   read at publication ([SampleRead.AT_PUBLISH], ruling 2.2, for a class that failed 4 of 12 alone) the real class's slope
 *   threshold is NOT asserted, because the model reproduces the flake without any freeze or lost speed: the return of the speed
 *   is driven by the playhead ticks and is late when they are (see the KDoc of the LOAD test); the twin asserts that the speed
 *   returns at exactly the tick the rule names, in every cycle.
 * - The wrap deferral (the `deferRate` and `applyDeferredRate` contract, `SpeedAtLoopWrapModelTest` for a speed of 1): at
 *   speeds 2, 3, 4 and 8 the property is put to 1.0 at the wrap and the speed is written once after it, outside W-WRAP.
 * - Completion and replay (`JavaFxVideoEngineTest`: playing to the end completes once, start after completion plays from the
 *   beginning, a seek after completion stays paused, looping restarts without completing): on the call log with exact
 *   virtual timestamps. The start after a completion at a speed other than 1 is asserted at 2x with no seek in between, the
 *   shape of `EngineVideoPlayer.staleCompletionLocked` and the one the M-J12 mutant of the J9 review changes.
 * - Position edges (`JavaFxPositionEdgesR10AdversarialTest`): all five tests, over the shared player. The playhead is the
 *   engine's reading every 25 ms (the poll interval the real class configures); `NeverDispatcher` keeps the poller
 *   silent, so the player's own position flow is read only where a transition writes it (the completion).
 * - The frame cap (`JavaFxFramesAndRateR5AdversarialTest`, the cap case): exactly `cap` snapshots per virtual second on the
 *   16.67 ms pulse, for the caps that divide it.
 * - The dispose twin (`JavaFxReleaseAtLoopPointAdversarialTest`, which stays real-media): a release at every moment around the
 *   loop point with the late end of media that OpenJFX runs after `dispose()` (B12). The log order is asserted (`cycleCount = 1`
 *   and `clearEndOfMedia` before the native dispose), the dispose part records no violation, and the late end really ran.
 * - Superseded seeks (`JavaFxTransportBacklogAdversarialTest`, commit da977586): a burst queued behind the FX thread issues one
 *   native seek, for the newest.
 *
 * Nothing sleeps and nothing reads real time except the hang bound of [prepare]; every random choice is a seeded [Random]
 * and the seed is in every failure message.
 */
@OptIn(ToolkitInternalApi::class)
class LoopEndFamilyModelTest {

    // ---- the rig ----

    private class Rig(
        val engine: JavaFxVideoEngine,
        val backend: ComposedBackend,
        val listener: TimedListener,
        val player: VideoPlayer?,
    ) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
        val hazards: HazardModel get() = composed.hazards
        val time: TimeModel get() = composed.time
        val loop: LoopModel get() = composed.loop
    }

    /** A listener that stamps every event with the virtual instant it arrived at, in milliseconds. */
    private class TimedListener(private val nowMs: () -> Double) : VideoPlaybackEngineListener {
        val completedAtMs: MutableList<Double> = mutableListOf()
        val failures: MutableList<Throwable> = mutableListOf()

        override fun onCompleted() {
            completedAtMs += nowMs()
        }

        override fun onFailed(cause: Throwable) {
            failures += cause
        }

        override fun onBufferingChanged(isBuffering: Boolean) = Unit

        override fun onVideoSizeChanged(size: VideoSize?) = Unit
    }

    /** An engine-level rig: the engine over a composed backend, a stamping listener, loaded. */
    private fun FxModelScope.engineRig(
        timeScript: TimeScript = TimeScript(),
        clip: ClipModel = ClipModel(),
        maxFrameRate: Int = JavaFxVideoEngine.DEFAULT_MAX_FRAME_RATE,
        lateEndOfMedia: Boolean = false,
        lateEndIgnoresClearedHandler: Boolean = false,
        lateStatus: NativeStatus? = null,
        body: Rig.() -> Unit,
    ) {
        val backend: ComposedBackend = composeAll(
            ScriptedBackend(host, clip),
            timeScript,
            lateEndOfMedia = lateEndOfMedia,
            lateEndIgnoresClearedHandler = lateEndIgnoresClearedHandler,
            lateStatus = lateStatus,
        )
        val engine = JavaFxVideoEngine(runtime = {}, maxFrameRate = maxFrameRate, backend = { backend })
        val listener = TimedListener { nowMs() }
        engine.setListener(listener)
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend, listener, null).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    /** [FxModelScope.load] for the shared player: drives the host while `prepare` runs, bounded in real time. */
    private fun FxModelScope.prepare(player: VideoPlayer, source: VideoSource, boundMs: Long = PREPARE_BOUND_MS) {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
        val deadline: Long = System.nanoTime() + boundMs * NANOS_PER_MILLI_LONG
        runBlocking {
            val preparing = async { player.prepare(source) }
            while (!preparing.isCompleted) {
                host.runUntilIdle()
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
                if (!preparing.isCompleted && System.nanoTime() - deadline > 0L) {
                    preparing.cancel()
                    throw AssertionError("prepare did not finish within $boundMs ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            preparing.await()
        }
    }

    /**
     * A rig over the shared player (on [NeverDispatcher]) with the collectors of the real position-edge class: every state
     * the player's `stateFlow` takes, from Idle, in order. The collector is Unconfined and started undispatched, so a state
     * is recorded in the instant it is written.
     */
    private fun FxModelScope.playerRig(
        positionIntervalMs: Long = POSITION_INTERVAL_MS,
        body: PlayerRig.() -> Unit,
    ) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        val player: VideoPlayer = videoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = positionIntervalMs))
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val states: MutableList<VideoPlayerState> = mutableListOf()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { states += it } }
        try {
            prepare(player, VideoSource.Asset(TestClip.ASSET))
            PlayerRig(this, engine, backend, player, states).body()
        } finally {
            scope.cancel()
            player.release()
            advanceBy(50)
        }
    }

    private class PlayerRig(
        val scope: FxModelScope,
        val engine: JavaFxVideoEngine,
        val backend: ComposedBackend,
        val player: VideoPlayer,
        val states: List<VideoPlayerState>,
    ) {
        /** The engine's reading of the playhead at every sample since the last [clearPositions]. */
        val positions: MutableList<Long> = mutableListOf()
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player

        fun clearPositions() = positions.clear()

        /** Advances by [totalMs] in steps of [stepMs], reading the engine's playhead after each step. */
        fun advanceSampling(totalMs: Long, stepMs: Long = POSITION_INTERVAL_MS) {
            var elapsed = 0L
            while (elapsed < totalMs) {
                val step: Long = minOf(stepMs, totalMs - elapsed)
                scope.advanceBy(step)
                elapsed += step
                positions += engine.positionMs()
            }
        }

        fun completions(): Int = states.count { it is VideoPlayerState.Completed }

        fun stateNames(): List<String> = states.map { it::class.simpleName.orEmpty() }

        /** The state names with consecutive repeats collapsed (position-only updates repeat a state). */
        fun stateSequence(): List<String> = stateNames().fold(mutableListOf()) { acc, name ->
            if (acc.lastOrNull() != name) acc += name
            acc
        }
    }

    /** Runs one named case in its own model; returns the failure of the case or of the end-of-test checks, or null. */
    private fun runCase(name: String, body: FxModelScope.() -> Unit): String? {
        var failure: Throwable? = null
        try {
            fxModel(block = body)
        } catch (t: Throwable) {
            failure = t
        }
        return failure?.let { "[$name] ${it.message?.take(MESSAGE_LIMIT)}" }
    }

    private fun assertNoFailures(failures: List<String>, total: Int, what: String) {
        assertEquals(emptyList(), failures, "${failures.size} of $total $what failed")
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / NANOS_PER_MILLI

    /** The virtual clock is in nanoseconds and the log in milliseconds: a delay of 300 ms can read 299.99999999999994. */
    private fun assertAt(expected: Double, actual: Double, message: String) =
        assertEquals(expected, actual, ROUNDING_MS, message)

    // ---- A14: a speed of four survives the loop point ----

    /** The samples one run of the looping clip at a speed of four leaves. */
    private class LoopRun(
        /** Where the engine read the playhead, at every 15 ms: (ms since the start, playhead), by cycle of the clip. */
        val engineCycles: List<List<Pair<Long, Long>>>,
        /** The decoder's own playhead at the same instants, by cycle. */
        val decoderCycles: List<List<Pair<Long, Double>>>,
        /** The instants of the wraps, in virtual milliseconds. */
        val wrapsAtMs: List<Double>,
    )

    /**
     * Plays the looping clip at [speed], as the real class does: the speed before the start, the engine read every 15 ms for
     * [RUN_MS] of virtual time, a new cycle whenever the engine's reading falls by more than a second.
     */
    private fun FxModelScope.runLooping(rig: Rig, speed: Float, runMs: Long): LoopRun {
        val wraps: MutableList<Double> = mutableListOf()
        rig.loop.onWrap { wraps += nowMs() }
        rig.engine.setLooping(true)
        rig.engine.setSpeed(speed)
        rig.engine.start()
        val engineCycles: MutableList<MutableList<Pair<Long, Long>>> = mutableListOf(mutableListOf())
        val decoderCycles: MutableList<MutableList<Pair<Long, Double>>> = mutableListOf(mutableListOf())
        var last = 0L
        var lastDecoder = 0.0
        var elapsed = 0L
        while (elapsed < runMs) {
            advanceBy(SAMPLE_STEP_MS)
            elapsed += SAMPLE_STEP_MS
            val position: Long = rig.engine.positionMs()
            if (position + 1_000L < last) engineCycles.add(mutableListOf())
            last = position
            engineCycles.last() += elapsed to position
            val decoder: Double = rig.time.nativePositionMs
            if (decoder + 1_000.0 < lastDecoder) decoderCycles.add(mutableListOf())
            lastDecoder = decoder
            decoderCycles.last() += elapsed to decoder
        }
        return LoopRun(engineCycles, decoderCycles, wraps)
    }

    /** The slope over the samples whose playhead lies between [from] and [to], in clip ms per real ms; NaN when there are fewer than two. */
    private fun <T : Number> slope(samples: List<Pair<Long, T>>, from: Double, to: Double): Double {
        val window: List<Pair<Long, T>> = samples.filter { it.second.toDouble() in from..to }
        if (window.size < 2) return Double.NaN
        return (window.last().second.toDouble() - window.first().second.toDouble()) /
            (window.last().first - window.first().first).toDouble()
    }

    /**
     * The speed is given back in every cycle, exactly once, outside the wrap window: after each wrap, one effective write
     * of [speed] to the decoder, no sooner than W-WRAP plus the published hold plus the settle movement of 200 ms at the
     * normal speed, and before the next wrap.
     */
    private fun assertSpeedGivenBackEveryCycle(rig: Rig, speed: Float, wrapsAtMs: List<Double>, label: String) {
        assertTrue(wrapsAtMs.size >= 2, "$label: only ${wrapsAtMs.size} wrap(s) in the run\n${rig.native.log.tail()}")
        val writes: List<NativeCallEntry> = rig.native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.arg == speed.toDouble() }
        val minGapMs: Double = LoopModel.DEFAULT_PUBLISH_HOLD_MS + LOOP_SETTLE_MS
        for ((index: Int, wrapAt: Double) in wrapsAtMs.withIndex()) {
            val until: Double = wrapsAtMs.getOrNull(index + 1) ?: Double.MAX_VALUE
            val inCycle: List<NativeCallEntry> = writes.filter { it.tMs >= wrapAt && it.tMs < until }
            if (until == Double.MAX_VALUE && inCycle.isEmpty()) continue // the run ended before the speed was due again
            assertEquals(1, inCycle.size, "$label: wrap ${index + 1} at $wrapAt: the speed was written ${inCycle.size} times in the cycle\n${rig.native.log}")
            val gap: Double = inCycle.single().tMs - wrapAt
            assertTrue(
                gap >= minGapMs - ROUNDING_MS,
                "$label: wrap ${index + 1}: the speed was written $gap ms after the wrap, inside the hold and the settle movement\n${rig.native.log}",
            )
        }
    }

    @Test
    fun aSpeedOfFourSurvivesTheLoopPointOfALoopingSource() = fxModel {
        engineRig {
            val run: LoopRun = runLooping(this, 4f, RUN_MS)
            assertTrue(run.engineCycles.size >= 3, "the source wrapped only ${run.engineCycles.size - 1} times in 9 s\n${native.log.tail()}")
            val engineSlope: Double = slope(run.engineCycles[1], 300.0, 2_300.0)
            assertTrue(!engineSlope.isNaN(), "the second cycle had no samples between 300 ms and 2300 ms: ${run.engineCycles[1].size}")
            assertTrue(engineSlope > 2.5, "the second cycle at a speed of 4 moved the playhead $engineSlope ms per ms: the speed was lost at the loop point")
            val decoderSlope: Double = slope(run.decoderCycles[1], 300.0, 2_300.0)
            assertTrue(decoderSlope > 2.5, "the decoder of the second cycle moved at $decoderSlope ms per ms\n${native.log}")
            assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
            assertSpeedGivenBackEveryCycle(this, 4f, run.wrapsAtMs, "default profile")
            // The first cycle of the run, exactly: the wrap at 866.67 ms is the 750 ms the clip takes at 4x after the play at
            // 116.67; the 1.0 goes to the property at the wrap, the speed 550 ms later (the 300 ms hold, then two samples of
            // 100 ms and the movement of 200 ms it needs).
            val firstWrap: Double = run.wrapsAtMs.first()
            val away: NativeCallEntry = native.log.calls(NativeCall.SET_RATE).first { it.tMs >= firstWrap }
            assertAt(firstWrap, away.tMs, "the property was not put to the normal speed at the wrap\n${native.log}")
            assertEquals(1.0, away.arg, "the first write after the wrap was not the 1.0\n${native.log}")
            val back: NativeCallEntry = native.log.calls(NativeCall.SET_RATE).first { it.tMs >= firstWrap && it.arg == 4.0 }
            assertAt(550.0, back.tMs - firstWrap, "the speed came ${back.tMs - firstWrap} ms after the first wrap\n${native.log}")
        }
    }

    /**
     * The same scenario under the `LOAD` profile of B3 (seeded 50-400 ms publication delays, a tenth of the samples dropped),
     * read at publication as JavaFX does, over 12 seeds: ruling 2.2 for a real class that failed 4 of 12 alone.
     *
     * What this twin asserts is what the code promises, not the real class's slope threshold. The return of the speed at a
     * wrap is driven by the ticks of the playhead (`applyDeferredRate`): the first tick after the published hold sets the
     * reference, and the speed is written at the first later tick that is 200 ms of the clip away from it. Under load those
     * ticks are late and uneven, so the speed comes back 520-1160 ms after the wrap instead of 550 ms (measured over 24 seeds
     * and 7 cycles each), and the slope of the window 300..2300 of the second cycle drops to 2.0-2.5, below the real class's
     * 2.5, in 8 of these 12 seeds; the decoder never freezes and never stays at the normal speed. So the twin asserts, in every
     * cycle of every seed: the write of the speed comes at EXACTLY the instant of the tick the rule above names (computed from
     * the model's publication log), once per cycle, after the hold and the settle movement; the monitor sees no violation. The
     * lateness is accepted behaviour (D-93, platform notes): it explains the real class's flake and is not a freeze; the bound
     * below keeps it within the documented 1.2 s.
     */
    @Test
    fun aSpeedOfFourIsGivenBackAtTheTickTheRuleNamesAfterEveryLoopPointUnderLoadReadAtPublication() {
        val failures: List<String> = (0 until LOAD_SEEDS).mapNotNull { index ->
            val seed: Long = LOAD_SEED + index
            runCase("load seed $seed") {
                engineRig(TimeScript().load(seed = seed, read = SampleRead.AT_PUBLISH)) {
                    val run: LoopRun = runLooping(this, 4f, RUN_MS)
                    assertTrue(run.engineCycles.size >= 3, "the source wrapped only ${run.engineCycles.size - 1} times in 9 s\n${native.log.tail()}")
                    assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
                    assertSpeedGivenBackEveryCycle(this, 4f, run.wrapsAtMs, "load seed $seed")
                    val writes: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.arg == 4.0 }
                    var checked = 0
                    for ((cycle: Int, wrapAt: Double) in run.wrapsAtMs.withIndex()) {
                        val until: Double = run.wrapsAtMs.getOrNull(cycle + 1) ?: Double.MAX_VALUE
                        // The ticks of the cycle: the publications that ran after the hold, in order.
                        val ticks: List<TimeModel.Publication> = time.publications
                            .filter { it.runMs >= wrapAt + LoopModel.DEFAULT_PUBLISH_HOLD_MS - ROUNDING_MS && it.runMs < until }
                        val reference: TimeModel.Publication = ticks.firstOrNull() ?: continue
                        val due: TimeModel.Publication = ticks.firstOrNull { it.value - reference.value >= LOOP_SETTLE_MS } ?: continue
                        val written: NativeCallEntry = writes.first { it.tMs >= wrapAt && it.tMs < until }
                        assertAt(due.runMs, written.tMs, "wrap ${cycle + 1} at $wrapAt: the speed was written at ${written.tMs}, the rule names the tick at ${due.runMs}\n${native.log}")
                        checked++
                    }
                    assertTrue(checked >= 5, "only $checked cycles could be checked against the rule\n${native.log.tail()}")
                }
            }
        }
        assertNoFailures(failures, LOAD_SEEDS, "load seeds")
    }

    // ---- the wrap deferral ----

    @Test
    fun aSpeedDeferredAtALoopWrapIsPutAwayAtTheWrapAndGivenOnceOutsideTheWrapWindow() {
        val failures: List<String> = listOf(2f, 3f, 4f, 8f).mapNotNull { speed ->
            runCase("speed $speed") {
                engineRig {
                    val run: LoopRun = runLooping(this, speed, WRAP_RUN_MS)
                    assertTrue(run.wrapsAtMs.size >= 2, "only ${run.wrapsAtMs.size} wrap(s) in the run at $speed\n${native.log.tail()}")
                    for ((index: Int, wrapAt: Double) in run.wrapsAtMs.withIndex()) {
                        val away: NativeCallEntry = native.log.calls(NativeCall.SET_RATE).first { it.tMs >= wrapAt }
                        assertAt(wrapAt, away.tMs, "wrap ${index + 1}: no write at the wrap instant\n${native.log}")
                        assertEquals(1.0, away.arg, "wrap ${index + 1}: the write at the wrap was not the 1.0 of the away step\n${native.log}")
                    }
                    assertSpeedGivenBackEveryCycle(this, speed, run.wrapsAtMs, "speed $speed")
                    assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
                    // Between a wrap and the write of the speed the decoder plays at the normal speed.
                    val firstWrap: Double = run.wrapsAtMs.first()
                    val back: NativeCallEntry = native.log.calls(NativeCall.SET_RATE).first { it.tMs >= firstWrap && it.arg == speed.toDouble() }
                    assertTrue(back.positionMs >= LOOP_SETTLE_MS - ROUNDING_MS, "the speed was given at ${back.positionMs} ms of the clip, before the settle movement\n${native.log}")
                }
            }
        }
        assertNoFailures(failures, 4, "speeds")
    }

    // ---- a looping source restarts without completing ----

    @Test
    fun aLoopingSourceWrapsWithoutCompletingAndKeepsProducingFrames() = fxModel {
        engineRig {
            engine.setLooping(true)
            engine.seekTo(2_500L)
            engine.start()
            var waited = 0L
            while (loop.cycle < 2 && waited < 6_000L) {
                advanceBy(10)
                waited += 10
            }
            assertEquals(2, loop.cycle, "the source never wrapped\n${native.log.tail()}")
            // Past the end once: the playhead wraps around to the start (the real class waits for a reading in 1..1500).
            val before: VideoFrame? = engine.frames.value
            advanceBy(300)
            assertTrue(engine.positionMs() in 1L..1_500L, "the engine reads ${engine.positionMs()} after the wrap\n${native.log.tail()}")
            assertEquals(emptyList(), listener.completedAtMs, "a looping source completed\n${native.log.tail()}")
            // Still decoding pictures after the wrap.
            var frame: VideoFrame? = engine.frames.value
            var frameWaited = 0L
            while (frame === before && frameWaited < 500L) {
                advanceBy(5)
                frameWaited += 5
                frame = engine.frames.value
            }
            assertTrue(frame !== before, "no new frame after the wrap\n${native.log.tail()}")
            // A second and a third cycle: still no completion, the end of media comes every cycle and is not an end.
            advanceBy(6_000)
            assertTrue(loop.cycle >= 4, "the source wrapped only ${loop.cycle - 1} times in 6.3 s\n${native.log.tail()}")
            assertEquals(emptyList(), listener.completedAtMs, "a looping source completed after ${loop.cycle - 1} wraps\n${native.log.tail()}")
            assertEquals(1, native.log.calls(NativeCall.SEEK).size, "FxPlayback issued a seek of its own at a wrap\n${native.log}")
            assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
        }
    }

    // ---- completion and replay (JavaFxVideoEngineTest) ----

    /** Seeks to [targetMs], lets the seek land and returns where the decoder stands (B5: the key frame at or before it). */
    private fun FxModelScope.seekAndLand(rig: Rig, targetMs: Long): Double {
        rig.engine.seekTo(targetMs)
        advanceBy(10)
        return rig.time.nativePositionMs
    }

    @Test
    fun playingToTheEndCompletesOnceAtTheExactInstantAndHoldsTheDuration() = fxModel {
        engineRig {
            val landed: Double = seekAndLand(this, 2_600L)
            assertAt(2_000.0, landed, "the seek to 2600 did not land on the key frame at 2000\n${native.log}")
            val startAt: Double = nowMs()
            engine.start()
            advanceBy(0)
            val playAt: Double = native.log.calls(NativeCall.PLAY).single().tMs
            assertAt(startAt, playAt, "the play did not follow the start at once\n${native.log}")
            advanceBy(2_000)
            assertEquals(1, listener.completedAtMs.size, "completions: ${listener.completedAtMs}\n${native.log}")
            // The decoder runs the 1000 ms from the key frame to the end at the normal speed, from the play.
            val duration: Double = TestClip.DURATION_MS.toDouble()
            assertAt(playAt + (duration - landed), listener.completedAtMs.single(), "the completion came at the wrong instant\n${native.log}")
            assertEquals(TestClip.DURATION_MS, engine.positionMs(), "the engine does not read the duration at the end\n${native.log}")
            assertEquals(TestClip.DURATION_MS, engine.durationMs())
            // JavaFX leaves its status at PLAYING after the last cycle: the engine pauses it, once, at the end.
            val pauses: List<NativeCallEntry> = native.log.calls(NativeCall.PAUSE).filter { it.tMs >= playAt }
            assertEquals(1, pauses.size, "the engine did not pause the native player once at the end\n${native.log}")
            assertAt(listener.completedAtMs.single(), pauses.single().tMs, "the native pause did not come with the completion\n${native.log}")
            assertEquals(NativeStatus.PAUSED, native.status, "the native player is not paused after the completion")
            assertEquals(1, native.log.calls(NativeCall.PLAY).size, "a second play was issued\n${native.log}")
            assertEquals(1, native.log.calls(NativeCall.SEEK).size, "a seek was issued after the end\n${native.log}")
            assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
        }
    }

    @Test
    fun aStartAfterCompletionPlaysFromTheBeginningWithOneSeekAndOnePlay() {
        val failures: List<String> = listOf(0L, 7L, 50L, 300L, 1_000L).mapNotNull { gapMs ->
            runCase("start $gapMs ms after the completion") {
                engineRig {
                    seekAndLand(this, 2_700L)
                    engine.start()
                    var waited = 0L
                    while (listener.completedAtMs.isEmpty() && waited < 3_000L) {
                        advanceBy(5)
                        waited += 5
                    }
                    assertEquals(1, listener.completedAtMs.size, "the first play never completed\n${native.log.tail()}")
                    advanceBy(gapMs)
                    val startAt: Double = nowMs()
                    engine.start()
                    advanceBy(0)
                    val seeks: List<NativeCallEntry> = native.log.calls(NativeCall.SEEK).filter { it.tMs >= startAt }
                    assertEquals(1, seeks.size, "the start after the end issued ${seeks.size} native seeks\n${native.log}")
                    assertEquals(0.0, seeks.single().arg, "the start after the end sought to ${seeks.single().arg}\n${native.log}")
                    assertEquals(1, native.log.calls(NativeCall.PLAY).count { it.tMs >= startAt }, "the start after the end did not play once\n${native.log}")
                    advanceBy(500)
                    // The real class: a reading in 1..1000 after the start; the decoder runs the 500 ms at the normal speed.
                    assertTrue(engine.positionMs() in 1L..1_000L, "the engine reads ${engine.positionMs()} 500 ms after the start\n${native.log}")
                    assertAt(500.0, time.nativePositionMs, "the decoder stands at ${time.nativePositionMs} 500 ms after the start\n${native.log}")
                    assertEquals(1, listener.completedAtMs.size, "a second completion came 500 ms after the start\n${native.log}")
                    assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
                }
            }
        }
        assertNoFailures(failures, 5, "gaps")
    }

    /**
     * A play that completed at a speed other than 1, then the engine's `start()` with NO `seekTo` in between: the shape of
     * `EngineVideoPlayer.staleCompletionLocked` (the J9 review, item 3, M-J12). The end branch of `start()` issues the native
     * seek to the start and marks it as the seek of this play; the speed then rides the pending nudge. Asserted: exactly one
     * native SEEK after the start, to the start; the decoder moves at the speed from the play on and stays at most 2300 ms
     * over 900 ms (a replay that went back to the end reads 3000 there); no second completion; the speed reached the decoder.
     * Without the mark the start would seek again, to the playhead the engine still publishes, which is the end.
     */
    @Test
    fun aStartAfterACompletionAtAnotherSpeedMakesOneNativeSeekAndPlaysFromTheBeginning() {
        val failures: List<String> = listOf(2f, 1.5f).flatMap { speed ->
            listOf(0L, 7L, 50L, 300L, 1_000L).mapNotNull { gapMs ->
                runCase("speed $speed start $gapMs ms after the completion") {
                    engineRig {
                        engine.setSpeed(speed)
                        seekAndLand(this, 1_000L)
                        engine.start()
                        var waited = 0L
                        while (listener.completedAtMs.isEmpty() && waited < 3_000L) {
                            advanceBy(5)
                            waited += 5
                        }
                        assertEquals(1, listener.completedAtMs.size, "the first play never completed at $speed\n${native.log.tail()}")
                        advanceBy(gapMs)
                        val startAt: Double = nowMs()
                        engine.start()
                        advanceBy(0)
                        val seeks: List<NativeCallEntry> = native.log.calls(NativeCall.SEEK).filter { it.tMs >= startAt }
                        assertEquals(1, seeks.size, "the start after the end issued ${seeks.size} native seeks\n${native.log}")
                        assertEquals(0.0, seeks.single().arg, "the start after the end sought to ${seeks.single().arg}\n${native.log}")
                        var highest = 0.0
                        var elapsed = 0L
                        while (elapsed < 900L) {
                            advanceBy(5)
                            elapsed += 5
                            highest = maxOf(highest, time.nativePositionMs)
                        }
                        assertTrue(highest <= 2_300.0, "the decoder reached $highest ms in 900 ms at a speed of $speed\n${native.log}")
                        assertAt(900.0 * speed, time.nativePositionMs, "the decoder stands at ${time.nativePositionMs} 900 ms after the start at $speed\n${native.log}")
                        assertEquals(speed.toDouble(), hazards.effectiveRate, "the speed never reached the decoder\n${native.log}")
                        assertEquals(1, listener.completedAtMs.size, "a second completion came within 900 ms of the start\n${native.log}")
                        assertEquals(1, native.log.calls(NativeCall.SEEK).count { it.tMs >= startAt }, "the replay seeked again\n${native.log}")
                        assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
                    }
                }
            }
        }
        assertNoFailures(failures, 10, "start-after-completion cases")
    }

    @Test
    fun aSeekAfterCompletionStaysPausedAndAStartResumesFromTheTarget() = fxModel {
        engineRig {
            seekAndLand(this, 2_600L)
            engine.start()
            var waited = 0L
            while (listener.completedAtMs.isEmpty() && waited < 3_000L) {
                advanceBy(5)
                waited += 5
            }
            assertEquals(1, listener.completedAtMs.size, "never completed\n${native.log.tail()}")
            // JavaFX itself leaves its status at PLAYING here; the engine must have paused it.
            advanceBy(StatusScript.DEFAULT_STATUS_LATENCY_MS.toLong() + 5)
            assertEquals(NativeStatus.PAUSED, native.status, "the native player was not paused at the end")
            // The player reports Paused after this seek; the engine must not quietly play on from here.
            val seekAt: Double = nowMs()
            engine.seekTo(1_000L)
            advanceBy(100)
            assertTrue(engine.positionMs() in 900L..1_100L, "the engine reads ${engine.positionMs()} after the seek\n${native.log}")
            advanceBy(700)
            assertTrue(engine.positionMs() in 900L..1_100L, "moved on to ${engine.positionMs()}\n${native.log}")
            assertEquals(NativeStatus.PAUSED, native.status)
            assertEquals(emptyList(), native.log.calls(NativeCall.PLAY).filter { it.tMs >= seekAt }, "a play followed the seek\n${native.log}")
            assertEquals(1, listener.completedAtMs.size, "a second completion: ${listener.completedAtMs}\n${native.log}")
            // And start() resumes from the seek target, not from the beginning.
            engine.start()
            advanceBy(400)
            assertTrue(engine.positionMs() >= 1_300L, "the engine reads ${engine.positionMs()} 400 ms after the start\n${native.log}")
            assertEquals(emptyList(), native.log.calls(NativeCall.SEEK).filter { it.tMs > seekAt }, "the start seeked again\n${native.log}")
            assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
        }
    }

    // ---- position edges (JavaFxPositionEdgesR10AdversarialTest) ----

    private fun PlayerRig.assertMonotone(label: String) {
        for (index: Int in 1 until positions.size) {
            assertTrue(positions[index] >= positions[index - 1], "$label: position went back from ${positions[index - 1]} to ${positions[index]} (index $index of $positions)")
        }
        assertTrue(positions.all { it in 0L..TestClip.DURATION_MS }, "$label: a position outside 0..${TestClip.DURATION_MS}: $positions")
    }

    private fun PlayerRig.awaitCompleted(count: Int, timeoutMs: Long = 9_000L) {
        var waited = 0L
        while (completions() < count && waited < timeoutMs) {
            advanceSampling(POSITION_INTERVAL_MS)
            waited += POSITION_INTERVAL_MS
        }
        assertTrue(completions() >= count, "Completed number $count never arrived; states ${stateNames()}\n${native.log.tail()}")
    }

    @Test
    fun anUninterruptedPlayNeverMovesThePlayheadBackAndEndsExactlyAtTheDurationWithOneCompletion() = fxModel {
        playerRig {
            player.play()
            awaitCompleted(1)
            advanceSampling(300)
            assertMonotone("plain play")
            assertEquals(TestClip.DURATION_MS, player.playbackPositionFlow.value)
            assertEquals(TestClip.DURATION_MS, engine.positionMs())
            assertEquals(1, completions(), "${stateNames()}")
            val completed: VideoPlayerState.Completed = states.last { it is VideoPlayerState.Completed } as VideoPlayerState.Completed
            assertEquals(TestClip.DURATION_MS, completed.duration)
            // The clip is 3000 ms at the normal speed from the play: the completion is not early and not late.
            val playAt: Double = native.log.calls(NativeCall.PLAY).first().tMs
            assertEquals(1, native.log.calls(NativeCall.PLAY).size)
            assertTrue(positions.first() <= 25L, "the first reading ${positions.first()} 25 ms after the play")
            assertTrue(playAt + TestClip.DURATION_MS <= nowMsOf(this), "the run ended before the clip could have")
        }
    }

    private fun nowMsOf(rig: PlayerRig): Double = rig.scope.host.elapsedNanos / NANOS_PER_MILLI

    @Test
    fun pauseAndResumeAtTheNormalSpeedKeepThePlayheadMonotoneAndThePausedPositionStill() = fxModel {
        playerRig {
            player.play()
            var waited = 0L
            while (engine.positionMs() <= 300L && waited < 5_000L) {
                advanceSampling(POSITION_INTERVAL_MS)
                waited += POSITION_INTERVAL_MS
            }
            assertTrue(engine.positionMs() > 300L, "never advanced\n${native.log.tail()}")
            repeat(3) {
                player.pause()
                val paused: VideoPlayerState = player.stateFlow.value
                assertTrue(paused is VideoPlayerState.Paused, "$paused")
                val at: Long = paused.currentPosition
                advanceSampling(300)
                assertEquals(at, (player.stateFlow.value as VideoPlayerState.Paused).currentPosition, "a paused playhead moved")
                assertEquals(at, player.playbackPositionFlow.value)
                assertEquals(at, engine.positionMs(), "the engine's playhead moved while paused")
                player.play()
                advanceSampling(250)
            }
            awaitCompleted(1, 12_000L)
            assertMonotone("pause/resume")
            assertEquals(1, completions())
            assertTrue(!composed.hazards.frozen, "the decoder froze\n${native.log.tail()}")
        }
    }

    @Test
    fun speedChangesWhilePlayingKeepThePlayheadMonotone() = fxModel {
        playerRig {
            player.play()
            var waited = 0L
            while (engine.positionMs() <= 200L && waited < 5_000L) {
                advanceSampling(POSITION_INTERVAL_MS)
                waited += POSITION_INTERVAL_MS
            }
            assertTrue(engine.positionMs() > 200L, "never advanced\n${native.log.tail()}")
            for (speed: Float in listOf(2f, 0.5f, 1f)) {
                player.setPlaybackSpeed(speed)
                advanceSampling(300)
            }
            awaitCompleted(1, 12_000L)
            assertMonotone("speed changes")
            assertEquals(1, completions())
            assertTrue(!composed.hazards.frozen, "the decoder froze\n${native.log.tail()}")
        }
    }

    @Test
    fun aReplayAfterTheEndStartsFromTheBeginningAndGivesASecondCompletion() = fxModel {
        playerRig {
            player.play()
            awaitCompleted(1)
            val mark: Int = positions.size
            player.replay()
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "replay never moved: ${player.stateFlow.value}")
            advanceSampling(POSITION_INTERVAL_MS)
            assertTrue(positions.drop(mark).first() <= 100L, "a replay must start from the beginning: ${positions.drop(mark).take(5)}")
            awaitCompleted(2, 9_000L)
            advanceSampling(200)
            assertMonotoneFrom(mark, "replay")
            assertEquals(2, completions())
            // Ready -> Playing -> Completed -> Playing -> Completed (position-only updates collapse in the state flow);
            // replay() is a seek from Completed (which reads Paused) and a play.
            assertEquals(
                listOf("Idle", "Preparing", "Ready", "Playing", "Completed", "Paused", "Playing", "Completed"),
                stateSequence(),
            )
            assertTrue(!composed.hazards.frozen, "the decoder froze\n${native.log.tail()}")
        }
    }

    private fun PlayerRig.assertMonotoneFrom(from: Int, label: String) {
        val slice: List<Long> = positions.drop(from)
        for (index: Int in 1 until slice.size) {
            assertTrue(slice[index] >= slice[index - 1], "$label: position went back from ${slice[index - 1]} to ${slice[index]} (index $index of $slice)")
        }
        assertTrue(slice.all { it in 0L..TestClip.DURATION_MS }, "$label: a position outside 0..${TestClip.DURATION_MS}: $slice")
    }

    /**
     * A stop near the end. The real class seeks to 2700-2900 and stops 40-190 ms after the play; the model seeks to the key
     * frame at or before it (B5), which is 2000, so those trials stop a second before the end. The twin keeps those trials
     * (seeks 2700 to 2940, stops after 40 to 190 ms) and adds the ones the real class meant: stops swept in 25 ms steps from
     * 900 to 1100 ms after a play from 2000, which straddle the end at 1000.
     * A stop in the one FX task between the end being queued and its handler running is not reached by this sweep; it is a
     * documented residue (platform notes, End of media; D-94).
     */
    @Test
    fun aStopNearTheEndLeavesThePlayerReadyAndNoEarlierCompletionArrivesAfterIt() {
        data class Trial(val seekMs: Long, val stopAfterMs: Long)
        val trials: List<Trial> = List(6) { Trial(2_700L + it * 40L, 40L + it * 30L) } +
            (900L..1_100L step 25L).map { Trial(2_000L, it) }
        val failures: List<String> = trials.mapNotNull { trial ->
            runCase("seek ${trial.seekMs} stop ${trial.stopAfterMs} ms after the play") {
                playerRig {
                    player.seekTo(trial.seekMs)
                    player.play()
                    advanceBy(trial.stopAfterMs)
                    player.stop()
                    val atStop: Int = states.size
                    val state: VideoPlayerState = player.stateFlow.value
                    assertTrue(state is VideoPlayerState.Ready, "after stop() the player reads $state")
                    advanceBy(1_500)
                    val late: List<VideoPlayerState> = states.drop(atStop)
                    assertEquals(emptyList(), late, "states after stop(): $late\n${native.log.tail()}")
                }
            }
        }
        assertNoFailures(failures, trials.size, "stop trials")
    }

    // ---- the frame cap (JavaFxFramesAndRateR5AdversarialTest) ----

    /**
     * A cap of [cap] snapshots per second on the 60 Hz pulse of the picture part: the snapshots of a playing player are
     * `1000 / cap` ms apart exactly (the cap divides the 16.67 ms pulse grid), so every virtual second from a snapshot holds
     * exactly [cap] of them, and no more frames are published than snapshots. The real class bounds the distinct frames in
     * 1.5 s between 2 and 10 at a cap of 5.
     */
    private fun capCase(cap: Int, expectedPerSecond: Int) {
        fxModel {
            engineRig(maxFrameRate = cap) {
                engine.start()
                advanceBy(500)
                assertTrue(engine.frames.value != null, "no first frame at a cap of $cap\n${native.log.tail()}")
                val seen = IdentityHashMap<VideoFrame, Boolean>()
                var elapsed = 0L
                // Two seconds, from the play at 116.7 ms to 2500 ms, all inside the 3 s of the clip.
                while (elapsed < 2_000L) {
                    advanceBy(1)
                    elapsed++
                    engine.frames.value?.let { seen[it] = true }
                }
                val snapshots: List<Double> = native.log.calls(NativeCall.SNAPSHOT).map { it.tMs }.filter { it >= 500.0 }
                assertTrue(snapshots.size >= expectedPerSecond * 2 - 1, "only ${snapshots.size} snapshots in 2 s at a cap of $cap\n${native.log.tail()}")
                for (index: Int in 1 until snapshots.size) {
                    assertAt(1_000.0 / expectedPerSecond, snapshots[index] - snapshots[index - 1], "the snapshots at $index are not 1000/$expectedPerSecond ms apart at a cap of $cap")
                }
                val windowFrom: Double = snapshots.first()
                val inWindow: Int = snapshots.count { it >= windowFrom - ROUNDING_MS && it < windowFrom + 1_000.0 - ROUNDING_MS }
                assertEquals(expectedPerSecond, inWindow, "snapshots in the second from $windowFrom at a cap of $cap\n${native.log.tail()}")
                // The real class: at most 10 distinct frames in 1.5 s at a cap of 5 (7.5 expected), and at least 2.
                assertTrue(seen.size <= snapshots.size + 1, "a cap of $cap published ${seen.size} frames for ${snapshots.size} snapshots")
                assertTrue(seen.size >= expectedPerSecond * 2 - 1, "a cap of $cap published only ${seen.size} distinct frames in 2 s while playing")
                assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
            }
        }
    }

    @Test
    fun aFrameRateCapOfFiveGivesExactlyFiveSnapshotsPerVirtualSecond() = capCase(5, 5)

    @Test
    fun aFrameRateCapOfTenGivesExactlyTenSnapshotsPerVirtualSecond() = capCase(10, 10)

    @Test
    fun theDefaultFrameRateCapGivesExactlyThirtySnapshotsPerVirtualSecond() =
        capCase(JavaFxVideoEngine.DEFAULT_MAX_FRAME_RATE, JavaFxVideoEngine.DEFAULT_MAX_FRAME_RATE)

    @Test
    fun aFrameRateCapAboveThePulseIsLimitedByThePulseToSixtySnapshotsPerVirtualSecond() = capCase(120, 60)

    // ---- the dispose twin (JavaFxReleaseAtLoopPointAdversarialTest) ----

    /**
     * What a release must leave: the native player disposed once; `clearEndOfMedia` and the single cycle written BEFORE the
     * dispose (587be890: a queued end of media must find no handler and no loop); the dispose part recorded no violation;
     * nothing reached the listener afterwards; the pulse stopped; no native seek after the dispose.
     */
    private fun Rig.assertSilentAfterRelease(label: String) {
        val log: List<NativeCallEntry> = native.log.entries
        val disposeAt: Int = log.indexOfFirst { it.call == NativeCall.DISPOSE }
        assertTrue(disposeAt >= 0, "$label: the native player was never disposed\n${native.log.tail()}")
        assertEquals(1, log.count { it.call == NativeCall.DISPOSE }, "$label: disposed more than once\n${native.log.tail()}")
        val cleared: Int = log.indexOfFirst { it.call == NativeCall.CLEAR_END_OF_MEDIA }
        assertTrue(cleared in 0 until disposeAt, "$label: the end-of-media handler was not cleared before the dispose\n${native.log.tail()}")
        val single: Int = log.indexOfLast { it.call == NativeCall.SET_CYCLE_COUNT && it.arg == 1.0 }
        assertTrue(single in 0 until disposeAt && log[single].effective, "$label: the cycle count was not put to 1 before the dispose\n${native.log.tail()}")
        assertEquals(emptyList(), log.drop(disposeAt + 1).filter { it.call == NativeCall.SEEK }, "$label: a seek reached the native player after the dispose\n${native.log.tail()}")
        assertEquals(emptyList(), composed.dispose.violations, "$label: the dispose part recorded violations")
        assertTrue(composed.dispose.lateEndRan, "$label: the late end of media never ran, so the check is vacuous")
        assertEquals(emptyList(), listener.completedAtMs, "$label: a completion reached the listener")
        assertTrue(!native.pulsing, "$label: the pulse was left running after the dispose")
    }

    @Test
    fun releasingALoopingSourceAtRandomMomentsAroundItsLoopPointLeavesNothingBehind() {
        val rnd = Random(RELEASE_SEED)
        val delays: List<Long> = List(RELEASE_TRIALS) { rnd.nextLong(700L, 1_500L) } + (990L..1_010L).toList()
        val failures: List<String> = delays.mapIndexedNotNull { trial, delayMs ->
            runCase("trial $trial (seed $RELEASE_SEED) release $delayMs ms after the start") {
                engineRig(lateEndOfMedia = true) {
                    engine.setLooping(true)
                    engine.seekTo(2_000L)
                    engine.start()
                    advanceBy(delayMs)
                    engine.release()
                    advanceBy(DisposeModel.DEFAULT_LATE_DELAY_MS.toLong() + 50)
                    assertSilentAfterRelease("release $delayMs ms after the start")
                }
            }
        }
        assertNoFailures(failures, delays.size, "release trials")
    }

    @Test
    fun releasingALoopingShortSourceWithinAFewMillisecondsOfItsLoopPointLeavesNothingBehind() {
        val rnd = Random(RELEASE_SEED + 4)
        val delays: List<Long> = List(RELEASE_SHORT_TRIALS) { rnd.nextLong(940L, 1_060L) } + (990L..1_010L).toList()
        val failures: List<String> = delays.mapIndexedNotNull { trial, delayMs ->
            runCase("trial $trial (seed ${RELEASE_SEED + 4}) release $delayMs ms after the start") {
                engineRig(clip = ClipModel(durationMs = 1_000.0), lateEndOfMedia = true) {
                    engine.setLooping(true)
                    engine.start()
                    advanceBy(delayMs)
                    engine.release()
                    advanceBy(DisposeModel.DEFAULT_LATE_DELAY_MS.toLong() + 50)
                    assertSilentAfterRelease("release $delayMs ms after the start of the short source")
                }
            }
        }
        assertNoFailures(failures, delays.size, "release trials")
    }

    /**
     * The same release when the queued end of media survives the clearing of the handler (the other reading of the
     * OpenJFX race) and a late PLAYING status overwrites DISPOSED: `FxPlayback` must still do nothing (its `disposed` guard),
     * so nothing reaches the listener and no rate or seek is written.
     */
    @Test
    fun aQueuedEndOfMediaThatSurvivesTheClearingAndALatePlayingReachNothingAfterTheRelease() {
        val failures: List<String> = (980L..1_020L step 5L).mapNotNull { delayMs ->
            runCase("release $delayMs ms after the start") {
                engineRig(lateEndOfMedia = true, lateEndIgnoresClearedHandler = true, lateStatus = NativeStatus.PLAYING) {
                    engine.setLooping(true)
                    engine.setSpeed(2f)
                    engine.seekTo(2_000L)
                    engine.start()
                    advanceBy(delayMs)
                    engine.release()
                    val disposedAtMs: Double = nowMs()
                    advanceBy(DisposeModel.DEFAULT_LATE_DELAY_MS.toLong() + 50)
                    assertSilentAfterRelease("release $delayMs ms after the start")
                    assertTrue(composed.dispose.lateEndDelivered || composed.dispose.lateEndRan, "the late end did not run")
                    val after: List<NativeCallEntry> = native.log.entries.filter { it.tMs > disposedAtMs && it.call != NativeCall.SNAPSHOT }
                    assertEquals(emptyList(), after.filter { it.call == NativeCall.SEEK || it.call == NativeCall.SET_RATE || it.call == NativeCall.PLAY }, "a command reached the native player after the release\n${native.log.tail()}")
                }
            }
        }
        assertNoFailures(failures, 9, "release trials")
    }

    // ---- superseded seeks (JavaFxTransportBacklogAdversarialTest, da977586) ----

    @Test
    fun aBurstOfSeeksQueuedBehindTheFxThreadIssuesOneNativeSeekForTheNewest() = fxModel {
        engineRig {
            engine.start()
            advanceBy(300)
            val before: Int = native.log.calls(NativeCall.SEEK).size
            // From this thread: nothing runs between the calls, so the three blocks queue behind each other.
            engine.seekTo(500L)
            engine.seekTo(1_500L)
            engine.seekTo(2_500L)
            assertEquals(before, native.log.calls(NativeCall.SEEK).size, "a seek ran before the FX thread did")
            advanceBy(0)
            val seeks: List<NativeCallEntry> = native.log.calls(NativeCall.SEEK).drop(before)
            assertEquals(listOf(2_500.0), seeks.map { it.arg }, "the superseded seeks reached the native player\n${native.log}")
            advanceBy(100)
            assertTrue(engine.positionMs() >= 2_000L, "the engine reads ${engine.positionMs()} after a seek to 2500\n${native.log}")
        }
    }

    /** The twin of the real class: forty rounds of pause, start and seek queued behind the FX thread, then drained. */
    @Test
    fun aBurstOfFortyPauseStartSeekRoundsQueuedBehindTheFxThreadIssuesOneNativeSeekForTheLastTarget() = fxModel {
        engineRig {
            engine.start()
            advanceBy(300)
            val before: Int = native.log.calls(NativeCall.SEEK).size
            repeat(40) { round: Int ->
                engine.pause()
                engine.start()
                engine.seekTo(((round * 97) % 2_800).toLong())
            }
            advanceBy(0)
            val last: Double = ((39 * 97) % 2_800).toDouble()
            val seeks: List<NativeCallEntry> = native.log.calls(NativeCall.SEEK).drop(before)
            assertEquals(listOf(last), seeks.map { it.arg }, "the superseded seeks reached the native player\n${native.log.tail(30)}")
            advanceBy(500)
            assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
        }
    }

    private companion object {
        /** The virtual clock is in nanoseconds and the log in milliseconds: 300 ms can read 299.99999999999994. */
        const val ROUNDING_MS: Double = 0.001
        const val NANOS_PER_MILLI: Double = 1_000_000.0
        const val NANOS_PER_MILLI_LONG: Long = 1_000_000L
        const val MESSAGE_LIMIT: Int = 1_500
        const val PREPARE_BOUND_MS: Long = 10_000L

        /** The sampling step of the real A14 class. */
        const val SAMPLE_STEP_MS: Long = 15L

        /** The run of the real A14 class. */
        const val RUN_MS: Long = 9_000L
        const val WRAP_RUN_MS: Long = 5_000L

        /** How far the playhead must move at the normal speed after a wrap before the speed is given back. */
        const val LOOP_SETTLE_MS: Double = 200.0
        const val LOAD_SEEDS: Int = 12
        const val LOAD_SEED: Long = 1_400L
        const val POSITION_INTERVAL_MS: Long = 25L
        const val RELEASE_SEED: Long = 5L
        const val RELEASE_TRIALS: Int = 60
        const val RELEASE_SHORT_TRIALS: Int = 80
    }
}
