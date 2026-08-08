package io.github.jamal_wia.kmptoolkit.session

/**
 * Whether a session is currently open.
 *
 * This is the whole of what this module knows about a session: there is one, or there is not.
 * *Whose* session it is, what it is made of (tokens, a profile, a device binding) and where that
 * is persisted are the consuming app's concern — see
 * `docs/kmptoolkit-session/01-overview.md` for why none of that lives here.
 */
public enum class SessionState {

    /** A session is open. Set by [SessionManager.startSession]. */
    ACTIVE,

    /**
     * No session is open. The initial state of every [SessionManager], and the state it returns to
     * once [SessionManager.endSession] has finished tearing the session down.
     */
    INACTIVE,
}
