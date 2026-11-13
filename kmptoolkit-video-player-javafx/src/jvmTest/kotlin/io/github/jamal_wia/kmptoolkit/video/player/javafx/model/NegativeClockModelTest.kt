package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RED FIRST tests of task D-r8 (ruling `plans/javafx-port-ruling.md` 3.2 and the audit of the brief): `FxPlayback` keeps
 * stamps and deadlines of `System.nanoTime()`, and a value of 0 stood for "none" in several of them. `nanoTime()` has no
 * zero and no sign: its value is meaningful only as a difference, and the platform may start it anywhere. So the contract
 * read here is that nothing the player does depends on where the clock starts: the same scenario on a clock that begins
 * at a negative value, at 0, at one thousand seconds, and so close below `Long.MAX_VALUE` that it wraps during the run,
 * gives the same native command sequence.
 *
 * Every case runs on all four clocks ([EPOCHS], each on a fresh host) and fails with the label of every clock that broke
 * it. The cases are the paths the audit found:
 * - a speed held across a loop wrap (the deferred rate has no deadline after a wrap);
 * - a start and a resume at a speed that no rate write preceded (nothing was written while idle, so nothing is left of
 *   an idle gap to wait out), and a resume that did follow an idle write, which must still wait out about 200 ms;
 * - the first frame, and the pulse that stops once the picture has been copied (the snapshot interval and the refresh
 *   window have no stamp before the first pulse);
 * - a pause and a resume in the same instant, with and without a speed held;
 * - the end of the media and the replay, with and without a speed held at the end.
 *
 * On a clock that is positive and far from the wrap all of them are green on the old code (the whole model tier is built
 * on such a clock); the negative clock is where a sentinel of 0 reads as an enormous or a negative duration, and the
 * clock near the wrap is where a comparison of two clock readings (`now < deadline`) turns upside down.
 */
@OptIn(ToolkitInternalApi::class)
class NegativeClockModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.withEngine(clip: ClipModel = ClipModel(), body: Rig.() -> Unit) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, clip = clip))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        loadByAdvancing(engine)
        try {
            Rig(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    /**
     * [FxModelScope.load] without its idle point: that one drains the host and fails when a block is still queued after a
     * minute of virtual time, and on a clock where the pulse never stops (one of the defects under test) it would end every
     * case at the load, hiding what the case is about. This one runs one queued block at a time, and only while one is
     * queued, until the load completes: the virtual time at the end of the load then depends on the queue and never on how
     * long the IO hop took in real time.
     */
    private fun FxModelScope.loadByAdvancing(engine: JavaFxVideoEngine) {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of a load whose IO hop is real
        val deadline: Long = System.nanoTime() + LOAD_BOUND_MS * NANOS_PER_MILLI_LONG
        runBlocking {
            val loading = async { engine.load(VideoSource.Asset(TestClip.ASSET)) }
            while (!loading.isCompleted) {
                if (host.pendingCount > 0) host.step()
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of a load whose IO hop is real
                if (!loading.isCompleted && System.nanoTime() - deadline > 0L) {
                    loading.cancel()
                    throw AssertionError("load did not finish within $LOAD_BOUND_MS ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            loading.await()
        }
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / NANOS_PER_MILLI

    /** Runs [run] on each clock in its own model and fails with every clock that broke it, like the other family files. */
    private fun onEveryClock(run: FxModelScope.(epoch: Long) -> Unit) {
        val failures: MutableList<String> = mutableListOf()
        for (epoch: Long in EPOCHS) {
            try {
                fxModel(epochNanos = epoch) { run(epoch) }
            } catch (t: Throwable) {
                failures += "[clock starts at $epoch ns] ${t.message?.lineSequence()?.take(MESSAGE_LINES)?.joinToString("\n")}"
            }
        }
        assertEquals(emptyList(), failures, "${failures.size} of ${EPOCHS.size} clocks broke the scenario")
    }

    /** An assertion whose message is built only when it fails. */
    private fun expect(condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError(message())
    }

    /** The commands of the log without the picture snapshots and the pulse, the last [count]: what a failure needs to show. */
    private fun ScriptedNativePlayer.trace(count: Int = 24): String {
        val shown: List<NativeCallEntry> = log.entries.filter {
            it.call != NativeCall.SNAPSHOT && it.call != NativeCall.START_PULSE && it.call != NativeCall.STOP_PULSE
        }
        return "native commands (the last $count of ${shown.size}, snapshots and pulse left out):" +
            shown.takeLast(count).joinToString(separator = "") { "\n  $it" }
    }

    /** The first PLAY at or after [fromMs], or a failure that says no play was issued by [byMs] after it. */
    private fun ScriptedNativePlayer.playAfter(fromMs: Double, byMs: Double, what: String): NativeCallEntry {
        val play: NativeCallEntry? = log.calls(NativeCall.PLAY).firstOrNull { it.tMs >= fromMs - TIME_TOLERANCE_MS }
        expect(play != null) { "$what: no play() was issued ${byMs.toLong()} ms after the start at $fromMs ms\n${trace()}" }
        return play!!
    }

    private fun Rig.assertSound(what: String) {
        assertTrue(!composed.hazards.frozen, "$what: the decoder froze\n${native.trace()}")
        val violations: List<HazardViolation> = composed.hazards.violations
        expect(violations.isEmpty()) { "$what: a fragile window was violated: ${violations.first().describe()}\n${native.trace()}" }
    }

    // ---- 1: a speed held across a loop wrap ----

    /**
     * A looping clip played at 2x: JavaFX starts the next cycle at normal speed, so the player takes the speed back to the
     * property's 1.0 at the wrap and gives it again once the playhead has moved on a moment. That second write has no
     * deadline after a wrap, only a wait for the playhead; it must arrive on every clock.
     */
    @Test
    fun aSpeedHeldAcrossALoopWrapIsGivenBackOnEveryClock() = onEveryClock { _ ->
        withEngine {
            var wrapAtMs: Double = Double.NaN
            composed.loop.onWrap { wrapAtMs = nowMs() }
            engine.setLooping(true)
            engine.start()
            advanceBy(700)
            engine.setSpeed(2f)
            var steps = 0
            while (composed.loop.cycle < 2) {
                check(steps++ < MAX_STEPS) { "the first wrap did not come ($host)" }
                check(step()) { "the host ran dry before the first wrap ($host)" }
            }
            advanceBy(900)

            expect(composed.hazards.effectiveRate == 2.0) {
                "the speed 2 held across the wrap at $wrapAtMs ms was never given back, 900 ms after it\n${native.trace()}"
            }
            val given: NativeCallEntry = native.log.calls(NativeCall.SET_RATE).first { it.effective && it.arg == 2.0 && it.tMs >= wrapAtMs }
            expect(given.tMs - wrapAtMs >= SEEK_WINDOW_MS - TIME_TOLERANCE_MS) {
                "the speed was given back ${given.tMs - wrapAtMs} ms after the wrap, inside the seek window\n${native.trace()}"
            }
            assertSound("speed held across a loop wrap")
        }
    }

    // ---- 2: a start and a resume with no idle write before them, and one after an idle write ----

    /** The first start of a loaded clip plays at once: no rate was ever written while idle, so there is no gap to wait out. */
    @Test
    fun theFirstStartPlaysAtOnceOnEveryClock() = onEveryClock { _ ->
        withEngine {
            advanceBy(30)
            val at: Double = nowMs()
            engine.start()
            advanceBy(2_000)

            val play: NativeCallEntry = native.playAfter(at, PLAY_LATE_MS, "first start")
            expect(play.tMs - at <= PLAY_LATE_MS) { "the first start played ${play.tMs - at} ms after it, not at once\n${native.trace()}" }
            expect(native.positionMs > MIN_POSITION_MS) { "the playhead stands at ${native.positionMs} ms after 2 s\n${native.trace()}" }
            assertSound("first start")
        }
    }

    /**
     * A resume at a speed that was given while the clip played: the property holds 2.0 and nothing was written while idle.
     * It plays at once (the settle path plays first and gives the speed later), and the speed reaches the decoder.
     */
    @Test
    fun aResumeAtASpeedThatNoIdleWriteProducedPlaysAtOnceOnEveryClock() = onEveryClock { _ ->
        withEngine {
            engine.start()
            advanceBy(700)
            engine.setSpeed(2f)
            advanceBy(300)
            engine.pause()
            advanceBy(100)
            val at: Double = nowMs()
            engine.start()
            advanceBy(2_000)

            val play: NativeCallEntry = native.playAfter(at, PLAY_LATE_MS, "resume at a speed")
            expect(play.tMs - at <= PLAY_LATE_MS) { "the resume played ${play.tMs - at} ms after it, not at once\n${native.trace()}" }
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed never reached the decoder\n${native.trace()}")
            assertSound("resume at a speed")
        }
    }

    /**
     * A resume after a speed was written to the property while paused (an idle write) waits out the rest of the 200 ms gap,
     * on every clock: no sooner (the freeze) and not never. The speed reaches the decoder after the play.
     */
    @Test
    fun aResumeAfterAnIdleWriteWaitsOutTheGapAndThenPlaysOnEveryClock() = onEveryClock { _ ->
        withEngine {
            engine.start()
            advanceBy(300)
            engine.pause()
            advanceBy(50)
            engine.setSpeed(2f)
            advanceBy(20)
            val write: NativeCallEntry = native.log.calls(NativeCall.SET_RATE).last { it.effective }
            val at: Double = nowMs()
            engine.start()
            advanceBy(2_500)

            val play: NativeCallEntry = native.playAfter(at, IDLE_GAP_MS, "resume after an idle write")
            expect(play.tMs - write.tMs >= IDLE_GAP_MS - TIME_TOLERANCE_MS) {
                "the resume played ${play.tMs - write.tMs} ms after the idle write (gap $IDLE_GAP_MS ms)\n${native.trace()}"
            }
            expect(play.tMs - write.tMs <= IDLE_GAP_MS + PLAY_LATE_MS) {
                "the resume played ${play.tMs - write.tMs} ms after the idle write, long after the gap of $IDLE_GAP_MS ms\n${native.trace()}"
            }
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed never reached the decoder\n${native.trace()}")
            assertSound("resume after an idle write")
        }
    }

    // ---- 3: the first frame and the pulse that ends ----

    /**
     * The first picture of a loaded clip is copied and published without any start, and the pulse that copied it stops
     * once nothing is left to copy. The snapshot interval has no earlier snapshot to be measured from, and the refresh
     * window has not been opened.
     */
    @Test
    fun theFirstFrameIsPublishedAndThePulseStopsOnEveryClock() = onEveryClock { _ ->
        withEngine {
            advanceBy(500)
            expect(engine.frames.value != null) { "no frame was published 500 ms after the load\n${native.log.tail()}" }
            advanceBy(3_000)
            expect(!native.pulsing) { "the pulse still runs 3.5 s after the load, with the picture copied and nothing playing\n${native.log.tail()}" }
        }
    }

    /** While a clip plays, frames keep coming at the capped rate. */
    @Test
    fun framesKeepComingWhilePlayingOnEveryClock() = onEveryClock { _ ->
        withEngine {
            engine.start()
            var changes = 0
            var last: VideoFrame? = engine.frames.value
            repeat(200) {
                advanceBy(5)
                val now: VideoFrame? = engine.frames.value
                if (now != null && now !== last) changes++
                last = now
            }
            expect(changes >= MIN_FRAME_CHANGES) { "only $changes new frames in 1 s of play\n${native.log.tail()}" }
        }
    }

    // ---- 4: a pause and a resume in the same instant ----

    /** No speed held: the resume plays at once. */
    @Test
    fun aPauseAndAResumeInTheSameInstantPlayAtOnceOnEveryClock() = onEveryClock { _ ->
        withEngine {
            engine.start()
            advanceBy(300)
            val at: Double = nowMs()
            onFx {
                engine.pause()
                engine.start()
            }
            advanceBy(2_000)

            val plays: List<NativeCallEntry> = native.log.calls(NativeCall.PLAY).filter { it.tMs >= at - TIME_TOLERANCE_MS }
            expect(plays.size == 1) { "${plays.size} play() commands after the pause and resume, expected one\n${native.trace()}" }
            expect(plays.single().tMs - at <= PLAY_LATE_MS) {
                "the resume played ${plays.single().tMs - at} ms after the pause, not at once\n${native.trace()}"
            }
            assertSound("pause and resume in the same instant")
        }
    }

    /**
     * A speed held for the settle when the pause comes: the pause writes it after its `pause()` (an idle write), so the
     * resume in the same instant waits out the gap and then plays, and the speed reaches the decoder.
     */
    @Test
    fun aPauseAndAResumeInTheSameInstantWithAHeldSpeedWaitOutTheGapOnEveryClock() = onEveryClock { _ ->
        withEngine {
            engine.start()
            advanceBy(300)
            engine.setSpeed(2f)
            val at: Double = nowMs()
            onFx {
                engine.pause()
                engine.start()
            }
            advanceBy(2_500)

            val play: NativeCallEntry = native.log.calls(NativeCall.PLAY).last()
            expect(play.tMs - at >= IDLE_GAP_MS - TIME_TOLERANCE_MS) {
                "the resume played ${play.tMs - at} ms after the pause that wrote the held speed (gap $IDLE_GAP_MS ms)\n${native.trace()}"
            }
            expect(play.tMs - at <= IDLE_GAP_MS + PLAY_LATE_MS) {
                "the resume played ${play.tMs - at} ms after the pause, long after the gap of $IDLE_GAP_MS ms\n${native.trace()}"
            }
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed never reached the decoder\n${native.trace()}")
            assertSound("pause and resume with a held speed")
        }
    }

    // ---- 5: the end of the media and the replay ----

    /** A clip that ended without a speed held replays at once: the end wrote no rate, so there is no gap to wait out. */
    @Test
    fun aReplayAfterTheEndOfAClipPlaysAtOnceOnEveryClock() = onEveryClock { _ ->
        withEngine(ClipModel(keyFrameIntervalMs = 10.0)) {
            engine.seekTo(2_940L)
            engine.start()
            advanceBy(500)
            val at: Double = nowMs()
            engine.start()
            advanceBy(1_000)

            val play: NativeCallEntry = native.playAfter(at, PLAY_LATE_MS, "replay")
            expect(play.tMs - at <= PLAY_LATE_MS) { "the replay played ${play.tMs - at} ms after it, not at once\n${native.trace()}" }
            expect(native.positionMs < REPLAY_POSITION_MS) { "the replay did not restart: the playhead stands at ${native.positionMs} ms\n${native.trace()}" }
            assertSound("replay")
        }
    }

    /**
     * The end of a clip with a speed held writes it after its `pause()` (an idle write); the replay 40 ms later waits out
     * the rest of the gap, and plays. The same shape as the end-of-media pin of the D-pi tests, on every clock.
     */
    @Test
    fun aReplayAfterTheEndOfAClipWithAHeldSpeedWaitsOutTheGapOnEveryClock() = onEveryClock { _ ->
        withEngine(ClipModel(keyFrameIntervalMs = 10.0)) {
            engine.seekTo(2_940L)
            engine.start()
            advanceBy(10)
            engine.pause()
            advanceBy(400)
            engine.start()
            advanceBy(20)
            engine.setSpeed(2f)
            advanceBy(100)

            val entries: List<NativeCallEntry> = native.log.entries
            val end: NativeCallEntry = native.log.calls(NativeCall.PAUSE).last()
            val write: NativeCallEntry? = native.log.calls(NativeCall.SET_RATE)
                .firstOrNull { it.effective && entries.indexOf(it) > entries.indexOf(end) }
            expect(write != null) { "the end of the clip wrote no held speed: the clip never played to its end\n${native.trace()}" }
            val endWrite: NativeCallEntry = write!!
            val playsBefore: Int = native.log.calls(NativeCall.PLAY).size
            advanceBy(40)
            val at: Double = nowMs()
            engine.start()
            advanceBy(2_000)

            expect(native.log.calls(NativeCall.PLAY).size > playsBefore) { "the replay never played\n${native.trace()}" }
            val replay: NativeCallEntry = native.log.calls(NativeCall.PLAY)[playsBefore]
            expect(replay.tMs - endWrite.tMs >= IDLE_GAP_MS - TIME_TOLERANCE_MS) {
                "the replay played ${replay.tMs - endWrite.tMs} ms after the end-of-media rate write (gap $IDLE_GAP_MS ms)\n${native.trace()}"
            }
            expect(replay.tMs - at <= IDLE_GAP_MS + PLAY_LATE_MS) {
                "the replay played ${replay.tMs - at} ms after the start, long after the gap of $IDLE_GAP_MS ms\n${native.trace()}"
            }
            assertSound("replay with a held speed")
        }
    }

    private companion object {
        /**
         * The clocks every case runs on: a negative start, 0, one thousand seconds (the default of the model tier), and one
         * second below `Long.MAX_VALUE`, which the run crosses, so a comparison of two readings sees them on both sides of
         * the wrap.
         */
        val EPOCHS: List<Long> = listOf(-1_000_000_000_000L, 0L, 1_000_000_000_000L, Long.MAX_VALUE - 1_000_000_000L)

        const val NANOS_PER_MILLI: Double = 1_000_000.0
        const val NANOS_PER_MILLI_LONG: Long = 1_000_000L
        const val LOAD_BOUND_MS: Long = 10_000L
        const val MESSAGE_LINES: Int = 30
        const val MAX_STEPS: Int = 100_000

        /** The virtual clock is in nanoseconds and the log in milliseconds. */
        const val TIME_TOLERANCE_MS: Double = 0.001

        /** `IDLE_RATE_GAP_NANOS` of `FxPlayback`. */
        const val IDLE_GAP_MS: Double = 200.0

        /** `SEEK_RATE_SETTLE_NANOS` of `FxPlayback`: no speed is written sooner than this after a native seek. */
        const val SEEK_WINDOW_MS: Double = 300.0

        /** How much later than the instant it is due a play may come: a delayed play is scheduled 1 ms past the end of its gap. */
        const val PLAY_LATE_MS: Double = 5.0

        const val MIN_POSITION_MS: Double = 1_000.0
        const val REPLAY_POSITION_MS: Double = 1_500.0
        const val MIN_FRAME_CHANGES: Int = 10
    }
}
