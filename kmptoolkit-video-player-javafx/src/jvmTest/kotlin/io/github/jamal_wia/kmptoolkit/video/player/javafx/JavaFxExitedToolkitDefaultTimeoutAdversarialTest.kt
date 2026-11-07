package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@OptIn(ToolkitInternalApi::class)
class JavaFxExitedToolkitDefaultTimeoutAdversarialTest {

    private fun drainFxMs(timeoutSeconds: Long): Long {
        val begun: Long = System.nanoTime()
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        assertTrue(done.await(timeoutSeconds, TimeUnit.SECONDS), "the FX thread did not drain within $timeoutSeconds s")
        return (System.nanoTime() - begun) / 1_000_000L
    }

    @Test
    fun `after the toolkit exited a load with the default timeout fails within five seconds`() {
        assumeJavaFxMedia()
        FxThread.startToolkit()
        val dropped: MutableList<Runnable> = CopyOnWriteArrayList()
        val realRunLater: (Runnable) -> Unit = FxThread.runLater
        val realStartup: (Runnable) -> Unit = FxThread.startup
        FxThread.runLater = { dropped += it }
        FxThread.startup = { throw IllegalStateException("Platform.exit has been called") }
        try {
            val engine = JavaFxVideoEngine()
            val begun: Long = System.nanoTime()
            val failure: Throwable? = runCatching {
                runBlocking { withTimeout(5_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
            }.exceptionOrNull()
            val tookMs: Long = (System.nanoTime() - begun) / 1_000_000L
            assertTrue(
                failure is JavaFxVideoPlayerException.RuntimeUnavailable,
                "a load on an exited toolkit must fail on RuntimeUnavailable, got $failure after $tookMs ms",
            )
            assertTrue(tookMs < 1_000L, "the exit is detected by a probe, not by waiting: took $tookMs ms")
        } finally {
            FxThread.runLater = realRunLater
            FxThread.startup = realStartup
            dropped.forEach { Platform.runLater(it) }
            drainFxMs(10L)
        }
    }
}
