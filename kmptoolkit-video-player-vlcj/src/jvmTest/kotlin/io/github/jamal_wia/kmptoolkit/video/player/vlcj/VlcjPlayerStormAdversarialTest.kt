package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
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
 * Ten real [VideoPlayer]s over the VLC engine and a fake libvlc each, every one driven from its own
 * thread (prepare with a short random timeout, transport, settings, unload, release) while a second
 * thread per player fires libvlc events. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjPlayerStormAdversarialTest {

    private val errors: MutableList<String> = CopyOnWriteArrayList()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val runtimes: MutableList<FakeVlcRuntime> = CopyOnWriteArrayList()

    @BeforeTest
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> errors += "${t.name}: $e" }
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        runtimes.forEach { it.shutdown() }
    }

    @Test
    fun `ten players on ten threads with event storms free every native player once and the instance once`() {
        val rts: List<FakeVlcRuntime> = List(10) { n ->
            // Every third player's parser never answers, so its prepares end only by timeout or cancel.
            FakeVlcRuntime().also { it.parseResult = if (n % 3 == 0) null else VlcParseStatus.DONE; runtimes += it }
        }
        val players: List<VideoPlayer> = rts.map { rt ->
            createVlcjVideoPlayer(
                VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { rt }),
                VideoPlayerConfig(positionUpdateIntervalMs = 10L),
                Dispatchers.Default,
            )
        }
        val deadline: Long = System.nanoTime() + 3_000_000_000L
        val stopEvents = java.util.concurrent.atomic.AtomicBoolean(false)
        val eventThreads = rts.mapIndexed { n, rt ->
            thread(name = "events-$n") {
                val random = Random(n + 500)
                while (!stopEvents.get()) {
                    val player: FakeVlcPlayer = rt.players.lastOrNull() ?: continue
                    runCatching {
                        when (random.nextInt(6)) {
                            0 -> player.fire { playing() }
                            1 -> player.fire { timeChanged(random.nextLong(0L, 2_000L)) }
                            2 -> player.fire { finished() }
                            3 -> player.fire { buffering(random.nextInt(101).toFloat()) }
                            4 -> player.fire { lengthChanged(2_000L) }
                            else -> player.fire { error() }
                        }
                    }
                    Thread.sleep(1L)
                }
            }
        }
        val drivers = players.mapIndexed { n, player ->
            thread(name = "driver-$n") {
                val random = Random(n)
                while (System.nanoTime() < deadline) {
                    when (random.nextInt(11)) {
                        0, 1 -> runBlocking {
                            runCatching {
                                withTimeout(random.nextLong(5L, 120L)) { player.prepare(VideoSource.File(TestClip.file.path)) }
                            }
                        }
                        2 -> player.play()
                        3 -> player.pause()
                        4 -> player.seekTo(random.nextLong(0L, 2_200L))
                        5 -> player.setPlaybackSpeed(0.5f + random.nextFloat() * 2f)
                        6 -> player.setVolume(random.nextFloat())
                        7 -> player.unload()
                        8 -> player.setRepeatMode(if (random.nextBoolean()) RepeatMode.One else RepeatMode.Off)
                        9 -> player.seekForward(random.nextLong(1L, 1_500L))
                        else -> player.replay()
                    }
                    Thread.sleep(random.nextLong(0L, 4L))
                }
            }
        }
        drivers.forEach { it.join(30_000L) }
        assertTrue(drivers.none { it.isAlive }, "a driver hung")
        val releasers = players.map { thread { it.release() } }
        releasers.forEach { it.join(15_000L) }
        assertTrue(releasers.none { it.isAlive }, "a release hung")
        stopEvents.set(true)
        eventThreads.forEach { it.join(15_000L) }
        assertTrue(eventThreads.none { it.isAlive }, "an event thread hung")
        val waitUntil: Long = System.nanoTime() + 15_000_000_000L
        for ((n: Int, rt: FakeVlcRuntime) in rts.withIndex()) {
            while ((rt.released < 1 || rt.players.any { it.calls.count { c -> c == "release" } < 1 }) && System.nanoTime() < waitUntil) {
                Thread.sleep(20L)
            }
            if (rt.players.isEmpty()) continue
            assertEquals(1, rt.released, "runtime $n: libvlc freed ${rt.released} times")
            for (p in rt.players) assertEquals(1, p.calls.count { it == "release" }, "runtime $n: a native player released ${p.calls.count { it == "release" }} times")
        }
        val created: Int = rts.sumOf { it.players.size }
        assertTrue(created >= 30, "the storm created only $created native players: it did not exercise the engine")
        assertEquals(emptyList(), errors.toList(), "uncaught exceptions")
    }
}
