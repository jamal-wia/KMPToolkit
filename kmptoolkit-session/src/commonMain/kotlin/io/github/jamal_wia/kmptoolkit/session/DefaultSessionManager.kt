package io.github.jamal_wia.kmptoolkit.session

import io.github.jamal_wia.kmptoolkit.coroutines.AppDispatchers
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * The only [SessionManager] implementation. Internal — consumers construct it through
 * `createSessionManager`, which is what keeps the constructor free to change.
 *
 * The concurrency design is one mutex held for the whole of a teardown, rather than a "already
 * logging out" boolean flag. The flag version has to answer "what does the second caller get?" and
 * the honest answers are all bad: returning early hands back a report of a teardown that has not
 * happened yet, and busy-waiting reinvents the mutex. Holding the lock across the teardown makes
 * the second caller simply wait, observe [SessionState.INACTIVE], and receive the real report.
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
     * The report of the most recent teardown. Read and written only under [mutex], which is also
     * what lets a caller that lost the race return the winner's report instead of a fresh empty
     * one.
     */
    private var lastReport: SessionEndReport = SessionEndReport.Empty

    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    override suspend fun startSession() {
        mutex.withLock {
            lastReport = SessionEndReport.Empty
            mutableState.value = SessionState.ACTIVE
        }
    }

    override suspend fun endSession(): SessionEndReport =
        // NonCancellable wraps the lock acquisition too, not just the teardown: a caller cancelled
        // while queued behind an in-flight teardown must not abandon the lock half-acquired, and a
        // teardown that has started must finish whatever happens to whoever asked for it.
        withContext(NonCancellable) {
            mutex.withLock {
                if (mutableState.value != SessionState.ACTIVE) {
                    logger.w { "endSession called with no session open — nothing to tear down" }
                    return@withLock lastReport
                }
                val report: SessionEndReport = runTeardown()
                lastReport = report
                // Last, deliberately: a collector that reacts to INACTIVE by navigating away must
                // not do so while cleaners are still wiping the state behind that navigation.
                mutableState.value = SessionState.INACTIVE
                report
            }
        }

    private suspend fun runTeardown(): SessionEndReport = withContext(dispatchers.io) {
        logger.i { "Ending session — ${cleaners.size} cleaner(s), revoker=${revoker != null}" }

        // Before the cleaners: revocation needs the credentials they are about to delete.
        val revokeFailure: Throwable? = revoker?.let { runStep(REVOKER_STEP_NAME, revokeTimeout, it::revoke) }

        val failures: List<SessionCleanerFailure> = supervisorScope {
            cleaners
                .map { cleaner: SessionCleaner ->
                    async { runStep(cleaner.name, cleanerTimeout, cleaner::clean) }
                }
                .let { running: List<Deferred<Throwable?>> -> running.awaitAll() }
                .mapIndexedNotNull { index: Int, failure: Throwable? ->
                    failure?.let { SessionCleanerFailure(cleaners[index].name, it) }
                }
        }

        SessionEndReport(cleanerFailures = failures, revokeFailure = revokeFailure)
    }

    /**
     * Runs one teardown step, bounded and isolated, and returns why it failed — or `null` if it
     * did not.
     *
     * Catches [Throwable] rather than [Exception], which is the opposite of the usual advice and is
     * deliberate. The alternative is letting an `Error` — or a stray [kotlinx.coroutines.CancellationException]
     * thrown by a badly written cleaner — abort teardown partway, which leaves the app with some
     * per-account state wiped, some not, and a session that never ended. That is strictly worse
     * than any of the outcomes a caught throwable can produce, and the throwable is not lost: it
     * comes back in the [SessionEndReport].
     *
     * The step's own cancellation is safe to swallow here because the whole teardown already runs
     * under [NonCancellable] — nothing outside it is waiting for a cancellation to propagate.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runStep(name: String, timeout: Duration, block: suspend () -> Unit): Throwable? =
        try {
            val finished: Unit? = withTimeoutOrNull(timeout) { block() }
            if (finished == null) {
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

    private companion object {
        /** Reported as the [SessionTeardownTimeoutException.name] when the revoker overruns. */
        const val REVOKER_STEP_NAME = "revoker"
    }
}
