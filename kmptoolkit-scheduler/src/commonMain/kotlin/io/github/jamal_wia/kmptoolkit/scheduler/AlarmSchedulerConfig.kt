package io.github.jamal_wia.kmptoolkit.scheduler

/**
 * The identifiers this module puts into platform artifacts on your behalf.
 *
 * Every one of them ends up somewhere a consumer can observe — an intent's data URI, an iOS
 * `userInfo` dictionary — so none of them is hardcoded to a name this library invented. The
 * defaults are derived from your own application id (Android) or are the conventional key names,
 * and you can replace any of them without touching the rest.
 *
 * There is no iOS background-task identifier here because this module never registers one: iOS
 * scheduling goes through `UNUserNotificationCenter`, which needs no `BGTaskScheduler`
 * registration and runs none of your code. See `05-platform-notes.md`.
 *
 * @param alarmIntentScheme **Android only.** URI scheme for the per-alarm data URI that keeps two
 *   alarms' `PendingIntent`s distinct. `null` (the default) derives it from the application id, as
 *   `<applicationId>.alarm` — unique per app, so two apps built from this library never collide.
 *   A non-null value must be a valid URI scheme: a letter followed by letters, digits, `+`, `-`,
 *   or `.`. **Keep it stable across app launches and versions:** an alarm armed under one scheme
 *   cannot be cancelled or replaced once the scheme changes, because its `PendingIntent` is matched
 *   on the scheme. Cancelling it is then a silent no-op, and scheduling the same id again arms a
 *   second alarm next to the old one.
 * @param alarmIdKey key carrying [ScheduledAlarm.id] — an intent extra on Android, a `userInfo`
 *   entry on iOS. Change it if it would collide with a key in your own [ScheduledAlarm.payload],
 *   since on iOS the two share one flat dictionary. **Keep it stable across app versions**, like
 *   [alarmIntentScheme]: an Android alarm armed under an earlier key is read with the new one when
 *   it fires, finds no id, and is dropped without a trace.
 * @param alarmTypeKey key carrying [ScheduledAlarm.type], on the same terms as [alarmIdKey],
 *   including keeping it stable across app versions.
 * @throws IllegalArgumentException if a key is blank, the two keys are equal, a key is one of the
 *   names this module writes into the same intent extras for its own use (`kmptoolkit_alarm_fire_at`,
 *   `kmptoolkit_alarm_title`, `kmptoolkit_alarm_body`, `kmptoolkit_alarm_channel`,
 *   `kmptoolkit_alarm_payload`: the key would overwrite or be overwritten by that entry and the alarm
 *   would be corrupted or dropped), or [alarmIntentScheme] is non-null and not a valid URI scheme. Failing at construction beats
 *   failing months later on an alarm that silently never fired.
 */
public data class AlarmSchedulerConfig(
    val alarmIntentScheme: String? = null,
    val alarmIdKey: String = DEFAULT_ALARM_ID_KEY,
    val alarmTypeKey: String = DEFAULT_ALARM_TYPE_KEY,
) {
    init {
        require(alarmIdKey.isNotBlank()) { "alarmIdKey must not be blank." }
        require(alarmTypeKey.isNotBlank()) { "alarmTypeKey must not be blank." }
        require(alarmIdKey != alarmTypeKey) {
            "alarmIdKey and alarmTypeKey must differ; both were '$alarmIdKey'."
        }
        require(alarmIdKey !in AlarmExtraNames.ALL) {
            "alarmIdKey '$alarmIdKey' is a name this module uses for its own extras."
        }
        require(alarmTypeKey !in AlarmExtraNames.ALL) {
            "alarmTypeKey '$alarmTypeKey' is a name this module uses for its own extras."
        }
        require(alarmIntentScheme == null || URI_SCHEME.matches(alarmIntentScheme)) {
            "alarmIntentScheme '$alarmIntentScheme' is not a valid URI scheme."
        }
    }

    public companion object {

        /** Default for [alarmIdKey]. */
        public const val DEFAULT_ALARM_ID_KEY: String = "alarm_id"

        /** Default for [alarmTypeKey]. */
        public const val DEFAULT_ALARM_TYPE_KEY: String = "alarm_type"

        private val URI_SCHEME = Regex("[a-zA-Z][a-zA-Z0-9+.\\-]*")
    }
}

/**
 * The scheme to actually use on Android: the configured one, or `<applicationId>.alarm` with any
 * character a URI scheme cannot hold replaced by `-`.
 *
 * Sanitizing matters because an application id may legally contain `_`, which a URI scheme may
 * not; `Uri.Builder` would accept it silently and produce a URI that two different alarms could
 * end up sharing.
 */
internal fun AlarmSchedulerConfig.resolveIntentScheme(applicationId: String): String =
    alarmIntentScheme ?: (sanitizeScheme(applicationId) + ".alarm")

private fun sanitizeScheme(raw: String): String {
    val mapped: String = raw.map { char ->
        if (char.isLetterOrDigit() && char.code < ASCII_LIMIT || char == '.' || char == '-' || char == '+') {
            char
        } else {
            '-'
        }
    }.joinToString(separator = "")
    val firstIsLetter: Boolean = mapped.firstOrNull()?.let { it.isLetter() && it.code < ASCII_LIMIT } == true
    return if (firstIsLetter) mapped else "a$mapped"
}

private const val ASCII_LIMIT = 128

/** The extra names [AlarmSchedulerConfig.alarmIdKey] and [AlarmSchedulerConfig.alarmTypeKey] must not take. */
internal object AlarmExtraNames {
    const val FIRE_AT: String = "kmptoolkit_alarm_fire_at"
    const val TITLE: String = "kmptoolkit_alarm_title"
    const val BODY: String = "kmptoolkit_alarm_body"
    const val CHANNEL: String = "kmptoolkit_alarm_channel"
    const val PAYLOAD: String = "kmptoolkit_alarm_payload"

    val ALL: Set<String> = setOf(FIRE_AT, TITLE, BODY, CHANNEL, PAYLOAD)
}
