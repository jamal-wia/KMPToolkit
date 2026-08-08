package io.github.jamal_wia.kmptoolkit.notification

/**
 * A button on a notification — and, more importantly, one half of a seam this library cannot close
 * on its own.
 *
 * A tapped button has to reach code that still exists after the app process was killed, and both
 * platforms solve that with something only the **consuming app** can declare:
 *
 * - **Android** needs a `PendingIntent`. This module builds one that broadcasts
 *   [NotificationConfig.actionBroadcastAction] carrying [id]; your app declares a manifest
 *   `<receiver>` for that action and routes it. A receiver registered in code would not be there
 *   after a process death, which is precisely when a button gets tapped.
 * - **iOS** needs a `UNNotificationCategory`, registered up front with
 *   `UNUserNotificationCenter.setNotificationCategories`, and that call replaces the app's *entire*
 *   category set. A library that called it would silently delete categories registered by your own
 *   code or by another SDK, so this module never calls it. You register your categories, and name
 *   the one to use in [LocalNotification.iosCategoryId].
 *
 * So this type renders buttons on Android only, and iOS buttons come from your category. Give the
 * category's `UNNotificationAction` identifiers the same values as [id] and your handling code
 * converges on one `when` for both platforms — that is the intended shape, and
 * `docs/kmptoolkit-notification/03-guide.md#action-buttons` walks through it end to end.
 *
 * The module never interprets [id]. What "cancel-download-42" means is your app's business; this
 * layer only carries it there and back.
 *
 * @property id opaque identifier handed back to you when the button is tapped. Must not be blank.
 * @property label already-localized button text.
 * @throws IllegalArgumentException if [id] is blank — a blank id arrives at your receiver
 *   indistinguishable from a missing extra.
 */
public data class NotificationAction(
    public val id: String,
    public val label: String,
) {
    init {
        require(id.isNotBlank()) { "Action id must not be blank." }
    }
}
