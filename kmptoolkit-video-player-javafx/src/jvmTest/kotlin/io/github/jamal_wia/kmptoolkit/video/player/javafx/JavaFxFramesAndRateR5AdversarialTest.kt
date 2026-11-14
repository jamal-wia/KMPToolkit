package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.IdentityHashMap
import javafx.application.Platform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 5, contract lawyer, real JavaFX: the documented `maxFrameRate` cap ("the most snapshots per
 * second copied into frames while playing"), the frame contract of `VideoFrame` (a fresh array per
 * frame, never written after it was emitted, an opaque picture of the source's size), and a speed
 * that survives the loop point of a looping source. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxFramesAndRateR5AdversarialTest {

    private val engines: MutableList<JavaFxVideoEngine> = mutableListOf()

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engines.forEach { it.release() }
        if (engines.isNotEmpty()) {
            val done = CountDownLatch(1)
            Platform.runLater { done.countDown() }
            assertTrue(done.await(10L, TimeUnit.SECONDS), "the FX thread did not drain")
        }
    }

    private fun loaded(maxFrameRate: Int = JavaFxVideoEngine.DEFAULT_MAX_FRAME_RATE): JavaFxVideoEngine {
        val engine = JavaFxVideoEngine(maxFrameRate = maxFrameRate, readyTimeoutMs = 15_000L).also { engines += it }
        runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
        return engine
    }

    /** Distinct frames seen by polling `frames.value` every 2 ms for [windowMs]; hashes taken at first sight. */
    private fun observeFrames(engine: JavaFxVideoEngine, windowMs: Long): Pair<IdentityHashMap<VideoFrame, Int>, Int> {
        val seen = IdentityHashMap<VideoFrame, Int>()
        var arrays = 0
        val end: Long = System.nanoTime() + windowMs * 1_000_000L
        while (System.nanoTime() < end) {
            val frame: VideoFrame? = engine.frames.value
            if (frame != null && !seen.containsKey(frame)) {
                seen[frame] = frame.pixels.contentHashCode()
                arrays++
            }
            Thread.sleep(2L)
        }
        return seen to arrays
    }

    @Test
    fun `a frame rate cap of five gives at most about five distinct frames per second`() {
        val engine: JavaFxVideoEngine = loaded(maxFrameRate = 5)
        engine.start()
        awaitTrue(message = { "no first frame at a cap of 5" }) { engine.frames.value != null }
        val (seen: IdentityHashMap<VideoFrame, Int>, _) = observeFrames(engine, windowMs = 1_500L)
        // 1.5 s at 5 per second is 7.5; the window may straddle a snapshot at each end.
        assertTrue(seen.size <= 10, "a cap of 5 fps gave ${seen.size} distinct frames in 1.5 s")
        assertTrue(seen.size >= 2, "a cap of 5 fps gave only ${seen.size} distinct frames in 1.5 s while playing")
    }

    @Test
    fun `every frame has its own array of the source's size that nothing writes after it was emitted`() {
        val engine: JavaFxVideoEngine = loaded(maxFrameRate = 30)
        engine.start()
        awaitTrue(message = { "no first frame" }) { engine.frames.value != null }
        val (seen: IdentityHashMap<VideoFrame, Int>, _) = observeFrames(engine, windowMs = 1_500L)
        assertTrue(seen.size >= 5, "only ${seen.size} distinct frames in 1.5 s at a cap of 30")
        val arrays = IdentityHashMap<IntArray, Boolean>()
        for ((frame: VideoFrame, hash: Int) in seen) {
            assertEquals(TestClip.WIDTH, frame.width)
            assertEquals(TestClip.HEIGHT, frame.height)
            assertEquals(TestClip.WIDTH * TestClip.HEIGHT, frame.pixels.size)
            assertNull(arrays.put(frame.pixels, true), "two frames share one pixel array")
            val centre: Int = frame.pixels[(TestClip.HEIGHT / 2) * TestClip.WIDTH + TestClip.WIDTH / 2]
            assertEquals(0xFF, centre ushr 24, "the picture is not opaque")
            val red: Int = (centre shr 16) and 0xFF
            val green: Int = (centre shr 8) and 0xFF
            val blue: Int = centre and 0xFF
            assertTrue(red > 180 && green < 90 && blue < 90, "the red clip's centre pixel is ${Integer.toHexString(centre)}")
        }
        // Later frames, a pause and a seek did not write into an earlier one.
        engine.pause()
        engine.seekTo(500L)
        Thread.sleep(700L)
        for ((frame: VideoFrame, hash: Int) in seen) {
            assertEquals(hash, frame.pixels.contentHashCode(), "a frame was written after it was emitted")
        }
    }

    @Test
    fun `a speed set while paused is played from the next start`() {
        val engine: JavaFxVideoEngine = loaded()
        engine.start()
        awaitTrue(message = { "the clip never advanced" }) { engine.positionMs() > 200L }
        engine.pause()
        Thread.sleep(300L)
        engine.setSpeed(8f)
        val listener = RecordingListener()
        engine.setListener(listener)
        val before: Long = engine.positionMs()
        engine.start()
        // At 8x the rest of a 3 s clip takes under half a second; at 1x it would take over 2 s.
        awaitTrue(timeoutMs = 2_000L, message = { "a speed of 8 set while paused was not played: position ${engine.positionMs()} from $before" }) {
            listener.events.contains(EngineEvent.Completed)
        }
        assertNotNull(listener.events.firstOrNull { it == EngineEvent.Completed })
    }
}
