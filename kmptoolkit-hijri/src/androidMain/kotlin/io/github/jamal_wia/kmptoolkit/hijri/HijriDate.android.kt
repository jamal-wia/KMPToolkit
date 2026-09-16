package io.github.jamal_wia.kmptoolkit.hijri

import android.icu.util.IslamicCalendar
import android.icu.util.ULocale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import android.icu.util.TimeZone as IcuTimeZone

/**
 * The calculation type is pinned rather than left to the locale: ICU resolves a locale's Islamic
 * calendar differently between its own versions, so a locale-derived calendar would quietly change
 * the date under the reader on an OS upgrade.
 */
public actual fun LocalDate.toHijriDate(): HijriDate {
    val calendar = IslamicCalendar(IcuTimeZone.GMT_ZONE, ULocale.ROOT)
    calendar.calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
    // Midnight UTC read by a UTC calendar: the conversion carries no hour that a zone could push
    // across a boundary.
    calendar.timeInMillis = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    return HijriDate(
        year = calendar.get(IslamicCalendar.YEAR),
        // ICU counts months from zero; the public contract counts from one.
        month = calendar.get(IslamicCalendar.MONTH) + 1,
        dayOfMonth = calendar.get(IslamicCalendar.DAY_OF_MONTH),
    )
}
