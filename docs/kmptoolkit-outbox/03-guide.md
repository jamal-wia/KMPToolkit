# Guide

Scenarios in roughly increasing order of subtlety.

## The item lifecycle

```
                        enqueue
                           │
                           ▼
   ┌──────────────────► PENDING ◄──────────────────┐
   │                       │                       │
   │  lease expires        │ execute()             │ recordFailure
   │  un-settled           ▼                       │ (Retry / settle Failed)
   │           ┌───────────┼───────────┬───────────┴──────┐
   │           │           │           │                  │
   │        Success      Drop        Park            Detached
   │           │           │           │                  │
   │           ▼           ▼           ▼                  ▼
   │       (deleted)   (deleted)    PARKED ◄─────────  IN_FLIGHT
   │                                   ▲                  │
   └───────────────────────────────────┼──────────────────┘
                                       │          settle(Delivered/Drop/Park/Failed)
                    a fresh enqueue with the same unique key revives a PARKED item
```

Three states, and only two ways out of the queue: a confirmed delivery, or a decision.

## Deduplicating an effect

A unique key makes an effect idempotent at the *queue* level, before it ever reaches the network.

```kotlin
outbox.enqueue(handler, payload, uniqueKey = "message-$localId")           // KEEP (default)
outbox.enqueue(handler, payload, uniqueKey = "draft-$docId",
    conflictPolicy = ConflictPolicy.REPLACE)
```

- **`KEEP`** — an already-queued effect wins and the new call returns `null`. This is what you want
  for "make sure this happens": a second tap on *send* does not send twice. It loses to an
  in-flight item too, so a detached delivery is not duplicated by a re-enqueue.
- **`REPLACE`** — the new effect supersedes the queued one, resetting payload, retry budget, and
  queue position. This is what you want for "only the latest matters": a draft autosave, a presence
  ping, a settings push.

A parked item is superseded under **either** policy. A parked item has no other way back, so
letting it keep its key would make that key permanently dead, and `KEEP` would silently swallow
every future enqueue for it.

Keyless items never conflict. Every keyless enqueue appends.

## Ordering

`orderingKey` puts items into strict-FIFO channels:

```kotlin
override fun orderingKey(payload: SendMessage): String = payload.conversationId
```

**What is guaranteed:** within one `(handler type, ordering key)` pair, items are attempted strictly
oldest-first, and a head that fails blocks its tail until it succeeds, drops, or parks. A blocked
tail spends no retry budget while it waits.

**What is not guaranteed:** anything across channels. Two different conversations, two different
handler types, or two keyless items may be delivered in any order and concurrently with each other's
retries. If you need one global order for a type, return a constant.

Channels are scoped per handler type, so two handlers that happen to key on the same raw string get
separate channels and one type's backoff can never stall another's.

An in-flight head keeps blocking its channel: a delivery handed to an executor is still the oldest
owed effect. A head that parks leaves its channel, so a permanently broken message does not freeze
a conversation forever.

## Retry and giving up

```kotlin
override val retryPolicy: RetryPolicy = ExponentialBackoffRetryPolicy(
    baseDelayMillis = 2_000,
    maxDelayMillis = 10 * 60_000,
    jitterRatio = 0.2,
    giveUp = GiveUpPolicy.ParkAfterAttempts(maxAttempts = 20),
)
```

Backoff is a pure function of the item's **persisted** attempt count, so a budget survives a
restart rather than resetting to zero on every launch. Jitter matters more than it looks: without
it, everything queued while a device was in a tunnel retries in lockstep and hits your server as one
spike.

`GiveUpPolicy` defaults to `Never`, and the default is deliberate. A generic attempt counter cannot
tell "twenty real rejections" from "twenty retries while offline", so giving up on that basis alone
risks discarding a good effect for a bad network. Prefer deciding inside `execute`, where you can
see the actual failure, and use `ParkAfterAttempts` as a backstop rather than as the main mechanism.
Reach for `DropAfterAttempts` only for effects nobody would miss.

Writing your own policy is three members — a fixed delay, a server-driven schedule, a curve that
flattens at night. `maxDelayMillis` must be honest: the engine uses it to recognize a backoff gate
that the device's clock jumped over.

## Waiting for a precondition

A `ConstraintProvider` is a live boolean an effect can gate on. Handlers name the ones they need:

```kotlin
override val constraintKeys: Set<String> = setOf("network", "socket_connected")
```

All named constraints must hold. The engine also *subscribes*: a `false → true` transition triggers
a drain, so effects fire on the transition rather than on the next heartbeat.

A key with no registered provider is treated as satisfied and logged as an error. Failing open is
deliberate — a typo that silently freezes a queue forever is much harder to notice than one extra
delivery attempt.

## Wiping a user's queue on logout

Effects enqueued by one account must never replay under another's credentials. Tag them, then wipe
the tag:

```kotlin
outbox.enqueue(handler, payload, tag = "session-$userId")

// on logout
store.deleteByTag("session-$userId")
```

The tag is opaque — the library never interprets it — and the wipe crosses every state, including
in-flight and parked items.

## Making a domain write and its effect atomic

This is the "transactional" half, and it needs the queue and your domain tables in the same
database:

```kotlin
val outbox: OutboxEngine = createOutboxEngine(
    store = store,
    handlers = handlers,
    scope = scope,
    transactionRunner = myDatabaseTransactionRunner,
)

transactionRunner.inTransaction {
    messagesDao.insert(message)
    outbox.enqueue(sendMessageHandler, SendMessage(message))
}
```

`enqueue` opens a transaction of its own, so your `TransactionRunner` **must** be reentrant — a
nested call has to join the outer transaction rather than start a second one. If it does not, the
two halves can commit independently and the guarantee you came for is gone.

The default, `TransactionRunner.Direct`, simply runs the block. That is the honest behavior when
enqueueing is the only write, which is the common case.

## Detached delivery, for work that outlives an attempt

Most handlers never need this. Use it when delivery is long enough that holding a coroutine open for
it is wrong — a multi-hundred-megabyte upload that should continue while the app is backgrounded:

```kotlin
override suspend fun execute(context: AttemptContext, payload: UploadVideo): AttemptResult {
    // Idempotent by contract: if an executor for this id already exists, join it.
    uploadExecutor.enqueueUnique(id = context.id, rejoinIfPresent = context.wasDetached)
    return AttemptResult.Detached(leaseMillis = 30 * 60_000)
}
```

The engine moves the row to `IN_FLIGHT` with a lease. Later, from wherever the upload finishes:

```kotlin
val engine: OutboxEngine = OutboxEngineRegistry.await(5.seconds) ?: return Result.retry()
engine.settle(itemId, SettleResult.Delivered)
```

**The lease is a claim, not a lock.** While it is unexpired the drain leaves the item alone. Once it
expires the item becomes attemptable again and `execute` runs afresh with
`context.wasDetached == true` — which is precisely why the hand-off must be idempotent. A previous
executor may have completed and merely failed to report (the app died right after the upload
finished), so a re-hand-off should probe or rejoin rather than start over.

Size the lease to the executor's realistic worst case. Too short and you get redundant hand-offs
racing a live executor — wasteful but survivable. Too long and recovery from a silently dead
executor is delayed by exactly that much.

Settling is addressed by item id, which makes every race degrade to a no-op: a superseded item was
re-inserted under a new id, a wiped item is gone, and a duplicate settle finds nothing. `Failed` is
additionally ignored for an item that is no longer in flight, and rejected if the item was re-handed
between the settle's read and its write.

One window stays open: an executor whose lease expired long ago, whose item was already re-handed,
and which only then reports `Failed`. That report spends an attempt and returns the item to pending.
The consequence is a possible redundant hand-off, never a lost effect — which is the same
at-least-once bargain the rest of the module makes.

## Draining on demand

```kotlin
outbox.trigger()                       // cheap, non-suspending, conflated — safe from a hot path
outbox.awaitDrained(60.seconds)        // suspend until the queue is empty, for a wake job
```

`trigger()` is for "the user pulled to refresh" or "a push told us to sync". You do not need it
after an enqueue.

`drain()` exists too, but calling it on a started engine breaks single-flight. It is for tests and
diagnostics.

## Shutting down

```kotlin
outbox.close()
```

Stops the drain, the heartbeat, the alarm and the constraint subscriptions, and clears the registry
slot if this engine holds it. It does not cancel the scope you provided and does not touch the
queue — everything owed is still owed, and a freshly constructed engine picks it up. Cancelling your
own scope stops the engine just as effectively.

## Logging

Pass a `Logger` and every enqueue, delivery, retry, park, drop and settle is logged with the item's
`type/uniqueKey`. Reconstructing the life of one item from a bug report is then a `grep`. Without a
logger the engine is silent, which is a poor trade in production.

Next: [`04-api-reference.md`](04-api-reference.md).
