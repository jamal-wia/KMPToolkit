package io.github.jamal_wia.kmptoolkit.session

import io.github.jamal_wia.kmptoolkit.coroutines.AppDispatchers
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * The only [SessionManager] implementation. Internal — consumers construct it through
 * `createSessionManager`, which is what keeps the constructor free to change.
 *
 * The concurrency design is one mutex held for the whole of a teardown, rather than an "already
 * logging out" boolean flag. The flag version has to answer "what does the second caller get?" and
 * the honest answers are all bad: returning early hands back a report of a teardown that has not
 * happened yet, and busy-waiting reinvents the mutex. Holding the lock across the teardown lets the
 * second caller simply wait and receive the real report.
 *
 * Holding a lock across arbitrary consumer code is only safe because no step can hold it
 * indefinitely — see [runStep] for how a teardown step that ignores cancellation is genuinely left
 * behind rather than awaited, and [checkNotReentrant] for the one way a consumer could still wedge
 * the lock, which is detected and refused instead.
 */
internal class DefaultSessionManager(
    private val cleaners: List<SessionCleaner>,
    private val revoker: SessionRevoker?,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
    private val cleanerTimeout: Duration,
    private val revokeTimeout: Duration,
) : SessionManager {

    private val mutex = Mutex()
    private val mutableState: MutableStateFlow<SessionState> = MutableStateFlow(SessionState.INACTIVE)

    /**
     * The report of the teardown that most recently closed a session. Read and written only under
     * [mutex]; handed to a caller that lost the race to an in-flight teardown, and to nobody else —
     * see [endSession] for why a later caller must not receive it.
     */
    private var lastReport: SessionEndReport = SessionEndReport.Empty

    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    override suspend fun startSession() {
        checkNotReentrant("startSession")
        mutex.withLock {
            lastReport = SessionEndReport.Empty
            mutableState.value = SessionState.ACTIVE
        }
    }

    override suspend fun endSession(): SessionEndReport {
        checkNotReentrant("endSession")

        // Read before queueing for the lock, and this is the whole of how the two "there is nothing
        // to tear down" cases are told apart. A caller that saw an open session and then found it
        // closed on acquiring the lock lost a race to a concurrent teardown, and is owed that
        // teardown's report. A caller that already found it closed on arrival is asking about a
        // session nobody was ending, and is owed Empty — replaying an older teardown's failures to
        // it would report failures that did not happen on this call.
        val sessionWasOpenOnArrival: Boolean = mutableState.value == SessionState.ACTIVE

        // NonCancellable wraps the lock acquisition too, not just the teardown: a caller cancelled
        // while queued behind an in-flight teardown must not abandon the lock half-acquired, and a
        // teardown that has started must finish whatever happens to whoever asked for it.
        return withContext(NonCancellable) {
            mutex.withLock {
                if (mutableState.value != SessionState.ACTIVE) {
                    logger.w { "endSession found no session open — nothing to tear down" }
                    return@withLock if (sessionWasOpenOnArrival) lastReport else SessionEndReport.Empty
                }
                val report: SessionEndReport = runTeardown()
                lastReport = report
                // Last, deliberately: a collector that reacts to INACTIVE by navigating away must
                // not do so while cleaners are still wiping the state behind that navigation.
                mutableState.value = SessionState.INACTIVE
                report
            }
        }
    }

    /**
     * Refuses a call made from inside this manager's own teardown.
     *
     * [Mutex] is not reentrant, so a cleaner that calls back into [endSession] or [startSession]
     * would wait forever for a lock its own call chain is holding — and the step timeout cannot
     * rescue it, because the nested call is the thing that hangs. A named exception thrown
     * immediately is strictly more useful than a hang that looks like a slow network.
     *
     * Detection is by a [TeardownMarker] carried in the coroutine context, which covers the case
     * that actually happens: a cleaner calling the manager directly, on the coroutine the teardown
     * gave it. A cleaner that hands the call to an unrelated scope escapes the marker and still
     * deadlocks — documented on [SessionCleaner], not solvable without the manager owning every
     * scope a consumer might use.
     */
    private suspend fun checkNotReentrant(operation: String) {
        if (coroutineContext[TeardownMarker]?.manager === this) {
            throw SessionReentrancyException(operation)
        }
    }

    private suspend fun runTeardown(): SessionEndReport = withContext(dispatchers.io) {
        logger.i { "Ending session — ${cleaners.size} cleaner(s), revoker=${revoker != null}" }

        // Steps run on a scope of their own rather than as children of this teardown, so that
        // abandoning one is possible at all: a child would still be awaited by the enclosing scope
        // on the way out, which is exactly the bug the timeout is supposed to prevent.
        val stepScope = CoroutineScope(dispatchers.io + SupervisorJob() + TeardownMarker(this@DefaultSessionManager))
        try {
            // Before the cleaners: revocation needs the credentials they are about to delete.
            val revokeFailure: Throwable? =
                revoker?.let { runStep(stepScope, REVOKER_STEP_NAME, revokeTimeout, it::revoke) }

            val failures: List<SessionCleanerFailure> = supervisorScope {
                cleaners
                    .map { cleaner: SessionCleaner ->
                        async { runStep(stepScope, cleaner.name, cleanerTimeout, cleaner::clean) }
                    }
                    .let { running: List<Deferred<Throwable?>> -> running.awaitAll() }
                    .mapIndexedNotNull { index: Int, failure: Throwable? ->
                        failure?.let { SessionCleanerFailure(cleaners[index].name, it) }
                    }
            }

            SessionEndReport(cleanerFailures = failures, revokeFailure = revokeFailure)
        } finally {
            // Signals any step still running that nobody is waiting for it. Cooperative work stops
            // here; work that ignores cancellation runs on to its natural end, detached, which is
            // the best a library can do without owning the thread it is on.
            stepScope.cancel()
        }
    }

    /**
     * Runs one teardown step, bounded and isolated, and returns why it failed — or `null` if it did
     * not.
     *
     * The bound is applied to *awaiting* the step, not to the step itself, and the distinction is
     * the whole point. `withTimeoutOrNull { block() }` cancels the block and then **waits for it to
     * finish**, so a step that does not check for cancellation — blocking I/O, a JNI call, a tight
     * CPU loop, its own `NonCancellable` — is still awaited in full, with this manager's lock held
     * and uncancellable. One such cleaner would wedge every later sign-out permanently. Launching
     * the step on [stepScope], which outlives it, and timing out the `await` instead means an
     * overrunning step is genuinely left behind.
     *
     * Catches [Throwable] rather than [Exception], which is the opposite of the usual advice and is
     * deliberate. The alternative is letting an `Error` — or a stray
     * [kotlinx.coroutines.CancellationException] thrown by a badly written cleaner — abort teardown
     * partway, which leaves the app with some per-account state wiped, some not, and a session that
     * never ended. That is strictly worse than any of the outcomes a caught throwable can produce,
     * and the throwable is not lost: it comes back in the [SessionEndReport].
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runStep(
        stepScope: CoroutineScope,
        name: String,
        timeout: Duration,
        block: suspend () -> Unit,
    ): Throwable? {
        val step: Deferred<Unit> = stepScope.async { block() }
        return try {
            val finished: Unit? = withTimeoutOrNull(timeout) { step.await() }
            if (finished == null) {
                step.cancel()
                val timedOut = SessionTeardownTimeoutException(name, timeout)
                logger.w(timedOut) { "Session teardown step '$name' timed out — abandoning it" }
                timedOut
            } else {
                null
            }
        } catch (throwable: Throwable) {
            logger.w(throwable) { "Session teardown step '$name' failed — continuing" }
            throwable
        }
    }

    private companion object {
        /** Reported as the [SessionTeardownTimeoutException.name] when the revoker overruns. */
        const val REVOKER_STEP_NAME = "revoker"
    }
}
