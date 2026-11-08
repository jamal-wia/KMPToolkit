package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The platform notes' behaviour sentences, tested literally on the real toolkit. */
@OptIn(ToolkitInternalApi::class)
class JavaFxDocumentedBehaviourAdversarialTest {

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

    @Test
    fun `while looping Completed is never reported and the playhead wraps around`() {
        engine.setLooping(true)
        engine.start()
        var highest = 0L
        var wrapped = false
        val deadline: Long = System.nanoTime() + 6_000_000_000L
        while (System.nanoTime() < deadline) {
            val position: Long = engine.positionMs()
            if (position > highest) highest = position
            if (highest > 2_000L && position < 1_000L) wrapped = true
            Thread.sleep(25L)
        }
        assertTrue(wrapped, "the playhead never wrapped around in 6 s of a 3 s clip (highest $highest)")
        assertEquals(0, listener.events.count { it == EngineEvent.Completed }, "Completed was reported while looping")
    }

    @Test
    fun `a seek from Completed stays paused in JavaFX until start`() {
        engine.start()
        awaitTrue(timeoutMs = 10_000L, message = { "Completed was not reported" }) { listener.events.contains(EngineEvent.Completed) }
        engine.seekTo(1_500L)
        Thread.sleep(900L)
        val held: Long = engine.positionMs()
        val status: String = runBlocking { withTimeout(5_000L) { checkNotNull(engine.inspectPlayback()).status } }
        assertTrue(held in 900L..1_600L, "the playhead moved on after the seek from Completed: $held ms")
        assertTrue(status != "PLAYING", "JavaFX is $status after a seek from Completed")
        engine.start()
        awaitTrue(timeoutMs = 5_000L, message = { "the playhead did not advance after start" }) { engine.positionMs() > held + 300L }
    }

    @Test
    fun `a local file reports its whole duration as buffered`() {
        awaitTrue(timeoutMs = 5_000L, message = { "no duration" }) { engine.durationMs() > 0L }
        assertEquals(engine.durationMs(), engine.bufferedPositionMs())
        assertEquals(TestClip.DURATION_MS, engine.durationMs(), "the duration of the 3 s clip")
    }

    @Test
    fun `start from the end after Completed plays the source again from its beginning`() {
        engine.start()
        awaitTrue(timeoutMs = 10_000L, message = { "Completed was not reported" }) { listener.events.contains(EngineEvent.Completed) }
        engine.start()
        awaitTrue(timeoutMs = 5_000L, message = { "the source did not restart from the beginning" }) {
            engine.positionMs() in 100L..2_000L
        }
        val status: String = runBlocking { withTimeout(5_000L) { checkNotNull(engine.inspectPlayback()).status } }
        assertEquals("PLAYING", status)
    }
}
