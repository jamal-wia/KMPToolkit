# kmptoolkit-files — Guide

Scenarios from the common to the specialised. Symbols are in
[`04-api-reference.md`](04-api-reference.md); what each platform does underneath is in
[`05-platform-notes.md`](05-platform-notes.md).

## Calling it: blocking, through `withIo`

Every `FileAccess` function does blocking I/O and is safe to call from any thread, concurrently, on
the same or different paths. From a coroutine, use `withIo`; it runs the block on a dispatcher
(`Dispatchers.IO` by default) and gives it a `CancellationCheck` that turns true when the calling
coroutine is cancelled:

```kotlin
suspend fun reload(files: FileAccess, path: String): ByteArray? =
    files.withIo { readBytes(path, maxBytes = 4L shl 20).getOrNull() }
```

Inside the block `this` is the `FileAccess`, so its functions are called without a receiver.

## Durable small records and choosing a durability

`writeAtomically(path, bytes, options)` is the one write. The `options.durability` decides how far
the guarantee reaches:

| `Durability` | What it does | After a process crash | After power loss | Cost |
|---|---|---|---|---|
| `Buffered` | no sync | old or whole new | the new name may point at an **empty** file | none |
| `Synced` (default) | the temporary file is synced before the rename | old or whole new | old or whole new, never torn | one `fsync` |
| `SyncedWithDirectory` | synced, then the directory entry is synced too (iOS: `F_FULLFSYNC`) | old or whole new | once `Success` is returned the new content survives | two syncs; on iOS a full flush, milliseconds |

`SyncedWithDirectory` holds on one condition: the directories on the path that existed before the call must
themselves be durable. A directory created a moment earlier with a plain `createDirectories` may still be
lost by a power cut, and the record with it. Get the directory from `AppDirectories` (it creates durably),
or create it with `createDirectories(dir, durable = true)`, or let the write create it
(`createParentDirectories`, which syncs what it created). A directory that has existed for a while, or
across a reboot, is durable; repeated durable calls on existing directories cost nothing.

Pick `Synced` for records you can regenerate or whose loss is tolerable (the default, and what the
downloader does). Pick `SyncedWithDirectory` for the few writes where "I told the user it was saved"
must still be true after a power cut — an outbox entry, a purchase receipt. Pick `Buffered` for
files whose loss costs nothing (a cache index that is rebuilt on a miss). The reason `Synced` is the
default and not `Buffered` is the empty-file case in the table: renaming an unsynced file over a good
one can leave nothing on a power loss.

```kotlin
files.withIo {
    writeAtomically(
        path = receiptPath,
        bytes = receiptBytes,
        options = WriteOptions(
            durability = Durability.SyncedWithDirectory,
            createParentDirectories = true,
            protection = FileProtection.CompleteUntilFirstUserAuthentication, // iOS only
        ),
    )
}
```

`WriteOptions` also has:

- `existing = ExistingFile.Fail` — never replace: `AlreadyExists` if anything is at the path. Uses a
  hard link, so a file system without hard links answers `Unsupported`.
- `tempDirectory` — where the temporary file goes, on the **same volume** as the target (another
  volume is `CrossDevice`). Use it to keep a watched directory free of temporary names.
- `tempPrefix` — the temporary file's name prefix for this call (default
  `FileAccessConfig.tempPrefix`).

On every failure the temporary file is removed and the target is untouched, with one documented
exception: when one of the final directory syncs of `SyncedWithDirectory` fails the call reports
`FileError.Io`, but the target already holds the new bytes.

Writers and readers that go through `FileAccess` in one process never make each other fail, on every
platform. Concurrent writers of one path each succeed and the last rename wins.

### Reading

`readBytes(path, maxBytes)` returns the whole file or a typed failure. A file larger than `maxBytes`
— judged by its size before reading, or by what reading found if it grew — is `TooLarge` and is not
returned in part. A device, pipe or socket is `Unsupported` and is never opened. The kind is checked
before the open, so a pipe that replaces the regular file between the check and the open is opened
by the blocking open, and the call then blocks the calling I/O thread until a writer appears (a race
the engines cannot close). `maxBytes` must be between 0 and `FileAccess.MAX_READ_BYTES`, or
`IllegalArgumentException`.

## Read-modify-write with `PathLocks`

`FileAccess` does not serialize anything: two coroutines that each read a counter, add one and write
it back lose an update. Wrap the whole cycle in a lock:

```kotlin
suspend fun increment(files: FileAccess, path: String): FileResult<Unit> =
    PathLocks.withLock(path) {
        files.withIo {
            val current: Long = readBytes(path, 32).getOrNull()?.decodeToString()?.toLongOrNull() ?: 0L
            writeAtomically(path, (current + 1).toString().encodeToByteArray())
        }
    }
```

`PathLocks` is process-wide and keys on `PathKey.of(path, CaseFolding.Always)`, so the spellings of
one file that differ in case or Unicode normalisation (`é` as one code point or two) share a lock.
Over-folding costs a little parallelism and never correctness. Library modules of the suite never use
`PathLocks`, so holding it cannot deadlock against a toolkit call. Use `tryWithLock` when you would
rather skip than wait.

For your own key space — per user, per item — make a private `KeyedMutex<K>`:

```kotlin
private val locks: KeyedMutex<String> = KeyedMutex()

suspend fun upload(id: String) = locks.withLock(id) { /* one at a time per id */ }
```

The registry holds a key only while someone holds or awaits it, so it does not grow with the number
of keys ever used (`activeKeyCount` is `0` when idle: assert it in tests to find leaks). It is not
reentrant — a `withLock` nested in a `withLock` of the same key deadlocks. `tryWithLock(key) { … }`
runs the action only when nobody holds **or awaits** the key, never suspends, and the action must
not suspend either.

## Temporary-file leftovers and `sweep`

A crash between "temporary file created" and "renamed" leaves a file named
`<prefix><digits>.tmp` behind. `FileNames.isTempName(name, prefix)` recognises that shape; `sweep`
removes entries by name and age:

```kotlin
suspend fun cleanUp(files: FileAccess, directory: String): Int =
    files.withIo { cancelled ->
        sweep(directory, olderThanMillis = 60 * 60 * 1000L, cancellation = cancelled) { name ->
            FileNames.isTempName(name)
        }.getOrNull() ?: 0
    }
```

`sweep` deletes only regular files and symbolic links directly in the directory (never recurses,
never deletes a directory) whose last modification is **more than** `olderThanMillis` before now.
Entries without a known time and entries dated in the future are kept. It returns how many it
deleted. The kind is checked before the remove, so an empty directory that replaces an aged matching
file between the check and the remove is removed (a race the engines cannot close). A path below a
regular file (`f/x`, `f` a regular file) is `NotADirectory(f/x)`, naming the path given; every other
function treats such a path as absent. The age is what keeps it from deleting the temporary file of
a write in flight: pick an age longer than your longest write. Run it at startup and when you open
a directory.

## Recursive delete and tree size

```kotlin
files.withIo { cancelled ->
    val size: Long = treeSize(cacheDir, cancelled).getOrNull() ?: 0L
    if (size > LIMIT) deleteRecursively(cacheDir, cancelled)
}
```

- `deleteRecursively` never follows a symbolic link (it removes the link), is iterative so a
  2000-deep tree cannot overflow the stack, counts an entry that disappears during the walk as
  removed (so two threads deleting one tree both succeed), keeps going past entries it cannot remove
  and then reports the first error. `Success` means nothing is left at the path.
- `treeSize` sums regular files; a link counts 0; an absent path is 0; entries that vanish or cannot
  be read count 0. It fails only with `Cancelled` or `InvalidPath`.
- Both check the `CancellationCheck` before every entry and stop with `FileError.Cancelled`, leaving
  the rest in place; rerunning finishes the job.

`delete(path)` removes one file, one link (never its target) or one empty directory; an absent path
is `Success`, a non-empty directory is `DirectoryNotEmpty`.

## Moving files

`move(from, to, options)` is one atomic rename within a volume. With `ExistingFile.Replace` it
replaces a non-directory at `to`; with `Fail` it never replaces anything (`AlreadyExists`). A
directory at `to` is `IsADirectory` — directories are never replaced. Links move as links.

Across volumes (`CrossDevice` from the rename) `MoveOptions.acrossVolumes` (default `true`) copies a
regular file to a temporary file next to `to` in 64 KiB chunks, syncs it, renames it into place and
only then removes `from`. A crash in between leaves both names holding the bytes — never none. The
copy checks the `CancellationCheck` before every chunk; a cancelled copy removes its temporary file
and leaves `from` and `to` as they were. A directory across volumes is `CrossDevice`.

`ExistingFile.Fail` with a directory or a symbolic link as `from` is `Unsupported`: no atomic
no-replace rename of a directory exists, and a hard link would follow the link on some platforms.

## App directories and backup

```kotlin
val cache: String = (directories.directory(AppRoot.Cache, "thumbnails") as FileResult.Success).value
val journal: String = (directories.directory(AppRoot.NoBackup, "journal") as FileResult.Success).value
val exports: FileResult<String> = directories.directory(AppRoot.Files, "exports", Backup.Excluded)
```

| `AppRoot` | Android | iOS | JVM |
|---|---|---|---|
| `Files` | `filesDir` | Application Support (not Documents: that folder is user-visible with file sharing) | `filesDirectory` |
| `NoBackup` | `noBackupFilesDir` | Application Support, directory excluded from backup | `filesDirectory` |
| `Cache` | `cacheDir` | `Caches` | `cacheDirectory` |
| `Temporary` | `cacheDir` | `NSTemporaryDirectory()` | `temporaryDirectory` |

`directory(root, name, backup)` creates `<root>/<name>` (and the root) if missing and returns its
path. `Backup.Excluded` marks the **directory** (iOS `NSURLIsExcludedFromBackupKey`, re-applied on
every call), so files replaced atomically inside it stay excluded — a flag on a file would be lost
when the atomic replace creates a new one. `NoBackup` is always excluded; `Cache` and `Temporary` are
never backed up, so `Excluded` is a no-op there. On Android `AppRoot.Files` with `Excluded` is
`FileError.Unsupported` and creates nothing (use `NoBackup`, which the platform excludes by
definition). The desktop JVM has no backup service: `Excluded` is a no-op. `name` must be a plain file
name or the call throws `IllegalArgumentException`.

`rootPath(root)` returns a root's absolute path without creating it.

## Names and paths

- `Paths` is **lexical** and uses the host's rules: POSIX on Android, iOS and Unix JVMs; Windows
  rules (`C:\`, `\\server\share`, both separators) on a Windows JVM. `isAbsolute`, `join`, `parentOf`,
  `normalizeLexically`, `resolveInside(base, relative)` for refusing `..` escapes. None does I/O.
- `FileNames.isPlainName(name)` / `requirePlainName(name)` check one directory-entry name: 255 UTF-8
  bytes at most, no `/` or NUL or unpaired surrogate, and on Windows also no `\ <>:"|?*`, no control
  characters, no trailing `.` or space, no reserved device name (`CON`, `NUL`, `COM1`…, also
  `CON .txt`). Use it on anything that comes from outside before you join it onto a path.
- `FileAccess` paths are **passed to the platform as given**: no normalisation, no case folding. The one exception: trailing
  separators are dropped first, so `d/` and `d` name the same entry on every platform (a trailing separator never follows a link).
  Only `PathKey.of(path, CaseFolding.Always)` folds, for in-memory comparison and lock keys — never
  persist its output.
- A relative, empty or NUL-containing path is `FileError.InvalidPath`; queries (`metadata`, `exists`)
  answer `null` / `false`.
- `SqliteFiles.sidecarsOf(databasePath)` returns `-wal`, `-shm`, `-journal` in that order: delete or
  move all four together.

## Errors

Every function returns `FileResult.Success` or `FileResult.Failure(error)`:

| `FileError` | Typical cause | Usually |
|---|---|---|
| `NotFound` | missing file, or missing parent without `createParentDirectories` | create it, or treat as "not there yet" |
| `AlreadyExists` | `ExistingFile.Fail` over something | someone else won; reread |
| `NotADirectory`, `IsADirectory` | wrong kind at a path | a bug or a hostile name; report |
| `DirectoryNotEmpty` | `delete` of a directory with content | use `deleteRecursively` |
| `AccessDenied` | permissions, read-only volume, Windows file held open past the retry budget | retry later, or report |
| `NoSpace` | disk full or quota | ask to free space; nothing was changed |
| `NameTooLong` | a name over the file system's limit | shorten it |
| `CrossDevice` | rename across volumes without `acrossVolumes`, temp directory on another volume | enable the copy, or fix the layout |
| `TooLarge` | file bigger than `maxBytes` | raise the limit or treat as corrupt |
| `Unsupported` | no hard links, a device file, backup exclusion on Android `Files` | choose another path |
| `InvalidPath` | relative, empty, NUL, a Windows reserved name, or a Windows `\\?\` or `\\.\` path | a bug in the caller |
| `Cancelled` | the `CancellationCheck` turned true | stop; state is documented per function |
| `Io(path, platformCode, cause)` | anything else | report with `cause` |

`FileError` never carries text meant for display — map cases to your own strings. `platformCode` is
the `errno` (or Win32 code) when known. Helpers: `getOrNull()`, `errorOrNull()`, `isSuccess`.

The only exceptions that escape are `IllegalArgumentException` for argument contracts (`maxBytes`,
`olderThanMillis`, a non-plain directory name, an invalid temp prefix), `CancellationException` from
`withIo`, and whatever your own lambdas (`sweep`'s `match`, `KeyedMutex` actions) throw.
