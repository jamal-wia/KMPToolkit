package io.github.jamal_wia.kmptoolkit.uploader

import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970

private const val MILLIS_PER_SECOND: Double = 1_000.0

internal actual fun currentEpochMillis(): Long =
    (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()

internal actual fun randomUploaderItemId(): String = NSUUID().UUIDString()
