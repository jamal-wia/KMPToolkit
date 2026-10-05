package io.github.jamal_wia.kmptoolkit.downloader.spi

import io.github.jamal_wia.kmptoolkit.downloader.Sha256

/**
 * What a [DownloadUrlResolver] learned about one download: where to fetch it and, when the backend
 * says so, the hash the bytes behind that URL must have.
 *
 * [expectedSha256] describes exactly the object behind [url] at the moment of resolving. A backend
 * that replaces objects in place hands out a different hash after each replacement, which is why
 * it travels with the URL rather than living on the [io.github.jamal_wia.kmptoolkit.downloader.DownloadUnit].
 * Pass it to [io.github.jamal_wia.kmptoolkit.downloader.DownloaderStorage.beginTempFile] when — and
 * only when — the transfer starts writing the temp file from byte zero.
 *
 * Take [expectedSha256] from `Sha256.parseOrNull(response.sha256)`: a hash the backend omitted or
 * garbled then means "no check" instead of a failed download.
 *
 * [toString] omits the URL's query and fragment, which usually carry the signature of a signed link.
 *
 * @param url the URL to fetch. Must not be blank.
 * @param expectedSha256 the hash of the object behind [url], or null when the backend stated none.
 */
public class ResolvedDownload(
    public val url: String,
    public val expectedSha256: Sha256? = null,
) {
    init {
        require(url.isNotBlank()) { "ResolvedDownload.url must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is ResolvedDownload && other.url == url && other.expectedSha256 == expectedSha256

    override fun hashCode(): Int = 31 * url.hashCode() + (expectedSha256?.hashCode() ?: 0)

    // A resolved URL is typically a short-lived signed link whose query carries the credential, and
    // toString ends up in logs: keep scheme, host and path, drop the query and fragment.
    override fun toString(): String {
        val cut: Int = url.indexOfFirst { it == '?' || it == '#' }
        val safeUrl: String = if (cut < 0) url else url.substring(0, cut) + "?…"
        return "ResolvedDownload(url=$safeUrl, expectedSha256=$expectedSha256)"
    }
}
