package io.github.jamal_wia.kmptoolkit.session.testing

import io.github.jamal_wia.kmptoolkit.session.SessionRevoker

/**
 * A [SessionRevoker] that calls no server, counts how often it was asked to, and does whatever the
 * test tells it to while running.
 *
 * Its main use is the offline case, which is the one that matters most and the one hardest to
 * reproduce against a real backend: make [onRevoke] throw, and assert that the session still ended
 * and every cleaner still ran.
 *
 * ```kotlin
 * val revoker = RecordingSessionRevoker(onRevoke = { throw IOException("offline") })
 *
 * val report = manager.endSession()
 *
 * assertEquals(1, revoker.revokeCalls)
 * assertNotNull(report.revokeFailure)
 * assertEquals(SessionState.INACTIVE, manager.state.value)
 * ```
 *
 * **Not thread-safe**, for the same reason as [RecordingSessionCleaner].
 */
public class RecordingSessionRevoker(

    /**
     * Runs inside [revoke], after the call has been counted. Assignable mid-test. Defaults to
     * succeeding immediately.
     */
    public var onRevoke: suspend () -> Unit = {},
) : SessionRevoker {

    private var revokeCallCount: Int = 0

    /** How many times [revoke] has been entered, counting calls that then threw or hung. */
    public val revokeCalls: Int
        get() = revokeCallCount

    override suspend fun revoke() {
        revokeCallCount++
        onRevoke()
    }
}
