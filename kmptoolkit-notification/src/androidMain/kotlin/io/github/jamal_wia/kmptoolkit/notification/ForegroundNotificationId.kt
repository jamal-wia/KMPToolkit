package io.github.jamal_wia.kmptoolkit.notification

/**
 * Binds one of your [Notifier] ids to the `Int` a foreground service shows its notification under.
 *
 * Android identifies an ordinary notification by its `(tag, id)` pair, and [Notifier] uses your
 * [String] id as the tag, so two ids can never land on one notification. `Service.startForeground`
 * is the exception: it takes a bare `Int` and no tag. A notification that a foreground service
 * starts with and that [Notifier.post] later updates in place therefore needs an `Int` of its own —
 * yours to choose, because only the app knows which numbers its other code already uses:
 *
 * ```kotlin
 * val PLAYBACK = ForegroundNotificationId(id = "playback", platformId = 1001)
 *
 * // In the service:
 * startForeground(PLAYBACK.platformId, buildForegroundNotification(this, PLAYBACK, notification, config))
 *
 * // Wherever the notifier is built — post("playback", …) and cancel("playback") now address the
 * // service's notification:
 * val notifier = createNotifier(context, permissions, config, foregroundIds = setOf(PLAYBACK))
 * ```
 *
 * Android only: iOS has no foreground services, and its notifications are identified by your
 * [String] id alone.
 *
 * @property id the [Notifier] id this binding is for. Must not be blank, as for [Notifier.post].
 * @property platformId the `Int` passed to `startForeground`. Must not be 0, which `startForeground`
 *   rejects. It shares a number space only with other untagged notifications — those your app posts
 *   through `NotificationManager` itself, and other [ForegroundNotificationId]s — never with what
 *   [Notifier] posts under a tag.
 * @since 3.0.0
 */
public data class ForegroundNotificationId(
    public val id: String,
    public val platformId: Int,
) {
    init {
        requireValidNotificationId(id)
        require(platformId != 0) { "platformId must not be 0; startForeground rejects it." }
    }
}
