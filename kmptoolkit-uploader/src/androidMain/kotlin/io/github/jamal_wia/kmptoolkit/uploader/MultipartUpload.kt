package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Performs [request] once as a streamed multipart upload and reports the raw outcome. Shared by both
 * `WorkManager` workers; all policy — classification, retries, hooks — lives elsewhere.
 *
 * @param onWholePercent called with each whole percent of the file parts written, at most once each.
 *   File bytes stand in for the body: text fields and multipart framing are a rounding error beside
 *   them.
 * @param isCancelled polled before every chunk of the body. The write is blocking, so a cancelled
 *   coroutine around it would otherwise let the whole body reach the server and only discard the
 *   outcome. Once it reads `true` the connection is dropped before the final chunk, so the server never
 *   receives a complete request, and [CancellationException] is thrown.
 * @throws CancellationException when [isCancelled] turned `true` mid-body.
 */
internal fun performMultipartUpload(
    request: UploadRequest,
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
    onWholePercent: (fraction: Float) -> Unit = {},
    isCancelled: () -> Boolean = { false },
): UploadResult {
    // Pre-flight the file parts so a vanished source reads as a transport failure the handler can react
    // to, instead of an exception mid-stream.
    request.fields.filterIsInstance<UploadField.File>().forEach { part ->
        if (!File(part.path).canRead()) {
            return UploadResult.TransportFailure("source file missing/unreadable: ${part.path}")
        }
    }
    val boundary = "kmptoolkit-uploader-${UUID.randomUUID()}"
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            doOutput = true
            // Stream straight from disk — never buffer a multi-megabyte body in memory.
            setChunkedStreamingMode(0)
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        val out: OutputStream = connection.outputStream
        writeMultipartBody(out, boundary, request, onWholePercent, isCancelled)
        // Closed only once the body is whole. Closing a chunked stream sends its final chunk, so closing
        // it on a cancellation or a failed write would hand the server a complete, truncated request;
        // the disconnect below drops the connection instead.
        out.close()
        UploadResult.Completed(connection.responseCode)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A server can reject-and-close before the chunked body finishes (size cap, proxy 4xx) — the
        // write then throws, but a status may still be readable. Prefer the real status so a permanent
        // rejection can be classified instead of retried forever as a transport failure.
        val lateStatus: Int? = connection?.let { conn ->
            runCatching { conn.responseCode }.getOrNull()?.takeIf { it >= HTTP_BAD_REQUEST }
        }
        if (lateStatus != null) {
            UploadResult.Completed(lateStatus)
        } else {
            UploadResult.TransportFailure(e.message ?: e::class.simpleName)
        }
    } finally {
        connection?.disconnect()
    }
}

private fun writeMultipartBody(
    out: OutputStream,
    boundary: String,
    request: UploadRequest,
    onWholePercent: (fraction: Float) -> Unit,
    isCancelled: () -> Boolean,
) {
    fun ensureNotCancelled() {
        if (isCancelled()) throw CancellationException("Upload cancelled mid-body.")
    }
    fun writeText(text: String) {
        ensureNotCancelled()
        out.write(text.encodeToByteArray())
    }
    val progress = WholePercentProgress(
        totalBytes = request.fields.filterIsInstance<UploadField.File>().sumOf { File(it.path).length() },
        onWholePercent = onWholePercent,
    )
    request.fields.forEach { field ->
        writeText("--$boundary\r\n")
        when (field) {
            is UploadField.Text -> {
                writeText("Content-Disposition: form-data; name=\"${field.name}\"\r\n\r\n")
                writeText(field.value)
            }

            is UploadField.File -> {
                writeText("Content-Disposition: form-data; name=\"${field.name}\"; filename=\"${field.fileName}\"\r\n")
                writeText("Content-Type: ${field.contentType}\r\n\r\n")
                File(field.path).inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read: Int = input.read(buffer)
                        if (read < 0) break
                        ensureNotCancelled()
                        out.write(buffer, 0, read)
                        progress.add(read.toLong())
                    }
                }
            }
        }
        writeText("\r\n")
    }
    writeText("--$boundary--\r\n")
}

private const val HTTP_BAD_REQUEST: Int = 400
private const val COPY_BUFFER_BYTES: Int = 8 * 1024
