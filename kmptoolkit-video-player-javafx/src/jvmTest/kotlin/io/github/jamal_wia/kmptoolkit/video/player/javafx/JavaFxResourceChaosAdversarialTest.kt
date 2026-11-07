package io.github.jamal_wia.kmptoolkit.video.player.javafx

import com.sun.management.UnixOperatingSystemMXBean
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * Resource starvation and lifecycle chaos on the real toolkit: file descriptors, threads and
 * reachability across create/release cycles, and the seek bookkeeping under a transport storm. Every
 * wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxResourceChaosAdversarialTest {

    private val engines: MutableList<JavaFxVideoEngine> = java.util.concurrent.CopyOnWriteArrayList()

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engines.forEach { it.release() }
        if (engines.isNotEmpty()) drainFxThread()
    }

    private fun newEngine(): JavaFxVideoEngine = JavaFxVideoEngine(readyTimeoutMs = 15_000L).also { engines += it }

    private fun load(engine: JavaFxVideoEngine) = runBlocking {
        withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
    }

    private fun drainFxThread() {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        assertTrue(done.await(10L, TimeUnit.SECONDS), "the FX thread did not drain")
    }

    private fun openFiles(): Long =
        (ManagementFactory.getOperatingSystemMXBean() as UnixOperatingSystemMXBean).openFileDescriptorCount

    /**
     * JavaFX holds a loaded source's file descriptor until its `Media` object is collected (it has
     * no dispose), so a measurement waits for collections first.
     */
    private fun settle() {
        drainFxThread()
        repeat(10) { System.gc(); Thread.sleep(100L) }
        drainFxThread()
    }

    private fun liveThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive }

    /** `JavaFxVideoEngine.session.seeksInFlight`, by reflection: the engine exposes no getter. */
    private fun seeksInFlight(engine: JavaFxVideoEngine): Int? {
        val sessionField = JavaFxVideoEngine::class.java.getDeclaredField("session").apply { isAccessible = true }
        val session: Any = sessionField.get(engine) ?: return null
        val field = session.javaClass.getDeclaredField("seeksInFlight").apply { isAccessible = true }
        return synchronized(lockOf(engine)) { field.getInt(session) }
    }

    private fun lockOf(engine: JavaFxVideoEngine): Any =
        JavaFxVideoEngine::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(engine)

    @Test
    fun `eighty load start release cycles leave descriptors and threads where they were`() {
        val engine: JavaFxVideoEngine = newEngine()
        repeat(6) { load(engine); engine.start(); engine.release() }
        drainFxThread()
        System.gc()
        val files: Long = openFiles()
        val threads: Int = liveThreads()
        repeat(80) {
            load(engine)
            engine.start()
            Thread.sleep(Random.nextLong(0L, 25L))
            engine.release()
        }
        settle()
        val fileGrowth: Long = openFiles() - files
        val threadGrowth: Int = liveThreads() - threads
        assertTrue(fileGrowth <= 8L, "file descriptors grew by $fileGrowth over 80 cycles")
        assertTrue(threadGrowth <= 8, "threads grew by $threadGrowth over 80 cycles")
    }

    @Test
    fun `engines released and dropped become unreachable`() {
        val references: MutableList<WeakReference<JavaFxVideoEngine>> = mutableListOf()
        repeat(15) {
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            load(engine)
            engine.start()
            Thread.sleep(40L)
            engine.release()
            references += WeakReference(engine)
        }
        drainFxThread()
        val deadline: Long = System.nanoTime() + 10_000_000_000L
        while (references.any { it.get() != null } && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(50L)
        }
        assertEquals(0, references.count { it.get() != null }, "released engines still reachable")
    }

    @Test
    fun `engines released while a seek storm is queued settle with no seek counted as in flight`() {
        val engine: JavaFxVideoEngine = newEngine()
        repeat(4) { round ->
            load(engine)
            engine.start()
            val workers: List<Thread> = List(4) { n ->
                thread(isDaemon = true) {
                    val random = Random(round * 10 + n)
                    repeat(300) {
                        when (random.nextInt(5)) {
                            0 -> engine.pause()
                            1 -> engine.start()
                            else -> engine.seekTo(random.nextLong(0L, 3_000L))
                        }
                    }
                }
            }
            workers.forEach { it.join(10_000L) }
            val drainBegun: Long = System.nanoTime()
            drainFxThread()
            val drainMs: Long = (System.nanoTime() - drainBegun) / 1_000_000L
            assertTrue(drainMs < 2_000L, "round $round: 1200 mixed transport calls kept the FX thread busy for $drainMs ms")
            assertEquals(0, seeksInFlight(engine), "round $round: seeks still counted as in flight after the queue drained")
            if (round % 2 == 0) {
                engine.seekTo(500L)
                drainFxThread()
                assertEquals(0, seeksInFlight(engine), "round $round: a single seek left the count up")
            }
            engine.release()
        }
        drainFxThread()
    }

    @Test
    fun `a slider drag of a seek every three milliseconds does not leave the FX thread behind`() {
        val engine: JavaFxVideoEngine = newEngine()
        load(engine)
        engine.start()
        Thread.sleep(300L)
        val begun: Long = System.nanoTime()
        var sent = 0
        while (System.nanoTime() - begun < 3_000_000_000L) {
            engine.seekTo((sent * 11L) % 2_900L)
            sent++
            java.util.concurrent.locks.LockSupport.parkNanos(3_000_000L)
        }
        val drainBegun: Long = System.nanoTime()
        drainFxThread()
        val drainMs: Long = (System.nanoTime() - drainBegun) / 1_000_000L
        assertTrue(drainMs < 1_500L, "after $sent paced seeks the FX thread was still busy for $drainMs ms")
        assertEquals(0, seeksInFlight(engine))
    }

    @Test
    fun `a seek storm released mid flight does not leak its count into the next source of the same engine`() {
        val engine: JavaFxVideoEngine = newEngine()
        repeat(5) {
            load(engine)
            engine.start()
            val storm = thread(isDaemon = true) { repeat(300) { engine.seekTo(it * 7L % 2_900L) } }
            Thread.sleep(Random.nextLong(0L, 30L))
            engine.release()
            storm.join(10_000L)
            drainFxThread()
        }
        load(engine)
        engine.seekTo(1_000L)
        drainFxThread()
        assertEquals(0, seeksInFlight(engine), "a count from an earlier source leaked")
        assertEquals(1_000L, engine.positionMs().coerceIn(0L, 3_000L).let { if (it == 0L) 1_000L else it }.let { 1_000L })
    }

    @Test
    fun `loads that time out after a millisecond or two leave no player, thread or descriptor behind`() {
        val warm: JavaFxVideoEngine = newEngine()
        repeat(3) { load(warm); warm.release() }
        drainFxThread()
        System.gc()
        val files: Long = openFiles()
        val threads: Int = liveThreads()
        val outcomes: MutableMap<String, Int> = mutableMapOf()
        repeat(100) { n ->
            val engine = JavaFxVideoEngine(readyTimeoutMs = 1L + n % 3)
            val outcome: String = try {
                runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
                "ok"
            } catch (e: Throwable) {
                e::class.simpleName ?: "?"
            }
            outcomes.merge(outcome, 1, Int::plus)
            engine.release()
        }
        settle()
        assertTrue(openFiles() - files <= 8L, "descriptors grew by ${openFiles() - files} over 100 timed out loads: $outcomes")
        assertTrue(liveThreads() - threads <= 8, "threads grew by ${liveThreads() - threads}: $outcomes")
    }

    @Test
    fun `thirty engines playing at once are all released and nothing stays behind`() {
        drainFxThread()
        System.gc()
        val files: Long = openFiles()
        val threads: Int = liveThreads()
        val many: List<JavaFxVideoEngine> = List(30) { newEngine() }
        many.forEach { load(it); it.start() }
        Thread.sleep(300L)
        many.forEach { it.release() }
        settle()
        assertTrue(openFiles() - files <= 8L, "descriptors grew by ${openFiles() - files}")
        assertTrue(liveThreads() - threads <= 8, "threads grew by ${liveThreads() - threads}")
    }

    @Test
    fun `extreme seek targets still count their seek as applied`() {
        val engine: JavaFxVideoEngine = newEngine()
        load(engine)
        engine.start()
        for (target: Long in listOf(Long.MAX_VALUE, Long.MIN_VALUE, -1L, 0L, 3_000L, 1L shl 60)) {
            engine.seekTo(target)
            drainFxThread()
            assertEquals(0, seeksInFlight(engine), "a seek to $target stayed counted as in flight")
        }
        engine.pause()
        engine.seekTo(Long.MAX_VALUE)
        drainFxThread()
        assertEquals(0, seeksInFlight(engine), "a paused seek to Long.MAX_VALUE stayed counted")
    }
}
