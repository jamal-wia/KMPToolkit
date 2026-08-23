# Platform notes

The engine itself is identical on both platforms. Storage differs a little; the transfer layer —
which this module does not ship — differs a lot, and so does what you must declare to run it.

## Permissions

**This module's own manifest declares nothing**, on either platform. `kmptoolkit-downloader`
itself only reads and writes files under the app's own storage — no network permission, no
notification permission, no foreground-service permission.

Your own `BackgroundResourceDownloader` implementation is a different matter — it is the part of
this system that actually opens a socket and, typically, runs a foreground service or a background
session so the transfer survives being backgrounded. Whatever you build there will need, in your
own app's manifest / `Info.plist`, roughly:

| Platform | Typically needed | Why |
|---|---|---|
| Android | `android.permission.INTERNET` | Your transfer implementation opens a connection. |
| Android | `android.permission.ACCESS_NETWORK_STATE` | If you gate a retry on connectivity. |
| Android | `android.permission.FOREGROUND_SERVICE` and a specific type (e.g. `dataSync`) on API 34+ | If your transfer runs as a foreground service so the OS does not kill it mid-download. |
| Android | `android.permission.POST_NOTIFICATIONS` (API 33+) | If your foreground service posts a visible notification — most do, since Android requires one. |
| iOS | `UIBackgroundModes` → `fetch` or a background `NSURLSessionConfiguration` | For a transfer that should continue after the app is suspended. |

None of these are declared by `kmptoolkit-downloader` — see
[`07-background-downloader.md`](07-background-downloader.md) for the implementation itself, which
is where these live.

## Storage locations

| Platform | Default base directory | Notes |
|---|---|---|
| Android | `filesDir/kmptoolkit_downloader/` | Configurable via `DownloaderStorageConfig.baseDirectoryName`. |
| iOS | `Application Support/kmptoolkit_downloader/` | Not `Documents/` — these are re-downloadable assets, and Application Support is excluded from the user-facing "Documents & Data" iCloud backup by default. Also explicitly marked `NSURLIsExcludedFromBackupKey` as a second net. |

Temp files live under `<base>/tmp/`; a `ZipArchive` unit stages its extraction under
`<base>/tmp/staging-<unit.id>/` — per unit, so two archives extracting at the same time never
collide.

## Integrity checks

Android verifies a `ResourceFormat.SqliteDatabase` unit with `android.database.sqlite` — already
on every Android classpath, no extra dependency. iOS has no such built-in binding, so this module
depends on `androidx.sqlite`'s bundled driver there — a small, well-tested SQLite implementation
shipped as a Kotlin/Native artifact, used **only** for this integrity check, never for your own
data.

ZIP extraction is `java.util.zip` on Android and a hand-written streaming extractor over zlib's raw
`inflate` on iOS (Kotlin/Native has no `java.util.zip`). Both stream through a single reusable
buffer rather than loading an entry into memory, and both carry zip-slip protection.

## Both platforms

- **No per-account partitioning.** Resources are device-global; there is no per-user storage
  split. If two signed-in accounts on the same device need different assets under the same `id`,
  that is outside this module's model — give them different ids instead.
- **`Available` / `Completed` are process-lifetime for the per-unit surface.** A resource deleted
  *around* the downloader (via `DownloaderStorage.deleteResource` called directly, rather than
  through the engine) leaves that unit's flow reporting stale state until the process restarts —
  nothing re-polls disk on its own. Delete through a path the engine observes, or expect the state
  to lie until relaunch.
- **No disk-space precondition.** Neither platform checks free space before enqueueing; a full disk
  fails during the transfer or during commit, and surfaces as whatever your
  `BackgroundResourceDownloader` reports (commonly `DownloadError.Unknown` unless your
  implementation's error message is recognisable) or, for a commit-time failure (a `ZipArchive`
  extraction, a `SqliteDatabase` write), as `DownloadError.Storage`.
- **`DownloaderStorage` keys everything by the unit's own properties (`id`, `relativePath`), never
  by object identity or by any internal per-instance map.** Two `DownloadUnit` instances with the
  same `id` — a host constructing a fresh instance per call is common and expected — always resolve
  to the same bytes on disk and the same commit outcome.
