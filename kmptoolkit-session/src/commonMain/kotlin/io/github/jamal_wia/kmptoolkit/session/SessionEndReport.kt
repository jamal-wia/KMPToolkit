package io.github.jamal_wia.kmptoolkit.session

import kotlin.time.Duration

/**
 * What happened during one teardown: which cleaners failed, and whether the [SessionRevoker] did.
 *
 * The session has already ended by the time you hold one of these — a report is a record, never a
 * verdict, and there is nothing in it that can undo the logout. Read it to log, to warn, or to
 * schedule a repair on next launch (a cleaner that failed left state behind); do not read it to
 * decide whether the user is signed out.
 *
 * One portability note about the throwables inside: on Android and the JVM, kotlinx-coroutines'
 * stacktrace recovery hands back an *augmented copy* of what a cleaner threw, not the identical
 * instance — on Kotlin/Native it is the same instance. Match on type and message; do not compare
 * by reference.
 *
 * @property cleanerFailures one entry per [SessionCleaner] that threw or timed out, in the order
 *   the cleaners were registered — not the order they failed in, which is not observable when they
 *   run concurrently. Empty when every cleaner succeeded.
 * @property revokeFailure why the [SessionRevoker] failed, or `null` when it succeeded or when no
 *   revoker was registered.
 */
public data class SessionEndReport(
    public val cleanerFailures: List<SessionCleanerFailure> = emptyList(),
    public val revokeFailure: Throwable? = null,
) {

    /** `true` when nothing failed — every cleaner finished and the revoker, if any, succeeded. */
    public val isClean: Boolean
        get() = cleanerFailures.isEmpty() && revokeFailure == null

    public companion object {

        /**
         * The report of a teardown that did nothing, returned by [SessionManager.endSession] when
         * no session was open. Indistinguishable from a perfectly clean teardown by design: check
         * [SessionManager.state] first if you need to know whether *your* call is the one that
         * ended the session.
         */
        public val Empty: SessionEndReport = SessionEndReport()
    }
}

/**
 * One [SessionCleaner] that did not finish cleanly.
 *
 * @property name the failing cleaner's [SessionCleaner.name].
 * @property cause what it threw — or a [SessionTeardownTimeoutException] when it overran the
 *   cleaner timeout instead of throwing.
 */
public data class SessionCleanerFailure(
    public val name: String,
    public val cause: Throwable,
)

/**
 * A cleaner or a revoker was still running when its timeout elapsed, so teardown abandoned it and
 * moved on.
 *
 * The abandoned work is cancelled, not awaited: whatever it had not finished stays unfinished. The
 * message is diagnostic, never something to show a user.
 *
 * @property name the [SessionCleaner.name] that overran, or `"revoker"` for the [SessionRevoker].
 * @property timeout the bound it overran.
 */
public class SessionTeardownTimeoutException(
    public val name: String,
    public val timeout: Duration,
) : RuntimeException("Session teardown step '$name' did not finish within $timeout")
