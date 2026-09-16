package io.github.jamal_wia.kmptoolkit.hijri

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract from `docs/kmptoolkit-hijri/01-overview.md`, written once and run by both platform
 * test classes — which is the point of putting it here rather than in a test class of its own.
 *
 * Android converts through ICU and iOS through Foundation: two independent implementations of the
 * same published tables. One expectation asserted against both is a cross-check no single-platform
 * test can give, and a disagreement fails in CI rather than on somebody's phone.
 *
 * Not a test class itself: `android.icu` needs a real Android runtime, so the Android half has to
 * run under Robolectric, and a `@RunWith` annotation cannot live in common code.
 */
internal object HijriDateContract {

    fun anchorDatesConvertToPublishedValues() {
        assertAnchorDates(where = "in the test's own zone")
    }

    /**
     * The day-by-day checks below walk 2026 and 2027, and neither has a 29 February. 2024 is an
     * ordinary leap year; 2000 is the century one, a leap year only because it divides by 400.
     */
    fun aLeapDayConvertsLikeAnyOtherDay() {
        assertEquals(HijriDate(year = 1445, month = 8, dayOfMonth = 18), LocalDate(2024, 2, 28).toHijriDate())
        assertEquals(HijriDate(year = 1445, month = 8, dayOfMonth = 19), LocalDate(2024, 2, 29).toHijriDate())
        assertEquals(HijriDate(year = 1445, month = 8, dayOfMonth = 20), LocalDate(2024, 3, 1).toHijriDate())
        assertEquals(HijriDate(year = 1420, month = 11, dayOfMonth = 23), LocalDate(2000, 2, 29).toHijriDate())
    }

    /**
     * The phone's zone must not move the date, and it takes both extremes to show it. Midnight taken
     * in the phone's zone lands on the previous day east of UTC; midnight UTC read by a calendar in
     * the phone's zone lands on the previous day west of it. A CI machine runs in UTC, where neither
     * mistake changes a single date.
     */
    fun theDeviceTimeZoneDoesNotMoveTheDate() {
        for (zoneId: String in listOf(FURTHEST_EAST, FURTHEST_WEST)) {
            withDeviceTimeZone(zoneId) { assertAnchorDates(where = "with the device set to $zoneId") }
        }
    }

    private fun assertAnchorDates(where: String) {
        assertEquals(HijriDate(year = 1447, month = 7, dayOfMonth = 12), LocalDate(2026, 1, 1).toHijriDate(), where)
        assertEquals(HijriDate(year = 1448, month = 4, dayOfMonth = 5), LocalDate(2026, 9, 16).toHijriDate(), where)
        assertEquals(HijriDate(year = 1420, month = 9, dayOfMonth = 24), LocalDate(2000, 1, 1).toHijriDate(), where)
    }

    /**
     * The platform calendars disagree on this one — ICU counts months from zero, Foundation from
     * one — and the contract says one. A regression on either side shows up as every month name
     * being off by one on exactly one platform.
     */
    fun monthIsOneBasedAndDayIsInRange() {
        forEachDayOfTwoYears { date: LocalDate ->
            val hijri: HijriDate = date.toHijriDate()
            assertTrue(hijri.month in 1..12, "month out of range on $date: ${hijri.month}")
            assertTrue(hijri.dayOfMonth in 1..30, "day out of range on $date: ${hijri.dayOfMonth}")
        }
    }

    fun eachCivilDayIsTheNextHijriDayOrAMonthRollover() {
        var previous: HijriDate = FIRST_DAY.toHijriDate()
        forEachDayOfTwoYears(from = FIRST_DAY.plus(DatePeriod(days = 1))) { date: LocalDate ->
            val current: HijriDate = date.toHijriDate()
            val steppedWithinMonth: Boolean = current.year == previous.year &&
                current.month == previous.month &&
                current.dayOfMonth == previous.dayOfMonth + 1
            val rolledOver: Boolean = current.dayOfMonth == 1 &&
                (previous.dayOfMonth == 29 || previous.dayOfMonth == 30)
            assertTrue(
                steppedWithinMonth || rolledOver,
                "$previous -> $current is neither the next day nor a month rollover, on $date",
            )
            previous = current
        }
    }

    fun aLaterCivilDateIsNeverAnEarlierHijriDate() {
        var previous: HijriDate = FIRST_DAY.toHijriDate()
        forEachDayOfTwoYears(from = FIRST_DAY.plus(DatePeriod(days = 1))) { date: LocalDate ->
            val current: HijriDate = date.toHijriDate()
            val movedForward: Boolean = when {
                current.year != previous.year -> current.year > previous.year
                current.month != previous.month -> current.month > previous.month
                else -> current.dayOfMonth > previous.dayOfMonth
            }
            assertTrue(movedForward, "$previous -> $current went backwards, on $date")
            previous = current
        }
    }

    /**
     * A Hijri year is about eleven days shorter than a civil one, so its start walks backwards
     * through the civil year. Pinning that catches a conversion which quietly followed the civil
     * year instead.
     */
    fun theHijriYearTurnsOverInsideTheCivilYear() {
        val starts: MutableList<LocalDate> = mutableListOf()
        forEachDayOfTwoYears { date: LocalDate ->
            val hijri: HijriDate = date.toHijriDate()
            if (hijri.month == 1 && hijri.dayOfMonth == 1) starts += date
        }

        assertEquals(2, starts.size, "expected two Hijri new years in two civil years, got $starts")
        val gap: Long = starts[1].toEpochDays() - starts[0].toEpochDays()
        assertTrue(gap in 354L..355L, "a Hijri year should be 354 or 355 days, got $gap")
    }

    private inline fun forEachDayOfTwoYears(from: LocalDate = FIRST_DAY, action: (LocalDate) -> Unit) {
        var date: LocalDate = from
        repeat(DAYS_IN_TWO_YEARS) {
            action(date)
            date = date.plus(DatePeriod(days = 1))
        }
    }

    private val FIRST_DAY = LocalDate(2026, 1, 1)
    private const val DAYS_IN_TWO_YEARS: Int = 730

    // UTC+14 and UTC-11 all year round, with no daylight saving to make the offset depend on the date.
    private const val FURTHEST_EAST: String = "Pacific/Kiritimati"
    private const val FURTHEST_WEST: String = "Pacific/Pago_Pago"
}
