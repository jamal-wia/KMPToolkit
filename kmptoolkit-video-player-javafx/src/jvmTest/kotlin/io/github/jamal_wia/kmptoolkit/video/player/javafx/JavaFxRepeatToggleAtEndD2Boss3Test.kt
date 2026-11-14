package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D2 boss 3, attack R1: the repeat mode switched in the instant between JavaFX deciding what the end of a cycle means and the
 * engine's end handler running. OpenJFX's `handleFinish` (an FX task) reads `cycleCount` and either loops (seek to the start, play)
 * or sets EOS and leaves the player PLAYING at the end; only then does it post `onEndOfMedia` as a further FX task. The engine's
 * handler reads its own `looping` field, which a `setLooping` landing between the two tasks has already changed.
 *
 * The test puts the `setLooping` block in that gap deterministically: a listener on JavaFX's `currentCount` (set inside
 * `handleFinish` before the decision) posts it with `Platform.runLater`, so it is queued ahead of the handler JavaFX posts after the
 * decision. Contract (core 03-guide "Looping", javafx 05 "Looping"/"End of media", vlcj 05 "Threading", where the same race is
 * handled by a rewind and restart): the clip either loops or reports Completed. The control (the block sets the mode it already
 * had) shows the trick itself does not disturb the end.
 *
 * The pins: the control, and looping switched OFF in the gap (JavaFX has already restarted the clip, the engine reads the new mode and
 * reports Completed, which the contract allows). Looping switched ON in the gap used to leave the player PLAYING at the end for good,
 * neither looping nor completing (finding R1 of the D2 boss 3 report, 3 of 3 on macOS, also after pause and play); since the fix (the
 * engine takes the loop-or-end decision from the looping setting as JavaFX's own decision read it, `NativeEvents.onCycleDecision`) the
 * clip reports Completed, and the next play restarts it and loops (javafx 05 "Looping").
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxRepeatToggleAtEndD2Boss3Test {

    private var engine: JavaFxVideoEngine? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engine?.release()
    }

    private class Outcome(val completed: Boolean, val looped: Boolean, val positions: List<Long>) {
        override fun toString(): String = "completed=$completed looped=$looped positions=${positions.takeLast(40)}"
    }

    private fun runEnd(loopingBefore: Boolean, loopingInGap: Boolean): Outcome {
        val captured: AtomicReference<JavaFxNativePlayer?> = AtomicReference(null)
        val capturing = object : NativeBackend by JavaFxBackend {
            override fun create(source: NativeSource): NativePlayer =
                JavaFxBackend.create(source).also { captured.set(it as JavaFxNativePlayer) }
        }
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L, backend = { capturing }).also { this.engine = it }
        val completed = AtomicBoolean(false)
        engine.setListener(object : VideoPlaybackEngineListener {
            override fun onCompleted() = completed.set(true)
            override fun onFailed(cause: Throwable) = Unit
            override fun onBufferingChanged(isBuffering: Boolean) = Unit
            override fun onVideoSizeChanged(size: VideoSize?) = Unit
        })
        runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
        engine.setLooping(loopingBefore)
        val native: JavaFxNativePlayer = checkNotNull(captured.get()) { "the backend built no player" }
        val toggles = AtomicInteger(0)
        runBlocking {
            withTimeout(5_000L) {
                FxThread.call {
                    native.mediaPlayer.currentCountProperty().addListener { _, _, _ ->
                        if (toggles.getAndIncrement() == 0) Platform.runLater { engine.setLooping(loopingInGap) }
                    }
                }
            }
        }
        engine.seekTo(2_300L)
        engine.start()
        awaitTrue(8_000L, { "JavaFX never finished the first cycle (currentCount listener never ran)" }) { toggles.get() > 0 }
        val positions: MutableList<Long> = mutableListOf()
        var sawLow = false
        var looped = false
        // A bounded observation, polled by awaitTrue: the verdict is what the engine reported, not the elapsed time.
        try {
            awaitTrue(OBSERVE_MS, { "neither" }) {
                val p: Long = engine.positionMs()
                positions += p
                if (p < 1_000L) sawLow = true
                if (sawLow && p > 1_200L) looped = true
                completed.get() || looped
            }
        } catch (_: AssertionError) {
            // Neither within the bound: the outcome below says so.
        }
        return Outcome(completed.get(), looped, positions)
    }

    @Test
    fun `the control the mode set again to what it was in the gap still reports Completed`() {
        val outcome: Outcome = runEnd(loopingBefore = false, loopingInGap = false)
        println("BOSS3-R1-CONTROL $outcome")
        assertTrue(outcome.completed, "the trick itself disturbed the end: $outcome")
    }

    @Test
    fun `looping switched off between JavaFX's restart and the engine's end handler either loops or reports Completed`() {
        val outcome: Outcome = runEnd(loopingBefore = true, loopingInGap = false)
        println("BOSS3-R1-OFF $outcome")
        assertTrue(outcome.completed || outcome.looped, "neither looped nor completed: $outcome")
    }

    @Test
    fun `looping switched on between JavaFX's end decision and the engine's end handler reports Completed`() {
        val outcome: Outcome = runEnd(loopingBefore = false, loopingInGap = true)
        println("BOSS3-R1-ON $outcome")
        assertTrue(outcome.completed, "the end JavaFX decided was not reported as Completed (the player stands at the end as Playing, or looped): $outcome")
        assertFalse(outcome.looped, "a clip JavaFX had ended was reported as looping: $outcome")
    }

    @Test
    fun `after the Completed of a looping switch in the gap the next play restarts the clip and loops`() {
        val outcome: Outcome = runEnd(loopingBefore = false, loopingInGap = true)
        println("BOSS3-R1-ON-REPLAY first $outcome")
        assertTrue(outcome.completed, "the premise: the end was reported as Completed: $outcome")
        val engine: JavaFxVideoEngine = checkNotNull(this.engine)
        val completedAgain = AtomicInteger(0)
        engine.setListener(object : VideoPlaybackEngineListener {
            override fun onCompleted() {
                completedAgain.incrementAndGet()
            }
            override fun onFailed(cause: Throwable) = Unit
            override fun onBufferingChanged(isBuffering: Boolean) = Unit
            override fun onVideoSizeChanged(size: VideoSize?) = Unit
        })
        engine.start()
        // Three stages: the restart (the position leaves the end for the start), a whole cycle (it nears the end), the wrap (it is at the start again).
        var restarted = false
        var nearedEnd = false
        var looped = false
        val positions: MutableList<Long> = mutableListOf()
        // A bounded observation, polled by awaitTrue: the verdict is what the engine reported, or a second Completed.
        try {
            awaitTrue(REPLAY_OBSERVE_MS, { "no wrap" }) {
                val p: Long = engine.positionMs()
                positions += p
                if (!restarted) restarted = p < 1_000L
                else if (!nearedEnd) nearedEnd = p > 2_500L
                else if (p < 1_000L) looped = true
                looped || completedAgain.get() > 0
            }
        } catch (_: AssertionError) {
            // Neither within the bound: the verdict below says so.
        }
        println("BOSS3-R1-ON-REPLAY looped=$looped completedAgain=${completedAgain.get()} positions=${positions.takeLast(40)}")
        assertTrue(looped, "the play after the Completed did not loop (completed again ${completedAgain.get()} times): ${positions.takeLast(40)}")
        assertEquals(0, completedAgain.get(), "the looping replay was reported as Completed: ${positions.takeLast(40)}")
    }

    /**
     * The same race through the shared player (`createVideoPlayer(engine)`, the way an app and the `*ThroughCoreR6` classes use it):
     * the block of the gap calls `player.setRepeatMode(RepeatMode.One)` on a player that was not looping. The player writes the mode
     * before it calls `engine.setLooping(true)`, so the engine's end (which it reports as `onCompleted`, the SPI tolerates that in this
     * race) reaches the player in `RepeatMode.One` and the player starts the clip over at once (`seekTo(0)` and `start()`, the core's
     * "Looping" rule). Contract: the state never becomes `Completed`, the playhead leaves the end for the start and moves on, and the
     * clip then loops. This is also the measurement of the core's `seekTo(0)` + `start()` right behind JavaFX's own end on the real decoder.
     */
    @Test
    fun `through the shared player looping switched on in the gap restarts the clip at once and it loops without ever completing`() {
        val captured: AtomicReference<JavaFxNativePlayer?> = AtomicReference(null)
        val capturing = object : NativeBackend by JavaFxBackend {
            override fun create(source: NativeSource): NativePlayer =
                JavaFxBackend.create(source).also { captured.set(it as JavaFxNativePlayer) }
        }
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L, backend = { capturing }).also { this.engine = it }
        val player: VideoPlayer = createVideoPlayer(engine)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
            assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "state ${player.stateFlow.value}")
            val native: JavaFxNativePlayer = checkNotNull(captured.get()) { "the backend built no player" }
            val states: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
            val positions: MutableList<Long> = CopyOnWriteArrayList()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { states += it } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.playbackPositionFlow.collect { positions += it } }
            val toggles = AtomicInteger(0)
            val positionsAtGap = AtomicInteger(-1)
            runBlocking {
                withTimeout(5_000L) {
                    FxThread.call {
                        native.mediaPlayer.currentCountProperty().addListener { _, _, _ ->
                            if (toggles.getAndIncrement() == 0) {
                                positionsAtGap.set(positions.size)
                                Platform.runLater { player.setRepeatMode(RepeatMode.One) }
                            }
                        }
                    }
                }
            }
            player.seekTo(2_300L)
            player.play()
            awaitTrue(8_000L, { "JavaFX never finished the first cycle (currentCount listener never ran); states ${states.takeLast(5)}" }) { toggles.get() > 0 }
            val from: Int = positionsAtGap.get()
            fun after(): List<Long> = positions.drop(from)
            // Three stages in order: the playhead leaves the end for the start, rises past 1000 ms (it moves), falls below 1000 ms again (it loops).
            awaitTrue(OBSERVE_MS, { "the playhead never fell below 1000 ms after the end (no restart); positions ${after().takeLast(40)}; states ${states.takeLast(5)}" }) {
                after().any { it < 1_000L }
            }
            val restartedAt: Int = from + after().indexOfFirst { it < 1_000L }
            awaitTrue(OBSERVE_MS, { "the restarted clip did not move past 1000 ms; positions ${positions.drop(restartedAt).takeLast(40)}; states ${states.takeLast(5)}" }) {
                positions.drop(restartedAt).any { it > 1_000L }
            }
            val movedAt: Int = restartedAt + positions.drop(restartedAt).indexOfFirst { it > 1_000L }
            awaitTrue(LOOP_OBSERVE_MS, { "the clip did not wrap below 1000 ms within the next cycle; positions ${positions.drop(movedAt).takeLast(40)}; states ${states.takeLast(5)}" }) {
                positions.drop(movedAt).any { it < 1_000L }
            }
            println("BOSS3-R1-ON-THROUGH-CORE states=${states.toList()} positions=${positions.drop(from).takeLast(60)}")
            assertTrue(states.none { it is VideoPlayerState.Completed }, "the shared player reported Completed: $states")
        } finally {
            scope.cancel()
            player.release()
        }
    }

    private companion object {
        const val OBSERVE_MS: Long = 4_000L
        const val LOOP_OBSERVE_MS: Long = 6_000L
        const val REPLAY_OBSERVE_MS: Long = 8_000L
    }
}
