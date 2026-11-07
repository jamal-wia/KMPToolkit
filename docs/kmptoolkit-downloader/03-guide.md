# Guide

Scenarios in roughly increasing order of subtlety.

## The group lifecycle

```
ensureAvailable ─▶ [already available?] ─── yes ──▶ return at once
                          │ no
                          ▼
                   [temp file Complete?] ─ yes ──▶ commit          (transfer finished while
                          │ no (None or Partial)                    nobody was observing it)
                          ▼
                   [transfer running?] ── yes ──▶ attach to it
                          │ no
                          ▼
                   subscribe to the events, THEN enqueueDownload (resumes a Partial file)
                          │
                          ▼
                   Progress… ─┬─ FileReady ─▶ mark Complete ─▶ commit ─▶ verify ─▶ Completed
                              │                                  └─ check failed ─▶ download again
                              ├─ Error     ─▶ temp file Complete? ─ yes ─▶ commit
                              │                 └─ no ─▶ retry? ─▶ re-enqueue │ give up
                              ├─ Cancelled ─▶ Idle, temp deleted
                              └─ (silence)  ─▶ stall timeout (5 min) ─▶ treated as Error
```

The engine subscribes to a transfer's events **before** it enqueues the transfer, so an event your
downloader emits while enqueuing (a file that is already whole, a failure to begin) is not lost even
through a `MutableSharedFlow` without replay. And on an `Error` or a stall it first looks at the
temp file: a `Complete` one means the transfer finished and only its `FileReady` went missing, so the
engine commits it instead of re-downloading or deleting it.

Two more rules follow from that order. What a subscription delivers *before* the engine enqueues is
dropped — it can only be a replay of an earlier attempt — so a replaying downloader must not
replay a terminal event of a finished transfer once a new `enqueueDownload` has been called. And
when the engine *attaches* to a transfer that was running, it checks again after subscribing: a
transfer that ended in between (and whose event a downloader without replay dropped) is committed
if its temp file is complete and started again otherwise. An event flow that completes without a
terminal event fails the unit at once as `DownloadError.Unknown`; it is not waited out as a stall.

Two invariants make the rest of this document make sense:

- **Nothing about an in-flight download is persisted by the engine except the stall counter.** "Is
  a transfer running" is answered by your `BackgroundResourceDownloader`
  (`isDownloadInProgress`); "is it done" is answered by the **file on disk**
  (`isResourceAvailable`). A crash therefore needs no recovery pass — the next `ensureAvailable`
  re-derives everything from those two questions.
- **The temp file IS the resume state, where the platform allows it — and a partial one is never
  committed.** `DownloaderStorage.tempFileState` tells a `Partial` file from a `Complete` one; they
  are different files, and only `markTempFileComplete` turns one into the other. A partial file is
  resumed via a ranged request — or attached to, while its transfer is still running — however
  large it is: a transfer killed mid-way leaves a file that exists and is not empty, and before
  2.0.0 the engine committed exactly that as the finished resource. A failed transfer keeps its
  partial file for the next `ensureAvailable` to resume from; only a user cancel, an integrity
  failure and a `NotFound` failure delete it, so only those restart from zero.

## Group path vs. per-unit path

The two surfaces on `Downloader` are deliberately **not** one generalized over the other — the
group path's mutex, progress scaling and notification machinery are proven for a large bundle and
left alone; the per-unit path is a separate, simpler machine for a runtime catalogue.

| | Group path | Per-unit path |
|---|---|---|
| Concurrency | one download per group, groups in parallel | several units in parallel |
| State | `downloadState(group)` — one per declared group | `unitDownloadStateFlow(unit)` — one per unit, created lazily |
| Retry | loop with capped backoff + persisted failure counter, up to 3 failed attempts in a row (progress past the earlier high-water mark restarts the run); `NotFound` / `Unauthorized` never retried | **none** — one stall surfaces as `Error`; these are small files a user can just re-tap |
| Failure | throws + publishes | throws + publishes (identical contract) |
| Bundled bypass | honoured | **ignored** — a runtime unit is never bundled |
| Notifier | called throughout | **never called** — the per-unit path talks to nobody but its own state flow |

`UnitDownloadState` has one state the group model does not: **`Available`**. The flow's first
emission is seeded from disk, so a collector can tell "already downloaded earlier" (`Available`)
from "nobody has asked" (`Idle`) from "finished downloading this process" (`Completed`) without a
separate storage read. `Completed` is sticky and never demoted back to `Available` — that
distinction is what a UI row keys off to celebrate a fresh download exactly once.

## Unit ids and relative paths

Give units ids made of `[A-Za-z0-9._-]` and keep them unique ignoring case (on iOS, ignoring Unicode
normalization too). Other ids work, but they are stored under a hashed name (see
[`05-platform-notes.md`](05-platform-notes.md)) rather than at the readable path.

A `relativePath` is yours: it is used exactly as written. If it cannot be used safely (empty, NUL,
climbing out of the base directory, or naming a directory the storage manages such as `tmp` or
`tmp/expect`) the unit fails before any transfer: `ensureAvailable` throws `DownloadFailedException`
with `DownloadError.Unknown`, whose `cause` is `InvalidDownloadUnitException` carrying the
`Reason`. `isAvailable` is `false` and `pathOf` throws the same exception. It is a catalogue error:
fix the catalogue, the temp files are kept.

## Prove the bytes are usable — `ResourceFormat`

"Is this download usable?" is not "does the file exist?": a truncated database and a
half-extracted archive both exist and are worthless, and discovering that at read time — a crash
deep inside a reader — is what this exists to prevent.

| Format | What commit does |
|---|---|
| `Opaque` | Move into place; existence is the only possible check |
| `ZipArchive(availabilityMarker)` | Extract into a staging directory, swap into place, delete the archive; the marker file is what proves completeness, since an interrupted extraction also leaves a directory behind |
| `SqliteDatabase(rowCountTable?, declaredRowCountMetaKey?)` | Open it and read its schema — which alone rejects non-databases — and, when a table and a `meta` key are given, compare the real row count against the count the file declares about itself |

The point of the self-check: nobody hardcodes an expected row count anywhere in this library — the
file states its own expected size. Which table and which key are the host's domain knowledge —
values on the format, not anything the library assumes. Both shipped storage implementations carry
zip-slip protection during extraction.

### A stated hash — `DownloadUnit.sha256`

Any unit, whatever its format, can state the SHA-256 of its bytes. A value from a manifest the
backend served is parsed with `parseOrNull`, so a missing or garbled entry means "no check" rather
than a crash:

```kotlin
data class ModelUnit(val manifestEntry: ModelManifestEntry) : DownloadUnit {
    override val id: String = "model-${manifestEntry.version}"
    override val apiPath: String = manifestEntry.path
    override val relativePath: String = "models/${manifestEntry.version}.bin"
    override val sha256: Sha256? = Sha256.parseOrNull(manifestEntry.sha256)   // null = no check
    override val group: ResourceGroup = Models
}
```

A constant the host wrote itself is parsed with `parse`, which throws for anything that is not 64
hex digits (either case). Written as `get() = Sha256.parse("...")`, a malformed constant fails the
unit before the transfer; written as a stored `val sha256 = Sha256.parse("...")`, it throws when the
unit is constructed.

Commit then hashes the complete temp file before anything else — before the move, before an
archive is extracted, before a database is opened — and a mismatch deletes it. Completeness
already rules out a truncated transfer; the hash is what catches bytes damaged in transit or on a
CDN, and a file stitched from two versions when the remote resource changed between an interrupted
transfer and its ranged resume. It costs one read of the file at commit, about a second for a few
hundred megabytes on a current phone. `Sha256` cannot hold a malformed value. The early engine check
covers only a getter that throws: the engine reads `sha256` before it starts a transfer, so such a
unit fails as `DownloadError.Unknown` straight away — before any byte is fetched, instead of after
the whole download on every tap.

A constant `sha256` is right for an object that never changes under its name. For one the backend
replaces in place, leave it `null` and use the hash the backend returns, as described next.

### Verifying a hash the backend returns

A backend that answers each resolve with `{url, sha256?, size_bytes}` and replaces objects in place
cannot be described by a constant: the hash belongs to the response that began the transfer. Return
it from your resolver:

```kotlin
override suspend fun resolve(unit: DownloadUnit): ResolvedDownload {
    val response: ResolveResponse = api.resolve(unit.apiPath)
    return ResolvedDownload(
        url = response.url,
        // parseOrNull, not parse: a hash the backend omitted or garbled means "no check",
        // and must never fail the download.
        expectedSha256 = Sha256.parseOrNull(response.sha256),
    )
}
```

and hand it to storage when — and only when — the transfer starts writing from byte zero:
`storage.beginTempFile(unit, resolved.expectedSha256)`. Storage persists it next to the temp file,
so a commit in a later process (a recovered `Complete` file, an iOS session that finished while the
app was dead) still enforces it. Commit hashes the file once and compares against every stated
expectation, `unit.sha256` and the recorded one, before extraction, a database check or the move.

- A mismatch is a `ResourceIntegrityException`: the file is deleted, one fresh download follows
  (with a fresh resolve), and a second mismatch is `DownloadError.Corrupted`.
- A record that exists but cannot be read is also an integrity failure, never "no check".
- No record at all (a partial from before 2.1.0, or a downloader that never called
  `beginTempFile`) means only `unit.sha256` applies; a warning is logged.
- The record is dropped with the temp file, on success and on every failure that deletes it.
- If `unit.sha256` and the expected hash are both set and differ, `beginTempFile` throws
  `IllegalArgumentException` before touching anything.

When exactly to call `beginTempFile` is a decision table in
[`07-background-downloader.md`](07-background-downloader.md).

## Failure, retry, and giving up

The group path's policy, as a table because it is not obvious:

| Terminal event | Advanced past the high-water mark this attempt? | Engine does |
|---|---|---|
| `FileReady` | — | mark the temp file complete (if the downloader did not), commit, verify, clear stall count, done |
| `Cancelled` | — | clear stall count, delete temp file, `Idle`, throw `DownloadCancelledException` |
| `Error` classified `NotFound` or `Unauthorized` | either | never retried: clear the failure count, `Error` state + notification, throw `DownloadFailedException` |
| `Error`, any other | yes | restart the run: failure count = 1, wait, re-enqueue |
| `Error`, any other | no, count ≤ 2 | increment, wait, re-enqueue |
| `Error`, any other | no, count > 2 | clear the failure count (the next `ensureAvailable` gets a fresh budget), `Error` state + notification, throw `DownloadFailedException` |

**What happens to the partial file.** A failed transfer keeps it, so the user's retry resumes
instead of starting over — a drive through a tunnel must not cost a 1.8 GB download. It is deleted
in exactly four cases: the user cancels (`cancelDownload`, either overload, whatever state the file
is in), the bytes fail their integrity check at commit, the failure is `NotFound` (404/410 — the
object is gone, so the bytes can never be completed), and the downloader reports `Cancelled` (the
engine takes that for a user cancel; a port reports it only as the answer to `cancelDownload`). `Unauthorized` keeps it: a refreshed session
fixes that. The engine has no rule for a 416; see
[`07-background-downloader.md`](07-background-downloader.md).

**Progress means moving forward.** The engine remembers the highest fraction any attempt of one
`ensureAvailable` has reported (the high-water mark). Only a `Progress` beyond it counts: a transfer
that restarts and re-reports where it already was, or reports `0`, has not moved. A repeated
`Progress` still proves the transfer is alive — it restarts the stall timer — but it is not
progress, so a downloader that repeats one value and then fails is stopped after three attempts.
Before every re-enqueue the engine waits 2 s, doubling per failure of the call up to 30 s.

A commit whose bytes fail a check — a `sha256` or recorded-hash mismatch, a database that does not
open or does not hold the rows it declares — deletes the temp file and downloads **once more**. A second failure is
`DownloadError.Corrupted`: persisting across two fresh downloads, it is the server's bytes or the
host's stated hash that is wrong, and no number of retries fixes either. Any other commit failure
(a full disk, an unwritable path, an archive that will not extract) is `DownloadError.Storage` at
once — downloading again is not the fix, so the complete temp file is **kept**: once the user has
freed space, the next `ensureAvailable` commits it without a transfer. Both surfaces share this
policy.

Three things make it work:

- **Stall detection is an inactivity timeout, not a wall clock.** It is armed when the engine
  subscribes to the attempt, so a transfer that never emits anything stalls too. A slow-but-progressing
  large transfer never trips it; a dead connection does. A transfer the OS legitimately holds
  silent (iOS waiting for connectivity) must re-emit its current `Progress`, `0` included, now and
  then, or the host sets `stallTimeout = Duration.INFINITE`. The timeout is 5 minutes unless you pass a
  `DownloaderConfig(stallTimeout = …)` to `createDownloader` (`Duration.INFINITE` turns it off). A stall re-enters the table above as an `Error`
  with no progress.
- **The failure counter is persisted** through `DownloadStateStore` — worthless if it reset with the
  process, since a crash loop would otherwise retry forever.
- **Error classification is one text-matching function** turning your
  `BackgroundResourceDownloader`'s raw platform strings into a `DownloadError`. It reads a stated
  HTTP status (`HTTP 503`, `status 404`) first — a status beats words such as "unauthorized" or
  "forbidden" in the same message — and falls back to wording only when none is stated. It has to
  be string-based — what a failing transfer reports differs per platform and per layer — so it is
  confined to one place and everything downstream is typed. The phrasing a downloader should use
  is in [`07-background-downloader.md`](07-background-downloader.md).

**When your own code throws.** The ports are host code. A `DownloadNotifier` that throws is logged
and ignored — a failed notification never fails the download it describes. Anything else that
throws inside an attempt (`enqueueDownload` — on Android 12+ a foreground service that may not
start from the background — `isDownloadInProgress`, the event flow, the `DownloadStateStore`) ends
the unit as a `DownloadFailedException` carrying that exception as its `cause`, with
`DownloadError.Unknown` (the host's own message) as the error, the state flow at `Error` and the
notifier told, and the group is not left at `Downloading`. The temp file stays. This covers the
transfer itself (the port calls above, the commit, the unit's hash). A `DownloaderStorage` that
you implement and that throws while the engine only *plans* a call (`isResourceAvailable`,
`tempFileState`) is not caught: that exception reaches the caller as it is.

The per-unit path has **no retry loop** — one stall surfaces directly as `Error`.

## Tell the user — the `DownloadNotifier` port

The engine decides **when** something is worth showing; the host decides **what it looks like**.
Consequences:

- The library carries no strings and no localization.
- `DownloadError` is the library's own small taxonomy precisely so the host can map it to its own
  copy, and at the UI edge to its own application-wide error type.
- A notifier that cannot post must **do nothing rather than throw** — a failed notification must
  never fail the download.

On completion the engine calls `remove()` **then** `showCompleted()`: some notification
implementations do not reliably honour a single update that flips a rapidly-updated ongoing
notification to non-ongoing, so the terminal post is a forced cancel plus a fresh notification
rather than an update layered on a stuck bar.

## Resolve the URL — and why there is no HTTP client in here

`DownloadUrlResolver` is the one step of a download that needs your base URL, your HTTP client and
your authentication — which is exactly why it is a port. The URL it returns should be treated as
short-lived: **re-resolve on every attempt, never cache it** in your
`BackgroundResourceDownloader` — a signed URL expires, and a retry hours later needs a fresh one.
It returns a `ResolvedDownload(url, expectedSha256 = null)`; the hash, when the backend states one,
describes exactly the object behind that URL (see "Verifying a hash the backend returns" above).
`ResolvedDownload.toString` leaves out the URL's query and fragment, since those usually carry a
signature.

The engine itself never calls this port; it exists so a `BackgroundResourceDownloader`
implementation has somewhere standard to turn a unit's `apiPath` into something fetchable. See
[`07-background-downloader.md`](07-background-downloader.md).

## Cancelling — user, and logout

**What a cancel releases, and what it leaves.** `cancelDownload(unit)` is also how a host releases a
unit it no longer wants: it deletes the partial temp file, the expected-hash record and, for an
archive unit, an extraction tree (`tmp/staging-<unit.id>/`) that a process kill left half-done (not
touched while a commit of that unit is running, and, very rarely, while another unit's commit is
registering; the next cancel or commit of the unit removes it). The same call also sweeps
`tmp/trash/`, where a detached tree waits for its delete, so a delete that a kill cut short is
finished by the next cancel of any unit.
Nothing else enumerates the temp area, and keeping a partial after a transient failure is the resume
contract, so a unit that is dropped from your catalogue keeps its partial until you call
`cancelDownload(unit)` for it. Do that when a catalogue revision removes ids.

**The engine keeps light state per id.** For every unit id it has seen during its lifetime (a
request, a state subscription, a cancel of a tracked unit) the engine keeps a state flow and a lock
until it is garbage collected. Ids must therefore come from a stable catalogue (they also key the
temp file, the background session and the stall count, so a fresh id per session would defeat
resume); minting a new id on every launch is not supported.

Three distinct operations:

- **`cancelDownload(group)`** — cancels each unit, clears stall counts, deletes temp files, drops
  the notification, resets the group to `Idle`. Safe from any thread.
- **`cancelDownload(unit)`** — the same for one unit. Synchronous like the group overload: the
  transfer is cancelled and the state is `Idle` when it returns, so a request made on the next
  line starts a fresh transfer instead of racing a cancel that is still on its way. (That relies
  on the port: after its `cancelDownload`, `isDownloadInProgress` must be `false` and the cancelled
  transfer's `Cancelled` must not be replayed to the next subscriber.)
  A cancel of a unit this engine has never tracked (never requested, never observed) still cancels
  the platform transfer, clears the stall count and deletes the unit's temp data, but starts no
  tracking for it.
- **`cancelAllDownloads()`** — the **logout** entry point: cancels every group-level and per-unit
  download the engine tracks (each as above, so every running `ensureAvailable` ends with
  `DownloadCancelledException`), then the engine's own coroutines (a notification-shade Retry).

**A cancel reaches a call that is already running.** Each `cancelDownload` marks the group (or the
unit) as cancelled, and every `ensureAvailable` that was started before it ends with
`DownloadCancelledException`: at once if it is waiting for a transfer or sitting out a retry
backoff, otherwise at its next step — before the next unit, before a transfer is started, after a
commit in flight. The running call removes the temp files itself (the commit reads them, so the
cancel never deletes them behind a running call), publishes `Idle` and removes the notification.
A commit that had already succeeded leaves its resource in place: while units are still missing
the call reports the cancel, and once the last commit has succeeded the cancel has no effect and
`Completed` is published (the resource is there, so the state says so). A group cancel also
cancels per-unit calls on that group's units, and never deletes a temp file a per-unit commit is
reading. A request made after the cancel is not affected. This is how a cancel or a logout stops a
call that is not a child of the engine's own scope (one a screen launched), and what keeps a retry
backoff from re-enqueueing a download the user cancelled.

What logout does **not** do: it cancels transfers, it never deletes committed resources. Deleting
is a cache-management concern via `DownloaderStorage.deleteResource`.

## The bundled-assets bypass

One constructor flag, `bundledResourcesPresent`. When `true`, `isAvailable(group)` returns `true`
for **every** declared group, so `ensureAvailable` returns instantly and nothing downloads —
useful for a debug or demo build that ships assets inside the package instead of fetching them.
Consumers then read from bundled resources on their own; the downloader is not consulted for the
path.

Two things to keep in mind: the bypass is **group-level only** — the per-unit path ignores it
entirely; and it reports "available" unconditionally, so a group whose bundled asset was never
actually shipped fails wherever the missing file is opened next, not here.

## Reaching the downloader from an OS-constructed entry point

`DownloaderRegistry` is how a foreground service the system restarted, or a notification-action
receiver, finds the running `Downloader` without a DI framework:

```kotlin
val downloader: Downloader = createDownloader(...)
DownloaderRegistry.register(downloader)
```

```kotlin
val downloader: Downloader = DownloaderRegistry.await(10.seconds)
    ?: return // startup has not produced one yet
```

See its own KDoc for the full contract — one slot, explicit registration, safe from any thread.

## Semantics cheat-sheet

- **Idempotent, not at-least-once.** A repeated download is harmless — the unit of work is "make
  this file exist", so a duplicate attempt costs bandwidth, never correctness. What *is*
  at-least-once is the **commit**: your transfer implementation may self-commit and the engine
  commits again on the next `ensureAvailable`, so commit checks `isResourceAvailable` first and
  skips. Two commits of one unit may also overlap — the group and the per-unit surface, two
  engines over one directory, or your self-commit racing the engine's — so the shipped storages
  serialize commits per unit across the whole process and re-check under that lock: the later one
  finds the unit committed and returns normally, never deleting what the first one put in place.
- **Only a `Complete` temp file is committed without a transfer.** "Completed while nobody
  observed it" is a fact the downloader records with `markTempFileComplete`, never something the
  engine infers from a file's existence or size.
- **Identity is `unit.id` / `group.key`**, never the object identity — a host may construct a fresh
  `DownloadUnit` per call and still hit the same mutex and the same state.
- **Cancel deletes the temp file; a coroutine cancellation does not.** Navigating away from a
  screen detaches the observer and leaves the transfer running under your
  `BackgroundResourceDownloader` — which is why the notification is deliberately not dismissed
  there.
- **Failure is thrown AND published, identically on both surfaces.**
- **The post-commit publish is non-cancellable on both paths** — once the file is on disk, the
  hand-off to `Completed` always finishes.
- **Verification is at commit, and commit is not trusted blindly** — the engine re-asserts
  `isResourceAvailable` after commit and fails loudly rather than allowing a silent
  re-download loop.
- **No per-account partitioning.** Resources are device-global; see
  [`05-platform-notes.md`](05-platform-notes.md).
