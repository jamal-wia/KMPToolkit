package io.github.jamal_wia.kmptoolkit.session

/**
 * A [SessionCleaner] or [SessionRevoker] called back into the very [SessionManager] that is running
 * it.
 *
 * That call can never succeed: the manager holds a non-reentrant lock for the whole of a teardown,
 * so the nested call would wait for a lock its own call chain is holding — forever, and
 * uncancellably. Nor can the step timeout rescue it, because the nested call *is* the thing that
 * hangs. Failing immediately, with a name that says what happened, is the only useful outcome.
 *
 * The fix is always the same: a cleaner cleans, and nothing more. Ending or starting a session is
 * the app's decision, made from outside teardown.
 *
 * Thrown from inside a cleaner, it is recorded like any other cleaner failure — teardown continues
 * and the session still ends.
 *
 * @property operation the [SessionManager] method that was re-entered, `"endSession"` or
 *   `"startSession"`.
 */
public class SessionReentrancyException(
    public val operation: String,
) : IllegalStateException(
    "$operation was called from inside this SessionManager's own teardown. A SessionCleaner or " +
        "SessionRevoker must not start or end a session; doing so would deadlock on the lock its " +
        "own teardown holds.",
)
