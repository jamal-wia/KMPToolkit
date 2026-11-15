package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.NoSuchFileException
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The outcome of [CrossProcessLock.acquire]. */
internal sealed interface LockAcquisition {

    /** The lock is held until [close]. Closing releases the lock and the file handle, and is idempotent. */
    class Held internal constructor(private val channel: FileChannel, private val lock: FileLock) : LockAcquisition, AutoCloseable {
        private val closed: AtomicBoolean = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                lock.release()
            } catch (_: IOException) {
                // Closing the channel releases the lock anyway.
            } finally {
                channel.close()
            }
        }
    }

    /** Another process (or another handle of this one) kept the lock for the whole deadline: `Unavailable` above. */
    data object TimedOut : LockAcquisition

    /** The lock file could not be created, opened or locked: `OperationFailed` above. */
    class Failed(val cause: IOException) : LockAcquisition {
        override fun toString(): String = "Failed(${cause::class.java.simpleName})"
    }
}

/**
 * The cross-process half of a store's write lock (plan S8, 4.3, 6c). The caller takes the in-process lock first (the
 * `KeyedMutex` of its file), then this one, and releases this one first. The lock is `FileChannel.tryLock` on a separate
 * `<file>.lock`, never the data file itself: on Windows a mandatory lock on the data file would block the atomic rename.
 *
 * - **Creation.** The lock file is created owner-only through `writeAtomically(ExistingFile.Fail)`, so the final name never
 *   exists with wider access; `AlreadyExists` counts as success (another process won). On a file system without hard links
 *   (`Unsupported`, FAT and exFAT, which have no permissions to protect either) the file is created with a plain open. It is
 *   never renamed and never deleted by the library.
 * - **Wait.** `tryLock` is polled every [pollInterval] until [deadline], then [LockAcquisition.TimedOut]. A handle is
 *   open only while one attempt runs, never while waiting, so a waiter holds no file descriptor. The wait is a coroutine
 *   `delay` and a `withTimeoutOrNull`, so it is cancellable and a test runs it on virtual time. A holder of the lock in
 *   this very JVM through another handle ([OverlappingFileLockException]) is just as busy as another process.
 * - **Cancellation.** A caller cancelled while waiting holds nothing afterwards; a lock acquired in the instant of a
 *   cancellation is released before the cancellation propagates.
 * - **No half state.** Acquiring writes nothing but the empty lock file; the data file is the caller's.
 * - **Network file systems** may grant or refuse `tryLock` unreliably; the atomic create-if-absent of the data file is the
 *   backstop, documented in `05-platform-notes.md`. A file system that refuses `tryLock` outright is [LockAcquisition.Failed].
 *
 * Blocking I/O: call it from the store's I/O dispatcher. The deadline and poll interval are constructor parameters so a
 * test can shorten them; production uses [LOCK_DEADLINE] and [POLL_INTERVAL].
 */
internal class CrossProcessLock(
    private val files: FileAccess = createFileAccess(),
    private val deadline: Duration = LOCK_DEADLINE,
    private val pollInterval: Duration = POLL_INTERVAL,
) {
    init {
        require(deadline > Duration.ZERO && pollInterval > Duration.ZERO) { "deadline and pollInterval must be positive" }
    }

    private sealed interface Attempt {
        class Got(val held: LockAcquisition.Held) : Attempt

        data object Busy : Attempt

        class Broke(val cause: IOException) : Attempt
    }

    /**
     * Takes the lock of [lockFile] within the deadline. [tempPrefix] names the temporary file of the one-time creation of
     * the lock file (the owning store's own prefix, so the store's `clear()` also removes a leftover of a creation that was
     * killed). Never throws for I/O problems; propagates cancellation.
     */
    suspend fun acquire(lockFile: File, tempPrefix: String): LockAcquisition {
        var held: LockAcquisition.Held? = null
        var broke: IOException? = null
        try {
            withTimeoutOrNull(deadline) {
                while (true) {
                    when (val attempt: Attempt = attempt(lockFile, tempPrefix)) {
                        is Attempt.Got -> {
                            held = attempt.held
                            return@withTimeoutOrNull
                        }
                        is Attempt.Broke -> {
                            broke = attempt.cause
                            return@withTimeoutOrNull
                        }
                        Attempt.Busy -> delay(pollInterval)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            held?.close()
            throw cancelled
        }
        held?.let { return it }
        broke?.let { return LockAcquisition.Failed(it) }
        return LockAcquisition.TimedOut
    }

    private fun attempt(lockFile: File, tempPrefix: String): Attempt {
        try {
            ensureLockFile(lockFile, tempPrefix)
            val channel: FileChannel = try {
                FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE)
            } catch (_: NoSuchFileException) {
                // Deleted behind our back since ensureLockFile: it is created again on the next attempt.
                return Attempt.Busy
            }
            val lock: FileLock? = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (error: IOException) {
                channel.close()
                return Attempt.Broke(error)
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
            if (lock == null) {
                channel.close()
                return Attempt.Busy
            }
            return Attempt.Got(LockAcquisition.Held(channel, lock))
        } catch (error: IOException) {
            return Attempt.Broke(error)
        } catch (error: SecurityException) {
            return Attempt.Broke(IOException("The lock file is not accessible", error))
        }
    }

    private fun ensureLockFile(lockFile: File, tempPrefix: String) {
        if (files.metadata(lockFile.absolutePath) != null) return
        val result: FileResult<Unit> = files.writeAtomically(
            lockFile.absolutePath,
            ByteArray(0),
            WriteOptions(
                durability = Durability.Buffered,
                existing = ExistingFile.Fail,
                createParentDirectories = true,
                tempPrefix = tempPrefix,
                ownerOnly = true,
            ),
        )
        if (result !is FileResult.Failure) return
        when (val error: FileError = result.error) {
            is FileError.AlreadyExists -> Unit
            // No hard links, so no atomic create-if-absent: such a volume (FAT, exFAT) has no permissions to set either.
            is FileError.Unsupported ->
                FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).close()
            else -> throw IOException(error.toString(), (error as? FileError.Io)?.cause)
        }
    }

    internal companion object {
        /** How long a write waits for another process before it answers `Unavailable` (plan 4.11). */
        val LOCK_DEADLINE: Duration = 10.seconds

        /** How often a waiting write tries again. */
        val POLL_INTERVAL: Duration = 25.milliseconds

        private const val LOCK_SUFFIX: String = ".lock"
        private const val MAX_NAME_BYTES: Int = 255
        private const val HASH_HEX_CHARS: Int = 16

        /**
         * The lock file of the data file [file]: `<file>.lock`, a sibling. When that name would pass the 255 bytes a file
         * system allows, the file name is cut at a code point boundary and `~`, 16 hex characters of its SHA-256 and `.lock`
         * follow: stable, different for names that share their beginning, and never a name a data file can have.
         */
        fun lockFileOf(file: File): File = File(file.absoluteFile.parentFile, lockNameOf(file.name))

        internal fun lockNameOf(fileName: String): String {
            val utf8: ByteArray = fileName.toByteArray(StandardCharsets.UTF_8)
            if (utf8.size + LOCK_SUFFIX.length <= MAX_NAME_BYTES) return fileName + LOCK_SUFFIX
            val keptBytes: Int = MAX_NAME_BYTES - LOCK_SUFFIX.length - 1 - HASH_HEX_CHARS
            var bytes = 0
            var end = 0
            while (end < fileName.length) {
                val codePoint: Int = fileName.codePointAt(end)
                val width: Int = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8).size
                if (bytes + width > keptBytes) break
                bytes += width
                end += Character.charCount(codePoint)
            }
            val hash: String = MessageDigest.getInstance("SHA-256").digest(utf8).take(HASH_HEX_CHARS / 2).joinToString("") { "%02x".format(it) }
            return fileName.substring(0, end) + "~" + hash + LOCK_SUFFIX
        }
    }
}
