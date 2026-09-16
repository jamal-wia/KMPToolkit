package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The periodic tick's gate in [AutoSystemBarsIconStyle]: a sample runs only once the scene has
 * settled, unless it has already been held back for the maximum deferral.
 */
class PeriodicSampleGateTest {

    @Test
    fun `settled scene is sampled`() {
        assertTrue(periodicSampleIsDue(sinceLastDrawMs = QUIET_AFTER_MS, deferredMs = 0L, animating = false))
    }

    @Test
    fun `scene that never drew is sampled`() {
        assertTrue(periodicSampleIsDue(sinceLastDrawMs = Long.MAX_VALUE, deferredMs = 0L, animating = false))
    }

    @Test
    fun `frame drawn just now defers the sample`() {
        assertFalse(periodicSampleIsDue(sinceLastDrawMs = 0L, deferredMs = 0L, animating = false))
    }

    @Test
    fun `frame drawn just under the quiet threshold defers the sample`() {
        assertFalse(periodicSampleIsDue(sinceLastDrawMs = QUIET_AFTER_MS - 1, deferredMs = 0L, animating = false))
    }

    @Test
    fun `platform animation defers the sample even when the root has not drawn for a while`() {
        // A child animating only its layer transform never redraws the root: the draw clock says
        // quiet, the platform says animating — the platform wins.
        assertFalse(periodicSampleIsDue(sinceLastDrawMs = Long.MAX_VALUE, deferredMs = 0L, animating = true))
    }

    @Test
    fun `endless animation is still sampled once the deferral cap is reached`() {
        assertTrue(periodicSampleIsDue(sinceLastDrawMs = 0L, deferredMs = MAX_PERIODIC_DEFERRAL_MS, animating = true))
    }

    @Test
    fun `deferral just under the cap keeps deferring while frames flow`() {
        assertFalse(
            periodicSampleIsDue(sinceLastDrawMs = 0L, deferredMs = MAX_PERIODIC_DEFERRAL_MS - 1, animating = true),
        )
    }

    @Test
    fun `without a platform signal a root-drawing animation is still capped`() {
        // iOS: rememberIsAnimating() is null → animating=false; frames keep the draw clock at 0.
        assertFalse(periodicSampleIsDue(sinceLastDrawMs = 0L, deferredMs = MAX_PERIODIC_DEFERRAL_MS - 1, animating = false))
        assertTrue(periodicSampleIsDue(sinceLastDrawMs = 0L, deferredMs = MAX_PERIODIC_DEFERRAL_MS, animating = false))
    }

    @Test
    fun `constants keep the quiet window inside one tick and the cap at two seconds`() {
        assertEquals(100L, QUIET_AFTER_MS)
        assertEquals(2_000L, MAX_PERIODIC_DEFERRAL_MS)
        assertEquals(300L, DEFAULT_SAMPLE_INTERVAL_MS)
    }

    @Test
    fun `endless animation at the default cadence is sampled on the eighth tick under the full cap`() {
        val deferral = PeriodicDeferral(DEFAULT_SAMPLE_INTERVAL_MS, nextCapMs = { MAX_PERIODIC_DEFERRAL_MS })
        val decisions: List<Boolean> = List(9) { deferral.onTick(sinceLastDrawMs = 0L, animating = true) }
        assertEquals(listOf(false, false, false, false, false, false, false, true, false), decisions)
    }

    @Test
    fun `the shortest jittered cap samples on the fifth tick`() {
        val shortest: Long = MAX_PERIODIC_DEFERRAL_MS - PERIODIC_DEFERRAL_JITTER_MS
        val deferral = PeriodicDeferral(DEFAULT_SAMPLE_INTERVAL_MS, nextCapMs = { shortest })
        val decisions: List<Boolean> = List(6) { deferral.onTick(sinceLastDrawMs = 0L, animating = true) }
        assertEquals(listOf(false, false, false, false, true, false), decisions)
    }

    @Test
    fun `a new cap is drawn after every sample`() {
        val caps: ArrayDeque<Long> = ArrayDeque(
            listOf(MAX_PERIODIC_DEFERRAL_MS, MAX_PERIODIC_DEFERRAL_MS - PERIODIC_DEFERRAL_JITTER_MS, MAX_PERIODIC_DEFERRAL_MS),
        )
        val deferral = PeriodicDeferral(DEFAULT_SAMPLE_INTERVAL_MS, nextCapMs = { caps.removeFirst() })
        val sampledOn: List<Int> = (1..13).filter { deferral.onTick(sinceLastDrawMs = 0L, animating = true) }
        // 8th tick under the first cap, then the 5th tick of the next cycle.
        assertEquals(listOf(8, 13), sampledOn)
        assertEquals(0, caps.size)
    }

    @Test
    fun `a sample resets the deferral instead of leaving it at the cap`() {
        val deferral = PeriodicDeferral(DEFAULT_SAMPLE_INTERVAL_MS, nextCapMs = { MAX_PERIODIC_DEFERRAL_MS })
        repeat(7) { deferral.onTick(sinceLastDrawMs = 0L, animating = true) }
        assertTrue(deferral.onTick(sinceLastDrawMs = 0L, animating = true))
        assertFalse(deferral.onTick(sinceLastDrawMs = 0L, animating = true))
    }

    @Test
    fun `a quiet tick samples at once and does not consume the deferral budget`() {
        val deferral = PeriodicDeferral(DEFAULT_SAMPLE_INTERVAL_MS, nextCapMs = { MAX_PERIODIC_DEFERRAL_MS })
        assertTrue(deferral.onTick(sinceLastDrawMs = QUIET_AFTER_MS, animating = false))
        assertTrue(deferral.onTick(sinceLastDrawMs = QUIET_AFTER_MS, animating = false))
    }

    @Test
    fun `the first tick ignores the draw clock and samples a non-animating scene`() {
        // It follows the frame it just awaited, so "drawn 0 ms ago" is true by construction.
        val deferral = PeriodicDeferral(DEFAULT_SAMPLE_INTERVAL_MS, nextCapMs = { MAX_PERIODIC_DEFERRAL_MS })
        assertTrue(deferral.onTick(sinceLastDrawMs = 0L, animating = false))
    }

    @Test
    fun `only the first tick ignores the draw clock`() {
        val deferral = PeriodicDeferral(DEFAULT_SAMPLE_INTERVAL_MS, nextCapMs = { MAX_PERIODIC_DEFERRAL_MS })
        deferral.onTick(sinceLastDrawMs = 0L, animating = false)
        assertFalse(deferral.onTick(sinceLastDrawMs = 0L, animating = false))
    }

    @Test
    fun `the first tick still defers while the platform reports an animation`() {
        val deferral = PeriodicDeferral(DEFAULT_SAMPLE_INTERVAL_MS, nextCapMs = { MAX_PERIODIC_DEFERRAL_MS })
        assertFalse(deferral.onTick(sinceLastDrawMs = Long.MAX_VALUE, animating = true))
    }

    @Test
    fun `random cap never exceeds the maximum nor undercuts it by more than the jitter`() {
        val random = Random(seed = 7)
        val caps: List<Long> = List(500) { randomDeferralCapMs(random) }
        assertTrue(caps.all { it in (MAX_PERIODIC_DEFERRAL_MS - PERIODIC_DEFERRAL_JITTER_MS)..MAX_PERIODIC_DEFERRAL_MS })
        assertTrue(caps.distinct().size > 1, "a jitter that never varies is no jitter")
    }

    @Test
    fun `a zero draw yields the maximum cap itself`() {
        // Deterministic: a Random whose every bit is zero makes nextLong(0, n) return 0.
        val zeroBits: Random = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }
        assertEquals(MAX_PERIODIC_DEFERRAL_MS, randomDeferralCapMs(zeroBits))
    }

    @Test
    fun `explicit cap overrides the default in the gate`() {
        assertTrue(periodicSampleIsDue(sinceLastDrawMs = 0L, deferredMs = 1_200L, animating = true, capMs = 1_100L))
        assertFalse(periodicSampleIsDue(sinceLastDrawMs = 0L, deferredMs = 1_200L, animating = true, capMs = 1_300L))
    }

    @Test
    fun `jitter stays smaller than the cap so a cycle always has a positive cap`() {
        assertEquals(900L, PERIODIC_DEFERRAL_JITTER_MS)
        assertTrue(PERIODIC_DEFERRAL_JITTER_MS < MAX_PERIODIC_DEFERRAL_MS)
    }
}
