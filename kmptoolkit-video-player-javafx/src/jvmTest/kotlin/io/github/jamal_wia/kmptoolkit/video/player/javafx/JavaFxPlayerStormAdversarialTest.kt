package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Many real players on many threads against the real toolkit: every player is driven from its own
 * thread (the documented rule), while other threads hit the toolkit-level probes. Every wait is
 * bounded; skipped where JavaFX cannot run.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxPlayerStormAdversarialTest {

    private val errors: MutableList<String> = CopyOnWriteArrayList()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> errors += "${t.name}: $e" }
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun drainFxThread(seconds: Long = 8L) {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        assertTrue(done.await(seconds, TimeUnit.SECONDS), "the FX thread did not drain within $seconds s")
    }

    private fun drive(player: VideoPlayer, random: Random, deadlineNanos: Long) {
        while (System.nanoTime() < deadlineNanos) {
            when (random.nextInt(12)) {
                0, 1 -> runBlocking {
                    try {
                        withTimeout(6_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) }
                    } catch (e: Exception) {
                        // A superseded, unloaded or timed-out prepare is an outcome, not a failure of the test.
                    }
                }
                2 -> player.play()
                3 -> player.pause()
                4 -> player.seekTo(random.nextLong(0L, 3_200L))
                5 -> player.setPlaybackSpeed(0.25f + random.nextFloat() * 3f)
                6 -> player.setVolume(random.nextFloat())
                7 -> player.unload()
                8 -> player.setRepeatMode(if (random.nextBoolean()) RepeatMode.One else RepeatMode.Off)
                9 -> player.seekForward(random.nextLong(1L, 2_000L))
                10 -> player.replay()
                else -> player.setMuted(random.nextBoolean())
            }
            if (random.nextInt(4) == 0) Thread.sleep(random.nextLong(0L, 8L))
        }
    }

    @Test
    fun `twelve players on twelve threads under prepare unload seek rate storms are all released and the FX thread drains`() {
        val players: List<VideoPlayer> = List(12) {
            createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 10_000L), VideoPlayerConfig(positionUpdateIntervalMs = 10L), Dispatchers.Default)
        }
        val deadline: Long = System.nanoTime() + 6_000_000_000L
        val drivers = players.mapIndexed { n, player -> thread(name = "storm-$n") { drive(player, Random(n * 31L), deadline) } }
        drivers.forEach { it.join(30_000L) }
        assertTrue(drivers.none { it.isAlive }, "a driver thread hung")
        val slowest = AtomicInteger(0)
        val releasers = players.map { player ->
            thread {
                val started: Long = System.nanoTime()
                player.release()
                slowest.accumulateAndGet(((System.nanoTime() - started) / 1_000_000L).toInt(), ::maxOf)
            }
        }
        releasers.forEach { it.join(15_000L) }
        assertTrue(releasers.none { it.isAlive }, "a release hung")
        assertTrue(slowest.get() < 3_000, "release took ${slowest.get()} ms")
        drainFxThread()
        Thread.sleep(300L)
        drainFxThread()
        assertEquals(emptyList(), errors.toList(), "uncaught exceptions during the storm")
    }

    @Test
    fun `thirty two threads asking whether the toolkit runs while twelve players play never see an exit or a slow answer`() {
        val players: List<VideoPlayer> = List(12) {
            createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 10_000L), VideoPlayerConfig(positionUpdateIntervalMs = 10L), Dispatchers.Default)
        }
        try {
            runBlocking { players.forEach { withTimeout(15_000L) { it.prepare(VideoSource.Asset(TestClip.ASSET)) } } }
            players.forEach { it.play() }
            val wrong = AtomicInteger(0)
            val slowest = AtomicInteger(0)
            val deadline: Long = System.nanoTime() + 3_000_000_000L
            val askers = List(32) {
                thread {
                    while (System.nanoTime() < deadline) {
                        val started: Long = System.nanoTime()
                        if (!isJavaFxMediaAvailable()) wrong.incrementAndGet()
                        slowest.accumulateAndGet(((System.nanoTime() - started) / 1_000_000L).toInt(), ::maxOf)
                    }
                }
            }
            askers.forEach { it.join(20_000L) }
            assertTrue(askers.none { it.isAlive }, "an asking thread hung")
            assertEquals(0, wrong.get(), "isJavaFxMediaAvailable() answered false for a running toolkit")
            assertTrue(slowest.get() < 1_500, "the slowest answer took ${slowest.get()} ms")
        } finally {
            players.forEach { it.release() }
            drainFxThread()
        }
        assertEquals(emptyList(), errors.toList())
    }

    @Test
    fun `a stall of the FX thread shorter than the silence bound never fails a load or a probe`() {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        try {
            Platform.runLater { Thread.sleep(1_200L) }
            runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
            assertTrue(isJavaFxMediaAvailable())
            Platform.runLater { Thread.sleep(1_200L) }
            assertTrue(isJavaFxMediaAvailable(), "a 1.2 s stall was taken for an exit")
        } finally {
            engine.release()
            drainFxThread()
        }
    }

    @Test
    fun `after a stall that was taken for an exit the next load works once the thread answers`() {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        try {
            val stalled = CountDownLatch(1)
            Platform.runLater { Thread.sleep(2_600L); stalled.countDown() }
            val outcome: Result<Unit> =
                runCatching { runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } } }
            outcome.exceptionOrNull()?.let {
                assertTrue(it is JavaFxVideoPlayerException.RuntimeUnavailable, "unexpected $it")
            }
            assertTrue(stalled.await(10L, TimeUnit.SECONDS))
            drainFxThread()
            engine.release()
            runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
            assertTrue(isJavaFxMediaAvailable(), "a recovered thread must count as running again")
        } finally {
            engine.release()
            drainFxThread()
        }
    }
}
