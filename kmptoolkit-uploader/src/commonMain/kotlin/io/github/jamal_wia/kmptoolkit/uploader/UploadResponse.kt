package io.github.jamal_wia.kmptoolkit.uploader

/**
 * What the server answered to an upload: the status line's code, the response headers and the start of
 * the response body. Handed to [UploadHandler.classify] and [UploadHandler.onDelivered] for every
 * answer the server gave — a 2xx, but also a 4xx or 5xx, whose body usually says why.
 *
 * It is a plain class, not a data class, because it holds a [ByteArray] and must be able to grow
 * without a binary-compatibility break. Equality is by content.
 *
 * - **Headers** are exposed with lowercase names. Several headers whose names differ only in case are
 *   merged, their values joined with `", "`. That join is lossy for `Set-Cookie`, whose values may
 *   themselves contain commas: do not read cookies from here. The total size of the headers a
 *   transport hands to the engine is capped at 64 K characters of names and values; entries beyond the
 *   cap are dropped.
 * - **The body** is copied on construction and [body] returns a fresh copy. It is never `null`: no body,
 *   or a `204`, is an empty array. It holds at most the handler's [UploadHandler.maxResponseBodyBytes]
 *   (never more than 1 MiB); when the server sent more, the rest was discarded and [isBodyTruncated]
 *   is `true`. [isBodyTruncated] is also `true` when the transport could not read the body completely —
 *   the connection broke, or the read took too long — in which case the body may be shorter than the
 *   limit, even empty, while the status and headers are those the server sent.
 * - [toString] never prints headers or body — they may hold tokens or personal data.
 *
 * Code that receives a response where [UploadResult] is [UploadResult.Completed] can rely on
 * `result.statusCode == response.statusCode`: the engine builds one from the other.
 *
 * @param statusCode the HTTP status code.
 * @param headers response headers; names are lowercased, names that differ only in case are merged.
 * @param body the response body, or its prefix; copied.
 * @param isBodyTruncated whether the body is incomplete: at least one received byte is missing from [body],
 *   or the transport could not read the body completely.
 * @since 4.0.0
 */
public class UploadResponse(
    public val statusCode: Int,
    headers: Map<String, String>,
    body: ByteArray,
    public val isBodyTruncated: Boolean,
) {

    /** The response headers, read-only, with lowercase names. */
    public val headers: Map<String, String> = mergeHeaders(headers)

    private val bodyBytes: ByteArray = body.copyOf()

    /** A fresh copy of the captured response body; empty when the server sent none. */
    public fun body(): ByteArray = bodyBytes.copyOf()

    /** The value of the header [name], looked up case-insensitively, or `null` when it is absent. */
    public fun header(name: String): String? = headers[name.lowercase()]

    internal val bodySize: Int get() = bodyBytes.size

    /** This response with the body cut to [limit] bytes and the headers capped; the engine's single enforcement point. */
    internal fun enforced(limit: Int): UploadResponse {
        val cappedHeaders: Map<String, String> = capHeaders(headers)
        val tooLong: Boolean = bodyBytes.size > limit
        if (!tooLong && cappedHeaders.size == headers.size) return this
        return UploadResponse(
            statusCode = statusCode,
            headers = cappedHeaders,
            body = if (tooLong) bodyBytes.copyOf(limit) else bodyBytes,
            isBodyTruncated = isBodyTruncated || tooLong,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UploadResponse) return false
        return statusCode == other.statusCode &&
            isBodyTruncated == other.isBodyTruncated &&
            headers == other.headers &&
            bodyBytes.contentEquals(other.bodyBytes)
    }

    override fun hashCode(): Int {
        var result: Int = statusCode
        result = 31 * result + isBodyTruncated.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + bodyBytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "UploadResponse(statusCode=$statusCode, headers=${headers.size}, body=${bodyBytes.size} B, truncated=$isBodyTruncated)"

    private companion object {

        fun mergeHeaders(raw: Map<String, String>): Map<String, String> {
            val merged: MutableMap<String, String> = LinkedHashMap()
            raw.forEach { (name, value) ->
                val key: String = name.lowercase()
                val previous: String? = merged[key]
                merged[key] = if (previous == null) value else "$previous, $value"
            }
            return merged.toMap()
        }

        fun capHeaders(headers: Map<String, String>): Map<String, String> {
            var total = 0
            val kept: MutableMap<String, String> = LinkedHashMap()
            for ((name, value) in headers) {
                total += name.length + value.length
                if (total > MAX_RESPONSE_HEADER_CHARS) break
                kept[name] = value
            }
            return if (kept.size == headers.size) headers else kept.toMap()
        }
    }
}

/** The most response-body bytes the engine ever keeps, whatever a handler asks for. */
internal const val MAX_RESPONSE_BODY_BYTES: Int = 1024 * 1024

/** The most characters of response header names and values the engine keeps. */
internal const val MAX_RESPONSE_HEADER_CHARS: Int = 64 * 1024

/**
 * Whether an HTTP response with [status] carries no content: a 1xx, 204, 205 or 304. Whatever length its headers
 * name belongs to the entity it stands for, not to a body it sent, so such a response is never flagged as
 * having a truncated body. One rule for every transport, so the platforms cannot drift.
 */
internal fun statusCarriesNoContent(status: Int): Boolean = status in 100..199 || status == 204 || status == 205 || status == 304
