# kmptoolkit-files — Overview

File-system primitives for Kotlin shared code, behind one `FileAccess` interface that behaves the
same on Android, iOS and the desktop JVM: atomic durable writes, bounded reads, safe recursive
delete, leftover sweeps, per-key coroutine locks, path and file-name rules including Windows, and
the app's private directories with backup handling. Every failure is a typed value, never a throw.

## The problem it solves

Almost every app writes small files that must survive a crash — a draft, a cursor, a downloaded
index, a queue journal — and cleans up after itself: temporary files from an interrupted write, a
cache directory that grew, a tree to remove. Shared Kotlin code has no common way to do either. The
standard library stops at `readBytes`/`writeBytes`, which truncate and then write, so a crash or a
full disk in the middle leaves an empty or half-written file. Each platform then needs its own
answer for "make it durable" (`fsync` on Android and the JVM, `F_FULLFSYNC` on iOS), for "delete a
directory without following a symbolic link", for names that Windows refuses, and for the three
different failure vocabularies (`IOException` subclasses, `errno`, `NSError`).

```kotlin
// Platform layer — the only place a factory is named.
val files: FileAccess = createFileAccess()

// Shared code — takes the interface. Every call is blocking: run it through withIo.
suspend fun saveDraft(files: FileAccess, path: String, bytes: ByteArray): FileResult<Unit> =
    files.withIo { writeAtomically(path, bytes, WriteOptions(Durability.Synced, createParentDirectories = true)) }

when (val result = saveDraft(files, path, bytes)) {
    is FileResult.Success -> Unit
    is FileResult.Failure -> when (result.error) {
        is FileError.NoSpace -> askToFreeSpace()
        is FileError.AccessDenied, is FileError.NotFound -> report(result.error)
        else -> report(result.error)
    }
}
```

What follows from that shape, and why the module exists:

- **A write is all or nothing.** `writeAtomically` writes a new temporary file, syncs it as you
  asked, and renames it over the target. A reader, another process, or the same app after a crash
  sees the previous content or the whole new content — never a mix, never an empty file in between.
  You choose how far it goes (`Buffered`, `Synced`, `SyncedWithDirectory`), see
  [`03-guide.md`](03-guide.md#durable-small-records-and-choosing-a-durability).
- **Every failure is a value.** No function throws except the documented `IllegalArgumentException`s
  for arguments a caller can avoid by construction (a negative size limit, a name that is not a
  plain file name). A full disk, a missing directory, a name that is too long, a volume without
  hard links are `FileError` cases you branch on.
- **Cleanup is safe.** Recursive delete and tree size never follow a symbolic link, are iterative
  (no depth limit), tolerate entries that vanish during the walk, and can be cancelled.
- **Locks are part of the toolkit.** `KeyedMutex` is one coroutine `Mutex` per key held only while
  someone holds or awaits it; `PathLocks` is a process-wide one over file paths for read-modify-write
  cycles.
- **Nothing is named for you.** The temporary-file prefix is a parameter
  (`FileAccessConfig.tempPrefix`, default `.kmptoolkit-`), so two libraries in one process never
  recognise each other's leftovers by accident.
- **Paths are plain `String`s.** Every toolkit module already takes strings, and the module does not
  import the instability of a pre-1.0 file library into a semver-covered API.

## What this is **not**

- **Not a shared or external storage API.** Everything here assumes app-private roots — `filesDir`,
  `cacheDir`, Application Support, `Caches`, a directory you own. No Android scoped storage,
  `MediaStore`, SAF (Storage Access Framework) trees or content URIs, no iOS document picker or
  iCloud container. These need a UI hand-off or a permission and cannot be hidden behind a path.
- **Not a document picker.** It never asks the user for a file.
- **Not streaming I/O.** There is no `Source`/`Sink`, no `InputStream`. A read returns the whole
  file as a `ByteArray` up to a limit you state; a write takes the whole content. A file that does
  not fit in memory wants [kotlinx-io](https://github.com/Kotlin/kotlinx-io) or Okio next to this
  module — they combine well, because both use plain paths.
- **Not a file watcher.** Nothing observes changes.
- **Not a hashing or compression library.** No digests, no ZIP.
- **Not a database.** Two files are two files; no operation groups several of them. Anything with
  structure, queries or transactions wants SQLDelight or Room.
- **Not a lock across processes.** `KeyedMutex` and `PathLocks` coordinate coroutines of one
  process. Two processes sharing a file need their own protocol (the atomic replace keeps each
  individual file whole; it does not order two writers).
- **Not a permission helper.** App-private roots need none, and no permission is declared. Reaching
  a file outside them is yours to arrange.

## When to use it

Use it when shared code writes small records that must survive a crash, or cleans up files: session
snapshots, a journal, a resumable-transfer index, a cache directory, the leftovers of an interrupted
write. If a library of the suite should do the same job for you, it already does: `kmptoolkit-storage`
is a key-value store, `kmptoolkit-downloader` and `kmptoolkit-uploader` own their own files. This
module is for the files that are yours.

When you only need a stream of bytes, or a file larger than memory, prefer kotlinx-io. When you only
need a few string settings, prefer `kmptoolkit-storage`.

## Targets

Android (minSdk 24), iOS (`iosArm64`, `iosSimulatorArm64`) and `jvm` — the desktop JVM is published
because `FileAccess` and `KeyedMutex` are types a consumer threads through **shared** code and
`kmptoolkit-storage`'s JVM store depends on this module; see
[`../01-architecture.md`](../01-architecture.md#desktop-targets).

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a durable record in shared code in five minutes
- [`03-guide.md`](03-guide.md) — durability, locking, leftovers, delete, cancellation, directories, errors
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — what each platform does underneath, Windows rules, device checks
- [`06-testing.md`](06-testing.md) — `FakeFileAccess`, crash and fault injection, `FileAccessContract`
