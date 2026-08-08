package io.github.jamal_wia.kmptoolkit.notification

import android.content.Context
import android.content.Intent

/**
 * The consumer's half of the action-button seam on Android: what the broadcast from a tapped button
 * looks like, and how to read it.
 *
 * When the user taps a [NotificationAction], this module fires a broadcast with the action from
 * [NotificationConfig.actionBroadcastAction] — by default `<applicationId>` +
 * `.KMPTOOLKIT_NOTIFICATION_ACTION`. Your app declares a **manifest** receiver for it:
 *
 * ```xml
 * <receiver android:name=".NotificationActionReceiver" android:exported="false">
 *     <intent-filter>
 *         <action android:name="${applicationId}.KMPTOOLKIT_NOTIFICATION_ACTION" />
 *     </intent-filter>
 * </receiver>
 * ```
 *
 * ```kotlin
 * class NotificationActionReceiver : BroadcastReceiver() {
 *     override fun onReceive(context: Context, intent: Intent) {
 *         when (NotificationActionIntent.actionId(intent)) {
 *             "cancel" -> cancelDownload(NotificationActionIntent.notificationId(intent))
 *         }
 *     }
 * }
 * ```
 *
 * A **manifest** receiver, not one registered in code, and not a callback held by this library: a
 * notification button is most often tapped when your process is gone, and only the manifest form is
 * still there for the system to deliver to. That is also why the library cannot own the receiver
 * for you — a `BroadcastReceiver` the framework instantiates has no way to be handed your
 * dependencies.
 *
 * The library never interprets either extra.
 */
public object NotificationActionIntent {

    /**
     * Extra carrying the tapped [NotificationAction.id].
     *
     * Fixed rather than derived from the application id, unlike the broadcast action: the extras
     * live inside an intent that is already addressed to one app, so there is nothing here for two
     * apps to collide on.
     */
    public const val EXTRA_ACTION_ID: String =
        "io.github.jamal_wia.kmptoolkit.notification.EXTRA_ACTION_ID"

    /** Extra carrying the id the notification was posted under ([Notifier.post]'s `id`). */
    public const val EXTRA_NOTIFICATION_ID: String =
        "io.github.jamal_wia.kmptoolkit.notification.EXTRA_NOTIFICATION_ID"

    /**
     * The tapped button's [NotificationAction.id], or `null` if [intent] did not come from this
     * module.
     */
    public fun actionId(intent: Intent): String? = intent.getStringExtra(EXTRA_ACTION_ID)

    /**
     * The [Notifier.post] id of the notification the button belonged to, or `null` if [intent] did
     * not come from this module.
     */
    public fun notificationId(intent: Intent): String? =
        intent.getStringExtra(EXTRA_NOTIFICATION_ID)

    /**
     * The broadcast action this module uses when [NotificationConfig.actionBroadcastAction] is left
     * at its default — `<applicationId>.KMPTOOLKIT_NOTIFICATION_ACTION`.
     *
     * Useful for asserting in a test that your manifest's `<intent-filter>` and the notifier agree;
     * the manifest itself must spell the string out with the `${applicationId}` placeholder, since
     * nothing can call a function at manifest-merge time.
     */
    public fun defaultAction(context: Context): String =
        context.applicationContext.packageName + NotificationConfig.ACTION_SUFFIX
}
