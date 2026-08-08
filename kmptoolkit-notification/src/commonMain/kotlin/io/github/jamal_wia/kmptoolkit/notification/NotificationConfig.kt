package io.github.jamal_wia.kmptoolkit.notification

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The identifiers this module writes into platform artifacts on your behalf, and the two numbers
 * that decide how often a progress notification is allowed to reach the platform.
 *
 * Nothing here is hardcoded to a name this library invented. The one identifier that leaves the
 * process — the Android broadcast action for a tapped button — defaults to a value derived from
 * **your** application id, so two apps built on this library can never receive each other's button
 * taps. See `docs/01-architecture.md`.
 *
 * @param actionBroadcastAction **Android only.** The broadcast action fired when a notification
 *   button is tapped. `null` (the default) derives it as
 *   `<applicationId>.KMPTOOLKIT_NOTIFICATION_ACTION`; the manifest form of that same string is
 *   `${applicationId}.KMPTOOLKIT_NOTIFICATION_ACTION`, which is what your `<receiver>` declares.
 *   Set it explicitly only if you already have a receiver on an action of your own. Must not be
 *   blank when non-null.
 * @param progressBucketPercent the width, in percent, of a progress bucket. A determinate update
 *   whose percentage falls in the same bucket as the last one actually posted for that id is
 *   suppressed, so a per-byte loop turns into at most `100 / progressBucketPercent + 1` posts.
 *   `1` disables bucketing (every distinct percent may post, subject to [minProgressInterval]).
 *   Must be in `1..100`.
 * @param minProgressInterval the shortest time between two posts for the same id while its progress
 *   is determinate. Bucketing alone does not bound the *rate*: a fast download crosses ten buckets
 *   in a second, and both platforms rate-limit an app that posts that often — Android starts
 *   dropping notifications outright. `Duration.ZERO` disables the throttle. Must not be negative.
 * @throws IllegalArgumentException if any parameter is outside the range described above. Failing
 *   at construction beats discovering months later that a progress bar moves in one jump.
 */
public data class NotificationConfig(
    public val actionBroadcastAction: String? = null,
    public val progressBucketPercent: Int = DEFAULT_PROGRESS_BUCKET_PERCENT,
    public val minProgressInterval: Duration = DEFAULT_MIN_PROGRESS_INTERVAL,
) {
    init {
        require(actionBroadcastAction == null || actionBroadcastAction.isNotBlank()) {
            "actionBroadcastAction must not be blank; pass null to derive it from the application id."
        }
        require(progressBucketPercent in 1..MAX_PERCENT) {
            "progressBucketPercent must be in 1..$MAX_PERCENT, was $progressBucketPercent."
        }
        require(!minProgressInterval.isNegative()) {
            "minProgressInterval must not be negative, was $minProgressInterval."
        }
    }

    public companion object {

        /** Default for [progressBucketPercent]: at most eleven posts over a full 0..100 run. */
        public const val DEFAULT_PROGRESS_BUCKET_PERCENT: Int = 10

        /**
         * Default for [minProgressInterval]. Two updates a second is well under the rate at which
         * either platform starts dropping notifications, and still looks live to a user.
         */
        public val DEFAULT_MIN_PROGRESS_INTERVAL: Duration = 500.milliseconds

        internal const val MAX_PERCENT: Int = 100

        /** Suffix appended to the application id when [actionBroadcastAction] is `null`. */
        internal const val ACTION_SUFFIX: String = ".KMPTOOLKIT_NOTIFICATION_ACTION"
    }
}

/**
 * The broadcast action to actually use: the configured one, or `<applicationId>` plus
 * [NotificationConfig.ACTION_SUFFIX].
 */
internal fun NotificationConfig.resolveBroadcastAction(applicationId: String): String =
    actionBroadcastAction ?: (applicationId + NotificationConfig.ACTION_SUFFIX)
