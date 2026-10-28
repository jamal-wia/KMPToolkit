package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [FxThread]'s ordering promise: work handed to it runs in the order it was handed over, whichever
 * thread handed it — the FX thread's inline shortcut never overtakes work queued before it.
 */
class FxThreadTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        FxThread.startToolkit()
    }

    @Test
    fun aPostOnTheFxThreadRunsAfterOneQueuedFromAnotherThreadBeforeIt() {
        val order: MutableList<String> = CopyOnWriteArrayList()
        onFxThread {
            // The FX thread is busy right here, so this post from another thread stays queued.
            thread { FxThread.post { order += "queued from another thread" } }.join()
            FxThread.post { order += "posted on the FX thread" }
        }
        drainFxThread()

        assertEquals(listOf("queued from another thread", "posted on the FX thread"), order)
    }

    @Test
    fun aPostOnTheFxThreadWithNothingQueuedRunsInline() {
        var ranInline = false
        onFxThread {
            FxThread.post { ranInline = true }
            assertTrue(ranInline, "nothing was queued, so the block runs before post() returns")
        }
    }

    /** Runs [block] on the FX thread and waits for it, rethrowing what it threw. */
    private fun onFxThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        Platform.runLater {
            try {
                block()
            } catch (e: Throwable) {
                failure = e
            } finally {
                done.countDown()
            }
        }
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the FX thread did not run the block")
        failure?.let { throw it }
    }

    /** Waits until everything queued on the FX thread so far has run. */
    private fun drainFxThread() = onFxThread {}

    private companion object {
        const val TIMEOUT_SECONDS: Long = 10L
    }
}
