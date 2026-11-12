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
 * Round 6: a speed changed in Ready (after `prepare`, before the first `play`) and read back through the
 * shared player, the way an app does it: the clip is played at that speed from the first play, and the
 * engine's rate property reads the speed once it plays. (The same change made while PAUSED is a suspect,
 * r6-D2-S6.)
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSpeedInReadyThroughCoreR6AdversarialTest {

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
