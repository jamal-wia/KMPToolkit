package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileProtection
import io.github.jamal_wia.kmptoolkit.files.hostUsesWindowsPaths
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.ReadableFile
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

private val WINDOWS_RENAME_BACKOFF_MILLIS: LongArray = longArrayOf(1, 2, 4, 8, 16, 32, 50, 50, 50)

/** [FileSystemOps] over java.nio.file: the desktop JVM always, Android from API 26. */
@OptIn(ToolkitInternalApi::class)
internal class NioFileSystemOps : FileSystemOps {

    override val windowsPaths: Boolean = hostUsesWindowsPaths

    override fun nowMillis(): Long = System.currentTimeMillis()

    private fun pathOf(path: String): Path =
        try {
            java.nio.file.Paths.get(path)
        } catch (e: Exception) {
            throw FileOpException(mapNioError(e, path))
        }

    override fun metadata(path: String, followLinks: Boolean): FileMetadata? {
        val p: Path = pathOf(path)
        val attributes: BasicFileAttributes = try {
            if (followLinks) {
                Files.readAttributes(p, BasicFileAttributes::class.java)
            } else {
                Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: FileSystemException) {
            // ENOTDIR on a parent surfaces as a plain FileSystemException.
            if ((e.reason ?: "").lowercase().contains("not a directory")) return null
            throw FileOpException(mapNioError(e, path))
        } catch (e: Exception) {
            throw FileOpException(mapNioError(e, path))
        }
        val kind: FileKind = when {
            attributes.isRegularFile -> FileKind.RegularFile
            attributes.isDirectory -> FileKind.Directory
            attributes.isSymbolicLink -> FileKind.SymbolicLink
            else -> FileKind.Other
        }
        return FileMetadata(
            kind = kind,
            sizeBytes = if (kind == FileKind.RegularFile) attributes.size() else 0L,
            lastModifiedMillis = attributes.lastModifiedTime()?.toMillis(),
        )
    }

    override fun list(directory: String): List<String>? {
        val p: Path = pathOf(directory)
        val names: MutableList<String> = mutableListOf()
        try {
            Files.newDirectoryStream(p).use { stream ->
                for (entry: Path in stream) names += entry.fileName.toString()
            }
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: FileOpException) {
            throw e
        } catch (e: Exception) {
            throw FileOpException(mapNioError(e, directory))
        }
        return names
    }

    override fun createDirectory(path: String) {
        val p: Path = pathOf(path)
        nio(path) { Files.createDirectory(p) }
    }

    override fun createNew(path: String, protection: FileProtection): WritableFile {
        val p: Path = pathOf(path)
        val channel: FileChannel = nio(path) {
            FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
        return NioWritableFile(channel, path)
    }

    override fun openForRead(path: String): ReadableFile {
        val p: Path = pathOf(path)
        val channel: FileChannel = nio(path) { FileChannel.open(p, StandardOpenOption.READ) }
        return NioReadableFile(channel, path)
    }

    override fun rename(from: String, to: String) {
        val source: Path = pathOf(from)
        val target: Path = pathOf(to)
        var attempt: Int = 0
        while (true) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (e: AccessDeniedException) {
                // A reader, an indexer or an antivirus holds the target without FILE_SHARE_DELETE: only on Windows
                // and only for this exception the move is retried; never remove-then-move.
                if (!windowsPaths || attempt >= WINDOWS_RENAME_BACKOFF_MILLIS.size) {
                    throw FileOpException(FileError.AccessDenied(to))
                }
                try {
                    Thread.sleep(WINDOWS_RENAME_BACKOFF_MILLIS[attempt])
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw FileOpException(FileError.AccessDenied(to))
                }
                attempt++
            } catch (e: Exception) {
                throw FileOpException(mapNioError(e, from))
            }
        }
    }

    override fun link(existing: String, newPath: String) {
        val source: Path = pathOf(existing)
        val target: Path = pathOf(newPath)
        try {
            Files.createLink(target, source)
        } catch (e: FileOpException) {
            throw e
        } catch (e: Exception) {
            throw FileOpException(
                classifyLinkFailure(
                    e,
                    newPath,
                    sourceStore = { Files.getFileStore(source) },
                    targetStore = { Files.getFileStore(target.parent) },
                ),
            )
        }
    }

    override fun remove(path: String) {
        val p: Path = pathOf(path)
        nio(path) { Files.delete(p) }
    }

    override fun syncDirectory(directory: String, full: Boolean) {
        // A directory cannot be opened on Windows; NTFS journals the rename.
        if (windowsPaths) return
        val p: Path = pathOf(directory)
        nio(directory) { FileChannel.open(p, StandardOpenOption.READ).use { it.force(true) } }
    }

    override fun usableSpace(path: String): Long? = try {
        File(path).usableSpace
    } catch (_: Exception) {
        null
    }
}

@OptIn(ToolkitInternalApi::class)
private class NioWritableFile(private val channel: FileChannel, private val path: String) : WritableFile {
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        nio(path) {
            val buffer: ByteBuffer = ByteBuffer.wrap(bytes, offset, length)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    override fun sync(full: Boolean) {
        nio(path) { channel.force(true) }
    }

    override fun close() {
        nio(path) { channel.close() }
    }
}

@OptIn(ToolkitInternalApi::class)
private class NioReadableFile(private val channel: FileChannel, private val path: String) : ReadableFile {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        nio(path) { channel.read(ByteBuffer.wrap(buffer, offset, length)) }

    override fun close() {
        nio(path) { channel.close() }
    }
}
