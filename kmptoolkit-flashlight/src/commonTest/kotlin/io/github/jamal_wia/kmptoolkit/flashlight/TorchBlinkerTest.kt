package io.github.jamal_wia.kmptoolkit.flashlight

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The shared blink loop, which is where both platform flashlights get the "safe to call from any
 * thread" promise from [Flashlight] — so it is tested here once, on every target, with a recording
 * stand-in for the torch.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorchBlinkerTest {

    @Test
    fun `start switches the torch on at once and keeps the pattern's rhythm`() = runTest {
        val torch = RecordingTorch()
        val blinker = TorchBlinker(backgroundScope, torch::set)

        blinker.start(FlashPattern.Blink)
        runCurrent()
        assertEquals(listOf(true), torch.switches)

        advanceTimeBy(FlashPattern.Blink.on)
        runCurrent()
        assertEquals(listOf(true, false), torch.switches)

        advanceTimeBy(FlashPattern.Blink.off)
        runCurrent()
        assertEquals(listOf(true, false, true), torch.switches)
    }

    @Test
    fun `stop leaves the torch off and no loop running`() = runTest {
        val torch = RecordingTorch()
        val blinker = TorchBlinker(backgroundScope, torch::set)
        blinker.start(FlashPattern.Blink)
        runCurrent()

        blinker.stop()
        runCurrent()

        assertFalse(torch.isOn)
        assertFalse(blinker.isBlinking)
        advanceTimeBy(FlashPattern.Blink.on + FlashPattern.Blink.off)
        runCurrent()
        assertFalse(torch.isOn)
    }

    @Test
    fun `stop with nothing running only ever switches the torch off`() = runTest {
        val torch = RecordingTorch()
        val blinker = TorchBlinker(backgroundScope, torch::set)

        blinker.stop()
        blinker.stop()
        runCurrent()

        assertTrue(torch.switches.isNotEmpty())
        assertTrue(torch.switches.none { on: Boolean -> on }, "switches=${torch.switches}")
        assertFalse(blinker.isBlinking)
    }

    @Test
    fun `a start right after a stop keeps its first flash even if the new loop runs first`() {
        // The order a multi-threaded dispatcher can produce and runTest never does: the new loop is
        // scheduled before the cancelled loop's finally. A last-in-first-out dispatcher forces it. Unless
        // the new loop waits for the stop to settle, the old loop's final "off" lands after its first "on".
        val dispatcher = LastInFirstOutDispatcher()
        val scope = CoroutineScope(dispatcher)
        val torch = RecordingTorch()
        val blinker = TorchBlinker(scope, torch::set)
        try {
            blinker.start(FlashPattern.Blink)
            dispatcher.drain()
            assertTrue(torch.isOn, "the first pattern never lit")

            blinker.stop()
            blinker.start(FlashPattern.Attention)
            dispatcher.drain()

            assertTrue(torch.isOn, "the new pattern's first flash was cut short: ${torch.switches}")
            assertTrue(blinker.isBlinking)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a replacing start does not have its first flash clipped by the loop it replaced`() = runTest {
        // The replaced loop switches the torch off in its finally. Unless the new loop waits for
        // that, the off lands after the new loop's first on and the first flash of the new pattern
        // is lost — the pattern a caller re-arms with is usually the urgent one.
        val torch = RecordingTorch()
        val blinker = TorchBlinker(backgroundScope, torch::set)
        blinker.start(FlashPattern.Blink)
        runCurrent()

        blinker.start(FlashPattern.Attention)
        runCurrent()

        assertEquals(listOf(true, false, true), torch.switches)
        advanceTimeBy(FlashPattern.Attention.on - 1.milliseconds)
        runCurrent()
        assertTrue(torch.isOn, "the new pattern's first flash ended early")
    }

    @Test
    fun `a job replaced while it waits still makes its successor wait for what it was waiting for`() {
        // stop, start, start in quick succession: the middle start is replaced before it ever ran. If its
        // wait could be cancelled, the last start would see it finish at once and light the torch before
        // the first loop's final "off" — the same clipped flash, one replacement further along.
        val dispatcher = LastInFirstOutDispatcher()
        val scope = CoroutineScope(dispatcher)
        val torch = RecordingTorch()
        val blinker = TorchBlinker(scope, torch::set)
        try {
            blinker.start(FlashPattern.Blink)
            dispatcher.drain()

            blinker.stop()
            blinker.start(FlashPattern.Blink)
            blinker.start(FlashPattern.Attention)
            dispatcher.drain()

            assertTrue(torch.isOn, "the last pattern's first flash was cut short: ${torch.switches}")
            assertTrue(blinker.isBlinking)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a replaced loop stops switching the torch`() = runTest {
        val torch = RecordingTorch()
        val blinker = TorchBlinker(backgroundScope, torch::set)
        blinker.start(FlashPattern.Blink)
        runCurrent()
        blinker.start(FlashPattern.Attention)
        runCurrent()
        torch.switches.clear()

        // One full Attention cycle is shorter than Blink's "on"; if the Blink loop were still alive,
        // its off would appear at Blink.on and break the Attention rhythm.
        advanceTimeBy(FlashPattern.Blink.on + FlashPattern.Blink.off)
        runCurrent()

        val expectedCycles: Int =
            ((FlashPattern.Blink.on + FlashPattern.Blink.off) / (FlashPattern.Attention.on + FlashPattern.Attention.off)).toInt()
        assertTrue(torch.switches.size >= expectedCycles * 2, "switches=${torch.switches}")
        assertTrue(torch.switches.zipWithNext().all { (a, b) -> a != b }, "switches=${torch.switches}")
    }

    @Test
    fun `concurrent starts from many threads leave nothing a single stop cannot reach`() = runTest {
        // With a plain field two racing starts could both cancel the same old job and each install
        // their own; the loser keeps blinking forever. Joining every loop after one stop would then
        // never complete, which the timeout turns into a failure.
        val torch = AtomicTorch()
        val loops = Job()
        val blinker = TorchBlinker(CoroutineScope(Dispatchers.Default + loops), torch::set)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) {
                    repeat(ROUNDS) {
                        List(STARTS_PER_ROUND) { index: Int ->
                            launch {
                                blinker.start(if (index % 2 == 0) FlashPattern.Blink else FlashPattern.Attention)
                            }
                        }.joinAll()
                    }
                    blinker.stop()
                    loops.children.toList().joinAll()
                }
            }

            assertFalse(blinker.isBlinking)
            assertFalse(torch.isOn)
            assertTrue(torch.switchCount > 0, "no loop ever reached the torch")
        } finally {
            CoroutineScope(loops).cancel()
        }
    }

    private class RecordingTorch {
        val switches: MutableList<Boolean> = mutableListOf()
        val isOn: Boolean get() = switches.lastOrNull() == true
        fun set(on: Boolean) {
            switches += on
        }
    }

    private class AtomicTorch {
        private val last: AtomicReference<Boolean> = AtomicReference(false)
        private val count: AtomicInt = AtomicInt(0)
        val isOn: Boolean get() = last.load()
        val switchCount: Int get() = count.load()
        fun set(on: Boolean) {
            last.store(on)
            count.incrementAndFetch()
        }
    }

    private companion object {
        const val ROUNDS: Int = 20
        const val STARTS_PER_ROUND: Int = 32
        const val MAX_DRAIN_STEPS: Int = 10_000
    }

    /**
     * Runs queued work newest-first, on the calling thread, when told to — the scheduling order that
     * exposes a job overtaking the one it should have waited for.
     */
    private class LastInFirstOutDispatcher : CoroutineDispatcher() {
        private val queue: ArrayDeque<Runnable> = ArrayDeque()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun drain() {
            repeat(MAX_DRAIN_STEPS) {
                val next: Runnable = queue.removeLastOrNull() ?: return
                next.run()
            }
            error("the dispatcher never went idle")
        }
    }
}
