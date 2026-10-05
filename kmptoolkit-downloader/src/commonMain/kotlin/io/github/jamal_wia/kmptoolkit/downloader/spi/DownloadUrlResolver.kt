package io.github.jamal_wia.kmptoolkit.downloader.spi

import io.github.jamal_wia.kmptoolkit.downloader.DownloadUnit

/**
 * Turns a [DownloadUnit]'s opaque [DownloadUnit.apiPath] into a URL the platform layer can fetch.
 *
 * This is the port that keeps an HTTP client out of the library entirely. Resolving the URL is the
 * one step that needs the host's base URL, its authentication and its response shape — so the host
 * does it, typically by asking its backend for a short-lived signed link. Moving the bytes
 * afterwards needs none of that: the platform downloader fetches the returned URL directly, which
 * is deliberate rather than incidental — a general-purpose HTTP client's buffering was found to
 * exhaust the heap on multi-hundred-megabyte transfers once the app was backgrounded (see
 * `docs/kmptoolkit-downloader/07-background-downloader.md`).
 *
 * Called on every attempt, never cached by the engine: a signed URL expires, and a retry hours
 * later must ask for a fresh one.
 *
 * **Current state:** declared for a [io.github.jamal_wia.kmptoolkit.downloader.BackgroundResourceDownloader]
 * implementation to consume, but the engine itself never calls it — resolving the URL is entirely
 * the transfer implementation's job, on the same terms as everything else in that port. It is
 * published because most `BackgroundResourceDownloader` implementations will want exactly this
 * shape, not because the engine mediates it.
 */
public fun interface DownloadUrlResolver {

    /**
     * Resolves where to fetch [unit] from, and the hash of the object there when the backend says
     * so. Throwing means this attempt failed and the engine's usual retry policy applies — the same
     * as any transport failure.
     *
     * The returned [ResolvedDownload.expectedSha256] belongs to exactly this response: pass it to
     * [io.github.jamal_wia.kmptoolkit.downloader.DownloaderStorage.beginTempFile] when the transfer
     * starts from byte zero, and never when it resumes a partial file.
     */
    public suspend fun resolve(unit: DownloadUnit): ResolvedDownload
}
