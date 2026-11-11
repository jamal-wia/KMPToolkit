package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 5, real JavaFX: the picture follows a seek made while paused (the pulse's refresh window), a
 * second load never shows a frame of the first, a local source never reports buffering, and the
 * settings given to the engine reach every source it loads. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxPictureAndBufferingR5AdversarialTest {

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

    private fun newEngine(): JavaFxVideoEngine = JavaFxVideoEngine(readyTimeoutMs = 15_000L).also { engine = it }

    private fun load(engine: JavaFxVideoEngine) {
        runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
    }

    @Test
    fun `a seek made while paused publishes a new picture`() {
        val engine: JavaFxVideoEngine = newEngine()
        load(engine)
        engine.start()
        awaitTrue(message = { "no first frame" }) { engine.frames.value != null }
        engine.pause()
        Thread.sleep(600L)
        val before: VideoFrame = assertNotNull(engine.frames.value)
        engine.seekTo(1_500L)
        awaitTrue(timeoutMs = 3_000L, message = { "no new frame after a seek made while paused" }) {
            engine.frames.value.let { it != null && it !== before }
        }
        // The pulse stops again afterwards: no frame is copied for ever while nothing changes.
        Thread.sleep(1_200L)
        val settled: VideoFrame? = engine.frames.value
        Thread.sleep(600L)
        assertTrue(engine.frames.value === settled, "frames kept coming while paused and idle")
    }

    @Test
    fun `a second load never shows a frame of the first and the first frame is a new one`() {
        val engine: JavaFxVideoEngine = newEngine()
        load(engine)
        engine.start()
        awaitTrue(message = { "no first frame" }) { engine.frames.value != null }
        val first: VideoFrame = assertNotNull(engine.frames.value)
        engine.release()
        assertNull(engine.frames.value, "a released engine still shows a frame")
        load(engine)
        assertNull(engine.frames.value, "a loaded engine shows a frame before the first start")
        engine.start()
        awaitTrue(message = { "no frame from the second load" }) { engine.frames.value != null }
        assertNotSame(first, engine.frames.value)
    }

    @Test
    fun `a local source never reports buffering through starts seeks pauses and the end`() {
        val engine: JavaFxVideoEngine = newEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        load(engine)
        engine.setSpeed(2f)
        engine.start()
        awaitTrue(message = { "the clip never advanced" }) { engine.positionMs() > 250L }
        repeat(4) { round: Int ->
            engine.seekTo(300L + round * 400L)
            Thread.sleep(150L)
            engine.pause()
            Thread.sleep(150L)
            engine.start()
        }
        awaitTrue(timeoutMs = 8_000L, message = { "the clip never completed" }) {
            listener.events.contains(EngineEvent.Completed)
        }
        val buffering: List<EngineEvent> = listener.events.filterIsInstance<EngineEvent.Buffering>()
        assertTrue(buffering.none { (it as EngineEvent.Buffering).isBuffering }, "a local clip reported buffering: $buffering")
    }

    @Test
    fun `settings given before the first load reach every source the engine loads`() {
        val engine: JavaFxVideoEngine = newEngine()
        engine.setSpeed(2f)
        engine.setVolume(0.3f)
        engine.setLooping(true)
        repeat(3) { round: Int ->
            load(engine)
            val inspection: FxPlayback.Inspection = assertNotNull(runBlocking { engine.inspectPlayback() })
            assertEquals(2.0, inspection.rate, "round $round: rate")
            assertEquals(0.3f.toDouble(), inspection.volume, 1e-6, "round $round: volume")
            assertEquals(javafx.scene.media.MediaPlayer.INDEFINITE, inspection.cycleCount, "round $round: cycle count")
            engine.release()
        }
    }
}
