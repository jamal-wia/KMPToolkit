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
                while (System.nanoTime() < deadline && e.positionMs() <= paused + 600L) Thread.sleep(15L)
                if (e.positionMs() <= paused + 600L) {
                    failures += "trial $trial stood at ${e.positionMs()} from $paused"
                    return@repeat
                }
                val from: Long = e.positionMs()
                val begin: Long = System.nanoTime()
                Thread.sleep(400L)
                val slope: Double = (e.positionMs() - from) / ((System.nanoTime() - begin) / 1_000_000.0)
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
                while (System.nanoTime() < deadline && e.positionMs() <= 600L) Thread.sleep(15L)
                if (e.positionMs() <= 600L) {
                    failures += "trial $trial stood at ${e.positionMs()} after a play from Completed (state ${p.stateFlow.value})"
                    return@repeat
                }
                val from: Long = e.positionMs()
                val begin: Long = System.nanoTime()
                Thread.sleep(300L)
                val slope: Double = (e.positionMs() - from) / ((System.nanoTime() - begin) / 1_000_000.0)
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

    private companion object {
        /** KMPTOOLKIT_R6_TRIALS lengthens a run without editing the test. */
        val TRIALS: Int = System.getenv("KMPTOOLKIT_R6_TRIALS")?.toIntOrNull() ?: 6
    }
}
