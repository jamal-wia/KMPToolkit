package io.github.jamal_wia.kmptoolkit.scheduler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Translates a [ScheduledAlarm] to and from the broadcast [Intent] that carries it to
 * [AlarmReceiver], and back again at fire time.
 *
 * Kept apart from both the scheduler and the receiver because it is the one piece of the Android
 * side that has an exact, checkable contract — a round trip must preserve the alarm — while
 * everything around it is `AlarmManager` behavior.
 */
internal object AlarmIntents {

    private const val EXTRA_FIRE_AT = "kmptoolkit_alarm_fire_at"
    private const val EXTRA_TITLE = "kmptoolkit_alarm_title"
    private const val EXTRA_BODY = "kmptoolkit_alarm_body"
    private const val EXTRA_CHANNEL = "kmptoolkit_alarm_channel"
    private const val EXTRA_PAYLOAD = "kmptoolkit_alarm_payload"

    /**
     * The explicit-component broadcast intent carrying the whole alarm.
     *
     * The per-id data URI is what makes two alarms distinct: `PendingIntent` equality compares an
     * intent's action, data, type, component, and categories — but **not** its extras — so without
     * it two alarms whose request codes collide would collapse into one.
     */
    fun toIntent(context: Context, alarm: ScheduledAlarm, keys: AlarmIntentKeys): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            data = alarmUri(alarm.id, keys.intentScheme)
            putExtra(keys.idKey, alarm.id)
            putExtra(keys.typeKey, alarm.type)
            putExtra(EXTRA_FIRE_AT, alarm.fireAtEpochMillis)
            putExtra(EXTRA_TITLE, alarm.notification.title)
            putExtra(EXTRA_BODY, alarm.notification.body)
            putExtra(EXTRA_CHANNEL, alarm.notification.channelId)
            putExtra(EXTRA_PAYLOAD, alarm.payload.toBundle())
        }

    /**
     * An extras-free intent to the same component carrying only the per-id data URI — everything
     * `PendingIntent` matching looks at, and nothing more, so it resolves the pending intent armed
     * by [toIntent] for the same id.
     */
    fun cancelIntent(context: Context, id: String, keys: AlarmIntentKeys): Intent =
        Intent(context, AlarmReceiver::class.java).apply { data = alarmUri(id, keys.intentScheme) }

    /** Rebuilds the alarm at fire time, or `null` if the intent is not one of ours. */
    fun fromIntent(intent: Intent, keys: AlarmIntentKeys): ScheduledAlarm? {
        val id: String = intent.getStringExtra(keys.idKey)?.takeIf { it.isNotBlank() } ?: return null
        val type: String = intent.getStringExtra(keys.typeKey)?.takeIf { it.isNotBlank() } ?: return null
        return ScheduledAlarm(
            id = id,
            type = type,
            fireAtEpochMillis = intent.getLongExtra(EXTRA_FIRE_AT, 0L),
            notification = AlarmNotification(
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
                channelId = intent.getStringExtra(EXTRA_CHANNEL).orEmpty(),
            ),
            payload = intent.getBundleExtra(EXTRA_PAYLOAD).toStringMap(),
        )
    }

    /**
     * Request code for the alarm's `PendingIntent`. A hash collision between two ids is harmless —
     * distinctness is carried by the data URI above — so any stable `Int` derived from the id will
     * do.
     */
    fun requestCode(id: String): Int = id.hashCode()

    private fun alarmUri(id: String, scheme: String): Uri =
        Uri.Builder().scheme(scheme).appendPath(id).build()

    /** Intent extras carry no `Map`, but they do carry a `Bundle`. */
    private fun Map<String, String>.toBundle(): Bundle = Bundle().also { bundle ->
        forEach { (key, value) -> bundle.putString(key, value) }
    }

    /** Inverse of [toBundle]; a non-string entry contributes nothing rather than failing the alarm. */
    private fun Bundle?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keySet().mapNotNull { key -> getString(key)?.let { key to it } }.toMap()
    }
}

/**
 * The consumer-configurable identifiers, resolved once against the application id so neither the
 * scheduler nor the receiver has to re-derive them.
 */
internal data class AlarmIntentKeys(
    val intentScheme: String,
    val idKey: String,
    val typeKey: String,
) {
    companion object {
        fun from(config: AlarmSchedulerConfig, applicationId: String): AlarmIntentKeys = AlarmIntentKeys(
            intentScheme = config.resolveIntentScheme(applicationId),
            idKey = config.alarmIdKey,
            typeKey = config.alarmTypeKey,
        )
    }
}
