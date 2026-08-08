package io.github.jamal_wia.kmptoolkit.session

/**
 * One unit of local cleanup that must run when a session ends — the fan-out SPI of this module.
 *
 * Register one per feature that holds per-account state (a database, a cache, an in-memory user
 * profile, a websocket connection) by passing it to `createSessionManager`. Every registered
 * cleaner runs on every [SessionManager.endSession].
 *
 * ### Contract
 * - **Order is unspecified, and cleaners run concurrently.** A cleaner may not assume any other
 *   cleaner has run, is running, or has finished — including cleaners it "obviously" depends on.
 *   If two pieces of cleanup are genuinely ordered, they belong in *one* cleaner that sequences
 *   them itself.
 * - **Must be idempotent.** It can run against already-empty state (a second logout, a logout on a
 *   session that was never fully established).
 * - **Must not make network calls.** Ending a session has to work offline — that is the moment a
 *   user most wants it to. Server-side revocation is [SessionRevoker]'s job, and it is separately
 *   allowed to fail.
 * - **Must not block indefinitely.** Each cleaner is bounded by the manager's cleaner timeout; a
 *   cleaner that overruns it is abandoned and reported as a failure, and teardown continues.
 * - **May throw.** A throwing cleaner is recorded in [SessionEndReport] and never prevents the
 *   other cleaners, the session ending, or [SessionManager.endSession] returning.
 */
public interface SessionCleaner {

    /**
     * Identifies this cleaner in [SessionEndReport] and in log output.
     *
     * Supplied rather than derived from the class name so it survives Android minification and
     * stays stable when the class is renamed. Uniqueness is not enforced — two cleaners sharing a
     * name simply produce two failures with the same name.
     */
    public val name: String

    /** Wipes this feature's per-account state. See the contract on [SessionCleaner]. */
    public suspend fun clean()
}
