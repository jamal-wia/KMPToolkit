@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileAccessConfig
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver

/**
 * An in-memory [FileAccess] that runs the real `kmptoolkit-files` engine, so every contract of [FileAccess] holds
 * exactly as on a device: the algorithms (atomic replace, no-replace move, cross-volume copy, recursive delete,
 * sweep, free space) are the production code, and only the file system underneath is simulated. POSIX path syntax on
 * every host: build its paths as strings (`"/data/x"`), not with `Paths` or `java.io.File`. Thread-safe.
 *
 * It models inodes (a rename carries content and sync state; hard links share them), symbolic links, per-file
 * synced content and per-directory synced entries, so a power loss can be simulated: see [crashAfter] and
 * [restartAfterCrash]. Names longer than 255 UTF-8 bytes are refused with [FileError.NameTooLong], as on ext4.
 *
 * @param caseSensitive `false` models a case-insensitive volume (APFS default, NTFS): `/d/A` and `/d/a` are one
 *   entry, the spelling of its creation is kept and listed, and Unicode spellings of one name are folded as
 *   `PathKey.of(name, CaseFolding.Always)` folds them.
 * @param config the [FileAccessConfig] of the engine, for example a custom temporary-file prefix.
 * @since 5.0.0
 */
public class FakeFileAccess private constructor(
    private val ops: InMemoryFileSystemOps,
    private val caseSensitive: Boolean,
    private val config: FileAccessConfig,
    private val engine: FileAccess,
) : FileAccess by engine {

    public constructor(
        caseSensitive: Boolean = true,
        config: FileAccessConfig = FileAccessConfig(),
    ) : this(InMemoryFileSystemOps(caseSensitive), caseSensitive, config)

    private constructor(
        ops: InMemoryFileSystemOps,
        caseSensitive: Boolean,
        config: FileAccessConfig,
    ) : this(ops, caseSensitive, config, fileAccessOver(ops, config))

    /**
     * The wall clock of this file system in epoch milliseconds; starts at 0. Modification times come from it and
     * so does the "now" of [sweep]: move it to age files.
     */
    public var nowMillis: Long
        get() = ops.nowMillisValue
        set(value) {
            ops.nowMillisValue = value
        }

    /**
     * Bytes the volume reports as free, or `null` for unknown (the default: unlimited). A write that does not fit
     * fails with [FileError.NoSpace] and changes nothing; a write that fits uses the space up, and removing the last
     * name of a file gives its bytes back. Seeding does not use space.
     */
    public var freeSpaceBytes: Long?
        get() = ops.freeSpace()
        set(value) {
            ops.setFreeSpace(value)
        }

    /** `false` makes every hard link fail with [FileError.Unsupported], as on FAT and exFAT. Default `true`. */
    public var hardLinksSupported: Boolean
        get() = ops.hardLinksSupported
        set(value) {
            ops.hardLinksSupported = value
        }

    /**
     * Runs before every low-level operation (also those of other threads), on the calling thread, before any
     * injected fault applies: delete a sibling, flip a flag, record a call. The path is the one the record of the
     * operation carries ([FakeOperationRecord.path]). Like any failure of the file system, an [Exception] it throws is
     * reported by the engine as [FileError.Io] carrying it as `cause`; an [Error] propagates to the caller.
     */
    public var beforeOperation: ((FakeOperation, path: String) -> Unit)?
        get() = ops.beforeOperation
        set(value) {
            ops.beforeOperation = value
        }

    // ---- seeding ----------------------------------------------------------------------------------

    /**
     * Creates the file at [path] with [bytes], creating every missing parent directory and replacing a file already
     * there; its modification time is [nowMillis]. Seeded entries count as durable.
     * @throws IllegalArgumentException when [path] is not absolute, or a directory is at [path] or in the way.
     */
    public fun createFile(path: String, bytes: ByteArray = ByteArray(0)) {
        ops.seedFile(path, bytes)
    }

    /**
     * Creates the directory at [path] and every missing parent; nothing happens if it exists. Counts as durable.
     * @throws IllegalArgumentException when [path] is not absolute or a file is in the way.
     */
    public fun createDirectory(path: String) {
        ops.seedDirectory(path)
    }

    /**
     * Creates a symbolic link at [path] pointing at [target] (absolute, or relative to the link's directory; it need
     * not exist), creating missing parents. Counts as durable.
     * @throws IllegalArgumentException when [path] is not absolute or already exists.
     */
    public fun createSymbolicLink(path: String, target: String) {
        ops.seedSymbolicLink(path, target)
    }

    /**
     * Sets the modification time of the entry at [path] (a link itself, not its target) to [millis].
     * @throws IllegalArgumentException when [path] does not exist.
     */
    public fun setLastModified(path: String, millis: Long) {
        ops.seedLastModified(path, millis)
    }

    /**
     * Makes the current state the durable baseline: every file keeps its content and every directory its entries
     * through a [CrashKind.PowerLoss]. Call it after seeding through [FileAccess] calls instead of the `create*`
     * functions.
     */
    public fun markAllDurable() {
        ops.markAllDurable()
    }

    // ---- fault injection --------------------------------------------------------------------------

    /**
     * The next [count] low-level [operation]s whose path satisfies [path] fail with [error] and have no effect.
     * The path is the one of [FakeOperationRecord.path]. Faults are consulted in the order they were added.
     */
    public fun failNext(
        operation: FakeOperation,
        error: FileError,
        count: Int = 1,
        path: (String) -> Boolean = { true },
    ) {
        require(count > 0) { "count must be positive: $count" }
        ops.addFault(operation, error, count, path)
    }

    /**
     * After the [occurrence]-th successful [operation] counted from this call, the fake crashes: its effect is
     * kept, the state is frozen, [SimulatedCrash] is thrown from that call, and every later call throws it too
     * (cleanup code of the interrupted operation therefore never runs, as in a dead process). Use
     * [restartAfterCrash] to continue.
     */
    public fun crashAfter(operation: FakeOperation, occurrence: Int = 1) {
        require(occurrence > 0) { "occurrence must be positive: $occurrence" }
        ops.armCrash(operation, occurrence)
    }

    /**
     * A new fake holding the state that survived the crash, with no faults, no crash and an empty [operations] log;
     * [nowMillis], [freeSpaceBytes] and [hardLinksSupported] carry over.
     *
     * [CrashKind.ProcessDeath] keeps everything. [CrashKind.PowerLoss] truncates every file to the content of its
     * last sync (a file never synced is empty) and, if [revertUnsyncedDirectoryEntries], restores every directory's
     * entries to its last `syncDirectory` or seeding, so a new or renamed name that was never made durable is gone.
     * @throws IllegalStateException when no crash happened.
     */
    public fun restartAfterCrash(
        kind: CrashKind = CrashKind.ProcessDeath,
        revertUnsyncedDirectoryEntries: Boolean = false,
    ): FakeFileAccess {
        val next: InMemoryFileSystemOps = ops.restarted(
            powerLoss = kind == CrashKind.PowerLoss,
            revertDirectories = revertUnsyncedDirectoryEntries,
        )
        return FakeFileAccess(next, caseSensitive, config)
    }

    // ---- inspection -------------------------------------------------------------------------------

    /** Every low-level operation attempted, in order, including failed ones and those that raised a crash. */
    public val operations: List<FakeOperationRecord>
        get() = ops.operations()

    /**
     * Every entry below the root by absolute path, directories before their children, each name of a hard-linked
     * file listed with the same content. Symbolic links are not followed. Works after a crash.
     */
    public fun entries(): Map<String, FakeEntry> = ops.snapshotEntries()
}

/**
 * The low-level operation a [FakeFileAccess] hook or record refers to: the calls the engine makes on the simulated
 * file system.
 *
 * @since 5.0.0
 */
public enum class FakeOperation {
    /** A stat of a path. */
    Metadata,

    /** A directory listing. */
    List,

    /** Creating one directory level. */
    CreateDirectory,

    /** Creating a new file exclusively (the temporary file of a write or copy). */
    CreateNew,

    /** Opening a file for reading. */
    OpenForRead,

    /** One write call on a file being created. */
    Write,

    /** Making the content of a file being created durable. */
    Sync,

    /** Closing a file being created. */
    Close,

    /** An atomic rename. */
    Rename,

    /** Creating a hard link. */
    Link,

    /** Removing a file, a link or an empty directory. */
    Remove,

    /** Making the entries of a directory durable. */
    SyncDirectory,

    /** Asking for the free space of a volume. */
    UsableSpace,
}

/**
 * How a simulated crash treats what was not made durable.
 *
 * @since 5.0.0
 */
public enum class CrashKind {
    /** The process dies; the operating system keeps every write and every name, synced or not. */
    ProcessDeath,

    /** The power fails: files keep only synced content and, optionally, directories only synced entries. */
    PowerLoss,
}

/**
 * One low-level operation a [FakeFileAccess] was asked to perform.
 *
 * @property path the path the operation names. For [FakeOperation.Rename] the source; for [FakeOperation.Link] the new
 *   name; for the operations on an open file ([FakeOperation.Write], [FakeOperation.Sync], [FakeOperation.Close]) the
 *   path it was created at.
 * @property secondPath the destination of a rename and the existing file of a link; `null` for every other operation.
 * @since 5.0.0
 */
public data class FakeOperationRecord(
    public val operation: FakeOperation,
    public val path: String,
    public val secondPath: String?,
)

/**
 * An entry of a [FakeFileAccess] as [FakeFileAccess.entries] reports it.
 *
 * @since 5.0.0
 */
public sealed interface FakeEntry {
    /** A regular file and its content (compared by content). */
    public class File(bytes: ByteArray) : FakeEntry {
        private val content: ByteArray = bytes.copyOf()

        /** A copy of the content. */
        public val bytes: ByteArray get() = content.copyOf()

        override fun equals(other: Any?): Boolean = other is File && other.content.contentEquals(content)

        override fun hashCode(): Int = content.contentHashCode()

        override fun toString(): String = "File(${content.size} bytes)"
    }

    /** A directory. */
    public data object Directory : FakeEntry

    /** A symbolic link and its target text, as given when it was created. */
    public data class SymbolicLink(public val target: String) : FakeEntry
}

/**
 * Thrown by a crashed [FakeFileAccess], from the call that crashed and from every later call. An [Error], so the
 * engine's handling of exceptions cannot swallow it and its cleanup does not run, as after a real process death.
 *
 * @since 5.0.0
 */
public class SimulatedCrash : Error("The simulated file system crashed")
