package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Tuning for the engine behind [createDownloader]. Every value has a default that is right for a
 * transfer whose [BackgroundResourceDownloader] reports progress regularly, so a host that does not
 * pass a config gets exactly the behaviour of a [DownloaderConfig] built with no arguments.
 *
 * A plain class rather than a data class, so a setting can be added in a later release without
 * changing a constructor or a `copy` signature that consumers already compiled against. Equality
 * is by value.
 *
 * @property stallTimeout how long the engine waits without receiving any [BackgroundDownloadEvent]
 *   for a transfer before it declares the attempt stalled: it cancels the transfer and handles the
 *   stall as a [DownloadError.Timeout] failure. Every event restarts the timer, a repeated
 *   [BackgroundDownloadEvent.Progress] included — which is how a downloader keeps a transfer the OS
 *   is legitimately holding (waiting for connectivity, for Wi-Fi) alive without that repeat counting
 *   as progress. [Duration.INFINITE] turns stall detection off and leaves it entirely to the
 *   transport; on iOS the only bound left is then the background session's
 *   `timeoutIntervalForResource`, 7 days by default. Must be positive. Defaults to
 *   [DEFAULT_STALL_TIMEOUT].
 * @since 3.1.0
 */
public class DownloaderConfig(
    public val stallTimeout: Duration = DEFAULT_STALL_TIMEOUT,
) {
    init {
        require(stallTimeout > Duration.ZERO) {
            "stallTimeout must be positive (Duration.INFINITE disables stall detection), was $stallTimeout"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloaderConfig) return false
        return stallTimeout == other.stallTimeout
    }

    override fun hashCode(): Int = stallTimeout.hashCode()

    override fun toString(): String = "DownloaderConfig(stallTimeout=$stallTimeout)"

    public companion object {
        /**
         * The stall timeout [createDownloader] uses when it is given no [DownloaderConfig]: 5
         * minutes — generous, since even at 1 KB/s with 8 KB chunks a progress event arrives every
         * few seconds.
         *
         * @since 3.1.0
         */
        public val DEFAULT_STALL_TIMEOUT: Duration = 5.minutes
    }
}
