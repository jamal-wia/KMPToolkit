# kmptoolkit-session — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.session`. Eight of them, and that is the
whole module.

## `SessionManager`

```kotlin
public interface SessionManager {
    public val state: StateFlow<SessionState>
    public suspend fun startSession()
    public suspend fun endSession(): SessionEndReport
}
```

Thread-safe. Hold one per app; obtain it from [`createSessionManager`](#createsessionmanager).

### `val state: StateFlow<SessionState>`

Whether a session is open. Starts at `SessionState.INACTIVE` in every new process — the module
persists nothing.

This is how the rest of the app learns a session ended: there is no callback to register and no
navigation handler to own. It flips to `INACTIVE` **after** every cleaner has finished or been
abandoned, so a collector that reacts by navigating can be sure nothing is still wiping state behind
it.

### `suspend fun startSession()`

Opens a session — moves `state` to `ACTIVE`. Call it once a sign-in has succeeded and whatever the
session consists of has been persisted by your own code.

Calling it while a session is already open is a no-op beyond re-arming teardown: the manager holds
no session identity, so it cannot distinguish one session from the next.

**Why it suspends,** unusually for something that sets a flag: it is serialized against
`endSession()`. Called during an in-flight teardown it waits for that teardown to complete and then
opens the new session, so a fast re-sign-in cannot be silently undone by the teardown it raced.

### `suspend fun endSession(): SessionEndReport`

Ends the session and reports what failed on the way out.

In order:

1. the `SessionRevoker`, if one was registered, bounded by `revokeTimeout`;
2. every `SessionCleaner`, **concurrently**, each bounded by `cleanerTimeout`;
3. `state` flips to `INACTIVE`.

Each step is individually exception-isolated and individually timeout-bounded, so no failure can
abort the rest. The session always ends. See
[`03-guide.md`](03-guide.md#failure-semantics-in-one-place) for the full table.

**No step can hold the session open.** Each is *abandoned* at its timeout rather than awaited, so a
cleaner that ignores cancellation — blocking I/O, a JNI call, its own `NonCancellable` — delays
sign-out by at most that bound and cannot wedge a later one. It keeps running, detached, until it
finishes on its own; nothing waits for it.

**Runs at most once per session,** and the two "nothing to do" cases are told apart:

- Called **concurrently** with a teardown already in flight, it does not start a second one: it
  suspends until that teardown finishes and returns *its* report, failures and all.
- Called on a session that was **already closed** when the call arrived, it does nothing, runs no
  cleaner, and returns `SessionEndReport.Empty` — deliberately *not* the previous teardown's report,
  which would attribute failures to a call during which they did not happen.

**Uncancellable once started.** Cancelling the calling coroutine does not stop the teardown: every
cleaner still runs to completion, the session still ends, and the cancelled caller still receives
the report. The caller is typically a screen scope that the sign-out navigation is about to destroy,
and a half-finished teardown is worse than either finishing or never starting.

**Not reentrant.** Calling it from inside a `SessionCleaner` or `SessionRevoker` of the same manager
throws [`SessionReentrancyException`](#sessionreentrancyexception) rather than deadlocking.

## `createSessionManager`

```kotlin
public fun createSessionManager(
    cleaners: List<SessionCleaner>,
    revoker: SessionRevoker? = null,
    dispatchers: AppDispatchers,
    logger: Logger = NoopLogger,
    cleanerTimeout: Duration = 5.seconds,
    revokeTimeout: Duration = 10.seconds,
): SessionManager
```

| Parameter | Meaning |
|---|---|
| `cleaners` | Everything that must run when the session ends. Copied defensively — mutating the list afterwards changes nothing. Order is irrelevant; an empty list is valid and makes `endSession()` a pure state flip. |
| `revoker` | Optional server-side revocation hook, run before the cleaners. `null` means teardown is entirely local. |
| `dispatchers` | Where teardown runs. The whole teardown is dispatched to `AppDispatchers.io` — cleaners wipe databases, which does not belong on the main thread. Substitute `TestAppDispatchers` in tests. |
| `logger` | Where teardown progress and failures are reported. The returned report carries the same failures either way. |
| `cleanerTimeout` | Upper bound on a single cleaner. Only bites when one stalls; it exists so a stuck cleaner delays sign-out by a bounded amount instead of hanging it forever. |
| `revokeTimeout` | Upper bound on the revoker. Larger by default because it is the one step allowed to touch the network — but still bounded. |

## `SessionState`

```kotlin
public enum class SessionState { ACTIVE, INACTIVE }
```

The whole of what this module knows about a session: there is one, or there is not. Whose it is,
what it is made of, and where that is persisted are the app's concern.

## `SessionCleaner`

```kotlin
public interface SessionCleaner {
    public val name: String
    public suspend fun clean()
}
```

The fan-out SPI: one implementation per feature that holds per-account state. Contract, in full:

- **Order is unspecified and cleaners run concurrently.** A cleaner may not assume anything about
  another. Genuinely ordered cleanup belongs inside one cleaner.
- **Must be idempotent** — it can run against already-empty state.
- **Must not make network calls.** Sign-out has to work offline; that is `SessionRevoker`'s job.
- **Must not block indefinitely** — it is bounded by `cleanerTimeout` and abandoned if it overruns.
  Abandoned means abandoned: it is cancelled and left to run detached, and nothing waits for it, so
  even a cleaner that ignores cancellation cannot hold up sign-out.
- **May throw** — the throwable is recorded and never stops anything else.
- **Must not call back into the manager running it** — `endSession()` and `startSession()` throw
  `SessionReentrancyException` when called from inside teardown. Detection follows the coroutine
  context, so it catches a direct call; a cleaner that routes the call through an unrelated scope
  escapes detection and genuinely deadlocks.

`name` is supplied rather than derived from the class name so it survives Android minification and
stays stable across a rename. Uniqueness is not enforced.

## `SessionRevoker`

```kotlin
public fun interface SessionRevoker {
    public suspend fun revoke()
}
```

The optional hook for telling a server the session is over — the one part of teardown allowed to
touch the network. This module never implements it: it has no HTTP client and no opinion about what
revocation means to your backend.

- Runs **before** the cleaners, because revocation usually needs the credentials they are about to
  wipe.
- **Its failure never prevents local teardown** — recorded in `SessionEndReport.revokeFailure`,
  then ignored. Otherwise a user could not sign out while offline.
- Gets **one** attempt. No retry, no queue, no deferral.

## `SessionEndReport`

```kotlin
public data class SessionEndReport(
    public val cleanerFailures: List<SessionCleanerFailure> = emptyList(),
    public val revokeFailure: Throwable? = null,
) {
    public val isClean: Boolean
    public companion object { public val Empty: SessionEndReport }
}
```

A record of one teardown, never a verdict: the session has already ended by the time you hold it.

- `cleanerFailures` — one entry per cleaner that threw or timed out, in **registration** order (not
  failure order, which is not observable when they run concurrently).
- `revokeFailure` — why the revoker failed, or `null` if it succeeded or was not registered.
- `isClean` — `true` when both are empty.
- `Empty` — what `endSession()` returns when the session was already closed on arrival. Deliberately
  indistinguishable from a perfectly clean teardown; check `state` first if you need to know whether
  *your* call ended the session. A caller that raced a teardown already in flight gets that
  teardown's real report instead.

**Portability note about the throwables inside:** on Android and the JVM, kotlinx-coroutines'
stacktrace recovery hands back an augmented *copy* of what a cleaner threw, not the identical
instance; on Kotlin/Native it is the same instance. Match on type and message, never by reference.

## `SessionCleanerFailure`

```kotlin
public data class SessionCleanerFailure(
    public val name: String,
    public val cause: Throwable,
)
```

One cleaner that did not finish cleanly. `name` is the failing cleaner's `SessionCleaner.name`;
`cause` is what it threw, or a `SessionTeardownTimeoutException` if it overran instead of throwing.

## `SessionTeardownTimeoutException`

```kotlin
public class SessionTeardownTimeoutException(
    public val name: String,
    public val timeout: Duration,
) : RuntimeException
```

A cleaner or the revoker was still running when its timeout elapsed, so teardown abandoned it. The
abandoned work is cancelled, not awaited — whatever it had not finished stays unfinished, and if it
does not honour cancellation it simply runs on, detached, with nothing waiting for it.

`name` is the cleaner's name, or `"revoker"` for the revoker. The message is diagnostic and is never
something to show a user.

## `SessionReentrancyException`

```kotlin
public class SessionReentrancyException(
    public val operation: String,
) : IllegalStateException
```

A cleaner or revoker called `endSession()` or `startSession()` on the very manager running it.

That call can never succeed — the manager holds a non-reentrant lock for the whole teardown, so the
nested call would wait for a lock its own call chain holds, forever and uncancellably, and the step
timeout cannot rescue it because the nested call *is* what hangs. Failing immediately with a named
exception is the only useful outcome.

Thrown from inside a cleaner it is recorded like any other cleaner failure: teardown continues and
the session still ends. `operation` is `"endSession"` or `"startSession"`.

Detection follows the coroutine context, which catches a direct call. A cleaner that routes the call
through an unrelated scope escapes it and genuinely deadlocks — an honest limitation, not solvable
without the library owning every scope a consumer might use. Calling a *different* `SessionManager`
is not reentrancy and works.
