package io.github.jamal_wia.kmptoolkit.uploader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
): UploadResult = performMultipartUploadCapturing(
    request = request,
    connectTimeoutMillis = connectTimeoutMillis,
    readTimeoutMillis = readTimeoutMillis,
    onWholePercent = onWholePercent,
    isCancelled = isCancelled,
    captureResponse = false,
).result

/** The raw outcome of one multipart upload and, when it was asked for and the server answered, the answer. */
internal class MultipartOutcome(val result: UploadResult, val response: UploadResponse?)

/**
 * [performMultipartUpload] that also reads what the server answered, when [captureResponse] is set: the
 * status, the headers and at most [MAX_RESPONSE_BODY_BYTES] of the body. Reading the body is part of the
 * same attempt — before the connection is dropped — and a body that cannot be read never turns a received
 * status into a [UploadResult.TransportFailure], which would upload an accepted file again.
 *
 * The handler's own limit is applied later, in the engine; this reads at most the engine's ceiling plus
 * one byte, enough to know the body was longer.
 */
internal fun performMultipartUploadCapturing(
    request: UploadRequest,
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
    onWholePercent: (fraction: Float) -> Unit = {},
    isCancelled: () -> Boolean = { false },
    captureResponse: Boolean = true,
): MultipartOutcome {
    // Pre-flight the file parts so a vanished source reads as a transport failure the handler can react
    // to, instead of an exception mid-stream.
    request.fields.filterIsInstance<UploadField.File>().forEach { part ->
        if (!File(part.path).canRead()) {
            return MultipartOutcome(UploadResult.TransportFailure("source file missing/unreadable: ${part.path}"), null)
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
        val status: Int = connection.responseCode
        MultipartOutcome(
            UploadResult.Completed(status),
            if (captureResponse) readResponse(connection, status, isCancelled, readTimeoutMillis) else null,
        )
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
            // Best effort: the connection was torn down mid-request, so the body may be unreadable — then
            // the response carries what the server did say and an empty, truncated body.
            MultipartOutcome(
                UploadResult.Completed(lateStatus),
                if (captureResponse) readResponse(connection, lateStatus, isCancelled, readTimeoutMillis) else null,
            )
        } else {
            MultipartOutcome(UploadResult.TransportFailure(e.message ?: e::class.simpleName), null)
        }
    } finally {
        connection?.disconnect()
    }
}

/**
 * The answer on [connection] (whose status is [status]): headers without the status line's null key, and the
 * body from `errorStream` for a 4xx/5xx — `null` when the server sent none — or `inputStream` otherwise.
 * A body that fails to read is reported as truncated rather than thrown.
 */
private fun readResponse(
    connection: HttpURLConnection,
    status: Int,
    isCancelled: () -> Boolean,
    deadlineMillis: Int,
): UploadResponse {
    val headers: Map<String, String> = runCatching {
        buildMap {
            connection.headerFields.forEach { (name, values) ->
                if (name != null && values != null) put(name, values.joinToString(", "))
            }
        }
    }.getOrDefault(emptyMap())
    var read = ReadBody(ByteArray(0), cut = false)
    try {
        val stream: InputStream? = if (status >= HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
        if (stream != null) {
            read = stream.use { input -> readAtMost(input, MAX_RESPONSE_BODY_BYTES, isCancelled, deadlineMillis) }
            // A server that closes early may end a fixed-length body without any error on some stacks:
            // fewer bytes than it declared is an incomplete body. Skipped when the stack decoded the
            // body (a content encoding), where the declared length is not the decoded one.
            val declared: Long? = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
            if (declared != null && connection.contentEncoding == null && read.bytes.size < declared && !read.cut) {
                read = ReadBody(read.bytes, cut = true)
            }
        }
    } catch (e: Exception) {
        // No stream to read from: the status stands, the body is unknown.
        read = ReadBody(read.bytes, cut = true)
    }
    return UploadResponse(status, headers, read.bytes, read.cut)
}

private class ReadBody(val bytes: ByteArray, val cut: Boolean)

/**
 * Reads [limit] bytes of [input], and one more to learn whether there was more. Stops with what it has —
 * flagged as cut — when [isCancelled] reads `true`, when reading takes longer than [deadlineMillis] in
 * total (the per-read timeout alone would let a slow trickle hold the thread for as long as the server
 * likes, past the worker's own limit), or when the stream fails.
 */
private fun readAtMost(input: InputStream, limit: Int, isCancelled: () -> Boolean, deadlineMillis: Int): ReadBody {
    val collected = ByteArrayOutputStream()
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    val startedAt: Long = System.nanoTime()
    var remaining: Int = limit + 1
    var interrupted = false
    try {
        while (remaining > 0) {
            if (isCancelled() || (System.nanoTime() - startedAt) / NANOS_PER_MILLI > deadlineMillis) {
                interrupted = true
                break
            }
            val read: Int = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            collected.write(buffer, 0, read)
            remaining -= read
        }
    } catch (e: Exception) {
        interrupted = true
    }
    val all: ByteArray = collected.toByteArray()
    return if (all.size > limit) ReadBody(all.copyOf(limit), true) else ReadBody(all, interrupted)
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
private const val NANOS_PER_MILLI: Long = 1_000_000L
