package io.github.jamal_wia.kmptoolkit.hijri

import kotlin.test.Test

/**
 * The shared contract, run against the JDK's `HijrahDate`. Same expectations as the Android and iOS
 * halves — three independent readings of the Umm al-Qura tables, checked against one another.
 */
class HijriDateJvmTest {

    @Test
    fun `the anchor dates convert to the published Umm al-Qura dates`() =
        HijriDateContract.anchorDatesConvertToPublishedValues()

    @Test
    fun `the month is one-based so it indexes a twelve-name array`() =
        HijriDateContract.monthIsOneBasedAndDayIsInRange()

    @Test
    fun `one more civil day is one more Hijri day or the first of the next month`() =
        HijriDateContract.eachCivilDayIsTheNextHijriDayOrAMonthRollover()

    @Test
    fun `a later civil date is never an earlier Hijri date`() =
        HijriDateContract.aLaterCivilDateIsNeverAnEarlierHijriDate()

    @Test
    fun `the Hijri year turns over inside the civil year and not with it`() =
        HijriDateContract.theHijriYearTurnsOverInsideTheCivilYear()
}
