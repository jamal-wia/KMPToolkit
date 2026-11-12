package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.CancellationCheck
import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileAccessConfig
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.FileProtection
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.ReadableFile
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile

private const val MAX_TEMP_ATTEMPTS: Int = 16
private const val COPY_CHUNK_BYTES: Int = 64 * 1024

/**
 * Every algorithm of the module, written once over [FileSystemOps]. Stateless and lock-free; see FileAccess for the
 * contracts and the design spec (section 4) for the step lists.
 */
@OptIn(ToolkitInternalApi::class)
internal class FileEngine(
    private val ops: FileSystemOps,
    private val config: FileAccessConfig,
) : FileAccess {

    private val rules: PathRules = if (ops.windowsPaths) PathRules.Windows else PathRules.Posix

    // ---- validation and error plumbing ------------------------------------------------------------

    private fun isValid(path: String): Boolean {
        if (path.isEmpty() || path.indexOf('\u0000') >= 0 || !rules.isAbsolute(path)) return false
        if (ops.windowsPaths) {
            for (segment: String in rules.parse(path).segments) {
                if (segment == "." || segment == "..") continue
                if (segment.endsWith('.') || segment.endsWith(' ') || FileNames.isWindowsReserved(segment)) return false
            }
        }
        return true
    }

    private fun <T> invalid(path: String): FileResult<T> = FileResult.Failure(FileError.InvalidPath(path))

    private inline fun <T> attempt(path: String?, block: () -> FileResult<T>): FileResult<T> =
        try {
            block()
        } catch (e: FileOpException) {
            FileResult.Failure(e.error)
        } catch (e: Exception) {
            FileResult.Failure(FileError.Io(path, null, e))
        }

    /** The error with [path] as its path, for failures of a temp file the caller never named. */
    private fun FileError.withPath(path: String): FileError = when (this) {
        is FileError.NotFound -> copy(path = path)
        is FileError.AlreadyExists -> copy(path = path)
        is FileError.NotADirectory -> copy(path = path)
        is FileError.IsADirectory -> copy(path = path)
        is FileError.DirectoryNotEmpty -> copy(path = path)
        is FileError.AccessDenied -> copy(path = path)
        is FileError.NoSpace -> copy(path = path)
        is FileError.NameTooLong -> copy(path = path)
        is FileError.CrossDevice -> copy(path = path)
        is FileError.TooLarge -> copy(path = path)
        is FileError.Unsupported -> copy(path = path)
        is FileError.InvalidPath -> copy(path = path)
        is FileError.Io -> copy(path = path)
        FileError.Cancelled -> this
    }

    private inline fun <T> labeled(path: String, block: () -> T): T =
        try {
            block()
        } catch (e: FileOpException) {
            throw FileOpException(e.error.withPath(path))
        }

    private fun removeQuietly(path: String) {
        try {
            ops.remove(path)
        } catch (_: Exception) {
            // Best effort by contract: a leftover is swept by its name.
        }
    }

    private fun closeQuietly(file: WritableFile) {
        try {
            file.close()
        } catch (_: Exception) {
            // The failure that got us here is the one to report.
        }
    }

    private fun closeQuietly(file: ReadableFile) {
        try {
            file.close()
        } catch (_: Exception) {
            // Nothing was written through it.
        }
    }

    private fun metadataOrNull(path: String, followLinks: Boolean): FileMetadata? =
        try {
            ops.metadata(path, followLinks)
        } catch (_: Exception) {
            null
        }

    // ---- queries ----------------------------------------------------------------------------------

    override fun metadata(path: String, followLinks: Boolean): FileMetadata? {
        if (!isValid(path)) return null
        return metadataOrNull(path, followLinks)
    }

    override fun list(directory: String): FileResult<List<String>> {
        if (!isValid(directory)) return invalid(directory)
        return attempt(directory) {
            FileResult.Success((ops.list(directory) ?: emptyList()).sorted())
        }
    }

    override fun freeSpaceBytes(path: String): Long? {
        if (!isValid(path)) return null
        var current: String = path
        while (true) {
            if (metadataOrNull(current, true) != null) {
                return try {
                    ops.usableSpace(current)
                } catch (_: Exception) {
                    null
                }
            }
            current = rules.parentOf(current) ?: return null
        }
    }

    // ---- directories ------------------------------------------------------------------------------

    override fun createDirectories(path: String, durable: Boolean): FileResult<Unit> {
        val created: MutableList<String> = ArrayList()
        val made: FileResult<Unit> = createDirectoriesTracking(path, created)
        if (made is FileResult.Failure || !durable || created.isEmpty()) return made
        return attempt(path) {
            try {
                syncCreatedLevels(created, full = true)
            } catch (e: FileOpException) {
                return FileResult.Failure(FileError.Io(path, (e.error as? FileError.Io)?.platformCode, e))
            }
            FileResult.Success(Unit)
        }
    }

    /**
     * Syncs each of [levels] (outermost first) into its parent, deepest first. With [full] the last sync, that of the
     * outermost level, is the full flush; otherwise none is (the caller flushes after them).
     */
    private fun syncCreatedLevels(levels: List<String>, full: Boolean) {
        val syncs: List<String> = levels.asReversed().mapNotNull { level: String -> rules.parentOf(level) }
        for ((index: Int, parentDir: String) in syncs.withIndex()) {
            ops.syncDirectory(parentDir, full && index == syncs.lastIndex)
        }
    }

    /**
     * [createDirectories] that appends to [created], outermost first, each level this call itself created or lost
     * the creation race for (found to be a directory after [FileError.AlreadyExists]).
     */
    private fun createDirectoriesTracking(path: String, created: MutableList<String>): FileResult<Unit> {
        if (!isValid(path)) return invalid(path)
        return attempt(path) {
            val missing: ArrayDeque<String> = ArrayDeque()
            var current: String = path
            while (true) {
                val found: FileMetadata? = ops.metadata(current, true)
                if (found != null) {
                    if (found.kind != FileKind.Directory) return FileResult.Failure(FileError.NotADirectory(current))
                    break
                }
                missing.addFirst(current)
                current = rules.parentOf(current) ?: return FileResult.Failure(FileError.NotFound(current))
            }
            for (level: String in missing) {
                try {
                    ops.createDirectory(level)
                    created += level
                } catch (e: FileOpException) {
                    if (e.error !is FileError.AlreadyExists) throw e
                    // The race loser succeeds when the winner made a directory.
                    if (ops.metadata(level, true)?.kind != FileKind.Directory) {
                        return FileResult.Failure(FileError.NotADirectory(level))
                    }
                    created += level
                }
            }
            FileResult.Success(Unit)
        }
    }

    // ---- reading ----------------------------------------------------------------------------------

    override fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray> {
        require(maxBytes in 0..FileAccess.MAX_READ_BYTES) {
            "maxBytes must be in 0..${FileAccess.MAX_READ_BYTES}, was $maxBytes"
        }
        if (!isValid(path)) return invalid(path)
        return attempt(path) {
            val found: FileMetadata = ops.metadata(path, true) ?: return FileResult.Failure(FileError.NotFound(path))
            when (found.kind) {
                FileKind.Directory -> return FileResult.Failure(FileError.IsADirectory(path))
                FileKind.RegularFile -> Unit
                // Opening a pipe or a device could block forever.
                else -> return FileResult.Failure(FileError.Unsupported(path))
            }
            if (found.sizeBytes > maxBytes) {
                return FileResult.Failure(FileError.TooLarge(path, found.sizeBytes, maxBytes))
            }
            val limit: Int = (maxBytes + 1).toInt()
            val file: ReadableFile = ops.openForRead(path)
            try {
                var buffer: ByteArray = ByteArray(minOf(found.sizeBytes + 1, limit.toLong()).toInt().coerceAtLeast(1))
                var total: Int = 0
                while (true) {
                    if (total == buffer.size) {
                        if (total >= limit) break
                        buffer = buffer.copyOf(minOf(buffer.size.toLong() * 2, limit.toLong()).toInt())
                    }
                    val read: Int = file.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                if (total > maxBytes) {
                    FileResult.Failure(FileError.TooLarge(path, total.toLong(), maxBytes))
                } else {
                    FileResult.Success(buffer.copyOf(total))
                }
            } finally {
                closeQuietly(file)
            }
        }
    }

    // ---- writing ----------------------------------------------------------------------------------

    /** Creates a temp file `<dir>/<prefix><digits>.tmp` exclusively; retries a name clash up to [MAX_TEMP_ATTEMPTS] times. */
    private fun createTemp(directory: String, prefix: String, protection: FileProtection): Pair<String, WritableFile> {
        repeat(MAX_TEMP_ATTEMPTS) {
            val temp: String = rules.join(directory, newTempName(prefix))
            try {
                return temp to ops.createNew(temp, protection)
            } catch (e: FileOpException) {
                if (e.error !is FileError.AlreadyExists) throw e
            }
        }
        throw FileOpException(FileError.Io(directory, null, null))
    }

    override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
        if (!isValid(path)) return invalid(path)
        val tempDirectoryOption: String? = options.tempDirectory
        if (tempDirectoryOption != null && !isValid(tempDirectoryOption)) return invalid(tempDirectoryOption)
        val parent: String = rules.parentOf(path) ?: return invalid(path)
        val tempDirectory: String = tempDirectoryOption ?: parent
        return attempt(path) {
            // The levels this call created below the target: SyncedWithDirectory must make each one durable.
            val createdLevels: MutableList<String> = ArrayList()
            if (options.createParentDirectories) {
                val made: FileResult<Unit> = createDirectoriesTracking(parent, createdLevels)
                if (made is FileResult.Failure) return made
                if (tempDirectoryOption != null) {
                    val madeTemp: FileResult<Unit> = createDirectories(tempDirectoryOption)
                    if (madeTemp is FileResult.Failure) return madeTemp
                }
            }
            val existing: FileMetadata? = ops.metadata(path, false)
            if (existing?.kind == FileKind.Directory) return FileResult.Failure(FileError.IsADirectory(path))
            if (options.existing == ExistingFile.Fail && existing != null) {
                return FileResult.Failure(FileError.AlreadyExists(path))
            }
            val prefix: String = options.tempPrefix ?: config.tempPrefix
            val (temp: String, file: WritableFile) = labeled(tempDirectory) { createTemp(tempDirectory, prefix, options.protection) }
            var settled: Boolean = false
            try {
                var closed: Boolean = false
                try {
                    labeled(path) {
                        file.write(bytes, 0, bytes.size)
                        when (options.durability) {
                            Durability.Buffered -> Unit
                            Durability.Synced -> file.sync(false)
                            Durability.SyncedWithDirectory -> file.sync(true)
                        }
                        file.close()
                    }
                    closed = true
                } finally {
                    if (!closed) closeQuietly(file)
                }
                labeled(path) {
                    when (options.existing) {
                        ExistingFile.Replace -> ops.rename(temp, path)
                        ExistingFile.Fail -> ops.link(temp, path)
                    }
                }
                if (options.existing == ExistingFile.Fail) {
                    // The new name is in place; a temp that cannot be removed now is swept later by its name.
                    removeQuietly(temp)
                }
                settled = true
                if (options.durability == Durability.SyncedWithDirectory) {
                    try {
                        // A new directory's entry is durable only once its parent is synced: deepest level first,
                        // then the target's parent with the full flush (one device flush covers them all on iOS).
                        syncCreatedLevels(createdLevels, full = false)
                        ops.syncDirectory(parent, true)
                    } catch (e: FileOpException) {
                        return FileResult.Failure(FileError.Io(parent, (e.error as? FileError.Io)?.platformCode, e))
                    }
                }
                FileResult.Success(Unit)
            } finally {
                if (!settled) removeQuietly(temp)
            }
        }
    }

    // ---- move -------------------------------------------------------------------------------------

    override fun move(
        from: String,
        to: String,
        options: MoveOptions,
        cancellation: CancellationCheck,
    ): FileResult<Unit> {
        if (!isValid(from)) return invalid(from)
        if (!isValid(to)) return invalid(to)
        return attempt(from) {
            val source: FileMetadata = ops.metadata(from, false) ?: return FileResult.Failure(FileError.NotFound(from))
            if (options.createParentDirectories) {
                val parent: String = rules.parentOf(to) ?: return invalid(to)
                val made: FileResult<Unit> = createDirectories(parent)
                if (made is FileResult.Failure) return made
            }
            if (ops.metadata(to, false)?.kind == FileKind.Directory) {
                return FileResult.Failure(FileError.IsADirectory(to))
            }
            if (options.existing == ExistingFile.Fail &&
                (source.kind == FileKind.Directory || source.kind == FileKind.SymbolicLink)
            ) {
                // No atomic no-replace rename of a directory exists, and link(2) follows a symbolic link on Darwin
                // (and Files.createLink on a macOS JVM): a no-replace move would turn the link into a file.
                return FileResult.Failure(FileError.Unsupported(from))
            }
            try {
                when (options.existing) {
                    ExistingFile.Replace -> ops.rename(from, to)
                    ExistingFile.Fail -> {
                        ops.link(from, to)
                        // [to] holds the bytes: a failed removal of [from] is not a failure of the move.
                        removeQuietly(from)
                    }
                }
                FileResult.Success(Unit)
            } catch (e: FileOpException) {
                if (e.error is FileError.CrossDevice && options.acrossVolumes && source.kind == FileKind.RegularFile) {
                    copyAcrossVolumes(from, to, options, cancellation)
                } else {
                    FileResult.Failure(e.error)
                }
            }
        }
    }

    private fun copyAcrossVolumes(
        from: String,
        to: String,
        options: MoveOptions,
        cancellation: CancellationCheck,
    ): FileResult<Unit> {
        val directory: String = rules.parentOf(to) ?: return invalid(to)
        val (temp: String, out: WritableFile) = labeled(directory) { createTemp(directory, config.tempPrefix, FileProtection.Default) }
        var settled: Boolean = false
        try {
            var outClosed: Boolean = false
            val input: ReadableFile = try {
                ops.openForRead(from)
            } catch (e: Exception) {
                closeQuietly(out)
                throw e
            }
            try {
                val buffer: ByteArray = ByteArray(COPY_CHUNK_BYTES)
                while (true) {
                    if (cancellation.isCancelled()) return FileResult.Failure(FileError.Cancelled)
                    val read: Int = input.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read > 0) labeled(to) { out.write(buffer, 0, read) }
                }
                labeled(to) {
                    out.sync(false)
                    out.close()
                }
                outClosed = true
            } finally {
                closeQuietly(input)
                if (!outClosed) closeQuietly(out)
            }
            labeled(to) {
                when (options.existing) {
                    ExistingFile.Replace -> ops.rename(temp, to)
                    ExistingFile.Fail -> ops.link(temp, to)
                }
            }
            if (options.existing == ExistingFile.Fail) removeQuietly(temp)
            settled = true
            removeQuietly(from)
            return FileResult.Success(Unit)
        } finally {
            if (!settled) removeQuietly(temp)
        }
    }

    // ---- delete, tree size, sweep -----------------------------------------------------------------

    override fun delete(path: String): FileResult<Unit> {
        if (!isValid(path)) return invalid(path)
        return attempt(path) {
            if (ops.metadata(path, false) == null) return FileResult.Success(Unit)
            try {
                ops.remove(path)
            } catch (e: FileOpException) {
                if (e.error !is FileError.NotFound) throw e
            }
            FileResult.Success(Unit)
        }
    }

    private class Frame(val path: String, var listed: Boolean)

    override fun deleteRecursively(path: String, cancellation: CancellationCheck): FileResult<Unit> {
        if (!isValid(path)) return invalid(path)
        return attempt(path) {
            var firstError: FileError? = null
            fun record(error: FileError) {
                if (firstError == null) firstError = error
            }
            val stack: ArrayDeque<Frame> = ArrayDeque()
            stack.addLast(Frame(path, false))
            while (stack.isNotEmpty()) {
                if (cancellation.isCancelled()) return FileResult.Failure(FileError.Cancelled)
                val frame: Frame = stack.last()
                if (frame.listed) {
                    stack.removeLast()
                    removeEntry(frame.path, ::record)
                    continue
                }
                val found: FileMetadata? = try {
                    ops.metadata(frame.path, false)
                } catch (e: FileOpException) {
                    record(e.error)
                    null
                }
                if (found == null) {
                    stack.removeLast()
                    continue
                }
                if (found.kind != FileKind.Directory) {
                    stack.removeLast()
                    removeEntry(frame.path, ::record)
                    continue
                }
                val names: List<String>? = try {
                    ops.list(frame.path)
                } catch (e: FileOpException) {
                    record(e.error)
                    stack.removeLast()
                    continue
                }
                if (names == null) {
                    stack.removeLast()
                    continue
                }
                frame.listed = true
                for (name: String in names) stack.addLast(Frame(rules.join(frame.path, name), false))
            }
            if (metadataOrNull(path, false) == null) {
                FileResult.Success(Unit)
            } else {
                FileResult.Failure(firstError ?: FileError.DirectoryNotEmpty(path))
            }
        }
    }

    private fun removeEntry(path: String, record: (FileError) -> Unit) {
        try {
            ops.remove(path)
        } catch (e: FileOpException) {
            if (e.error !is FileError.NotFound) record(e.error)
        } catch (e: Exception) {
            record(FileError.Io(path, null, e))
        }
    }

    override fun treeSize(path: String, cancellation: CancellationCheck): FileResult<Long> {
        if (!isValid(path)) return invalid(path)
        return attempt(path) {
            var total: Long = 0L
            val stack: ArrayDeque<String> = ArrayDeque()
            stack.addLast(path)
            while (stack.isNotEmpty()) {
                if (cancellation.isCancelled()) return FileResult.Failure(FileError.Cancelled)
                val current: String = stack.removeLast()
                val found: FileMetadata = metadataOrNull(current, false) ?: continue
                when (found.kind) {
                    FileKind.RegularFile -> total = saturatingAdd(total, found.sizeBytes)
                    FileKind.Directory -> {
                        val names: List<String> = try {
                            ops.list(current)
                        } catch (_: Exception) {
                            null
                        } ?: continue
                        for (name: String in names) stack.addLast(rules.join(current, name))
                    }
                    FileKind.SymbolicLink, FileKind.Other -> Unit
                }
            }
            FileResult.Success(total)
        }
    }

    override fun sweep(
        directory: String,
        olderThanMillis: Long,
        cancellation: CancellationCheck,
        match: (name: String) -> Boolean,
    ): FileResult<Int> {
        require(olderThanMillis >= 0) { "olderThanMillis must not be negative, was $olderThanMillis" }
        if (!isValid(directory)) return invalid(directory)
        val listed: FileResult<List<String>> = attempt(directory) {
            FileResult.Success(ops.list(directory) ?: emptyList())
        }
        val names: List<String> = when (listed) {
            is FileResult.Failure -> return listed
            is FileResult.Success -> listed.value
        }
        if (names.isEmpty()) return FileResult.Success(0)
        val now: Long = ops.nowMillis()
        var deleted: Int = 0
        for (name: String in names) {
            if (cancellation.isCancelled()) return FileResult.Failure(FileError.Cancelled)
            // The caller's lambda runs outside every catch: its exceptions propagate unchanged.
            if (!match(name)) continue
            val child: String = rules.join(directory, name)
            val found: FileMetadata = metadataOrNull(child, false) ?: continue
            if (found.kind != FileKind.RegularFile && found.kind != FileKind.SymbolicLink) continue
            val modified: Long = found.lastModifiedMillis ?: continue
            if (saturatingSubtract(now, modified) <= olderThanMillis) continue
            try {
                ops.remove(child)
                deleted++
            } catch (_: Exception) {
                // Best effort: a vanished entry is not counted, any other failure is skipped.
            }
        }
        return FileResult.Success(deleted)
    }

    private fun saturatingAdd(a: Long, b: Long): Long {
        val sum: Long = a + b
        return if (((a xor sum) and (b xor sum)) < 0) Long.MAX_VALUE else sum
    }

    private fun saturatingSubtract(a: Long, b: Long): Long {
        val difference: Long = a - b
        return if (((a xor b) and (a xor difference)) < 0) (if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE) else difference
    }
}
