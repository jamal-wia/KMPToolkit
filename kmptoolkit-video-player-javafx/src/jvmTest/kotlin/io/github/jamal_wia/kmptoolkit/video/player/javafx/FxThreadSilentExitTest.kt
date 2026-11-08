package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An exit that gives no message (the host's last window closed, the toolkit thread died) drops every
 * block handed to the toolkit; the probe in [FxThread.hasExited] must notice that, and must not
 * mistake a thread that recovers for a dead one.
 */
class FxThreadSilentExitTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        FxThread.startToolkit()
    }

    @Test
    fun `a live idle toolkit is not reported as exited and answers without waiting`() {
        val begun: Long = System.nanoTime()
        repeat(50) { assertFalse(FxThread.hasExited(), "probe $it") }
        assertTrue((System.nanoTime() - begun) / 1_000_000L < 1_500L, "an idle toolkit must answer fast")
    }

    @Test
    fun `a toolkit that drops its blocks is reported as exited once the probe stayed silent, and recovers when it answers`() {
        val dropped: MutableList<Runnable> = CopyOnWriteArrayList()
        val real: (Runnable) -> Unit = FxThread.runLater
        val realImplicit: () -> Boolean = FxThread.implicitExitPossible
        // An exit that gives no message needs implicit exit on; the test toolkit has it switched off.
        FxThread.implicitExitPossible = { true }
        FxThread.runLater = { dropped += it }
        try {
            // First sight: the probe has only just been issued, the silence is not yet proven.
            assertFalse(FxThread.hasExited(wait = false))
            val begun: Long = System.nanoTime()
            assertTrue(FxThread.hasExited(), "a probe silent for the whole bound means exited")
            val tookMs: Long = (System.nanoTime() - begun) / 1_000_000L
            assertTrue(tookMs < 4_000L, "the wait is bounded, took $tookMs ms")
            // Once proven, every later call answers at once: the same probe is already old.
            val again: Long = System.nanoTime()
            repeat(20) { assertTrue(FxThread.hasExited(), "call $it") }
            assertTrue((System.nanoTime() - again) / 1_000_000L < 500L, "a proven exit must not wait again")
            assertTrue(dropped.size == 1, "at most one probe is outstanding, saw ${dropped.size}")
        } finally {
            FxThread.runLater = real
            FxThread.implicitExitPossible = realImplicit
            // The toolkit was alive all along: the blocks that were held back now run and answer.
            dropped.forEach { Platform.runLater(it) }
            val done = CountDownLatch(1)
            Platform.runLater { done.countDown() }
            assertTrue(done.await(5L, TimeUnit.SECONDS), "the FX thread did not drain")
        }
        assertFalse(FxThread.hasExited(), "a toolkit whose probe finally ran is alive again")
    }

    @Test
    fun `a probe queued behind the engine's own long block is not silence`() {
        val realImplicit: () -> Boolean = FxThread.implicitExitPossible
        FxThread.implicitExitPossible = { true }
        try {
            FxThread.post { Thread.sleep(2_600L) }
            val deadline: Long = System.nanoTime() + 3_000_000_000L
            var calls = 0
            while (System.nanoTime() < deadline) {
                assertFalse(FxThread.hasExited(), "call $calls while the engine's own block runs")
                calls++
                if (calls >= 200) break
            }
        } finally {
            FxThread.implicitExitPossible = realImplicit
            drain()
        }
    }

    @Test
    fun `a host block that keeps the thread silent for 2 s is taken for an exit only while implicit exit is possible`() {
        val realImplicit: () -> Boolean = FxThread.implicitExitPossible
        FxThread.implicitExitPossible = { true }
        try {
            Platform.runLater { Thread.sleep(2_600L) }
            assertTrue(FxThread.hasExited(), "app work for 2 s with implicit exit on looks like an exit")
            drain()
            assertFalse(FxThread.hasExited(), "the thread answered again")
        } finally {
            FxThread.implicitExitPossible = realImplicit
            drain()
        }
    }

    private fun drain() {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        assertTrue(done.await(10L, TimeUnit.SECONDS), "the FX thread did not drain")
    }
}
