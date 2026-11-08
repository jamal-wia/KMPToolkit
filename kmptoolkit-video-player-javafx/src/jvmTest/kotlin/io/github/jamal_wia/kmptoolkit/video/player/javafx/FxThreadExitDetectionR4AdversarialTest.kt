package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 4 on the exit detection of D-62 J2 (implicit-exit flag, engine work counted in a state flow):
 * attacks on the bookkeeping rather than on the stall itself. Implicit exit is switched on through the
 * seam, the way a host-started toolkit has it. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class FxThreadExitDetectionR4AdversarialTest {

    private val errors: MutableList<String> = CopyOnWriteArrayList()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val realRunLater: (Runnable) -> Unit = FxThread.runLater
    private val realImplicit: () -> Boolean = FxThread.implicitExitPossible

    /** Blocks the simulated exited toolkit dropped; run again in [tearDown] so no probe stays unanswered. */
    private val dropped: MutableList<Runnable> = CopyOnWriteArrayList()

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        FxThread.startToolkit()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> errors += "${t.name}: $e" }
    }

    @AfterTest
    fun tearDown() {
        FxThread.runLater = realRunLater
        FxThread.implicitExitPossible = realImplicit
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        dropped.forEach { Platform.runLater(it) }
        drain()
    }

    private fun drain() {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        assertTrue(done.await(10L, TimeUnit.SECONDS), "the FX thread did not drain")
    }

    /** Own blocks that throw must leave the "engine work running" count where it was. */
    @Test
    fun `an engine block that throws on the FX thread does not hide a later silent exit forever`() {
        FxThread.implicitExitPossible = { true }
        val exited = AtomicBoolean(false)
        FxThread.runLater = { block -> if (!exited.get()) realRunLater(block) else dropped += block }
        repeat(3) { FxThread.post { error("boom $it") } }
        drain()
        exited.set(true) // From here on the toolkit drops every block, as after an implicit exit.
        val begun: Long = System.nanoTime()
        val result: Boolean = FxThread.hasExited()
        val tookMs: Long = (System.nanoTime() - begun) / 1_000_000L
        assertTrue(result, "a count leaked by a throwing block hides the exit forever (took $tookMs ms)")
        assertTrue(tookMs < 4_500L, "took $tookMs ms")
        assertEquals(3, errors.size, "the three blocks' failures should have reached the handler: $errors")
    }

    @Test
    fun `a call whose block throws does not hide a later silent exit forever either`() {
        FxThread.implicitExitPossible = { true }
        val exited = AtomicBoolean(false)
        FxThread.runLater = { block -> if (!exited.get()) realRunLater(block) else dropped += block }
        repeat(3) {
            try {
                runBlocking { withTimeout(5_000L) { FxThread.call<Unit> { error("call boom") } } }
            } catch (expected: IllegalStateException) {
                // The caller gets the exception.
            }
        }
        drain()
        exited.set(true)
        assertTrue(FxThread.hasExited(), "a count leaked by a throwing call hides the exit forever")
    }

    @Test
    fun `thirty two threads asking about an exit during one long host block issue at most two probes and all answer in time`() {
        FxThread.implicitExitPossible = { true }
        val posted = AtomicInteger(0)
        FxThread.runLater = { block ->
            posted.incrementAndGet()
            realRunLater(block)
        }
        val release = CountDownLatch(1)
        Platform.runLater { release.await(20L, TimeUnit.SECONDS) }
        val answers: MutableList<Boolean> = CopyOnWriteArrayList()
        val slowest = AtomicInteger(0)
        val callers: List<Thread> = List(32) {
            thread(name = "asker-$it") {
                repeat(5) {
                    val begun: Long = System.nanoTime()
                    answers += FxThread.hasExited()
                    slowest.updateAndGet { old -> maxOf(old, ((System.nanoTime() - begun) / 1_000_000L).toInt()) }
                }
            }
        }
        callers.forEach { it.join(15_000L) }
        val stuck: Int = callers.count { it.isAlive }
        val probesDuringStall: Int = posted.get()
        release.countDown()
        drain()
        assertEquals(0, stuck, "askers stuck")
        assertEquals(160, answers.size)
        assertTrue(answers.all { it }, "a probe silent for the whole bound means exited for every asker")
        assertTrue(slowest.get() < 4_500, "slowest asker took ${slowest.get()} ms")
        assertTrue(probesDuringStall <= 2, "the heartbeat queued $probesDuringStall blocks behind the stall")
        assertFalse(FxThread.hasExited(), "the thread answered again")
    }

    @Test
    fun `host stalls just under the bound, interleaved with engine blocks, are never taken for an exit`() {
        FxThread.implicitExitPossible = { true }
        repeat(4) { round ->
            Platform.runLater { Thread.sleep(1_400L) }
            repeat(3) { FxThread.post { Thread.sleep(50L) } }
            val verdicts: MutableList<Boolean> = mutableListOf()
            val deadline: Long = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < deadline) {
                verdicts += FxThread.hasExited(wait = false)
                Thread.sleep(25L)
            }
            assertTrue(verdicts.none { it }, "round $round: a 1.4 s host stall was taken for an exit")
            drain()
        }
    }

    @Test
    fun `six engines loading and playing at once with implicit exit possible never see an exit`() {
        FxThread.implicitExitPossible = { true }
        val failures: MutableList<String> = CopyOnWriteArrayList()
        val workers: List<Thread> = List(6) { index ->
            thread(name = "engine-$index") {
                repeat(3) {
                    val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
                    try {
                        runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
                        engine.start()
                        Thread.sleep(150L)
                        engine.seekTo(500L)
                        Thread.sleep(150L)
                    } catch (e: Throwable) {
                        failures += "engine $index round $it: $e"
                    } finally {
                        engine.release()
                    }
                }
            }
        }
        workers.forEach { it.join(60_000L) }
        assertTrue(workers.none { it.isAlive }, "a worker is stuck")
        assertEquals(emptyList(), failures.toList())
    }
}
