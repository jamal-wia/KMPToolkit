package io.github.jamal_wia.kmptoolkit.hijri

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

/**
 * The shared contract, run against ICU's `IslamicCalendar`.
 *
 * Robolectric rather than a plain JVM test: `android.icu` is part of the Android runtime, and
 * without it every call answers "Method setCalculationType not mocked" instead of a date.
 */
@RunWith(RobolectricTestRunner::class)
class HijriDateAndroidTest {

    @Test
    fun `the anchor dates convert to the published Umm al-Qura dates`() =
        HijriDateContract.anchorDatesConvertToPublishedValues()

    @Test
    fun `the month is one-based so it indexes a twelve-name array`() =
        HijriDateContract.monthIsOneBasedAndDayIsInRange()

    @Test
    fun `one more civil day is one more Hijri day, or the first of the next month`() =
        HijriDateContract.eachCivilDayIsTheNextHijriDayOrAMonthRollover()

    @Test
    fun `a later civil date is never an earlier Hijri date`() =
        HijriDateContract.aLaterCivilDateIsNeverAnEarlierHijriDate()

    @Test
    fun `the Hijri year turns over inside the civil year, not with it`() =
        HijriDateContract.theHijriYearTurnsOverInsideTheCivilYear()
}
