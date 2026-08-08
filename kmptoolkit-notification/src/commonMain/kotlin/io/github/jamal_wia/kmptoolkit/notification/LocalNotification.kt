package io.github.jamal_wia.kmptoolkit.notification

/**
 * One notification to show, described in platform-neutral terms.
 *
 * Every user-visible string here — [title], [body], [NotificationChannelSpec.name],
 * [NotificationAction.label] — is **already localized by you**. This module resolves no string
 * resources and ships no copy of its own (see `docs/01-architecture.md`), so what you pass is
 * exactly what the OS renders.
 *
 * "Local" is the operative word: this is something your app decides to show. A push notification
 * arriving from FCM or APNs is a different mechanism with a different delivery path, and is out of
 * this module's scope — see `docs/kmptoolkit-notification/01-overview.md`.
 *
 * @property title the first line.
 * @property body the second line. Long text is truncated by the platform until the notification is
 *   expanded; neither platform is told to expand it here.
 * @property channel where to post it. On Android this is a real, user-visible channel; on iOS only
 *   parts of it apply — [NotificationChannelSpec] spells out which.
 * @property icon the Android small icon. iOS ignores it entirely and always shows the app icon.
 * @property progress a progress bar, or `null` for an ordinary notification. Android draws it;
 *   iOS has no progress bar, so bake the percentage into [body] if it must be visible there. Either
 *   way it drives coalescing — see [NotificationProgress].
 * @property ongoing when `true`, the user cannot swipe the notification away. Use it for work
 *   actually in flight; a stuck ongoing notification is a support ticket. Android only.
 * @property autoCancel when `true`, tapping the notification dismisses it. Android only; iOS always
 *   dismisses on tap.
 * @property actions buttons to render. **Android only** — on iOS buttons come from a category you
 *   registered yourself, named by [iosCategoryId]. See [NotificationAction] for the whole seam.
 * @property iosCategoryId identifier of a `UNNotificationCategory` you registered with
 *   `UNUserNotificationCenter` at app start; the OS renders that category's buttons. `null` means
 *   no buttons on iOS. Ignored on Android.
 * @property contentExtras what tapping the notification should carry into your app. `null` means
 *   the notification is not tappable-to-open (the right choice for a progress notification);
 *   non-null opens your launcher activity with these values as intent extras, and an empty map just
 *   opens the app. **Android only** — on iOS a tap goes to your `UNUserNotificationCenterDelegate`,
 *   which reads `userInfo` you would have set on the request yourself.
 */
public data class LocalNotification(
    public val title: String,
    public val body: String,
    public val channel: NotificationChannelSpec,
    public val icon: NotificationIcon = NotificationIcon.Default,
    public val progress: NotificationProgress? = null,
    public val ongoing: Boolean = false,
    public val autoCancel: Boolean = true,
    public val actions: List<NotificationAction> = emptyList(),
    public val iosCategoryId: String? = null,
    public val contentExtras: Map<String, String>? = null,
)

/**
 * The progress bar on a [LocalNotification], and the input to the coalescing rule.
 *
 * `null` progress (the property's default) means "no progress bar at all", and is also what a
 * terminal frame should carry: a finished download is not 100% in progress, it is done. That is not
 * only cosmetic — see the coalescing rules in
 * `docs/kmptoolkit-notification/03-guide.md#progress-notifications`.
 */
public sealed interface NotificationProgress {

    /**
     * A bar with no known completion fraction — a spinner.
     *
     * Never coalesced: it carries no percentage, so there is nothing to compare against.
     */
    public data object Indeterminate : NotificationProgress

    /**
     * A bar filled to [percent] out of 100.
     *
     * Values outside `0..100` are clamped when rendering, and clamped before bucketing, so a
     * caller that computes `bytes * 100 / total` and lands on 101 does not get a surprise.
     *
     * @property percent completion, `0..100`.
     */
    public data class Determinate(public val percent: Int) : NotificationProgress
}
