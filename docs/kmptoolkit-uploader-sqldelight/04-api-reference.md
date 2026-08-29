# kmptoolkit-uploader-sqldelight — API reference

Every public symbol, its contract, and its thread-safety. Package
`io.github.jamal_wia.kmptoolkit.uploader.sqldelight`.

The module's surface is deliberately small: two factory functions, one interface, one config, one
exception, one schema handle. Everything you actually *do* with a queue is
`kmptoolkit-uploader`'s API — see
[its reference](../kmptoolkit-uploader/04-api-reference.md).

## `UploaderStorage`

```kotlin
public interface UploaderStorage {
    public val store: UploaderStore
    public val transactionRunner: TransactionRunner
    public fun close()
}
```

The two SPI implementations `kmptoolkit-uploader` needs, over one SQLite database. They come as a pair
because they are only correct together: both confine every statement to the same thread, and a store
and a runner on two different threads would produce a transaction that does not contain the writes
made inside it, with nothing reporting the mismatch.

| Member | Contract |
|---|---|
| `store` | Implements `UploaderStore` in full — every invariant in its KDoc, verified by `UploaderStoreContract` against a real database on Android and iOS. Every write is committed before the call returns. Safe to call from any coroutine on any thread; calls are serialized internally. |
| `transactionRunner` | A real SQL transaction, reentrant: a nested `inTransaction` joins the outer one. Atomic across everything written through **this** database. Rolls back and propagates unchanged on a throw or a cancellation. |
| `close()` | Releases the database thread, and the connection if this storage opened one. Idempotent. Never closes a driver you supplied. Using the storage afterwards is a bug. |

**Lifetime.** One instance per queue, per process, for the life of the process. Not per screen — the
queue outlives the UI, which is the point of it.

## `createUploaderStorage(driver)` — common

```kotlin
public fun createUploaderStorage(driver: SqlDriver): UploaderStorage
```

Puts the queue in a database **you** own. This is the route that makes the uploader genuinely
transactional, because the queue rows and your domain rows are then behind one driver and one
transaction.

- **You own the schema.** This function does not create the table; adding a table to your database
  is a migration you have to version. See [`03-guide.md`](03-guide.md).
- **You own the driver.** `close()` never closes it.
- **Synchronous drivers only** — `AndroidSqliteDriver`, `NativeSqliteDriver`. A driver returning
  `QueryResult.AsyncValue` is out of scope.
- **Your writes must go through `transactionRunner`** to share a transaction with an enqueue.

**Throws** nothing on its own; failures surface from the first operation.

## `createUploaderStorage(context, config)` — Android

```kotlin
public fun createUploaderStorage(
    context: Context,
    config: UploaderDatabaseConfig = UploaderDatabaseConfig(),
): UploaderStorage
```

Opens the queue on its own file in the app's private `databases/` directory, creating the schema on
first use and migrating it on an upgrade. Only the application context is retained, so passing an
Activity cannot leak it.

**Throws** `UploaderStorageException(OPEN, …)` if the file cannot be opened, created or migrated.

## `createUploaderStorage(config)` — iOS

```kotlin
public fun createUploaderStorage(config: UploaderDatabaseConfig = UploaderDatabaseConfig()): UploaderStorage
```

The same, on the iOS file system. See [`05-platform-notes.md`](05-platform-notes.md) for where the
file lands.

**Throws** `UploaderStorageException(OPEN, …)`.

> The factories are per-platform, not `expect`/`actual`: Android needs a `Context` and iOS needs
> nothing, and a shared signature would force one of them to declare a parameter it ignores. See
> [`docs/01-architecture.md`](../01-architecture.md).

## `UploaderDatabaseConfig`

```kotlin
public data class UploaderDatabaseConfig(public val name: String? = null)
```

Which file the standalone factories open. Irrelevant when you supply a driver.

| `name` | Meaning |
|---|---|
| `null` (default) | Resolved at runtime to the app's own identifier: `Context.getPackageName()` on Android, `CFBundleIdentifier` on iOS |
| a string | Used verbatim as the identifying part of the file name |

**Rejected at construction**, with `IllegalArgumentException`: blank names, and names containing
`/`, `\`, a space, or a NUL. The name becomes part of a file name verbatim, so a separator in it
would write the queue outside the directory the platform expects — and that is a bug in a literal
you wrote, not a runtime condition to recover from.

Two different names give two different files. The resolved file name always carries this module's
own namespace, so a name that happens to match one of your other databases still gets a distinct
file.

## `uploaderDatabaseSchema`

```kotlin
public val uploaderDatabaseSchema: SqlSchema<QueryResult.Value<Unit>>
```

The queue table's schema — `create` and `migrate` — for the shared-driver route. The standalone
factories use it for you.

Creates `kmptoolkit_uploader_item` and four indices, all carrying that prefix, so nothing it creates
can collide with a table of yours. Its `version` is this module's own and moves with this module's
releases; it is not your database's version, and [`03-guide.md`](03-guide.md) explains how the two
are reconciled.

## `UploaderStorageException`

```kotlin
public class UploaderStorageException : RuntimeException {
    public val operation: UploaderStorageOperation
    override val cause: Throwable?
}
```

A database operation failed. Thrown, not returned, because `UploaderStore` has no result type and
swallowing a failed write would silently break the durability promise `enqueue` makes to its caller.

Nothing platform-specific escapes: a `SQLiteException` or a Darwin `sqlite3` error is always the
`cause` of one of these, never the thing you catch.

`CancellationException` is **not** wrapped — structured concurrency would break if a cancelled
coroutine looked like a database fault.

Not a message to display. `operation` and `cause` are diagnostics; deciding what a user sees is the
app's job, as everywhere else in this toolkit.

## `UploaderStorageOperation`

```kotlin
public enum class UploaderStorageOperation {
    INSERT_KEEP, INSERT_REPLACE, GET_ALL_ACTIVE, GET_BY_ID, RECORD_FAILURE,
    MARK_IN_FLIGHT, PARK, DELETE_BY_ID, DELETE_BY_TAG, OBSERVE_BY_TYPE, CLEAR_ALL,
    TRANSACTION, OPEN,
}
```

Which function an exception came out of. Mirrors `UploaderStore`, plus:

- `TRANSACTION` — the transaction machinery itself failed to begin, commit or roll back. An
  exception thrown by *your* block is never this: it propagates unchanged.
- `OPEN` — opening the database, creating its schema, or migrating it.

Coarse on purpose. You can branch on "a write failed" versus "the database would not open" without
this module promising a taxonomy of SQLite result codes it would then have to keep stable.

## Not public API

The generated SQLDelight types in `io.github.jamal_wia.kmptoolkit.uploader.sqldelight.db` —
`KmpToolkitUploaderDatabase`, `UploaderItemQueries`, `Kmptoolkit_uploader_item` — are visible because
SQLDelight has no way to generate them `internal`. They are not a supported surface: they describe
the current schema and will change with it. Use `uploaderDatabaseSchema` if you need the schema, and
`UploaderStorage.store` for everything else.
