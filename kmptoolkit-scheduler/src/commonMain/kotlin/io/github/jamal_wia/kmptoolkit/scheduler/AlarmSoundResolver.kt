package io.github.jamal_wia.kmptoolkit.scheduler

/**
 * Resolves the custom notification sound for an alarm's channel, or `null` for the platform
 * default.
 *
 * An SPI, not a value: your app owns the channel-to-sound vocabulary, so it supplies the mapping
 * and this module stays free of any dependency on a notification or resources layer.
 *
 * Consumed **only by the iOS scheduler**, which must set a sound on each notification at schedule
 * time. On Android the sound belongs to the notification channel, so nothing calls this there.
 *
 * The returned string is passed straight to `UNNotificationSound.soundNamed`, so it is a bundled
 * sound **filename including its extension** — `"reminder.caf"`, not `"reminder"`. A name that
 * does not resolve to a bundled file makes iOS fall back to the default sound.
 *
 * Called on the scheduling thread, synchronously, once per [AlarmScheduler.schedule] call. Keep it
 * a pure lookup.
 */
public fun interface AlarmSoundResolver {

    /**
     * @param channelId the alarm's [AlarmNotification.channelId].
     * @return a bundled sound filename, or `null` for the platform default sound.
     */
    public fun soundFor(channelId: String): String?
}
