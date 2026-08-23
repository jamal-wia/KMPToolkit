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
    public data class Server(val statusCode: Int?) : DownloadError()

    /**
     * The bytes arrived but could not be finalized: no room on disk, an unwritable path, a ZIP that
     * would not extract, or a database that failed its integrity check (see
     * [ResourceFormat.SqliteDatabase]). Distinct from a transport failure because retrying the
     * download is not obviously the fix.
     */
    public data class Storage(val message: String? = null) : DownloadError()

    /** Anything the host's platform layer could not classify. [message] is for logs, not for users. */
    public data class Unknown(val message: String? = null) : DownloadError()
}
