# Implementing a custom store

`UploaderStore` is the one thing this module does not supply, and the reason it has no database
dependency. This page is everything you need to implement it correctly against any storage you
already have.

Read it if you are backing the queue with Room, Realm, a hand-rolled SQLite layer, a file, or
anything else. If you use SQLDelight, `kmptoolkit-uploader-sqldelight` already does this.

## The division of labour

The engine owns **all policy**: which item runs next, how backoff is computed, when to give up, when
a lease has expired, what ordering means. The store owns **none** of it.

A store is a set of mechanical primitives over a durable table. That is what lets one well-tested
engine sit on any storage — and it also means a store is small. The reference implementation,
`InMemoryUploaderStore`, is about 130 lines including comments.

## The table

One row per queued effect. A SQL schema that satisfies the contract:

```sql
CREATE TABLE uploader_item (
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
    lease_until     INTEGER NOT NULL DEFAULT 0,
    claim           INTEGER NOT NULL DEFAULT 0          -- the guards' version; see below
);

CREATE INDEX uploader_item_state ON uploader_item(state, sequence);
CREATE INDEX uploader_item_type  ON uploader_item(type, sequence);
CREATE UNIQUE INDEX uploader_item_identity ON uploader_item(type, unique_key)
    WHERE unique_key IS NOT NULL;
```

**The `sequence` column is not optional.** `getAllActive` must return insertion order, and
`created_at` is not insertion order — two items enqueued in the same millisecond have the same
timestamp, and the engine derives its FIFO channel heads from this list. Ordering by `created_at`
produces a store that passes casual testing and silently shuffles messages under a fast burst. Use
an autoincrementing sequence, and order by it everywhere.

Note that a replacement gets a **new** sequence: superseding is a delete plus an insert, never an
in-place edit, so a repeatedly replaced key cannot hold the head of its channel forever.

**The `claim` column is a counter, not a timestamp.** It is `UploaderItem.claim`: stored as given on
insert (the engine inserts `0`), and advanced by exactly one — to the caller's expected claim plus
one — by every guarded write that applies (`recordFailure`, `markInFlight`, `park`), and by nothing
else. The engine hands each executor the value its hand-off writes and checks every report against
the row's, so a store that skips an advance, or moves the counter any other way, lets a superseded
executor's report pass for the current one. Because every applied write moves it, a row never returns
to a `(state, claim)` pair it has left, which is what makes the pair safe to compare.

## The five invariants

### 1. Durable before returning

Every write must be committed before the call returns. `enqueue` promises the caller that the effect
survives process death from the moment it returns; a store that buffers and flushes later turns a
crash into silent data loss. This is the one invariant `UploaderStoreContract` cannot check for you.

### 2. Atomic per function

Each function is atomic on its own — a concurrent reader sees before or after, never halfway. Two
functions are **not** atomic together, and the engine never assumes they are.

The places the engine does depend on more are the three compare-and-sets — inside `recordFailure`,
`markInFlight` and `park` — and the two removals that hand back what they removed — `insertReplace`
and `deleteByTag`. Each replaces a decision the engine would otherwise make from an earlier read, and each
must be one atomic read-modify-write so a concurrent writer that changed the rows in between is
detected, or included, rather than missed. See below.

### 3. Insertion order from `getAllActive`

`PENDING` and `IN_FLIGHT` only. `PARKED` is excluded — a parked item is out of rotation, and
including it would let it block the head of its ordering channel forever. Parked items remain
visible through `observeByType`.

```sql
SELECT * FROM uploader_item WHERE state IN ('PENDING', 'IN_FLIGHT') ORDER BY sequence;
```

### 4. Absent rows are no-ops, everywhere

`recordFailure`, `markInFlight`, `park`, `deleteById` and `deleteByTag` are addressed by id or tag
and must treat "no such row" as success-with-nothing-done (`false` where the function returns one),
never as an error. Calling any of them
twice with the same arguments must be indistinguishable from calling it once.

This is not defensive politeness; the settle and claim races are *designed* around it. A late settle
from an executor whose item was superseded lands on nothing and degrades to a harmless no-op instead
of corrupting a fresh claim.

### 5. Safe under concurrency

At least three callers can overlap: the drain coroutine, a feature calling `enqueue`, and a platform
executor calling `settle`. Guard your state — a `Mutex`, a serialized database writer, a
transaction, whatever your engine offers.

## The four functions worth care

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

It returns the row it superseded, as it was just before the delete, read in the **same** atomic step
— the engine cancels that item's upload. A read made first and then a separate replace would miss an
item enqueued in between: its row would go and its upload run on.

### `recordFailure` — the optimistic guard

Five fields in one atomic write — `attempts`, `next_run_at`, `last_error`, `state`, and
`lease_until = 0` — plus the advanced `claim`. Clearing the lease is what returns a detached item to
rotation.

`state` comes from the `disposition` argument: `RETRY` writes `PENDING`, and a failure that spent the
retry budget arrives as `PARK` (write `PARKED`, keeping the final attempt count and error) or `DROP`
(delete the row instead, under the same guard). The give-up must be this same write: a row that
passed through `PENDING` on its way out could be handed off once more by a drain reading it in
between — an attempt past `maxAttempts`, on an item already given up.

The write must apply **only if** the row is still in `expectedState` under `expectedClaim` — the
state and claim the caller read it in:

```sql
UPDATE uploader_item
   SET attempts = ?, next_run_at = ?, last_error = ?, state = :state, lease_until = 0,
       claim = claim + 1
 WHERE id = ? AND state = :expected_state AND claim = :expected_claim;
-- DROP: DELETE FROM uploader_item WHERE id = ? AND (<the same guard>)
-- return whether a row was affected
```

Return whether a row was actually updated. The guard closes two windows:

- A detached executor reports a failure after the drain re-handed the item — while the report was in
  flight, or long after its lease expired. The engine rejects a report from a superseded claim before
  it gets here; the guard catches the re-hand that lands between the engine's read and this write.
  Without it, the report would clear a claim a second executor is working under, and you would have
  two live deliveries for one effect.
- The drain runs a handler — `execute`, or `prepareUpload` before a hand-off — and while it runs, a
  settle from a detached executor parks the item. Without the guard, the drain's failure would return
  a known-permanent failure to rotation.

Compare the claim, not the lease. Two claims can carry the same lease — written in the same
millisecond, or across a wall clock that stepped back — and a park or a failure clears the lease to
`0`, the very lease a pending row carries.

There is no unguarded form: the drain's single-flight ownership keeps other drain passes off the row,
but a settle arrives on an executor's own coroutine.

### `markInFlight` — the claim

The drain reads the queue, then claims an item for an executor. Between the two, a detached executor
of an earlier attempt may report its outcome: parking the item on a permanent failure, or returning it
to `PENDING` under a backoff gate. So the claim carries the state and claim the drain read, and must be
written **only if** the row still holds both:

```sql
UPDATE uploader_item
   SET state = 'IN_FLIGHT', lease_until = :lease_until, claim = claim + 1
 WHERE id = ? AND state = :expected_state AND claim = :expected_claim;
-- return whether a row was affected
```

A first hand-off expects `PENDING` under the claim it read; a re-hand after an expired lease expects
`IN_FLIGHT` under the claim that expired. The advanced claim is the new executor's: the engine handed
it out as `AttemptContext.claim` before writing it, and from this write on a report carrying any
earlier one is superseded. Without the guard, the claim would revive a parked item — the
transport uploads a known-permanent failure again — or retry one ahead of its gate. A claim that
misses returns `false`, and the engine launches nothing. It **must not touch `attempts`** — a hand-off
is not a failure.

### `park` — the verdict

Sets `state = 'PARKED'` and `last_error`, clears the lease, advances the claim, leaves `attempts`
alone, deletes nothing — under the same guard as the two above, returning whether it wrote:

```sql
UPDATE uploader_item
   SET state = 'PARKED', last_error = ?, lease_until = 0, claim = claim + 1
 WHERE id = ? AND state = :expected_state AND claim = :expected_claim;
```

A park is a verdict on the attempt the caller read. A re-hand that landed since has made it a verdict
on an attempt the engine no longer runs, and an unguarded park would take the item from the executor
now holding it.

## Everything else, briefly

| Function | Notes |
|---|---|
| `getById` | Tolerant lookup; `null` is a normal answer, not an error |
| `deleteById` | Removes one row |
| `deleteByTag` | Removes every row with that exact tag, in **every** state — this is a logout wipe. Returns the removed rows, in insertion order, read in the same atomic step as the delete: the engine cancels each one's upload, so an item enqueued while the wipe ran must be among them |
| `observeByType` | Live flow, **all** states, insertion order. Emits on collection and on every change; never completes |
| `clearAll` | Removes everything. A debug/test hatch |

The payload and the tag are **opaque**. A store stores and returns them unchanged and never parses,
validates, or acts on either.

## Prove it

```kotlin
class MyUploaderStoreTest {

    @Test
    fun `it satisfies the UploaderStore contract`() = runTest {
        UploaderStoreContract { MyUploaderStore(freshDatabase()) }.verifyAll()
    }
}
```

Forty-two checks covering insertion ordering under a same-millisecond burst, both conflict policies
against all three states, both compare-and-sets and the give-up dispositions, what the removals hand
back, no-op-on-absent-row, tag deletion across states, and the observation flow. The lambda must return a fresh, empty store each call.

Do not skip this on the grounds that the implementation is obviously correct. Every invariant on
this page exists because breaking it produces a bug that looks like something else: a message
delivered twice, a conversation silently reordered, a queue that stalls after a crash.

## A worked skeleton

```kotlin
class MyUploaderStore(private val db: MyDatabase) : UploaderStore {

    override suspend fun insertKeep(record: UploaderItem): Boolean = db.transaction {
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

    override suspend fun getAllActive(): List<UploaderItem> =
        db.selectActiveOrderedBySequence().map { it.toUploaderItem() }

    override suspend fun recordFailure(
        id: String,
        attempts: Int,
        nextRunAtEpochMillis: Long,
        lastError: String?,
        expectedState: UploaderItemState,
        expectedClaim: Long,
        disposition: FailureDisposition,
    ): Boolean = db.transaction {
        val current: Row = db.findById(id) ?: return@transaction false
        if (current.state != expectedState.name || current.claim != expectedClaim) {
            return@transaction false
        }
        // updateFailure also clears the lease and writes claim = expectedClaim + 1.
        when (disposition) {
            FailureDisposition.DROP -> db.deleteById(id)
            FailureDisposition.PARK -> db.updateFailure(id, "PARKED", attempts, nextRunAtEpochMillis, lastError, expectedClaim + 1)
            FailureDisposition.RETRY -> db.updateFailure(id, "PENDING", attempts, nextRunAtEpochMillis, lastError, expectedClaim + 1)
        }
        true
    }

    override suspend fun markInFlight(
        id: String,
        leaseUntilEpochMillis: Long,
        expectedState: UploaderItemState,
        expectedClaim: Long,
    ): Boolean = db.transaction {
        val current: Row = db.findById(id) ?: return@transaction false
        if (current.state != expectedState.name || current.claim != expectedClaim) {
            return@transaction false
        }
        db.updateClaim(id, leaseUntilEpochMillis, claim = expectedClaim + 1)
        true
    }

    // park: the same guard as markInFlight. The remaining six are each a single statement.
}
```

Read `InMemoryUploaderStore` in `kmptoolkit-uploader-testing` alongside this — it is the same shape,
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
