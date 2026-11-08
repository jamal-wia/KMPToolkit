package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Getters read from other threads while sessions come and go, and a crowd of engines dropped
 * without release (GC dependent, bounded). Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjGettersAndGcAdversarialTest {

    private val runtime = FakeVlcRuntime()
    private val creations = AtomicInteger()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    private fun newEngine(): VlcjVideoEngine = VlcjVideoEngine(
        vlcArgs = emptyList(),
        discover = { true },
        createRuntime = { creations.incrementAndGet(); runtime },
    )

    @Test
    fun `getters polled from four threads during fifty load and release cycles never throw or leave the valid range`() = runBlocking<Unit> {
        val engine: VlcjVideoEngine = newEngine()
        val stop = AtomicBoolean(false)
        val problems: MutableList<String> = CopyOnWriteArrayList()
        val pollers = List(4) {
            thread {
                while (!stop.get()) {
                    try {
                        val duration: Long = engine.durationMs()
                        val position: Long = engine.positionMs()
                        val buffered: Long = engine.bufferedPositionMs()
                        if (duration !in 0L..2_000L) problems += "duration $duration"
                        if (position !in 0L..2_000L) problems += "position $position"
                        if (buffered !in 0L..2_000L) problems += "buffered $buffered"
                    } catch (e: Throwable) {
                        problems += "threw $e"
                    }
                }
            }
        }
        repeat(50) { round: Int ->
            withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) }
            engine.start()
            val player: FakeVlcPlayer = runtime.player
            player.fire { playing() }
            engine.seekTo((round * 37 % 2_000).toLong())
            player.fire { timeChanged((round * 41 % 2_000).toLong()) }
            if (round % 3 == 0) player.fire { finished() }
            engine.release()
        }
        stop.set(true)
        pollers.forEach { it.join(10_000L) }
        engine.dispose()
        assertTrue(pollers.none { it.isAlive }, "a poller hung")
        assertTrue(problems.isEmpty(), "getter problems: ${problems.distinct().take(8)}")
    }

    private fun loadAndDropEngines(count: Int): List<WeakReference<VlcjVideoEngine>> = List(count) {
        val engine: VlcjVideoEngine = newEngine()
        runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }
        WeakReference(engine)
    }

    @Test
    fun `a hundred engines dropped without release free every native player and instance (GC dependent)`() {
        val references: List<WeakReference<VlcjVideoEngine>> = loadAndDropEngines(100)
        var rounds = 0
        while (references.any { it.get() != null } && rounds < 120) {
            System.gc()
            Thread.sleep(50L)
            rounds++
        }
        assertTrue(references.none { it.get() != null }, "${references.count { it.get() != null }} engines never collected after $rounds rounds (not a defect by itself)")
        val deadline: Long = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline &&
            !(runtime.players.size == 100 && runtime.players.all { p -> p.calls.count { it == "release" } == 1 } && runtime.released == 100)
        ) {
            Thread.sleep(20L)
        }
        assertTrue(runtime.players.size == 100, "players ${runtime.players.size}")
        assertTrue(runtime.players.all { p -> p.calls.count { it == "release" } == 1 }, "unreleased native players: ${runtime.players.count { p -> p.calls.count { it == "release" } != 1 }}")
        assertTrue(runtime.released == 100, "instances freed ${runtime.released} of 100")
    }
}
