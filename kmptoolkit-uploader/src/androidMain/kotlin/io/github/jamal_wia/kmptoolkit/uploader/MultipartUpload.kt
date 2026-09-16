package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Performs [request] once as a streamed multipart upload and reports the raw outcome. Shared by both
 * `WorkManager` workers; all policy — classification, retries, hooks — lives elsewhere.
 *
 * @param onWholePercent called with each whole percent of the file parts written, at most once each.
 *   File bytes stand in for the body: text fields and multipart framing are a rounding error beside
 *   them.
 */
internal fun performMultipartUpload(
    request: UploadRequest,
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
    onWholePercent: (fraction: Float) -> Unit = {},
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
        connection.outputStream.use { out -> writeMultipartBody(out, boundary, request, onWholePercent) }
        UploadResult.Completed(connection.responseCode)
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
) {
    fun writeText(text: String) {
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
