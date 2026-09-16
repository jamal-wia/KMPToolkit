package io.github.jamal_wia.kmptoolkit.hijri

import android.icu.util.TimeZone as IcuTimeZone
import java.util.TimeZone as JavaTimeZone

/**
 * ICU keeps its own copy of the default zone. On a device, libcore drops that copy whenever the
 * `java.util` default changes; under Robolectric `java.util.TimeZone` is the host JDK's, which knows
 * nothing about ICU, so the copy has to be dropped here. `setICUDefault(null)` is that drop — it is
 * hidden from the SDK stubs, hence the reflection. Without it an `IslamicCalendar` built on the
 * default zone would keep reading whichever zone ICU saw first, and the test could not fail.
 */
internal actual fun withDeviceTimeZone(zoneId: String, block: () -> Unit) {
    val previous: JavaTimeZone = JavaTimeZone.getDefault()
    JavaTimeZone.setDefault(JavaTimeZone.getTimeZone(zoneId))
    dropIcuDefaultZone()
    try {
        block()
    } finally {
        JavaTimeZone.setDefault(previous)
        dropIcuDefaultZone()
    }
}

private fun dropIcuDefaultZone() {
    IcuTimeZone::class.java
        .getMethod("setICUDefault", IcuTimeZone::class.java)
        .invoke(null, null)
}
