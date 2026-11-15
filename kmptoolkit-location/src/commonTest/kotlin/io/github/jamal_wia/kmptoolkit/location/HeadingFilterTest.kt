package io.github.jamal_wia.kmptoolkit.location

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The minimum-change rules of `HeadingProvider.observeHeading` (PLAN decision D25, group loc-heading),
 * which both platform providers apply through [HeadingFilter]: the first reading passes; afterwards a
 * reading passes when the magnetic heading moved at least `minChangeDegrees` from the last heading that
 * PASSED, measured across the 0/360 seam; a calibration flip or `trueDegrees` going null or non-null
 * always passes. Also [normalizeDegrees], which keeps every emitted angle in `[0, 360)`.
 *
 * Every random draw comes from `Random(HEADING_TEST_SEED)`-derived generators and the seed is in each
 * failure message.
 */
class HeadingFilterTest {

    private fun heading(
        magnetic: Double,
        trueDegrees: Double? = magnetic,
        needsCalibration: Boolean = false,
    ): Heading = Heading(magnetic, trueDegrees, accuracyDegrees = null, needsCalibration = needsCalibration)

    private fun HeadingFilter.passes(candidate: Heading): Boolean = accept(candidate) != null

    @Test
    fun `the first reading passes whatever the filter`() {
        assertTrue(HeadingFilter(1.0).passes(heading(123.0)))
        assertTrue(HeadingFilter(1e300).passes(heading(0.0)))
        assertTrue(HeadingFilter(0.0).passes(heading(359.999)))
    }

    @Test
    fun `a change below the filter is dropped and a change equal to it passes`() {
        val filter = HeadingFilter(1.0)
        filter.accept(heading(10.0))

        assertNull(filter.accept(heading(10.999)), "0.999 degrees is below a 1 degree filter")
        assertNotNull(filter.accept(heading(11.0)), "exactly the filter is 'at least', so it passes")
    }

    @Test
    fun `the change is measured across the seam so 359_5 to 0_5 is one degree`() {
        val passing = HeadingFilter(1.0)
        passing.accept(heading(359.5))
        assertNotNull(passing.accept(heading(0.5)), "359.5 -> 0.5 is a 1 degree turn across north")

        val below = HeadingFilter(1.0)
        below.accept(heading(359.5))
        assertNull(below.accept(heading(0.4)), "359.5 -> 0.4 is 0.9 degrees")

        // The naive abs(new - old) sees 359 here and fires on a 0.9 degree turn; the opposite mistake,
        // a needle that freezes at north, is the next assertion.
        val reverse = HeadingFilter(1.0)
        reverse.accept(heading(0.5))
        assertNotNull(reverse.accept(heading(359.5)), "0.5 -> 359.5 is a 1 degree turn the other way")
    }

    @Test
    fun `slow turns in steps below the filter add up from the last emitted heading`() {
        val filter = HeadingFilter(1.0)
        val steps: List<Double> = listOf(0.0, 0.4, 0.8, 1.2, 1.6, 2.0, 2.4)

        // Control: comparing consecutive samples, no step reaches 1 degree, so that filter would freeze
        // the needle for the whole turn.
        assertTrue(steps.zipWithNext().all { (a: Double, b: Double) -> abs(b - a) < 1.0 }, "control: every step is 0.4")

        val emitted: List<Double> = steps.mapNotNull { degrees: Double -> filter.accept(heading(degrees))?.magneticDegrees }

        assertEquals(listOf(0.0, 1.2, 2.4), emitted, "reference is the last EMITTED heading, not the previous sample")
    }

    @Test
    fun `a dropped reading does not move the reference`() {
        val filter = HeadingFilter(1.0)
        filter.accept(heading(0.0))

        assertNull(filter.accept(heading(0.9)))
        assertNotNull(filter.accept(heading(1.0)), "1.0 is a full degree from the emitted 0.0, though only 0.1 from 0.9")
    }

    @Test
    fun `a calibration flip passes without movement in both directions`() {
        val filter = HeadingFilter(45.0)
        filter.accept(heading(100.0, needsCalibration = false))

        assertNotNull(filter.accept(heading(100.0, needsCalibration = true)), "needs calibration appeared")
        assertNull(filter.accept(heading(100.0, needsCalibration = true)), "control: the same state again is not news")
        assertNotNull(filter.accept(heading(100.0, needsCalibration = false)), "calibration was fixed")
    }

    @Test
    fun `true north appearing or disappearing passes without movement a changed value does not`() {
        val filter = HeadingFilter(45.0)
        filter.accept(heading(100.0, trueDegrees = null))

        assertNotNull(filter.accept(heading(100.0, trueDegrees = 104.0)), "null -> non-null")
        assertNull(filter.accept(heading(100.0, trueDegrees = 105.0)), "non-null -> another non-null is no news")
        assertNotNull(filter.accept(heading(100.0, trueDegrees = null)), "non-null -> null")
    }

    @Test
    fun `a zero filter passes every reading including an identical one`() {
        val filter = HeadingFilter(0.0)
        filter.accept(heading(50.0))

        assertNotNull(filter.accept(heading(50.0)))
        assertNotNull(filter.accept(heading(50.0000001)))
    }

    @Test
    fun `a huge filter lets only the first reading and state changes through`() {
        val filter = HeadingFilter(1e300)
        filter.accept(heading(0.0))

        assertNull(filter.accept(heading(180.0)), "even a half turn is below 1e300")
        assertNotNull(filter.accept(heading(180.0, needsCalibration = true)))
    }

    @Test
    fun `a half turn is a change of 180 degrees in either direction`() {
        val filter = HeadingFilter(180.0)
        filter.accept(heading(0.0))
        assertNotNull(filter.accept(heading(180.0)), "0 -> 180 is exactly 180")
        assertNotNull(filter.accept(heading(0.0)), "180 -> 0 is exactly 180")
    }

    @Test
    fun `a reading with a non finite magnetic heading never passes and leaves the reference alone`() {
        val filter = HeadingFilter(1.0)
        filter.accept(heading(10.0))

        assertNull(filter.accept(heading(Double.NaN)))
        assertNull(filter.accept(heading(Double.POSITIVE_INFINITY)))
        assertNull(filter.accept(heading(10.5)), "control: the reference is still 10.0, 0.5 is below the filter")
        assertNotNull(filter.accept(heading(11.0)))
    }

    @Test
    fun `a filter that is negative or not finite is refused when it is built and zero is allowed`() {
        assertFailsWith<IllegalArgumentException> { HeadingFilter(-0.001) }
        assertFailsWith<IllegalArgumentException> { HeadingFilter(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { HeadingFilter(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { HeadingFilter(Double.NEGATIVE_INFINITY) }
        HeadingFilter(0.0)
        HeadingFilter(-0.0)
    }

    @Test
    fun `two filters are independent`() {
        val tight = HeadingFilter(1.0)
        val loose = HeadingFilter(10.0)
        tight.accept(heading(0.0))
        loose.accept(heading(0.0))

        assertNotNull(tight.accept(heading(2.0)))
        assertNull(loose.accept(heading(2.0)))
        assertNotNull(loose.accept(heading(10.0)))
        assertNull(tight.accept(heading(2.5)), "the tight filter's reference is its own last pass, 2.0")
    }

    @Test
    fun `normalizeDegrees never returns 360 or minus zero`() {
        assertEquals(0.0, normalizeDegrees(360.0))
        assertEquals(0.0, normalizeDegrees(-360.0))
        assertEquals(0.0, normalizeDegrees(720.0))
        assertEquals(359.0, normalizeDegrees(-1.0))
        assertEquals(1.0, normalizeDegrees(361.0))
        assertEquals(0.0, normalizeDegrees(-1e-20), "a tiny negative angle rounds up to a full turn: it must read 0")
        assertEquals(0L, normalizeDegrees(-0.0).toRawBits(), "-0.0 must come out as +0.0")
        assertTrue(normalizeDegrees(Double.NaN).isNaN())
        assertTrue(!normalizeDegrees(Double.POSITIVE_INFINITY).isFinite())
        val rng = Random(HEADING_TEST_SEED)
        repeat(10_000) {
            val value: Double = rng.nextDouble(-1e6, 1e6)
            val normalized: Double = normalizeDegrees(value)
            assertTrue(normalized >= 0.0 && normalized < 360.0, "value=$value normalized=$normalized" + seedNote(HEADING_TEST_SEED))
        }
    }

    @Test
    fun `random sequences agree with an independently written decision rule`() {
        // Headings on a 0.25 degree grid: every difference is exact in binary, so the reference below
        // (a different formulation of the wrapped difference) and the filter must decide identically,
        // including at an exact tie with the filter.
        val filters: List<Double> = listOf(0.0, 0.25, 1.0, 2.5, 90.0, 180.0)
        repeat(2_000) { run: Int ->
            val rng = Random(HEADING_TEST_SEED + run)
            val min: Double = filters[rng.nextInt(filters.size)]
            val filter = HeadingFilter(min)
            var reference: Heading? = null
            repeat(60) { step: Int ->
                val magnetic: Double = rng.nextInt(0, 1440) * 0.25
                val candidate = Heading(
                    magneticDegrees = magnetic,
                    trueDegrees = if (rng.nextInt(10) == 0) null else magnetic,
                    accuracyDegrees = null,
                    needsCalibration = rng.nextInt(12) == 0,
                )
                val last: Heading? = reference
                val expected: Boolean = last == null ||
                    last.needsCalibration != candidate.needsCalibration ||
                    (last.trueDegrees == null) != (candidate.trueDegrees == null) ||
                    wrappedChange(last.magneticDegrees, magnetic) >= min
                if (expected) reference = candidate

                val actual: Boolean = filter.accept(candidate) != null
                assertEquals(expected, actual, "run=$run step=$step min=$min last=${last?.magneticDegrees} new=$magnetic" + seedNote(HEADING_TEST_SEED + run))
            }
        }
    }

    /** The smaller angle between two headings, written differently from `angleDifference`. */
    private fun wrappedChange(from: Double, to: Double): Double = abs((((to - from) % 360.0) + 540.0) % 360.0 - 180.0)
}
