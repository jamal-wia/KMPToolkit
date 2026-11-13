package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.mapNioError
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round 2 of the breaker for unit G, step 3 (docs-sentence audit): the error mapping table of 05-platform-notes.md and
 * the `FileError` paragraph of 04-api-reference.md, one assertion per documented row, on the pure mapping function.
 */
class NioErrorsDocsR2Test {
    private val path: String = "/d/f"

    private fun untyped(reason: String): FileSystemException = FileSystemException(path, null, reason)

    @Test
    fun `typed exceptions map by type and never by text`() {
        assertEquals(FileError.NotFound(path), mapNioError(NoSuchFileException(path), path))
        assertEquals(FileError.AlreadyExists(path), mapNioError(FileAlreadyExistsException(path), path))
        assertEquals(FileError.NotADirectory(path), mapNioError(NotDirectoryException(path), path))
        assertEquals(FileError.DirectoryNotEmpty(path), mapNioError(DirectoryNotEmptyException(path), path))
        assertEquals(FileError.AccessDenied(path), mapNioError(AccessDeniedException(path), path))
        assertEquals(FileError.CrossDevice(path), mapNioError(AtomicMoveNotSupportedException(path, "x", "no"), path))
        assertEquals(FileError.Unsupported(path), mapNioError(UnsupportedOperationException("x"), path))
        assertEquals(FileError.AccessDenied(path), mapNioError(SecurityException("x"), path))
        // A typed exception keeps its type whatever its message says.
        assertEquals(FileError.NotFound(path), mapNioError(NoSuchFileException(path, null, "Permission denied"), path))
    }

    @Test
    fun `the English texts of the documented errno rows map to the documented errors`() {
        val rows: Map<String, FileError> = mapOf(
            "No space left on device" to FileError.NoSpace(path),
            "Disk quota exceeded" to FileError.NoSpace(path),
            "File name too long" to FileError.NameTooLong(path),
            "Invalid cross-device link" to FileError.CrossDevice(path),
            "Directory not empty" to FileError.DirectoryNotEmpty(path),
            "Is a directory" to FileError.IsADirectory(path),
            "Not a directory" to FileError.NotADirectory(path),
            "No such file or directory" to FileError.NotFound(path),
            "Read-only file system" to FileError.AccessDenied(path),
            "Permission denied" to FileError.AccessDenied(path),
        )
        for ((reason: String, expected: FileError) in rows) {
            assertEquals(expected, mapNioError(untyped(reason), path), reason)
        }
    }

    @Test
    fun `an untyped failure that no row explains is Io with the original exception as cause`() {
        val surprise: FileSystemException = untyped("Input/output error")
        val mapped: FileError = mapNioError(surprise, path)
        assertEquals(FileError.Io(path, null, surprise), mapped)
        val unrelated: IllegalStateException = IllegalStateException("not an IO failure")
        assertEquals(FileError.Io(path, null, unrelated), mapNioError(unrelated, path))
    }

    @Test
    fun `a link refused by the file system is Unsupported for operation not supported and not permitted`() {
        for (reason: String in listOf("Operation not supported", "Operation not permitted", "Incorrect function")) {
            assertEquals(FileError.Unsupported(path), mapNioError(untyped(reason), path, forLink = true), reason)
        }
    }
}
