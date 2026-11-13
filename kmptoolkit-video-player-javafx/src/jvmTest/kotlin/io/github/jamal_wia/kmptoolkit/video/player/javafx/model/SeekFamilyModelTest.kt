package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.EngineEvent
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.RecordingListener
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * The seek family on the model tier (task J9): the deterministic F twins of the real-media seek and speed classes, on
 * the composed, watched scripted player ([composeAll]) in virtual time. Every scenario runs through the real
 * `JavaFxVideoEngine` and `FxPlayback` (and, where the real class does, the shared player over [NeverDispatcher]); the
 * end-of-test check of [fxModel] fails any scenario that violates a fragile window, whatever it asserts itself.
 *
 * Real-media twin of each group, and what the F test asserts beyond it (never less):
 * - D2-PF1 (`JavaFxSpeedAfterSeekFreezeAdversarialTest`): a speed given right after a replay of a playing clip, from the
 *   FX thread and from a plain thread (30 seeded gaps each), after a settled gap, and the two recorded sequences. The real
 *   class asserts that the playhead moves within a bound; the twin asserts that the monitor saw no violation, that the
 *   decoder never froze, that the playhead moved, and that the speed reached the decoder, no sooner than the seek window
 *   (W-SEEK-RUN, 300 ms) after the last native seek.
 * - S9 (`JavaFxSeekAtSpeedFreezeR6AdversarialTest`): a seek right after a loop wrap at a speed of 2, at every 7 ms from
 *   the wrap, and a seek in the middle of playback at 2x; plus the deferred-wait form of S9 (a seek while a deferred speed
 *   waits restarts the wait).
 * - Stale reads (`JavaFxStaleReadUnderLoadR9AdversarialTest`, the `LOAD` profile of B3, seeded) and the playhead read
 *   before a seek (`JavaFxVideoEngineTest.aPlayheadReadBeforeASeekLandsDoesNotReplaceItsTarget`): a sample published
 *   after the seek it predates never replaces the seek's target.
 * - Seeks around the end (`JavaFxSeekAcrossTheEndR9AdversarialTest`, `JavaFxSeekAtTheEndR5AdversarialTest`).
 *
 * Two deliberate differences from the real-media class of the LOAD group, both reported (Q-J9-1 and Q-J9-2 of the J9
 * report): the real class reads `engine.positionMs()` from the first millisecond, while the `LOAD` profile of B3 lets a
 * sample taken before a seek be published after the seek has run (the model's samples wait independent seeded delays, the
 * JavaFX queue is FIFO), so the twin reads the DECODER's position from the seek on and the engine's reading from the
 * longest sample delay on; and the real class's "replay after the end" gives the speed between the seek and the play,
 * which the model flags W-IDLE on the current code, so that order is not asserted and the shared player's order (a seek,
 * the play, then the speed) is.
 *
 * Nothing sleeps and nothing reads real time except the hang bound of [prepare]; every random choice is a seeded
 * [Random] and the seed is in every failure message.
 */
class SeekFamilyModelTest {

    // ---- the rig ----

    private class Rig(
        val engine: JavaFxVideoEngine,
        val backend: ComposedBackend,
        val listener: RecordingListener?,
        val player: VideoPlayer?,
    ) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
        val hazards: HazardModel get() = composed.hazards
        val time: TimeModel get() = composed.time
    }

    /** An engine-level rig: the engine and a recording listener, loaded. */
    private fun FxModelScope.engineRig(timeScript: TimeScript = TimeScript(), body: Rig.() -> Unit) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host), timeScript)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        val listener = RecordingListener()
        engine.setListener(listener)
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend, listener, null).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    /** A rig over the shared player (on [NeverDispatcher]), prepared. */
    private fun FxModelScope.playerRig(timeScript: TimeScript = TimeScript(), body: Rig.() -> Unit) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host), timeScript)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        val player: VideoPlayer = videoPlayer(engine)
        prepare(player, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend, null, player).body()
        } finally {
            player.release()
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

    // ---- D2-PF1: a speed given right after a replay of a playing clip (design 4.4, both variants) ----

    private fun speedOf(rnd: Random): Float = listOf(0.5f, 1.5f, 2f, 3f)[rnd.nextInt(4)]

    /**
     * The expectations of one D2-PF1 round, read after the speed was given and 2 s passed: the decoder never froze, the
     * playhead moved, the speed reached the decoder, and no rate was written inside the 300 ms window of the last native
     * seek.
     */
    private fun FxModelScope.assertSpeedAfterReplay(rig: Rig, target: Float, since: Double) {
        val native: ScriptedNativePlayer = rig.native
        assertTrue(!rig.hazards.frozen, "the decoder froze\n${native.log.tail()}")
        assertEquals(target.toDouble(), rig.hazards.effectiveRate, "the speed never reached the decoder\n${native.log}")
        val seek: NativeCallEntry = native.log.calls(NativeCall.SEEK).last { it.tMs >= since }
        val given: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs >= seek.tMs }
        assertTrue(given.isNotEmpty(), "no rate was written after the last seek\n${native.log}")
        assertTrue(
            given.all { it.tMs - seek.tMs >= HazardConfig.DEFAULT_SEEK_RUN_MS - ROUNDING_MS },
            "a rate was written ${given.first().tMs - seek.tMs} ms after the seek, inside W-SEEK-RUN\n${native.log}",
        )
        assertTrue(native.positionMs >= 1_000.0, "the playhead stands at ${native.positionMs} 2 s after the speed\n${native.log}")
    }

    @Test
    fun aSpeedGivenRightAfterAReplayOfAPlayingClipOnTheFxThreadNeverFreezesThePlayhead() {
        val failures: List<String> = (0 until ROUNDS).mapNotNull { round ->
            val rnd = Random(100 + round)
            val target: Float = speedOf(rnd)
            runCase("fx round $round seed ${100 + round} speed $target") {
                playerRig {
                    player!!.play()
                    advanceBy(300)
                    val since: Double = nowMs()
                    onFx {
                        player.replay()
                        player.replay()
                        player.setPlaybackSpeed(target)
                    }
                    advanceBy(2_000)
                    assertSpeedAfterReplay(this, target, since)
                }
            }
        }
        assertNoFailures(failures, ROUNDS, "rounds")
    }

    @Test
    fun aSpeedGivenRightAfterAReplayOfAPlayingClipFromAPlainThreadNeverFreezesThePlayhead() {
        val failures: List<String> = (0 until ROUNDS).mapNotNull { round ->
            val rnd = Random(200 + round)
            val target: Float = speedOf(rnd)
            val gapOne: Long = rnd.nextLong(0L, 6L)
            val gapTwo: Long = rnd.nextLong(0L, 6L)
            runCase("thread round $round seed ${200 + round} speed $target gaps $gapOne $gapTwo") {
                playerRig {
                    player!!.play()
                    advanceBy(300)
                    val since: Double = nowMs()
                    player.replay()
                    advanceBy(gapOne)
                    player.replay()
                    advanceBy(gapTwo)
                    player.setPlaybackSpeed(target)
                    advanceBy(2_000)
                    assertSpeedAfterReplay(this, target, since)
                }
            }
        }
        assertNoFailures(failures, ROUNDS, "rounds")
    }

    @Test
    fun aSpeedGivenLongAfterAReplayOfAPlayingClipNeverFreezesThePlayhead() {
        val failures: List<String> = (0 until ROUNDS).mapNotNull { round ->
            val rnd = Random(300 + round)
            val target: Float = speedOf(rnd)
            val settleMs: Long = 400L + rnd.nextLong(0L, 300L)
            runCase("settled round $round seed ${300 + round} speed $target after $settleMs ms") {
                playerRig {
                    player!!.play()
                    advanceBy(300)
                    val since: Double = nowMs()
                    onFx {
                        player.replay()
                        player.replay()
                    }
                    advanceBy(settleMs)
                    player.setPlaybackSpeed(target)
                    advanceBy(2_000)
                    assertSpeedAfterReplay(this, target, since)
                }
            }
        }
        assertNoFailures(failures, ROUNDS, "rounds")
    }

    /** One call of a recorded sequence: made on the FX thread or on the test thread, then followed by a pause. */
    private class Op(val onFx: Boolean, val pauseMs: Long, val run: (VideoPlayer) -> Unit)

    private fun fx(pauseMs: Long, run: (VideoPlayer) -> Unit): Op = Op(onFx = true, pauseMs = pauseMs, run = run)

    private fun thread(pauseMs: Long, run: (VideoPlayer) -> Unit): Op = Op(onFx = false, pauseMs = pauseMs, run = run)

    /** The tail of the sequence that the random fuzz found frozen (seed 3): a play, a stop and a play, 3x, two replays, 1.5x. */
    private fun stopPlaySpeedReplayTail(): List<Op> = listOf(
        fx(400L) { it.play() },
        thread(80L) { it.stop() },
        fx(150L) { it.play() },
        thread(150L) { it.setPlaybackSpeed(3.0f) },
        fx(5L) { it.replay() },
        fx(0L) { it.replay() },
        thread(80L) { it.setMuted(true) },
        thread(1L) { it.setPlaybackSpeed(1.5f) },
    )

    /**
     * Plays [ops] on a fresh player; the case fails when the decoder froze, when the player is left Playing with a standing
     * playhead for 3.5 s (the real class's check), or when the last speed never reached the decoder.
     */
    private fun recorded(name: String, ops: List<Op>, lastSpeed: Float): String? = runCase(name) {
        playerRig {
            val shared: VideoPlayer = player!!
            for (op: Op in ops) {
                if (op.onFx) onFx { op.run(shared) } else op.run(shared)
                if (op.pauseMs > 0L) advanceBy(op.pauseMs)
            }
            val first: Long = engine.positionMs()
            var moved = false
            var waited = 0L
            while (waited < 3_500L && !moved) {
                if (shared.stateFlow.value !is VideoPlayerState.Playing || engine.positionMs() != first) moved = true
                advanceBy(5)
                waited += 5
            }
            assertTrue(moved, "Playing with the playhead standing at $first for 3.5 s\n${native.log}")
            advanceBy(1_500)
            assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
            assertEquals(lastSpeed.toDouble(), hazards.effectiveRate, "the last speed never reached the decoder\n${native.log}")
        }
    }

    @Test
    fun aStopThenPlayThenSpeedThenDoubleReplayThenSpeedSequenceNeverFreezesThePlayhead() {
        assertNoFailures(listOfNotNull(recorded("tail", stopPlaySpeedReplayTail(), 1.5f)), 1, "sequences")
    }

    /** All 22 calls of the fuzz class's seed 3, with its own pauses, copied without the randomness. */
    @Test
    fun theRecordedSeedThreeCallSequenceOfTheFuzzNeverFreezesThePlayhead() {
        val ops: List<Op> = listOf(
            fx(1L) { it.stop() },
            fx(900L) { it.pause() },
            fx(0L) { it.setMuted(true) },
            fx(20L) { it.setMuted(false) },
            fx(0L) { it.seekBackward(1320L) },
            thread(0L) { it.setVolume(0.3f) },
            fx(0L) { it.play() },
            thread(20L) { it.setVolume(0.0f) },
            fx(0L) { it.seekTo(1696L) },
            thread(150L) { it.seekTo(1064L) },
            fx(1L) { it.seekBackward(305L) },
            fx(150L) { it.setPlaybackSpeed(1.0f) },
            fx(900L) { it.setMuted(false) },
            thread(150L) { it.pause() },
        ) + stopPlaySpeedReplayTail()
        assertNoFailures(listOfNotNull(recorded("seed3", ops, 1.5f)), 1, "sequences")
    }

    // ---- S9: a seek right after a loop wrap at a speed, and in the middle of playback at a speed ----

    /**
     * The real class's check, on virtual time: after a seek, the first reading taken 300 ms on is the landing, and a later
     * reading at least 300 ms away from it proves the playhead moves on. Reads [Rig.engine] every 20 virtual ms for 3.5 s.
     */
    private fun FxModelScope.movesOnAfterSeek(rig: Rig): Boolean {
        var landed: Long = -1L
        var elapsedMs = 0L
        while (elapsedMs < 3_500L) {
            val position: Long = rig.engine.positionMs()
            if (elapsedMs > 300L) {
                if (landed < 0L) landed = position
                if (abs(position - landed) >= 300L) return true
            }
            advanceBy(20)
            elapsedMs += 20
        }
        return false
    }

    /** A seek made [afterWrapMs] after the playhead was first seen to wrap at a speed of 2, then watched for 3.5 s. */
    private fun seekAfterWrap(afterWrapMs: Long): String? = runCase("seek $afterWrapMs ms after the wrap") {
        engineRig {
            engine.setLooping(true)
            engine.setSpeed(2f)
            engine.start()
            var last = 0L
            var wrapped = false
            var waited = 0L
            while (waited < 8_000L && !wrapped) {
                val position: Long = engine.positionMs()
                if (position + 1_000L < last) wrapped = true
                last = position
                if (!wrapped) {
                    advanceBy(5)
                    waited += 5
                }
            }
            assertTrue(wrapped, "the source never wrapped\n${native.log}")
            val wrappedAt: Double = nowMs()
            advanceBy(afterWrapMs)
            val seekAt: Double = nowMs()
            engine.seekTo(2_500L)
            assertTrue(movesOnAfterSeek(this), "the playhead stood at ${engine.positionMs()} for 3.5 s after the seek\n${native.log}")
            assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
            assertSpeedGivenOutsideTheSeekWindow(this, seekAt, wrappedAt, 2.0)
        }
    }

    /**
     * What the monitor cannot say by itself: the speed [speed] reached the decoder at some point from [sinceMs] on, and
     * every rate written after the last native seek at or after [seekAt] (in log order, so a write at the same virtual
     * instant but before the seek does not count) came at least W-SEEK-RUN (300 ms) after that seek.
     */
    private fun FxModelScope.assertSpeedGivenOutsideTheSeekWindow(rig: Rig, seekAt: Double, sinceMs: Double, speed: Double) {
        val native: ScriptedNativePlayer = rig.native
        val entries: List<NativeCallEntry> = native.log.entries
        val seekIndex: Int = entries.indexOfLast { it.call == NativeCall.SEEK && it.tMs >= seekAt }
        assertTrue(seekIndex >= 0, "the seek never reached the native player\n${native.log}")
        val seek: NativeCallEntry = entries[seekIndex]
        val after: List<NativeCallEntry> = entries.drop(seekIndex + 1).filter { it.call == NativeCall.SET_RATE && it.effective }
        val inside: List<NativeCallEntry> = after.filter { it.tMs - seek.tMs < HazardConfig.DEFAULT_SEEK_RUN_MS - ROUNDING_MS }
        assertEquals(emptyList(), inside, "a rate was written inside W-SEEK-RUN of the seek at ${seek.tMs}\n${native.log}")
        assertTrue(
            entries.any { it.call == NativeCall.SET_RATE && it.effective && it.arg == speed && it.tMs >= sinceMs },
            "the speed $speed never reached the decoder from $sinceMs ms on\n${native.log}",
        )
    }

    @Test
    fun aSeekMadeJustAfterALoopWrapAtASpeedOfTwoNeverLeavesThePlayheadStandingForGood() {
        // Every 7 ms from the wrap to 700 ms after it: the samples of the playhead come every 100 ms, so the seek meets every phase.
        val distances: List<Long> = (0L..700L step 7L).toList()
        assertNoFailures(distances.mapNotNull { seekAfterWrap(it) }, distances.size, "seeks after a wrap")
    }

    @Test
    fun aSeekInTheMiddleOfPlaybackAtASpeedOfTwoNeverLeavesThePlayheadStandingForGood() {
        val waits: List<Long> = listOf(0L, 100L, 300L, 500L, 800L)
        val failures: List<String> = waits.mapNotNull { extra ->
            runCase("seek after the playhead passed 300 ms plus $extra ms") {
                engineRig {
                    engine.setSpeed(2f)
                    engine.start()
                    var waited = 0L
                    while (engine.positionMs() <= 300L && waited < 5_000L) {
                        advanceBy(20)
                        waited += 20
                    }
                    assertTrue(engine.positionMs() > 300L, "the clip never advanced\n${native.log}")
                    advanceBy(extra)
                    val seekAt: Double = nowMs()
                    engine.seekTo(1_100L)
                    assertTrue(movesOnAfterSeek(this), "the playhead stood at ${engine.positionMs()} for 3.5 s after a seek at a speed of 2\n${native.log}")
                    assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
                    assertSpeedGivenOutsideTheSeekWindow(this, seekAt, 0.0, 2.0)
                }
            }
        }
        assertNoFailures(failures, waits.size, "seeks during playback")
    }

    // ---- stale reads: a read of the playhead taken before a seek never replaces the seek's target ----

    /** The `LOAD` profile of B3 with a seed of this class: samples wait 50-400 ms and a tenth is dropped. */
    private fun loadScript(seed: Long): TimeScript = TimeScript().load(seed = seed)

    /**
     * The longest a sample can wait in the `LOAD` profile plus one sampling interval: from this long after a seek on, every
     * sample taken before it has been published, so the engine's reading is the current one again.
     */
    private val loadSettleMs: Long = (TimeScript.LOAD_MAX_DELAY_MS + TimeScript.DEFAULT_SAMPLE_INTERVAL_MS).toLong()

    @Test
    fun aSeekMadeJustAfterAPlayAndFollowedByASpeedIsNotUndoneByTheSpeedUnderLoad() {
        val failures: List<String> = (0 until LOAD_TRIALS).mapNotNull { trial ->
            val seed: Long = LOAD_SEED + trial
            runCase("load trial $trial seed $seed") {
                engineRig(loadScript(seed)) {
                    engine.start()
                    advanceBy(80L + (trial % 5) * 40L)
                    engine.seekTo(2_000L)
                    engine.setSpeed(2f)
                    advanceBy(0)
                    val seekIndex: Int = native.log.entries.indexOfLast { it.call == NativeCall.SEEK }
                    var lowestDecoder: Double = Double.MAX_VALUE
                    var lowestSettled: Long = Long.MAX_VALUE
                    var elapsedMs = 0L
                    while (elapsedMs < 1_500L) {
                        lowestDecoder = minOf(lowestDecoder, time.nativePositionMs)
                        if (elapsedMs >= loadSettleMs) lowestSettled = minOf(lowestSettled, engine.positionMs())
                        advanceBy(2)
                        elapsedMs += 2
                    }
                    // A seek made after the caller's, to a position read from the native player before the caller's seek
                    // landed, is what a stale read of the playhead would do (FxPlayback.setSpeed and start read it).
                    val laterSeeks: List<NativeCallEntry> = native.log.entries.drop(seekIndex + 1).filter { it.call == NativeCall.SEEK }
                    assertEquals(emptyList(), laterSeeks, "the speed seeked again after the caller's seek\n${native.log}")
                    assertTrue(lowestDecoder >= 1_500.0, "the decoder stood at $lowestDecoder after a seek to 2000 and a speed of 2\n${native.log}")
                    assertTrue(lowestSettled >= 1_500L, "the engine read $lowestSettled from $loadSettleMs ms after the seek\n${native.log}")
                    assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
                }
            }
        }
        assertNoFailures(failures, LOAD_TRIALS, "trials under load")
    }

    @Test
    fun aSharedPlayerReplayAfterTheEndAtASpeedStartsFromTheStartAndDoesNotReturnToTheEndUnderLoad() {
        val failures: List<String> = (0 until LOAD_TRIALS).mapNotNull { trial ->
            val seed: Long = LOAD_SEED + 100 + trial
            runCase("load trial $trial seed $seed") {
                engineRig(loadScript(seed)) {
                    engine.setSpeed(4f)
                    engine.start()
                    var waited = 0L
                    while (listener!!.events.none { it is EngineEvent.Completed } && waited < 8_000L) {
                        advanceBy(10)
                        waited += 10
                    }
                    assertTrue(listener.events.any { it is EngineEvent.Completed }, "never completed at 4x\n${native.log}")
                    val completions: Int = listener.events.count { it is EngineEvent.Completed }
                    // The shared player's replay of a completed source: a seek, the play, then the speed in force. The real
                    // class (JavaFxStaleReadUnderLoadR9AdversarialTest) gives the speed BETWEEN the seek and the play; on the
                    // model that order is flagged W-IDLE on the current code (see the report, Question Q-J9-1), so it is not
                    // asserted here and not stepped around.
                    engine.seekTo(0L)
                    engine.start()
                    engine.setSpeed(2f)
                    advanceBy(0)
                    var highestDecoder = 0.0
                    var highestSettled = 0L
                    var elapsedMs = 0L
                    while (elapsedMs < 900L) {
                        highestDecoder = maxOf(highestDecoder, time.nativePositionMs)
                        if (elapsedMs >= loadSettleMs) highestSettled = maxOf(highestSettled, engine.positionMs())
                        advanceBy(2)
                        elapsedMs += 2
                    }
                    val after: Int = listener.events.count { it is EngineEvent.Completed }
                    assertTrue(highestDecoder <= 2_300.0, "900 ms after a replay at 2x the decoder reached $highestDecoder\n${native.log}")
                    assertTrue(highestSettled <= 2_300L, "the engine read $highestSettled from $loadSettleMs ms after the replay\n${native.log}")
                    assertEquals(completions, after, "a replay at 2x completed again within 900 ms\n${native.log}")
                }
            }
        }
        assertNoFailures(failures, LOAD_TRIALS, "trials under load")
    }

    /**
     * Twin of `JavaFxVideoEngineTest.aPlayheadReadBeforeASeekLandsDoesNotReplaceItsTarget`. The real test holds the FX
     * thread while the source plays on, so JavaFX's readings of the old playhead wait ahead of the seek in the queue. On
     * the model that is the instant a sample was taken and its publication is queued but has not run: [TimeModel] queues
     * the publication at once, so the host is stepped one block at a time until a sample was taken, the pause and the seek
     * are made from the test thread (queued behind the publication), and a probe is queued right behind the seek. The
     * publication then runs while the seek is still in flight: the engine must keep the seek's target.
     */
    @Test
    fun aPlayheadReadBeforeASeekLandsDoesNotReplaceItsTarget() {
        val failures: List<String> = listOf(500L, 700L, 1_100L, 1_600L).mapNotNull { playMs ->
            runCase("sample taken after $playMs ms of playing") {
                engineRig {
                    engine.start()
                    advanceBy(playMs)
                    val taken: Int = time.samplesTaken
                    var steps = 0
                    while (time.samplesTaken == taken) {
                        check(host.step() && steps++ < 100) { "no sample was taken ($host)" }
                    }
                    assertTrue(host.pendingCount > 0, "the publication of the sample is not queued ($host)")
                    val stale: Long = native.positionMs.toLong()
                    assertTrue(stale > 0L, "the sample carries the playhead, not the start")
                    engine.pause()
                    engine.seekTo(0L)
                    assertEquals(0L, engine.positionMs(), "right after seekTo")
                    var afterSeek: Long = -1L
                    host.postLater { afterSeek = engine.positionMs() }
                    advanceBy(0)
                    assertEquals(0L, afterSeek, "a reading taken before the seek replaced its target (the stale sample was $stale)")
                }
            }
        }
        assertNoFailures(failures, 4, "stale samples")
    }

    // ---- S9, the deferred-wait form: a seek while a deferred speed waits restarts the wait ----

    @Test
    fun aSeekWhileADeferredSpeedWaitsRestartsTheWaitInsteadOfFreezingThePlayhead() {
        // Every 7 ms from just after the 300 ms window to 400 ms later: the seek meets every phase of the samples.
        val waits: List<Long> = (310L..710L step 7L).toList()
        val failures: List<String> = waits.mapNotNull { wait ->
            runCase("seek $wait ms after the speed was deferred") {
                playerRig {
                    player!!.play()
                    advanceBy(300)
                    val replayAt: Double = nowMs()
                    onFx {
                        player.replay()
                        player.setPlaybackSpeed(1.5f)
                    }
                    advanceBy(wait)
                    val seekAt: Double = nowMs()
                    player.seekTo(2_000L)
                    advanceBy(2_000)
                    assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
                    assertSpeedGivenOutsideTheSeekWindow(this, seekAt, replayAt, 1.5)
                    assertEquals(1.5, hazards.effectiveRate, "the speed was not in force at the end\n${native.log}")
                }
            }
        }
        assertNoFailures(failures, waits.size, "seeks")
    }

    // ---- seeks around the end ----

    @Test
    fun aSeekAwayFromTheEndMadeAroundTheNaturalEndResumesAtTheTargetAndNotAtTheStart() {
        val rnd = Random(END_SEED)
        val delays: List<Long> = List(END_TRIALS) { rnd.nextLong(0L, 1_500L) } + (300L..500L step 10L).toList()
        val failures: List<String> = delays.mapIndexedNotNull { trial, delayMs ->
            runCase("trial $trial (seed $END_SEED) seek $delayMs ms after play") {
                playerRig {
                    val shared: VideoPlayer = player!!
                    shared.seekTo(2_600L)
                    shared.play()
                    advanceBy(delayMs)
                    val seekAt: Double = nowMs()
                    shared.seekTo(1_000L)
                    var lowest: Long = Long.MAX_VALUE
                    var completed = false
                    var elapsedMs = 0L
                    while (elapsedMs < 700L) {
                        lowest = minOf(lowest, engine.positionMs())
                        if (shared.stateFlow.value is VideoPlayerState.Completed) completed = true
                        advanceBy(2)
                        elapsedMs += 2
                    }
                    assertTrue(lowest >= 800L && !completed, "a seek to 1000 left the playhead reading $lowest, completed=$completed\n${native.log}")
                    val restarts: List<NativeCallEntry> = native.log.calls(NativeCall.SEEK).filter { it.tMs >= seekAt && it.arg == 0.0 }
                    assertEquals(emptyList(), restarts, "the playhead was put back to the start after the seek\n${native.log}")
                }
            }
        }
        assertNoFailures(failures, delays.size, "seeks around the end")
    }

    /**
     * The race of the real class's second test, made exact. The natural order, on the model's single thread: the end of
     * the source is raised first and the caller's seek is queued right behind it (the hook of [beforeEachTask] runs the
     * seek call between two blocks, as another thread would).
     */
    @Test
    fun aSeekQueuedRightBehindTheEndOfMediaStillResumesAtTheTarget() {
        val failures: List<String> = listOf(0L, 1L, 2L).mapNotNull { variant ->
            runCase("variant $variant") {
                playerRig {
                    val shared: VideoPlayer = player!!
                    shared.seekTo(2_000L)
                    shared.play()
                    var seekAt: Double = -1.0
                    beforeEachTask {
                        if (seekAt < 0.0 && time.endsReached == 1) {
                            seekAt = nowMs()
                            shared.seekTo(1_000L)
                        }
                    }
                    var waited = 0L
                    while (seekAt < 0.0 && waited < 3_000L) {
                        advanceBy(10)
                        waited += 10
                    }
                    beforeEachTask(null)
                    assertTrue(seekAt >= 0.0, "the source never reached its end\n${native.log}")
                    advanceBy(600L + variant * 100L)
                    assertTrue(engine.positionMs() >= 800L, "the engine reads ${engine.positionMs()} after a seek to 1000 behind the end\n${native.log}")
                    val restarts: List<NativeCallEntry> = native.log.calls(NativeCall.SEEK).filter { it.tMs >= seekAt && it.arg == 0.0 }
                    assertEquals(emptyList(), restarts, "the playhead was put back to the start\n${native.log}")
                }
            }
        }
        assertNoFailures(failures, 3, "variants")
    }

    /**
     * The other order of that race: the caller's seek runs first and the end of media the native player had raised
     * before it ran is delivered after it (the FX thread was busy). The model delivers that stale end with
     * [ScriptedNativePlayer.deliverEndOfMedia] right behind the seek.
     */
    @Test
    fun aSeekThatRunsBeforeAnEndOfMediaRaisedEarlierStillResumesAtTheTarget() {
        val failures: List<String> = listOf(0L, 100L, 200L).mapNotNull { busyMs ->
            runCase("busy $busyMs ms") {
                playerRig {
                    val shared: VideoPlayer = player!!
                    shared.seekTo(2_000L)
                    shared.play()
                    advanceBy(800L)
                    val seekAt: Double = nowMs()
                    shared.seekTo(1_000L)
                    host.postLater { native.deliverEndOfMedia() }
                    advanceBy(600L + busyMs)
                    var lowest: Long = Long.MAX_VALUE
                    var elapsedMs = 0L
                    while (elapsedMs < 600L) {
                        lowest = minOf(lowest, engine.positionMs())
                        advanceBy(2)
                        elapsedMs += 2
                    }
                    assertTrue(lowest >= 800L, "after a seek to 1000 queued across the end the engine read $lowest (state ${shared.stateFlow.value})\n${native.log}")
                    val restarts: List<NativeCallEntry> = native.log.calls(NativeCall.SEEK).filter { it.tMs >= seekAt && it.arg == 0.0 }
                    assertEquals(emptyList(), restarts, "the playhead was put back to the start\n${native.log}")
                }
            }
        }
        assertNoFailures(failures, 3, "variants")
    }

    @Test
    fun aSeekToTheStartMadeAsTheEndIsReachedNeverLeavesTheEngineStuckPausedAndSilent() {
        val rnd = Random(5)
        val failures: List<String> = (0 until 16).mapNotNull { trial ->
            val trigger: Long = rnd.nextLong(2_300L, 3_000L)
            runCase("trial $trial (seed 5) trigger $trigger") {
                engineRig {
                    engine.setSpeed(8f)
                    engine.start()
                    var waited = 0L
                    while (waited < 6_000L && engine.positionMs() < trigger && !listener!!.events.contains(EngineEvent.Completed)) {
                        advanceBy(1)
                        waited++
                    }
                    engine.seekTo(0L)
                    var settled = 0L
                    while (settled < 3_500L && !listener!!.events.contains(EngineEvent.Completed)) {
                        advanceBy(25)
                        settled += 25
                    }
                    val status: String = native.status.toString()
                    assertTrue(
                        listener!!.events.contains(EngineEvent.Completed) || status == "PLAYING",
                        "no completion after 3.5 s, status $status, position ${engine.positionMs()}\n${native.log}",
                    )
                    assertTrue(!hazards.frozen, "the decoder froze\n${native.log.tail()}")
                }
            }
        }
        assertNoFailures(failures, 16, "trials")
    }

    private companion object {
        const val ROUNDS: Int = 30
        const val LOAD_TRIALS: Int = 6
        const val END_SEED: Long = 9L
        const val END_TRIALS: Int = 40
        const val LOAD_SEED: Long = 9_000L
        const val MESSAGE_LIMIT: Int = 1_500
        const val PREPARE_BOUND_MS: Long = 10_000L
        const val NANOS_PER_MILLI: Double = 1_000_000.0
        const val NANOS_PER_MILLI_LONG: Long = 1_000_000L

        /** The virtual clock is in nanoseconds and the log in milliseconds: 300 ms can read 299.99999999999994. */
        const val ROUNDING_MS: Double = 0.001
    }
}
