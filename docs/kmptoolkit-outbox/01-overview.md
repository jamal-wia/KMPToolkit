# kmptoolkit-outbox — overview

A transactional outbox for shared Kotlin code: your app records an outgoing effect **as data**, and
this module keeps trying to deliver it until it lands — across offline stretches, process death, and
OS-level wake-ups.

The problem it solves is the one every offline-capable app hits eventually. The user taps *send*,
the request fails, and now the app has to decide: pretend it worked, lose the message, or grow a
bespoke retry loop. That third option is what usually happens, once per feature, each with its own
half of the problem solved — chat retries but does not survive a restart, the diary survives a
restart but retries in a tight loop, neither of them stops when the user logs out.

An outbox turns "the app owes the server something" into a row. Sending is not a request that either
succeeds or is forgotten; it is a debt that is settled or still outstanding.

```kotlin
// The write and the queue entry commit together — there is no state where the message
// exists locally with nothing queued to send it, or the reverse.
transactionRunner.inTransaction {
    messagesDao.insert(message.copy(pending = true))
    outbox.enqueue(sendMessageHandler, SendMessage(message), uniqueKey = message.id)
}
```

From there the engine owns it: exponential backoff with jitter, strict FIFO per conversation, a
pause while the device is offline and an immediate retry when it is back, a WorkManager job that
finishes the queue after the app was killed, and — for a genuinely long upload — a hand-off to an
external executor under a self-expiring lease.

## What you get

- **Durable enqueue.** `enqueue` suspends until the effect is persisted. After it returns, the
  effect survives process death.
- **At-least-once delivery.** An item is deleted only after its handler confirms success. Handlers
  are expected to be idempotent, and the item id is a ready-made idempotency key.
- **Nothing disappears quietly.** Every path that removes an item is either a confirmed delivery or
  a decision you made — a handler's `Drop`, or a give-up policy you configured. Anything else
  *parks*: kept, visible, out of rotation, revivable.
- **Ordering where you want it, and only there.** An ordering key gives strict FIFO within one
  channel (a conversation, a document) while other channels keep flowing independently.
- **Retry policy per effect type**, injected and pure, so it is testable under virtual time instead
  of a wall clock.
- **Constraint gating.** An effect can wait for a live precondition — connectivity, a connected
  socket — and the queue re-drains the moment it flips.
- **A platform wake layer.** WorkManager on Android, `BGTaskScheduler` on iOS, both optional.
- **Detached delivery.** A handler can hand a long upload to an external executor and settle it
  later, under a lease that expires so a dead executor cannot strand the item forever.

## Storage is yours

**This module has no database dependency at all.** Persistence is an SPI —
[`OutboxStore`](07-custom-store.md) — with a documented contract, a reference implementation you can
read in one sitting (`InMemoryOutboxStore`), and a runnable contract check
(`OutboxStoreContract`) that holds your implementation to every invariant the engine relies on.

That is a deliberate reversal of how the donor code this module came from was written: there, the
engine compiled against a generated SQLDelight database type, which made a well-tested engine
unusable by anyone with a different database. Here the engine never learns what a table is.

If you use SQLDelight, `kmptoolkit-outbox-sqldelight` is a ready store. If you use Room, Realm,
`NSUserDefaults`, or a file, [`07-custom-store.md`](07-custom-store.md) is a walkthrough.

## What this is not

- **Not a job scheduler.** There is no "run this at 9am", no periodic work, no cron. The engine
  runs things *as soon as it can*, and the only time input is a backoff gate. If you want to run
  code at a specific time, that is [`kmptoolkit-scheduler`](../kmptoolkit-scheduler/01-overview.md).
- **Not a database.** It stores effects, not your domain. There is no query API, no migration story,
  no schema — the payload is an opaque string and the store is something you bring.
- **Not a sync engine.** It is strictly outbound. Pulling changes from a server, merging them, and
  resolving conflicts are all outside its scope; it only makes sure what you decided to send gets
  sent.
- **Not a network client.** It never opens a socket. `execute` is your code, using your HTTP client,
  and the module declares no `INTERNET` permission.
- **Not a delivery guarantee stronger than at-least-once.** Exactly-once does not exist across a
  process boundary. The engine gives you a stable idempotency key and expects the server to
  deduplicate.
- **Not a background-execution grant.** The wake layer asks the OS for time; on iOS the OS very
  often says no. See [`05-platform-notes.md`](05-platform-notes.md) before promising a user that
  something will send while the app is closed.

## Shape of the API

| Type | Role |
|---|---|
| `Outbox` | What features depend on: `enqueue`, `observe`, `trigger` |
| `OutboxEngine` | `Outbox` plus lifecycle: `start`, `drain`, `settle`, `awaitDrained`, `close` |
| `OutboxHandler<P>` | One per effect type: encode, decode, order, deliver |
| `AttemptResult` | What a delivery attempt concluded |
| `SettleResult` | What a *detached* delivery concluded |
| `RetryPolicy` | How failures are paced; `ExponentialBackoffRetryPolicy` is the built-in |
| `OutboxStore` | **The SPI you implement** — persistence |
| `TransactionRunner` | Makes a domain write and its owed effect atomic |
| `ConstraintProvider` | A live precondition effects can wait for |
| `WakeScheduler` | The OS wake layer; adapters ship for both platforms |

Next: [`02-getting-started.md`](02-getting-started.md).
