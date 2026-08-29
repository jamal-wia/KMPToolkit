# Testing

`kmptoolkit-uploader-testing` ships the doubles. Add it as a test dependency:

```kotlin
commonTest.dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-uploader-testing")
}
```

| Fixture | Use it when |
|---|---|
| `InMemoryUploaderStore` | You want the **real engine** in a test — retries, ordering, leases |
| `UploaderStoreContract` | You wrote your own `UploaderStore` and want it held to the contract |
| `FakeUploader` | You are testing code that only *decides* to enqueue |
| `RecordingWakeScheduler` | You want to assert the app would be woken |
| `MutableConstraintProvider` | You are testing "wait for the network, then fire" |
| `MutableUploaderClock` | You are testing backoff gates or lease expiry |

## Testing code that enqueues

Most repository tests do not need an engine at all:

```kotlin
@Test
fun `sending a message queues it under its local id`() = runTest {
    val uploader = FakeUploader()
    val repository = MessageRepository(uploader)

    repository.send(conversationId = "c1", body = "hi")

    val call = assertNotNull(uploader.lastEnqueued())
    assertEquals("message-local-1", call.uniqueKey)
    assertEquals(ConflictPolicy.KEEP, call.conflictPolicy)
    assertEquals(SendMessage("c1", "hi"), call.payload)
}
```

`FakeUploader` records the payload **object**, not an encoded string — it never calls your handler's
`encodePayload` — so assertions compare against what you passed in. Simulate a `KEEP` conflict by
passing a result function:

```kotlin
val uploader = FakeUploader { call -> if (call.uniqueKey == "already-queued") null else "id" }
```

## Testing with the real engine

For anything about delivery, run the engine over `InMemoryUploaderStore`:

```kotlin
@Test
fun `a failing effect is retried with a backoff gate`() = runTest {
    val store = InMemoryUploaderStore()
    val clock = MutableUploaderClock()
    val handler = FailingHandler()
    val engine = createUploaderEngine(
        store = store,
        handlers = listOf(handler),
        scope = backgroundScope,
        clock = clock,
    )
    engine.enqueue(handler, payload)

    engine.drain()

    val item = assertNotNull(store.snapshot().singleOrNull())
    assertEquals(1, item.attempts)
    assertTrue(item.nextRunAtEpochMillis > 0L)
}
```

Three habits make these tests deterministic:

**Use `backgroundScope`, always.** A started engine's heartbeat loops forever, and a
forever-looping coroutine in the test's own scope makes `runTest` **hang** rather than fail.
`backgroundScope` is cancelled when the test body ends.

**Prefer `drain()` over `start()`.** `drain()` is one synchronous pass — deterministic, no timing.
Reach for `start()` only when the thing under test *is* a trigger: the heartbeat, a constraint
transition, the backoff alarm.

**Never call `advanceUntilIdle()` on a started engine.** "Advance until there is nothing left to do"
never returns when a heartbeat is scheduled forever. Use `runCurrent()` and bounded `advanceTimeBy`.

## Two clocks, and why you often need both

`runTest` gives you a virtual **coroutine** clock, which `delay` observes. The engine also reads a
**wall** clock, which is where backoff gates and leases are written. They are independent, and a
test of the alarm path moves both:

```kotlin
clock.nowMillis = 1_000L          // the persisted gate is now in the past
advanceTimeBy(1_100.milliseconds) // the alarm's delay fires
runCurrent()
```

Testing lease expiry usually needs only the wall clock, since the drain reads it directly:

```kotlin
engine.drain()                    // handler returns Detached(leaseMillis = 10_000)
clock.advanceBy(10_001)
engine.drain()                    // the item is attemptable again, wasDetached == true
```

## Testing your own store

If you implemented `UploaderStore`, run the contract against it. One test gets you thirty invariants:

```kotlin
class MyUploaderStoreTest {

    @Test
    fun `it satisfies the UploaderStore contract`() = runTest {
        UploaderStoreContract { MyUploaderStore(freshDatabase()) }.verifyAll()
    }
}
```

The lambda must return a **fresh, empty** store each time — every check calls it, so no check can be
influenced by another's leftovers. A violation throws `UploaderStoreContractViolation` naming the
invariant that broke and what was seen instead.

Every check is also public individually, if you would rather have per-invariant reporting:

```kotlin
private val contract = UploaderStoreContract { MyUploaderStore(freshDatabase()) }

@Test fun `insertion order survives a burst`() = runTest {
    contract.insertionOrderSurvivesSameMillisecondInserts()
}

@Test fun `a stale lease is rejected`() = runTest {
    contract.recordFailureIsRejectedWhenTheLeaseMoved()
}
```

It is deliberately not a JUnit base class: `kotlin.test`'s JVM annotations are typealiases to a
framework's own, so publishing one would put JUnit on the compile classpath of every consumer,
including their iOS build.

**What the contract cannot check** is durability across a process restart — no in-process check can
observe that. If your store buffers writes anywhere, prove that separately; the engine's central
promise rests on it.

## Testing constraint gating

```kotlin
@Test
fun `a gated effect fires when the network returns`() = runTest {
    val network = MutableConstraintProvider("network", satisfied = false)
    val engine = createUploaderEngine(
        store = InMemoryUploaderStore(),
        handlers = listOf(handler),
        scope = backgroundScope,
        constraintProviders = listOf(network),
    )
    engine.start()
    engine.enqueue(handler, payload)
    runCurrent()
    assertEquals(0, handler.attempts)

    network.satisfy()
    runCurrent()

    assertEquals(1, handler.attempts)
}
```

## Testing the wake layer

```kotlin
val wake = RecordingWakeScheduler()
// ... enqueue something ...
assertTrue(wake.isArmed, "a persisted debt must leave a wake armed")

engine.drain()                    // the queue empties
assertFalse(wake.isArmed)
```

## What this module's own tests cover

Worth reading if you are extending the engine: `kmptoolkit-uploader/src/commonTest` covers retry and
backoff under an injected clock, give-up policies, ordering within and across channels, constraint
gating, poison payloads, schema downgrades, the full detached-delivery protocol (lease expiry,
settle-twice, a settle racing a re-hand, an executor that died with the process), cancellation,
transient store failures, and the start/close lifecycle. `androidUnitTest` pins the merged-manifest
permission set and the derived WorkManager work name.
