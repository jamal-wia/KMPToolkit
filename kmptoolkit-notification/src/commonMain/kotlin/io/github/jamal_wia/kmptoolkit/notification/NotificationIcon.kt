package io.github.jamal_wia.kmptoolkit.notification

/**
 * The small icon Android draws in the status bar and on the notification itself.
 *
 * **Android-only, and named so you can see that from common code.** iOS has no equivalent: it
 * always shows the app icon, and there is nothing an app can do about it. Rather than invent a
 * neutral-sounding abstraction that one platform ignores entirely, this type says which platform it
 * is for.
 *
 * Android *requires* a small icon — a notification without one is rejected by the framework — which
 * is why [Default] exists and why it is the default value on [LocalNotification.icon]: a caller who
 * has not yet drawn an icon still gets a notification rather than a
 * [NotificationResult.Failed].
 */
public sealed interface NotificationIcon {

    /**
     * A neutral platform-provided icon, so that a notification always has a valid one.
     *
     * It is a stock Android drawable, not artwork of this library's — but it is also not *your*
     * artwork, and every app should replace it with [AndroidDrawable] before shipping.
     */
    public data object Default : NotificationIcon

    /**
     * Your own drawable, by resource id — `R.drawable.ic_notification`.
     *
     * A compile-time id rather than a name keeps this reflection-free and verifiable by R8. It must
     * be a drawable that works as a notification icon: Android 5+ renders it as a silhouette from
     * its alpha channel, so a full-colour bitmap comes out as a white blob. An id that does not
     * resolve at post time comes back as [NotificationResult.Failed] rather than as a crash.
     *
     * @property resourceId an Android drawable resource id.
     */
    public data class AndroidDrawable(public val resourceId: Int) : NotificationIcon
}
