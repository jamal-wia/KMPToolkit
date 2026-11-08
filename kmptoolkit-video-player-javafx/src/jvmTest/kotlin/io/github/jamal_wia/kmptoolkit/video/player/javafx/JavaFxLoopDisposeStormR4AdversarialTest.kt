package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 4: the dispose of a looping player (D-62 J4) under much shorter loops than the 1 s clip the
 * earlier rounds used, so the end-of-cycle event is queued on the FX thread many times a second; plus
 * the rate and volume guards at the finite extremes. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxLoopDisposeStormR4AdversarialTest {

    private val errors: MutableList<String> = CopyOnWriteArrayList()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            errors += "${t.name}: $e @ ${e.stackTrace.take(5).joinToString(" < ")}"
        }
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun drainFxThread() {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        assertTrue(done.await(10L, TimeUnit.SECONDS), "the FX thread did not drain")
    }

    /** A PCM wav of [millis] ms of silence, 8 kHz 8-bit mono. */
    private fun shortWav(millis: Int): File {
        val samples: Int = 8 * millis
        val buffer: ByteBuffer = ByteBuffer.allocate(44 + samples).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(8_000)
            .putShort(1).putShort(8)
        buffer.put("data".toByteArray()).putInt(samples)
        repeat(samples) { buffer.put(0x80.toByte()) }
        return File.createTempFile("kmptoolkit-short", ".wav").apply {
            deleteOnExit()
            writeBytes(buffer.array())
        }
    }

    private fun loadBounded(engine: JavaFxVideoEngine, source: VideoSource) = runBlocking {
        withTimeout(20_000L) { engine.load(source) }
    }

    @Test
    fun `releasing a looping 60 ms source at random moments throws nothing on the FX thread`() {
        val wav: File = shortWav(60)
        val random = Random(41)
        repeat(60) {
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            loadBounded(engine, VideoSource.File(wav.path))
            engine.setLooping(true)
            engine.start()
            Thread.sleep(random.nextLong(0L, 260L))
            engine.release()
            if (it % 6 == 0) drainFxThread()
        }
        Thread.sleep(300L)
        drainFxThread()
        assertEquals(emptyList(), errors.toList().distinct(), "uncaught exceptions on the FX thread")
    }

    @Test
    fun `releasing a looping 60 ms source at the fastest rate while seeks and loop switches are in flight throws nothing`() {
        val wav: File = shortWav(60)
        val random = Random(43)
        repeat(40) {
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            loadBounded(engine, VideoSource.File(wav.path))
            engine.setSpeed(8f)
            engine.setLooping(true)
            engine.start()
            val stop = AtomicBoolean(false)
            val flipper = thread(name = "flipper") {
                var on = true
                while (!stop.get()) {
                    engine.setLooping(on)
                    on = !on
                    engine.seekTo(random.nextLong(0L, 60L))
                    Thread.sleep(2L)
                }
            }
            Thread.sleep(random.nextLong(5L, 200L))
            engine.release()
            stop.set(true)
            flipper.join(5_000L)
            assertTrue(!flipper.isAlive, "flipper thread stuck")
        }
        Thread.sleep(300L)
        drainFxThread()
        assertEquals(emptyList(), errors.toList().distinct(), "uncaught exceptions on the FX thread")
    }

    @Test
    fun `eight concurrent players on 40 ms loops released at random moments throw nothing and all drain`() {
        val wav: File = shortWav(40)
        val engines: List<JavaFxVideoEngine> = List(8) { JavaFxVideoEngine(readyTimeoutMs = 15_000L) }
        engines.forEach { loadBounded(it, VideoSource.File(wav.path)) }
        val random = Random(47)
        val workers: List<Thread> = engines.mapIndexed { index, engine ->
            thread(name = "storm-$index") {
                val own = Random(index + 100)
                repeat(6) {
                    engine.setLooping(true)
                    engine.start()
                    Thread.sleep(own.nextLong(0L, 120L))
                    engine.pause()
                    engine.seekTo(0L)
                    if (own.nextBoolean()) engine.setLooping(false)
                }
            }
        }
        workers.forEach { it.join(30_000L) }
        assertTrue(workers.none { it.isAlive }, "a worker is stuck")
        engines.forEach { engine ->
            engine.setLooping(true)
            engine.start()
        }
        Thread.sleep(random.nextLong(10L, 150L))
        engines.forEach { it.release() }
        Thread.sleep(300L)
        drainFxThread()
        assertEquals(emptyList(), errors.toList().distinct(), "uncaught exceptions on the FX thread")
    }

    @Test
    fun `finite extreme rates and volumes give JavaFX a rate and volume it holds without an error`() = runBlocking<Unit> {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        loadBounded(engine, VideoSource.Asset(TestClip.ASSET))
        val seen: MutableList<String> = mutableListOf()
        for (rate: Float in listOf(Float.MIN_VALUE, 1e-10f, 0.03f, 8.5f, 1e30f, Float.MAX_VALUE)) {
            engine.setSpeed(rate)
            val inspected: FxPlayback.Inspection = withTimeout(5_000L) { checkNotNull(engine.inspectPlayback()) }
            seen += "rate $rate -> ${inspected.rate} (status ${inspected.status})"
        }
        for (volume: Float in listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e30f, -1e30f)) {
            engine.setVolume(volume)
            val inspected: FxPlayback.Inspection = withTimeout(5_000L) { checkNotNull(engine.inspectPlayback()) }
            seen += "volume $volume -> ${inspected.volume}"
            assertTrue(!inspected.volume.isNaN(), "volume $volume left JavaFX with NaN")
        }
        engine.start()
        Thread.sleep(400L)
        engine.release()
        drainFxThread()
        println("R4-RATES: " + seen.joinToString(" | "))
        assertEquals(emptyList(), errors.toList().distinct(), "uncaught exceptions on the FX thread")
    }
}
