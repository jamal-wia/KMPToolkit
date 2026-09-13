package io.github.jamal_wia.kmptoolkit.flashlight

import kotlinx.coroutines.CoroutineScope
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
    fun `stop with nothing running just switches the torch off`() = runTest {
        val torch = RecordingTorch()
        val blinker = TorchBlinker(backgroundScope, torch::set)

        blinker.stop()
        blinker.stop()

        assertEquals(listOf(false, false), torch.switches)
        assertFalse(blinker.isBlinking)
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
    }
}
