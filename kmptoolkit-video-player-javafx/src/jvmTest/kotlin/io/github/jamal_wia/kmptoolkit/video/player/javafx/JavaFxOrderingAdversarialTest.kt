package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Call ordering and micro-offset races of [JavaFxVideoEngine] against the real toolkit. Every wait
 * is bounded; skipped where JavaFX cannot run.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxOrderingAdversarialTest {

    private var engine: JavaFxVideoEngine? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engine?.release()
    }

    private fun drainFxThread(seconds: Long = 60L) {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        assertTrue(done.await(seconds, TimeUnit.SECONDS), "the FX thread did not drain within $seconds s")
    }

    private fun loaded(): JavaFxVideoEngine = JavaFxVideoEngine().also {
        engine = it
        runBlocking { withTimeout(20_000L) { it.load(VideoSource.Asset(TestClip.ASSET)) } }
    }

    private fun spin(iterations: Int) {
        var sink = 0
        repeat(iterations) { sink += it }
        if (sink == -1) println(sink)
    }

    @Test
    fun `three hundred alternating start and pause calls from one thread end in the state of the last one`() = runBlocking<Unit> {
        val e: JavaFxVideoEngine = loaded()
        for (lastIsStart: Boolean in listOf(false, true)) {
            repeat(300) { i: Int -> if ((i % 2 == 0) == lastIsStart) e.pause() else e.start() }
            if (lastIsStart) e.start() else e.pause()
            drainFxThread()
            // JavaFX reports its status through queued native events: give it a moment to settle.
            var status = ""
            awaitTrue(5_000L, message = { "after a final ${if (lastIsStart) "start" else "pause"} the status stayed $status" }) {
                status = runBlocking { withTimeout(10_000L) { e.inspectPlayback() }!!.status }
                if (lastIsStart) status == "PLAYING" || status == "STALLED" else status == "PAUSED"
            }
        }
    }

    @Test
    fun `settings stormed from one thread end at the last value written`() = runBlocking<Unit> {
        val e: JavaFxVideoEngine = loaded()
        val random = Random(5)
        var volume = 0f
        var speed = 1f
        repeat(300) {
            volume = random.nextInt(0, 11) / 10f
            speed = 0.5f + random.nextInt(0, 4) * 0.5f
            e.setVolume(volume)
            e.setSpeed(speed)
            e.setLooping(it % 2 == 0)
        }
        e.setLooping(false)
        drainFxThread()
        val seen = withTimeout(10_000L) { e.inspectPlayback() }!!
        assertEquals(volume.toDouble(), seen.volume, 0.0001, "volume")
        assertEquals(speed.toDouble(), seen.rate, 0.0001, "rate")
        assertEquals(1, seen.cycleCount, "looping off")
    }

    @Test
    fun `a hundred loads released at micro offsets all end and the engine still loads`() = runBlocking<Unit> {
        val e = JavaFxVideoEngine().also { engine = it }
        val random = Random(99)
        repeat(100) { round: Int ->
            val offset: Int = random.nextInt(0, 600_000)
            val load = async(Dispatchers.Default) { runCatching { e.load(VideoSource.Asset(TestClip.ASSET)) } }
            val racer = async(Dispatchers.Default) { spin(offset); e.release() }
            racer.await()
            val error: Throwable? = withTimeout(30_000L) { load.await() }.exceptionOrNull()
            assertTrue(
                error == null || error is VideoPlayerReleasedException || error is CancellationException,
                "round $round: ${error?.let { it::class.simpleName + ": " + it.message }}",
            )
            e.release()
            assertEquals(0L, e.durationMs(), "round $round: something stayed loaded")
        }
        drainFxThread()
        withTimeout(20_000L) { e.load(VideoSource.Asset(TestClip.ASSET)) }
        assertTrue(e.durationMs() > 0L)
    }
}
