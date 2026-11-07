package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Several threads asking [ToolkitJavaFxRuntime] whether the toolkit runs at the same moment: the
 * (blocking) start happens exactly once and every caller sees its single outcome. Deterministic: a
 * latch holds the first start open until every other caller is parked behind it, so no test depends
 * on timing luck.
 */
class ToolkitStartOnceTest {

    @BeforeTest
    fun setUp() {
        // The locked path loads javafx.scene.media.MediaPlayer before it calls the start seam.
        assumeJavaFxMedia()
    }

    @Test
    fun concurrentCallersStartTheToolkitExactlyOnceAndAllSeeItRunning() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = ToolkitJavaFxRuntime(
            startToolkit = {
                calls.incrementAndGet()
                entered.countDown()
                release.await(10L, TimeUnit.SECONDS)
            },
            onFxThread = { false },
        )

        val answers: List<AtomicReference<Boolean>> = runConcurrently(runtime, callers = 4, calls, entered, release)

        assertEquals(1, calls.get(), "the toolkit start must run once, however many callers arrive together")
        answers.forEach { assertEquals(true, it.get()) }
        assertTrue(runtime.isAvailable(), "the outcome stays")
        assertEquals(1, calls.get())
    }

    @Test
    fun concurrentCallersAllSeeTheSamePermanentFailureFromOneStart() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = ToolkitJavaFxRuntime(
            startToolkit = {
                calls.incrementAndGet()
                entered.countDown()
                release.await(10L, TimeUnit.SECONDS)
                throw IllegalStateException("Platform.exit has been called")
            },
            onFxThread = { false },
        )

        val answers: List<AtomicReference<Boolean>> = runConcurrently(runtime, callers = 4, calls, entered, release)

        assertEquals(1, calls.get(), "latecomers see the remembered failure, they do not start again")
        answers.forEach { assertEquals(false, it.get()) }
        assertEquals(false, runtime.isAvailable())
        assertEquals(1, calls.get())
    }

    @Test
    fun aToolkitProvenRunningByTheFxThreadIsNotOverwrittenByALaterFailureOfAnotherThread() {
        val fx: AtomicReference<Thread?> = AtomicReference()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = ToolkitJavaFxRuntime(
            startToolkit = {
                entered.countDown()
                release.await(10L, TimeUnit.SECONDS)
                throw IllegalStateException("Platform.exit has been called")
            },
            onFxThread = { Thread.currentThread() === fx.get() },
        )
        val background: AtomicReference<Boolean> = AtomicReference()
        val other = thread { background.set(runtime.isAvailable()) }
        try {
            assertTrue(entered.await(5L, TimeUnit.SECONDS))
            // The FX thread answers while the other thread still holds the start open.
            val fxAnswer: AtomicReference<Boolean> = AtomicReference()
            // Publish the identity before the thread can run, or its thread check could miss it.
            val fxCaller = thread(start = false) { fxAnswer.set(runtime.isAvailable()) }
            fx.set(fxCaller)
            fxCaller.start()
            fxCaller.join(5_000L)
            assertEquals(true, fxAnswer.get())
        } finally {
            release.countDown()
            other.join(5_000L)
        }

        assertEquals(false, background.get(), "the failing starter itself was told it failed")
        assertEquals(true, runtime.isAvailable(), "what the FX thread proved is not undone by a later failure")
    }

    /**
     * Starts [callers] threads on [runtime]; the first enters the start seam and is held there until
     * every other caller is either parked behind the monitor or (broken guard) inside the seam too.
     */
    private fun runConcurrently(
        runtime: ToolkitJavaFxRuntime,
        callers: Int,
        calls: AtomicInteger,
        entered: CountDownLatch,
        release: CountDownLatch,
    ): List<AtomicReference<Boolean>> {
        val answers: List<AtomicReference<Boolean>> = List(callers) { AtomicReference() }
        val threads: MutableList<Thread> = mutableListOf()
        try {
            threads += thread { answers[0].set(runtime.isAvailable()) }
            assertTrue(entered.await(5L, TimeUnit.SECONDS), "the first caller never reached the start")
            for (i in 1 until callers) threads += thread { answers[i].set(runtime.isAvailable()) }
            val others: List<Thread> = threads.drop(1)
            val deadline: Long = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline &&
                calls.get() == 1 &&
                !others.all { it.state == Thread.State.BLOCKED }
            ) {
                Thread.sleep(5L)
            }
        } finally {
            release.countDown()
            threads.forEach { it.join(10_000L) }
        }
        return answers
    }
}
