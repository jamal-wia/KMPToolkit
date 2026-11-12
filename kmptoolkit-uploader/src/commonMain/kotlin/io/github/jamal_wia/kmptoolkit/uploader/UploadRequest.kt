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
 *
 * [toString] prints the url and method, the header *names* and the fields, but never a header value or the value of a
 * text field: they hold bearer tokens and personal data, and a request ends up in logs and crash reports.
 */
public data class UploadRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val fields: List<UploadField>,
) {
    override fun toString(): String =
        "UploadRequest(url=$url, method=$method, headers=${headers.keys}, fields=$fields)"
}

/** One multipart form field. */
public sealed interface UploadField {

    /**
     * A plain text field. The [name] is sent between quotes with `"`, CR and LF percent-escaped (`%22`, `%0D`,
     * `%0A`) as browsers do; the [value] is body content and goes out as it is. [toString] prints the [name] and the
     * length of the [value], never the value.
     */
    public data class Text(val name: String, val value: String) : UploadField {
        override fun toString(): String = "Text(name=$name, value=<${value.length} chars>)"
    }

    /**
     * A file part streamed from [path] at upload time — the request references the file, the
     * transport reads it. The file must stay on disk until the item settles. [name] and [fileName] are sent
     * percent-escaped for `"`, CR and LF as browsers do, and CR and LF are removed from [contentType], so none
     * of them can end its header or add a header or a part to the request.
     */
    public data class File(
        val name: String,
        val fileName: String,
        val contentType: String,
        val path: String,
    ) : UploadField
}
