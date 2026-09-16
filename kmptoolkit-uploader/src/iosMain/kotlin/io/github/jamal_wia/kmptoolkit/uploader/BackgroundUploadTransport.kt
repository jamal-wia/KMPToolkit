package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSInputStream
import platform.Foundation.NSLock
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOutputStream
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSUUID
import platform.Foundation.inputStreamWithFileAtPath
import platform.Foundation.outputStreamToFileAtPath
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject

/**
 * How [createBackgroundUploadTransport] names and paces its background sessions.
 *
 * @param sessionIdentifierPrefix the prefix of every background `NSURLSession` identifier; the item id
 *   is appended. `null` — the default — resolves to `<CFBundleIdentifier>.uploader.upload.`. The
 *   identifier outlives the process inside `nsurlsessiond`, so **pin it** if an earlier version of your
 *   app started sessions under another prefix: a relaunch for a session nobody recognises loses that
 *   upload's outcome. Must not be blank.
 * @param lease the lease each hand-off is claimed under. iOS may run a background session long after
 *   the app hands it over, so it is generous; the identifier rejoin makes even a short one safe.
 * @param rehandFlushWindow how long a re-hand that finds no running task waits for buffered completion
 *   events before starting the upload afresh — the previous process may have finished it without
 *   settling, and starting again would upload it twice.
 * @since 1.5.0
 */
public class BackgroundUploadConfig(
    public val sessionIdentifierPrefix: String? = null,
    public val lease: Duration = 60.minutes,
    public val rehandFlushWindow: Duration = 3.seconds,
) {
    init {
        require(sessionIdentifierPrefix == null || sessionIdentifierPrefix.isNotBlank()) {
            "sessionIdentifierPrefix must be null or non-blank, was '$sessionIdentifierPrefix'."
        }
        require(lease.isPositive()) { "lease must be positive, was $lease." }
        require(!rehandFlushWindow.isNegative()) { "rehandFlushWindow must not be negative, was $rehandFlushWindow." }
    }

    override fun equals(other: Any?): Boolean =
        other is BackgroundUploadConfig &&
            sessionIdentifierPrefix == other.sessionIdentifierPrefix &&
            lease == other.lease &&
            rehandFlushWindow == other.rehandFlushWindow

    override fun hashCode(): Int {
        var result: Int = sessionIdentifierPrefix.hashCode()
        result = 31 * result + lease.hashCode()
        result = 31 * result + rehandFlushWindow.hashCode()
        return result
    }

    override fun toString(): String =
        "BackgroundUploadConfig(sessionIdentifierPrefix=$sessionIdentifierPrefix, lease=$lease, " +
            "rehandFlushWindow=$rehandFlushWindow)"

    internal companion object {
        const val DEFAULT_PREFIX_SUFFIX: String = ".uploader.upload."
    }
}

/**
 * Creates the iOS [UploadTransport] for an [UploadHandler]: one background `NSURLSession` per item,
 * whose identifier carries the item id.
 *
 * The system daemon, `nsurlsessiond`, keeps uploading after the app is suspended or killed — the
 * strongest durability iOS offers a transfer. Because the identifier carries the id, any process can
 * settle the item from it alone, including a relaunch iOS makes just to deliver the result: forward
 * that relaunch to [BackgroundUploadRelaunch.handleEvents] from your app delegate.
 *
 * Settlement goes through [UploadGateway], so the handler's `classify` and hooks run in the engine, and
 * whole-percent progress reaches [UploadHandler.onUploadProgress].
 *
 * **Idempotent launch.** An item whose session is live in this process is joined. A re-hand in a new
 * process rejoins a task the daemon is still running. A re-hand that finds nothing running waits
 * [BackgroundUploadConfig.rehandFlushWindow] for buffered completion events before starting afresh.
 *
 * The multipart body is written to a temporary file first — a background upload must come from a
 * file — and removed when the upload completes. It is rebuilt on every hand-off.
 *
 * Create it once per process, before the engine starts; the relaunch entry point finds it through a
 * process-wide registration.
 *
 * @since 1.5.0
 */
public fun createBackgroundUploadTransport(
    config: BackgroundUploadConfig = BackgroundUploadConfig(),
    logger: Logger = NoopLogger,
): UploadTransport = BackgroundSessionUploadTransport(
    identifierPrefix = config.sessionIdentifierPrefix
        ?: ((NSBundle.mainBundle.bundleIdentifier ?: FALLBACK_BUNDLE_ID) + BackgroundUploadConfig.DEFAULT_PREFIX_SUFFIX),
    config = config,
    logger = logger,
).also(BackgroundUploadRelaunch::register)

/**
 * Where iOS hands a background upload's results to an app it relaunched for them.
 *
 * When a background upload finishes while the app is not running, iOS relaunches it and calls
 * `application(_:handleEventsForBackgroundURLSession:completionHandler:)` — an app-delegate method
 * only your Swift code can implement. Forward it here:
 *
 * ```swift
 * func application(_ application: UIApplication,
 *                  handleEventsForBackgroundURLSession identifier: String,
 *                  completionHandler: @escaping () -> Void) {
 *     if identifier.hasPrefix("com.example.app.uploader.upload.") {
 *         BackgroundUploadRelaunch.shared.handleEvents(identifier: identifier, completion: completionHandler)
 *     } else {
 *         completionHandler()
 *     }
 * }
 * ```
 *
 * The transport recreates the session, receives the buffered completion and settles the item. The
 * completion is called exactly once: when the events are drained, or after a safety timeout, because
 * iOS penalises an app that never calls it. If the upload turns out unfinished, the item's lease
 * expiry re-hands it to the transport.
 *
 * @since 1.5.0
 */
public object BackgroundUploadRelaunch {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport: MutableStateFlow<BackgroundSessionUploadTransport?> = MutableStateFlow(null)

    internal fun register(created: UploadTransport) {
        transport.value = created as BackgroundSessionUploadTransport
    }

    /**
     * Handles one relaunch delivery for the session [identifier]. [completion] runs exactly once, off the
     * main queue.
     *
     * Waits up to ten seconds for [createBackgroundUploadTransport] to have run in this process — your
     * app's start-up creates it on every launch path, including this one — and completes at once if it
     * does not appear or does not own [identifier].
     */
    public fun handleEvents(identifier: String, completion: () -> Unit) {
        val once = OnceGuard(completion)
        scope.launch {
            delay(COMPLETION_SAFETY_TIMEOUT)
            once.fire()
        }
        scope.launch {
            val live: BackgroundSessionUploadTransport? =
                withTimeoutOrNull(TRANSPORT_WAIT) { transport.filterNotNull().first() }
            val itemId: String? = live?.itemIdOf(identifier)
            if (live == null || itemId == null) {
                once.fire()
                return@launch
            }
            live.handleRelaunchEvents(itemId) { once.fire() }
        }
    }

    private val TRANSPORT_WAIT: Duration = 10.seconds

    /** Ceiling on holding the OS completion handler; events normally drain well within it. */
    private val COMPLETION_SAFETY_TIMEOUT: Duration = 25.seconds
}

/** Runs [action] at most once, whichever of several racing callers gets there first. */
internal class OnceGuard(private val action: () -> Unit) {
    private val fired = AtomicInt(0)

    /** @return whether this call ran the action. */
    fun fire(): Boolean {
        if (!fired.compareAndSet(expected = 0, newValue = 1)) return false
        action()
        return true
    }
}

internal class BackgroundSessionUploadTransport(
    private val identifierPrefix: String,
    private val config: BackgroundUploadConfig,
    private val logger: Logger,
) : UploadTransport {

    // A failure in a settlement coroutine is logged, never allowed to terminate the process.
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
            logger.e(failure) { "Background upload coroutine failed." }
        },
    )

    /** One live session per item in this process; the delegate removes its own entry when it completes. */
    private val liveLock = NSLock()
    private val liveUploads: MutableMap<String, LiveUpload> = mutableMapOf()

    override val leaseMillis: Long = config.lease.inWholeMilliseconds

    /** The item id carried by [identifier], or `null` for a session this transport does not own. */
    fun itemIdOf(identifier: String): String? =
        identifier.takeIf { it.startsWith(identifierPrefix) }?.removePrefix(identifierPrefix)?.takeIf { it.isNotEmpty() }

    override fun launch(itemId: String, request: UploadRequest) {
        launch(itemId, isRehandOff = false, request = request)
    }

    override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
        val (live: LiveUpload, created: Boolean) = obtainLive(itemId)
        if (!created) {
            logger.i { "Upload hand-off joins the live session — item=$itemId" }
            return
        }
        live.session.getTasksWithCompletionHandler { dataTasks, uploadTasks, downloadTasks ->
            val running: Int = (dataTasks?.size ?: 0) + (uploadTasks?.size ?: 0) + (downloadTasks?.size ?: 0)
            when {
                running > 0 -> logger.i { "Rejoined a running upload — item=$itemId" }
                isRehandOff -> scope.launch {
                    delay(config.rehandFlushWindow)
                    if (!live.delegate.receivedAnyEvent) startUpload(live, itemId, request)
                }

                else -> startUpload(live, itemId, request)
            }
        }
    }

    override fun cancelAll() {
        // Invalidated while holding the lock, so a concurrent launch for the same item blocks until the old
        // session is gone — never two live sessions with one identifier, which Apple leaves undefined.
        liveLock.lock()
        try {
            liveUploads.values.forEach { live ->
                // Cancelled tasks still report completion; marked first, they settle nothing and spend no
                // retry budget.
                live.delegate.cancelled = true
                live.session.invalidateAndCancel()
                removeTemporaryBody(live.delegate.itemId)
            }
            liveUploads.clear()
        } finally {
            liveLock.unlock()
        }
    }

    /** Drains what a dead process left behind for [itemId]; [onDone] fires once the events are drained. */
    fun handleRelaunchEvents(itemId: String, onDone: () -> Unit) {
        val (live: LiveUpload, created: Boolean) = obtainLive(itemId)
        if (created) logger.i { "Relaunch settlement — item=$itemId" }
        live.delegate.notifyWhenDrained {
            onDone()
            releaseIfIdle(itemId, live)
        }
        // The drained signal may never come; a session left open with nothing running would make every later
        // hand-off of this item join it and upload nothing.
        scope.launch {
            delay(RELAUNCH_IDLE_CHECK)
            releaseIfIdle(itemId, live)
        }
    }

    /** The live session for [itemId], created under the lock if absent; and whether this call created it. */
    private fun obtainLive(itemId: String): Pair<LiveUpload, Boolean> {
        liveLock.lock()
        try {
            liveUploads[itemId]?.let { existing -> return existing to false }
            val configuration: NSURLSessionConfiguration =
                NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(identifierPrefix + itemId)
            configuration.allowsCellularAccess = true
            configuration.sessionSendsLaunchEvents = true
            configuration.discretionary = false
            val delegate = UploadSessionDelegate(
                itemId = itemId,
                scope = scope,
                logger = logger,
                onTerminal = ::releaseAfterTerminal,
            )
            val session: NSURLSession = NSURLSession.sessionWithConfiguration(
                configuration = configuration,
                delegate = delegate,
                delegateQueue = null,
            )
            val live = LiveUpload(session, delegate)
            liveUploads[itemId] = live
            return live to true
        } finally {
            liveLock.unlock()
        }
    }

    /**
     * Forgets [delegate]'s session and its body file — unless a newer session for the same item has taken
     * the slot since, whose body file the same path now holds.
     */
    private fun releaseAfterTerminal(delegate: UploadSessionDelegate) {
        liveLock.lock()
        try {
            val current: LiveUpload? = liveUploads[delegate.itemId]
            if (current != null && current.delegate !== delegate) return
            liveUploads.remove(delegate.itemId)
            removeTemporaryBody(delegate.itemId)
        } finally {
            liveLock.unlock()
        }
    }

    /** Invalidates and forgets [live] when it has no task and never saw a completion. */
    private fun releaseIfIdle(itemId: String, live: LiveUpload) {
        if (live.delegate.terminalDelivered) return
        live.session.getTasksWithCompletionHandler { dataTasks, uploadTasks, downloadTasks ->
            val running: Int = (dataTasks?.size ?: 0) + (uploadTasks?.size ?: 0) + (downloadTasks?.size ?: 0)
            if (running > 0 || live.delegate.terminalDelivered || live.delegate.receivedAnyEvent) return@getTasksWithCompletionHandler
            liveLock.lock()
            try {
                if (liveUploads[itemId] === live) liveUploads.remove(itemId)
            } finally {
                liveLock.unlock()
            }
            logger.i { "Released an idle relaunch session — item=$itemId" }
            live.session.finishTasksAndInvalidate()
        }
    }

    private fun startUpload(live: LiveUpload, itemId: String, request: UploadRequest) {
        val url: NSURL = NSURL.URLWithString(request.url) ?: run {
            failBeforeStart(live, itemId, "malformed url: ${request.url}")
            return
        }
        val boundary = "kmptoolkit-uploader-${NSUUID().UUIDString}"
        val bodyPath: String = temporaryBodyPath(itemId)
        writeMultipartBody(bodyPath, boundary, request.fields)?.let { failure ->
            failBeforeStart(live, itemId, failure)
            return
        }

        val urlRequest: NSMutableURLRequest = NSMutableURLRequest.requestWithURL(url)
        urlRequest.setHTTPMethod(request.method)
        request.headers.forEach { (name, value) -> urlRequest.setValue(value, forHTTPHeaderField = name) }
        urlRequest.setValue("multipart/form-data; boundary=$boundary", forHTTPHeaderField = "Content-Type")
        logger.i { "Starting upload — item=$itemId" }
        live.session.uploadTaskWithRequest(urlRequest, fromFile = NSURL.fileURLWithPath(bodyPath)).resume()
    }

    private fun failBeforeStart(live: LiveUpload, itemId: String, message: String) {
        logger.e { "Upload cannot start — item=$itemId: $message" }
        // No task was created, so no completion will come to settle and release it; do both here. Marked
        // cancelled first, the invalidation below reports nothing of its own.
        live.delegate.cancelled = true
        live.session.invalidateAndCancel()
        scope.launch {
            if (!UploadGateway.complete(itemId, UploadResult.TransportFailure(message))) {
                logger.w { "Upload failure did not reach an engine — item=$itemId" }
            }
            releaseAfterTerminal(live.delegate)
        }
    }

    private class LiveUpload(val session: NSURLSession, val delegate: UploadSessionDelegate)

    private companion object {
        val RELAUNCH_IDLE_CHECK: Duration = 30.seconds
    }
}

/**
 * Writes the multipart body for [fields] to [bodyPath], streaming each file in chunks so a large source is
 * never held in memory.
 *
 * @return `null` on success; otherwise why the body could not be written, with nothing left at [bodyPath]
 *   — the body may embed a user's recording.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writeMultipartBody(bodyPath: String, boundary: String, fields: List<UploadField>): String? {
    fields.filterIsInstance<UploadField.File>().firstOrNull { !NSFileManager.defaultManager.isReadableFileAtPath(it.path) }
        ?.let { missing -> return "source file missing/unreadable: ${missing.path}" }
    val output: NSOutputStream = NSOutputStream.outputStreamToFileAtPath(bodyPath, append = false)
    output.open()
    val failure: String? = try {
        writeMultipartParts(output, boundary, fields)
    } finally {
        output.close()
    }
    if (failure != null) removeFile(bodyPath)
    return failure
}

@OptIn(ExperimentalForeignApi::class)
private fun writeMultipartParts(output: NSOutputStream, boundary: String, fields: List<UploadField>): String? {
    val writeFailure = "cannot write the upload body to temporary storage"
    for (field in fields) {
        if (!output.writeText("--$boundary\r\n")) return writeFailure
        when (field) {
            is UploadField.Text -> {
                val part = "Content-Disposition: form-data; name=\"${field.name}\"\r\n\r\n${field.value}"
                if (!output.writeText(part)) return writeFailure
            }

            is UploadField.File -> {
                val header = "Content-Disposition: form-data; name=\"${field.name}\"; filename=\"${field.fileName}\"\r\n" +
                    "Content-Type: ${field.contentType}\r\n\r\n"
                if (!output.writeText(header)) return writeFailure
                val input: NSInputStream = NSInputStream.inputStreamWithFileAtPath(field.path)
                    ?: return "source file missing/unreadable: ${field.path}"
                input.open()
                val copied: Boolean = try {
                    output.copyFrom(input)
                } finally {
                    input.close()
                }
                if (!copied) return "source file unreadable or body unwritable: ${field.path}"
            }
        }
        if (!output.writeText("\r\n")) return writeFailure
    }
    return if (output.writeText("--$boundary--\r\n")) null else writeFailure
}

/** Copies [input] to its end; `false` when either stream fails. */
@OptIn(ExperimentalForeignApi::class)
private fun NSOutputStream.copyFrom(input: NSInputStream): Boolean {
    val buffer = ByteArray(COPY_CHUNK_BYTES)
    return buffer.usePinned { pinned ->
        val pointer: CPointer<UByteVar> = pinned.addressOf(0).reinterpret()
        var read: Long = input.read(pointer, COPY_CHUNK_BYTES.convert()).convert()
        while (read > 0L && writeFully(pointer, read)) {
            read = input.read(pointer, COPY_CHUNK_BYTES.convert()).convert()
        }
        read == 0L
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSOutputStream.writeText(text: String): Boolean {
    val bytes: ByteArray = text.encodeToByteArray()
    if (bytes.isEmpty()) return true
    return bytes.usePinned { pinned -> writeFully(pinned.addressOf(0).reinterpret(), bytes.size.toLong()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSOutputStream.writeFully(bytes: CPointer<UByteVar>, length: Long): Boolean {
    var offset = 0L
    while (offset < length) {
        val written: Long = write(bytes + offset, (length - offset).convert()).convert()
        if (written <= 0L) return false
        offset += written
    }
    return true
}

private fun removeTemporaryBody(itemId: String) {
    removeFile(temporaryBodyPath(itemId))
}

@OptIn(ExperimentalForeignApi::class)
private fun removeFile(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}

internal fun temporaryBodyPath(itemId: String): String = NSTemporaryDirectory() + "/kmptoolkit_upload_$itemId.tmp"

private const val COPY_CHUNK_BYTES: Int = 64 * 1024

private const val FALLBACK_BUNDLE_ID: String = "io.github.jamal_wia.kmptoolkit.uploader.unbundled"

// Top-level: the delegate subclasses NSObject, and Kotlin/Native forbids a companion with fields there.
private const val STATUS_UNKNOWN: Long = -1L

/**
 * Reports one item's raw outcome through [UploadGateway], in whatever process iOS delivers
 * `didCompleteWithError` — the terminal callback, which also carries transport errors and empty
 * responses. All policy runs in the engine.
 */
private class UploadSessionDelegate(
    val itemId: String,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val onTerminal: (UploadSessionDelegate) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {

    /** Any event at all tells a re-hand's flush window not to start a fresh upload. */
    @Volatile
    var receivedAnyEvent: Boolean = false

    /** Set when the session was cancelled on purpose: its completion settles nothing. */
    @Volatile
    var cancelled: Boolean = false

    /** Set synchronously by the completion callback, before its settlement runs. */
    @Volatile
    var terminalDelivered: Boolean = false

    @Volatile
    private var terminalHandled: Boolean = false

    @Volatile
    private var onAllEventsDelivered: (() -> Unit)? = null

    /**
     * Progress goes through one conflated channel and one forwarder, so fractions reach the handler in order
     * and the forwarder is joined before the settlement — never a progress call after the item settled.
     */
    private val fractions: Channel<Float> = Channel(Channel.CONFLATED)
    private val forwarder: Job = scope.launch { for (fraction in fractions) UploadGateway.progress(itemId, fraction) }

    private val progress: WholePercentProgress = WholePercentProgress(totalBytes = PERCENT_BASIS) { fraction ->
        fractions.trySend(fraction)
    }

    /** Chains [callback] to "the buffered events are drained"; fires at once if they already are. */
    fun notifyWhenDrained(callback: () -> Unit) {
        onAllEventsDelivered = callback
        if (terminalHandled) fireDrained()
    }

    private fun fireDrained() {
        val callback: (() -> Unit)? = onAllEventsDelivered
        onAllEventsDelivered = null
        callback?.invoke()
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        receivedAnyEvent = true // the body is unused; the status arrives with completion
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didSendBodyData: Long,
        totalBytesSent: Long,
        totalBytesExpectedToSend: Long,
    ) {
        receivedAnyEvent = true
        if (totalBytesExpectedToSend <= 0L) return
        progress.set(totalBytesSent * PERCENT_BASIS / totalBytesExpectedToSend)
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        fireDrained()
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        receivedAnyEvent = true
        terminalDelivered = true
        val statusCode: Long = (task.response as? NSHTTPURLResponse)?.statusCode ?: STATUS_UNKNOWN
        val outcome: UploadResult = when {
            didCompleteWithError != null -> UploadResult.TransportFailure(didCompleteWithError.localizedDescription)
            statusCode == STATUS_UNKNOWN -> UploadResult.TransportFailure("no HTTP response")
            else -> UploadResult.Completed(statusCode.toInt())
        }
        val wasCancelled: Boolean = cancelled
        logger.i { "Upload completed — item=$itemId, outcome=$outcome, cancelled=$wasCancelled" }
        scope.launch {
            fractions.close()
            forwarder.join()
            if (!wasCancelled && !UploadGateway.complete(itemId, outcome)) {
                // The row stays in flight and the lease expiry re-hands it.
                logger.w { "Upload outcome did not reach an engine — item=$itemId" }
            }
            onTerminal(this@UploadSessionDelegate)
            // The terminal event was the buffered work: once it is settled, the OS completion handler need
            // not wait for the safety timeout — DidFinishEvents may never come after invalidation.
            terminalHandled = true
            fireDrained()
        }
        session.invalidateAndCancel()
    }
}

/** Progress is quantized against a fixed basis, so byte totals above Int range stay exact. */
private const val PERCENT_BASIS: Long = 10_000L
