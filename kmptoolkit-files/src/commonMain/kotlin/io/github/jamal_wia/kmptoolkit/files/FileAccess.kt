package io.github.jamal_wia.kmptoolkit.files

/**
 * Blocking file-system primitives for app-private files, identical on Android, iOS and the desktop JVM.
 *
 * Every function does blocking I/O: call it off the main thread, for example inside [withIo]. Every function is
 * safe to call from any thread, concurrently, on the same or different paths; the instance holds no open handle
 * between calls and needs no release. Nothing is serialized inside: concurrent writers of one path each succeed and
 * the last rename wins; use [PathLocks] or a [KeyedMutex] for read-modify-write cycles.
 *
 * Failures are typed [FileError]s inside [FileResult]; no function throws except [IllegalArgumentException] where
 * stated. A relative, empty or NUL-containing path is [FileError.InvalidPath] (a query answers `null`/`false`).
 * Paths are passed to the platform as given: no lexical normalization, no case folding.
 *
 * @since 5.0.0
 */
public interface FileAccess {

    /**
     * Metadata of the entry at [path], or `null` when nothing is there, the path is invalid, or it cannot be examined.
     * With [followLinks] `false` (the default) a symbolic link reports [FileKind.SymbolicLink] and is never followed;
     * with `true` it reports its target, and a dangling link is `null`.
     */
    public fun metadata(path: String, followLinks: Boolean = false): FileMetadata?

    /**
     * The names of the entries of [directory], sorted by [String.compareTo], without `.` and `..`.
     * An absent directory is `Success(emptyList())`; a non-directory is [FileError.NotADirectory].
     */
    public fun list(directory: String): FileResult<List<String>>

    /**
     * Creates [path] and every missing parent. Succeeds when [path] is already a directory, also when another thread
     * or process creates it concurrently. [FileError.NotADirectory] when [path] or a parent is a non-directory.
     * Does not make the new directories durable against power loss; [writeAtomically] with
     * [Durability.SyncedWithDirectory] does.
     */
    public fun createDirectories(path: String): FileResult<Unit>

    /**
     * The whole content of the file at [path] (links followed), at most [maxBytes] bytes.
     * A file larger than [maxBytes], judged by its size before reading or by what reading found, is
     * [FileError.TooLarge] and is not returned in part. Absent: [FileError.NotFound]; a directory:
     * [FileError.IsADirectory]; a device, pipe or socket: [FileError.Unsupported] (it is never opened).
     * @throws IllegalArgumentException when [maxBytes] is negative or above [MAX_READ_BYTES].
     */
    public fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray>

    /**
     * Replaces the content of [path] with [bytes] atomically: a reader, another process, or the same app after a crash
     * sees the previous content or the whole new content, never a mix, never an empty file in between.
     * The bytes go to a new uniquely named temporary file first (see [FileNames.isTempName]) - in [path]'s directory or
     * in [WriteOptions.tempDirectory] - which is synced per [WriteOptions.durability] and then renamed over [path].
     * Concurrent calls for one path never share a temporary file; the last rename wins. Writers and readers of one
     * path that go through FileAccess in this process never make each other fail, on every platform. On Windows,
     * another process or code outside FileAccess holding [path] open can make the replace fail with
     * [FileError.AccessDenied] once the rename retry budget (about 210 ms, see 05-platform-notes) is used up; [path]
     * is then untouched and no temporary file is left.
     * On every [FileResult.Failure] the temporary file is removed and [path] is untouched, except a failure of the
     * final directory syncs of [Durability.SyncedWithDirectory] (reported as [FileError.Io]), when [path] already holds
     * the new bytes. A crash can leave a temporary file behind; [sweep] it by [FileNames.isTempName].
     * A symbolic link at [path] is replaced by a regular file; its target is untouched.
     * Errors: missing parent [FileError.NotFound] (unless [WriteOptions.createParentDirectories]); a directory at
     * [path] [FileError.IsADirectory]; [ExistingFile.Fail] and something at [path] [FileError.AlreadyExists];
     * temp directory on another volume [FileError.CrossDevice]; a file system without hard links and
     * [ExistingFile.Fail] [FileError.Unsupported].
     */
    public fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions = WriteOptions()): FileResult<Unit>

    /**
     * Moves the entry at [from] to [to] (links are moved as links). Within one volume this is one atomic rename.
     * [ExistingFile.Replace] atomically replaces a non-directory at [to]; [ExistingFile.Fail] never replaces anything
     * ([FileError.AlreadyExists]), using a hard link plus removal of [from]. Across volumes, when
     * [MoveOptions.acrossVolumes] is set and [from] is a regular file, the bytes are copied to a temporary file next
     * to [to], synced, renamed (or linked) into place, and only then is [from] removed; otherwise
     * [FileError.CrossDevice]. Once [to] holds the bytes the result is Success even if removing [from] failed.
     * A cancelled copy removes its temporary file and leaves [from] and [to] as they were ([FileError.Cancelled]).
     * Errors: [from] absent [FileError.NotFound]; a directory at [to] [FileError.IsADirectory]; a directory [from]
     * across volumes [FileError.CrossDevice]; a directory or symbolic link [from] with [ExistingFile.Fail] [FileError.Unsupported] (a hard link
     * would follow the link on some platforms, and no atomic no-replace rename of a directory exists).
     */
    public fun move(
        from: String,
        to: String,
        options: MoveOptions = MoveOptions(),
        cancellation: CancellationCheck = CancellationCheck.Never,
    ): FileResult<Unit>

    /**
     * Removes the file, the symbolic link (never its target) or the empty directory at [path].
     * Absent is Success. A non-empty directory is [FileError.DirectoryNotEmpty].
     */
    public fun delete(path: String): FileResult<Unit>

    /**
     * Removes [path] and everything under it without following any symbolic link. An entry that disappears during
     * the walk counts as removed, so concurrent deletes of one tree all succeed. Keeps going past entries it cannot
     * remove and then reports the first error; Success means nothing is left at [path]. Checks [cancellation] before
     * every entry and stops with [FileError.Cancelled], leaving the rest in place. Iterative: no depth limit.
     * Links present during the walk are never followed; a concurrent replacement of a directory by a link while the
     * walk is inside it is not defended against.
     */
    public fun deleteRecursively(path: String, cancellation: CancellationCheck = CancellationCheck.Never): FileResult<Unit>

    /**
     * The total size of the regular files at or under [path], without following symbolic links (a link counts 0).
     * Absent is `Success(0)`. Entries that vanish or cannot be read count 0. Fails only with [FileError.Cancelled]
     * or [FileError.InvalidPath].
     */
    public fun treeSize(path: String, cancellation: CancellationCheck = CancellationCheck.Never): FileResult<Long>

    /**
     * Deletes the regular files and symbolic links directly in [directory] whose name satisfies [match] and whose
     * last modification is more than [olderThanMillis] before now (entries without a known time, and entries dated
     * in the future, are kept). Never recurses, never deletes a directory. Returns how many were deleted; an absent
     * directory is `Success(0)`. Exceptions thrown by [match] propagate.
     * @throws IllegalArgumentException when [olderThanMillis] is negative.
     */
    public fun sweep(
        directory: String,
        olderThanMillis: Long,
        cancellation: CancellationCheck = CancellationCheck.Never,
        match: (name: String) -> Boolean,
    ): FileResult<Int>

    /**
     * Bytes available to this app on the volume that holds [path], measured at [path] or, when it does not exist yet,
     * at its nearest existing ancestor. `null` when no ancestor exists or the platform cannot tell.
     */
    public fun freeSpaceBytes(path: String): Long?

    public companion object {
        /** The largest `maxBytes` [readBytes] accepts: `Int.MAX_VALUE - 16`. */
        public const val MAX_READ_BYTES: Long = Int.MAX_VALUE.toLong() - 16
    }
}

/**
 * Whether anything exists at [path], links followed: `metadata(path, followLinks = true) != null`. Never throws.
 *
 * @since 5.0.0
 */
public fun FileAccess.exists(path: String): Boolean = metadata(path, followLinks = true) != null
