package io.github.jamal_wia.kmptoolkit.notification

/**
 * Posts and removes **local** notifications on the platform's own notification surface —
 * `NotificationManagerCompat` on Android, `UNUserNotificationCenter` on iOS.
 *
 * This is the only type your shared code should depend on. The concrete instance is built in
 * platform code — `createNotifier(context, permissionHandler)` on Android,
 * `createNotifier(permissionHandler)` on iOS — because the two platforms need different things to
 * construct it (see `docs/01-architecture.md`).
 *
 * **Contract:**
 * - **Nothing throws for a runtime condition.** A missing permission, notifications switched off,
 *   a channel the user blocked, a small icon that does not resolve — all of it comes back as a
 *   [NotificationResult]. A notification is usually decorative; it must not take down the code that
 *   asked for it. The one exception is a **blank `id`**, which is a programming error rather than a
 *   runtime condition and throws [IllegalArgumentException] — see [post].
 * - **[post] is suspending because checking whether you may post is asynchronous on iOS.** On
 *   Android it does not suspend in practice.
 * - **[post] identifies a notification by [String] id, and re-posting the same id replaces it in
 *   place.** That is how a progress notification updates itself without stacking copies.
 * - **Rapid determinate-progress re-posts are coalesced**, so a per-percent loop does not push a
 *   hundred updates at the platform and get rate-limited. A suppressed post returns
 *   [NotificationResult.Coalesced] — see [NotificationConfig] for the two knobs, and
 *   `docs/kmptoolkit-notification/03-guide.md` for why the terminal frame is never suppressed.
 * - **[cancel] of an id that is not showing is a no-op**, and so is [cancelAll] with nothing to
 *   clear. Neither reports anything back, because neither platform tells you whether it removed
 *   anything.
 * - **Safe to call from any thread**, including concurrently for different ids.
 * - **It schedules nothing.** Everything posted here appears immediately. For "show this at 08:00
 *   tomorrow", see [`kmptoolkit-scheduler`](https://github.com/jamal-wia/KMPToolkit).
 *
 * Implement it yourself when you want a decorator — a wrapper that drops notifications while the
 * app is in the foreground is a handful of lines, and [noOpNotifier] covers "turn them off".
 */
public interface Notifier {

    /**
     * Posts [notification] under [id], replacing whatever is currently showing under that id.
     *
     * @param id your own stable identifier. It is the replace key, the cancel key, and the seed for
     *   the platform's own integer id on Android; it is never shown to the user. Must not be blank.
     * @return what the platform made of the request; see [NotificationResult]. Safe to ignore, and
     *   worth logging.
     * @throws IllegalArgumentException if [id] is blank. This is the one thing [post] throws for,
     *   and it is deliberate: a blank id is a bug in the calling code, not a state of the device.
     *   Left unchecked the two platforms disagree about it in the worst possible way — Android
     *   quietly folds it onto a shared integer id, while `UNNotificationRequest` rejects it with an
     *   Objective-C exception that Kotlin/Native cannot catch, killing the process. Every other
     *   identifier in this module ([NotificationChannelSpec.id], [NotificationAction.id]) is
     *   validated at construction; this one has no constructor to validate it in.
     */
    public suspend fun post(id: String, notification: LocalNotification): NotificationResult

    /**
     * Removes the notification showing under [id], and forgets its progress-coalescing state so a
     * later run under the same id starts fresh.
     *
     * A no-op when nothing is showing under [id] — including an id this notifier never posted.
     */
    public fun cancel(id: String)

    /**
     * Removes **every** notification this app is showing, not only the ones this instance posted.
     *
     * That is deliberate: on logout you want a clean tray, including a notification left by a
     * previous process. Scheduled-but-not-yet-delivered notifications are not touched — see
     * `docs/kmptoolkit-notification/05-platform-notes.md`.
     */
    public fun cancelAll()
}

/**
 * A [Notifier] that posts nothing and reports [NotificationResult.NotificationsDisabled] for every
 * call.
 *
 * Use it as the instance you inject when the user has turned notifications off in your own
 * settings, or on a target where you have not wired a real implementation — call sites in shared
 * code then stay unconditional instead of growing a null check each.
 *
 * The returned instance is stateless, so calling this repeatedly costs nothing.
 */
public fun noOpNotifier(): Notifier = NoOpNotifier

/**
 * The one validation every [Notifier] implementation performs, so that a blank id fails the same
 * way everywhere instead of only on the platform that happens to notice.
 *
 * It lives here rather than in each implementation because the failure it prevents is
 * platform-dependent and asymmetric: silently sharing an integer id on Android, an uncatchable
 * Objective-C exception on iOS.
 */
internal fun requireValidNotificationId(id: String) {
    require(id.isNotBlank()) {
        "Notification id must not be blank; it is the replace and cancel key for this notification."
    }
}

private object NoOpNotifier : Notifier {

    override suspend fun post(id: String, notification: LocalNotification): NotificationResult {
        // Validated here too: a blank id must not slip through unnoticed in exactly the
        // configuration where nothing is posted anyway, only to crash once notifications are on.
        requireValidNotificationId(id)
        return NotificationResult.NotificationsDisabled
    }

    override fun cancel(id: String): Unit = Unit

    override fun cancelAll(): Unit = Unit
}
