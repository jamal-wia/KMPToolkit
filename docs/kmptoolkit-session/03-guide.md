# kmptoolkit-session — Guide

Everything the module promises, why it promises it, and where the sharp edges are.

## Writing a cleaner

A `SessionCleaner` is one suspend function plus a name. Four rules govern it, and all four exist
because teardown is a fan-out, not a sequence.

### Order is unspecified and cleaners run concurrently

Every cleaner is launched at once and the teardown waits for all of them. A cleaner may **not**
assume another has run, is running, or has finished — not even one it "obviously" depends on,
because nothing in the module gives you a way to express that dependency.

If two pieces of cleanup are genuinely ordered — wipe the index only after the files it points at
are gone — that is one cleaner that sequences them internally:

```kotlin
class MediaSessionCleaner(...) : SessionCleaner {
    override val name: String = "media"

    override suspend fun clean() {
        files.deleteAll()     // ordered inside one cleaner,
        index.clear()         // which is the only place ordering exists
    }
}
```

Concurrency was chosen over registration order deliberately. Sequential teardown makes the whole
sign-out as slow as the sum of its parts, and — worse — it makes ordering an *implicit* contract
that some cleaner will eventually come to depend on by accident, at which point reordering the
registration list breaks it silently. Concurrent-and-unordered cannot be depended on by accident.

### It must be idempotent

A cleaner can run against state that is already empty: a second sign-out, a sign-out of a session
that never fully established, a crash-recovery path. Deleting nothing must be a no-op, not an error.

### It must not touch the network

Ending a session has to work with the radio off — that is exactly the moment a user reaches for
"sign out on this device". Cancelling in-flight local work is fine; starting new traffic is not.
Server-side revocation has its own hook, described below, and is separately allowed to fail.

### It may throw, and it may be slow — both are handled

A cleaner that throws is recorded and skipped. A cleaner that overruns the cleaner timeout (5s by
default) is abandoned and recorded. Neither stops the other cleaners, and neither stops the session
from ending. You never need a `try`/`catch` inside a cleaner for the sake of the teardown — only for
your own recovery.

## Failure semantics, in one place

| What happens | Effect on other cleaners | Effect on the session | What you get back |
|---|---|---|---|
| A cleaner throws | none — they all still run | none — it still ends | `cleanerFailures` entry with the throwable |
| A cleaner throws an `Error` | none | none | `cleanerFailures` entry, same as any throwable |
| A cleaner overruns its timeout | none | none | `cleanerFailures` entry with `SessionTeardownTimeoutException` |
| *Every* cleaner throws | — | none — it still ends | one entry per cleaner, in registration order |
| The revoker throws or hangs | none — they run afterwards regardless | none | `revokeFailure` |
| The calling coroutine is cancelled | none — teardown is uncancellable | none | the teardown finishes; a cancelled caller may not observe the report — read it from a later `endSession()` |

The single rule behind that table: **a teardown that aborts halfway is worse than either outcome.**
An app with three of five features wiped is in a state nobody designed, tested, or can reason
about — a signed-out user still holding another account's cached chat list. Finishing the teardown
and reporting the damage is strictly better, so nothing is allowed to abort it.

That is also why `Error` is caught, which normally would be wrong. The usual argument — an `Error`
means the process is in trouble and swallowing it hides a real bug — is answered here by the report:
the throwable is not swallowed, it is handed back to the caller. What is not acceptable is letting
it abort the other four cleaners on its way out.

## Deciding what to do about a failed teardown

`endSession()` returns a `SessionEndReport`. By the time you hold it, the session is already over —
there is no "retry the sign-out" to perform. Useful things to do with it:

```kotlin
val report: SessionEndReport = sessionManager.endSession()

if (!report.isClean) {
    // 1. Log it. A cleaner that fails in production is invisible otherwise.
    logger.w { "sign-out left work behind: ${report.cleanerFailures.map { it.name }}" }

    // 2. Remember it, and repair on next launch — a cleaner that failed left data behind.
    pendingRepairs.record(report.cleanerFailures.map { it.name })
}
```

What *not* to do with it: keep the user signed in, block the navigation, or show a "sign-out
failed" error. None of them are true. The session ended.

## Server-side revocation, and why it may fail

`SessionRevoker` is the one step allowed to make a network call, and it runs **first** — before the
cleaners, because it usually needs the credentials the cleaners are about to delete.

```kotlin
val manager = createSessionManager(
    cleaners = cleaners,
    revoker = SessionRevoker { authApi.revokeCurrentSession() },
    dispatchers = dispatchers,
)
```

It gets one attempt, bounded by the revoke timeout (10s by default), and its failure is recorded in
`report.revokeFailure` and then ignored. There is no retry and no queue: a user on a dead connection
must not be held signed in waiting for a request that will never answer.

If your backend genuinely must learn about the sign-out eventually, that is a durable-queue problem,
not a session-lifecycle one — enqueue the revocation in whatever outbox you already have and let
this hook be the fast path.

## The 401 path

The common reason a session ends is not a button. It is a refresh that finally failed:

```kotlin
// in your auth interceptor / token refresher — not in this module, which knows nothing about tokens
suspend fun onRefreshFailedPermanently() {
    sessionManager.endSession()
}
```

Two things make this safe without any coordination on your side:

- **It can race the sign-out button and lose.** Concurrent calls tear down once; the second caller
  suspends until the first finishes and gets the same report.
- **It can be called from a dying scope.** The teardown is uncancellable once started, so the
  navigation it triggers can destroy the component that called it without truncating the cleanup.

## Restoring a session across process death

The module persists nothing, so `state` is `INACTIVE` in every new process. Restoring is a decision
only your app can make, and it is one call:

```kotlin
suspend fun onAppStart() {
    if (credentialStore.load() != null) sessionManager.startSession()
}
```

Persisting the credentials themselves is [`kmptoolkit-storage`](../kmptoolkit-storage/01-overview.md)'s
job, not this module's — see [`01-overview.md`](01-overview.md#what-this-is-not).

## Starting a session while one is ending

`startSession()` suspends, which is unusual for something that only sets a flag. It does so because
it is serialized against `endSession()`: a sign-in that lands while a teardown is still running
would otherwise be silently undone when that teardown flips the state to `INACTIVE`.

Called during an in-flight teardown, it waits for the teardown to finish and then opens the new
session — the final state is `ACTIVE`, which is what the user just asked for.

## Threading

Teardown runs on `AppDispatchers.io`: cleaners wipe databases and caches, which has no business on
the main thread. The `state` flow is a plain `StateFlow` and is safe to collect from anywhere; if
you react to it by navigating, collect it wherever your app already does main-thread work.

Everything else is thread-safe by construction — one mutex serializes `startSession` and
`endSession`, so concurrent callers on different threads cannot produce two teardowns of one session.
