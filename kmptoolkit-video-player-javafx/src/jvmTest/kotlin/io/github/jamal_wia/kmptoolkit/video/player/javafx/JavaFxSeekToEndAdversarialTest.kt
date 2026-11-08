package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Seeking onto the end of a source while it plays or is paused: a source that is at its end must
 * complete (the core's contract is that a playing source that reaches its end reports Completed),
 * never sit at the end reporting nothing. Parity with the end-of-source decisions of the core.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSeekToEndAdversarialTest {

    private val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
    private val listener = RecordingListener()

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        engine.setListener(listener)
        runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
    }

    @AfterTest
    fun tearDown() {
        engine.release()
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        done.await(10L, TimeUnit.SECONDS)
    }

    private fun completions(): Int = listener.events.count { it == EngineEvent.Completed }

    @Test
    fun `seeking onto the duration while playing completes`() {
        engine.start()
        Thread.sleep(300L)
        engine.seekTo(TestClip.DURATION_MS)
        awaitTrue(timeoutMs = 6_000L, message = { "no Completed after seeking onto the end while playing" }) { completions() >= 1 }
    }

    @Test
    fun `seeking far past the duration while playing completes`() {
        engine.start()
        Thread.sleep(300L)
        engine.seekTo(TestClip.DURATION_MS * 1_000L)
        awaitTrue(timeoutMs = 6_000L, message = { "no Completed after seeking past the end while playing" }) { completions() >= 1 }
    }

    @Test
    fun `starting after a seek onto the duration while paused completes`() {
        engine.seekTo(TestClip.DURATION_MS)
        Thread.sleep(300L)
        engine.start()
        awaitTrue(timeoutMs = 6_000L, message = { "no Completed after start from a seek onto the end" }) { completions() >= 1 }
    }

    @Test
    fun `a seek onto the duration just before the natural end completes exactly once`() {
        engine.seekTo(TestClip.DURATION_MS - 200L)
        engine.start()
        Thread.sleep(200L)
        engine.seekTo(TestClip.DURATION_MS)
        awaitTrue(timeoutMs = 6_000L, message = { "no Completed" }) { completions() >= 1 }
        Thread.sleep(1_500L)
        assertTrue(completions() == 1, "Completed was reported ${completions()} times")
    }
}
