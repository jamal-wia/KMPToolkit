# Implementing a custom store

`OutboxStore` is the one thing this module does not supply, and the reason it has no database
dependency. This page is everything you need to implement it correctly against any storage you
already have.

Read it if you are backing the queue with Room, Realm, a hand-rolled SQLite layer, a file, or
anything else. If you use SQLDelight, `kmptoolkit-outbox-sqldelight` already does this.

## The division of labour

The engine owns **all policy**: which item runs next, how backoff is computed, when to give up, when
a lease has expired, what ordering means. The store owns **none** of it.

A store is a set of mechanical primitives over a durable table. That is what lets one well-tested
engine sit on any storage — and it also means a store is small. The reference implementation,
`InMemoryOutboxStore`, is about 130 lines including comments.

## The table

One row per queued effect. A SQL schema that satisfies the contract:

```sql
CREATE TABLE outbox_item (
    sequence        INTEGER PRIMARY KEY AUTOINCREMENT,  -- insertion order; see below
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

CREATE INDEX outbox_item_state ON outbox_item(state, sequence);
CREATE INDEX outbox_item_type  ON outbox_item(type, sequence);
CREATE UNIQUE INDEX outbox_item_identity ON outbox_item(type, unique_key)
    WHERE unique_key IS NOT NULL;
```

**The `sequence` column is not optional.** `getAllActive` must return insertion order, and
`created_at` is not insertion order — two items enqueued in the same millisecond have the same
timestamp, and the engine derives its FIFO channel heads from this list. Ordering by `created_at`
produces a store that passes casual testing and silently shuffles messages under a fast burst. Use
an autoincrementing sequence, and order by it everywhere.

Note that a replacement gets a **new** sequence: superseding is a delete plus an insert, never an
in-place edit, so a repeatedly replaced key cannot hold the head of its channel forever.

## The five invariants

### 1. Durable before returning

Every write must be committed before the call returns. `enqueue` promises the caller that the effect
survives process death from the moment it returns; a store that buffers and flushes later turns a
crash into silent data loss. This is the one invariant `OutboxStoreContract` cannot check for you.

### 2. Atomic per function

Each function is atomic on its own — a concurrent reader sees before or after, never halfway. Two
functions are **not** atomic together, and the engine never assumes they are.

The one place the engine does depend on more is the compare-and-set inside `recordFailure`. See
below.

### 3. Insertion order from `getAllActive`

`PENDING` and `IN_FLIGHT` only. `PARKED` is excluded — a parked item is out of rotation, and
including it would let it block the head of its ordering channel forever. Parked items remain
visible through `observeByType`.

```sql
SELECT * FROM outbox_item WHERE state IN ('PENDING', 'IN_FLIGHT') ORDER BY sequence;
```

### 4. Absent rows are no-ops, everywhere

`recordFailure`, `markInFlight`, `park`, `deleteById` and `deleteByTag` are addressed by id or tag
and must treat "no such row" as success-with-nothing-done, never as an error. Calling any of them
twice with the same arguments must be indistinguishable from calling it once.

This is not defensive politeness; the settle and lease races are *designed* around it. A late settle
from an executor whose item was superseded lands on nothing and degrades to a harmless no-op instead
of corrupting a fresh claim.

### 5. Safe under concurrency

At least three callers can overlap: the drain coroutine, a feature calling `enqueue`, and a platform
executor calling `settle`. Guard your state — a `Mutex`, a serialized database writer, a
transaction, whatever your engine offers.

## The two functions worth care

### `insertKeep` — the conflict rules

Dedup identity is `(type, unique_key)`. A `null` unique key **never** conflicts: always insert,
always return `true`.

With a matching row present:

| Existing state | Action | Return |
|---|---|---|
| `PENDING` | keep it, insert nothing | `false` |
| `IN_FLIGHT` | keep it, insert nothing | `false` |
| `PARKED` | **delete it, insert the new row at the tail** | `true` |

The `PARKED` case is the one people get wrong. A parked item has no other route back into rotation,
so if it kept its key, that key would be permanently dead and every future `KEEP` enqueue for it
would be silently swallowed. A fresh enqueue is new intent.

`insertReplace` is simpler: delete any row with the same identity, in any state, then insert the new
one at the tail. Never an in-place update — the replacement must carry a clean id, attempt count,
gate and lease.

### `recordFailure` — the optimistic guard

Five fields in one atomic write: `attempts`, `next_run_at`, `last_error`, `state = PENDING`, and
`lease_until = 0`. Clearing the lease is what returns a detached item to rotation.

When `expectedLeaseUntilEpochMillis` is non-null, the write must apply **only if** the row's current
`lease_until` still equals it:

```sql
UPDATE outbox_item
   SET attempts = ?, next_run_at = ?, last_error = ?, state = 'PENDING', lease_until = 0
 WHERE id = ?
   AND (:expected_lease IS NULL OR lease_until = :expected_lease);
-- return whether a row was affected
```

Return whether a row was actually updated. This closes the window where a detached executor reports
a failure while the drain, concurrently, notices the lease expired and re-hands the item: without
the guard, the stale report would clear a claim a second executor is currently working under, and
you would have two live deliveries for one effect.

A `null` expected lease skips the guard entirely — that is the drain's own path, where it already
holds single-flight ownership.

## Everything else, briefly

| Function | Notes |
|---|---|
| `getById` | Tolerant lookup; `null` is a normal answer, not an error |
| `markInFlight` | Sets state and lease. **Must not touch `attempts`** — a hand-off is not a failure |
| `park` | Sets state and `last_error`, clears the lease, leaves `attempts` alone. Deletes nothing |
| `deleteById` | Removes one row |
| `deleteByTag` | Removes every row with that exact tag, in **every** state — this is a logout wipe |
| `observeByType` | Live flow, **all** states, insertion order. Emits on collection and on every change; never completes |
| `clearAll` | Removes everything. A debug/test hatch |

The payload and the tag are **opaque**. A store stores and returns them unchanged and never parses,
validates, or acts on either.

## Prove it

```kotlin
class MyOutboxStoreTest {

    @Test
    fun `it satisfies the OutboxStore contract`() = runTest {
        OutboxStoreContract { MyOutboxStore(freshDatabase()) }.verifyAll()
    }
}
```

Thirty checks covering insertion ordering under a same-millisecond burst, both conflict policies
against all three states, the compare-and-set, no-op-on-absent-row, tag deletion across states, and
the observation flow. The lambda must return a fresh, empty store each call.

Do not skip this on the grounds that the implementation is obviously correct. Every invariant on
this page exists because breaking it produces a bug that looks like something else: a message
delivered twice, a conversation silently reordered, a queue that stalls after a crash.

## A worked skeleton

```kotlin
class MyOutboxStore(private val db: MyDatabase) : OutboxStore {

    override suspend fun insertKeep(record: OutboxItem): Boolean = db.transaction {
        val existing: Row? = record.uniqueKey?.let { db.findByIdentity(record.type, it) }
        when (existing?.state) {
            "PENDING", "IN_FLIGHT" -> false
            "PARKED" -> {
                db.deleteById(existing.id)
                db.insert(record)
                true
            }
            else -> {
                db.insert(record)
                true
            }
        }
    }

    override suspend fun getAllActive(): List<OutboxItem> =
        db.selectActiveOrderedBySequence().map { it.toOutboxItem() }

    override suspend fun recordFailure(
        id: String,
        attempts: Int,
        nextRunAtEpochMillis: Long,
        lastError: String?,
        expectedLeaseUntilEpochMillis: Long?,
    ): Boolean = db.transaction {
        val current: Row = db.findById(id) ?: return@transaction false
        if (expectedLeaseUntilEpochMillis != null &&
            current.leaseUntil != expectedLeaseUntilEpochMillis
        ) {
            return@transaction false
        }
        db.updateFailure(id, attempts, nextRunAtEpochMillis, lastError)
        true
    }

    // ... the remaining seven, each a single statement
}
```

Read `InMemoryOutboxStore` in `kmptoolkit-outbox-testing` alongside this — it is the same shape,
complete, and passes the contract unmodified.

## While you are here: the other three SPIs

They are much smaller, and their full contracts are in their KDoc.

- **`TransactionRunner`** — atomic and **reentrant**. A nested `inTransaction` must join the outer
  transaction. `enqueue` opens one of its own, so if nesting starts a second transaction, a domain
  write and its owed effect can commit independently and the transactional guarantee is gone.
- **`ConstraintProvider`** — a `key` and a `StateFlow<Boolean>` that is cheap to read, emits on every
  change, and is safe to read from any thread. Seed it optimistically: a wrong `true` costs one
  failed attempt, a wrong `false` stalls every gated effect.
- **`WakeScheduler`** — `scheduleWake` / `cancelWake`, both idempotent, cheap, non-suspending, and
  **must not throw**. The engine calls them from inside a caller's `enqueue`, once per item during a
  burst; deduplicate on your side and swallow a platform refusal.
