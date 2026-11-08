package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Adversarial tests of [JavaFxVideoEngine] against the real toolkit: seek storms, release storms,
 * callbacks on the FX thread that call back into the engine, and repeated create/release cycles.
 * Every wait is bounded; skipped where JavaFX cannot run.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxEngineAdversarialTest {

    private val engines: MutableList<JavaFxVideoEngine> = CopyOnWriteArrayList()

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engines.forEach { it.release() }
        if (engines.isNotEmpty()) drainFxThread()
    }

    private fun newEngine(readyTimeoutMs: Long = 15_000L): JavaFxVideoEngine =
        JavaFxVideoEngine(readyTimeoutMs = readyTimeoutMs).also { engines += it }

    private fun load(engine: JavaFxVideoEngine) = runBlocking {
        withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
    }

    private fun drainFxThread(seconds: Long = 5L) {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        if (!done.await(seconds, TimeUnit.SECONDS)) {
            val fx: Map.Entry<Thread, Array<StackTraceElement>>? =
                Thread.getAllStackTraces().entries.firstOrNull { it.key.name == "JavaFX Application Thread" }
            val trace: String = fx?.value?.joinToString("\n   at ") { it.toString() } ?: "no FX thread"
            throw AssertionError("the FX thread did not drain within 5 s; it is ${fx?.key?.state}:\n   at $trace")
        }
    }

    private fun liveThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive }

    @Test
    fun `a seek storm from eight threads leaves the playhead at the last target, not stuck at an earlier one`() {
        val engine: JavaFxVideoEngine = newEngine()
        load(engine)
        repeat(3) { round: Int ->
            val workers = List(8) { n ->
                thread {
                    val random = Random(round * 100 + n)
                    repeat(25) { engine.seekTo(random.nextLong(0L, 2_800L)) }
                }
            }
            workers.forEach { it.join(15_000L) }
            assertTrue(workers.none { it.isAlive }, "round $round: a seeking thread hung")
            val target: Long = 1_000L + round * 400L
            engine.seekTo(target)
            assertEquals(target, engine.positionMs(), "round $round: the target is reported at once")
            awaitTrue(message = { "round $round: position ${engine.positionMs()} never settled near $target" }) {
                abs(engine.positionMs() - target) <= 250L
            }
            Thread.sleep(300L)
            assertTrue(abs(engine.positionMs() - target) <= 250L, "round $round: the playhead drifted to ${engine.positionMs()} from $target")
        }
    }

    @Test
    fun `eight threads releasing while frames flow free once and nothing arrives after`() {
        repeat(5) { round: Int ->
            val engine: JavaFxVideoEngine = newEngine()
            val allReturned = AtomicBoolean(false)
            val late = AtomicInteger()
            engine.setListener(object : VideoPlaybackEngineListener {
                override fun onCompleted() { if (allReturned.get()) late.incrementAndGet() }
                override fun onFailed(cause: Throwable) { if (allReturned.get()) late.incrementAndGet() }
                override fun onBufferingChanged(isBuffering: Boolean) { if (allReturned.get()) late.incrementAndGet() }
                override fun onVideoSizeChanged(size: VideoSize?) { if (allReturned.get()) late.incrementAndGet() }
            })
            load(engine)
            engine.start()
            awaitTrue(message = { "round $round: no frame" }) { engine.frames.value != null }
            val gate = CountDownLatch(1)
            val callers = List(8) {
                thread {
                    gate.await(5, TimeUnit.SECONDS)
                    engine.release()
                }
            }
            gate.countDown()
            callers.forEach { it.join(10_000L) }
            assertTrue(callers.none { it.isAlive }, "round $round: a releasing thread hung")
            allReturned.set(true)
            drainFxThread()
            Thread.sleep(200L)
            assertNull(engine.frames.value, "round $round: a frame survived the release")
            assertEquals(0, late.get(), "round $round: events after every release returned")
            assertEquals(0L, engine.positionMs())
        }
    }

    @Test
    fun `releasing from inside the completion callback on the FX thread leaves the engine reusable`() {
        val engine: JavaFxVideoEngine = newEngine()
        val completed = CountDownLatch(1)
        val fxThread = AtomicBoolean(false)
        engine.setListener(object : VideoPlaybackEngineListener {
            override fun onCompleted() {
                fxThread.set(Platform.isFxApplicationThread())
                engine.release()
                completed.countDown()
            }
            override fun onFailed(cause: Throwable) = Unit
            override fun onBufferingChanged(isBuffering: Boolean) = Unit
            override fun onVideoSizeChanged(size: VideoSize?) = Unit
        })
        load(engine)
        engine.seekTo(TestClip.DURATION_MS - 200L)
        engine.start()
        assertTrue(completed.await(10L, TimeUnit.SECONDS), "completion never arrived")
        assertTrue(fxThread.get(), "the callback was expected on the FX thread")
        drainFxThread()
        assertEquals(0L, engine.positionMs())
        assertNull(engine.frames.value)
        engine.setListener(null)
        load(engine)
        assertTrue(abs(engine.durationMs() - TestClip.DURATION_MS) <= 100L, "a fresh load after the in-callback release works")
    }

    @Test
    fun `releasing from inside the size callback during the load ends that load instead of hanging it`() {
        repeat(5) { round: Int ->
            val engine: JavaFxVideoEngine = newEngine()
            engine.setListener(object : VideoPlaybackEngineListener {
                override fun onCompleted() = Unit
                override fun onFailed(cause: Throwable) = Unit
                override fun onBufferingChanged(isBuffering: Boolean) = Unit
                override fun onVideoSizeChanged(size: VideoSize?) {
                    if (size != null) engine.release()
                }
            })
            val outcome: Throwable? = runCatching { load(engine) }.exceptionOrNull()
            assertTrue(
                outcome is VideoPlayerReleasedException || outcome is CancellationException,
                "round $round: the released load ended with $outcome",
            )
            drainFxThread()
            assertEquals(0L, engine.durationMs())
            assertNull(engine.frames.value)
            engine.setListener(null)
            load(engine)
            assertTrue(abs(engine.durationMs() - TestClip.DURATION_MS) <= 100L, "round $round: reusable afterwards")
            engine.release()
        }
    }

    @Test
    fun `twenty loads released after a random delay all end and the engine still loads`() = runBlocking<Unit> {
        val engine: JavaFxVideoEngine = newEngine()
        val random = Random(7)
        repeat(20) { round: Int ->
            val job = async(Dispatchers.Default) { runCatching { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
            delay(random.nextLong(0L, 40L))
            engine.release()
            val outcome: Result<Unit> = withTimeout(20_000L) { job.await() }
            val error: Throwable? = outcome.exceptionOrNull()
            assertTrue(
                error == null || error is VideoPlayerReleasedException || error is CancellationException,
                "round $round: ${error?.let { it::class.simpleName + ": " + it.message }}",
            )
            engine.release()
            assertEquals(0L, engine.durationMs(), "round $round: nothing stays loaded after the release")
        }
        withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
        assertTrue(abs(engine.durationMs() - TestClip.DURATION_MS) <= 100L)
    }

    @Test
    fun `thirty loads cancelled at a random moment all unwind and leave the engine reusable`() = runBlocking<Unit> {
        val engine: JavaFxVideoEngine = newEngine()
        val random = Random(11)
        repeat(30) { round: Int ->
            val job = launch(Dispatchers.Default) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
            delay(random.nextLong(0L, 60L))
            withTimeout(20_000L) { job.cancelAndJoin() }
            engine.release()
            assertEquals(0L, engine.durationMs(), "round $round: nothing stays loaded after the cancellation")
        }
        drainFxThread()
        withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
        assertTrue(abs(engine.durationMs() - TestClip.DURATION_MS) <= 100L)
    }

    @Test
    fun `thirty create load start release cycles do not accumulate threads and the FX queue drains`() {
        // Warm up: the first player starts the toolkit's and the media framework's own threads.
        newEngine().also { load(it); it.start(); Thread.sleep(100L); it.release() }
        drainFxThread()
        System.gc()
        val baseline: Int = liveThreads()
        repeat(30) {
            val engine: JavaFxVideoEngine = newEngine()
            load(engine)
            engine.start()
            Thread.sleep(30L)
            engine.release()
        }
        drainFxThread()
        engines.clear()
        System.gc()
        Thread.sleep(500L)
        val after: Int = liveThreads()
        assertTrue(after <= baseline + 6, "live threads grew from $baseline to $after over 30 create/release cycles")
    }

    @Test
    fun `a listener that throws from the completion callback does not wedge the engine`() {
        val engine: JavaFxVideoEngine = newEngine()
        val completions = AtomicInteger()
        engine.setListener(object : VideoPlaybackEngineListener {
            override fun onCompleted() {
                completions.incrementAndGet()
                throw IllegalStateException("listener failure")
            }
            override fun onFailed(cause: Throwable) = Unit
            override fun onBufferingChanged(isBuffering: Boolean) = Unit
            override fun onVideoSizeChanged(size: VideoSize?) = Unit
        })
        load(engine)
        repeat(2) { round: Int ->
            engine.seekTo(TestClip.DURATION_MS - 300L)
            engine.start()
            awaitTrue(10_000L, message = { "round $round: completions ${completions.get()}" }) { completions.get() == round + 1 }
        }
        engine.release()
        drainFxThread()
    }

    @Test
    fun `a player under a transport storm from eight threads is released with nothing left running`() = runBlocking<Unit> {
        val player = createVideoPlayer(JavaFxVideoEngine(), VideoPlayerConfig(positionUpdateIntervalMs = 10L), Dispatchers.Default)
        withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) }
        player.play()
        val workers = List(8) { n ->
            thread {
                repeat(60) { i: Int ->
                    when ((i + n) % 6) {
                        0 -> player.seekTo(((i * 53) % 2_800).toLong())
                        1 -> player.pause()
                        2 -> player.play()
                        3 -> player.setVolume((i % 10) / 10f)
                        4 -> player.setPlaybackSpeed(0.5f + (i % 4) * 0.5f)
                        else -> player.seekForward(300L)
                    }
                    Thread.sleep(2L)
                }
            }
        }
        workers.forEach { it.join(30_000L) }
        assertTrue(workers.none { it.isAlive }, "a transport caller hung")
        withTimeout(10_000L) { player.release() }
        // Every pause/play/seek is a block of real FX-thread work (see the suspects); wait it out.
        drainFxThread(90L)
        assertTrue(player.stateFlow.value !is VideoPlayerState.Playing, "state after release: ${player.stateFlow.value}")
    }
}
