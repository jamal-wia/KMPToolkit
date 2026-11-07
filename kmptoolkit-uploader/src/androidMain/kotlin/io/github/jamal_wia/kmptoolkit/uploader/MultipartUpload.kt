package io.github.jamal_wia.kmptoolkit.uploader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    watch: WriteWatch? = null,
): UploadResult = performMultipartUploadCapturing(
    request = request,
    connectTimeoutMillis = connectTimeoutMillis,
    readTimeoutMillis = readTimeoutMillis,
    onWholePercent = onWholePercent,
    isCancelled = isCancelled,
    captureResponse = false,
    watch = watch,
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
    watch: WriteWatch? = null,
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
        watch?.begin(connection)
        val out: OutputStream = connection.outputStream // connects; not counted as body inactivity
        watch?.startWriting()
        writeMultipartBody(out, boundary, request, onWholePercent, isCancelled, watch)
        // Closed only once the body is whole. Closing a chunked stream sends its final chunk, so closing
        // it on a cancellation or a failed write would hand the server a complete, truncated request;
        // the disconnect below drops the connection instead.
        out.close()
        watch?.finishWriting()
        val status: Int = connection.responseCode
        watch?.answered()
        MultipartOutcome(
            UploadResult.Completed(status),
            if (captureResponse) readResponse(connection, status, isCancelled, readTimeoutMillis) else null,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A server can answer and close before the chunked body finishes (size cap, proxy 4xx, an early 2xx
        // or redirect) — the write then throws, but a status may still be readable. Any final status the
        // server sent before the body ended is its answer: a rejection to classify, or an early acceptance
        // or redirect that must not upload the file again. Prefer the real status so it can be classified
        // instead of retried forever as a transport failure. 1xx is not final and stays a failure.
        // Not after the watchdog dropped the connection: the peer is not answering, and asking for its status
        // would wait out the whole read timeout on a socket that was closed on purpose.
        val lateStatus: Int? = connection?.takeUnless { watch?.wasDropped == true }?.let { conn ->
            runCatching { conn.responseCode }.getOrNull()?.takeIf { it in HTTP_FIRST_FINAL_STATUS..HTTP_LAST_STATUS }
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
 * body from `errorStream` for a 4xx/5xx or `inputStream` otherwise — an empty body when the server sent none,
 * and also when the headers declared one the stack did not deliver, in which case it is flagged truncated.
 * A body that fails to read is reported as truncated rather than thrown. A status that carries no content
 * (1xx, 204, 205, 304) is never read and never flagged: whatever length its headers name belongs to the
 * entity it stands for, not to a body it sent.
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
    if (status in HTTP_INFORMATIONAL_RANGE || status in STATUSES_WITHOUT_CONTENT) {
        return UploadResponse(status, headers, ByteArray(0), false)
    }
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
        } else if (declaresBody(connection)) {
            // The stack kept the body from us (a 401 or 407 on a stack that dropped the connection to
            // retry): say so, instead of reporting that the server sent nothing.
            read = ReadBody(read.bytes, cut = true)
        }
    } catch (e: Exception) {
        // No stream to read from: the status stands, the body is unknown.
        read = ReadBody(read.bytes, cut = true)
    }
    return UploadResponse(status, headers, read.bytes, read.cut)
}

/** Whether the response's headers announce a body: a positive `Content-Length` or a chunked transfer encoding. */
private fun declaresBody(connection: HttpURLConnection): Boolean {
    val length: Long? = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
    if (length != null) return length > 0
    return connection.getHeaderField("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true
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
    watch: WriteWatch?,
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
                        watch?.touch()
                        progress.add(read.toLong())
                    }
                }
            }
        }
        writeText("\r\n")
    }
    writeText("--$boundary--\r\n")
}

private const val HTTP_FIRST_FINAL_STATUS: Int = 200
private const val HTTP_LAST_STATUS: Int = 599
private val HTTP_INFORMATIONAL_RANGE: IntRange = 100..199
private val STATUSES_WITHOUT_CONTENT: Set<Int> = setOf(204, 205, 304)
private const val HTTP_BAD_REQUEST: Int = 400
private const val COPY_BUFFER_BYTES: Int = 8 * 1024
private const val NANOS_PER_MILLI: Long = 1_000_000L

/**
 * What a transfer shares with its watchdog: the open connection and when the body last moved. `HttpURLConnection`
 * has no write timeout and the body write is blocking, so a peer that stops reading — or a path that silently
 * died — pins the thread for as long as the kernel keeps retransmitting, and a cancelled coroutine around it
 * cannot interrupt it. The only way out is to close the socket from another thread, which [drop] does.
 */
internal class WriteWatch {
    // @Volatile/AtomicReference, not StateFlow or Mutex: touch() runs on the blocking body-write thread once per
    // chunk, and begin()/drop() is a publish-then-check handshake between that thread and the watchdog.
    private val connection: AtomicReference<HttpURLConnection?> = AtomicReference(null)

    @Volatile
    private var writing: Boolean = false

    @Volatile
    private var lastActivityNanos: Long = System.nanoTime()

    @Volatile
    private var awaitingAnswer: Boolean = false

    @Volatile
    private var awaitingSinceNanos: Long = 0L

    @Volatile
    private var dropped: Boolean = false

    /** Whether [drop] was called: the connection was closed from outside, not by the peer. */
    val wasDropped: Boolean get() = dropped

    /**
     * Publishes [opened] so a [drop] reaches it, and so a cancel during the connect is not lost. Does NOT start
     * the stall clock: the connect (TCP + TLS) runs under the connect timeout, not the body-inactivity limit.
     */
    fun begin(opened: HttpURLConnection) {
        connection.set(opened)
        // A drop that landed before the connection was published (a cancel during connect) still applies.
        if (dropped) opened.disconnect()
    }

    /** The connection is up and the body write starts: from here on, silence counts as a stall. */
    fun startWriting() {
        lastActivityNanos = System.nanoTime()
        writing = true
    }

    fun touch() {
        lastActivityNanos = System.nanoTime()
    }

    /** The body is whole: from here the transfer waits for the status line and headers, under [stalledFor]'s second limit. */
    fun finishWriting() {
        writing = false
        awaitingSinceNanos = System.nanoTime()
        awaitingAnswer = true
    }

    /** The status line and headers arrived; the body read that follows has its own deadline. */
    fun answered() {
        awaitingAnswer = false
    }

    /** Closes the socket; the blocked write throws and the transfer reports through its ordinary failure path. */
    fun drop() {
        dropped = true
        connection.get()?.disconnect()
    }

    /**
     * Whether the transfer is stuck: the body write made no progress for [limitMillis], or — once the body is
     * sent — the status line and headers have not arrived within twice that. The first [limitMillis] of the
     * wait is the server's silent processing, which the per-read timeout already allows; the second is what a
     * server that drips its headers a byte at a time (each byte resetting that timeout) can still take.
     */
    fun stalledFor(limitMillis: Int): Boolean =
        (writing && (System.nanoTime() - lastActivityNanos) / NANOS_PER_MILLI > limitMillis) ||
            (awaitingAnswer && (System.nanoTime() - awaitingSinceNanos) / NANOS_PER_MILLI > 2L * limitMillis)
}

/**
 * Runs the blocking transfer in [block] under a watchdog that closes its connection when the surrounding
 * coroutine is cancelled, when the body write makes no progress for [stallMillis], or when the server takes
 * longer than twice that to send its status line and headers once the body is sent. The watchdog is a child
 * of this scope, so cancellation reaches it even while the transfer's own thread is blocked.
 */
internal suspend fun <T> withWriteWatchdog(stallMillis: Int, block: suspend (WriteWatch) -> T): T = coroutineScope {
    val watch = WriteWatch()
    val watchdog = launch {
        try {
            while (true) {
                delay(WATCHDOG_POLL_MILLIS)
                if (watch.stalledFor(stallMillis)) break
            }
        } finally {
            // Reached on a stall, and on cancellation of the job — the case a blocked write cannot see.
            watch.drop()
        }
    }
    try {
        block(watch)
    } finally {
        watchdog.cancel()
    }
}

private const val WATCHDOG_POLL_MILLIS: Long = 250L
