package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.CaseFolding
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.KeyedMutex
import io.github.jamal_wia.kmptoolkit.files.PathKey
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.temporaryPrefixOf
import java.io.File
import java.io.IOException
import java.nio.file.Files
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

    /**
     * The create failed. Everything it did was rolled back, unless [rollbackFailure] is set: then the rollback itself
     * failed and the store is NOT as it was (see [EncryptedFileGuard.StoreFile.createFile]).
     */
    class Failed(val error: StorageError, val rollbackFailure: IOException? = null) : CreateResult
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
 * - **The store file is addressed by its real path, resolved per round.** [locked] resolves the path before the lock (for
 *   the case-folded lock key) and under it (for the block), through the injectable [StorePathResolver]; the block gets a
 *   [StoreFile] and every file operation of the round goes through it. A failing resolution fails the call closed, never
 *   with a guessed path.
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
    private val resolver: StorePathResolver = StorePathResolver.real,
    /** A store file larger than this is unparsable on read, so a write that would make one is refused first (plan 4.11). */
    val maxStoreBytes: Long = MAX_STORE_BYTES,
) {
    /** What one locked try ended with: the block's value, or "the real path moved under us, try again". */
    private sealed interface Round<out T> {
        class Done<out T>(val value: T) : Round<T>
        data object Moved : Round<Nothing>
    }

    /** Whether the directory of the store does not exist yet: such a store holds nothing, and reading it creates nothing. */
    fun directoryMissing(): Boolean = file.absoluteFile.parentFile?.exists() == false

    /**
     * Runs [block] under both locks and wraps its value in `Success`. A lock that is not obtained within the budget is
     * `Unavailable`; a lock file that cannot be created or a file system without locks is `OperationFailed`; so is a
     * file system that cannot be asked where the store file really is (never a guessed path: see [RealStorePath]).
     *
     * The real path is resolved before the lock (for the lock key, case-folded) and again under it, for the block: a
     * path that moved in between is tried again (a bounded number of times, then `Unavailable`), so one identity is
     * always one lock.
     */
    suspend fun <T> locked(operation: StorageOperation, key: String?, block: suspend (StoreFile) -> T): StorageResult<T> {
        var lastLockName: String = file.name
        repeat(MAX_PATH_TRIES) {
            val before: RealStorePath = try {
                resolver.resolve(file)
            } catch (error: IOException) {
                return failed(operation, key, error)
            } catch (error: SecurityException) {
                return failed(operation, key, error)
            }
            val lockKey: String = PathKey.of(before.path, CaseFolding.Always)
            // The names of a long file name are hashed (SHA-256): a provider that fails there is an environment problem like the
            // resolution above, answered here before any lock is taken, never thrown out of the call.
            val lockFile: File
            val tempPrefix: String
            try {
                lockFile = CrossProcessLock.lockFileOf(before.file)
                tempPrefix = temporaryPrefixOf(before.name)
            } catch (error: JcaFailure) {
                return failed(operation, key, error)
            }
            lastLockName = lockFile.name
            val budget: Duration = crossProcessLock.deadline
            val started: ComparableTimeMark = timeSource.markNow()
            val round: StorageResult<Round<T>> = LOCKS.withLock(lockKey, budget, onTimeout = { unavailable(lockFile.name) }) {
                when (val acquisition: LockAcquisition = crossProcessLock.acquire(lockFile, tempPrefix, budget - started.elapsedNow())) {
                    LockAcquisition.TimedOut -> unavailable(lockFile.name)
                    is LockAcquisition.Failed -> failed(operation, key, acquisition.cause)
                    is LockAcquisition.NotSupported -> failed(operation, key, acquisition.cause)
                    is LockAcquisition.Held -> acquisition.use { shielded(operation, key) { under(lockKey, block) } }
                }
            }
            when (round) {
                is StorageResult.Failure -> return round
                is StorageResult.Success -> when (val outcome: Round<T> = round.value) {
                    is Round.Done -> return StorageResult.Success(outcome.value)
                    Round.Moved -> Unit
                }
            }
        }
        return unavailable(lastLockName)
    }

    /** Under the locks: resolve again, and run [block] only if this is still the file the lock key stands for. */
    private suspend fun <T> under(lockKey: String, block: suspend (StoreFile) -> T): Round<T> {
        val real: RealStorePath = resolver.resolve(file)
        if (PathKey.of(real.path, CaseFolding.Always) != lockKey) return Round.Moved
        return Round.Done(block(StoreFile(real)))
    }

    private suspend fun <T> shielded(operation: StorageOperation, key: String?, block: suspend () -> Round<T>): StorageResult<Round<T>> =
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

    private fun <T> unavailable(lockName: String): StorageResult<T> = StorageResult.Failure(
        StorageError.Unavailable(TimeoutException("Another writer held $lockName past the wait limit")),
    )

    /**
     * The store file of ONE locked round, at the real path resolved under the locks: every operation on the file inside
     * the block goes through it, so the stored spelling is the one the identity was derived from.
     */
    inner class StoreFile internal constructor(val real: RealStorePath) {
        /** The store file at its real path. */
        val file: File = real.file

        private val tempPrefix: String = temporaryPrefixOf(real.name)

        /** The raw bytes of the store file, or `null` when it is absent. A file too large to be a store is [FileState.Unparsable]. */
        fun classify(kinds: Set<StoreKind>): FileState {
            return when (val result: FileResult<ByteArray> = files.readBytes(file.absolutePath, maxStoreBytes)) {
                is FileResult.Success -> StoreClassifier.classify(result.value, kinds)
                is FileResult.Failure -> when (val error: FileError = result.error) {
                    is FileError.NotFound -> FileState.Absent
                    is FileError.TooLarge -> FileState.Unparsable(newerVersion = false, reason = "TOO_LARGE")
                    else -> throw ioException(error)
                }
            }
        }

        /**
         * Whether the store path is a symbolic link, asked after [classify] answered [FileState.Absent] (which reads through links:
         * a link then has no target). It asks BOTH names: the resolved one ([file]) and the store's own configured one, because the
         * round resolves the real path first, and a link whose target was deleted AFTER that resolution has already been replaced
         * by its target in the resolved name, which is then no link at all. Creating at either name would create the file at the far
         * end of the link, or replace the link. Metadata of the names themselves, never an open (so it cannot block on a pipe).
         */
        fun isLinkWithoutTarget(): Boolean =
            Files.isSymbolicLink(file.toPath()) || Files.isSymbolicLink(this@EncryptedFileGuard.file.absoluteFile.toPath())

        /**
         * Whether the store's own configured name (not the resolved one) is a symbolic link: the one fact that decides if the stored
         * spelling of the suffix may differ from the configured one only by case (see [StoreIdentity.of]). Metadata of the name itself,
         * never an open.
         */
        fun configuredNameIsLink(): Boolean = Files.isSymbolicLink(this@EncryptedFileGuard.file.absoluteFile.toPath())

        /** Writes the whole file atomically, owner-only, synced. Returns `false` only for [ExistingFile.Fail] when a file is there. */
        fun write(bytes: ByteArray, existing: ExistingFile): Boolean {
            // A file over the limit would be read back as unparsable and quarantined by the next put: the engine refuses
            // such a write before it touches anything, this is the last line.
            if (bytes.size > maxStoreBytes) throw IOException("The encoded store would be ${bytes.size} bytes, over the limit of $maxStoreBytes")
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
         * is deleted or the previous bytes restored, a quarantined file is renamed back (over the file this create made, in
         * one atomic rename, so a file that cannot be deleted cannot keep the data in quarantine). The store is then
         * byte-identical to before the call. The rollback never suspends, so it runs to the end even for a cancelled caller
         * (there is no `NonCancellable` to need).
         *
         * If the rollback ITSELF fails, the store is not as it was: for a create that followed a quarantine, the user's file
         * stays under its quarantine name while the store reads empty. That is never silent: [CreateResult.Failed] carries
         * the failure ([CreateResult.Failed.rollbackFailure], which names the quarantine file) and a thrown error gets it as a
         * suppressed exception. A failed rollback of an empty file (nothing of the user's) leaves the recoverable crash
         * state of plan 4.4 and is not reported.
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
                if (error != null) return CreateResult.Failed(error, rollback(mode, created, quarantined))
                committed = true
                return CreateResult.Created
            } catch (error: Throwable) {
                if (!committed) rollback(mode, created, quarantined)?.let { residual: IOException -> error.addSuppressed(residual) }
                throw error
            }
        }

        private fun classifyBytes(): FileState = classify(StoreKind.entries.toSet())

        /** Undoes a create; returns the failure that left the user's quarantined file out of its place, or `null`. */
        private fun rollback(mode: CreateMode, created: Boolean, quarantined: File?): IOException? {
            try {
                when (mode) {
                    CreateMode.Fresh -> if (created) files.delete(file.absolutePath)
                    CreateMode.AfterQuarantine -> {
                        // Over our own empty file when we made one, otherwise only into a free name (never over a winner's file).
                        if (quarantined != null) {
                            if (created) Quarantine.restoreOver(files, quarantined, file) else Quarantine.restore(files, quarantined, file)
                        } else if (created) {
                            files.delete(file.absolutePath)
                        }
                    }
                    is CreateMode.ReplaceEmpty -> if (created) write(mode.previous, ExistingFile.Replace)
                }
            } catch (error: IOException) {
                // A failed rollback of an empty file leaves an empty sealed file without its item: the recoverable crash
                // state of plan 4.4, read as "key lost, zero entries" by the next call. Only a quarantined file of the
                // user's is reported.
                if (mode == CreateMode.AfterQuarantine && quarantined != null) {
                    return IOException("Could not move ${quarantined.name} back to ${file.name}: the data stays in the quarantine file", error)
                }
            }
            return null
        }

        private fun ioException(error: FileError): IOException = IOException(error.toString(), (error as? FileError.Io)?.cause)

    }

    internal companion object {
        /** How often a round is repeated when the real path moved between the pre-lock and the under-lock resolution. */
        const val MAX_PATH_TRIES: Int = 3

        /** Process-wide, one entry per store file with a user; entries vanish when nobody holds or awaits the key. */
        val LOCKS: KeyedMutex<String> = KeyedMutex()

        /** No store is this large; a bigger file is not one (read as unparsable instead of read into memory). */
        const val MAX_STORE_BYTES: Long = 256L * 1024 * 1024
    }
}
