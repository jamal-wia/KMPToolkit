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
 * Round 5, real JavaFX: on macOS the native player has been seen dropping back to normal speed at
 * moments the rate property does not notice (the engine's own comment about play(); the loop point
 * is the open one). A speed the player was given must be the speed the playhead moves at after a
 * speed change during playback, after a seek during playback and after a pause and resume. Every
 * wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSpeedSurvivalR5AdversarialTest {

    private var engine: JavaFxVideoEngine? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engine?.release()
        if (engine != null) {
            val done = CountDownLatch(1)
            Platform.runLater { done.countDown() }
            assertTrue(done.await(10L, TimeUnit.SECONDS), "the FX thread did not drain")
        }
    }

    private fun loaded(): JavaFxVideoEngine {
        val created = JavaFxVideoEngine(readyTimeoutMs = 15_000L).also { engine = it }
        runBlocking { withTimeout(20_000L) { created.load(VideoSource.Asset(TestClip.ASSET)) } }
        return created
    }

    /** The playhead's speed in ms per ms over [windowMs]. */
    private fun slope(engine: JavaFxVideoEngine, windowMs: Long): Double {
        val first: Long = engine.positionMs()
        val begin: Long = System.nanoTime()
        Thread.sleep(windowMs)
        val elapsedMs: Double = (System.nanoTime() - begin) / 1_000_000.0
        return (engine.positionMs() - first) / elapsedMs
    }

    @Test
    fun `a speed raised during playback is played`() {
        val engine: JavaFxVideoEngine = loaded()
        engine.start()
        awaitTrue(message = { "the clip never advanced" }) { engine.positionMs() > 150L }
        engine.setSpeed(2f)
        Thread.sleep(300L)
        val speed: Double = slope(engine, 600L)
        assertTrue(speed > 1.5, "a speed of 2 set during playback moved the playhead $speed ms per ms")
    }

    @Test
    fun `a seek during playback at a speed of two keeps the speed`() {
        val engine: JavaFxVideoEngine = loaded()
        engine.setSpeed(2f)
        engine.start()
        awaitTrue(message = { "the clip never advanced" }) { engine.positionMs() > 400L }
        engine.seekTo(100L)
        // The first reading after a seek may still be the old one; wait for the playhead to be moving again.
        awaitTrue(message = { "the playhead never moved on after the seek: ${engine.positionMs()}" }) {
            engine.positionMs() in 250L..900L
        }
        val speed: Double = slope(engine, 600L)
        assertTrue(speed > 1.5, "after a seek the playhead moved $speed ms per ms at a speed of 2")
    }

    @Test
    fun `a speed lowered during playback is played`() {
        val engine: JavaFxVideoEngine = loaded()
        engine.setSpeed(4f)
        engine.start()
        awaitTrue(message = { "the clip never advanced" }) { engine.positionMs() > 300L }
        engine.setSpeed(0.5f)
        Thread.sleep(500L)
        val speed: Double = slope(engine, 800L)
        assertTrue(speed < 0.8, "a speed of 0.5 set during playback moved the playhead $speed ms per ms")
        assertTrue(speed > 0.2, "a speed of 0.5 set during playback moved the playhead only $speed ms per ms")
    }
}
