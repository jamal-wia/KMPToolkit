package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 6: the round-5 speed tests drive the ENGINE (`setSpeed` before `start`), but the shared player
 * orders its calls the other way round when the speed changed while the player was not playing: `play()`
 * calls `engine.start()` and only then `engine.setSpeed(speed)` (the property was never pushed while
 * paused). So the engine's start sees the old rate and plays plainly, and the new rate is written
 * straight after a plain `play()` - the very order round 5 found to freeze the playhead or lose the speed
 * on macOS. These tests go through the player, the way an app does.
 *
 * The slope windows are timed at playhead changes; see plans/s6-slope-window-advice.md (ruling D-22).
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSpeedThroughCoreR6AdversarialTest {

    private lateinit var player: VideoPlayer
    private lateinit var engine: JavaFxVideoEngine

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        player = createVideoPlayer(engine)
    }

    @AfterTest
    fun tearDown() {
        if (::player.isInitialized) player.release()
    }

    private fun prepare() = runBlocking {
        withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) }
        assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "state ${player.stateFlow.value}")
    }

    /**
     * Pause, change the speed while paused, play again: the playhead must move on and at the new speed.
     * [trials] fresh players; every failure names its trial.
     */
    @Test
    fun `a speed changed while paused is played after the next play, through the player`() {
        val failures: MutableList<String> = mutableListOf()
        repeat(TRIALS) { trial: Int ->
            val e = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            val p: VideoPlayer = createVideoPlayer(e)
            try {
                runBlocking { withTimeout(20_000L) { p.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                p.play()
                awaitTrue(message = { "trial $trial: the clip never advanced" }) { e.positionMs() > 250L }
                p.pause()
                Thread.sleep(300L)
                val paused: Long = e.positionMs()
                p.setPlaybackSpeed(2f)
                p.play()
                val deadline: Long = System.nanoTime() + 3_500L * 1_000_000L
                while (System.nanoTime() < deadline && e.positionMs() <= paused + 600L) Thread.sleep(5L)
                if (e.positionMs() <= paused + 600L) {
                    failures += "trial $trial stood at ${e.positionMs()} from $paused"
                    return@repeat
                }
                val from: Long = e.positionMs()
                val begin: Long = System.nanoTime()
                Thread.sleep(WINDOW_MS)
                val slope: Double = slopeToNextChange(e, from, begin)
                if (slope < 1.5) failures += "trial $trial moved $slope ms per ms at a speed of 2 (paused at $paused)"
            } finally {
                p.release()
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS resumes")
    }

    /** The same from Ready: the speed is changed after `prepare` and before the very first play. */
    @Test
    fun `a speed changed in Ready is played from the first play, through the player`() {
        val failures: MutableList<String> = mutableListOf()
        repeat(TRIALS) { trial: Int ->
            val e = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            val p: VideoPlayer = createVideoPlayer(e)
            try {
                runBlocking { withTimeout(20_000L) { p.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                p.setPlaybackSpeed(4f)
                val begin: Long = System.nanoTime()
                p.play()
                // A 3 s clip at 4x ends within ~0.75 s plus JavaFX's start-up delay; at 1x it takes over 3 s.
                val deadline: Long = begin + 2_200L * 1_000_000L
                while (System.nanoTime() < deadline && p.stateFlow.value !is VideoPlayerState.Completed) Thread.sleep(15L)
                if (p.stateFlow.value !is VideoPlayerState.Completed) {
                    failures += "trial $trial: ${p.stateFlow.value} at position ${e.positionMs()} after 2.2 s at a speed of 4"
                }
            } finally {
                p.release()
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS plays")
    }

    /** The same after the source played to its end: the speed is changed while Completed, then play() starts over. */
    @Test
    fun `a speed changed while Completed is played by the next play, through the player`() {
        val failures: MutableList<String> = mutableListOf()
        repeat(TRIALS) { trial: Int ->
            val e = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            val p: VideoPlayer = createVideoPlayer(e)
            try {
                runBlocking { withTimeout(20_000L) { p.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                p.setPlaybackSpeed(4f)
                p.play()
                awaitTrue(timeoutMs = 8_000L, message = { "trial $trial: never completed at a speed of 4" }) { p.stateFlow.value is VideoPlayerState.Completed }
                p.setPlaybackSpeed(2f)
                p.play()
                val deadline: Long = System.nanoTime() + 3_500L * 1_000_000L
                while (System.nanoTime() < deadline && e.positionMs() <= 600L) Thread.sleep(5L)
                if (e.positionMs() <= 600L) {
                    failures += "trial $trial stood at ${e.positionMs()} after a play from Completed (state ${p.stateFlow.value})"
                    return@repeat
                }
                val from: Long = e.positionMs()
                val begin: Long = System.nanoTime()
                Thread.sleep(WINDOW_MS)
                val slope: Double = slopeToNextChange(e, from, begin)
                if (slope < 1.5 && p.stateFlow.value is VideoPlayerState.Playing) failures += "trial $trial moved $slope ms per ms at a speed of 2 after a play from Completed"
            } finally {
                p.release()
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS restarts")
    }

    @Test
    fun `the player and the engine agree on the speed in use after play`() {
        prepare()
        player.setPlaybackSpeed(2f)
        player.play()
        awaitTrue(message = { "the clip never advanced" }) { engine.positionMs() > 200L }
        val inspected = runBlocking { engine.inspectPlayback() }
        // The speed is given to the native player once play() has run: from then on the property reads it.
        assertEquals(2.0, inspected?.rate, "JavaFX's rate property after a play at a speed of 2 (state ${player.stateFlow.value})")
    }

    /**
     * Media ms per wall ms from [from], read at [begin] just after the reported playhead changed, to the next
     * change after the window. JavaFX publishes the playhead about every 100 ms, so a reading taken at an
     * arbitrary instant is up to one tick old: over a 400 ms window a correct 2x engine can read 600/403 = 1.49
     * and over 300 ms 1.33 (round 6). Timing both edges at a change removes that grid error; the threshold
     * and the scenario are unchanged. A playhead that does not change within [END_CHANGE_TIMEOUT_MS] ends the
     * window where it stands (a freeze reads 0). Ruling: plans/s6-slope-window-advice.md.
     */
    private fun slopeToNextChange(e: JavaFxVideoEngine, from: Long, begin: Long): Double {
        val last: Long = e.positionMs()
        val deadline: Long = System.nanoTime() + END_CHANGE_TIMEOUT_MS * 1_000_000L
        var now: Long = last
        while (now == last && System.nanoTime() < deadline) {
            Thread.sleep(5L)
            now = e.positionMs()
        }
        val end: Long = System.nanoTime()
        val slope: Double = (now - from) / ((end - begin) / 1_000_000.0)
        if (PRINT_SLOPES) println("R6SLOPE $slope")
        return slope
    }

    private companion object {
        /** Wall clock of the slope window before its end is timed at the next playhead change (see [slopeToNextChange]). */
        const val WINDOW_MS: Long = 600L

        /** How long the end of a slope window waits for the reported playhead to change. */
        const val END_CHANGE_TIMEOUT_MS: Long = 250L

        /** KMPTOOLKIT_R6_PRINT=1 prints every measured slope (for the distribution a ruling asks for). */
        val PRINT_SLOPES: Boolean = System.getenv("KMPTOOLKIT_R6_PRINT") == "1"

        /** KMPTOOLKIT_R6_TRIALS lengthens a run without editing the test. */
        val TRIALS: Int = System.getenv("KMPTOOLKIT_R6_TRIALS")?.toIntOrNull() ?: 6
    }
}
