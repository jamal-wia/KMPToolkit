package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@OptIn(ToolkitInternalApi::class)
class JavaFxTransportBacklogAdversarialTest {

    private fun drainFxMs(timeoutSeconds: Long): Long {
        val begun: Long = System.nanoTime()
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        assertTrue(done.await(timeoutSeconds, TimeUnit.SECONDS), "the FX thread did not drain within $timeoutSeconds s")
        return (System.nanoTime() - begun) / 1_000_000L
    }

    @Test
    fun `a burst of forty pause play seek rounds does not keep the FX thread busy for seconds`() {
        assumeJavaFxMedia()
        val engine = JavaFxVideoEngine()
        try {
            runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
            engine.start()
            Thread.sleep(300L)
            repeat(40) { round: Int ->
                engine.pause()
                engine.start()
                engine.seekTo(((round * 97) % 2_800).toLong())
            }
            val drained: Long = drainFxMs(60L)
            assertTrue(drained < 2_000L, "the FX thread needed $drained ms to work off 120 transport calls (no coalescing)")
            val last: Long = ((39 * 97) % 2_800).toLong()
            val position: Long = engine.positionMs()
            assertTrue(abs(position - last) <= 250L, "the player settles on the last target $last, reports $position")
        } finally {
            engine.release()
            drainFxMs(60L)
        }
    }
}
