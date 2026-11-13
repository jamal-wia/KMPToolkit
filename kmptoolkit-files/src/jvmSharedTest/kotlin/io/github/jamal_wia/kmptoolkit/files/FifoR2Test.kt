package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 2 of the breaker for unit G: a named pipe (FIFO) in the tree. Opening one for reading blocks until a writer
 * appears, so `readBytes` must answer `Unsupported` without ever opening it, and every walk (`treeSize`, `sweep`,
 * `deleteRecursively`, `move`, `list`) must behave per contract around it. A watchdog thread opens the pipe read-write
 * after three seconds, so a regression that opens it fails an assertion instead of hanging the build.
 * Skipped where the host cannot make a FIFO (Windows).
 */
class FifoR2Test {

    private val files: FileAccess = createFileAccess()

    private fun fifoOrNull(root: File, name: String): String? {
        val path: String = File(root, name).path
        return try {
            val process: Process = ProcessBuilder("mkfifo", path).redirectErrorStream(true).start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() == 0 && File(path).exists()) path else null
        } catch (_: Exception) {
            null
        }
    }

    /** Runs [block] with a watchdog that frees any reader blocked on a FIFO under [root] after three seconds. */
    private fun guarded(root: File, block: () -> Unit) {
        val watchdog: Thread = Thread {
            try {
                Thread.sleep(3_000)
                while (true) {
                    root.walkTopDown().filter { f: File -> !f.isDirectory && !f.isFile }.forEach { f: File ->
                        try {
                            RandomAccessFile(f, "rw").use { Thread.sleep(150) }
                        } catch (_: Exception) {
                            // Gone or not a pipe any more.
                        }
                    }
                    Thread.sleep(500)
                }
            } catch (_: InterruptedException) {
                // The test finished.
            }
        }
        watchdog.isDaemon = true
        watchdog.start()
        try {
            block()
        } finally {
            watchdog.interrupt()
        }
    }

    private fun withFifo(block: (root: File, fifo: String) -> Unit) {
        val root: File = Files.createTempDirectory("kmptoolkit-fifo-r2").toFile()
        try {
            val fifo: String = fifoOrNull(root, "pipe") ?: return // no mkfifo on this host (Windows)
            guarded(root) { block(root, fifo) }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun link(root: File, name: String, target: String): String? {
        val path: String = File(root, name).path
        return try {
            Files.createSymbolicLink(File(path).toPath(), File(target).toPath())
            path
        } catch (_: Exception) {
            null
        }
    }

    @Test
    fun `a pipe is Other with size zero and readBytes is Unsupported at once through the path and through a link`() =
        withFifo { root: File, fifo: String ->
            assertEquals(FileKind.Other, files.metadata(fifo, false)?.kind)
            assertEquals(0L, files.metadata(fifo, false)?.sizeBytes)
            assertNotNull(files.metadata(fifo, false)?.lastModifiedMillis)
            val started: Long = System.nanoTime()
            assertEquals(FileResult.Failure(FileError.Unsupported(fifo)), files.readBytes(fifo, 100))
            assertEquals(FileResult.Failure(FileError.Unsupported(fifo)), files.readBytes(fifo, 0))
            val viaLink: String? = link(root, "toPipe", fifo)
            if (viaLink != null) {
                assertEquals(FileKind.SymbolicLink, files.metadata(viaLink, false)?.kind)
                assertEquals(FileKind.Other, files.metadata(viaLink, true)?.kind)
                assertEquals(FileResult.Failure(FileError.Unsupported(viaLink)), files.readBytes(viaLink, 100))
            }
            assertTrue(System.nanoTime() - started < 2_000_000_000L, "readBytes of a pipe took ${(System.nanoTime() - started) / 1_000_000} ms: it opened the pipe")
        }

    @Test
    fun `list and createDirectories treat a pipe as a non-directory`() = withFifo { _: File, fifo: String ->
        assertEquals(FileResult.Failure(FileError.NotADirectory(fifo)), files.list(fifo))
        assertEquals(FileResult.Failure(FileError.NotADirectory(fifo)), files.createDirectories(fifo))
        val below: FileResult<Unit> = files.createDirectories(fifo + File.separator + "child")
        assertTrue(below is FileResult.Failure && below.error is FileError.NotADirectory, "below a pipe: $below")
        assertEquals(FileKind.Other, files.metadata(fifo, false)?.kind)
    }

    @Test
    fun `treeSize counts a pipe as zero alone and inside a directory`() = withFifo { root: File, fifo: String ->
        assertEquals(FileResult.Success(0L), files.treeSize(fifo))
        val data: String = File(root, "data").path
        assertTrue(files.writeAtomically(data, ByteArray(37)) is FileResult.Success)
        assertEquals(FileResult.Success(37L), files.treeSize(root.path))
    }

    @Test
    fun `sweep never deletes a pipe however old and however well it matches`() = withFifo { root: File, fifo: String ->
        val old: String = File(root, "old.tmp").path
        assertTrue(files.writeAtomically(old, ByteArray(3)) is FileResult.Success)
        assertTrue(File(old).setLastModified(System.currentTimeMillis() - 3_600_000L))
        Thread.sleep(30)
        val swept: FileResult<Int> = files.sweep(root.path, olderThanMillis = 0L) { true }
        assertEquals(FileResult.Success(1), swept)
        assertEquals(FileKind.Other, files.metadata(fifo, false)?.kind)
        assertEquals(null, files.metadata(old, false))
    }

    @Test
    fun `deleteRecursively removes a tree holding pipes without opening them and delete removes one`() =
        withFifo { root: File, fifo: String ->
            val nested: String = File(root, "tree").path
            assertTrue(files.createDirectories(File(nested, "sub").path) is FileResult.Success)
            val second: String? = fifoOrNull(File(nested, "sub"), "inner")
            assertTrue(files.writeAtomically(File(nested, "f").path, ByteArray(1)) is FileResult.Success)
            assertEquals(FileResult.Success(Unit), files.deleteRecursively(nested))
            assertEquals(null, files.metadata(nested, false))
            if (second != null) assertEquals(null, files.metadata(second, false))
            assertEquals(FileResult.Success(Unit), files.delete(fifo))
            assertEquals(null, files.metadata(fifo, false))
            assertEquals(FileResult.Success(Unit), files.delete(fifo))
        }

    @Test
    fun `deleteRecursively whose root is a pipe removes just the pipe`() = withFifo { _: File, fifo: String ->
        assertEquals(FileResult.Success(Unit), files.deleteRecursively(fifo))
        assertEquals(null, files.metadata(fifo, false))
    }

    @Test
    fun `move carries a pipe as itself and Fail never replaces`() = withFifo { root: File, fifo: String ->
        val moved: String = File(root, "moved").path
        assertEquals(FileResult.Success(Unit), files.move(fifo, moved))
        assertEquals(null, files.metadata(fifo, false))
        assertEquals(FileKind.Other, files.metadata(moved, false)?.kind)
        val occupied: String = File(root, "occupied").path
        assertTrue(files.writeAtomically(occupied, ByteArray(2)) is FileResult.Success)
        assertEquals(
            FileResult.Failure(FileError.AlreadyExists(occupied)),
            files.move(moved, occupied, MoveOptions(existing = ExistingFile.Fail)),
        )
        assertEquals(FileKind.Other, files.metadata(moved, false)?.kind)
        val third: String = File(root, "third").path
        assertEquals(FileResult.Success(Unit), files.move(moved, third, MoveOptions(existing = ExistingFile.Fail)))
        assertEquals(FileKind.Other, files.metadata(third, false)?.kind)
        assertEquals(null, files.metadata(moved, false))
    }

    @Test
    fun `a file moved or written onto a pipe replaces it and Fail refuses`() = withFifo { root: File, fifo: String ->
        assertEquals(
            FileResult.Failure(FileError.AlreadyExists(fifo)),
            files.writeAtomically(fifo, "x".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail)),
        )
        assertEquals(FileKind.Other, files.metadata(fifo, false)?.kind)
        val source: String = File(root, "source").path
        assertTrue(files.writeAtomically(source, "payload".encodeToByteArray()) is FileResult.Success)
        assertEquals(
            FileResult.Failure(FileError.AlreadyExists(fifo)),
            files.move(source, fifo, MoveOptions(existing = ExistingFile.Fail)),
        )
        assertEquals(FileResult.Success(Unit), files.move(source, fifo))
        assertEquals(FileKind.RegularFile, files.metadata(fifo, false)?.kind)
        assertEquals("payload", files.readBytes(fifo, 100).getOrNull()?.decodeToString())
    }

    @Test
    fun `writeAtomically over a pipe replaces it by a regular file and leaves no temporary file`() =
        withFifo { root: File, fifo: String ->
            assertEquals(FileResult.Success(Unit), files.writeAtomically(fifo, "now a file".encodeToByteArray()))
            assertEquals(FileKind.RegularFile, files.metadata(fifo, false)?.kind)
            assertEquals(listOf("pipe"), files.list(root.path).getOrNull())
        }
}
