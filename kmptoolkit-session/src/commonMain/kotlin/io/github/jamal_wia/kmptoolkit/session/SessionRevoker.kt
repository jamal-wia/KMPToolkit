package io.github.jamal_wia.kmptoolkit.session

/**
 * Optional hook for telling a server the session is over — the one part of teardown that is allowed
 * to touch the network.
 *
 * This module never implements it and never will: it has no HTTP client, knows nothing about
 * tokens, and has no opinion about what "revoke" means to your backend. Implement it as a thin
 * adapter over whatever auth API you already have, and pass it to `createSessionManager`.
 *
 * ### Contract
 * - It runs **before** the cleaners, because a revocation call almost always needs the credentials
 *   the cleaners are about to wipe.
 * - **Its failure never prevents local teardown.** A thrown exception or an overrun of the
 *   revoke timeout is recorded in [SessionEndReport.revokeFailure]; the cleaners still run and the
 *   session still ends. Otherwise a user could not sign out while offline, which is precisely when
 *   they most want to.
 * - It gets **one** attempt. This module does not retry, queue, or defer a failed revocation. If
 *   your backend must eventually learn about the logout, hand that to a durable queue of your own
 *   and let this call be the fast path.
 */
public fun interface SessionRevoker {

    /** Tells the server the current session is over. See the contract on [SessionRevoker]. */
    public suspend fun revoke()
}
