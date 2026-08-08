package io.github.jamal_wia.kmptoolkit.scheduler

/**
 * One local event to fire at an exact wall-clock instant, offline, without a server round trip.
 *
 * A scheduler only arms and cancels alarms. **What** happens when one fires is an [AlarmHandler]'s
 * job on Android, and the OS notification's job on iOS — never the scheduler's.
 *
 * @param id stable identity of this alarm. Re-scheduling the same id **replaces** the pending
 *   alarm on both platforms rather than adding a second one, and [AlarmScheduler.cancel] takes
 *   this value. Must not be blank.
 * @param type routing key matched against [AlarmHandler.type] when the alarm fires on Android.
 *   Matching is exact and case-sensitive. Must not be blank.
 * @param fireAtEpochMillis the instant to fire at, in milliseconds since the Unix epoch (UTC), as
 *   returned by `System.currentTimeMillis()` / `NSDate.timeIntervalSince1970 * 1000`. Deliberately
 *   a `Long` rather than a date-time type so the module needs no date-time dependency. A value
 *   already in the past is legal — see [AlarmScheduler.schedule] for what each platform does with
 *   it.
 * @param notification what the OS shows when the alarm fires. Carried with the alarm because iOS
 *   cannot run any of your code at fire time once the app is killed: the text has to be handed to
 *   the OS at *schedule* time. On Android the handler runs at fire time and may ignore this and
 *   build its own display instead (e.g. in the language the user has since switched to).
 * @param payload free-form data carried through to the tap that follows the fired notification. A
 *   `Map<String, String>` because that is what both platforms natively carry — Android intent
 *   extras, iOS `userInfo` — so neither side has to invent an encoding and this module needs no
 *   serialization dependency. On iOS these entries land in `userInfo` verbatim, which is all a tap
 *   handler ever receives; populating them with the same keys your remote pushes use lets one tap
 *   handler serve both sources.
 */
public data class ScheduledAlarm(
    val id: String,
    val type: String,
    val fireAtEpochMillis: Long,
    val notification: AlarmNotification,
    val payload: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "ScheduledAlarm.id must not be blank." }
        require(type.isNotBlank()) { "ScheduledAlarm.type must not be blank." }
    }
}

/**
 * What the OS shows when an alarm fires — the minimum both platforms need at schedule time.
 *
 * Deliberately string-only, with no icon or sound handle: it has to survive a round trip through
 * Android intent extras, and keeping it free of platform resource handles keeps this module free
 * of any notification-rendering dependency.
 *
 * There is no sound field. On Android the sound belongs to the notification channel; on iOS the
 * scheduler resolves it from [channelId] through [AlarmSoundResolver] at schedule time. The sound
 * therefore lives in exactly one place and is never duplicated onto the alarm.
 *
 * [title] and [body] are strings **you** supply, already localized. This module never generates,
 * translates, or defaults any user-facing text.
 *
 * @param title notification title, shown as-is.
 * @param body notification body, shown as-is.
 * @param channelId the Android notification channel the handler should post to, and the key iOS
 *   passes to [AlarmSoundResolver]. This module neither creates nor validates the channel.
 */
public data class AlarmNotification(
    val title: String,
    val body: String,
    val channelId: String,
)
