package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 6 (probe: loop wrap at a speed of 2, then a seek to 2500 ms, sampled for 4 s): in one trial of four the
 * playhead stood at 2000 ms (the key frame the seek snapped to) for the whole 4 s with JavaFX's status
 * PLAYING and its rate property at 2.0, the freeze round 5 found for a rate change made right after play().
 * Here the rate is the speed the engine gives back after a loop wrap, and it lands within about 100 ms of a
 * native seek. The control seeks a non-looping source at speed 2 in the middle of playback.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSeekAtSpeedFreezeR6AdversarialTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    private val trials: Int = System.getenv("KMPTOOLKIT_R6_TRIALS")?.toIntOrNull() ?: 8

    /** Whether the playhead, after a seek, moved at least 300 ms away from where the seek landed within 3.5 s. */
    private fun movesOnAfter(engine: JavaFxVideoEngine, afterMs: Long = 3_500L): Boolean {
        var landed: Long = -1L
        val begin: Long = System.nanoTime()
        val deadline: Long = begin + afterMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val position: Long = engine.positionMs()
            // Skip the reading that still is the requested target: it is replaced by where JavaFX landed.
            if (System.nanoTime() - begin > 300L * 1_000_000L) {
                if (landed < 0L) landed = position
                if (kotlin.math.abs(position - landed) >= 300L) return true
            }
            Thread.sleep(20L)
        }
        return false
    }

    @Test
    fun `a seek made just after a loop wrap at a speed of two never leaves the playhead standing for good`() {
        val frozen: MutableList<String> = mutableListOf()
        repeat(trials) { trial: Int ->
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            try {
                runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
                engine.setLooping(true)
                engine.setSpeed(2f)
                engine.start()
                var last = 0L
                var wrapped = false
                val deadline: Long = System.nanoTime() + 8_000L * 1_000_000L
                while (System.nanoTime() < deadline && !wrapped) {
                    val position: Long = engine.positionMs()
                    if (position + 1_000L < last) wrapped = true
                    last = position
                    Thread.sleep(5L)
                }
                if (!wrapped) {
                    frozen += "trial $trial: the source never wrapped"
                    return@repeat
                }
                engine.seekTo(2_500L)
                if (!movesOnAfter(engine)) frozen += "trial $trial: the playhead stood at ${engine.positionMs()} for 3.5 s after the seek"
            } finally {
                engine.release()
            }
        }
        assertEquals(emptyList(), frozen, "of $trials seeks after a wrap")
    }

    @Test
    fun `a seek in the middle of playback at a speed of two never leaves the playhead standing for good`() {
        val frozen: MutableList<String> = mutableListOf()
        repeat(trials) { trial: Int ->
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            try {
                runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
                engine.setSpeed(2f)
                engine.start()
                awaitTrue(message = { "trial $trial: the clip never advanced" }) { engine.positionMs() > 300L }
                engine.seekTo(1_100L)
                // The clip is 3 s: at 2x from the key frame at 1000 it reaches 2000 well inside the window.
                var moved = false
                val deadline: Long = System.nanoTime() + 3_500L * 1_000_000L
                var landed = -1L
                val begin: Long = System.nanoTime()
                while (System.nanoTime() < deadline && !moved) {
                    val position: Long = engine.positionMs()
                    if (System.nanoTime() - begin > 300L * 1_000_000L) {
                        if (landed < 0L) landed = position
                        if (kotlin.math.abs(position - landed) >= 300L) moved = true
                    }
                    Thread.sleep(20L)
                }
                if (!moved) frozen += "trial $trial: the playhead stood at ${engine.positionMs()} for 3.5 s after a seek at a speed of 2"
            } finally {
                engine.release()
            }
        }
        assertEquals(emptyList(), frozen, "of $trials seeks during playback")
    }
}
