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
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import platform.Foundation.NSLock
import platform.Foundation.NSMutableData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.appendBytes
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.writeToFile
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

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One live session per item in this process; the delegate removes its entry when it completes. */
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
                    if (!live.delegate.receivedAnyEvent) startUpload(live.session, itemId, request)
                }

                else -> startUpload(live.session, itemId, request)
            }
        }
    }

    override fun cancelAll() {
        // Invalidated while holding the lock, so a concurrent launch for the same item blocks until the old
        // session is gone — never two live sessions with one identifier, which Apple leaves undefined.
        liveLock.lock()
        try {
            liveUploads.values.forEach { live ->
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
        live.delegate.notifyWhenDrained(onDone)
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
                onTerminal = {
                    removeTemporaryBody(itemId)
                    releaseSession(itemId)
                },
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

    private fun releaseSession(itemId: String) {
        liveLock.lock()
        try {
            liveUploads.remove(itemId)
        } finally {
            liveLock.unlock()
        }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun startUpload(session: NSURLSession, itemId: String, request: UploadRequest) {
        // The URL is parsed before anything is written, so an early return leaves no body — which may
        // embed a user's recording — behind in tmp.
        val url: NSURL = NSURL.URLWithString(request.url) ?: run {
            failBeforeStart(session, itemId, "malformed url: ${request.url}")
            return
        }
        val boundary = "kmptoolkit-uploader-${NSUUID().UUIDString}"
        val body = NSMutableData()
        for (field in request.fields) {
            body.appendString("--$boundary\r\n")
            when (field) {
                is UploadField.Text -> {
                    body.appendString("Content-Disposition: form-data; name=\"${field.name}\"\r\n\r\n")
                    body.appendString(field.value)
                }

                is UploadField.File -> {
                    val fileData: NSData = NSData.dataWithContentsOfFile(field.path) ?: run {
                        failBeforeStart(session, itemId, "source file missing/unreadable: ${field.path}")
                        return
                    }
                    body.appendString(
                        "Content-Disposition: form-data; name=\"${field.name}\"; filename=\"${field.fileName}\"\r\n",
                    )
                    body.appendString("Content-Type: ${field.contentType}\r\n\r\n")
                    body.appendBytes(fileData.bytes, fileData.length)
                }
            }
            body.appendString("\r\n")
        }
        body.appendString("--$boundary--\r\n")

        val bodyPath: String = temporaryBodyPath(itemId)
        body.writeToFile(bodyPath, atomically = true)

        val urlRequest: NSMutableURLRequest = NSMutableURLRequest.requestWithURL(url)
        urlRequest.setHTTPMethod(request.method)
        request.headers.forEach { (name, value) -> urlRequest.setValue(value, forHTTPHeaderField = name) }
        urlRequest.setValue("multipart/form-data; boundary=$boundary", forHTTPHeaderField = "Content-Type")
        logger.i { "Starting upload — item=$itemId" }
        session.uploadTaskWithRequest(urlRequest, fromFile = NSURL.fileURLWithPath(bodyPath)).resume()
    }

    private fun failBeforeStart(session: NSURLSession, itemId: String, message: String) {
        logger.e { "Upload cannot start — item=$itemId: $message" }
        scope.launch {
            UploadGateway.complete(itemId, UploadResult.TransportFailure(message))
            releaseSession(itemId)
        }
        session.invalidateAndCancel()
    }

    private class LiveUpload(val session: NSURLSession, val delegate: UploadSessionDelegate)
}

@OptIn(ExperimentalForeignApi::class)
private fun removeTemporaryBody(itemId: String) {
    NSFileManager.defaultManager.removeItemAtPath(temporaryBodyPath(itemId), error = null)
}

internal fun temporaryBodyPath(itemId: String): String = NSTemporaryDirectory() + "/kmptoolkit_upload_$itemId.tmp"

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private fun NSMutableData.appendString(string: String) {
    val data: NSData = NSString.create(string = string).dataUsingEncoding(NSUTF8StringEncoding) ?: return
    appendBytes(data.bytes, data.length)
}

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
    private val onTerminal: () -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {

    /** Any event at all tells a re-hand's flush window not to start a fresh upload. */
    @Volatile
    var receivedAnyEvent: Boolean = false

    @Volatile
    private var terminalHandled: Boolean = false

    @Volatile
    private var onAllEventsDelivered: (() -> Unit)? = null

    private val progress: WholePercentProgress = WholePercentProgress(totalBytes = PERCENT_BASIS) { fraction ->
        // Best effort: a settled row drops the signal engine-side, so a late progress coroutine cannot
        // resurrect a finished upload.
        scope.launch { UploadGateway.progress(itemId, fraction) }
    }

    /** Chains [callback] to "the buffered events are drained"; fires at once if they already are. */
    fun notifyWhenDrained(callback: () -> Unit) {
        onAllEventsDelivered = callback
        if (terminalHandled) fireDrained()
    }

    private fun fireDrained() {
        onAllEventsDelivered?.invoke()
        onAllEventsDelivered = null
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
        val statusCode: Long = (task.response as? NSHTTPURLResponse)?.statusCode ?: STATUS_UNKNOWN
        val outcome: UploadResult = when {
            didCompleteWithError != null -> UploadResult.TransportFailure(didCompleteWithError.localizedDescription)
            statusCode == STATUS_UNKNOWN -> UploadResult.TransportFailure("no HTTP response")
            else -> UploadResult.Completed(statusCode.toInt())
        }
        logger.i { "Upload completed — item=$itemId, outcome=$outcome" }
        scope.launch {
            if (!UploadGateway.complete(itemId, outcome)) {
                // The row stays in flight and the lease expiry re-hands it.
                logger.w { "Upload outcome did not reach an engine — item=$itemId" }
            }
            onTerminal()
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
