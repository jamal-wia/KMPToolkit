package io.github.jamal_wia.kmptoolkit.hijri

import kotlinx.datetime.LocalDate

/**
 * A date on the Umm al-Qura calendar — the Saudi civil calendar, and the one Islamic calendar both
 * Android and iOS compute from the same published tables rather than from a moon sighting.
 *
 * A sighting is what a local mosque announces, so this date can legitimately sit a day away from
 * the one on someone's wall. That difference is a fact about the calendar, not a defect here, and
 * an app that shows the date usually offers the reader a correction of a day either way.
 *
 * [month] runs `1..12` with Muharram first, so it indexes an array of month names directly. Month
 * names are deliberately absent: this module returns numbers, and the copy is the consuming app's
 * to localize.
 */
public data class HijriDate(
    public val year: Int,
    public val month: Int,
    public val dayOfMonth: Int,
)

/**
 * This date on the Umm al-Qura calendar.
 *
 * The conversion is delegated to the platform rather than computed here. A Hijri month is 29 or 30
 * days and which one is not a formula — it is a table the Saudi authority publishes, and both
 * platforms already carry it. Arithmetic of our own would disagree with the phone's own calendar
 * for whole months at a time, which is the one thing a date library must not do.
 *
 * The conversion is date-to-date: no hour is involved, so no time zone can push the result across
 * a day boundary. Callers decide which day it is in their zone before calling.
 */
public expect fun LocalDate.toHijriDate(): HijriDate
