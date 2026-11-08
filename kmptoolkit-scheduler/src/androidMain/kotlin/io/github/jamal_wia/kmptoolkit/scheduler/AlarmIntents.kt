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

    private const val EXTRA_FIRE_AT = AlarmExtraNames.FIRE_AT
    private const val EXTRA_TITLE = AlarmExtraNames.TITLE
    private const val EXTRA_BODY = AlarmExtraNames.BODY
    private const val EXTRA_CHANNEL = AlarmExtraNames.CHANNEL
    private const val EXTRA_PAYLOAD = AlarmExtraNames.PAYLOAD

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

    /**
     * Like [cancelIntent], but for the data URI 3.0.x wrote: plain `appendPath`, which turns every
     * unpaired surrogate into `?`. `null` for an id without one (its URI never changed, so slot 0 of
     * [cancelIntent] already finds the old alarm). Alarms armed by 3.0.x for an id with an unpaired
     * surrogate used the lossy `appendPath` URI. Drop in 4.0.0.
     *
     * The request code of that alarm was `id.hashCode()`, which is [requestCode] of slot 0.
     */
    fun legacyCancelIntent(context: Context, id: String, keys: AlarmIntentKeys): Intent? {
        if (!id.hasUnpairedSurrogate()) return null
        return Intent(context, AlarmReceiver::class.java).apply {
            data = Uri.Builder().scheme(keys.intentScheme).appendPath(id).build()
        }
    }

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
    fun requestCode(id: String): Int = requestCode(id, slot = 0)

    /**
     * How many `PendingIntent` slots an id owns. One slot holds the armed alarm; the others are what
     * a replacement can be built in without touching it, and what a retirement the platform refused
     * leaves occupied. More than two, so a single refused retirement (a restarting system server)
     * does not take away the free slot the next replacement needs.
     */
    const val SLOT_COUNT: Int = 4

    /**
     * Request code of one of an alarm's [SLOT_COUNT] `PendingIntent` slots. A replacement is armed in
     * a slot no alarm occupies, so a replacement the platform refuses has not touched an alarm that
     * stays armed; slot `0` is the code every earlier version used, so an alarm armed before an
     * update is found there.
     */
    fun requestCode(id: String, slot: Int): Int = id.hashCode() + slot

    private fun alarmUri(id: String, scheme: String): Uri =
        Uri.Builder().scheme(scheme).appendLosslessPath(id).build()

    /**
     * `Uri.Builder.appendPath`, except that it cannot merge two different ids into one path.
     * `Uri.encode` writes UTF-8, which has no form for an unpaired surrogate, so it turns each into
     * `?` and two ids that differ only in such characters (equal hash codes included) would be one
     * `PendingIntent`. An id without one is appended exactly as `appendPath` would (alarms armed by
     * an earlier version stay found); an id with one gets each unpaired surrogate as `%u` and its four
     * hex digits, a form no `Uri.encode` output contains because `%` is always followed by two hex
     * digits there.
     */
    private fun Uri.Builder.appendLosslessPath(segment: String): Uri.Builder {
        if (!segment.hasUnpairedSurrogate()) return appendPath(segment)
        val encoded = StringBuilder()
        var index = 0
        while (index < segment.length) {
            val char: Char = segment[index]
            val paired: Boolean = char.isHighSurrogate() && index + 1 < segment.length && segment[index + 1].isLowSurrogate()
            if (paired) {
                encoded.append(Uri.encode(segment.substring(index, index + 2)))
                index += 2
            } else if (char.isSurrogate()) {
                encoded.append("%u").append(char.code.toString(HEX_RADIX).padStart(HEX_DIGITS, '0').uppercase())
                index += 1
            } else {
                encoded.append(Uri.encode(char.toString()))
                index += 1
            }
        }
        return appendEncodedPath(encoded.toString())
    }

    private fun String.hasUnpairedSurrogate(): Boolean {
        var index = 0
        while (index < length) {
            val char: Char = this[index]
            when {
                char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> index += 2
                char.isSurrogate() -> return true
                else -> index += 1
            }
        }
        return false
    }

    private const val HEX_RADIX: Int = 16
    private const val HEX_DIGITS: Int = 4

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
