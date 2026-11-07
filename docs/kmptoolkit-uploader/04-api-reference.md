# API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.uploader` and
`io.github.jamal_wia.kmptoolkit.uploader.spi`.

## `Uploader`

The narrow interface features depend on.

| Member | Signature | Notes |
|---|---|---|
| `enqueue` | `suspend fun <P : Any> enqueue(handler: UploaderHandler<P>, payload: P, uniqueKey: String? = null, tag: String? = null, conflictPolicy: ConflictPolicy = KEEP): String?` | Suspends until persisted. Returns the new item id, or `null` when a `KEEP` conflict left the queued item in place. The drain trigger and the platform wake are armed even if the caller is cancelled (or the write fails) mid-call, because a row may already have committed. |
| `observe` | `fun observe(type: String): Flow<List<UploaderItem>>` | All states, insertion order. Does not suspend — the query runs on collection. |
| `cancel` | `suspend fun cancel(id: String)` | Since 2.0.0. Withdraws one item in any state: deletes its row, then cancels its upload on its `UploadHandler`'s transport. Touches nothing else. Unknown id: no-op. Best effort for bytes already sent. |
| `cancelByTag` | `suspend fun cancelByTag(tag: String)` | Since 2.0.0. `cancel` for every item with that exact tag — the logout wipe. |
| `trigger` | `fun trigger()` | Conflated, non-suspending, safe from anywhere. |

**Suspension rule:** an operation that touches storage suspends; one that only moves in-memory state
does not. So `enqueue` and `cancel` suspend and `trigger` does not, and you never have to check the docs to know
whether a call can block.

**Thread safety:** all of them are safe to call concurrently, subject to your store's own contract.

## `UploaderEngine : Uploader`

| Member | Signature | Notes |
|---|---|---|
| `start` | `fun start()` | Idempotent. Launches one drain coroutine, wires triggers, drains leftovers. A call after `close()` is a no-op. |
| `drain` | `suspend fun drain()` | One full pass. **Do not call on a started engine** — it breaks single-flight. For tests and diagnostics. |
| `settle` | `suspend fun settle(id: String, claim: Long, result: SettleResult)` | Reports a detached delivery, with the `id` and `claim` from the executor's `AttemptContext`. Every race degrades to a no-op; see below. |
| `awaitDrained` | `suspend fun awaitDrained(timeout: Duration): Boolean` | For a wake job. An `IN_FLIGHT` item counts as **not** drained. An already empty queue is `true` even for a zero timeout. A failing store read never throws: it counts as "not drained" and is re-read at `drainPollInterval`, so a persistent failure ends in `false` at the timeout. |
| `close` | `fun close()` | Idempotent, non-suspending. Stops the engine, leaves the queue and your scope alone. |

### `settle` guards

A report is **current** when its `claim` is the one the item is held under — or the one the drain is
about to write, for a fast executor that reports before `execute` has returned its `Detached`. A
re-hand-off after an expired lease writes a new claim and supersedes every earlier one.

| Situation | Effect |
|---|---|
| Unknown id | No-op |
| `Delivered` / `Drop`, from any claim | Applied — facts about the effect: it landed, or it is no longer owed. A superseded executor that delivered has still delivered. |
| `Failed` / `Park` from the current claim | Applied: `Failed` spends one attempt (and may give up in the same write), `Park` parks |
| `Failed` / `Park` from a superseded claim | No-op — verdicts on an attempt the engine no longer runs; the executor now holding the item reaches its own |
| `Failed` / `Park` on a parked item | No-op — parked stays parked |
| Re-hand-off landing between the settle's read and its write | No-op — the write is a compare-and-set on the claim, so the fresh claim wins |

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

`id`, `attempts`, `uniqueKey`, `orderingKey`, `claim`, `wasDetached`.

`claim` is the claim this attempt runs under: the value `UploaderItem.claim` takes when the attempt's
hand-off is written. Unique per attempt of an item and never reused — unlike the lease, which two
claims can share (written in the same millisecond, or across a clock stepped back). An executor passes
it back to `settle` with the `id`.

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
expire the moment it was written and spin the drain. The engine honours a lease for at most 24 hours:
a longer one (up to `Long.MAX_VALUE`, or an `UploadTransport.leaseMillis` that large) is shortened to
that, and the item is re-handed after a day. The ceiling is what lets the engine tell a lease written
before a backwards wall-clock step from a real one (see the clocks note in
[`05-platform-notes.md`](05-platform-notes.md)).

## `SettleResult`

`Delivered`, `Failed(cause)`, `Drop(reason)`, `Park(reason)` — the same effects as their
`AttemptResult` counterparts. There is deliberately no detached/retry split: an executor never owns
retry policy. `Delivered` and `Drop` apply from any claim; `Failed` and `Park` only from the current
one — see [`settle` guards](#settle-guards).

## `UploadTransport`

```kotlin
public interface UploadTransport {
    public val leaseMillis: Long
    public fun launch(itemId: String, request: UploadRequest)
    public fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) // since 1.5.0, default calls launch(itemId, request)
    public fun cancel(itemId: String) // since 2.0.0; replaces cancelAll()
}
```

An optional, ready-made executor for `AttemptResult.Detached` — see
[`08-upload-transport.md`](08-upload-transport.md). `launch` must be idempotent per item id.
`isRehandOff` is `true` after an expired lease, when the previous executor may have finished without
reporting. `cancel` stops that one item's delivery — in this process or, where the platform keeps
the transfer outside it, one an earlier process started — and settles nothing; an id the transport is
not running is a no-op. Call `Uploader.cancel` rather than this directly for an `UploadHandler`'s
item: it removes the row first, which is what keeps a job that starts later from uploading. There is
no "cancel everything" any more: one transport is shared by several handlers, and cancelling all of it
cancelled their uploads too.

## `UploadHandler<P : Any>` (since 1.5.0)

```kotlin
public abstract class UploadHandler<P : Any>(transport: UploadTransport) : UploaderHandler<P> {
    public abstract suspend fun prepareUpload(context: AttemptContext, payload: P): UploadPreparation
    public open val maxResponseBodyBytes: Int                      // since 3.1.0; default DEFAULT_MAX_RESPONSE_BODY_BYTES
    public open suspend fun classify(payload: P, result: UploadResult, response: UploadResponse?): SettleResult // since 3.1.0
    public open suspend fun classify(payload: P, result: UploadResult): SettleResult // open since 3.1.0; was abstract
    public open suspend fun onUploadProgress(payload: P, fraction: Float)
    public open suspend fun onDelivered(payload: P, response: UploadResponse?) // since 3.1.0
    public open suspend fun onDelivered(payload: P)
    public open suspend fun onSettled(payload: P, attempts: Int, result: SettleResult)
    final override suspend fun execute(context: AttemptContext, payload: P): AttemptResult

    public companion object {
        public const val DEFAULT_MAX_RESPONSE_BODY_BYTES: Int = 65_536 // since 3.1.0
    }
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
| `classify(payload, result, response)` | with the raw outcome and the server's response, for every outcome; the default calls the two-argument `classify` | `SettleResult.Failed` |
| `classify(payload, result)` | by the default three-argument `classify`; the default is `defaultUploadClassification(result)` | `SettleResult.Failed` |
| `onUploadProgress` | per whole percent, while in flight; must not block: on Android a hook still running 2 s after the transfer ended is cancelled | logged, ignored |
| `onDelivered(payload, response)` | for `Delivered`, before the row is removed; the default calls the one-argument `onDelivered` | logged, ignored — the response is then lost |
| `onDelivered(payload)` | by the default two-argument `onDelivered` | logged, ignored |
| `onSettled` | after the item settled; `attempts` as at hand-off | logged, ignored |

`maxResponseBodyBytes` is the most body bytes the handler wants. The engine clamps it to `0..1 MiB` (a
negative value is `0`, logged once), cuts a longer body to it and sets `UploadResponse.isBodyTruncated`;
`0` keeps status and headers and no body. Handlers written for 3.0 are unchanged: the new hooks call
the old ones by default.

`execute`: `Proceed` → `transport.launch(id, wasDetached, request)` and `Detached(transport.leaseMillis)`
(a non-positive lease parks); `Drop`/`Park` → the same `AttemptResult`.

## `UploadGateway` (since 1.5.0)

```kotlin
public object UploadGateway {
    public val DEFAULT_ENGINE_WAIT: Duration // 60 s
    public suspend fun prepareAttempt(itemId: String, engineWait: Duration = DEFAULT_ENGINE_WAIT): UploadAttempt
    public suspend fun progress(itemId: String, fraction: Float)
    public suspend fun complete(itemId: String, result: UploadResult, engineWait: Duration = DEFAULT_ENGINE_WAIT): Boolean
    public suspend fun complete(itemId: String, response: UploadResponse, engineWait: Duration = DEFAULT_ENGINE_WAIT): Boolean // since 3.1.0
}

public sealed interface UploadAttempt {
    public data class Ready(val request: UploadRequest) : UploadAttempt
    public data object NothingOwed : UploadAttempt
    public data object EngineUnavailable : UploadAttempt
}
```

How a transport for `UploadHandler` items reaches the engine registered in `UploaderEngineRegistry`.
`prepareAttempt` is `NothingOwed` for an item gone, not in flight, dropped or parked by its handler, or
whose preparation threw. `complete` returns `false` only when no engine registered within the wait, or
its store failed; an unknown or already settled id is a no-op that returns `true`. `complete(itemId,
response)` (since 3.1.0) reports what the server answered — the engine builds
`UploadResult.Completed(response.statusCode)` from it — while `complete(itemId, result)` is the
status-only form, for a `TransportFailure` or a transport that cannot read the body. `progress` is best
effort and never waits.

## `UploadResponse` (since 3.1.0)

```kotlin
public class UploadResponse(
    public val statusCode: Int,
    headers: Map<String, String>,
    body: ByteArray,
    public val isBodyTruncated: Boolean,
) {
    public val headers: Map<String, String>        // lowercase names, read-only
    public fun body(): ByteArray                   // a fresh copy, never null
    public fun header(name: String): String?       // case-insensitive
}
```

A plain class, not a data class: it holds a `ByteArray` and can grow without an ABI break. Equality is by
content. `toString` prints only counts — `UploadResponse(statusCode=201, headers=3, body=812 B,
truncated=false)` — never headers or body, which may hold tokens or personal data.

- The constructor copies the body once and `body()` copies on every read.
- Headers whose names differ only in case are merged, values joined with `", "`. That is lossy for
  `Set-Cookie`; do not read cookies from here. The engine keeps at most 64 K characters of header names and values.
- A response with no body, or a `204`, has an empty `body()`.
- `isBodyTruncated` is `true` when at least one received body byte is missing from `body()` — over the
  handler's `maxResponseBodyBytes` (limit `0` with any body counts), or a body a transport could not
  read completely — the connection broke or the read timed out — in which case `body()` may be shorter
  than the limit, even empty. It never causes a retry.

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
`Data` cap on Android. Field and file names are sent percent-escaped for `"`, CR and LF (`%22`, `%0D`, `%0A`)
as browsers do, and CR and LF are removed from a file's `contentType`, on both platforms; text values are
body content and go out untouched.

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

A default, not a policy this module can claim to know for your API — override `UploadHandler.classify`,
or pass your own `classify` to `createWorkManagerUploadTransport`, when a status should map differently.
`UploadResult` carries only the status; the server's headers and body travel in `UploadResponse`.

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

A data class of fourteen fields — see its KDoc for each; `claim` is the row's claim counter. States: `PENDING`, `IN_FLIGHT`, `PARKED`.
There is deliberately no `RUNNING` state; a crash mid-execution needs no recovery pass because the
row is still `PENDING` on the next launch.

`lastError` is at most about 4096 characters: the engine truncates longer failure text (cutting on a character
boundary and appending `… [truncated N chars]`) before it reaches a store, and reads an exception whose
`message` getter throws safely. A custom store's `last_error` column can therefore stay plain `TEXT`.

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
registers on your behalf. `close()` clears the slot if the closing engine holds it, and registering an engine that is already closed (or closes while `register` runs) is ignored, so a closed engine is never handed out. An engine stopped by cancelling the scope it was created in (without `close()`) is just as inert: it is unregistered, `current` and `await` never return it, and registering one is ignored. `await` with a zero timeout still returns an engine that is registered at that moment. `await` returning
`null` means the outcome did not land, so a platform executor should ask to be retried.

## SPI — `io.github.jamal_wia.kmptoolkit.uploader.spi`

### `UploaderStore`

Eleven functions; the full contract is in the interface KDoc and in
[`07-custom-store.md`](07-custom-store.md). The invariants in one line each:

- Durable before returning; atomic per function; `recordFailure`, `markInFlight` and `park` are each a
  single compare-and-set on the state and claim the caller read, returning whether it wrote —
  `recordFailure` giving up (`FailureDisposition.PARK` or `DROP`) in that same write. None has an
  unguarded form.
- `UploaderItem.claim` is `0` on insert and advances by exactly one in every guarded write that
  applies, and in no other way — the engine derives the claims it hands out from that rule.
- `insertReplace` returns the item it superseded and `deleteByTag` the items it removed, each read in
  the same atomic step as its delete — the engine cancels exactly those uploads.
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
from arbitrary threads. Seed optimistically. If reading `satisfied` throws, the engine logs it and skips
that item for the pass (as if unsatisfied) without affecting the items behind it.

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
| `UploadHandlerWorker` | Since 1.5.0. Reads the server's response since 3.1.0. Open, with `protected open fun readItemId(inputData)` for taking over an earlier worker's jobs; input keys in its companion |

## iOS

| Symbol | Notes |
|---|---|
| `createBackgroundTaskWakeScheduler(config, logger)` | Returns the scheduler; keep the reference |
| `BackgroundTaskWakeConfig(taskIdentifier = null, requiresNetworkConnectivity = true, requiresExternalPower = false, engineWait = 5.seconds, drainBudget = 25.seconds)` | `taskIdentifier` defaults to `<bundleId>.uploader.drain` |
| `BackgroundTaskWakeScheduler.taskIdentifier` | The resolved string — must be in `Info.plist` |
| `BackgroundTaskWakeScheduler.handleWake(onDone)` | Call from your Swift `BGTaskScheduler` handler; `onDone` runs even if the drain throws |
| `createBackgroundUploadTransport(config, logger)` | Since 1.5.0. Background `NSURLSession` per item, for `UploadHandler` items; reads the server's response since 3.1.0 |
| `BackgroundUploadConfig(sessionIdentifierPrefix = null, lease = 60.minutes, rehandFlushWindow = 3.seconds, requestTimeout = 60.seconds, resourceTimeout = 7.days)` | `sessionIdentifierPrefix` defaults to `<bundleId>.uploader.upload.`. `requestTimeout` (idle timeout) and `resourceTimeout` (whole transfer, retries included) since 3.1.0 — the system defaults, both must be positive and finite; see [timeouts](08-upload-transport.md#timeouts). The three-parameter constructor remains as a deprecated overload (without default arguments) so Swift callers of `init(sessionIdentifierPrefix:lease:rehandFlushWindow:)` still compile |
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
| `RecordingUploadTransport` | Since 1.5.0. Records hand-offs for an `UploadHandler`; settle through `UploadGateway` — `complete(itemId, UploadResponse)` hands the handler a server answer (since 3.1.0) |

See [`06-testing.md`](06-testing.md).
