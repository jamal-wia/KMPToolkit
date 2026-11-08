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

### Platform limits of the transfer layer

- **Android 15 caps a `dataSync` foreground service at 6 hours** per 24 hours, for apps that target
  Android 15 (API 35) or higher. The system calls the service's `onTimeout`, which must stop it
  within seconds; a new `dataSync` service cannot start from the background afterwards. Report it
  as an `Error`: the engine keeps the partial and an `ensureAvailable` made once the app is in the
  foreground again resumes it. A `WorkManager` worker kept alive with `setForeground` is the same
  foreground service and under the same limit; see 07 for the details.
- **A background `NSURLSession` always waits for connectivity** and stays silent while it does, for
  up to its `timeoutIntervalForResource` (7 days by default). The engine's stall timer (5 minutes,
  armed at subscription) reads that as a stall, so the downloader re-emits the current `Progress`
  about every 60 seconds while the system lists the task (through a flow that delivers equal
  values, not a `StateFlow`), or the host sets `DownloaderConfig(stallTimeout = Duration.INFINITE)`. See the iOS section of
  [`07-background-downloader.md`](07-background-downloader.md).
- **A resume with `Range` needs a validator the library does not store.** Storage records only the
  expected hash, no `ETag`; an `If-Range` header on Android is the downloader's own business.

None of the permissions above are declared by `kmptoolkit-downloader` — see
[`07-background-downloader.md`](07-background-downloader.md) for the implementation itself, which
is where these live.

## Storage locations

| Platform | Default base directory | Notes |
|---|---|---|
| Android | `filesDir/kmptoolkit_downloader/` | Configurable via `DownloaderStorageConfig.baseDirectoryName`: one plain folder name, not blank, not `.` or `..`, no path separator or NUL (otherwise the constructor throws `IllegalArgumentException`). |
| iOS | `Application Support/kmptoolkit_downloader/` | Not `Documents/` — these are re-downloadable assets, and Application Support is excluded from the user-facing "Documents & Data" iCloud backup by default. Also explicitly marked `NSURLIsExcludedFromBackupKey` as a second net. |

Temp files live under `<base>/tmp/`: an unfinished transfer at `<unit.id>.<unit.tempExtension>`,
and the same file renamed to `<unit.id>.<unit.tempExtension>.complete` once
`markTempFileComplete` declares it finished. The extension is part of that name, so keep it stable
per id, as you keep `relativePath` stable: instances of one id that return different extensions see
different temp files. The rename is atomic within one directory on both
platforms, so a crash leaves either a partial file or a complete one. A `ZipArchive` unit stages
its extraction under `<base>/tmp/staging-<unit.id>/` — per unit, so two archives extracting at the
same time never collide, and two commits of the same unit never share it, since commits of one unit
are serialized across every storage instance over the base directory in the process. A process kill
mid-extraction leaves the tree behind; the next commit of that unit replaces it, and
`deleteTempFile(unit)` (so `cancelDownload`) removes it — unless a commit of that unit is running in
the process at that moment: the removal never waits for it (it would be deleting the tree the commit
is extracting into), so it is skipped and the next cancel or commit reclaims the tree. Another
unit's commit registering or leaving the lock registry never causes a skip (that probe is retried).
The tree is only renamed to `tmp/kmptoolkit-internal/trash/<random number>` under the lock and
deleted after it is released, so a large extraction never stalls other units' commits (the entry is
named by a number alone, so no id's length can make the rename fail). If the process dies or the
delete fails midway, the remainder stays in `tmp/kmptoolkit-internal/trash/`; every later
`deleteTempFile`, for any unit, sweeps that directory (nothing ever commits from it, so no lock is needed). A plain file
is moved into place by an atomic rename that replaces the old file, on both platforms, so a
committed resource never goes missing while it is replaced. The lock is per process: a
`BackgroundResourceDownloader` that commits from a separate Android process is not covered by it. The hash a transfer must have (`beginTempFile`) is a one-line file at
`<base>/tmp/expect/<unit.id>.sha256`, keyed by id alone so a free-form `tempExtension` cannot make two
units share or miss a record; it is written to `<base>/tmp/expect-staged/<unit.id>.sha256` and flushed to
disk first, then moved into place by an atomic rename once the old temp files are gone.

#### Units whose id is not a plain file name

The names above are those of a unit whose id has no `/` and no NUL, whose `<id>.<ext>.complete`
(and `staging-<id>`) fit in 255 bytes, and whose `tempExtension` is one plain name. For every other
unit the same files live under `<base>/tmp/kmptoolkit-internal/`, named by a key: the lowercase
base32 SHA-256 of the id (52 characters of `a-z2-7`). So `models/v1` has its partial at
`tmp/kmptoolkit-internal/<key>.tmp`, its record at `tmp/kmptoolkit-internal/expect/<key>.sha256`, its
staging at `tmp/kmptoolkit-internal/staging-<key>`. A `tempExtension` that is not a plain name of at
most 64 bytes is replaced by `tmp` in the hashed name (the extension is cosmetic); the record stays
keyed by the id alone. Nothing a plain id owns can be under `kmptoolkit-internal`, which is why a
hashed unit can never collide with another unit's files, and why `tmp/` as a whole is reserved for the
storage: do not put your own files there. Always take the temp path from `getTempFilePath(unit)`,
never build it, and never cache it across launches: it may be under a subdirectory, and
`beginTempFile` makes sure that directory exists.

A unit whose names are hashed gets its 3.0.0 temp files moved on first touch in each process: the
record, the complete file and the partial file move to their hashed place with a move that never
overwrites (`link(2)` of the new name, then removal of the old one, so a destination created at the
same moment is not replaced; when the destination already exists it wins and the old file is
deleted), and a leftover staged record is deleted. A file system without hard links cannot do the
move: it is logged and the old file stays. A process killed in between finishes the move next time. The move
takes no lock. A file there can only be moved if it is really the unit's own old temp file, so a `relativePath` equal to the unit's own 3.0.0 partial or `.complete` path (`tmp/<id>.<ext>`, with the unit's own id and extension) is rejected like its hashed temp names (`RESERVED_INTERNAL_PATH`). It only moves files whose old path was under `tmp/` and not below `expect`, `expect-staged`,
`staging-…` or `trash`, so an old `tmp/staging-a/b.tmp`, `tmp/expect/foo.sha256` or `tmp/../x.tmp`
left by an id with a separator stays where it is, and so does `tmp/trash/x.tmp` (in 3.0.0 only the
unit `trash/x` could have written it; leaving it is a conservative rule, not a necessity); such orphans and old staging trees are not
cleaned up. A downloader that cached the old path across the app update and keeps writing to it can
write into a file that has been moved; take the path again after every launch.

Only partial and complete files that already existed before this version first used the base
directory are moved. On that first use the storage writes a one-time marker,
`tmp/kmptoolkit-internal/legacy-layout`, holding `fresh` (the base had nothing under `tmp/`, so there
is no 3.0.0 file: nothing is ever migrated) or `upgraded` (it had files: only a partial or complete
file whose modification time is strictly older than the marker is moved). A resource that another
unit commits later at a path that looks like such a file (a `relativePath` under `tmp/`, which is
accepted with a warning) is therefore never swept into the temp area. Records are not subject to this:
they live under `tmp/expect/`, which no resource may use. If the marker cannot be written or read,
nothing is moved, a warning is logged once, and the unit's partial download starts again instead of
resuming. Residual: a file under `tmp/` with an older modification time than the marker (an adopted
or copied file that kept its time, or a clock set back) that has the exact legacy name of a hashed
unit's partial can still be moved; do not put your own files under `tmp/`.

Known limits that are not fixed: two units whose id and extension join to the same name (`a.b` with
`c`, and `a` with `b.c`) still share files, as in 3.0.0; ids that differ only in case or Unicode
normalization share files on a case-insensitive volume. On such a volume (iOS asks the volume once
per storage; an unanswerable volume counts as case-insensitive) the commit lock of `Model` and
`model` is shared, so they never commit or detach a staging tree at the same time, and a
`relativePath` such as `TMP/Expect/x` is rejected like `tmp/expect/x` (case is folded with the full
mapping, so `tmp/ſtaging-x` is rejected too, and ids such as `ß`, `ẞ`, `straße` and `STRASSE` share a commit
lock). Android is case-sensitive.

On iOS the OS may restart a background download from zero while claiming to resume it from
`resumeData`. The bytes of the new file then do not match the hash recorded for the first one, so
the first commit fails as an integrity failure. The engine's integrity retry downloads afresh with a
new resolve and a new record, which is usually just one extra download, not an error;
`DownloadError.Corrupted` is reported only if the retry mismatches too.

On iOS the expected-hash record is created with `NSFileProtectionNone`. It holds only a public hash,
and a background commit can run while the device is locked: under the app's default protection
class (Complete, or until first unlock) the record would be unreadable, and a finished download
would be deleted as corrupted. The rename into place keeps the attribute.

### Adopting a directory your app already populated

The layout under the base directory is fixed for every unit whose id is a plain file name (see
above): a committed resource at `<base>/<unit.relativePath>`, an in-progress transfer at
`<base>/tmp/<unit.id>.<unit.tempExtension>`, an unpacked archive recognised by its
`availabilityMarker`. An app that stored its downloads in exactly that shape under
its own directory name — typically because it is moving its own downloader onto this module — keeps
every one of them by naming that directory:

```kotlin
createDownloaderStorage(context, DownloaderStorageConfig(baseDirectoryName = "resources"))
```

Committed resources are then found in place, a half-finished transfer resumes from its current size,
and the next commit lands beside them. A temp file your old code left is always treated as
`Partial`, even if its transfer had in fact finished: your code never recorded completeness, and
guessing from the size is exactly what this module refuses to do. Its transfer is resumed with a
`Range` starting at the file's size; if the file was already whole, the server answers
`416 Range Not Satisfiable`, which your `BackgroundResourceDownloader` should treat as "already
complete" and mark it so when the reported total matches (and otherwise restart from byte zero) (see [`07-background-downloader.md`](07-background-downloader.md)).

Leave the default instead and every existing user downloads everything again while the old copies
stay on disk, unreferenced. The base directory is resolved under the same root either way —
`filesDir` on Android, `Application Support` on iOS — so only the name has to match. The adoption
cases are pinned by `AndroidDownloaderStorageAdoptionTest`.

Staging directories are the one part that is not adopted: they are transient by design, and one left
behind by an interrupted extraction under an old name is simply never reused.

## Integrity checks

Android verifies a `ResourceFormat.SqliteDatabase` unit with `android.database.sqlite` — already
on every Android classpath, no extra dependency. iOS has no such built-in binding, so this module
depends on `androidx.sqlite`'s bundled driver there — a small, well-tested SQLite implementation
shipped as a Kotlin/Native artifact, used **only** for this integrity check, never for your own
data.

ZIP extraction is `java.util.zip` on Android and a hand-written streaming extractor over zlib's raw
`inflate` on iOS (Kotlin/Native has no `java.util.zip`). Both stream through a single reusable
buffer rather than loading an entry into memory, both extract every entry or fail the commit, and both
treat an entry that would leave the extraction directory as a failure (names are resolved lexically on
`/` only: `a/../inner.txt` extracts to `inner.txt`, `release..notes.txt` is an ordinary name, `../evil.txt`
fails). Both check each entry's sizes and CRC-32; an entry written with a data descriptor (a streaming
writer's layout, as `java.util.zip.ZipOutputStream` produces) is supported for DEFLATED entries. A
truncated or corrupt archive, an unsupported compression method, an encrypted entry or a traversal name
fails the commit with `ResourceIntegrityException` (one fresh download, then `DownloadError.Corrupted`).
iOS does not read ZIP64 archives (entries or archives over 4 GiB): such an archive fails the commit there.
The walk is the one `java.util.zip.ZipInputStream` makes: local headers in order, the central directory
never read. An archive must end with its end of central directory record (a comment cut short is tolerated, and
so are bytes after the record); an archive cut anywhere before it, its central directory included, fails the
commit with `ResourceIntegrityException` on both platforms. This is decided from the file alone before anything is
extracted: a signature `PK\5\6` with 22 bytes after it, within the last 69,633 bytes, whose central directory
offset plus size equals its own position (a ZIP64 record counts when its locator precedes it). Self-extracting
archives (a stub in front of the first header) fail on their first bytes. Entry names are read as UTF-8 whether
or not the archive sets the UTF-8 flag; a name that is not valid UTF-8 (an archive written in a legacy code page)
fails the commit. Android 14 and later (`targetSdk` 34+) also refuse entry names containing `..` segments or
starting with `/` inside `java.util.zip` (Zip path validation); on those devices such archives fail as
`Corrupted`, while older devices and iOS extract them under their normalized names.

When the marker is not at the top and the archive holds exactly one non-empty top-level directory that
contains it, that directory's contents are moved up; files and empty directories beside it stay where they
are, and a name that exists at the top as a file fails the commit with `ResourceIntegrityException`. An empty
directory of the same name is replaced; a non-empty directory is never deleted.

A `SqliteDatabase` file must be at least 100 bytes long and start with the 16-byte SQLite header
(`SQLite format 3` and a NUL); an empty or cut body fails the commit, before the database is opened. The row
count table is used as a name (resolved through `sqlite_master`, then quoted: a keyword or a name with spaces
works) and the meta key is bound as a value, so neither is ever SQL text. A table value that names no table is
still used as SQL text, as in 3.0.0, for compatibility only.

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
  implementation's error message is recognisable) or, for a commit-time failure (a write during a
  `ZipArchive` extraction, a move into place), as `DownloadError.Storage`. Extraction writes are checked
  on both platforms, so a full disk during extraction fails the commit instead of leaving a truncated
  file. An archive whose bytes cannot be extracted at all is not a storage failure but
  `ResourceIntegrityException` (above).
- **`DownloaderStorage` keys everything by the unit's own properties (`id`, `relativePath`), never
  by object identity or by any internal per-instance map.** Two `DownloadUnit` instances with the
  same `id` — a host constructing a fresh instance per call is common and expected — always resolve
  to the same bytes on disk and the same commit outcome.

## No `iosX64` target

This module publishes `iosArm64` and `iosSimulatorArm64` only — the same set as every other
`kmptoolkit-*` module (see `docs/01-architecture.md`). Independently of that suite-wide choice,
this module could not offer `iosX64` anyway: `androidx.sqlite:sqlite-bundled`, which backs the
`SqliteDatabase` integrity check on iOS, publishes no `iosX64` klib at all. Building or testing
against the Intel iOS simulator is not supported.
