# kmptoolkit-outbox-sqldelight — Platform notes

Where the file actually is, how the two drivers differ, and the threading model — which is the one
part of this module you may have to reason about.

## Permissions and app configuration

**This module declares nothing.** No permission in its `AndroidManifest.xml` — there is no manifest
to declare one in — no `Info.plist` key, no entitlement, no background mode. The queue lives in your
app's own private data directory, which needs no permission on either platform.

Four permissions do reach your merged manifest, and they are not this module's:

| Permission | Comes from | Why it cannot be removed |
|---|---|---|
| `WAKE_LOCK` | `androidx.work`, via `kmptoolkit-outbox` | Holds the CPU while a queued job runs |
| `RECEIVE_BOOT_COMPLETED` | same | Restores scheduled work after a reboot |
| `FOREGROUND_SERVICE` | same | For work you choose to run in the foreground |
| `ACCESS_NETWORK_STATE` | same | Reads connectivity for a `NetworkType` constraint |

They arrive because this module depends on `kmptoolkit-outbox`, whose Android wake layer is
WorkManager. `kmptoolkit-outbox`'s own
[platform notes](../kmptoolkit-outbox/05-platform-notes.md) explain them in full. Both modules'
`LibraryManifestTest` pins the exact set, so a dependency upgrade that adds a fifth fails the build
rather than turning up in your Play Store listing.

SQLDelight's `android-driver` contributes none.

## Where the file lives

The standalone factories derive the file name from `OutboxDatabaseConfig.name`, which defaults to
your application id. Given a name `N`, the file is `N.kmptoolkit.outbox.db`:

| | Location |
|---|---|
| Android | `/data/data/<your.package>/databases/N.kmptoolkit.outbox.db` — pull it with `adb shell run-as <your.package> cat databases/...` on a debuggable build |
| iOS | The simulator's or device's app container, under the directory SQLiter's `DatabaseFileContext` resolves — `Library/databases/` relative to the app's home |

These paths are an implementation detail, documented so you can find your own data, not so you can
depend on them. The suffix is what keeps a queue distinct from a database of yours that happens to
share a name.

Android's `databases/` directory is inside the app sandbox and is **included in auto-backup** by
default. A restored backup therefore arrives with somebody's — usually the same person's — queued
effects in it, on a device that may have different credentials. If that matters for your app, add a
`data_extraction_rules.xml` exclusion for the file, or wipe the queue on first launch after a
restore. This module cannot decide that for you.

## Driver differences

| | Android (`AndroidSqliteDriver`) | iOS (`NativeSqliteDriver`) |
|---|---|---|
| Underlying SQLite | The platform's, so the version follows the OS — 3.9 at `minSdk` 24, much newer on a current device | Bundled by SQLiter with the app, so it is the same everywhere |
| Connections | Framework connection pool | SQLiter pool: one writer, several readers |
| In-memory mode | `name = null` | `inMemoryDriver(schema)` |
| Journal mode | WAL, the platform default | WAL, SQLiter's default |

The version spread on Android is why this module's SQL stays conservative. `recordFailure`'s
compare-and-set, for instance, does not use an `UPDATE … RETURNING` clause to learn whether it wrote
a row: `RETURNING` needs SQLite 3.35 and `minSdk` 24 ships 3.9. It uses the statement's own
affected-row count instead — which also avoids `SELECT changes()`, a second statement that both
pools can serve from a *reader* connection, where it is always 0.

## Threading

**Every statement of one `OutboxStorage` runs on one dedicated thread**, created with the storage
and released by `close()`.

This is not caution, it is a requirement. SQLDelight's `Transacter.Transaction` records the thread
that created it and fails if it is touched from another; both drivers track the current transaction
per thread. A suspending call that resumed on a different thread of a pool would therefore either
fail outright or — worse — see no enclosing transaction and quietly open a second one, which is
precisely the failure `TransactionRunner` exists to prevent.

`Dispatchers.IO.limitedParallelism(1)` would not do: it serializes tasks but does not pin a thread,
so a coroutine that suspends mid-transaction can resume on a different one.

What this means for you:

- **You never dispatch.** Every store call switches to the database thread itself and switches back.
  Call them from anywhere.
- **Do not switch dispatchers inside `inTransaction`** — see [`03-guide.md`](03-guide.md). A store
  call that has left the database thread is detected and fails with an `IllegalStateException`
  rather than writing outside the transaction.
- **Do not await anything slow inside `inTransaction`.** It holds the database thread and SQLite's
  write lock for the duration.
- **One thread per storage.** Two queues means two threads, which is the cost of them being
  genuinely independent. Two storages over one file is not supported for a different reason — see
  [`03-guide.md`](03-guide.md).
- **`close()` blocks until the thread is quiet**, then releases it. It has to: releasing a thread
  does not join it, and closing the connection while a statement is still running would close it
  under a live cursor. On Android the thread is a daemon, so forgetting `close()` leaks a thread but
  cannot hang a JVM; on iOS it is a worker with its own run loop and must be closed.

`observeByType`'s flow reads on that same thread, so an emission can never observe a half-applied
transaction. Collecting it from inside `inTransaction` is safe — it recognizes it is already on the
database thread instead of dispatching onto it — and shows the uncommitted state so far.

## Targets

`androidTarget`, `iosArm64`, `iosSimulatorArm64` — the standard set for this toolkit. No
JVM or desktop target, and no JS: the module exists to back the two synchronous drivers, and the
asynchronous web-worker driver would change how transactions are expressed throughout.
