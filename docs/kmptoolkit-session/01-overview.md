# kmptoolkit-session — Overview

Two things, and nothing else: whether a session is currently open, and a way for every part of an
app to be told — and to clean up — when it ends.

```kotlin
val sessionManager: SessionManager = createSessionManager(
    cleaners = listOf(profileCleaner, chatCleaner, downloadsCleaner),
    revoker = SessionRevoker { authApi.revoke() },
)

sessionManager.startSession()            // after a successful sign-in
sessionManager.state                     // StateFlow<SessionState>, ACTIVE or INACTIVE
val report = sessionManager.endSession() // runs the revoker, then every cleaner, then flips to INACTIVE
```

## The problem it solves

Signing out is not one operation, it is *n* operations owned by *n* different features. The chat
feature holds a cached conversation list, the media feature holds downloaded files, some component
holds an in-memory profile, and each of them has to be wiped when the account goes away. Wiring
that up by hand produces one of two shapes, both bad:

- **A god function** — `logout()` in some app-level class that reaches into every feature. Every new
  feature edits it, and forgetting to is a silent data leak between accounts.
- **A pile of listeners** — each feature subscribes to "logged out" and does its own thing, with no
  one able to say when teardown is finished, what failed, or whether it ran twice.

This module is the third shape: features register a `SessionCleaner` where they are already wired,
the manager fans out to all of them, and the sign-out completes **once**, with a report of anything
that failed. The rest of the app does not subscribe to a callback — it observes `state`.

## What it guarantees

These are the properties the module exists to provide; each one is spelled out in
[`03-guide.md`](03-guide.md) and pinned by a test.

- **The session always ends.** No cleaner failure, no revoker failure, no timeout, and no
  cancellation of the calling coroutine can leave the session open. A teardown that aborts halfway
  is worse than either finishing or never starting.
- **Failures are reported, never swallowed.** `endSession()` returns a `SessionEndReport` naming
  every cleaner that threw or overran its timeout, plus the revoker's failure if there was one.
- **No participant can hold sign-out hostage.** Each step is *abandoned* at its timeout rather than
  awaited, so even a cleaner that ignores cancellation delays sign-out by a bounded amount and
  cannot wedge a later one. A cleaner that calls back into the manager is refused with a named
  exception instead of deadlocking on its lock.
- **Exactly once per session.** Concurrent callers do not each trigger a teardown; the first runs
  it and the rest receive the same report.
- **Offline sign-out works.** Server revocation is optional, bounded, and allowed to fail — it can
  never keep a user signed in.

## What this is **not**

The scope above is the whole scope, and the neighbouring problems are deliberately somebody else's:

- **Not an auth library.** It does not sign anyone in, does not know what a credential is, and has
  no concept of a user, a role, or an account. `startSession()` is you telling it a sign-in already
  succeeded.
- **Not token storage.** It stores nothing at all — not a token, not a user id, not even the fact
  that a session existed across process death. `state` starts at `INACTIVE` in every new process;
  restoring it from whatever you persisted is a `startSession()` call at launch. For the persisting
  itself use [`kmptoolkit-storage`](../kmptoolkit-storage/01-overview.md), whose encrypted variant is
  built for exactly this.
- **Not token refresh.** There is no notion of expiry, no refresh loop, no retry-with-new-token.
  When your refresh finally fails for good, that is a `endSession()` call.
- **Not HTTP, and there will be no network module in this library.** `SessionRevoker` is an SPI with
  no implementation here, precisely so this module never needs a client, an interceptor, or an
  opinion about your API.
- **Not a session store or a multi-session manager.** One manager tracks one session. It holds no
  identity, so it cannot tell one user's session from the next — which is also why starting a
  session twice is not an error.
- **Not durable.** Nothing is queued, retried, or resumed after a crash. A revocation that failed
  is failed; if your backend must eventually hear about it, put that in a durable queue of your own.
- **Not navigation.** It flips a `StateFlow`; where that sends the user is the app's decision, made
  wherever the app already makes routing decisions.

## Dependencies

`kotlinx-coroutines-core` (teardown dispatches to a `CoroutineDispatcher`) and
[`kmptoolkit-logging`](../kmptoolkit-logging/01-overview.md) (optional, defaults to `NoopLogger`).
No DI framework, no Compose, no platform code at all — the module is pure common Kotlin, identical
on Android and iOS.
