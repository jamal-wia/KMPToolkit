# kmptoolkit-files — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.files` (and `.spi`, internal). All symbols
are `@since 5.0.0`. "Thread-safe" below means: callable from any thread, concurrently.

---

## `FileAccess`

```kotlin
public interface FileAccess {
    public fun metadata(path: String, followLinks: Boolean = false): FileMetadata?
    public fun list(directory: String): FileResult<List<String>>
    public fun createDirectories(path: String, durable: Boolean = false): FileResult<Unit>
    public fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray>
    public fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions = WriteOptions()): FileResult<Unit>
    public fun move(from: String, to: String, options: MoveOptions = MoveOptions(),
                    cancellation: CancellationCheck = CancellationCheck.Never): FileResult<Unit>
    public fun delete(path: String): FileResult<Unit>
    public fun deleteRecursively(path: String, cancellation: CancellationCheck = CancellationCheck.Never): FileResult<Unit>
    public fun treeSize(path: String, cancellation: CancellationCheck = CancellationCheck.Never): FileResult<Long>
    public fun sweep(directory: String, olderThanMillis: Long,
                     cancellation: CancellationCheck = CancellationCheck.Never,
                     match: (name: String) -> Boolean): FileResult<Int>
    public fun freeSpaceBytes(path: String): Long?

    public companion object { public const val MAX_READ_BYTES: Long = Int.MAX_VALUE.toLong() - 16 }
}

public fun FileAccess.exists(path: String): Boolean
```

Blocking file-system primitives for app-private files. Every function does blocking I/O: call it off
the main thread, for example inside `withIo`. **Thread-safe**: safe from any thread, concurrently, on
the same or different paths; the instance holds no open handle between calls and needs no release.
Nothing is serialized inside: concurrent writers of one path each succeed and the last rename wins;
use `PathLocks` or a `KeyedMutex` for read-modify-write cycles.

Failures are typed `FileError`s inside `FileResult`; no function throws except
`IllegalArgumentException` where stated. A relative, empty or NUL-containing path is
`FileError.InvalidPath` (a query answers `null`/`false`). Paths are passed to the platform as given:
no lexical normalization, no case folding.

| Member | Contract |
|---|---|
| `metadata` | The entry's `FileMetadata`, or `null` when nothing is there, the path is invalid, or it cannot be examined. With `followLinks = false` (default) a symbolic link reports `FileKind.SymbolicLink` and is never followed; with `true` it reports its target, and a dangling link is `null` |
| `list` | The entry names of `directory`, sorted by `String.compareTo`, without `.` and `..`. An absent directory is `Success(emptyList())`; a non-directory is `NotADirectory` |
| `createDirectories` | Creates the path and every missing parent. `Success` when it is already a directory, also when another thread or process creates it concurrently. `NotADirectory` when the path or a parent is a non-directory. With `durable`, every directory this call created, or found created by a concurrent caller while creating it, is synced into its parent before `Success` (iOS: one `F_FULLFSYNC`); directories that existed before are assumed durable. Without `durable` nothing is synced, so a power loss shortly after the call can lose the new directories. A sync failure is `Io`; the directories then exist but may not be durable |
| `readBytes` | The whole content (links followed), at most `maxBytes`. Larger — by its size before reading or by what reading found — is `TooLarge` and is not returned in part. Absent: `NotFound`; a directory: `IsADirectory`; a device, pipe or socket: `Unsupported` (never opened). **Throws** `IllegalArgumentException` when `maxBytes` is negative or above `MAX_READ_BYTES` |
| `writeAtomically` | Replaces the content with `bytes` atomically: a reader, another process, or the same app after a crash sees the previous content or the whole new content, never a mix, never an empty file in between. The bytes go to a new, uniquely named temporary file (see `FileNames.isTempName`) — in the path's directory or `WriteOptions.tempDirectory` — which is synced per `WriteOptions.durability` and then renamed over the path. Concurrent calls for one path never share a temporary file; the last rename wins. Writers and readers of one path through `FileAccess` in one process never make each other fail, on every platform; on Windows another process (or code outside `FileAccess`) holding the path open can make the replace fail with `AccessDenied` once the rename retry budget (about 210 ms) is used up — the path is then untouched and no temporary file is left. On every failure the temporary file is removed and the path untouched, except a failure of the final directory syncs of `SyncedWithDirectory` (`Io`), when the path already holds the new bytes. A crash can leave a temporary file; `sweep` it by `isTempName`. A symbolic link at the path is replaced by a regular file; its target is untouched. Errors: missing parent `NotFound` (unless `createParentDirectories`); a directory at the path `IsADirectory`; `ExistingFile.Fail` with something there `AlreadyExists`; temp directory on another volume `CrossDevice`; no hard links with `Fail` `Unsupported` |
| `move` | Moves the entry (links move as links). Within a volume: one atomic rename. `Replace` atomically replaces a non-directory at `to`; `Fail` never replaces anything (`AlreadyExists`), using a hard link plus removal of `from`. Across volumes, when `acrossVolumes` is set and `from` is a regular file: copied to a temporary file next to `to`, synced, renamed (or linked) into place, then `from` is removed; otherwise `CrossDevice`. Once `to` holds the bytes the result is `Success` even if removing `from` failed. A cancelled copy removes its temporary file and leaves both names as they were (`Cancelled`). Errors: `from` absent `NotFound`; a directory at `to` `IsADirectory`; a directory `from` across volumes `CrossDevice`; a directory or symbolic link `from` with `Fail` `Unsupported` |
| `delete` | Removes the file, the symbolic link (never its target) or the empty directory. Absent is `Success`. A non-empty directory is `DirectoryNotEmpty` |
| `deleteRecursively` | Removes the path and everything under it without following any symbolic link. An entry that disappears during the walk counts as removed, so concurrent deletes of one tree all succeed. Keeps going past entries it cannot remove, then reports the first error; `Success` means nothing is left. Checks `cancellation` before every entry and stops with `Cancelled`, leaving the rest in place. Iterative: no depth limit. Links present during the walk are never followed; a concurrent replacement of a directory by a link while the walk is inside it is not defended against |
| `treeSize` | The total size of the regular files at or under the path, links not followed (a link counts 0). Absent is `Success(0)`. Entries that vanish or cannot be read count 0. Fails only with `Cancelled` or `InvalidPath` |
| `sweep` | Deletes the regular files and symbolic links directly in `directory` whose name satisfies `match` and whose last modification is **more than** `olderThanMillis` before now (entries without a known time, and entries dated in the future, are kept). Never recurses, never deletes a directory. Returns how many were deleted; an absent directory is `Success(0)`. Exceptions thrown by `match` propagate. **Throws** `IllegalArgumentException` when `olderThanMillis` is negative |
| `freeSpaceBytes` | Bytes available to this app on the volume that holds the path, measured at the path or, when it does not exist yet, at its nearest existing ancestor. `null` when no ancestor exists or the platform cannot tell |
| `exists` (extension) | `metadata(path, followLinks = true) != null`; never throws. A dangling link does not exist |

`cancellation` is checked by `deleteRecursively`, `treeSize` and `sweep` before every entry, and by a
cross-volume `move` before every 64 KiB chunk. Single-file operations are short and never check it.

---

## `FileResult<T>`

```kotlin
public sealed interface FileResult<out T> {
    public data class Success<out T>(public val value: T) : FileResult<T>
    public data class Failure(public val error: FileError) : FileResult<Nothing>
}
public fun <T> FileResult<T>.getOrNull(): T?
public fun <T> FileResult<T>.errorOrNull(): FileError?
public val FileResult<*>.isSuccess: Boolean
```

The same shape as `StorageResult`. There is deliberately no `getOrThrow`: the cases are expected
outcomes. A `Failure` has had no effect beyond what the function's contract documents.

## `FileError`

```kotlin
public sealed interface FileError {
    public val path: String?
    public data class NotFound(override val path: String) : FileError
    public data class AlreadyExists(override val path: String) : FileError
    public data class NotADirectory(override val path: String) : FileError
    public data class IsADirectory(override val path: String) : FileError
    public data class DirectoryNotEmpty(override val path: String) : FileError
    public data class AccessDenied(override val path: String) : FileError
    public data class NoSpace(override val path: String) : FileError
    public data class NameTooLong(override val path: String) : FileError
    public data class CrossDevice(override val path: String) : FileError
    public data class TooLarge(override val path: String, val sizeBytes: Long, val maxBytes: Long) : FileError
    public data class Unsupported(override val path: String) : FileError
    public data class InvalidPath(override val path: String) : FileError
    public data object Cancelled : FileError            // path is null
    public data class Io(override val path: String?, val platformCode: Int? = null, val cause: Throwable? = null) : FileError
}
```

Never carries display text; `path` is the path the operation was working on (the caller's path, not
a temporary file's). `AccessDenied` covers `EACCES`, `EPERM`, `EROFS` and a Windows access-denied after
the retries; `NoSpace` is `ENOSPC` or `EDQUOT`; `CrossDevice` is `EXDEV` or the JVM refusing an atomic
move; `Unsupported` is a file system or platform that cannot do this (no hard links; backup exclusion
on Android); `InvalidPath` is relative, empty, NUL-containing, or (Windows) a reserved device name or
a segment ending in `.` or space; `Io.platformCode` is the `errno`/Win32 code when known.

## Values

```kotlin
public data class FileMetadata(val kind: FileKind, val sizeBytes: Long, val lastModifiedMillis: Long?)
public enum class FileKind { RegularFile, Directory, SymbolicLink, Other }
```

`sizeBytes` is the length for `RegularFile` and `0` for every other kind. `lastModifiedMillis` is epoch
milliseconds, or `null` when the platform cannot report it.

```kotlin
public data class WriteOptions(
    val durability: Durability = Durability.Synced,
    val existing: ExistingFile = ExistingFile.Replace,
    val protection: FileProtection = FileProtection.Default,
    val createParentDirectories: Boolean = false,
    val tempDirectory: String? = null,
    val tempPrefix: String? = null,
)
public enum class Durability { Buffered, Synced, SyncedWithDirectory }
public enum class ExistingFile { Replace, Fail }
public enum class FileProtection { Default, None, Complete, CompleteUnlessOpen, CompleteUntilFirstUserAuthentication }
public data class MoveOptions(
    val existing: ExistingFile = ExistingFile.Replace,
    val acrossVolumes: Boolean = true,
    val createParentDirectories: Boolean = false,
)
```

| Member | Meaning |
|---|---|
| `Durability.Buffered` | No sync: atomic against a process crash, not against power loss (the new name may point at an empty file) |
| `Durability.Synced` | The file is synced before the rename: after power loss the path holds old or new content, never a torn file |
| `Durability.SyncedWithDirectory` | Synced, plus the directory entry after the rename (iOS: `F_FULLFSYNC`): once `Success`, the new content survives power loss, provided the directories on the path that existed before the call are durable (created with `createDirectories(durable = true)`, by `AppDirectories`, by an earlier `SyncedWithDirectory` write, or present long enough for the system to have written them back) |
| `FileProtection` | iOS data-protection class of the new file; ignored on Android and the JVM |
| `createParentDirectories` | Creates the parent of the path (and `tempDirectory`) first |
| `tempDirectory` | Where the temporary file goes; must be on the same volume as the path. `null` = the path's own directory |
| `tempPrefix` | Prefix of the temporary file's name; `null` = `FileAccessConfig.tempPrefix`. Same rules as that; `IllegalArgumentException` from the constructor otherwise |

```kotlin
public data class FileAccessConfig(val tempPrefix: String = DEFAULT_TEMP_PREFIX) {
    public companion object { public const val DEFAULT_TEMP_PREFIX: String = ".kmptoolkit-" }
}
```

`tempPrefix` starts the name of every temporary file this instance creates (`<prefix><digits>.tmp`).
1..232 UTF-8 bytes (`FileAccessConfig.MAX_TEMP_PREFIX_BYTES`: 255 minus 19 digits minus `.tmp`, so every temporary name fits 255 bytes), and `"<prefix>0.tmp"` must be a `FileNames.isPlainName` on the host, else
`IllegalArgumentException`. A parameter, not a constant, per the suite's "no hardcoded consumer
identifier" rule.

## `CancellationCheck` and `withIo`

```kotlin
public fun interface CancellationCheck {
    public fun isCancelled(): Boolean
    public companion object { public val Never: CancellationCheck }
}

public suspend fun <T> FileAccess.withIo(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: FileAccess.(cancellation: CancellationCheck) -> T,
): T
```

`CancellationCheck` is a plain `Boolean` check rather than a throwing one, so a non-coroutine caller
gets a typed `Failure(Cancelled)`. `withIo` runs `block` on `dispatcher` with a check that turns
`true` once the calling coroutine is cancelled; a cancelled caller gets `CancellationException` (the
block's `Failure(Cancelled)` is never returned to it).

## Locks

```kotlin
public class KeyedMutex<K : Any> {
    public suspend fun <T> withLock(key: K, action: suspend () -> T): T
    public fun tryWithLock(key: K, action: () -> Unit): Boolean
    public val activeKeyCount: Int
}

public object PathLocks {
    public suspend fun <T> withLock(path: String, action: suspend () -> T): T
    public fun tryWithLock(path: String, action: () -> Unit): Boolean
}
```

`KeyedMutex`: one coroutine `Mutex` per key, held in a registry only while some caller holds or awaits
it, so the registry does not grow with the number of keys ever used. Keys are compared with
`equals`/`hashCode` and must be immutable. **Not reentrant**: a `withLock` nested in a `withLock` of the
same key deadlocks. Safe from any thread on every platform.

| Member | Contract |
|---|---|
| `withLock` | Runs `action` while no other `withLock` or `tryWithLock` of `key` runs. Callers of one key run one at a time; callers of different keys never wait for each other. The key is released when `action` returns, throws, or the caller is cancelled while waiting or running |
| `tryWithLock` | Runs `action` only if no caller holds **or awaits** `key` right now, and returns whether it ran. Never suspends, never waits; other keys' callers never make it fail. A `withLock` of `key` that arrives while `action` runs waits for it. `action` should be short and must not suspend |
| `activeKeyCount` | How many keys are held or awaited right now. `0` when idle: the leak check for tests |

`PathLocks`: a process-wide `KeyedMutex` over `PathKey.of(path, CaseFolding.Always)`, for
read-modify-write cycles of one file across unrelated parts of an app. Library modules never use it
(they keep private `KeyedMutex` instances), so holding it can never deadlock against a toolkit call.

## Paths, names, keys

```kotlin
public object Paths {
    public fun isAbsolute(path: String): Boolean
    public fun join(parent: String, child: String): String
    public fun parentOf(path: String): String?
    public fun normalizeLexically(path: String): String
    public fun resolveInside(base: String, relative: String): String?
}
```

Lexical path rules of the host: POSIX on Android, iOS and Unix JVMs; Windows rules on a Windows JVM.
No file I/O; thread-safe.

| Member | Contract |
|---|---|
| `isAbsolute` | POSIX: starts with `/`. Windows: `C:\`, `C:/`, `\\server\share`, `//server/share`; `\x` and `C:x` are not. Empty: `false` |
| `join` | `parent` + one host separator + `child`, without doubling a separator at the joint. **Throws** `IllegalArgumentException` if `child` is empty or absolute |
| `parentOf` | The path without its last segment (trailing separators ignored); `null` for a root, an empty path, or a single relative segment |
| `normalizeLexically` | Resolves `.`, `..` and repeated separators and drops a trailing separator. `..` above a root stays at the root; leading `..` of a relative path are kept; an empty result is `.`. Windows: accepts both separators, writes `\`. Does not know about symbolic links |
| `resolveInside` | `normalizeLexically(join(base, relative))` when that stays inside `base`, else `null` (absolute, NUL, or escaping `relative`) |

```kotlin
public object FileNames {
    public const val MAX_NAME_BYTES: Int = 255
    public fun isPlainName(name: String): Boolean
    public fun requirePlainName(name: String, parameterName: String = "name"): String
    public fun isWindowsReserved(name: String): Boolean
    public fun isTempName(name: String, prefix: String = FileAccessConfig.DEFAULT_TEMP_PREFIX): Boolean
}
```

| Member | Contract |
|---|---|
| `MAX_NAME_BYTES` | The portable name limit `isPlainName` applies on every host: 255 UTF-8 bytes, the limit of ext4 and F2FS (Android) and of most Linux file systems. APFS counts 255 characters and NTFS 255 UTF-16 code units, so a longer UTF-8 name can still be valid there; `FileAccess` never pre-rejects such a name |
| `isPlainName` | Whether `name` can be one directory entry on this host: not empty, not `.` or `..`, no `/`, no NUL, no unpaired surrogate, at most `MAX_NAME_BYTES` UTF-8 bytes; on Windows also no `\`, no `<>:"\|?*`, no U+0001..U+001F, no trailing `.` or space, and not `isWindowsReserved` |
| `requirePlainName` | Returns `name` when `isPlainName`, else throws `IllegalArgumentException` naming `parameterName` |
| `isWindowsReserved` | Whether the part of `name` before its first `.`, without trailing spaces, is, ignoring case, `CON`, `PRN`, `AUX`, `NUL`, `COM0`–`COM9` or `LPT0`–`LPT9` (also with `¹²³`). Host independent. `CON .txt` and `NUL ` are reserved: Windows opens the device |
| `isTempName` | Whether `name` is `<prefix><1..19 ASCII digits>.tmp`: a temporary file of `writeAtomically` or `move` with that prefix |

```kotlin
public enum class CaseFolding { Never, Always }
public object PathKey { public fun of(path: String, folding: CaseFolding): String }
public object SqliteFiles { public fun sidecarsOf(databasePath: String): List<String> }
```

`PathKey.of`: a key under which spellings of one file compare equal — `normalizeLexically(path)`, then
for `CaseFolding.Always` NFC, lowercase, uppercase, lowercase, NFC (locale-independent), so `ß`/`ẞ`/`ss`,
the sigma forms, `ſ`/`s`, the Kelvin sign and `k`, and NFC/NFD spellings share a key on every platform.
Over-folding only costs parallelism. Lexical: canonicalize first if symbolic links matter. For
in-memory comparison only; never persist it. `CaseFolding.Never` keeps case.

`SqliteFiles.sidecarsOf`: `databasePath` + `-wal`, `-shm`, `-journal`, in that order.

## App directories

```kotlin
public interface AppDirectories {
    public fun rootPath(root: AppRoot): String
    public fun directory(root: AppRoot, name: String, backup: Backup = Backup.Included): FileResult<String>
    public fun excludeFromBackup(path: String): FileResult<Unit>
}
public enum class AppRoot { Files, NoBackup, Cache, Temporary }
public enum class Backup { Included, Excluded }
```

| Member | Contract |
|---|---|
| `rootPath` | The absolute path of the root. Does not create it |
| `directory` | Creates `<root>/<name>` (and the root) if missing and returns its path; idempotent and safe from many threads. `Backup.Excluded` marks the **directory** (iOS `NSURLIsExcludedFromBackupKey`, re-applied on every call), so files replaced atomically inside it stay excluded. `NoBackup` is always excluded; `Cache` and `Temporary` are never backed up, so `Excluded` is a no-op there. Android `Files` + `Excluded` is `Unsupported` and creates nothing. The desktop JVM has no backup service: `Excluded` is a no-op. **Throws** `IllegalArgumentException` when `name` is not a plain file name |
| `excludeFromBackup` | iOS: excludes the existing entry at the path from backup (`NotFound` if absent). Android: `Unsupported`. JVM: no-op `Success` |

Platform factories, declared per platform with the same shape (shared code takes the interfaces):

```kotlin
// androidMain
public fun createFileAccess(config: FileAccessConfig = FileAccessConfig()): FileAccess
public fun createAppDirectories(context: Context, files: FileAccess = createFileAccess()): AppDirectories
// iosMain
public fun createFileAccess(config: FileAccessConfig = FileAccessConfig()): FileAccess
public fun createAppDirectories(files: FileAccess = createFileAccess()): AppDirectories
// jvmMain (IllegalArgumentException when a directory is not absolute)
public fun createFileAccess(config: FileAccessConfig = FileAccessConfig()): FileAccess
public fun createAppDirectories(
    filesDirectory: String,
    cacheDirectory: String = filesDirectory,
    temporaryDirectory: String = System.getProperty("java.io.tmpdir"),
    files: FileAccess = createFileAccess(),
): AppDirectories
```

| Root | Android | iOS | JVM |
|---|---|---|---|
| `Files` | `filesDir` | Application Support | `filesDirectory` |
| `NoBackup` | `noBackupFilesDir` | Application Support, directory excluded | `filesDirectory` |
| `Cache` | `cacheDir` | `Caches` | `cacheDirectory` |
| `Temporary` | `cacheDir` | `NSTemporaryDirectory()` | `temporaryDirectory` |

## The internal SPI (`io.github.jamal_wia.kmptoolkit.files.spi`)

`FileSystemOps`, `WritableFile`, `ReadableFile`, `FileOpException` and `fileAccessOver(ops, config)` are
`public` and `@ToolkitInternalApi`: they let `kmptoolkit-files-testing` run the real engine over an
in-memory file system. They are **not** part of the contract, may change in any release, and need an
explicit `@OptIn(ToolkitInternalApi::class)`. Do not implement `FileSystemOps` in application code.
