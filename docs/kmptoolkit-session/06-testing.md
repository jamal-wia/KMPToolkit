# kmptoolkit-session — Testing

## The fixture artifact

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-session")
    testImplementation("io.github.jamal-wia:kmptoolkit-session-testing")
    testImplementation("io.github.jamal-wia:kmptoolkit-coroutines-testing") // for TestAppDispatchers
}
```

`kmptoolkit-session-testing` ships two types, `RecordingSessionCleaner` and
`RecordingSessionRevoker`. They live in a separate artifact for the reason described in
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate-testing-artifacts):
Kotlin Multiplatform cannot expose one module's `commonTest` to a consumer, and a consumer who never
writes a test never downloads them.

## What is worth asserting

The module itself is already tested — that a failing cleaner does not stop the others, that
concurrent sign-outs tear down once, that the session ends anyway. What *your* tests should cover is
the part only you can get wrong:

1. every feature that holds per-account state actually registered a cleaner;
2. each cleaner really wipes what it claims to;
3. your app reacts correctly to `state` becoming `INACTIVE`.

### Every feature is registered

The single most valuable test in an app using this module, and the one that catches the bug this
module exists to prevent — a new feature that caches account data and forgets to clean it up:

```kotlin
@Test
fun `every registered cleaner runs on sign-out`() = runTest {
    val cleaners = listOf(
        RecordingSessionCleaner(name = "chat"),
        RecordingSessionCleaner(name = "profile"),
        RecordingSessionCleaner(name = "downloads"),
    )
    val manager = createSessionManager(cleaners, dispatchers = TestAppDispatchers(testScheduler))
    manager.startSession()

    manager.endSession()

    assertEquals(listOf(1, 1, 1), cleaners.map { it.cleanCalls })
}
```

### A cleaner does its job — and does it twice

Idempotence is a contract requirement, so test it as one. Call `clean()` directly; there is no need
for a manager:

```kotlin
@Test
fun `cleaning twice leaves the chat database empty and does not throw`() = runTest {
    database.insert(conversation)

    ChatSessionCleaner(database).clean()
    ChatSessionCleaner(database).clean()

    assertEquals(emptyList(), database.conversations())
}
```

### Your app reacts to the session ending

```kotlin
@Test
fun `signing out sends the user to the sign-in screen`() = runTest {
    val manager = createSessionManager(emptyList(), dispatchers = TestAppDispatchers(testScheduler))
    val navigator = RecordingNavigator()
    SessionRouter(manager, navigator).start(backgroundScope)
    manager.startSession()

    manager.endSession()

    assertEquals(listOf(SignInScreen), navigator.replacedAll)
}
```

## Driving the failure paths

The fixtures exist mainly so the awkward paths are one line each. Both take a suspend lambda that
runs inside the recorded call, and both are reassignable mid-test.

**A cleaner that fails** — assert that your logging/repair path fires and that the rest still ran:

```kotlin
val broken = RecordingSessionCleaner(name = "db", onClean = { throw IllegalStateException("disk full") })
val healthy = RecordingSessionCleaner(name = "cache")

val report = manager.endSession()

assertEquals(1, healthy.cleanCalls)                             // a failure next door skipped nothing
assertEquals(listOf("db"), report.cleanerFailures.map { it.name })
```

**A cleaner that hangs** — with a `TestAppDispatchers` the cleaner timeout elapses in virtual time,
so this test is instant:

```kotlin
val stuck = RecordingSessionCleaner(name = "db", onClean = { delay(Long.MAX_VALUE) })
// ...
val failure = report.cleanerFailures.single()
assertIs<SessionTeardownTimeoutException>(failure.cause)
```

**Signing out offline** — the case that matters most and is hardest to reproduce against a real
backend:

```kotlin
val revoker = RecordingSessionRevoker(onRevoke = { throw IOException("offline") })
val manager = createSessionManager(cleaners, revoker, TestAppDispatchers(testScheduler))
manager.startSession()

val report = manager.endSession()

assertEquals(1, revoker.revokeCalls)
assertNotNull(report.revokeFailure)
assertEquals(SessionState.INACTIVE, manager.state.value)        // signed out regardless
```

## Two traps in tests around this module

**`runTest`'s own scope dispatches with a `StandardTestDispatcher`.** A `launch { endSession() }` has
*not* started running when `launch` returns, so cancelling that job — or asserting on the manager at
that point — tests a teardown that never began and passes for the wrong reason. Park a cleaner on a
`CompletableDeferred` and wait for it to signal that it was entered before you assert anything about
an "in-flight" teardown. This module's own test suite does exactly that.

**Compare throwables by type and message, not by reference.** On Android and the JVM the throwable
in a `SessionEndReport` is a stacktrace-recovered copy of what the cleaner threw, so `assertSame`
passes on Kotlin/Native and fails on JVM.

## Fixture reference

### `RecordingSessionCleaner`

```kotlin
public class RecordingSessionCleaner(
    override val name: String = "recording-cleaner",
    public var onClean: suspend () -> Unit = {},
) : SessionCleaner {
    public val cleanCalls: Int
}
```

`cleanCalls` counts every entry into `clean()`, including calls that then threw or hung.

### `RecordingSessionRevoker`

```kotlin
public class RecordingSessionRevoker(
    public var onRevoke: suspend () -> Unit = {},
) : SessionRevoker {
    public val revokeCalls: Int
}
```

**Neither is thread-safe.** The counters are plain `Int`s, so several teardowns running in genuine
parallel against one instance can lose an increment. That is the normal shape of a test double —
assert one manager at a time, or count in your own synchronized fake if you are deliberately testing
parallel entry.
