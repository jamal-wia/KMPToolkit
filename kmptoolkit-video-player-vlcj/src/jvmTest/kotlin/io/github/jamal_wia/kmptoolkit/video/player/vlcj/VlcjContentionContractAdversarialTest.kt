package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * Contention against [FakeVlcRuntime]: several engines, several caller threads each, and events
 * arriving from the event thread at the same time; then the documented ordering and hold
 * guarantees checked literally. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjContentionContractAdversarialTest {

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

    private fun newRuntime(): FakeVlcRuntime = FakeVlcRuntime().also { runtimes += it }

    private fun newEngine(runtime: FakeVlcRuntime, nanoTime: () -> Long = System::nanoTime): VlcjVideoEngine =
        VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime }, nanoTime = nanoTime)

    private fun loadClip(engine: VlcjVideoEngine) = runBlocking {
        withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) }
    }

    @Test
    fun `four engines under eight transport threads and event storms end with the latest settings applied and nothing after release`() {
        val rts: List<FakeVlcRuntime> = List(4) { newRuntime() }
        val engines: List<VlcjVideoEngine> = rts.map { newEngine(it) }
        engines.forEachIndexed { n, engine ->
            val listener = RecordingListener()
            listener.reaction = { event ->
                if (event == "completed" && n % 2 == 0) {
                    engine.seekTo(0L)
                    engine.start()
                }
            }
            engine.setListener(listener)
            loadClip(engine)
            engine.start()
            rts[n].player.fire { playing() }
        }
        val deadline: Long = System.nanoTime() + 2_000_000_000L
        val workers: MutableList<Thread> = mutableListOf()
        engines.forEachIndexed { n, engine ->
            repeat(2) { k ->
                workers += thread(name = "caller-$n-$k") {
                    val random = Random(n * 10 + k)
                    while (System.nanoTime() < deadline) {
                        when (random.nextInt(9)) {
                            0 -> engine.start()
                            1 -> engine.pause()
                            2 -> engine.seekTo(random.nextLong(0L, 2_000L))
                            3 -> engine.setSpeed(0.5f + random.nextInt(4) * 0.5f)
                            4 -> engine.setVolume(random.nextFloat())
                            5 -> engine.setLooping(random.nextBoolean())
                            6 -> engine.positionMs()
                            7 -> engine.durationMs()
                            else -> engine.bufferedPositionMs()
                        }
                        // Paced: the fake records every call in a copy-on-write list, so an unpaced
                        // loop would measure that list, not the engine.
                        Thread.sleep(1L)
                    }
                }
            }
            workers += thread(name = "events-$n") {
                val random = Random(n + 99)
                val player: FakeVlcPlayer = rts[n].player
                while (System.nanoTime() < deadline) {
                    when (random.nextInt(6)) {
                        0 -> player.fire { playing() }
                        1 -> player.fire { timeChanged(random.nextLong(0L, 2_000L)) }
                        2 -> player.fire { finished() }
                        3 -> player.fire { buffering(random.nextInt(101).toFloat()) }
                        4 -> player.fire { lengthChanged(2_000L) }
                        else -> player.fire { paused() }
                    }
                    Thread.sleep(1L)
                }
            }
        }
        workers.forEach { it.join(20_000L) }
        assertTrue(workers.none { it.isAlive }, "a thread hung: ${workers.filter { it.isAlive }.map { it.name }}")
        engines.forEachIndexed { n, engine ->
            val runtime: FakeVlcRuntime = rts[n]
            val player: FakeVlcPlayer = runtime.player
            runtime.drainTasks()
            engine.setLooping(false)
            engine.setSpeed(1.75f)
            engine.setVolume(0.3f)
            engine.start()
            player.fire { playing() }
            runtime.drainTasks()
            assertEquals("setRate(1.75)", player.calls.last { it.startsWith("setRate(") }, "engine $n: the latest rate was not the last applied")
            assertEquals("setVolume(30)", player.calls.last { it.startsWith("setVolume(") }, "engine $n: the latest volume was not the last applied")
            val firstPlay: Int = player.calls.indexOf("play")
            val firstSeek: Int = player.calls.indexOfFirst { it.startsWith("setTime(") }
            assertTrue(firstSeek == -1 || (firstPlay != -1 && firstPlay < firstSeek), "engine $n: a seek went out before the first play")
            engine.release()
            val callsAtRelease: Int = player.calls.size
            player.fire { playing() }
            player.fire { finished() }
            runtime.drainTasks()
            engine.seekTo(5L)
            engine.start()
            engine.setSpeed(2f)
            runtime.drainTasks()
            val teardownCalls: Set<String> = setOf("stopParsing", "stop", "release")
            val late: List<String> = player.calls.drop(callsAtRelease).filter { it !in teardownCalls }
            assertEquals(emptyList(), late, "engine $n: native calls after release")
            engine.dispose()
        }
        assertEquals(emptyList(), errors.toList(), "uncaught exceptions")
    }

    @Test
    fun `a pause made right after a restart from the completion callback always wins, two hundred times`() {
        val runtime: FakeVlcRuntime = newRuntime()
        val engine: VlcjVideoEngine = newEngine(runtime)
        val listener = RecordingListener()
        listener.reaction = { event ->
            if (event == "completed") {
                engine.seekTo(0L)
                engine.start()
            }
        }
        engine.setListener(listener)
        loadClip(engine)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        val losses: MutableList<Int> = mutableListOf()
        repeat(200) { round ->
            val before: Int = player.calls.size
            player.fire { finished() }
            engine.pause()
            runtime.drainTasks()
            val transport: List<String> = player.calls.drop(before).filter { it == "play" || it == "pause" }
            if (transport.isEmpty() || transport.last() != "pause") losses += round
            player.fire { playing() }
            runtime.drainTasks()
        }
        assertEquals(emptyList(), losses, "rounds where the restart outlived the pause")
        engine.dispose()
    }

    @Test
    fun `a seek target is never replaced by a stale clock reading while four threads seek and read`() {
        val runtime: FakeVlcRuntime = newRuntime()
        // A frozen clock: the 500 ms hold never expires, so only a reading near a target may end it.
        val engine: VlcjVideoEngine = newEngine(runtime, nanoTime = { 0L })
        loadClip(engine)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        val targets: Set<Long> = setOf(500L, 600L, 700L, 800L, 900L)
        val stale = 9_000L
        val deadline: Long = System.nanoTime() + 1_500_000_000L
        val bad: MutableList<String> = CopyOnWriteArrayList()
        val readers = List(4) { n ->
            thread {
                val random = Random(n)
                while (System.nanoTime() < deadline) {
                    val target: Long = targets.elementAt(random.nextInt(targets.size))
                    engine.seekTo(target)
                    val seen: Long = engine.positionMs()
                    if (seen !in targets) bad += "after seekTo($target) the playhead read $seen"
                }
            }
        }
        val staleFeeder = thread {
            while (System.nanoTime() < deadline) {
                player.playhead = stale
                player.fire { timeChanged(stale) }
            }
        }
        (readers + staleFeeder).forEach { it.join(15_000L) }
        assertTrue((readers + staleFeeder).none { it.isAlive }, "a thread hung")
        assertEquals(emptyList(), bad.take(5), "stale readings reached the playhead")
        engine.dispose()
    }

    @Test
    fun `release returns at once while another thread is still creating libvlc`() {
        val runtime: FakeVlcRuntime = newRuntime()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val engine = VlcjVideoEngine(
            vlcArgs = emptyList(),
            discover = { true },
            createRuntime = {
                entered.countDown()
                gate.await(2_500L, TimeUnit.MILLISECONDS)
                runtime
            },
        )
        val loader = thread(name = "first-load") {
            runCatching { loadClip(engine) }
        }
        try {
            assertTrue(entered.await(5L, TimeUnit.SECONDS), "libvlc creation never started")
            val startedAt: Long = System.nanoTime()
            engine.release()
            val tookMs: Long = (System.nanoTime() - startedAt) / 1_000_000L
            assertTrue(tookMs < 800L, "release() took $tookMs ms while libvlc was being created")
        } finally {
            gate.countDown()
            loader.join(10_000L)
        }
        assertTrue(!loader.isAlive, "the first load never ended")
        engine.dispose()
    }

    @Test
    fun `a player released while its first prepare is still creating libvlc returns at once and the prepare ends`() {
        val runtime: FakeVlcRuntime = newRuntime()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val engine = VlcjVideoEngine(
            vlcArgs = emptyList(),
            discover = { true },
            createRuntime = {
                entered.countDown()
                gate.await(2_500L, TimeUnit.MILLISECONDS)
                runtime
            },
        )
        val player = createVlcjVideoPlayer(
            engine,
            io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig(),
            kotlinx.coroutines.Dispatchers.Default,
        )
        val preparer = thread(name = "first-prepare") {
            runBlocking { withTimeout(10_000L) { runCatching { player.prepare(VideoSource.File(TestClip.file.path)) } } }
        }
        try {
            assertTrue(entered.await(5L, TimeUnit.SECONDS), "libvlc creation never started")
            val startedAt: Long = System.nanoTime()
            player.release()
            val tookMs: Long = (System.nanoTime() - startedAt) / 1_000_000L
            assertTrue(tookMs < 800L, "release() took $tookMs ms while libvlc was being created")
        } finally {
            gate.countDown()
            preparer.join(10_000L)
        }
        assertTrue(!preparer.isAlive, "the prepare never ended")
        val deadline: Long = System.nanoTime() + 10_000_000_000L
        while (runtime.released == 0 && runtime.players.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(20L)
        assertEquals(runtime.players.size, runtime.players.count { it.calls.count { c -> c == "release" } == 1 }, "a native player was not freed once")
    }

    @Test
    fun `a duration learned late replaces the unknown one and a later zero never erases it`() {
        val runtime: FakeVlcRuntime = newRuntime()
        runtime.parsedInfo = VlcParsedInfo(durationMs = 0L, trackCount = 1, videoSize = null)
        val engine: VlcjVideoEngine = newEngine(runtime)
        loadClip(engine)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        assertEquals(0L, engine.durationMs())
        player.fire { lengthChanged(5_000L) }
        assertEquals(5_000L, engine.durationMs())
        player.fire { lengthChanged(0L) }
        player.fire { durationChanged(0L) }
        assertEquals(5_000L, engine.durationMs())
        engine.dispose()
    }
}
