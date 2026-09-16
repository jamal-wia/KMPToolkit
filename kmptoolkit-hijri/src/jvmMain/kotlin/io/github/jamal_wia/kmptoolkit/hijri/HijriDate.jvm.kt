package io.github.jamal_wia.kmptoolkit.hijri

import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

/**
 * The desktop half, on `java.time.chrono.HijrahDate`.
 *
 * No calculation type to pin here, unlike ICU: the JDK ships exactly one Hijrah variant, and it is
 * `Hijrah-umalqura` — the same published tables the other two platforms read. The shared contract
 * test is what holds that claim up rather than this comment.
 *
 * No time zone is involved at all: `HijrahDate.from` converts a date to a date, which is what the
 * other two platforms go to the trouble of reading in UTC to achieve.
 */
public actual fun LocalDate.toHijriDate(): HijriDate {
    val hijrah: HijrahDate = HijrahDate.from(toJavaLocalDate())
    return HijriDate(
        year = hijrah.get(ChronoField.YEAR),
        month = hijrah.get(ChronoField.MONTH_OF_YEAR),
        dayOfMonth = hijrah.get(ChronoField.DAY_OF_MONTH),
    )
}
