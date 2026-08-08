package io.github.jamal_wia.kmptoolkit.scheduler

internal const val MILLIS_PER_SECOND = 1000.0

/**
 * Smallest interval iOS accepts for a `UNTimeIntervalNotificationTrigger`. The API rejects a
 * non-positive interval outright, so an alarm whose time has already passed — a just-missed
 * reminder, a clock correction, a device that was off — is clamped to "about now" instead of
 * throwing.
 */
internal const val MIN_TRIGGER_INTERVAL_SECONDS = 1.0

/**
 * Seconds from [nowEpochMillis] until [fireAtEpochMillis], clamped to at least
 * [MIN_TRIGGER_INTERVAL_SECONDS].
 *
 * Lives in common code purely so it can be tested without a device: the iOS scheduler is otherwise
 * a thin wrapper over `UNUserNotificationCenter`, and this arithmetic — the past-time clamp in
 * particular — is the only part of it that can be wrong on its own.
 */
internal fun triggerIntervalSeconds(fireAtEpochMillis: Long, nowEpochMillis: Long): Double {
    // Subtract in Double, not Long: a far-future fire time minus a negative "now" overflows a Long
    // and would wrap into the past, silently turning a distant alarm into an immediate one.
    val delta: Double = (fireAtEpochMillis.toDouble() - nowEpochMillis.toDouble()) / MILLIS_PER_SECOND
    return if (delta > MIN_TRIGGER_INTERVAL_SECONDS) delta else MIN_TRIGGER_INTERVAL_SECONDS
}
