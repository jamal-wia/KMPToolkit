package io.github.jamal_wia.kmptoolkit.location

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [angleDifference] (PLAN D5): the signed shortest turn in (-180, 180], positive clockwise. Two
 * recipes proposed during the design got the half turn wrong, so the half turn is pinned both ways.
 */
class AngleDifferenceTest {

    @Test
    fun `the published cases`() {
        assertEquals(20.0, angleDifference(350.0, 10.0))
        assertEquals(-20.0, angleDifference(10.0, 350.0))
        assertEquals(180.0, angleDifference(0.0, 180.0))
        assertEquals(180.0, angleDifference(180.0, 0.0))
        assertEquals(180.0, angleDifference(0.0, -180.0))
        assertEquals(1.0, angleDifference(359.5, 0.5))
        assertEquals(0.0, angleDifference(0.0, 0.0))
    }

    @Test
    fun `equal angles are zero even across a full turn and never negative zero`() {
        for (angle: Double in listOf(0.0, 90.0, 359.0, -45.0, 720.0)) {
            assertEquals(0.0, angleDifference(angle, angle))
            assertEquals(0.0, angleDifference(angle, angle + 360.0))
        }
        assertTrue(1.0 / angleDifference(-0.0, 0.0) > 0.0, "the result is +0.0, not -0.0")
    }

    @Test
    fun `a turn through north is short`() {
        assertEquals(-2.0, angleDifference(1.0, 359.0))
        assertEquals(2.0, angleDifference(359.0, 1.0))
    }

    @Test
    fun `inputs outside zero to 360 are reduced`() {
        assertEquals(20.0, angleDifference(-10.0, 10.0))
        assertEquals(20.0, angleDifference(710.0, 730.0))
        assertEquals(20.0, angleDifference(350.0 - 3_600.0, 10.0 + 3_600.0))
        assertEquals(-90.0, angleDifference(270.0, 180.0))
        assertEquals(180.0, angleDifference(-90.0, 90.0))
    }

    @Test
    fun `a huge input keeps its fraction`() {
        // 1e15 is a multiple of 360 plus 280 (1e15 mod 360 = 280): the turn from there to 290.5 is 10.5.
        assertEquals(10.5, angleDifference(1e15, 290.5), 1e-9)
    }

    @Test
    fun `non-finite input throws for either argument`() {
        for (bad: Double in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("from=$bad") { angleDifference(bad, 0.0) }
            assertFailsWith<IllegalArgumentException>("to=$bad") { angleDifference(0.0, bad) }
        }
    }

    @Test
    fun `the result is always in minus 180 exclusive to 180 inclusive and is congruent to the difference`() {
        val seed: Int = GEO_TEST_SEED + 20
        val rng = Random(seed)
        repeat(100_000) { index: Int ->
            val from: Double = rng.nextDouble(-1_000.0, 1_000.0)
            val to: Double = rng.nextDouble(-1_000.0, 1_000.0)
            val turn: Double = angleDifference(from, to)
            val inRange: Boolean = turn > -180.0 && turn <= 180.0
            // The remainder of (turn - (to - from)) by 360 is zero or 360 up to rounding.
            val residue: Double = abs((turn - (to - from)) % 360.0)
            val congruent: Boolean = residue < 1e-9 || abs(residue - 360.0) < 1e-9
            if (!inRange || !congruent) {
                throw AssertionError("sample $index: from=$from to=$to gave $turn (residue $residue)${seedNote(seed)}")
            }
        }
    }

    @Test
    fun `reversing the arguments negates the turn except at the half turn`() {
        val seed: Int = GEO_TEST_SEED + 21
        val rng = Random(seed)
        repeat(50_000) { index: Int ->
            val from: Double = rng.nextDouble(0.0, 360.0)
            val to: Double = rng.nextDouble(0.0, 360.0)
            val forward: Double = angleDifference(from, to)
            val backward: Double = angleDifference(to, from)
            if (abs(forward) != 180.0 && abs(forward + backward) > 1e-12) {
                throw AssertionError("sample $index: $from <-> $to gave $forward and $backward${seedNote(seed)}")
            }
        }
    }
}
