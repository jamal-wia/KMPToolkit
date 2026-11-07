package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A toolkit that has exited drops every block handed to it; a load must still end. */
@OptIn(ToolkitInternalApi::class)
class FxThreadExitTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        FxThread.startToolkit()
    }

    @Test
    fun loadingAfterTheToolkitExitedFailsInsteadOfWaitingForever() {
        val dropped: MutableList<Runnable> = CopyOnWriteArrayList()
        val real: (Runnable) -> Unit = FxThread.runLater
        // After Platform.exit, runLater neither throws nor runs: it drops the block.
        FxThread.runLater = { dropped += it }
        try {
            val engine = JavaFxVideoEngine(runtime = { }, readyTimeoutMs = 200L)
            assertFailsWith<JavaFxVideoPlayerException.RuntimeUnavailable> {
                runBlocking {
                    withTimeout(5_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
                }
            }
        } finally {
            FxThread.runLater = real
            // Hand the dropped blocks to the real toolkit: they release the queued count.
            dropped.forEach { Platform.runLater(it) }
            drainFxThread()
        }
    }

    /** Waits until everything queued on the FX thread so far has run. */
    private fun drainFxThread() {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        assertTrue(done.await(5L, TimeUnit.SECONDS), "the FX thread did not drain")
    }
}
