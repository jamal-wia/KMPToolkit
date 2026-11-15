package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.KeyedMutex
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.jvmStorageLockKey
import io.github.jamal_wia.kmptoolkit.storage.temporaryPrefixOf
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeoutException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException

/** How [EncryptedFileGuard.createFile] puts the first (empty, sealed) file of a new key in place (plan 4.4 "Create"). */
internal sealed interface CreateMode {

    /** No file: create it with no-replace semantics. */
    data object Fresh : CreateMode

    /** An unusable file is moved to quarantine first; a failed create renames it back. */
    data object AfterQuarantine : CreateMode

    /** A file with zero entries and a lost key: replaced in place, [previous] kept for rollback. */
    class ReplaceEmpty(val previous: ByteArray) : CreateMode
}

/** How a create ended. */
internal sealed interface CreateResult {
    /** The empty file and, where the store has one, the key item are in place. */
    data object Created : CreateResult

    /** Another process created the file first: abandon the key and read the winner. */
    data object Raced : CreateResult

    /** The create failed; everything it did was rolled back. */
    class Failed(val error: StorageError) : CreateResult
}

/**
 * The file-level half of an encrypted desktop store (both the OS-key store and the app-key store): the in-process lock,
 * the cross-process lock under ONE 10 s budget (plan 4.11, same as the plain store), the whole-file read and classify,
 * the atomic owner-only write, quarantine, the sweep of leftover temporary files, and the create-with-rollback of
 * plan 4.4. It knows nothing about keys.
 *
 * - **Every operation takes both locks**, reads included (plan 4.4: the header is never read while a key is being
 *   created). The in-process lock is the timed `KeyedMutex` of `kmptoolkit-files`, then [CrossProcessLock]; the budget
 *   marked at the start of [locked] covers both waits, and only the waits are timed, never the block.
 * - **A file system that refuses file locks fails closed**: [LockAcquisition.NotSupported] is `OperationFailed` with
 *   the `IOException` as cause, nothing read, nothing written, no native call (the lock is part of the security
 *   argument of 4.4; the plain store may degrade, a secure store never does).
 * - **The block may suspend** (a native call that creates the key must run inside the lock, plan R7) but never
 *   waits for anything but a native call that has its own limit or is interactive and cancellable. A caller cancelled
 *   while waiting or running holds neither lock afterwards (`Held.use`, the timed `withLock`).
 * - **Nothing throws out of [locked]**: an `IOException`, a `SecurityException` or a provider failure inside the block is
 *   `OperationFailed(operation, key, cause)`; a `CancellationException` always propagates.
 */
internal class EncryptedFileGuard(
    val file: File,
    private val files: FileAccess = createFileAccess(),
    private val crossProcessLock: CrossProcessLock = CrossProcessLock(),
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val clock: () -> Instant = Instant::now,
) {
    private val lockFile: File = CrossProcessLock.lockFileOf(file)

    /** The prefix of every temporary sibling of this store (also the one of its lock-file creation). */
    private val tempPrefix: String = temporaryPrefixOf(file.name)

    /** Shared by every store over this file, whatever the spelling of its path (the plain store's folding). */
    private val lockKey: String = jvmStorageLockKey(file)

    /** Whether the directory of the store does not exist yet: such a store holds nothing, and reading it creates nothing. */
    fun directoryMissing(): Boolean = file.absoluteFile.parentFile?.exists() == false

    /**
     * Runs [block] under both locks and wraps its value in `Success`. A lock that is not obtained within the budget is
     * `Unavailable`; a lock file that cannot be created or a file system without locks is `OperationFailed`.
     */
    suspend fun <T> locked(operation: StorageOperation, key: String?, block: suspend () -> T): StorageResult<T> {
        val budget: Duration = crossProcessLock.deadline
        val started: ComparableTimeMark = timeSource.markNow()
        return LOCKS.withLock(lockKey, budget, onTimeout = { unavailable<T>() }) {
            when (val acquisition: LockAcquisition = crossProcessLock.acquire(lockFile, tempPrefix, budget - started.elapsedNow())) {
                LockAcquisition.TimedOut -> unavailable()
                is LockAcquisition.Failed -> failed(operation, key, acquisition.cause)
                is LockAcquisition.NotSupported -> failed(operation, key, acquisition.cause)
                is LockAcquisition.Held -> acquisition.use { shielded(operation, key, block) }
            }
        }
    }

    private suspend fun <T> shielded(operation: StorageOperation, key: String?, block: suspend () -> T): StorageResult<T> =
        try {
            StorageResult.Success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            failed(operation, key, error)
        } catch (error: SecurityException) {
            failed(operation, key, error)
        } catch (error: IllegalStateException) {
            // The AES-GCM provider failed (an environment problem, never "wrong key"): typed, not thrown.
            failed(operation, key, error)
        } catch (error: IllegalArgumentException) {
            failed(operation, key, error)
        }

    private fun <T> failed(operation: StorageOperation, key: String?, cause: Throwable): StorageResult<T> =
        StorageResult.Failure(StorageError.OperationFailed(operation, key = key, cause = cause))

    private fun <T> unavailable(): StorageResult<T> = StorageResult.Failure(
        StorageError.Unavailable(TimeoutException("Another writer held ${lockFile.name} past the wait limit")),
    )

    // ---- Everything below runs only inside `locked`. ----

    /** The raw bytes of the store file, or `null` when it is absent. A file too large to be a store is [FileState.Unparsable]. */
    fun classify(kinds: Set<StoreKind>): FileState {
        return when (val result: FileResult<ByteArray> = files.readBytes(file.absolutePath, MAX_STORE_BYTES)) {
            is FileResult.Success -> StoreClassifier.classify(result.value, kinds)
            is FileResult.Failure -> when (val error: FileError = result.error) {
                is FileError.NotFound -> FileState.Absent
                is FileError.TooLarge -> FileState.Unparsable(newerVersion = false, reason = "TOO_LARGE")
                else -> throw ioException(error)
            }
        }
    }

    /** Writes the whole file atomically, owner-only, synced. Returns `false` only for [ExistingFile.Fail] when a file is there. */
    fun write(bytes: ByteArray, existing: ExistingFile): Boolean {
        val result: FileResult<Unit> = files.writeAtomically(
            file.absolutePath,
            bytes,
            WriteOptions(Durability.Synced, existing = existing, createParentDirectories = true, tempPrefix = tempPrefix, ownerOnly = true),
        )
        if (result is FileResult.Failure) {
            if (result.error is FileError.AlreadyExists && existing == ExistingFile.Fail) return false
            throw ioException(result.error)
        }
        return true
    }

    /** Moves the store file to a free quarantine name (never replaces, never deletes) and returns where it went. */
    fun quarantine(): File = Quarantine.moveAside(files, file, clock())

    /** Removes the temporary siblings of this store that a killed writer left behind (exact prefix, never a neighbour's). */
    fun sweepTemps() {
        val directory: File = file.absoluteFile.parentFile ?: return
        directory.listFiles { sibling: File -> FileNames.isTempName(sibling.name, tempPrefix) }
            ?.forEach { leftover: File -> files.delete(leftover.absolutePath) }
    }

    /**
     * Puts [emptyFile] (a sealed file with zero entries) in place for [mode], then runs [install] (the OS item, `null` =
     * done or the error), rolling EVERYTHING back on any failure, including cancellation and every exception: a created file
     * is deleted or the previous bytes restored, a quarantined file is renamed back. The store is then byte-identical to
     * before the call. The rollback never suspends, so it runs to the end even for a cancelled caller (there is no
     * `NonCancellable` to need).
     */
    suspend fun createFile(mode: CreateMode, emptyFile: ByteArray, install: suspend () -> StorageError?): CreateResult {
        var quarantined: File? = null
        var created = false
        var committed = false
        try {
            when (mode) {
                CreateMode.Fresh -> if (!write(emptyFile, ExistingFile.Fail)) return CreateResult.Raced
                CreateMode.AfterQuarantine -> {
                    quarantined = quarantine()
                    if (!write(emptyFile, ExistingFile.Fail)) {
                        // Someone created a file between the move and the write: nothing of ours to undo but the move,
                        // which cannot be undone over a file that exists. The quarantined bytes are kept.
                        return CreateResult.Raced
                    }
                }
                is CreateMode.ReplaceEmpty -> {
                    // Re-checked under the lock: the file must still be the empty one we decided on.
                    val current: FileState = classifyBytes()
                    if (current !is FileState.Parsed || current.file.entries.isNotEmpty()) return CreateResult.Raced
                    write(emptyFile, ExistingFile.Replace)
                }
            }
            created = true
            val error: StorageError? = install()
            if (error != null) {
                rollback(mode, created, quarantined)
                return CreateResult.Failed(error)
            }
            committed = true
            return CreateResult.Created
        } catch (error: Throwable) {
            if (!committed) rollback(mode, created, quarantined)
            throw error
        }
    }

    private fun classifyBytes(): FileState = classify(StoreKind.entries.toSet())

    private fun rollback(mode: CreateMode, created: Boolean, quarantined: File?) {
        try {
            when (mode) {
                CreateMode.Fresh -> if (created) files.delete(file.absolutePath)
                CreateMode.AfterQuarantine -> {
                    if (created) files.delete(file.absolutePath)
                    if (quarantined != null) Quarantine.restore(files, quarantined, file)
                }
                is CreateMode.ReplaceEmpty -> if (created) write(mode.previous, ExistingFile.Replace)
            }
        } catch (_: IOException) {
            // The store is then an empty sealed file without its item: the recoverable crash state of plan 4.4,
            // read as "key lost, zero entries" by the next call. The original failure is what the caller answers.
        }
    }

    private fun ioException(error: FileError): IOException = IOException(error.toString(), (error as? FileError.Io)?.cause)

    internal companion object {
        /** Process-wide, one entry per store file with a user; entries vanish when nobody holds or awaits the key. */
        val LOCKS: KeyedMutex<String> = KeyedMutex()

        /** No store is this large; a bigger file is not one (read as unparsable instead of read into memory). */
        const val MAX_STORE_BYTES: Long = 256L * 1024 * 1024
    }
}
