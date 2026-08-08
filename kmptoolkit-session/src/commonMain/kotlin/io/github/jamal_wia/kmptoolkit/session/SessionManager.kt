package io.github.jamal_wia.kmptoolkit.session

import io.github.jamal_wia.kmptoolkit.coroutines.AppDispatchers
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Holds whether a session is open, and ends it exactly once when asked.
 *
 * The whole module is these two responsibilities. Ending a session means: call the optional
 * [SessionRevoker], run every registered [SessionCleaner], then flip [state] to
 * [SessionState.INACTIVE] — in that order, with failures reported rather than swallowed and none of
 * them able to stop the session from ending.
 *
 * Obtain an instance from `createSessionManager` and hold one per app. Implementations are
 * thread-safe: concurrent calls are serialized, and a teardown runs at most once per session no
 * matter how many callers ask for it at the same time.
 *
 * ```kotlin
 * // after a successful sign-in
 * sessionManager.startSession()
 *
 * // anywhere that needs to react — a nav host, a component, a Swift observer
 * sessionManager.state.collect { if (it == SessionState.INACTIVE) showSignIn() }
 *
 * // sign-out, a 401 that cannot be refreshed, an account deletion
 * val report: SessionEndReport = sessionManager.endSession()
 * ```
 */
public interface SessionManager {

    /**
     * Whether a session is currently open. Starts at [SessionState.INACTIVE].
     *
     * This is how the rest of the app learns the session ended — there is no callback to register
     * and no navigation handler to own. It flips to [SessionState.INACTIVE] only after every
     * cleaner has finished or been abandoned, so a collector that reacts by navigating to a
     * sign-in screen can be sure nothing is still wiping state behind it.
     */
    public val state: StateFlow<SessionState>

    /**
     * Marks a session open, moving [state] to [SessionState.ACTIVE]. Call it once a sign-in has
     * succeeded and whatever the session consists of has been persisted.
     *
     * Calling it while a session is already open is a no-op beyond re-arming teardown: the manager
     * holds no session identity, so it cannot tell one session from the next.
     *
     * It suspends because it is serialized against [endSession]: starting a session while a
     * teardown is still running would otherwise be silently undone when that teardown finishes.
     * Called during an in-flight teardown, it waits for that teardown to complete and then opens
     * the new session.
     */
    public suspend fun startSession()

    /**
     * Ends the session and reports what failed on the way out.
     *
     * In order: the [SessionRevoker] runs first (it usually needs credentials the cleaners are
     * about to wipe), then every [SessionCleaner] runs **concurrently**, then [state] flips to
     * [SessionState.INACTIVE]. Each step is individually timeout-bounded and individually
     * exception-isolated, so no single failure can abort the rest — the session always ends, and
     * every failure is recorded in the returned [SessionEndReport].
     *
     * **Runs at most once per session.** Concurrent callers do not each trigger a teardown: the
     * first one runs it, the rest suspend until it completes and receive the same report. Called
     * when no session is open it does nothing and returns [SessionEndReport.Empty] — the cleaners
     * do not run.
     *
     * **Uncancellable once started.** A half-finished teardown leaves the app in a worse state than
     * either finishing or not starting, and the caller is typically a screen scope that the logout
     * navigation itself is about to destroy. Cancelling the calling coroutine therefore does not
     * stop the teardown: every cleaner still runs to completion and the session still ends. What a
     * cancelled caller cannot count on is *observing* the returned report — it is a cancelled
     * coroutine, and structured concurrency still applies to it. Read the report from a caller you
     * did not cancel, or from a later `endSession()` call, which returns the same one.
     */
    public suspend fun endSession(): SessionEndReport
}

/**
 * Creates a [SessionManager] over the given teardown participants.
 *
 * @param cleaners every [SessionCleaner] that must run when the session ends. Order is irrelevant —
 *   they run concurrently. An empty list is valid and makes [SessionManager.endSession] a pure
 *   state flip.
 * @param revoker optional server-side revocation hook, run before the cleaners. `null` — the
 *   default — means teardown is entirely local.
 * @param dispatchers where teardown runs. Cleaners wipe databases and caches, which does not belong
 *   on the main thread, so the whole teardown is dispatched to [AppDispatchers.io]. Substitute
 *   `TestAppDispatchers` from `kmptoolkit-coroutines-testing` in tests.
 * @param logger where teardown progress and failures are reported. Defaults to [NoopLogger] — the
 *   [SessionEndReport] carries the same failures either way.
 * @param cleanerTimeout upper bound on a single cleaner. Cleaners are fast local wipes; this only
 *   bites when one stalls, and it exists so a stuck cleaner delays sign-out by a bounded amount
 *   instead of hanging it forever.
 * @param revokeTimeout upper bound on the [SessionRevoker]. Larger than [cleanerTimeout] by default
 *   because it is the one step allowed to touch the network — but still bounded, since a user on a
 *   dead connection must not wait on a request that will never answer.
 */
public fun createSessionManager(
    cleaners: List<SessionCleaner>,
    revoker: SessionRevoker? = null,
    dispatchers: AppDispatchers,
    logger: Logger = NoopLogger,
    cleanerTimeout: Duration = 5.seconds,
    revokeTimeout: Duration = 10.seconds,
): SessionManager = DefaultSessionManager(
    cleaners = cleaners.toList(),
    revoker = revoker,
    dispatchers = dispatchers,
    logger = logger,
    cleanerTimeout = cleanerTimeout,
    revokeTimeout = revokeTimeout,
)
