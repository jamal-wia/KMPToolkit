package io.github.jamal_wia.kmptoolkit.hijri

import kotlin.test.Test

/**
 * The shared contract, run against Foundation's `NSCalendar`. Same expectations as the Android
 * half — if the two platforms ever disagree about a date, one of these two classes fails.
 */
class HijriDateIosTest {

    @Test
    fun anchorDatesConvertToPublishedValues() =
        HijriDateContract.anchorDatesConvertToPublishedValues()

    @Test
    fun monthIsOneBasedAndDayIsInRange() =
        HijriDateContract.monthIsOneBasedAndDayIsInRange()

    @Test
    fun eachCivilDayIsTheNextHijriDayOrAMonthRollover() =
        HijriDateContract.eachCivilDayIsTheNextHijriDayOrAMonthRollover()

    @Test
    fun aLaterCivilDateIsNeverAnEarlierHijriDate() =
        HijriDateContract.aLaterCivilDateIsNeverAnEarlierHijriDate()

    @Test
    fun theHijriYearTurnsOverInsideTheCivilYear() =
        HijriDateContract.theHijriYearTurnsOverInsideTheCivilYear()
}
