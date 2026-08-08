# kmptoolkit-outbox-sqldelight — guide

The schema, what to do when your own database has to migrate too, and the handful of rules that
keep a transaction meaning what you think it means.

## The two ways to open a queue, and why the second one exists

### Standalone: its own file

```kotlin
val storage: OutboxStorage = createOutboxStorage(context)   // Android
val storage: OutboxStorage = createOutboxStorage()          // iOS
```

Durable, self-contained, nothing to migrate. What it cannot give you is atomicity with your own
data: the queue file and your app's database are two SQLite files, and no transaction spans two
files. This is still the right choice when enqueueing **is** the only write — which covers most
handlers — and `TransactionRunner` remains useful there, because it makes a burst of enqueues one
commit instead of several.

### Shared driver: inside the database you already have

```kotlin
val storage: OutboxStorage = createOutboxStorage(myDriver)

storage.transactionRunner.inTransaction {
    messagesQueries.markSending(id)      // your table
    outbox.enqueue(sendMessage, id)      // the queue table
}
```

Now both writes are in one transaction on one connection, so there is no instant at which the
message is marked *sending* with nothing queued to send it, or the reverse. This is what
"transactional outbox" actually means, and it is the reason `kmptoolkit-outbox` made storage a port
rather than shipping a database of its own.

The price is two responsibilities, below.

## The schema

One table and four indices, all prefixed `kmptoolkit_outbox_` so nothing can collide with a table of
yours:

```sql
CREATE TABLE kmptoolkit_outbox_item (
    sequence        INTEGER PRIMARY KEY AUTOINCREMENT,
    id              TEXT    NOT NULL UNIQUE,
    type            TEXT    NOT NULL,
    payload         TEXT    NOT NULL,
    schema_version  INTEGER NOT NULL,
    unique_key      TEXT,
    ordering_key    TEXT,
    tag             TEXT,
    state           TEXT    NOT NULL,   -- PENDING | IN_FLIGHT | PARKED
    attempts        INTEGER NOT NULL,
    next_run_at     INTEGER NOT NULL,
    created_at      INTEGER NOT NULL,
    last_error      TEXT,
    lease_until     INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX kmptoolkit_outbox_item_identity
    ON kmptoolkit_outbox_item(type, unique_key) WHERE unique_key IS NOT NULL;
CREATE INDEX kmptoolkit_outbox_item_state ON kmptoolkit_outbox_item(state, sequence);
CREATE INDEX kmptoolkit_outbox_item_type  ON kmptoolkit_outbox_item(type, sequence);
CREATE INDEX kmptoolkit_outbox_item_tag   ON kmptoolkit_outbox_item(tag);
```

Two columns are worth understanding rather than skimming.

**`sequence` is the insertion order**, and it is not `created_at`. Timestamps have millisecond
resolution, so a burst of enqueues inside one millisecond has one timestamp; the engine derives its
FIFO channel heads from `getAllActive`, so ordering by `created_at` would silently shuffle messages
under exactly the load where order matters. `AUTOINCREMENT` rather than a bare
`INTEGER PRIMARY KEY` is also deliberate: SQLite reuses a plain rowid once the highest row is
deleted, so a row enqueued **after** a successful delivery could sort **before** rows that were
already waiting.

**`(type, unique_key)` is the dedup identity**, in a *partial* unique index. Keyless rows are not in
the index at all, so any number of them can coexist — which is what "a null unique key never
conflicts" means in the SPI.

Superseding a row — either conflict policy — is always a delete plus an insert, never an in-place
edit. The replacement therefore takes a new `sequence` and enters at the tail, which is what stops a
repeatedly-replaced key from holding the head of its ordering channel forever.

## Migrations

### This module's schema

It has its own version, currently **1**, dumped to
`kmptoolkit-outbox-sqldelight/src/commonMain/sqldelight/databases/1.db` and verified on every build,
so a change to the table that forgets its migration fails here rather than on a user's upgrade.

For a **standalone** storage there is nothing for you to do: the driver creates the schema on first
open and migrates it on an upgrade.

### When the queue is in *your* database

Your database has its own version, and this module's schema is a table inside it. Reconciling the
two is a documented procedure rather than an automatic one, because a library silently issuing DDL
against your database on startup is exactly the kind of thing you would want to have approved.

**Adding the queue to an existing database** is a migration of *yours*. In the `.sqm` that moves
your database to the version which first has a queue:

```kotlin
// YourDatabase.Schema.migrate(...) runs your .sqm files. Run ours from the matching
// AfterVersion callback, so the table is created exactly once, at a version you chose.
YourDatabase.Schema.migrate(
    driver = driver,
    oldVersion = old,
    newVersion = new,
    AfterVersion(7) { outboxDatabaseSchema.create(it) },
)
```

Equivalently, if you build the driver with `AndroidSqliteDriver(YourDatabase.Schema, ...)`, pass the
same `AfterVersion` callback there.

**A fresh install** creates your schema at its current version, so the queue table has to be created
alongside it:

```kotlin
val driver = AndroidSqliteDriver(
    schema = object : SqlSchema<QueryResult.Value<Unit>> by YourDatabase.Schema {
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            YourDatabase.Schema.create(driver)
            outboxDatabaseSchema.create(driver)
            return QueryResult.Unit
        }
    },
    context = context,
    name = "your-app.db",
)
```

**When this module's own schema changes** — a new column in a future release — the upgrade is not
carried by your database's version, because your version did not move. Two options, and the release
that introduces such a change will say which applies:

1. Bump *your* database version and run `outboxDatabaseSchema.migrate(driver, from, to)` from that
   migration. This is the recommended route: your database has exactly one version number, and
   everything in it moves with that number.
2. Recreate the queue table, if and only if losing queued effects is acceptable — it is not, in
   almost every app, which is why the queue exists.

This module treats its schema as public API for exactly this reason. A change to it is a change to
your migration path, and is called out under a `Breaking` heading in `CHANGELOG.md`.

## Transactions: the three rules

`TransactionRunner` here is a real SQL transaction, and it is **reentrant** — a nested
`inTransaction` joins the outer one rather than opening a second. That is what lets your code open a
transaction, write a domain row, and call `outbox.enqueue(...)`, which opens one of its own several
frames down.

Three things follow from how it is implemented, and all three are easy to get right once seen.

**1. Every statement runs on one dedicated thread.** SQLDelight transactions are confined to the
thread that opened them, so this module pins a thread per `OutboxStorage` rather than borrowing a
pool. You never have to think about it — the store dispatches for you — with one exception, below.

**2. Do not switch dispatchers inside `inTransaction`.**

```kotlin
// WRONG — fails with IllegalStateException: the write would have run on another
// thread, outside the transaction, and committed separately.
storage.transactionRunner.inTransaction {
    withContext(Dispatchers.IO) { outbox.enqueue(...) }
}

// RIGHT — everything in the block runs on the database thread already.
storage.transactionRunner.inTransaction {
    myQueries.markSending(id)
    outbox.enqueue(...)
}
```

The failure is deliberate and immediate. A coroutine context element survives a dispatcher switch
but the invariant it stands for does not, so a store call that has left the database thread is
detected and refused rather than allowed to write outside the transaction that is still open. A
silent success there is the exact bug a transactional outbox exists to prevent, so it is the one
thing this module will not do quietly. Note that this can only catch **this module's** statements —
a write of yours through your own queries is not something this module sees.

**3. Do not await anything slow inside `inTransaction`.** A network call in a transaction block
holds the database thread and SQLite's write lock until it returns. That is bad advice in any
database; here it also stalls every other queue operation. Do the slow work first, then open the
transaction — or, better, put the slow work in a handler, which is what the outbox is for.

A block that throws rolls the transaction back and the exception propagates unchanged, including
`CancellationException`: a cancelled coroutine never leaves a half-applied transaction behind.

Collecting `observeByType` from inside a transaction is fine — the flow recognizes that it is
already on the database thread rather than dispatching onto it — though what it shows you is the
uncommitted state your own transaction has written so far.

## Failures

Nothing platform-specific escapes. A SQLite failure — a full disk, a corrupt file, a database that
cannot be opened — arrives as `OutboxStorageException`, carrying the `OutboxStorageOperation` that
was running and the platform cause.

It is thrown rather than returned because `OutboxStore` has no result type, and swallowing a failed
write would be worse than throwing: the engine would believe an effect is queued that is not, and
`enqueue`'s promise to its caller — "after this returns, the effect survives process death" — would
be quietly false. Treat it the way you treat any storage failure: it is not a message to show, and
the queue is the wrong place to retry it from.

## Two queues in one app

Give them different names, and they get different files and different threads:

```kotlin
val chat: OutboxStorage = createOutboxStorage(context, OutboxDatabaseConfig("com.example.chat"))
val uploads: OutboxStorage = createOutboxStorage(context, OutboxDatabaseConfig("com.example.uploads"))
```

Two `OutboxStorage` instances over the **same** file, on the other hand, is not something to do. The
engine's drain is single-flight on the assumption that it is the only one working the queue; two of
them would deliver some effects twice. One storage per queue per process.

## What lives where

| Concern | Owner |
|---|---|
| Which item runs next, backoff, give-up, lease expiry, ordering | `kmptoolkit-outbox`'s engine |
| Rows, indices, transactions, insertion order | this module |
| The payload's meaning, delivery, idempotency | your handler |

If a question is about *policy*, the answer is in
[`kmptoolkit-outbox/03-guide.md`](../kmptoolkit-outbox/03-guide.md). This module has no policy at
all.
