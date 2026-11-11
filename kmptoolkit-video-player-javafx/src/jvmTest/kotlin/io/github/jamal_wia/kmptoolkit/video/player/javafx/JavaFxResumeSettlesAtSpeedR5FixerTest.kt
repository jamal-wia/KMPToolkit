package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 5, ruling D-16 (condition 1): the breaker's freeze methods count a resume as moved at paused + 150, which
 * the playhead passes at normal speed before the deferred speed is given to the native player. This class checks
 * what comes after: a pause and a start at a speed other than 1 must carry the playhead past paused + 1200 within
 * 3 s, a point beyond the speed change and the catch-up of the reported time, so a playhead that freezes at the
 * change fails it. The number of fresh engines per speed is 6, or the environment variable
 * `KMPTOOLKIT_FREEZE_TRIALS` (the 30 + 30 run of the fix).
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxResumeSettlesAtSpeedR5FixerTest {

    private val trials: Int = System.getenv("KMPTOOLKIT_FREEZE_TRIALS")?.toIntOrNull() ?: 6

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    private fun frozenResumes(speed: Float): List<String> {
        val frozen: MutableList<String> = mutableListOf()
        repeat(trials) { trial: Int ->
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            try {
                runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
                engine.setSpeed(speed)
                engine.start()
                awaitTrue(message = { "trial $trial: the clip never advanced" }) { engine.positionMs() > 300L }
                engine.pause()
                Thread.sleep(300L)
                val paused: Long = engine.positionMs()
                engine.start()
                val deadline: Long = System.nanoTime() + 3_000L * 1_000_000L
                while (System.nanoTime() < deadline && engine.positionMs() <= paused + 1_200L) Thread.sleep(20L)
                val reached: Long = engine.positionMs()
                // A clip that played to its end reads its duration (3 s) or 0 once completed: both are past the point.
                if (reached <= paused + 1_200L && reached != 0L) frozen += "trial $trial at speed $speed stood at $reached from $paused"
            } finally {
                engine.release()
            }
        }
        return frozen
    }

    @Test
    fun `a resume at a speed of two carries the playhead past the speed change`() {
        assertEquals(emptyList(), frozenResumes(speed = 2f))
    }

    @Test
    fun `a resume at a speed of four carries the playhead past the speed change`() {
        assertEquals(emptyList(), frozenResumes(speed = 4f))
    }
}
