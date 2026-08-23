# Guide

Scenarios in roughly increasing order of subtlety.

## The group lifecycle

```
ensureAvailable ─▶ [already available?] ─── yes ──▶ return at once
                          │ no
                          ▼
                   [temp file on disk?] ── yes ──▶ commit          (transfer finished while
                          │ no                                      the process was dead)
                          ▼
                   enqueueDownload ─▶ Progress… ─┬─ FileReady ─▶ commit ─▶ verify ─▶ Completed
                                                 ├─ Error     ─▶ retry? ─▶ re-enqueue │ give up
                                                 ├─ Cancelled ─▶ Idle, temp deleted
                                                 └─ (silence)  ─▶ 5-minute stall ─▶ treated as Error
```

Two invariants make the rest of this document make sense:

- **Nothing about an in-flight download is persisted by the engine except the stall counter.** "Is
  a transfer running" is answered by your `BackgroundResourceDownloader`
  (`isDownloadInProgress`); "is it done" is answered by the **file on disk**
  (`isResourceAvailable`). A crash therefore needs no recovery pass — the next `ensureAvailable`
  re-derives everything from those two questions.
- **The temp file IS the resume state, where the platform allows it.** A downloader that resumes
  via a ranged request does so only when a partial temp file survived; a user cancel and a final
  failure both delete it, so those restart from zero.

## Group path vs. per-unit path

The two surfaces on `Downloader` are deliberately **not** one generalized over the other — the
group path's mutex, progress scaling and notification machinery are proven for a large bundle and
left alone; the per-unit path is a separate, simpler machine for a runtime catalogue.

| | Group path | Per-unit path |
|---|---|---|
| Concurrency | one download per group, groups in parallel | several units in parallel |
| State | `downloadState(group)` — one per declared group | `unitDownloadStateFlow(unit)` — one per unit, created lazily |
| Retry | progress-based loop + persisted stall counter, up to 2 stalls without progress | **none** — one stall surfaces as `Error`; these are small files a user can just re-tap |
| Failure | throws + publishes | throws + publishes (identical contract) |
| Bundled bypass | honoured | **ignored** — a runtime unit is never bundled |
| Notifier | called throughout | **never called** — the per-unit path talks to nobody but its own state flow |

`UnitDownloadState` has one state the group model does not: **`Available`**. The flow's first
emission is seeded from disk, so a collector can tell "already downloaded earlier" (`Available`)
from "nobody has asked" (`Idle`) from "finished downloading this process" (`Completed`) without a
separate storage read. `Completed` is sticky and never demoted back to `Available` — that
distinction is what a UI row keys off to celebrate a fresh download exactly once.

## Prove the bytes are usable — `ResourceFormat`

"Is this download usable?" is not "does the file exist?": a truncated database and a
half-extracted archive both exist and are worthless, and discovering that at read time — a crash
deep inside a reader — is what this exists to prevent.

| Format | What commit does |
|---|---|
| `Opaque` | Move into place; existence is the only possible check |
| `ZipArchive(availabilityMarker)` | Extract into a staging directory, swap into place, delete the archive; the marker file is what proves completeness, since an interrupted extraction also leaves a directory behind |
| `SqliteDatabase(rowCountTable?, declaredRowCountMetaKey?)` | Open it — which alone rejects non-databases — and, when a table and a `meta` key are given, compare the real row count against the count the file declares about itself |

The point of the self-check: nobody hardcodes an expected row count anywhere in this library — the
file states its own expected size. Which table and which key are the host's domain knowledge —
values on the format, not anything the library assumes. Both shipped storage implementations carry
zip-slip protection during extraction.

## Failure, retry, and giving up

The group path's policy, as a table because it is not obvious:

| Terminal event | Made progress this attempt? | Engine does |
|---|---|---|
| `FileReady` | — | commit, verify, clear stall count, done |
| `Cancelled` | — | clear stall count, delete temp file, `Idle`, throw `DownloadCancelledException` |
| `Error` | yes | **retry unconditionally**, stall count reset |
| `Error` | no, count ≤ 2 | increment, re-enqueue |
| `Error` | no, count > 2 | clear stall count (the next `ensureAvailable` gets a fresh budget), delete temp file, `Error` state + notification, throw `DownloadFailedException` |

Three things make it work:

- **Stall detection is an inactivity timeout, not a wall clock.** A slow-but-progressing large
  transfer never trips it; a dead connection does. A stall re-enters the table above as an `Error`
  with no progress.
- **The stall counter is persisted** through `DownloadStateStore` — worthless if it reset with the
  process, since a crash loop would otherwise retry forever.
- **Error classification is one keyword-matching function** turning your
  `BackgroundResourceDownloader`'s raw platform strings into a `DownloadError`. It has to be
  string-based — what a failing transfer reports differs per platform and per layer — so it is
  confined to one place and everything downstream is typed.

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

The engine itself never calls this port; it exists so a `BackgroundResourceDownloader`
implementation has somewhere standard to turn a unit's `apiPath` into something fetchable. See
[`07-background-downloader.md`](07-background-downloader.md).

## Cancelling — user, and logout

Three distinct operations:

- **`cancelDownload(group)`** — cancels each unit, clears stall counts, deletes temp files, drops
  the notification, resets the group to `Idle`. Safe from any thread.
- **`cancelDownload(unit)`** — non-suspend; the state reset is dispatched onto the engine's own
  scope, so a caller that must observe the reset synchronously should await the flow rather than
  read it on the next line.
- **`cancelAllDownloads()`** — the **logout** entry point: cancels every group-level and per-unit
  download. It is `suspend` and awaits every per-unit reset directly, unlike the fire-and-forget
  `cancelDownload(unit)` overload — going through that overload instead lost the state write to a
  scope-cancellation race in the codebase this module draws from, kept here as documentation of why
  the suspend version exists.

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
  skips.
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
