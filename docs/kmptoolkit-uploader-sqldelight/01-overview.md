# kmptoolkit-uploader-sqldelight — overview

The storage half of [`kmptoolkit-uploader`](../kmptoolkit-uploader/01-overview.md), on SQLite via
SQLDelight.

`kmptoolkit-uploader` ships with **no database dependency at all**: persistence is an SPI
(`UploaderStore` plus `TransactionRunner`) that you implement against whatever your app already
stores things in. That is the right shape for the engine — it is what lets one well-tested engine
sit on Room, Realm, a file, or a map — but it does leave a real task on your desk. This module is
that task, done, for the case where SQLDelight is what you already use.

```kotlin
val storage: UploaderStorage = createUploaderStorage(context)

val engine: UploaderEngine = createUploaderEngine(
    store = storage.store,
    transactionRunner = storage.transactionRunner,
    scope = applicationScope,
)
```

Two lines instead of the roughly 200 that
[`07-custom-store.md`](../kmptoolkit-uploader/07-custom-store.md) walks you through — and, more to the
point, the three parts of that page that are easy to get subtly wrong are already right here, and
proved right by `UploaderStoreContract` running against a real database on both platforms:

- **The insertion sequence.** `getAllActive` returns items in insertion order — a monotonic
  sequence, not `created_at`. Two enqueues in the same millisecond keep their order, and a deleted
  row's position is never handed to a later one, because the column is
  `INTEGER PRIMARY KEY AUTOINCREMENT` rather than a bare rowid.
- **The compare-and-set.** `recordFailure`'s optimistic lease guard is one `UPDATE` with the guard
  in its `WHERE` clause, not a read followed by a write — a read-then-write loses exactly the race
  the guard exists to win.
- **The reentrant transaction.** A nested `inTransaction` joins the outer one rather than opening a
  second, so a domain write and the effect it owes really do commit together.

## What you get

- **`UploaderStore` and `TransactionRunner`**, as a pair, from one factory.
- **Two ways to open it.** Standalone, on a database file of its own, named after your app so two
  queues never collide. Or on a `SqlDriver` you already own — which is the one that makes the
  uploader genuinely transactional, because the queue rows and your domain rows are then in the same
  database.
- **A published schema.** `uploaderDatabaseSchema` is what you create and migrate when the queue
  lives in your database. Its table is `kmptoolkit_uploader_item` and every index carries the same
  prefix, so nothing it creates can collide with a table of yours.
- **Typed failures.** A SQLite exception never crosses the SPI: it arrives as
  `UploaderStorageException` naming the operation that failed.
- **No permissions, no manifest entries, no `Info.plist` keys.** The queue lives in your app's own
  private data directory.

## What this is not

- **Not a general-purpose database wrapper.** It owns exactly one table and offers no way to put
  anything else in it. If you want a database, use SQLDelight directly; this module is the queue.
- **Not a way to avoid `kmptoolkit-uploader`.** It implements that module's SPI and does nothing on
  its own — no retry, no backoff, no ordering, no wake-ups. Every policy decision lives in the
  engine. On its own, this module is a table with eleven functions over it.
- **Not automatic transactional atomicity.** Opening the standalone file gives you a durable queue,
  but your domain tables are in a different database and no transaction spans both. Atomicity
  between a domain write and its owed effect requires the queue to be in **your** database — see
  [`03-guide.md`](03-guide.md). The standalone factory does not pretend otherwise, and neither
  should your design.
- **Not asynchronous-driver compatible.** It targets the synchronous drivers, `android-driver` and
  `native-driver`. A driver that returns `QueryResult.AsyncValue` — the web worker driver — is out
  of scope, along with the JS and JVM targets it implies.
- **Not a migration tool for your database.** It creates and migrates its own schema. Reconciling
  that with your app's own schema version is a documented procedure, not an automatic one.
- **Not shareable between two processes.** One `UploaderStorage` per queue per process. SQLite would
  let two processes open one file; the engine's single-flight drain assumes it is the only one
  working the queue, and two of them would deliver some effects twice.

## Where to go next

| You want | Read |
|---|---|
| A working queue in five minutes | [`02-getting-started.md`](02-getting-started.md) |
| The schema, migrations, and putting the queue in your own database | [`03-guide.md`](03-guide.md) |
| Every public symbol | [`04-api-reference.md`](04-api-reference.md) |
| File locations, driver differences, WAL and threading | [`05-platform-notes.md`](05-platform-notes.md) |
| What the engine does with all this | [`kmptoolkit-uploader/01-overview.md`](../kmptoolkit-uploader/01-overview.md) |
| Implementing a store yourself instead | [`kmptoolkit-uploader/07-custom-store.md`](../kmptoolkit-uploader/07-custom-store.md) |
