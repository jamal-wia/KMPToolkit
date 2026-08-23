# kmptoolkit-downloader — overview

A resumable background-download engine for the assets an app can't ship inside its own binary: a
bundled dataset, a downloadable model file, a large media archive fetched from a server manifest.
The transfer outlives the process — resumed after backgrounding, process death and restart — and a
resource is verified before it counts as present, so a consumer that gets `pathOf(unit)` back can
open the file without defensive checks.

The problem it solves is the one every app with an optional large asset hits eventually: a plain
`GET` that streams to a file is easy to write and easy to get wrong under the conditions that
actually happen — the user backgrounds the app mid-transfer, the process is killed to reclaim
memory, the network drops for ten seconds. Re-deriving retry, resume and commit logic for every
asset means re-deriving the same bugs for every asset.

```kotlin
// Downloads whatever of the group is missing, suspending until it is all present. Returns at
// once if it already is; concurrent callers for the same group share one download.
downloader.ensureAvailable(MyResourceGroup.LANGUAGE_MODEL)
val modelPath: String = downloader.pathOf(MyDownloadUnit.LanguageModel)
```

From there the engine owns it: one download per group with progress scaled across whatever still
needs fetching, an inactivity-based stall timeout instead of a wall-clock one, bounded retries with
their count persisted so a crash loop doesn't retry forever, and — before anything is reported
`Completed` — a re-check that the committed bytes are actually there.

## What you get

- **Resumability, where the platform allows it.** A transfer that dies with a partial temp file on
  disk resumes from that file rather than restarting; a storage implementation exposes the byte
  offset so your downloader can issue a ranged request.
- **Verification before counted present.** A `ResourceFormat.SqliteDatabase` unit is opened and,
  optionally, its own declared row count is checked against the database file's real one; a
  `ResourceFormat.ZipArchive` unit is proven complete by the presence of a marker file the
  extraction is known to produce. A truncated download fails at commit, not three layers away
  inside whatever opens the file next.
- **Survives process death.** Nothing about an in-flight download is persisted by the engine except
  a stall counter — "is a transfer running" is answered by your `BackgroundResourceDownloader`,
  "is it done" by the file on disk. A crash needs no recovery pass; the next `ensureAvailable`
  re-derives everything from those two questions.
- **Two surfaces, not one generalized over the other.** A group path for a fixed bundle the app
  ships against — one mutex, aggregate progress, one notification. A per-unit path for a runtime
  catalogue where several rows download independently, each with its own progress and cancel.
- **Typed failure, on both surfaces identically.** `ensureAvailable` throws
  `DownloadCancelledException` / `DownloadFailedException`, and publishes the same outcome to the
  matching state flow — a caller may equally catch the exception or render from state.
- **No user-facing text anywhere.** `DownloadNotifier` carries typed progress and a typed
  `DownloadError`, never a string meant for display.

## The one port this module cannot supply

**There is no HTTP client and no bundled transfer implementation.** Moving bytes in a way that
survives the app being backgrounded or killed is `BackgroundResourceDownloader` — an SPI with a
documented contract and a walkthrough at [`07-background-downloader.md`](07-background-downloader.md).
Everything else — storage, commit, verification, retry, progress scaling, notification timing — is
this module's job, once, so it never has to be re-derived per asset.

That split exists because the donor code this module draws from tied its transfer implementation
directly to its own app's API client and base URL; here the engine never learns either. Storage
*is* shipped — `createDownloaderStorage(...)` on both Android and iOS — because "where do committed
bytes live on this device" has one right answer per platform, unlike "how do bytes get here",
which depends entirely on your backend.

## What this is not

- **Not a general-purpose HTTP client.** It fetches nothing itself. `DownloadUrlResolver` turns an
  opaque path into a URL; your `BackgroundResourceDownloader` fetches it with whatever transport you
  choose.
- **Not a small-file fetcher.** For anything the caller wants in memory, or a plain JSON response, a
  normal suspend call is the right tool. Reach for this module when the transfer must outlive the
  process and the commit must be verified.
- **Not per-user or per-account storage.** Resources are device-global; there is no partitioning by
  signed-in account. See [`05-platform-notes.md`](05-platform-notes.md).
- **Not an integrity guarantee beyond format.** No checksum, no signature, no expected byte count —
  a server serving the wrong `Opaque` bytes commits them happily. `SqliteDatabase` and `ZipArchive`
  get real checks because their shape allows one; `Opaque` does not.
- **Not a job scheduler.** There is no "run this at 9am". If you want time-based scheduling instead
  of demand-driven downloading, that is
  [`kmptoolkit-scheduler`](../kmptoolkit-scheduler/01-overview.md).

## Shape of the API

| Type | Role |
|---|---|
| `Downloader` | What consumers depend on: `ensureAvailable`, `isAvailable`, `pathOf`, `downloadState`, `unitDownloadStateFlow`, cancel and retry |
| `DownloadUnit` | One downloadable thing — a remote location plus a place on disk. The host's own catalogue; the library ships none |
| `ResourceGroup` | A user-facing bundle of units — one notification, one aggregate progress |
| `ResourceFormat` | What committing a unit's bytes means: `Opaque`, `ZipArchive`, `SqliteDatabase` |
| `DownloadError` | The library's own small failure taxonomy |
| `BackgroundResourceDownloader` | **The SPI you implement** — the platform transfer itself |
| `DownloaderStorage` | Shipped for you (Android and iOS) — where bytes live on disk |
| `DownloadUrlResolver` | Turns a unit's opaque path into a fetchable URL |
| `DownloadNotifier` | How a download tells the user it is happening — typed, no strings |
| `DownloadStateStore` | The one bit of engine state that must survive process death — the stall counter |
| `DownloadDispatchers` | The dispatchers the engine's own coroutines run on |
| `DownloaderRegistry` | A process-wide slot for OS-constructed entry points to find the running `Downloader` |

Next: [`02-getting-started.md`](02-getting-started.md).
