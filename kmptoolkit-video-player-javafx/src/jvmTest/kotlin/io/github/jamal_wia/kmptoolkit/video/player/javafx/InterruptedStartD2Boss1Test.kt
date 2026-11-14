package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * D2 boss 1, B5 (ruling reviews/d2-boss1-ruling-opus.md section 2): an interrupt is the caller's cancellation, not a
 * property of the machine. `isJavaFxMediaAvailable()` and `prepare` remember only "a permanent failure (missing
 * runtime, no display ...)" (04-api-reference.md, 03-guide.md), so an interrupted first start is not remembered, the
 * caller keeps its interrupt status, and a toolkit the engine started keeps implicit exit off when that first start
 * had to be retried.
 */
class InterruptedStartD2Boss1Test {

    @Test
    fun anInterruptedFirstStartWithASeamIsNotRememberedAndKeepsTheInterruptStatus() {
        val calls = AtomicInteger()
        val runtime = ToolkitJavaFxRuntime(
            // The first start waits for the FX thread like FxThread.startToolkit (a latch with a bound), the second answers.
            startToolkit = { if (calls.incrementAndGet() == 1) CountDownLatch(1).await(5, TimeUnit.SECONDS) },
            onFxThread = { false },
            hasExited = { false },
        )
        Thread.currentThread().interrupt()
        val first: Boolean
        val interruptFlagKept: Boolean
        try {
            first = runtime.isAvailable()
        } finally {
            interruptFlagKept = Thread.interrupted()
        }
        val second: Boolean = runtime.isAvailable()
        assertEquals(
            "first=false interruptFlagKept=true second=true calls=2",
            "first=$first interruptFlagKept=$interruptFlagKept second=$second calls=${calls.get()}",
        )
    }

    @Test
    fun aCallInterruptedWhileItWaitsForTheFxThreadIsNotRememberedAndKeepsItsInterruptStatus() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val runtime = ToolkitJavaFxRuntime(
            startToolkit = {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    CountDownLatch(1).await(30, TimeUnit.SECONDS)
                }
            },
            onFxThread = { false },
            hasExited = { false },
        )
        val first: AtomicReference<Boolean> = AtomicReference()
        val interruptFlagKept: AtomicReference<Boolean> = AtomicReference()
        val caller: Thread = thread(name = "interrupted-first-start-caller") {
            first.set(runtime.isAvailable())
            interruptFlagKept.set(Thread.currentThread().isInterrupted)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the first start never began waiting")
            caller.interrupt()
            caller.join(10_000L)
            assertFalse(caller.isAlive, "the interrupted caller did not return")
        } finally {
            // A hang bound only: a caller that never saw the interrupt still ends.
            caller.interrupt()
            caller.join(5_000L)
        }
        val second: Boolean = runtime.isAvailable()
        assertEquals(
            "first=false interruptFlagKept=true second=true calls=2",
            "first=${first.get()} interruptFlagKept=${interruptFlagKept.get()} second=$second calls=${calls.get()}",
        )
    }

    @Test
    fun anInterruptedFirstStartOfTheRealToolkitIsNotRememberedAndKeepsTheInterruptStatus() {
        assumeJavaFxMedia()
        val runtime = ToolkitJavaFxRuntime()
        Thread.currentThread().interrupt()
        val first: Boolean
        val interruptFlagKept: Boolean
        try {
            first = runtime.isAvailable()
        } finally {
            interruptFlagKept = Thread.interrupted()
        }
        val second: Boolean = runtime.isAvailable()
        assertEquals(
            "first=false interruptFlagKept=true second=true",
            "first=$first interruptFlagKept=$interruptFlagKept second=$second",
        )
    }

    @Test
    fun aProbeForAnExitedToolkitThatIsInterruptedProvesNothingAndKeepsTheInterruptStatus() {
        val probes = AtomicInteger()
        val runtime = ToolkitJavaFxRuntime(
            startToolkit = { },
            onFxThread = { false },
            // FxThread.hasExited waits on a latch, which throws InterruptedException for an interrupted caller.
            hasExited = {
                probes.incrementAndGet()
                throw InterruptedException("probe interrupted")
            },
        )
        assertTrue(runtime.isAvailable(), "the first start does not probe for an exit")
        val answeredWhileInterrupted: Boolean
        val interruptFlagKept: Boolean
        try {
            answeredWhileInterrupted = runtime.isAvailable()
        } finally {
            interruptFlagKept = Thread.interrupted()
        }
        val afterwards: Boolean = runtime.isAvailable()
        assertEquals(
            "answered=true interruptFlagKept=true afterwards=true probes=2",
            "answered=$answeredWhileInterrupted interruptFlagKept=$interruptFlagKept afterwards=$afterwards probes=${probes.get()}",
        )
    }

    @Test
    fun aToolkitTheEngineStartedKeepsImplicitExitOffWhenTheFirstWaitWasInterruptedAndTheStartIsRetried() {
        assumeJavaFxMedia()
        val realStartup: (Runnable) -> Unit = FxThread.startup
        val implicitExitBefore: Boolean = Platform.isImplicitExit()
        try {
            // The engine's Platform.startup returns normally, but the wait for the FX thread is interrupted.
            FxThread.startup = { }
            Thread.currentThread().interrupt()
            try {
                FxThread.startToolkit()
                fail("the interrupted wait did not throw")
            } catch (expected: InterruptedException) {
                // The retry below must not forget that this engine started the toolkit.
            } finally {
                Thread.interrupted()
            }
            FxThread.startup = realStartup
            Platform.setImplicitExit(true)
            // The retry meets "Toolkit already initialized" from the real startup, then waits for the FX thread.
            FxThread.startToolkit()
            assertFalse(Platform.isImplicitExit(), "a toolkit the engine started must keep implicit exit off after a retried first start")
        } finally {
            FxThread.startup = realStartup
            Thread.interrupted()
            Platform.setImplicitExit(implicitExitBefore)
        }
    }
}
