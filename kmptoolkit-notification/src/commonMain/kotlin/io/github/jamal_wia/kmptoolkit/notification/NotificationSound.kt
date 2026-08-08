package io.github.jamal_wia.kmptoolkit.notification

/**
 * What a notification plays.
 *
 * This module only *names* a sound; the audio file itself is a platform resource you ship and each
 * notifier resolves — `res/raw/<name>` on Android, a file in the app bundle on iOS. Keeping it a
 * plain name is what lets [NotificationChannelSpec] stay in `commonMain` without carrying a
 * platform resource handle.
 *
 * On Android 8+ the sound belongs to the channel and is fixed when the channel is created, which is
 * why it lives on [NotificationChannelSpec] rather than on [LocalNotification]. On iOS it is
 * genuinely per notification, and this module applies it per notification there — the same field,
 * honestly different mechanics.
 */
public sealed interface NotificationSound {

    /** No sound. Also what a [NotificationImportance.Low] channel effectively gives you anyway. */
    public data object Silent : NotificationSound

    /** The platform's default notification sound. */
    public data object Default : NotificationSound

    /**
     * A sound you bundled, named by its platform resource.
     *
     * @property resourceName on Android, the `res/raw` resource name **without** an extension
     *   (`"chat_message"` for `res/raw/chat_message.wav`); on iOS, the filename **with** its
     *   extension as it sits in the bundle (`"chat_message.caf"`). The two platforms name the same
     *   asset differently and neither accepts the other's form, so a cross-platform caller usually
     *   builds this value per platform. Must not be blank.
     * @throws IllegalArgumentException if [resourceName] is blank — a blank name resolves to a
     *   silent notification on Android and to the default sound on iOS, which is the worst kind of
     *   divergence to debug.
     */
    public data class Custom(public val resourceName: String) : NotificationSound {
        init {
            require(resourceName.isNotBlank()) { "Custom sound resourceName must not be blank." }
        }
    }
}
