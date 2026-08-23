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
 * only when a partial temp file survives — an automatic retry, or process death mid-transfer; on
 * iOS the OS-owned background session is what survives, reconnected by identifier on relaunch, and
 * the temp file appears only on completion. A user cancel and a final failure both delete the temp
 * file, so those start fresh.
 *
 * The engine owns every retry decision; an implementation reports what happened and stops there.
 */
public interface BackgroundResourceDownloader {

    /**
     * Starts a background download for [unit]. Idempotent: enqueuing a unit already in flight joins
     * the running transfer rather than starting a second one.
     */
    public fun enqueueDownload(unit: DownloadUnit)

    /**
     * Progress and terminal events for [unit]. Survives process death — on relaunch an
     * implementation reconnects to the transfer the OS kept running and replays its outcome.
     */
    public fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent>

    /** True when a download for [unit] is enqueued or running, including across a restart. */
    public fun isDownloadInProgress(unit: DownloadUnit): Boolean

    /** Cancels [unit]'s transfer. Does not delete the temp file — the engine decides that. */
    public fun cancelDownload(unit: DownloadUnit)
}

/** What a [BackgroundResourceDownloader] reports about one unit's transfer. */
public sealed class BackgroundDownloadEvent {
    public abstract val unit: DownloadUnit

    /** An event that ends a transfer — exactly one of these arrives per attempt. */
    public sealed class Terminal : BackgroundDownloadEvent()

    public data class Progress(
        override val unit: DownloadUnit,
        val fraction: Float,
    ) : BackgroundDownloadEvent()

    public data class FileReady(
        override val unit: DownloadUnit,
    ) : Terminal()

    /**
     * [message] is raw platform text, not a classified error: what a failing transfer can report
     * differs per platform and per layer (an HTTP status line, an `NSError` description, a socket
     * message), and normalizing it at the source would mean every implementation re-deriving the
     * same taxonomy. The engine classifies it into a [DownloadError] once, in one place.
     */
    public data class Error(
        override val unit: DownloadUnit,
        val message: String,
    ) : Terminal()

    public data class Cancelled(
        override val unit: DownloadUnit,
    ) : Terminal()
}
