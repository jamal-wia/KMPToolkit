# kmptoolkit-files — Platform notes

What each platform does underneath, the rules that differ between file systems, and what has not
been verified off a device.

## Permissions and app configuration

**None.** No Android permission is declared or required, nothing goes into `Info.plist`, and no
entitlement is needed: every root of `AppDirectories` is app-private. Following the repository rule in
[`../01-architecture.md`](../01-architecture.md), the module declares nothing in its own
`AndroidManifest.xml`. Reaching a file **outside** the app-private roots (shared or external storage,
SAF trees, document pickers) is out of scope; see [`01-overview.md`](01-overview.md).

## What each operation uses

| Step | Android with `java.nio.file` (API ≥ 26, host tests) | Android API 24–25 (`android.system.Os`) | iOS (POSIX) | JVM Linux/macOS | JVM Windows |
|---|---|---|---|---|---|
| create temp | `FileChannel.open(CREATE_NEW, WRITE)` | `Os.open(O_CREAT or O_EXCL or O_WRONLY)` | `open(O_WRONLY or O_CREAT or O_EXCL or O_CLOEXEC, 0666)`, protection set through `NSFileManager` before the first write | as Android NIO | as Android NIO |
| `Synced` | `force(true)` | `Os.fsync` | `fsync` | `force(true)` | `force(true)` |
| `SyncedWithDirectory`, file | `force(true)` | `Os.fsync` | `F_FULLFSYNC` (falls back to `fsync` on `ENOTSUP`/`EINVAL`) | `force(true)` (a macOS JVM cannot issue `F_FULLFSYNC`) | `force(true)` |
| replace | `Files.move(ATOMIC_MOVE)` | `Os.rename` | `rename(2)` | `Files.move(ATOMIC_MOVE)` | same, plus the retry below |
| no-replace | `Files.createLink` + delete | `Os.link` + `Os.remove` | `link(2)` + `unlink` | `Files.createLink` | `Files.createLink` (NTFS yes, FAT/exFAT no → `Unsupported`) |
| directory sync | `FileChannel.open(dir, READ).force(true)` | `Os.open(dir, O_RDONLY)` + `Os.fsync` | `open(O_RDONLY)` + `F_FULLFSYNC` (fallback `fsync`) | `FileChannel.open(dir, READ).force(true)` | skipped (a directory cannot be opened; NTFS journals the rename) |

One engine written over a small internal SPI composes these (the same code runs on every target, and
under `FakeFileAccess`). A failed `Os`/`errno` call is mapped, never thrown: `ENOENT` → `NotFound`,
`EEXIST` → `AlreadyExists`, `ENOTDIR` → `NotADirectory`, `EISDIR` → `IsADirectory`, `ENOTEMPTY` →
`DirectoryNotEmpty`, `EACCES`/`EPERM`/`EROFS` → `AccessDenied` (`link`: `EPERM`, `ENOTSUP`,
`EOPNOTSUPP` → `Unsupported`), `ENOSPC`/`EDQUOT` → `NoSpace`, `ENAMETOOLONG` → `NameTooLong`, `EXDEV` →
`CrossDevice`, anything else `Io(platformCode = errno)`; `EINTR` is retried inside the operation.

## Rename and open files

On POSIX a `rename` replaces a target that another process (or the same one) has open: the reader keeps
the old inode. The replace is therefore never blocked by readers on Android, iOS or a Unix JVM.

On **Windows** a rename cannot replace a target that is open, even when the opener used
`FILE_SHARE_DELETE` (which this library's own readers do). The module handles the two cases
differently:

- **In-process readers and writers are coordinated.** On a Windows JVM the NIO ops keep an
  in-process gate: a read holds a shared permit on its path until the file is closed, and a replace
  holds an exclusive permit on both of its paths (taken in a fixed order, so two renames in opposite
  directions cannot deadlock) for the whole retry loop. Writers and readers that go through
  `FileAccess` in one process therefore never make each other fail. "One path" means the spellings that
  `PathKey.of(path, CaseFolding.Always)` equates (case, separators, `.`/`..`). Other names of the same file —
  an 8.3 short name, an administrative share such as `\\localhost\C$\...`, a junction, symbolic link or `subst`
  drive — are coordinated like another process: the bounded retry, then `AccessDenied`. The gate is fair — a waiting
  replace blocks later readers, so readers that never pause cannot starve it — and the wait is
  interruptible: an interrupted thread gets `AccessDenied` with its interrupt flag set again.
- **Holders outside the process (or outside `FileAccess`) get a bounded retry.** An indexer, an
  antivirus scanner, another program, or your own code reading with `FileInputStream` can hold the
  target without `FILE_SHARE_DELETE`. `Files.move` then throws `AccessDeniedException`, and the
  rename is retried up to 10 attempts, sleeping 1, 2, 4, 8, 16, 32, 50, 50, 50 ms between them
  (about 210 ms in all) — only on Windows, only for that exception, only in `rename`. After the budget
  the call is `FileError.AccessDenied`, the target is untouched and no temporary file is left. The
  library never removes the target and then moves.

`AtomicMoveNotSupportedException` is `CrossDevice`; there is no non-atomic fallback anywhere.

Before the POSIX delete semantics of Windows 10 version 1809 a `delete` while an in-process reader is
open leaves the file delete-pending and a further reader then gets `AccessDenied`; current Windows
removes the name at once.

## Windows names and paths

- Paths accept both `\` and `/` and are written with `\`; roots are `X:\` and `\\server\share\`.
  `\x` (drive-relative) and `X:x` are relative, hence `InvalidPath`. Plain paths of any length work: the JDK
  adds `\\?\` itself for long paths. A path in the extended-length or device namespace (`\\?\...`,
  `\\.\...`, either separator) is `InvalidPath`.
- `FileAccess` rejects, as `InvalidPath`, any segment that is a reserved device name (`CON`, `NUL`,
  `COM1`…, also with a trailing space before the dot: `CON .txt`) or ends in `.` or a space — this
  also stops a write from opening a console device. `FileNames.isPlainName` applies the same rules
  for names you construct.
- NTFS counts a name in 255 UTF-16 code units; see the next section.

## Name length: 255 bytes, characters or code units

`FileNames.MAX_NAME_BYTES` (255 UTF-8 bytes) is the **portable** limit `isPlainName` applies on every
host. File systems disagree: ext4 and F2FS (Android) and most Linux file systems limit a name to 255
bytes; APFS (macOS, iOS) limits it to 255 characters; NTFS counts 255 UTF-16 code units. A name of 200
CJK characters (600 UTF-8 bytes) is therefore accepted on NTFS and APFS and refused on ext4/F2FS.

`FileAccess` never pre-rejects a name by length — the platform decides. When an operation that names
or creates an entry **fails** with an untyped exception, the module classifies the failure as
`NameTooLong(path)` by the length of a segment of the operation's path (Windows: more than 255 UTF-16
code units; a macOS host: more than 255 code points; elsewhere: more than 255 UTF-8 bytes). It is a
classification of a failure that already happened, never a check, and it is never applied to a read,
write, sync or close of a file that is already open. A disk-full while writing a file inside a folder
with a long name stays `NoSpace`.

### Message text is the last resort, and it is localised

The JDK reports some failures (a full disk, a name that is too long, a cross-device link) as an untyped
`FileSystemException` whose message is the operating system's text, in the system language. The
mapping goes by exception **type** first (`NoSuchFileException`, `AccessDeniedException`, …), then by
file store (different stores → `CrossDevice`; a FAT-family store → `Unsupported` for `link`), then by
segment length (above), and only then by English message text ("No space left", "File name too
long", …). On a non-English Windows or Linux desktop a `NoSpace` can therefore arrive as
`FileError.Io` carrying the original exception as `cause`; treat `Io` as "failed, reason unknown" and
log `cause`. iOS and Android API 24–25 map by `errno` and do not depend on text.

ENOTDIR (a regular file where the path needs a directory) is one of the text-dependent cases on a JVM. A query
(`metadata`, `exists`) still answers `null`/`false`, but on a Linux desktop in another language `readBytes`,
`createDirectories`, `writeAtomically` and `move` can report `Io` where an English system reports `NotFound` or
`NotADirectory`. macOS, iOS and Android are not affected (unlocalized `strerror` or `errno`).

## Case-insensitive volumes and Unicode

No function folds case or normalizes Unicode except `PathKey.of(path, CaseFolding.Always)` (NFD first and NFC last: the
JDK's `java.text.Normalizer`, `decomposedStringWithCanonicalMapping` and `precomposedStringWithCanonicalMapping` on iOS; case mapping is Kotlin's
locale-independent `lowercase()`/`uppercase()` on every target). On a case-insensitive volume (APFS by
default, NTFS) the platform decides: `move("/d/File", "/d/file")` with `Replace` renames the spelling;
with `Fail` it is `AlreadyExists` there (the same entry) and a plain move on a case-sensitive volume.
Use `PathKey` for lock keys and in-memory maps so that those spellings share an entry. On iOS the
file system returns names in the form it stored; `list` does not normalize them.

## Hard links on FAT and exFAT

`ExistingFile.Fail` — in `writeAtomically` and `move` — is a hard link plus a removal. NTFS, ext4, F2FS
and APFS have hard links; FAT, exFAT and some network shares do not, and the call is
`FileError.Unsupported` with the target untouched and no temporary file left. The module recognises a
FAT-family store by file-store name (`fat`, `fat12`, `fat16`, `fat32`, `vfat`, `msdos`, `exfat`).

On **Android**, whether `link` is permitted inside `filesDir` and `noBackupFilesDir` depends on the
system's SELinux policy and `targetSdk`; this has not been verified on a device (see
[Owner checks](#owner-checks-device-only)). Until it is, do not build a flow that relies on
`ExistingFile.Fail` succeeding on Android; handle `Unsupported`.

## `F_FULLFSYNC` and directory sync

Only `Durability.SyncedWithDirectory` uses `F_FULLFSYNC` on iOS: `fcntl(fd, F_FULLFSYNC)` on the
temporary file before the rename and on a read-only descriptor of the parent directory after it; a `-1`
with `ENOTSUP` or `EINVAL` falls back to `fsync`. `Synced` is plain `fsync` — the downloader's choice
— because a full flush costs milliseconds per call and is the wrong default. A JVM on macOS cannot issue
`F_FULLFSYNC` (`FileChannel.force` is `fsync`); on that host `SyncedWithDirectory` is as strong as
the JDK allows. A directory created by the same call (`createParentDirectories`) is made durable too: after the rename the
parent of each level the call created is synced, deepest first, then the target's parent, so the first record in
a new directory survives power loss (a new directory's entry is durable only once its parent is synced; F2FS on
Android is the file system known to need it). An existing directory is assumed durable; `createDirectories(durable = true)` and `AppDirectories` sync the levels they create the same way (one `F_FULLFSYNC` on iOS), and a level lost to a concurrent creator counts as created. Windows skips the directory sync: NTFS journals the rename.

## Protection classes and backup flags (iOS)

`FileProtection` is accepted everywhere and applied on iOS before the first byte is written
(`NSFileProtectionKey`). The iOS **simulator** does not enforce data-protection classes, so tests
there cannot show locked-device behaviour. The backup flag is a property of a **directory entry's
inode**, and an atomic replace creates a new inode every time — a flag set on the file would be lost on
the next write. `AppDirectories.directory(..., Backup.Excluded)` therefore marks the *directory*
(`NSURLIsExcludedFromBackupKey`) and re-applies it on every call, and files replaced inside it stay
excluded. `AppRoot.Files` on iOS is Application Support, not Documents, which is user-visible when
file sharing is enabled. `freeSpaceBytes` on iOS reads `NSFileSystemFreeSize`, not "available capacity
for important usage" (which counts purgeable space and would move a threshold you set).

## Android API 24–25: the legacy path

Android uses `java.nio.file` where the runtime has it (devices from API 26, and host test runtimes such as
Robolectric at any SDK level) and `android.system.Os` otherwise (API 24–25 devices). The factory asks whether
`java.nio.file.Files` is loadable, not for `SDK_INT`. On the legacy path it uses `java.io.File` plus
`android.system.Os` (`open`, `fsync`, `rename`, `link`, `lstat`). The result is the same contract.
Two differences remain:

- **Modification times have 1 s granularity** there, so `sweep` may judge an entry up to 999 ms older
  than it is. Use ages in minutes, not milliseconds.
- **It cannot be exercised by a host test.** Under Robolectric `android.system.Os` is a stub at every
  API level (calls return without doing anything), so this path is covered by a canary test that fails
  the day Robolectric implements `Os`, plus a check of the ops selection, and by an owner device check.
  Robolectric's host runtime has `java.nio.file`, so a host test at SDK 24 runs the NIO ops (the contract
  run "API 24 host runtime (NIO selected)" of `kmptoolkit-files-testing`).

## Cross-process and concurrency notes

- `deleteRecursively` never follows a link present during its walk, but a concurrent replacement of a
  directory by a symbolic link while the walk is inside it (a link-swap race) is not defended against
  — the same limit as `rm -r`. Do not run it on a directory another process can write to with
  hostile intent.
- Locks (`KeyedMutex`, `PathLocks`) cover coroutines in one process. The atomic replace keeps a file
  whole for other processes but does not order two writers.
- A crash can leave a temporary file; `sweep` by `FileNames.isTempName` and an age.

## Owner checks (device only)

Not verifiable on a host; recorded for a person with a device:

1. **API 24–25 legacy ops** (`android.system.Os`): create, write, fsync, rename, link, directory fsync
   and the `errno` mapping on an API 24 or 25 device or emulator, and that the factory selects the legacy
   ops there (`java.nio.file.Files` not loadable) and the NIO ops on API 26 and newer. The selection is by
   NIO presence, not by `SDK_INT`; its premise (API 24-25 devices have a working `android.system.Os` and no
   `java.nio.file`) cannot be verified on a host.
2. **`link` inside `filesDir`/`noBackupFilesDir`** on API 24/25, 26, 30 and 35 with the current
   `targetSdk`. If it is denied, `ExistingFile.Fail` on Android will be documented as unsupported.
3. **Directory fsync on ART** (`FileChannel.open(dir, READ).force(true)`, API 26+) for
   `SyncedWithDirectory`.
4. **iOS**: the protection class while the device is locked, and `F_FULLFSYNC` on a device.
5. **FAT/exFAT** behaviour of the no-replace paths on Windows and on removable volumes on Android.
