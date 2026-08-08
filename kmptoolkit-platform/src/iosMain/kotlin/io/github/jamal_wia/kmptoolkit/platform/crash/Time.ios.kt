package io.github.jamal_wia.kmptoolkit.platform.crash

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

private const val MILLIS_PER_SECOND: Double = 1000.0

internal actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()
