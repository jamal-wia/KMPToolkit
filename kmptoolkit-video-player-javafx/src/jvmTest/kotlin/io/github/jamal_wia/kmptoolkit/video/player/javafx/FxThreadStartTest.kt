package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Starting the toolkit from the FX thread: it must not stall for the start timeout, and a busy FX
 * thread must not be cached as "unavailable" for the rest of the process.
 */
class FxThreadStartTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        FxThread.startToolkit()
    }

    @Test
    fun startingTheToolkitOnTheFxThreadReturnsAtOnce() {
        val outcome: Outcome = onFxThreadWithin(2L) { FxThread.startToolkit() }
        assertNull(outcome.failure, "an FX-thread caller proves the toolkit runs; nothing to wait for")
    }

    @Test
    fun theAvailabilityCheckOnTheFxThreadAnswersTrueAndStaysTrueForEveryThread() {
        val runtime = ToolkitJavaFxRuntime()
        val onFx: AtomicReference<Boolean> = AtomicReference()
        val outcome: Outcome = onFxThreadWithin(2L) { onFx.set(runtime.isAvailable()) }
        assertNull(outcome.failure)
        assertEquals(true, onFx.get())

        val fromBackground: AtomicReference<Boolean> = AtomicReference()
        thread { fromBackground.set(runtime.isAvailable()) }.join(5_000L)
        assertEquals(true, fromBackground.get())
    }

    @Test
    fun anFxThreadThatWasOnlyBusyIsNotRememberedAsUnavailable() {
        var calls = 0
        val runtime = ToolkitJavaFxRuntime(startToolkit = {
            calls++
            if (calls == 1) throw FxThreadUnresponsiveException("busy")
        })

        assertFalse(runtime.isAvailable(), "the busy thread did not answer")
        assertTrue(runtime.isAvailable(), "a busy thread passes: the next call tries again")
        assertEquals(2, calls)
    }

    @Test
    fun aPermanentFailureIsRememberedWithoutStartingAgain() {
        var calls = 0
        val runtime = ToolkitJavaFxRuntime(startToolkit = {
            calls++
            throw IllegalStateException("Platform.exit has been called")
        })

        assertFalse(runtime.isAvailable())
        assertFalse(runtime.isAvailable())
        assertEquals(1, calls)
    }

    @Test
    fun aRuntimeWhoseThreadCheckBuildsNoToolkitIsUnavailableNotThrowing() {
        // Asking JavaFX which thread this is builds the toolkit; on a broken runtime that throws.
        var calls = 0
        var threadChecks = 0
        val runtime = ToolkitJavaFxRuntime(
            startToolkit = {
                calls++
                throw RuntimeException("No toolkit found")
            },
            onFxThread = {
                threadChecks++
                throw RuntimeException("No toolkit found")
            },
        )

        assertFalse(runtime.isAvailable())
        assertFalse(runtime.isAvailable())
        assertEquals(1, calls, "the failure is remembered, not rebuilt on every call")
        assertEquals(1, threadChecks, "a remembered failure skips the thread check, which rebuilds the toolkit")
    }

    @Test
    fun aThreadCheckThatCannotLoadNativesIsAlsoNotTheFxThread() {
        val runtime = ToolkitJavaFxRuntime(
            startToolkit = { throw UnsatisfiedLinkError("prism") },
            onFxThread = { throw UnsatisfiedLinkError("prism") },
        )

        assertFalse(runtime.isAvailable())
    }

    @Test
    fun aToolkitThatExitedFailsAtOnceAndIsRememberedAsPermanent() {
        val real: (Runnable) -> Unit = FxThread.startup
        // What Platform.startup throws once Platform.exit ran; runLater would then drop every block.
        FxThread.startup = { throw IllegalStateException("Platform.exit has been called") }
        try {
            val runtime = ToolkitJavaFxRuntime()
            val begun: Long = System.nanoTime()
            assertFalse(runtime.isAvailable())
            assertFalse(runtime.isAvailable())
            assertTrue(System.nanoTime() - begun < 2_000_000_000L, "no 10 s wait for a dropped block")
        } finally {
            FxThread.startup = real
        }
    }

    @Test
    fun theFxThreadIsNotHeldUpByAnotherThreadStartingTheToolkit() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Another thread inside ensureStarted, waiting for the FX thread as startToolkit does.
        val runtime = ToolkitJavaFxRuntime(startToolkit = {
            entered.countDown()
            release.await(10L, TimeUnit.SECONDS)
        })
        val other = thread { runtime.isAvailable() }
        try {
            assertTrue(entered.await(5L, TimeUnit.SECONDS))
            val answer: AtomicReference<Boolean> = AtomicReference()
            onFxThreadWithin(2L) { answer.set(runtime.isAvailable()) }
            assertEquals(true, answer.get())
        } finally {
            release.countDown()
            other.join(5_000L)
        }
    }

    private class Outcome(val failure: Throwable?)

    /** Runs [block] on the FX thread and fails unless it finishes within [seconds]. */
    private fun onFxThreadWithin(seconds: Long, block: () -> Unit): Outcome {
        val done = CountDownLatch(1)
        val failure: AtomicReference<Throwable?> = AtomicReference()
        Platform.runLater {
            try {
                block()
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                done.countDown()
            }
        }
        assertTrue(done.await(seconds, TimeUnit.SECONDS), "the block did not finish within $seconds s")
        return Outcome(failure.get())
    }
}
