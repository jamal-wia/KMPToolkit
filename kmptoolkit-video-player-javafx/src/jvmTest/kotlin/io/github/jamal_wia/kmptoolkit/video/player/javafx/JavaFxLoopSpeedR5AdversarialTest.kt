package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * SUSPECT r5-D2-S1: on macOS the native player drops back to normal speed at the loop point of a
 * looping source, although JavaFX's rate property still reads the speed that was set (probe: the
 * second cycle at a speed of 4 moves the playhead at 1 ms per ms). The engine re-applies the rate
 * only in start().
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxLoopSpeedR5AdversarialTest {

    private var engine: JavaFxVideoEngine? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engine?.release()
    }

    private fun loaded(): JavaFxVideoEngine {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L).also { this.engine = it }
        runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
        return engine
    }

    @Test
    fun `a speed of four survives the loop point of a looping source`() {
        val engine: JavaFxVideoEngine = loaded()
        engine.setLooping(true)
        engine.setSpeed(4f)
        engine.start()
        // (milliseconds since the start, playhead) of every sample, grouped by cycle of the clip.
        val cycles: MutableList<MutableList<Pair<Long, Long>>> = mutableListOf(mutableListOf())
        var last: Long = 0L
        val begin: Long = System.nanoTime()
        while (System.nanoTime() - begin < 9_000L * 1_000_000L) {
            val position: Long = engine.positionMs()
            if (position + 1_000L < last) cycles += mutableListOf<Pair<Long, Long>>()
            last = position
            cycles.last() += ((System.nanoTime() - begin) / 1_000_000L) to position
            Thread.sleep(15L)
        }
        assertTrue(cycles.size >= 3, "the source wrapped only ${cycles.size - 1} times in 9 s")
        // The second cycle, the playhead between 300 ms and 2300 ms: it moves 4 ms per ms at 4x and 1 ms
        // per ms at 1x. (A cycle also starts with a pause at 0 that this window leaves out.)
        val window: List<Pair<Long, Long>> = cycles[1].filter { it.second in 300L..2_300L }
        assertTrue(window.size >= 2, "the second cycle had no samples between 300 ms and 2300 ms: ${cycles[1].size}")
        val slope: Double = (window.last().second - window.first().second).toDouble() /
            (window.last().first - window.first().first).toDouble()
        assertTrue(slope > 2.5, "the second cycle at a speed of 4 moved the playhead $slope ms per ms: the speed was lost at the loop point")
    }
}
