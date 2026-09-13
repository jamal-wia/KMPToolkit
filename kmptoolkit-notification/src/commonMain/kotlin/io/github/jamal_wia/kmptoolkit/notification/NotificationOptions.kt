package io.github.jamal_wia.kmptoolkit.notification

/**
 * How one post is presented, beyond what the [LocalNotification] itself describes.
 *
 * Everything here is **Android behavior**; iOS has no counterpart to any of it and ignores the
 * options entirely. They live apart from [LocalNotification] because they describe how a posting
 * behaves on a platform rather than what the notification says — and so that adding one never
 * changes the shape of a type every caller already constructs.
 *
 * Pass it to [Notifier.post]'s three-argument overload. [DEFAULT] is what the two-argument overload
 * uses.
 *
 * @property alertOnce whether re-posting an id that is still showing updates it **silently**. `true`
 *   (the default) keeps a progress notification from sounding on every step. Set it to `false` for a
 *   repeating reminder under one id: with `true`, a reminder the user left in the shade would swallow
 *   the next one without a sound or a heads-up.
 * @property dismissAction a [NotificationAction] whose broadcast is sent when the user **swipes the
 *   notification away** — exactly the broadcast a button with that action would send, so one
 *   receiver handles both. The action is not drawn as a button unless it is also in
 *   [LocalNotification.actions]. Typical use: swiping away a notification for a sound that is playing
 *   stops the sound.
 * @property mediaStyle draws the notification with the media layout, whose point here is its
 *   collapsed row: the first of [LocalNotification.actions] is shown without expanding the
 *   notification. Has no effect on a notification without actions. It publishes no media session.
 * @property actionIcons the icon drawn for each button, keyed by [NotificationAction.id]. A button
 *   with no entry gets no icon, which is fine on a phone's expanded notification — Android 7+ does not
 *   draw action icons there — but leaves a blank button in [mediaStyle]'s collapsed row and on a
 *   watch. Only [NotificationIcon.AndroidDrawable] draws anything; [NotificationIcon.Default] means no
 *   icon.
 */
public class NotificationOptions(
    public val alertOnce: Boolean = true,
    public val dismissAction: NotificationAction? = null,
    public val mediaStyle: Boolean = false,
    public val actionIcons: Map<String, NotificationIcon> = emptyMap(),
) {

    override fun equals(other: Any?): Boolean = other is NotificationOptions &&
        alertOnce == other.alertOnce &&
        dismissAction == other.dismissAction &&
        mediaStyle == other.mediaStyle &&
        actionIcons == other.actionIcons

    override fun hashCode(): Int {
        var result: Int = alertOnce.hashCode()
        result = HASH_MULTIPLIER * result + (dismissAction?.hashCode() ?: 0)
        result = HASH_MULTIPLIER * result + mediaStyle.hashCode()
        result = HASH_MULTIPLIER * result + actionIcons.hashCode()
        return result
    }

    override fun toString(): String =
        "NotificationOptions(alertOnce=$alertOnce, dismissAction=$dismissAction, " +
            "mediaStyle=$mediaStyle, actionIcons=$actionIcons)"

    public companion object {

        /** The options [Notifier.post] applies when none are passed. */
        public val DEFAULT: NotificationOptions = NotificationOptions()

        private const val HASH_MULTIPLIER: Int = 31
    }
}
