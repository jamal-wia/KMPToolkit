# API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.uploader` and
`io.github.jamal_wia.kmptoolkit.uploader.spi`.

## `Uploader`

The narrow interface features depend on.

| Member | Signature | Notes |
|---|---|---|
| `enqueue` | `suspend fun <P : Any> enqueue(handler: UploaderHandler<P>, payload: P, uniqueKey: String? = null, tag: String? = null, conflictPolicy: ConflictPolicy = KEEP): String?` | Suspends until persisted. Returns the new item id, or `null` when a `KEEP` conflict left the queued item in place. |
| `observe` | `fun observe(type: String): Flow<List<UploaderItem>>` | All states, insertion order. Does not suspend — the query runs on collection. |
| `trigger` | `fun trigger()` | Conflated, non-suspending, safe from anywhere. |

**Suspension rule:** an operation that touches storage suspends; one that only moves in-memory state
does not. So `enqueue` suspends and `trigger` does not, and you never have to check the docs to know
whether a call can block.

**Thread safety:** all three are safe to call concurrently, subject to your store's own contract.

## `UploaderEngine : Uploader`

| Member | Signature | Notes |
|---|---|---|
| `start` | `fun start()` | Idempotent. Launches one drain coroutine, wires triggers, drains leftovers. A call after `close()` is a no-op. |
| `drain` | `suspend fun drain()` | One full pass. **Do not call on a started engine** — it breaks single-flight. For tests and diagnostics. |
| `settle` | `suspend fun settle(id: String, result: SettleResult)` | Reports a detached delivery. Every race degrades to a no-op; see below. |
| `awaitDrained` | `suspend fun awaitDrained(timeout: Duration): Boolean` | For a wake job. An `IN_FLIGHT` item counts as **not** drained. |
| `close` | `fun close()` | Idempotent, non-suspending. Stops the engine, leaves the queue and your scope alone. |

### `settle` guards

| Situation | Effect |
|---|---|
| Unknown id | No-op |
| `Failed` on an item that is not `IN_FLIGHT` | No-op — parked stays parked, pending is already the drain's |
| `Failed` whose lease changed between the read and the write | No-op — the fresh claim wins |
| `Delivered` / `Drop` / `Park` | Applied unconditionally — terminal facts, not opinions about retry state |

## `createUploaderEngine`

```kotlin
fun createUploaderEngine(
    store: UploaderStore,
    handlers: List<UploaderHandler<*>>,
    scope: CoroutineScope,
    constraintProviders: List<ConstraintProvider> = emptyList(),
    transactionRunner: TransactionRunner = TransactionRunner.Direct,
    wakeScheduler: WakeScheduler = WakeScheduler.NoOp,
    config: UploaderConfig = UploaderConfig(),
    logger: Logger = NoopLogger,
    clock: UploaderClock = UploaderClock.System,
    idGenerator: () -> String = ::randomUploaderItemId,
): UploaderEngine
```

Returns a constructed but **not started** engine. Throws `IllegalArgumentException` if two handlers
share a `type` or two providers share a `key` — both are wiring bugs whose symptom would otherwise
be delayed, silent data loss.

## `UploaderHandler<P : Any>`

| Member | Default | Contract |
|---|---|---|
| `type: String` | — | Stable queue key. Renaming orphans queued rows, which then park. |
| `schemaVersion: Int` | `1` | A row with a *newer* version parks rather than being mis-decoded. |
| `retryPolicy: RetryPolicy` | `ExponentialBackoffRetryPolicy()` | Per effect type. |
| `constraintKeys: Set<String>` | `emptySet()` | All must be satisfied. Unknown key ⇒ satisfied + logged error. |
| `encodePayload(P): String` | — | Any format; the engine never inspects it. |
| `decodePayload(String): P` | — | Throwing parks the item with the failure attached. |
| `orderingKey(P): String?` | `null` | Called once at enqueue and persisted. |
| `execute(AttemptContext, P): AttemptResult` | — | Must be at-least-once tolerant, bounded, and should return rather than throw. |

A thrown exception from `execute` is treated as `Retry`. A `CancellationException` is disambiguated:
real cancellation of the engine's scope propagates, while one leaked by the handler's own
`withTimeout` becomes a `Retry` so a handler cannot stall the queue.

## `AttemptContext`

`id`, `attempts`, `uniqueKey`, `orderingKey`, `wasDetached`.

`wasDetached` is `true` only when a lease expired without a settle — the signal to rejoin or probe
rather than start a fresh delivery.

## `AttemptResult`

| Variant | Row | Attempts |
|---|---|---|
| `Success` | deleted | — |
| `Retry(cause: Throwable? = null)` | back to `PENDING` under the retry policy | +1 |
| `Drop(reason: String)` | deleted | — |
| `Park(reason: String)` | `PARKED`, kept and visible | unchanged |
| `Detached(leaseMillis: Long)` | `IN_FLIGHT` under a lease | unchanged |

`Detached` requires a positive lease; a non-positive one throws at construction, because it would
expire the moment it was written and spin the drain.

## `SettleResult`

`Delivered`, `Failed(cause)`, `Drop(reason)`, `Park(reason)` — the same effects as their
`AttemptResult` counterparts. There is deliberately no detached/retry split: an executor never owns
retry policy.

## `UploadTransport`

```kotlin
public interface UploadTransport {
    public val leaseMillis: Long
    public fun launch(itemId: String, request: UploadRequest)
    public fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) // since 1.5.0, default calls launch(itemId, request)
    public fun cancelAll()
}
```

An optional, ready-made executor for `AttemptResult.Detached` — see
[`08-upload-transport.md`](08-upload-transport.md). `launch` must be idempotent per item id.
`isRehandOff` is `true` after an expired lease, when the previous executor may have finished without
reporting. `cancelAll` is safe to over-call: an item still owed survives and is re-handed on the next
drain or lease expiry.

## `UploadHandler<P : Any>` (since 1.5.0)

```kotlin
public abstract class UploadHandler<P : Any>(transport: UploadTransport) : UploaderHandler<P> {
    public abstract suspend fun prepareUpload(context: AttemptContext, payload: P): UploadPreparation
    public abstract suspend fun classify(payload: P, result: UploadResult): SettleResult
    public open suspend fun onUploadProgress(payload: P, fraction: Float)
    public open suspend fun onDelivered(payload: P)
    public open suspend fun onSettled(payload: P, attempts: Int, result: SettleResult)
    final override suspend fun execute(context: AttemptContext, payload: P): AttemptResult
}

public sealed interface UploadPreparation {
    public data class Proceed(val request: UploadRequest) : UploadPreparation
    public data class Drop(val reason: String) : UploadPreparation
    public data class Park(val reason: String) : UploadPreparation
}
```

| Member | Called | A throw means |
|---|---|---|
| `prepareUpload` | at hand-off, and again when the transport runs the attempt (`wasDetached = true`) | hand-off: a retry; run time: the item stays in flight for the lease |
| `classify` | with the raw outcome | `SettleResult.Failed` |
| `onUploadProgress` | per whole percent, while in flight | logged, ignored |
| `onDelivered` | for `Delivered`, before the row is removed | logged, ignored |
| `onSettled` | after the item settled; `attempts` as at hand-off | logged, ignored |

`execute`: `Proceed` → `transport.launch(id, wasDetached, request)` and `Detached(transport.leaseMillis)`
(a non-positive lease parks); `Drop`/`Park` → the same `AttemptResult`.

## `UploadGateway` (since 1.5.0)

```kotlin
public object UploadGateway {
    public val DEFAULT_ENGINE_WAIT: Duration // 60 s
    public suspend fun prepareAttempt(itemId: String, engineWait: Duration = DEFAULT_ENGINE_WAIT): UploadAttempt
    public suspend fun progress(itemId: String, fraction: Float)
    public suspend fun complete(itemId: String, result: UploadResult, engineWait: Duration = DEFAULT_ENGINE_WAIT): Boolean
}

public sealed interface UploadAttempt {
    public data class Ready(val request: UploadRequest) : UploadAttempt
    public data object NothingOwed : UploadAttempt
    public data object EngineUnavailable : UploadAttempt
}
```

How a transport for `UploadHandler` items reaches the engine registered in `UploaderEngineRegistry`.
`prepareAttempt` is `NothingOwed` for an item gone, not in flight, dropped or parked by its handler, or
whose preparation threw. `complete` returns `false` only when no engine registered within the wait.
`progress` is best effort and never waits.

## `UploadRequest` / `UploadField`

```kotlin
public data class UploadRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val fields: List<UploadField>,
)

public sealed interface UploadField {
    public data class Text(val name: String, val value: String) : UploadField
    public data class File(val name: String, val fileName: String, val contentType: String, val path: String) : UploadField
}
```

Declarative description of one multipart upload. `UploadField.File` streams from `path` at upload
time — the request never carries file bytes, which is also what keeps it well under WorkManager's
`Data` cap on Android.

## `UploadResult` / `defaultUploadClassification`

```kotlin
public sealed interface UploadResult {
    public data class Completed(val statusCode: Int) : UploadResult
    public data class TransportFailure(val message: String?) : UploadResult
}

public fun defaultUploadClassification(result: UploadResult): SettleResult
```

| `UploadResult` | `defaultUploadClassification` |
|---|---|
| `Completed(2xx)` | `SettleResult.Delivered` |
| `Completed(4xx)` | `SettleResult.Drop("HTTP <code>")` |
| `Completed(other)` | `SettleResult.Failed(null)` |
| `TransportFailure` | `SettleResult.Failed(null)` |

A default, not a policy this module can claim to know for your API — pass your own `classify` to
`createWorkManagerUploadTransport` when a status should map differently.

## `RetryPolicy`

```kotlin
interface RetryPolicy {
    val maxDelayMillis: Long   // a real upper bound — the engine detects clock jumps with it
    val giveUp: GiveUpPolicy
    fun backoffMillis(attempts: Int): Long   // pure, cheap, must not read the clock
}
```

`ExponentialBackoffRetryPolicy(baseDelayMillis = 1_000, maxDelayMillis = 300_000, jitterRatio = 0.2,
giveUp = Never, random = Random.Default)` — `min(max, base × 2^(n−1))`, spread by ±`jitterRatio`,
clamped to `0..maxDelayMillis`. Rejects a non-positive base, a max below the base, and a jitter
outside `0.0..1.0`. Inject a seeded `Random` for a deterministic test.

`GiveUpPolicy`: `Never` (default), `ParkAfterAttempts(n)`, `DropAfterAttempts(n)`; `n` must be
positive.

## `UploaderItem` / `UploaderItemState`

A data class of thirteen fields — see its KDoc for each. States: `PENDING`, `IN_FLIGHT`, `PARKED`.
There is deliberately no `RUNNING` state; a crash mid-execution needs no recovery pass because the
row is still `PENDING` on the next launch.

## `ConflictPolicy`

`KEEP` (default) and `REPLACE`. Both supersede a `PARKED` conflict. Ignored when `uniqueKey` is
`null`.

## `UploaderConfig`

| Field | Default | Meaning |
|---|---|---|
| `heartbeatInterval` | `30.seconds` | Safety-net drain, for a lost wake-up signal |
| `minAlarmDelay` | `50.milliseconds` | Floor for the backoff alarm, so a just-passed gate cannot spin |
| `drainPollInterval` | `500.milliseconds` | `awaitDrained` re-check cadence |
| `clockAnomalyFactor` | `2` | How far past `maxDelayMillis` a gate may sit before it is read as a backwards clock jump |

All validated at construction.

## `UploaderClock`

`fun interface UploaderClock { fun nowEpochMillis(): Long }`, with `UploaderClock.System` reading the
platform wall clock. Gates and leases are absolute epoch millis because they must survive process
death, which a monotonic reading cannot.

## `UploaderEngineRegistry`

`register(engine)`, `unregister(engine)`, `current`, `suspend await(timeout): UploaderEngine?`.

The module's one piece of global state, and only because a WorkManager `Worker` and an iOS `BGTask`
handler are constructed by the OS outside any object graph. Registration is explicit — nothing
registers on your behalf. `close()` clears the slot if the closing engine holds it. `await` returning
`null` means the outcome did not land, so a platform executor should ask to be retried.

## SPI — `io.github.jamal_wia.kmptoolkit.uploader.spi`

### `UploaderStore`

Eleven functions; the full contract is in the interface KDoc and in
[`07-custom-store.md`](07-custom-store.md). The invariants in one line each:

- Durable before returning; atomic per function; `recordFailure`'s guard is a single compare-and-set.
- `getAllActive` returns `PENDING` + `IN_FLIGHT` in **insertion order** (a sequence, not a timestamp).
- Every id-addressed write treats an absent row as a no-op and is idempotent.
- Callable concurrently.
- Dedup identity is `(type, uniqueKey)`; a `null` key never conflicts.
- Payload and tag are opaque.

### `TransactionRunner`

`suspend fun <R> inTransaction(block: suspend () -> R): R`. Must be atomic and **reentrant** — a
nested call joins the outer transaction. `TransactionRunner.Direct` runs the block.

### `ConstraintProvider`

`val key: String`, `val satisfied: StateFlow<Boolean>`. Cheap to read, emits on every change, read
from arbitrary threads. Seed optimistically.

### `WakeScheduler`

`fun scheduleWake()`, `fun cancelWake()`. Both idempotent, cheap, non-suspending, and **must not
throw** — the engine calls them from inside a caller's `enqueue`. `WakeScheduler.NoOp` is the
default.

## Android

| Symbol | Notes |
|---|---|
| `createWorkManagerWakeScheduler(context, config, logger)` | Application context is extracted internally |
| `WorkManagerWakeConfig(uniqueWorkName = null, requiresNetwork = true, initialBackoff = 30.seconds, drainBudget = 1.minutes, engineWait = 5.seconds)` | `uniqueWorkName` defaults to `<applicationId>.uploader.wake` |
| `UploaderDrainWorker` | Constructed reflectively by WorkManager; needs no manifest entry |
| `createWorkManagerUploadTransport(context, config, classify, logger)` | See [`08-upload-transport.md`](08-upload-transport.md) |
| `UploadTransportConfig(uniqueWorkNamePrefix = null, workTag = null, requiresNetwork = true, leaseMillis = 15.minutes, workBackoff = 30.seconds, engineWait = 5.seconds, connectTimeoutMillis = 30_000, readTimeoutMillis = 60_000)` | `uniqueWorkNamePrefix` defaults to `<applicationId>.uploader.upload.` |
| `UploaderUploadWorker` | Constructed reflectively by WorkManager; needs no manifest entry |
| `createWorkManagerUploadHandlerTransport(context, config, logger)` | Since 1.5.0. For `UploadHandler` items; the job stores only the item id |
| `UploadHandlerWorker` | Since 1.5.0. Open, with `protected open fun readItemId(inputData)` for taking over an earlier worker's jobs; input keys in its companion |

## iOS

| Symbol | Notes |
|---|---|
| `createBackgroundTaskWakeScheduler(config, logger)` | Returns the scheduler; keep the reference |
| `BackgroundTaskWakeConfig(taskIdentifier = null, requiresNetworkConnectivity = true, requiresExternalPower = false, engineWait = 5.seconds, drainBudget = 25.seconds)` | `taskIdentifier` defaults to `<bundleId>.uploader.drain` |
| `BackgroundTaskWakeScheduler.taskIdentifier` | The resolved string — must be in `Info.plist` |
| `BackgroundTaskWakeScheduler.handleWake(onDone)` | Call from your Swift `BGTaskScheduler` handler; `onDone` runs even if the drain throws |
| `createBackgroundUploadTransport(config, logger)` | Since 1.5.0. Background `NSURLSession` per item, for `UploadHandler` items |
| `BackgroundUploadConfig(sessionIdentifierPrefix = null, lease = 60.minutes, rehandFlushWindow = 3.seconds)` | `sessionIdentifierPrefix` defaults to `<bundleId>.uploader.upload.` |
| `BackgroundUploadRelaunch.handleEvents(identifier, completion)` | Call from `handleEventsForBackgroundURLSession` |

## `kmptoolkit-uploader-testing`

| Symbol | Purpose |
|---|---|
| `InMemoryUploaderStore` | A complete store; run the real engine against it |
| `UploaderStoreContract` | Runnable checks for every store invariant |
| `UploaderStoreContractViolation` | The `AssertionError` a failed check throws |
| `FakeUploader` | Records enqueues; for testing code that only decides to enqueue |
| `RecordingWakeScheduler` | Counts arming and disarming |
| `MutableConstraintProvider` | A constraint you flip by hand |
| `MutableUploaderClock` | A wall clock you move by hand, including backwards |
| `RecordingUploadTransport` | Since 1.5.0. Records hand-offs for an `UploadHandler`; settle through `UploadGateway` |

See [`06-testing.md`](06-testing.md).
