package io.github.jamal_wia.kmptoolkit.downloader

/**
 * Why a download failed, in the only terms this library can honestly speak.
 *
 * Deliberately its own small taxonomy rather than the host's application-wide error type: a
 * download can fail for exactly these reasons, and forcing the host's full error hierarchy through
 * here means every consumer would have to answer for cases that cannot occur just to satisfy an
 * exhaustive `when`. The host maps these onto its own errors and its own strings at the edge — see
 * [io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadNotifier].
 */
public sealed class DownloadError {

    /** The transfer could not reach the server, or died mid-flight. */
    public data object NoConnection : DownloadError()

    /** The server accepted the connection but did not answer in time. */
    public data object Timeout : DownloadError()

    /** The resource is not where the host said it would be (404). */
    public data object NotFound : DownloadError()

    /** The request was rejected as unauthenticated or forbidden (401/403). */
    public data object Unauthorized : DownloadError()

    /** The server answered, but with a failure — [statusCode] is null when it wasn't an HTTP one. */
    public data class Server(public val statusCode: Int?) : DownloadError()

    /**
     * The bytes arrived but could not be finalized: no room on disk, an unwritable path, or a ZIP
     * that would not extract. Distinct from a transport failure because retrying the download is
     * not obviously the fix.
     */
    public data class Storage(public val message: String? = null) : DownloadError()

    /**
     * The bytes arrived and failed a check, again after a fresh download: a [DownloadUnit.sha256]
     * or recorded-hash mismatch, or a [ResourceFormat.SqliteDatabase] that does not open or does not
     * hold the rows it declares. Nothing was committed. Persisting across two downloads usually
     * means the server is serving different bytes than the host expects — a stale hash in the
     * catalogue, or a broken upload — rather than anything on the device.
     */
    public data class Corrupted(public val message: String? = null) : DownloadError()

    /**
     * Anything the host's platform layer could not classify, and a unit whose [DownloadUnit.sha256]
     * getter throws (a catalogue error, reported before any transfer starts). [message] is for logs,
     * not for users.
     */
    public data class Unknown(public val message: String? = null) : DownloadError()
}
