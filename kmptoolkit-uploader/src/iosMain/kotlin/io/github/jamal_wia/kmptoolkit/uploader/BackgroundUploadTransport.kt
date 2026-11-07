package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Duration.Companion.days
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
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSInputStream
import platform.Foundation.NSLock
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMutableData
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
import platform.Foundation.appendData
import platform.Foundation.inputStreamWithFileAtPath
import platform.Foundation.outputStreamToFileAtPath
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.subdataWithRange
import platform.darwin.NSObject
import platform.posix.memcpy

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
 * @param requestTimeout the session's `timeoutIntervalForRequest`: an **idle** timeout — how long the
 *   transfer may go without sending or receiving a single byte, not a bound on the whole upload. The
 *   default, 60 seconds, is the system's own. A background session does not fail on this timeout; iOS
 *   silently retries until [resourceTimeout] runs out. Must be positive and finite.
 * @param resourceTimeout the session's `timeoutIntervalForResource`: how long the whole transfer, retries
 *   after an idle timeout included, may take before iOS fails it. The default, 7 days, is the system's
 *   own. It therefore bounds how long an upload may be re-sent; keep [lease] below it. Must be positive and finite.
 * @since 1.5.0 ([requestTimeout] and [resourceTimeout] since 3.1.0)
 */
public class BackgroundUploadConfig(
    public val sessionIdentifierPrefix: String? = null,
    public val lease: Duration = 60.minutes,
    public val rehandFlushWindow: Duration = 3.seconds,
    public val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    public val resourceTimeout: Duration = DEFAULT_RESOURCE_TIMEOUT,
) {
    /**
     * The constructor before [requestTimeout] and [resourceTimeout] existed; kept so that Swift code calling
     * `BackgroundUploadConfig(sessionIdentifierPrefix:lease:rehandFlushWindow:)` still compiles. It has no
     * default arguments on purpose: `BackgroundUploadConfig()` and every call that leaves a parameter out
     * resolve to the primary constructor, and only a call that supplies exactly these three arguments
     * (positionally or by name) and no timeout lands here, with a deprecation warning.
     *
     * Removing its defaults (3.0.0 had them) is a deliberate, recorded trade-off: a library compiled against
     * 3.0.0 that calls it with arguments omitted must be recompiled against 3.1.0.
     */
    @Deprecated(
        "Superseded by the constructor with requestTimeout and resourceTimeout.",
        ReplaceWith(
            "BackgroundUploadConfig(sessionIdentifierPrefix, lease, rehandFlushWindow, " +
                "requestTimeout = 60.seconds, resourceTimeout = 7.days)",
            "kotlin.time.Duration.Companion.seconds",
            "kotlin.time.Duration.Companion.days",
        ),
        level = DeprecationLevel.WARNING,
    )
    public constructor(
        sessionIdentifierPrefix: String?,
        lease: Duration,
        rehandFlushWindow: Duration,
    ) : this(sessionIdentifierPrefix, lease, rehandFlushWindow, DEFAULT_REQUEST_TIMEOUT, DEFAULT_RESOURCE_TIMEOUT)

    init {
        require(sessionIdentifierPrefix == null || sessionIdentifierPrefix.isNotBlank()) {
            "sessionIdentifierPrefix must be null or non-blank, was '$sessionIdentifierPrefix'."
        }
        require(lease.isPositive()) { "lease must be positive, was $lease." }
        require(!rehandFlushWindow.isNegative()) { "rehandFlushWindow must not be negative, was $rehandFlushWindow." }
        require(requestTimeout.isPositive() && requestTimeout.isFinite()) {
            "requestTimeout must be positive and finite, was $requestTimeout."
        }
        require(resourceTimeout.isPositive() && resourceTimeout.isFinite()) {
            "resourceTimeout must be positive and finite, was $resourceTimeout."
        }
    }

    override fun equals(other: Any?): Boolean =
        other is BackgroundUploadConfig &&
            sessionIdentifierPrefix == other.sessionIdentifierPrefix &&
            lease == other.lease &&
            rehandFlushWindow == other.rehandFlushWindow &&
            requestTimeout == other.requestTimeout &&
            resourceTimeout == other.resourceTimeout

    override fun hashCode(): Int {
        var result: Int = sessionIdentifierPrefix.hashCode()
        result = 31 * result + lease.hashCode()
        result = 31 * result + rehandFlushWindow.hashCode()
        result = 31 * result + requestTimeout.hashCode()
        result = 31 * result + resourceTimeout.hashCode()
        return result
    }

    override fun toString(): String =
        "BackgroundUploadConfig(sessionIdentifierPrefix=$sessionIdentifierPrefix, lease=$lease, " +
            "rehandFlushWindow=$rehandFlushWindow, requestTimeout=$requestTimeout, " +
            "resourceTimeout=$resourceTimeout)"

    internal companion object {
        /** The system's `timeoutIntervalForRequest` default. */
        val DEFAULT_REQUEST_TIMEOUT: Duration = 60.seconds

        /** The system's `timeoutIntervalForResource` default. */
        val DEFAULT_RESOURCE_TIMEOUT: Duration = 7.days

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
 * **Idempotent launch.** An item whose upload an earlier hand-off in this process started is joined. A
 * session whose upload already completed is replaced by a fresh one rather than joined, and one that only
 * a relaunch opened is taken over and checked for a running task like a session of the hand-off's own. A
 * re-hand in a new process rejoins a task the daemon is still running. A re-hand that finds nothing running waits
 * [BackgroundUploadConfig.rehandFlushWindow] for buffered completion events before starting afresh.
 *
 * **Cancellation by item.** [UploadTransport.cancel] stops that one item's session and nothing else.
 * An upload an earlier process started is reached through its session identifier, which carries the
 * item id, so a cancel after a relaunch still finds it.
 *
 * The multipart body is written to a temporary file first — a background upload must come from a
 * file — and removed when the upload completes. It is rebuilt on every hand-off.
 *
 * Create it once per process, before the engine starts; the relaunch entry point finds it through a
 * process-wide registration. A second call replaces that registration with the new transport and does not
 * stop the first one, which keeps the sessions it owns.
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
    private val fired: CompletableDeferred<Unit> = CompletableDeferred()

    /** @return whether this call ran the action. */
    fun fire(): Boolean {
        if (!fired.complete(Unit)) return false
        action()
        return true
    }
}

internal class BackgroundSessionUploadTransport(
    private val identifierPrefix: String,
    private val config: BackgroundUploadConfig,
    private val logger: Logger,
    private val journal: OutcomeJournal = iosOutcomeJournal(logger),
    private val sessionFactory: UploadSessionFactory = NsUrlUploadSessionFactory(config),
    /** How long a hand-off waits for the previous session of the same identifier to report itself invalid. */
    private val invalidationWait: Duration = DEFAULT_INVALIDATION_WAIT,
    /** Called after a hand-off wrote its body and before it takes the lock to create the task. Test seam. */
    private val onBodyWritten: (String) -> Unit = {},
) : UploadTransport {

    // A failure in a settlement coroutine is logged, never allowed to terminate the process.
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
            logger.e(failure) { "Background upload coroutine failed." }
        },
    )

    /** One live session per item in this process; the delegate removes its own entry when it completes. */
    // NSLock, not a coroutine Mutex: taken from NSURLSession delegate callbacks and the non-suspending
    // launch()/cancel(), around Objective-C session calls that must be atomic with these maps.
    private val liveLock = NSLock()
    private val liveUploads: MutableMap<String, LiveUpload> = mutableMapOf()

    /**
     * The latest session created per item whose invalidation has not been reported yet (removed by
     * `didBecomeInvalidWithError`). `invalidateAndCancel` only REQUESTS invalidation; two live background
     * sessions with one identifier are undefined, so a hand-off creating the next session waits for this one.
     */
    private val invalidating: MutableMap<String, UploadSessionDelegate> = mutableMapOf()

    /**
     * Hand-offs deferred behind an invalidating predecessor, per item, each as an identity token. A [cancel] clears
     * the item's entry, so a deferred hand-off that wakes afterwards finds its token gone and starts nothing.
     * Read and written under [liveLock] only.
     */
    private val pendingHandOffs: MutableMap<String, MutableList<Any>> = mutableMapOf()

    override val leaseMillis: Long = config.lease.inWholeMilliseconds

    init {
        // Outcomes a dead process journaled and nobody collected: replay them to the engine, or delete the
        // expired ones. Once per transport, so once per process; the engine may not be up yet, which the
        // gateway waits for.
        scope.launch { journal.sweep(gatewaySettle(UploadGateway.DEFAULT_ENGINE_WAIT)) }
    }

    /** The item id carried by [identifier], or `null` for a session this transport does not own. */
    fun itemIdOf(identifier: String): String? =
        identifier.takeIf { it.startsWith(identifierPrefix) }?.removePrefix(identifierPrefix)?.takeIf { it.isNotEmpty() }

    override fun launch(itemId: String, request: UploadRequest) {
        launch(itemId, isRehandOff = false, request = request)
    }

    override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
        // First of all: an outcome this item's earlier upload journaled is replayed INSTEAD of uploading — the
        // session it ran on is gone and its events will not come again. Nothing is started, not even a session.
        if (replayJournaled(itemId, isRehandOff)) return
        handOff(itemId, isRehandOff, request)
    }

    private fun handOff(itemId: String, isRehandOff: Boolean, request: UploadRequest, deferredToken: Any? = null) {
        val live: LiveUpload = when (val claim: HandOffClaim = claimForHandOff(itemId, deferredToken)) {
            HandOffClaim.Joined -> {
                logger.i { "Upload hand-off joins the live session — item=$itemId" }
                return
            }

            HandOffClaim.Withdrawn -> {
                logger.i { "A deferred hand-off was cancelled before it started — item=$itemId" }
                return
            }

            is HandOffClaim.AwaitPredecessor -> {
                // Bounded: the invalidation callback could in theory never come.
                scope.launch {
                    val invalidated: Unit? = withTimeoutOrNull(invalidationWait) { claim.predecessor.invalidated.await() }
                    if (invalidated == null) {
                        logger.w { "The previous session was not reported invalid in time; continuing — item=$itemId" }
                        forgetInvalidating(itemId, claim.predecessor)
                        claim.predecessor.closeProgress()
                    }
                    handOff(itemId, isRehandOff, request, claim.token)
                }
                return
            }

            is HandOffClaim.Claimed -> claim.live
        }
        live.session.getTaskCount { running ->
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

    override fun cancel(itemId: String) {
        // A cancelled item settles nothing, so an outcome journaled for it must not be replayed later.
        journal.delete(itemId)
        // Under the lock, so a concurrent launch for the same item either sees the session gone or is
        // joined to the one being cancelled — never two live sessions with one identifier, which Apple
        // leaves undefined — and so startUpload cannot create a task on a session invalidated here.
        val orphan: LiveUpload?
        liveLock.lock()
        try {
            // Withdraws every hand-off still waiting for a predecessor's invalidation.
            pendingHandOffs.remove(itemId)
            val live: LiveUpload? = liveUploads.remove(itemId)
            if (live != null) {
                // Cancelled tasks still report completion; marked first, they settle nothing and spend no
                // retry budget.
                live.delegate.cancelled = true
                live.session.invalidateAndCancel()
                // A session cancelled before it had a task never gets a completion to close its progress pipe.
                live.delegate.closeProgress()
                removeTemporaryBody(itemId)
                orphan = null
            } else if (invalidating.containsKey(itemId)) {
                // The slot is empty because this process's own session for the item is still invalidating
                // (invalidateAndCancel already cancelled its tasks): no earlier-process task can sit under the
                // identifier, so no orphan session is opened beside the one being invalidated.
                removeTemporaryBody(itemId)
                orphan = null
            } else {
                // Not live here — but nsurlsessiond may still be running a transfer an earlier process
                // started. The identifier carries the item id, so the session can be reattached by name.
                orphan = createLive(itemId).also { it.delegate.cancelled = true }
                liveUploads[itemId] = orphan
            }
        } finally {
            liveLock.unlock()
        }
        if (orphan == null) {
            logger.i { "Cancelled the upload — item=$itemId" }
            return
        }
        orphan.session.cancelTasks { cancelledCount ->
            liveLock.lock()
            try {
                orphan.session.invalidateAndCancel()
                // No task of this process ever ran on the orphan session, so no completion will close it.
                orphan.delegate.closeProgress()
                if (liveUploads[itemId] === orphan) liveUploads.remove(itemId)
                removeTemporaryBody(itemId)
            } finally {
                liveLock.unlock()
            }
            logger.i { "Cancelled $cancelledCount upload task(s) left by an earlier process — item=$itemId" }
        }
    }

    /**
     * Replays the journaled outcome of [itemId], if a usable one exists and this is a re-hand.
     *
     * A lost settlement always leaves the item in flight until its lease expires, so only a re-hand can be
     * owed a replay: for a first hand-off an entry is stale, and is deleted so that it cannot stand in for
     * the upload this hand-off was made for.
     *
     * @return whether a replay was started — the caller then starts nothing. An unusable entry is deleted by
     *   the inspection and reads as none.
     */
    private fun replayJournaled(itemId: String, isRehandOff: Boolean): Boolean {
        val entry: JournalEntry = journal.inspect(itemId)
        if (entry !is JournalEntry.Valid) return false
        if (!isRehandOff) {
            logger.i { "Discarded a stale journaled outcome at a first hand-off — item=$itemId" }
            journal.delete(itemId)
            return false
        }
        logger.i { "Upload hand-off replays the journaled outcome instead of uploading — item=$itemId" }
        scope.launch {
            // An engine that is not reachable keeps the entry; the lease expiry re-hands the item and lands here again.
            journal.replay(itemId, gatewaySettle(UploadGateway.DEFAULT_ENGINE_WAIT))
        }
        return true
    }

    /** Drains what a dead process left behind for [itemId]; [onDone] fires once the events are drained. */
    fun handleRelaunchEvents(itemId: String, onDone: () -> Unit) {
        // The upload already finished and was journaled by an earlier delivery: replay it; there is nothing
        // buffered to drain, and no session to reopen.
        val entry: JournalEntry = journal.inspect(itemId)
        if (entry is JournalEntry.Valid) {
            scope.launch {
                try {
                    journal.replay(itemId, gatewaySettle(UploadGateway.DEFAULT_ENGINE_WAIT))
                } finally {
                    onDone()
                }
            }
            return
        }
        val (live: LiveUpload, created: Boolean) = obtainLive(itemId, newWake = true)
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

    /**
     * The session a hand-off of [itemId] runs on, decided under the lock; `null` when the hand-off joins
     * one an earlier hand-off in this process already owns, whose task or start is already under way.
     *
     * Only such a session may be joined. Two others can sit in the slot, and joining either would start
     * nothing while the engine counts the item as handed off — in flight, uploading nothing, until its
     * lease expires:
     * - one whose terminal event was delivered: that upload is over and its session invalidated, so it
     *   is replaced by a fresh one;
     * - one a relaunch opened only to drain buffered events: this hand-off takes it over, marked so that
     *   [releaseIfIdle] no longer invalidates it from under the hand-off, and checks its tasks exactly as
     *   it would a session of its own — rejoining one still running, or starting afresh.
     */
    private fun claimForHandOff(itemId: String, deferredToken: Any?): HandOffClaim {
        liveLock.lock()
        try {
            if (deferredToken != null) {
                // A cancel since this hand-off was deferred removed its token.
                val pending: MutableList<Any>? = pendingHandOffs[itemId]
                if (pending == null || !pending.remove(deferredToken)) return HandOffClaim.Withdrawn
                if (pending.isEmpty()) pendingHandOffs.remove(itemId)
            }
            val existing: LiveUpload? = liveUploads[itemId]
            if (existing != null && !existing.delegate.terminalDelivered) {
                if (existing.handedOff) return HandOffClaim.Joined
                existing.handedOff = true
                return HandOffClaim.Claimed(existing)
            }
            // A replacement session must not open before its predecessor reported itself invalid.
            invalidating[itemId]?.let { predecessor ->
                val token = Any()
                pendingHandOffs.getOrPut(itemId) { mutableListOf() }.add(token)
                return HandOffClaim.AwaitPredecessor(predecessor, token)
            }
            val live: LiveUpload = createLive(itemId)
            live.handedOff = true
            liveUploads[itemId] = live
            return HandOffClaim.Claimed(live)
        } finally {
            liveLock.unlock()
        }
    }

    /** What [claimForHandOff] decided under the lock. */
    private sealed interface HandOffClaim {
        /** An earlier hand-off's session is live: nothing to start. */
        data object Joined : HandOffClaim

        /** The hand-off owns [live]. */
        class Claimed(val live: LiveUpload) : HandOffClaim

        /** The slot is free, but [predecessor]'s invalidation is still pending: wait, then claim again. */
        class AwaitPredecessor(val predecessor: UploadSessionDelegate, val token: Any) : HandOffClaim

        /** A [cancel] arrived while this hand-off was deferred: start nothing. */
        data object Withdrawn : HandOffClaim
    }

    private fun forgetInvalidating(itemId: String, delegate: UploadSessionDelegate) {
        liveLock.lock()
        try {
            if (invalidating[itemId] === delegate) invalidating.remove(itemId)
        } finally {
            liveLock.unlock()
        }
    }

    /**
     * The live session for [itemId], created under the lock if absent; and whether this call created it.
     * [newWake] starts a new drained signal on an existing session, inside the same lock.
     */
    private fun obtainLive(itemId: String, newWake: Boolean = false): Pair<LiveUpload, Boolean> {
        liveLock.lock()
        try {
            liveUploads[itemId]?.let { existing ->
                // A relaunch wake on a session that is still live: its events are not the earlier wake's.
                if (newWake) existing.delegate.startWake()
                return existing to false
            }
            val live: LiveUpload = createLive(itemId)
            liveUploads[itemId] = live
            return live to true
        } finally {
            liveLock.unlock()
        }
    }

    /** A new background session for [itemId]. The caller holds [liveLock] and records it. */
    private fun createLive(itemId: String): LiveUpload {
        val delegate = UploadSessionDelegate(
            itemId = itemId,
            scope = scope,
            logger = logger,
            journal = journal,
            terminalLock = liveLock,
            onTerminal = ::releaseAfterTerminal,
            onInvalidated = { invalidated -> forgetInvalidating(invalidated.itemId, invalidated) },
        )
        invalidating[itemId] = delegate
        return LiveUpload(sessionFactory.create(identifierPrefix + itemId, delegate), delegate)
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

    /**
     * Invalidates and forgets [live] when it has no task, never saw a completion, and no hand-off has
     * taken it over.
     */
    private fun releaseIfIdle(itemId: String, live: LiveUpload) {
        if (live.delegate.terminalDelivered) return
        live.session.getTaskCount { running ->
            if (running > 0 || live.delegate.terminalDelivered || live.delegate.receivedAnyEvent) return@getTaskCount
            liveLock.lock()
            try {
                // A hand-off that claimed the session after the task check above is about to start its
                // upload on it; invalidating now would strand that hand-off. Decided under the lock the
                // claim takes, so exactly one of the two wins.
                if (live.handedOff || liveUploads[itemId] !== live) return@getTaskCount
                liveUploads.remove(itemId)
                // Inside the lock, so a hand-off that finds the slot empty creates its session — with the
                // same identifier — only after this one is invalidated.
                live.session.finishTasksAndInvalidate()
                // The session never had a task, so no completion will close its progress pipe.
                live.delegate.closeProgress()
            } finally {
                liveLock.unlock()
            }
            logger.i { "Released an idle relaunch session — item=$itemId" }
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
        onBodyWritten(itemId)
        // Checked and created under the lock that cancel holds while invalidating: a task created on an
        // invalidated session raises an Objective-C exception.
        liveLock.lock()
        try {
            if (live.delegate.cancelled) {
                logger.i { "Upload cancelled before it started — item=$itemId" }
                removeTemporaryBody(itemId)
                return
            }
            // The session reports: the previous process's task finished (its terminal event invalidated this
            // session, so a task created now would raise) or is delivering events. Either way that task owns
            // the outcome; starting a second upload would at best repeat it. Decided here, atomically with the
            // creation, rather than a flush window ago.
            if (live.delegate.terminalDelivered || live.delegate.receivedAnyEvent) {
                logger.i { "A buffered event arrived while the body was written; no task started — item=$itemId" }
                removeTemporaryBody(itemId)
                return
            }
            logger.i { "Starting upload — item=$itemId" }
            live.session.startUploadTask(urlRequest, bodyPath)
        } finally {
            liveLock.unlock()
        }
    }

    private fun failBeforeStart(live: LiveUpload, itemId: String, message: String) {
        logger.e { "Upload cannot start — item=$itemId: $message" }
        // No task was created, so no completion will come to settle and release it; do both here. Marked
        // cancelled first, the invalidation below reports nothing of its own. Marked terminal too: the
        // failure reported below is this session's outcome, so a re-hand it triggers must start on a
        // fresh session rather than join this dead one.
        live.delegate.cancelled = true
        live.delegate.terminalDelivered = true
        live.session.invalidateAndCancel()
        // No task was created, so no completion will close the progress pipe either.
        live.delegate.closeProgress()
        scope.launch {
            if (!UploadGateway.complete(itemId, UploadResult.TransportFailure(message))) {
                logger.w { "Upload failure did not reach an engine — item=$itemId" }
            }
            releaseAfterTerminal(live.delegate)
        }
    }

    private class LiveUpload(val session: UploadSession, val delegate: UploadSessionDelegate) {

        /**
         * Whether a hand-off in this process owns this session — created it, or took over the one a
         * relaunch opened. Read and written under [liveLock] only.
         */
        var handedOff: Boolean = false
    }

    private companion object {
        val RELAUNCH_IDLE_CHECK: Duration = 30.seconds
        val DEFAULT_INVALIDATION_WAIT: Duration = 2.seconds
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

/** The slice of `NSURLSession` the transport uses; [NsUrlUploadSession] is the real one, tests script a fake. */
internal interface UploadSession {
    /** Reports how many tasks the session has, on the session's own queue. */
    fun getTaskCount(callback: (Int) -> Unit)

    /** Cancels every task the session has and reports how many there were. */
    fun cancelTasks(callback: (Int) -> Unit)

    /** Creates AND resumes an upload task for [bodyPath]; the real session raises on an invalidated one. */
    fun startUploadTask(request: NSMutableURLRequest, bodyPath: String)

    fun invalidateAndCancel()

    fun finishTasksAndInvalidate()
}

/** Opens the session of [identifier] that reports to [delegate]. */
internal fun interface UploadSessionFactory {
    fun create(identifier: String, delegate: UploadSessionDelegate): UploadSession
}

internal class NsUrlUploadSessionFactory(private val config: BackgroundUploadConfig) : UploadSessionFactory {
    override fun create(identifier: String, delegate: UploadSessionDelegate): UploadSession = NsUrlUploadSession(
        NSURLSession.sessionWithConfiguration(
            configuration = buildSessionConfiguration(identifier, config),
            delegate = delegate,
            delegateQueue = null,
        ),
    )
}

internal class NsUrlUploadSession(private val session: NSURLSession) : UploadSession {
    override fun getTaskCount(callback: (Int) -> Unit) {
        session.getTasksWithCompletionHandler { dataTasks, uploadTasks, downloadTasks ->
            callback((dataTasks?.size ?: 0) + (uploadTasks?.size ?: 0) + (downloadTasks?.size ?: 0))
        }
    }

    override fun cancelTasks(callback: (Int) -> Unit) {
        session.getTasksWithCompletionHandler { dataTasks, uploadTasks, downloadTasks ->
            val tasks: List<NSURLSessionTask> = listOfNotNull(dataTasks, uploadTasks, downloadTasks)
                .flatten()
                .filterIsInstance<NSURLSessionTask>()
            tasks.forEach { task -> task.cancel() }
            callback(tasks.size)
        }
    }

    override fun startUploadTask(request: NSMutableURLRequest, bodyPath: String) {
        session.uploadTaskWithRequest(request, fromFile = NSURL.fileURLWithPath(bodyPath)).resume()
    }

    override fun invalidateAndCancel() = session.invalidateAndCancel()

    override fun finishTasksAndInvalidate() = session.finishTasksAndInvalidate()
}

/**
 * Reports one item's raw outcome through [UploadGateway], in whatever process iOS delivers
 * `didCompleteWithError` — the terminal callback, which also carries transport errors and empty
 * responses. All policy runs in the engine.
 */
internal class UploadSessionDelegate(
    val itemId: String,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val journal: OutcomeJournal,
    private val terminalLock: NSLock,
    private val onTerminal: (UploadSessionDelegate) -> Unit,
    private val onInvalidated: (UploadSessionDelegate) -> Unit = {},
) : NSObject(), NSURLSessionDataDelegateProtocol {

    // @Volatile, not StateFlow: written from NSURLSession delegate callbacks (receivedAnyEvent on every
    // progress event), some of them without terminalLock (receivedAnyEvent, and cancelled/terminalDelivered in
    // failBeforeStart). The volatile is what makes every read, locked or not, see those writes; terminalLock
    // only orders task creation against complete()/cancel(), it does not publish these fields.

    /** Any event at all tells a re-hand's flush window not to start a fresh upload. */
    @Volatile
    var receivedAnyEvent: Boolean = false

    /** Set when the session was cancelled on purpose: its completion settles nothing. */
    @Volatile
    var cancelled: Boolean = false

    /** Set synchronously by the completion callback, before its settlement runs. */
    @Volatile
    var terminalDelivered: Boolean = false

    /**
     * Completed once the terminal event is settled. Permanent: a delegate that settled is never reused.
     */
    private val settled: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * DidFinishEvents of the current wake. A signal that lands before anyone registered is remembered, but only
     * for its own wake: [startWake] replaces a completed one, so a later relaunch wake on the same live
     * delegate waits for its own signal (or the settlement) instead of firing at once.
     */
    private val finishedEvents: MutableStateFlow<CompletableDeferred<Unit>> = MutableStateFlow(CompletableDeferred())

    /**
     * Progress goes through one conflated channel and one forwarder, so fractions reach the handler in order
     * and the forwarder is joined before the settlement — never a progress call after the item settled.
     */
    private val fractions: Channel<Float> = Channel(Channel.CONFLATED)
    internal val forwarder: Job = scope.launch { for (fraction in fractions) UploadGateway.progress(itemId, fraction) }

    private val progress: WholePercentProgress = WholePercentProgress(totalBytes = PERCENT_BASIS) { fraction ->
        fractions.trySend(fraction)
    }

    /** Completed when the session reports itself invalid (`didBecomeInvalidWithError`). */
    val invalidated: CompletableDeferred<Unit> = CompletableDeferred()

    override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) {
        reportInvalidated()
    }

    /**
     * Ends the progress pipe from the owner's side, for a session that is dropped without ever delivering a
     * terminal event (no task was created, or it was cancelled while idle): `didCompleteWithError` is sent
     * once per task, so without this the forwarder would stay suspended on the channel for the life of the
     * transport and pin this delegate. Idempotent, and a no-op once [complete] has closed the channel. The
     * forwarder drains the one conflated value that may be pending and ends; later progress is dropped.
     */
    fun closeProgress() {
        fractions.close()
    }

    /** What `didBecomeInvalidWithError` does; separate so a test double of the session can report it. */
    fun reportInvalidated() {
        // After this callback no task can run on the session, so nothing more is worth forwarding.
        closeProgress()
        onInvalidated(this)
        invalidated.complete(Unit)
    }

    /** Begins a new relaunch wake: a DidFinishEvents remembered from an earlier wake no longer counts. */
    fun startWake() {
        finishedEvents.update { current -> if (current.isCompleted) CompletableDeferred() else current }
    }

    /**
     * Chains [callback] to "the buffered events of this wake are drained" (DidFinishEvents of the current wake,
     * or the terminal event settled); fires at once if they already are. Runs exactly once however many
     * signals arrive.
     */
    fun notifyWhenDrained(callback: () -> Unit) {
        // Runs synchronously on the completing thread, or at once on this thread if already drained.
        val once = OnceGuard(callback)
        settled.invokeOnCompletion { once.fire() }
        finishedEvents.value.invokeOnCompletion { once.fire() }
    }

    /** The response body so far, kept up to the engine's ceiling plus one byte; handed over once, at completion. */
    private val responseBody = ResponseBodyBuffer()

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        receivedAnyEvent = true
        responseBody.append(didReceiveData)
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
        finishedEvents.value.complete(Unit)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val httpResponse: NSHTTPURLResponse? = task.response as? NSHTTPURLResponse
        complete(
            statusCode = httpResponse?.statusCode ?: STATUS_UNKNOWN,
            headerFields = httpResponse?.allHeaderFields,
            errorDescription = didCompleteWithError?.localizedDescription,
            invalidate = session::invalidateAndCancel,
        )
    }

    /**
     * The terminal event, however the session delivered it: [statusCode] is [STATUS_UNKNOWN] without an HTTP
     * response, [errorDescription] is set for a transport error, and [invalidate] invalidates the session.
     */
    fun complete(statusCode: Long, headerFields: Map<Any?, *>?, errorDescription: String?, invalidate: () -> Unit) {
        // Under the lock startUpload creates its task under: a hand-off that has not created its task yet sees
        // the terminal state and starts nothing, so no task is ever created on the session invalidated below —
        // which raises an Objective-C exception Kotlin/Native cannot catch. A hand-off that already created
        // its task is unaffected.
        terminalLock.lock()
        try {
            receivedAnyEvent = true
            terminalDelivered = true
        } finally {
            terminalLock.unlock()
        }
        val outcome: UploadResult = when {
            errorDescription != null -> UploadResult.TransportFailure(errorDescription)
            statusCode == STATUS_UNKNOWN -> UploadResult.TransportFailure("no HTTP response")
            else -> UploadResult.Completed(statusCode.toInt())
        }
        // Built here, before the session is invalidated below: the server's answer exists only for an
        // outcome that is a completed response, and the body is converted from the accumulated bytes once.
        val response: UploadResponse? = if (outcome is UploadResult.Completed) {
            uploadResponseOf(outcome.statusCode, headerFields, responseBody)
        } else {
            null
        }
        val wasCancelled: Boolean = cancelled
        // Written here, synchronously, before the session is invalidated and before any coroutine runs: the
        // process may be suspended or killed as soon as this callback returns, and the outcome would exist
        // nowhere else. A failed write leaves today's behaviour.
        val journaled: Boolean = !wasCancelled && response != null && journal.record(itemId, response)
        logger.i { "Upload completed — item=$itemId, outcome=$outcome, cancelled=$wasCancelled" }
        // Before the settlement is launched, not after: the settlement can return the item to the queue,
        // and a re-hand landing at once replaces this session — terminal now — with a new one under the
        // same identifier, which must not overlap a session that is not yet invalidated.
        invalidate()
        scope.launch {
            fractions.close()
            forwarder.join()
            val landed: Boolean = when {
                wasCancelled -> true
                response != null && journaled -> {
                    // Written in full, synchronously; now that an engine can say what the handler asked
                    // for, the body is cut to it before the settlement.
                    UploadGateway.responseBodyLimit(itemId, UploadGateway.DEFAULT_ENGINE_WAIT)
                        ?.let { limit -> journal.restrictBody(itemId, limit) }
                    journal.settleRecorded(itemId, response, gatewaySettle(UploadGateway.DEFAULT_ENGINE_WAIT))
                }

                response != null -> UploadGateway.complete(itemId, response)

                else -> UploadGateway.complete(itemId, outcome)
            }
            if (!landed) {
                // The row stays in flight and the lease expiry re-hands it.
                logger.w { "Upload outcome did not reach an engine — item=$itemId" }
            }
            onTerminal(this@UploadSessionDelegate)
            // The terminal event was the buffered work: once it is settled, the OS completion handler need
            // not wait for the safety timeout — DidFinishEvents may never come after invalidation.
            settled.complete(Unit)
        }
    }
}

/**
 * Accumulates a response body as the session delivers it, keeping at most [ceiling] plus one byte — one
 * more than the engine ever keeps, so a longer body is known to be longer without holding all of it. The
 * delegate's queue is serial, so it needs no lock.
 */
internal class ResponseBodyBuffer(private val ceiling: Int = MAX_RESPONSE_BODY_BYTES) {

    private val data = NSMutableData()

    @OptIn(ExperimentalForeignApi::class)
    fun append(chunk: NSData) {
        val room: Long = ceiling.toLong() + 1L - data.length.toLong()
        if (room <= 0L) return
        if (chunk.length.toLong() <= room) {
            data.appendData(chunk)
        } else {
            data.appendData(chunk.subdataWithRange(NSMakeRange(0u, room.convert())))
        }
    }

    /** What was kept: the body up to the ceiling, and whether more than that arrived. */
    @OptIn(ExperimentalForeignApi::class)
    fun toBodyAndCut(): Pair<ByteArray, Boolean> {
        val size: Int = data.length.toInt()
        if (size == 0) return ByteArray(0) to false
        val all = ByteArray(size)
        all.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return if (size > ceiling) all.copyOf(ceiling) to true else all to false
    }
}

/**
 * The [UploadResponse] for a completed answer: [headerFields] — `NSHTTPURLResponse.allHeaderFields`, whose
 * keys and values are `Any` — reduced to the entries that are strings on both sides, and the [body] kept.
 */
internal fun uploadResponseOf(statusCode: Int, headerFields: Map<Any?, *>?, body: ResponseBodyBuffer): UploadResponse {
    val headers: MutableMap<String, String> = LinkedHashMap()
    headerFields?.forEach { (name, value) ->
        if (name is String && value is String) headers[name] = value
    }
    val (bytes: ByteArray, cut: Boolean) = body.toBodyAndCut()
    return UploadResponse(statusCode, headers, bytes, cut)
}

/** Progress is quantized against a fixed basis, so byte totals above Int range stay exact. */
private const val PERCENT_BASIS: Long = 10_000L

/** The background session configuration for [identifier], with [config]'s timeouts applied. */
internal fun buildSessionConfiguration(identifier: String, config: BackgroundUploadConfig): NSURLSessionConfiguration {
    val configuration: NSURLSessionConfiguration =
        NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(identifier)
    configuration.allowsCellularAccess = true
    configuration.sessionSendsLaunchEvents = true
    configuration.discretionary = false
    configuration.timeoutIntervalForRequest = config.requestTimeout.toDouble(DurationUnit.SECONDS)
    configuration.timeoutIntervalForResource = config.resourceTimeout.toDouble(DurationUnit.SECONDS)
    return configuration
}
