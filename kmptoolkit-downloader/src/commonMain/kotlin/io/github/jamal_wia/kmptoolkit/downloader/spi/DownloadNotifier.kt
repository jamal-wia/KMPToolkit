package io.github.jamal_wia.kmptoolkit.downloader.spi

import io.github.jamal_wia.kmptoolkit.downloader.DownloadError
import io.github.jamal_wia.kmptoolkit.downloader.ResourceGroup

/**
 * How a download tells the user it is happening. The engine decides **when** something is worth
 * showing; everything about **what it looks like** — the title, the wording of an error, the
 * notification channel, whether permission has been granted to post at all — belongs to the host.
 *
 * This port is why the library carries no string resources and no localization: a [ResourceGroup]
 * arrives here and the host names it in the user's language. It is also why [DownloadError] is this
 * library's own small taxonomy — the host maps it onto its own copy.
 *
 * Every method must tolerate being called when notifications are disabled or permission was
 * refused; a notifier that cannot post should do nothing rather than throw, since a failed
 * notification must never fail the download it was describing.
 */
public interface DownloadNotifier {

    /**
     * Reports [progress] (0..1) for [group], with a cancel affordance. The host is expected to
     * update in place rather than post a new notification per call — this is invoked often.
     */
    public suspend fun showProgress(group: ResourceGroup, progress: Float)

    /** Reports that [group] finished downloading. */
    public suspend fun showCompleted(group: ResourceGroup)

    /** Reports that [group] failed with [error], with a retry affordance. */
    public suspend fun showError(group: ResourceGroup, error: DownloadError)

    /** Withdraws any notification for [group] — the download was cancelled or superseded. */
    public fun remove(group: ResourceGroup)

    public companion object {
        /** Does nothing. The default for a consumer with no user-facing download surface to post to. */
        public val NoOp: DownloadNotifier = object : DownloadNotifier {
            override suspend fun showProgress(group: ResourceGroup, progress: Float): Unit = Unit
            override suspend fun showCompleted(group: ResourceGroup): Unit = Unit
            override suspend fun showError(group: ResourceGroup, error: DownloadError): Unit = Unit
            override fun remove(group: ResourceGroup): Unit = Unit
        }
    }
}
