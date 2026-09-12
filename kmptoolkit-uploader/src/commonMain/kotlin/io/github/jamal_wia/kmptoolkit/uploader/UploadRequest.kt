package io.github.jamal_wia.kmptoolkit.uploader

/**
 * Declarative description of one multipart HTTP upload — everything [UploadTransport] needs to
 * perform the request without touching your handler's own types.
 *
 * Build one inside [UploaderHandler.execute] and hand it to [UploadTransport.launch] before
 * returning [AttemptResult.Detached] — see `docs/kmptoolkit-uploader/08-upload-transport.md`.
 *
 * @property url the destination.
 * @property method the HTTP method. `"POST"` covers virtually every multipart upload API; the field
 *   exists for the rare server that insists on `"PUT"`.
 * @property headers sent verbatim, rebuilt fresh on every call to [UploaderHandler.execute] — so a
 *   short-lived value (a bearer token) is fine here, unlike in something persisted once and reused.
 * @property fields multipart fields, in wire order.
 */
public data class UploadRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val fields: List<UploadField>,
)

/** One multipart form field. */
public sealed interface UploadField {

    /** A plain text field. */
    public data class Text(val name: String, val value: String) : UploadField

    /**
     * A file part streamed from [path] at upload time — the request references the file, the
     * transport reads it. The file must stay on disk until the item settles.
     */
    public data class File(
        val name: String,
        val fileName: String,
        val contentType: String,
        val path: String,
    ) : UploadField
}
