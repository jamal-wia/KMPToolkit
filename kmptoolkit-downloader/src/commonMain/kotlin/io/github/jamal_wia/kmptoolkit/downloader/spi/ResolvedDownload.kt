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
 * [toString] omits the URL's query and fragment, which usually carry the signature of a signed link,
 * and masks any `user:password@` part.
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

    // A resolved URL is typically a short-lived signed link: its query carries the credential, and
    // so may its userinfo (https://user:pass@host/). toString ends up in logs, so keep scheme, host
    // and path, mask the userinfo, and drop the query and fragment.
    override fun toString(): String {
        val cut: Int = url.indexOfFirst { it == '?' || it == '#' }
        val kept: String = if (cut < 0) url else url.substring(0, cut)
        val marker: String = when {
            cut < 0 -> ""
            url[cut] == '?' -> "?…"
            else -> "#…"
        }
        return "ResolvedDownload(url=${redactUserInfo(kept)}$marker, expectedSha256=$expectedSha256)"
    }

    private fun redactUserInfo(withoutQuery: String): String {
        val authorityStart: Int = withoutQuery.indexOf("://").let { if (it < 0) return withoutQuery else it + 3 }
        val authorityEnd: Int = withoutQuery.indexOf('/', authorityStart).let { if (it < 0) withoutQuery.length else it }
        val at: Int = withoutQuery.lastIndexOf('@', authorityEnd - 1)
        if (at < authorityStart) return withoutQuery
        return withoutQuery.substring(0, authorityStart) + "***" + withoutQuery.substring(at)
    }
}
