package io.github.jamal_wia.kmptoolkit.hijri

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Foundation.NSTimeZone
import platform.Foundation.defaultTimeZone
import platform.Foundation.resetSystemTimeZone
import platform.Foundation.setDefaultTimeZone
import platform.Foundation.systemTimeZone
import platform.Foundation.timeZoneWithName
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv

/**
 * Foundation has two zones a conversion could read: the default one, which `NSCalendar` starts
 * from, and the system one, which `kotlinx.datetime` asks for. The system zone comes from `TZ` once
 * its cache is reset, so both are set here, and both are put back.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun withDeviceTimeZone(zoneId: String, block: () -> Unit) {
    val zone: NSTimeZone = checkNotNull(NSTimeZone.timeZoneWithName(zoneId)) { "unknown zone $zoneId" }
    val previousTz: String? = getenv(TZ)?.toKString()
    val previousDefault: NSTimeZone = NSTimeZone.defaultTimeZone
    setenv(TZ, zoneId, 1)
    NSTimeZone.resetSystemTimeZone()
    NSTimeZone.setDefaultTimeZone(zone)
    check(NSTimeZone.systemTimeZone.name == zoneId) { "the system zone did not follow TZ=$zoneId" }
    try {
        block()
    } finally {
        if (previousTz == null) unsetenv(TZ) else setenv(TZ, previousTz, 1)
        NSTimeZone.resetSystemTimeZone()
        NSTimeZone.setDefaultTimeZone(previousDefault)
    }
}

private const val TZ: String = "TZ"
