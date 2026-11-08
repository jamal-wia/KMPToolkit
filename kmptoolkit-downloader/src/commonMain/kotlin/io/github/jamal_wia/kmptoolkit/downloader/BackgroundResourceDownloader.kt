package io.github.jamal_wia.kmptoolkit.downloader

import kotlinx.coroutines.flow.Flow

/**
 * The platform machinery that actually moves bytes, and keeps moving them after the app is
 * backgrounded or killed — a foreground service on Android, a background `NSURLSession` on iOS.
 * **This is the one port `kmptoolkit-downloader` cannot supply for you** — see
 * `docs/kmptoolkit-downloader/07-background-downloader.md` for a walkthrough of implementing it,
 * and pass your implementation to [io.github.jamal_wia.kmptoolkit.downloader.createDownloader].
 *
 * **No pause/resume.** Only cancel and re-enqueue. On Android, resumption via HTTP `Range` happens
 * whenever a partial temp file survives — an automatic retry, a later `ensureAvailable` after a
 * failure, or process death mid-transfer; on iOS the OS-owned background session is what survives,
 * reconnected by identifier on relaunch, and the temp file appears only on completion. The engine
 * keeps the partial temp file when a transfer fails (a lost connection, a timeout, a server error,
 * a rejected request) and deletes it only on a user cancel, on bytes that fail their integrity
 * check, and when the server says the object is gone (404/410), so only those start from byte zero.
 *
 * The engine owns every retry decision; an implementation reports what happened and stops there.
 *
 * An implementation also owns the temp-file writer, so it calls
 * [DownloaderStorage.beginTempFile] with the hash of the response it is about to write (a
 * [io.github.jamal_wia.kmptoolkit.downloader.spi.ResolvedDownload.expectedSha256]) whenever it
 * starts from byte zero, and never on a `206` resume — see the decision table in the docs.
 */
public interface BackgroundResourceDownloader {

    /**
     * Starts a background download for [unit]. Idempotent: enqueuing a unit already in flight joins
     * the running transfer rather than starting a second one.
     *
     * The engine subscribes to [observeProgress] **before** it calls this, so an event emitted while
     * enqueuing (a download that is already whole, a failure to begin) reaches it even through a
     * flow without replay. Do not throw from here; report a failure as a
     * [BackgroundDownloadEvent.Error] instead.
     */
    public fun enqueueDownload(unit: DownloadUnit)

    /**
     * Progress and terminal events for [unit]. Survives process death — on relaunch an
     * implementation reconnects to the transfer the OS kept running and replays its outcome.
     *
     * Replay only the outcome of the transfer currently known for [unit]. Once [enqueueDownload] is
     * called for a unit, a terminal event of an earlier transfer must not reach a subscriber any
     * more: the engine drops whatever a subscription delivers before it enqueues, because it can
     * only be such a leftover, and an implementation that replays must not deliver the leftover
     * again after the enqueue.
     */
    public fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent>

    /** True when a download for [unit] is enqueued or running, including across a restart. */
    public fun isDownloadInProgress(unit: DownloadUnit): Boolean

    /**
     * Cancels [unit]'s transfer. Does not delete the temp file — the engine decides that. Once this
     * returns, [isDownloadInProgress] is `false` for [unit] and the cancelled transfer's
     * [BackgroundDownloadEvent.Cancelled] is not replayed to a later subscriber: a request made
     * straight after a cancel must find a clean slate.
     */
    public fun cancelDownload(unit: DownloadUnit)
}

/** What a [BackgroundResourceDownloader] reports about one unit's transfer. */
public sealed class BackgroundDownloadEvent {
    public abstract val unit: DownloadUnit

    /** An event that ends a transfer — exactly one of these arrives per attempt. */
    public sealed class Terminal : BackgroundDownloadEvent()

    public data class Progress(
        override val unit: DownloadUnit,
        /**
         * Expected in 0..1. The engine ignores NaN and infinity and clamps any other value into 0..1
         * before it reaches a state flow or the notifier.
         */
        public val fraction: Float,
    ) : BackgroundDownloadEvent()

    public data class FileReady(
        override val unit: DownloadUnit,
    ) : Terminal()

    /**
     * [message] is raw platform text, not a classified error: what a failing transfer can report
     * differs per platform and per layer (an HTTP status line, an `NSError` description, a socket
     * message), and normalizing it at the source would mean every implementation re-deriving the
     * same taxonomy. The engine classifies it into a [DownloadError] once, in one place.
     *
     * An HTTP failure is recognized only when the message states the status as `HTTP <code>` or
     * `status <code>` (for example `HTTP 404`, `HTTP error: 503`): 404 and 410 become
     * [DownloadError.NotFound], 401 and 403 [DownloadError.Unauthorized], 5xx [DownloadError.Server],
     * any other status [DownloadError.Unknown]. A number anywhere else in the text (an id, a URL, a
     * byte count) is never read as a status.
     */
    public data class Error(
        override val unit: DownloadUnit,
        public val message: String,
    ) : Terminal()

    public data class Cancelled(
        override val unit: DownloadUnit,
    ) : Terminal()
}
