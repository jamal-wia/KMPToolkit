package io.github.jamal_wia.kmptoolkit.hijri

import java.util.TimeZone

/** The JDK has one default zone, and `kotlinx.datetime` reads the same one through `ZoneId`. */
internal actual fun withDeviceTimeZone(zoneId: String, block: () -> Unit) {
    val previous: TimeZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
    try {
        block()
    } finally {
        TimeZone.setDefault(previous)
    }
}
