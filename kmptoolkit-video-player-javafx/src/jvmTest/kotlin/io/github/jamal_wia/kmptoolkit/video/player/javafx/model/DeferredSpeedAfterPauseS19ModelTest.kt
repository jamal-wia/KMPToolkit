package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 round 13, suspect S19 and its twins (ruling `reviews/d2-r13-ruling-opus.md`): a speed that waits (after a seek, a loop
 * wrap or the end of a clip) must not be written on a reading that arrives after `pause()`, and the return to normal
 * speed at a loop wrap that JavaFX delivers after `pause()` counts as a rate written while not playing. The contract is the
 * sentence of `05-platform-notes.md`, "Playback speed": a `play()` that follows a rate written while the player was not
 * playing within about 200 ms waits for the rest of those 200 ms. The end-of-test hazard check of `fxModel` fails a case
 * that played inside that gap or wrote a rate inside W-PLAY.
 *
 * - T1, the seek shape (the suspect, 36 instants of the pause): a deferred speed, a late tick after `pause()`.
 * - T2, the end shape: the clip ends with a speed still waiting, a late tick reads PLAYING, the replay follows at once.
 * - T3, the wrap after a pause: a looping clip at 2x, the end-of-cycle handler JavaFX queued runs after `pause()`.
 * - T4, the gauge carry-over: the movement counted before a pause must not complete the count after the resume.
 *
 * Virtual time only; the instants are fixed numbers, so the cases are deterministic.
 */
class DeferredSpeedAfterPauseS19ModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / NANOS_PER_MS

    private fun FxModelScope.rig(
        script: TimeScript = TimeScript(),
        status: StatusScript = StatusScript(),
        clip: ClipModel = ClipModel(),
        body: Rig.() -> Unit,
    ) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, clip = clip, script = status), timeScript = script)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    private fun ScriptedNativePlayer.writes(afterMs: Double, rate: Double): List<NativeCallEntry> =
        log.calls(NativeCall.SET_RATE).filter { it.effective && it.arg == rate && it.tMs >= afterMs - EPSILON_MS }

    private fun ScriptedNativePlayer.anyWrites(fromMs: Double, untilMs: Double): List<NativeCallEntry> =
        log.calls(NativeCall.SET_RATE).filter { it.tMs >= fromMs - EPSILON_MS && it.tMs < untilMs }

    // ---- T1. the seek shape: a deferred speed and a pause whose status has not been reported yet ----

    /**
     * `applyDeferredRate` checks the native status but not `playIssued`: after `pause()` the status still reads PLAYING
     * until the native side answers, and a reading published late can still tick. The case sweeps the instant of the pause
     * across the wait of a speed given after a seek, then resumes 40 ms later; the speed must stay waiting for the next start.
     */
    @Test
    fun aSpeedDeferredAfterASeekIsNotWrittenAfterAPauseAndTheNextPlayStaysClearOfIt() {
        val findings: MutableList<String> = mutableListOf()
        for (pauseOffsetMs: Int in 305..700 step 11) {
            try {
                fxModel {
                    rig(
                        script = TimeScript().also { it.publishDelayMs = 90.0 },
                        status = StatusScript().statusLatency(NativeCall.PAUSE, 1, SLOW_PAUSE_STATUS_MS),
                    ) {
                        engine.start()
                        advanceBy(800)
                        engine.seekTo(2_000L)
                        advanceBy(10)
                        engine.setSpeed(2f)
                        advanceBy(pauseOffsetMs.toLong() - 10)
                        engine.pause()
                        val pausedAt: Double = nowMs()
                        advanceBy(40)
                        val after: List<NativeCallEntry> = native.writes(pausedAt, 2.0)
                        engine.start()
                        advanceBy(600)
                        if (after.isNotEmpty()) {
                            findings += "pause at +$pauseOffsetMs ms: the deferred speed was written ${after.first().tMs - pausedAt} ms after the pause()\n" +
                                native.log.toString().lines().filter { l: String -> CALL_LINE.containsMatchIn(l) }.takeLast(8).joinToString("\n")
                        }
                    }
                }
            } catch (t: Throwable) {
                findings += "pause at +$pauseOffsetMs ms: ${t.message?.take(900)}"
            }
        }
        assertTrue(findings.isEmpty(), findings.joinToString("\n"))
    }

    // ---- T2. the end shape: the clip ends with a speed waiting, a late tick reads PLAYING, the replay follows ----

    /**
     * A speed given right after a seek near the end of a clip with a short key-frame interval: the clip ends 200 ms after
     * the seek, inside the 300 ms wait, so the speed is still waiting. `onEndOfMedia` pauses the native player, whose status
     * (as on JavaFX, which leaves it at PLAYING at the end) is reported late; a tick that arrives meanwhile completes the
     * count of the wait. No rate may be written on a player that has ended, and the replay that follows at once must
     * still play at the speed.
     */
    @Test
    fun aSpeedWaitingWhenTheClipEndsIsNotWrittenByALateTickAndTheReplayStaysClearOfIt() = fxModel {
        rig(
            clip = ClipModel(keyFrameIntervalMs = 100.0),
            status = StatusScript().statusLatency(NativeCall.PAUSE, 1, 600.0),
        ) {
            engine.start()
            advanceBy(2_600)
            engine.seekTo(2_850L)
            val seekAt: Double = nowMs()
            advanceBy(10)
            engine.setSpeed(2f)
            // The clip ends 200 ms after the seek; the late ticks follow at +320 ms and +570 ms, past the 300 ms wait.
            advanceBy(310 - 10)
            assertTrue(native.log.calls(NativeCall.PAUSE).isNotEmpty(), "the clip ended and paused\n${native.log}")
            assertEquals(NativeStatus.PLAYING, native.status, "the status still reads PLAYING (late pause status)\n${native.log}")
            val events: NativeEvents = checkNotNull(native.events) { "the player has no listener" }
            advanceBy(20)
            val firstTickAt: Double = nowMs()
            native.positionMs = 2_500.0
            onFx { events.onCurrentTime() }
            advanceBy(250)
            native.positionMs = 2_750.0
            onFx { events.onCurrentTime() }
            val lateTickAt: Double = nowMs()
            assertEquals(NativeStatus.PLAYING, native.status, "the status still reads PLAYING at the second tick, ${lateTickAt - seekAt} ms after the seek\n${native.log}")
            advanceBy(10)
            val replayAt: Double = nowMs()
            val earlyWrites: List<NativeCallEntry> = native.anyWrites(firstTickAt, replayAt)
            assertEquals(emptyList(), earlyWrites, "a rate was written on a player that had ended\n${native.log.tail()}")
            engine.start()
            advanceBy(1_500)
            assertEquals(2.0, composed.hazards.effectiveRate, "the replay runs at the speed given before the end\n${native.log.tail()}")
        }
    }

    // ---- T3. the wrap after a pause: the end-of-cycle handler JavaFX queued runs after pause() ----

    /**
     * A looping clip at 2x. JavaFX queued the end-of-cycle handler before `pause()` and runs it after it (the same queueing
     * the comment of `FxPlayback.dispose` describes): the handler writes 1.0 on a player that is paused, to forward the
     * speed of the next cycle later. That is a rate written while not playing, so a `play()` 40 ms later must wait out
     * the rest of the 200 ms gap.
     */
    @Test
    fun aLoopWrapDeliveredAfterAPauseStampsTheIdleGapAndTheNextPlayWaitsOutIt() = fxModel {
        rig(clip = ClipModel(durationMs = 30_000.0)) {
            engine.setLooping(true)
            engine.setSpeed(2f)
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(1)
            val pausedAt: Double = nowMs()
            onFx { native.deliverEndOfMedia() }
            advanceBy(40)
            val ones: List<NativeCallEntry> = native.writes(pausedAt, 1.0)
            assertEquals(1, ones.size, "the wrap wrote 1.0 once on the paused player\n${native.log.tail()}")
            engine.start()
            advanceBy(1_000)
            val plays: List<NativeCallEntry> = native.log.calls(NativeCall.PLAY).filter { it.tMs > ones.single().tMs }
            assertTrue(plays.isNotEmpty(), "the replay played\n${native.log.tail()}")
            assertTrue(
                plays.first().tMs - ones.single().tMs >= IDLE_GAP_MS - EPSILON_MS,
                "the play came ${plays.first().tMs - ones.single().tMs} ms after the write of 1.0 on a paused player\n${native.log.tail()}",
            )
            assertEquals(2.0, composed.hazards.effectiveRate, "the speed came back after the resume\n${native.log.tail()}")
        }
    }

    // ---- T4. the gauge carry-over: the movement counted before a pause must not complete the count after the resume ----

    /**
     * Playing at 2x, a seek, then a speed of 1 inside the 300 ms window: the speed waits and its gauge counts the movement
     * after the window, with samples every 25 ms. The pause comes when about 150 ms of the 200 ms are counted; the
     * resume 250 ms later takes the plain path (speed 1), which leaves the speed waiting. The first tick after that play must
     * not complete the old count and write a rate inside W-PLAY.
     */
    @Test
    fun movementCountedBeforeAPauseDoesNotCompleteTheWaitOfASpeedAfterTheResume() = fxModel {
        rig(
            script = TimeScript().also { it.sampleIntervalMs = 25.0 },
            clip = ClipModel(durationMs = 30_000.0),
        ) {
            engine.setSpeed(2f)
            engine.start()
            advanceBy(1_500)
            engine.seekTo(8_000L)
            advanceBy(10)
            engine.setSpeed(1f)
            // 300 ms after the seek the wait opens; 75 ms of wall time later 150 ms of media are counted at 2x.
            advanceBy(300 - 10 + 75)
            engine.pause()
            advanceBy(250)
            engine.start()
            val playAt: Double = nowMs()
            advanceBy(600)
            val inWindow: List<NativeCallEntry> = native.anyWrites(playAt, playAt + W_PLAY_MS)
            assertEquals(emptyList(), inWindow, "a rate was written within ${W_PLAY_MS.toInt()} ms of the play\n${native.log.tail()}")
        }
    }

    private companion object {
        const val SLOW_PAUSE_STATUS_MS: Double = 300.0
        const val EPSILON_MS: Double = 0.01
        const val NANOS_PER_MS: Double = 1_000_000.0
        const val IDLE_GAP_MS: Double = 200.0
        const val W_PLAY_MS: Double = 100.0
        val CALL_LINE: Regex = Regex("^\\s*t=\\S+ (SET_RATE|PAUSE|PLAY|SEEK)\\b")
    }
}
