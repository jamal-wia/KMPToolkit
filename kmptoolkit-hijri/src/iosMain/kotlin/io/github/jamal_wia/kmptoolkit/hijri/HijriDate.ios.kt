package io.github.jamal_wia.kmptoolkit.hijri

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierIslamicUmmAlQura
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT

/** The iOS half; see the Android one for why the zone is fixed to UTC. */
public actual fun LocalDate.toHijriDate(): HijriDate {
    val calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierIslamicUmmAlQura)
    calendar.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
    val startOfDay: NSDate = NSDate.dateWithTimeIntervalSince1970(
        atStartOfDayIn(TimeZone.UTC).epochSeconds.toDouble(),
    )
    val parts: NSDateComponents = calendar.components(
        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay,
        fromDate = startOfDay,
    )
    return HijriDate(
        year = parts.year.toInt(),
        month = parts.month.toInt(),
        dayOfMonth = parts.day.toInt(),
    )
}
