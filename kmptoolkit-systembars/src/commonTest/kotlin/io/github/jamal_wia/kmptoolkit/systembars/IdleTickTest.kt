package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The idle skip in [AutoSystemBarsIconStyle]: a due tick with nothing drawn since the last sample
 * reads the same pixels again, so it is skipped — until the ticks since that sample span the idle
 * bound, and never while the platform reports an animation.
 */
class IdleTickTest {

    private val interval: Long = DEFAULT_SAMPLE_INTERVAL_MS

    @Test
    fun `nothing drawn and a recent sample make the tick redundant`() {
        assertTrue(idleTickIsRedundant(drawsSinceLastSample = 0, ticksSinceLastSample = 1, intervalMs = interval, animating = false))
    }

    @Test
    fun `one root draw since the last sample is enough to read again`() {
        assertFalse(idleTickIsRedundant(drawsSinceLastSample = 1, ticksSinceLastSample = 1, intervalMs = interval, animating = false))
    }

    @Test
    fun `the idle bound forces a sample on the seventh tick at the default cadence`() {
        // 6 × 300 ms = 1.8 s < 2 s: still redundant; 7 × 300 ms = 2.1 s: read again.
        assertTrue(idleTickIsRedundant(drawsSinceLastSample = 0, ticksSinceLastSample = 6, intervalMs = interval, animating = false))
        assertFalse(idleTickIsRedundant(drawsSinceLastSample = 0, ticksSinceLastSample = 7, intervalMs = interval, animating = false))
    }

    @Test
    fun `an animation is never redundant even when the root did not draw`() {
        // A layer-only animation changes the pixels under the bars without a root draw.
        assertFalse(idleTickIsRedundant(drawsSinceLastSample = 0, ticksSinceLastSample = 1, intervalMs = interval, animating = true))
    }

    @Test
    fun `ticks that land exactly on the idle bound are no longer redundant`() {
        // 3 × 500 ms = 1.5 s: still under; 4 × 500 ms = 2 s: the bound is reached, not still under it.
        assertTrue(idleTickIsRedundant(drawsSinceLastSample = 0, ticksSinceLastSample = 3, intervalMs = 500L, animating = false))
        assertFalse(idleTickIsRedundant(drawsSinceLastSample = 0, ticksSinceLastSample = 4, intervalMs = 500L, animating = false))
    }

    @Test
    fun `a cadence no faster than the idle bound never skips`() {
        assertFalse(
            idleTickIsRedundant(drawsSinceLastSample = 0, ticksSinceLastSample = 1, intervalMs = IDLE_RESAMPLE_MS, animating = false),
        )
    }

    @Test
    fun `a cap sample inside the idle bound is still taken while animating`() {
        // Under a jittered cap an endless animation is sampled on the 5th–7th tick (1.5–2.1 s) with
        // no root draw since the previous sample: only `animating` keeps that cap sample from being
        // skipped.
        for (tick in 5..7) {
            assertFalse(
                idleTickIsRedundant(drawsSinceLastSample = 0, ticksSinceLastSample = tick, intervalMs = interval, animating = true),
                "tick $tick",
            )
        }
    }

    @Test
    fun `the idle bound sits between the cadence and the deferral cap`() {
        assertTrue(IDLE_RESAMPLE_MS > DEFAULT_SAMPLE_INTERVAL_MS)
        assertTrue(IDLE_RESAMPLE_MS <= MAX_PERIODIC_DEFERRAL_MS)
    }
}
