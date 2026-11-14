@file:OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.mkfifo
import platform.posix.open
import platform.posix.usleep

/**
 * Round 2 of the breaker for unit G, iOS twin of the JVM FifoR2Test: `mkfifo` makes a named pipe whose open-for-read
 * blocks until a writer appears, so `readBytes` must answer `Unsupported` without opening it and every walk must
 * behave per contract around it. A watchdog coroutine opens every pipe read-write after three seconds so that a
 * regression fails an assertion instead of hanging the simulator test.
 */
class IosFifoR2Test {

    private val files: FileAccess = createFileAccess()

    private fun join(parent: String, child: String): String = parent.trimEnd('/') + "/" + child

    private fun makeFifo(directory: String, name: String): String {
        val path: String = join(directory, name)
        check(mkfifo(path, 0x1B6.convert()) == 0) { "mkfifo failed for $path" }
        return path
    }

    private fun pipesUnder(directory: String, found: MutableList<String>) {
        val names: List<String> = files.list(directory).getOrNull() ?: return
        for (name: String in names) {
            val child: String = join(directory, name)
            when (files.metadata(child, false)?.kind) {
                FileKind.Other -> found += child
                FileKind.Directory -> pipesUnder(child, found)
                else -> Unit
            }
        }
    }

    private fun withFifo(block: (root: String, fifo: String) -> Unit) {
        val root: String = TestEnv.newRoot()
        val watchdog = GlobalScope.launch(Dispatchers.Default) {
            delay(3_000)
            while (isActive) {
                val pipes: MutableList<String> = ArrayList()
                pipesUnder(root, pipes)
                for (pipe: String in pipes) {
                    val descriptor: Int = open(pipe, O_RDWR)
                    if (descriptor >= 0) {
                        delay(150)
                        close(descriptor)
                    }
                }
                delay(500)
            }
        }
        try {
            block(root, makeFifo(root, "pipe"))
        } finally {
            watchdog.cancel()
            TestEnv.deleteTree(root)
        }
    }

    private fun bytes(count: Int): ByteArray = ByteArray(count)

    @Test
    fun `a pipe is Other with size zero and readBytes is Unsupported at once through the path and through a link`() =
        withFifo { root: String, fifo: String ->
            assertEquals(FileKind.Other, files.metadata(fifo, false)?.kind)
            assertEquals(0L, files.metadata(fifo, false)?.sizeBytes)
            assertNotNull(files.metadata(fifo, false)?.lastModifiedMillis)
            assertEquals(FileResult.Failure(FileError.Unsupported(fifo)), files.readBytes(fifo, 100))
            assertEquals(FileResult.Failure(FileError.Unsupported(fifo)), files.readBytes(fifo, 0))
            val viaLink: String = join(root, "toPipe")
            assertTrue(TestEnv.createSymbolicLink(viaLink, fifo))
            assertEquals(FileKind.SymbolicLink, files.metadata(viaLink, false)?.kind)
            assertEquals(FileKind.Other, files.metadata(viaLink, true)?.kind)
            assertEquals(FileResult.Failure(FileError.Unsupported(viaLink)), files.readBytes(viaLink, 100))
        }

    @Test
    fun `list and createDirectories treat a pipe as a non-directory`() = withFifo { _: String, fifo: String ->
        assertEquals(FileResult.Failure(FileError.NotADirectory(fifo)), files.list(fifo))
        assertEquals(FileResult.Failure(FileError.NotADirectory(fifo)), files.createDirectories(fifo))
        val below: FileResult<Unit> = files.createDirectories(join(fifo, "child"))
        assertTrue(below is FileResult.Failure && below.error is FileError.NotADirectory, "below a pipe: $below")
        assertEquals(FileKind.Other, files.metadata(fifo, false)?.kind)
    }

    @Test
    fun `treeSize counts a pipe as zero alone and inside a directory`() = withFifo { root: String, fifo: String ->
        assertEquals(FileResult.Success(0L), files.treeSize(fifo))
        assertTrue(files.writeAtomically(join(root, "data"), bytes(37)) is FileResult.Success)
        assertEquals(FileResult.Success(37L), files.treeSize(root))
    }

    @Test
    fun `sweep never deletes a pipe however old and however well it matches`() = withFifo { root: String, fifo: String ->
        val old: String = join(root, "old.tmp")
        assertTrue(files.writeAtomically(old, bytes(3)) is FileResult.Success)
        assertTrue(TestEnv.setLastModified(old, 1_000L))
        usleep(30_000u)
        assertEquals(FileResult.Success(1), files.sweep(root, olderThanMillis = 0L) { true })
        assertEquals(FileKind.Other, files.metadata(fifo, false)?.kind)
        assertEquals(null, files.metadata(old, false))
    }

    @Test
    fun `deleteRecursively removes a tree holding pipes without opening them and delete removes one`() =
        withFifo { root: String, fifo: String ->
            val nested: String = join(root, "tree")
            assertTrue(files.createDirectories(join(nested, "sub")) is FileResult.Success)
            val second: String = makeFifo(join(nested, "sub"), "inner")
            assertTrue(files.writeAtomically(join(nested, "f"), bytes(1)) is FileResult.Success)
            assertEquals(FileResult.Success(Unit), files.deleteRecursively(nested))
            assertEquals(null, files.metadata(nested, false))
            assertEquals(null, files.metadata(second, false))
            assertEquals(FileResult.Success(Unit), files.delete(fifo))
            assertEquals(null, files.metadata(fifo, false))
            assertEquals(FileResult.Success(Unit), files.delete(fifo))
        }

    @Test
    fun `deleteRecursively whose root is a pipe removes just the pipe`() = withFifo { _: String, fifo: String ->
        assertEquals(FileResult.Success(Unit), files.deleteRecursively(fifo))
        assertEquals(null, files.metadata(fifo, false))
    }

    @Test
    fun `move carries a pipe as itself and Fail never replaces`() = withFifo { root: String, fifo: String ->
        val moved: String = join(root, "moved")
        assertEquals(FileResult.Success(Unit), files.move(fifo, moved))
        assertEquals(null, files.metadata(fifo, false))
        assertEquals(FileKind.Other, files.metadata(moved, false)?.kind)
        val occupied: String = join(root, "occupied")
        assertTrue(files.writeAtomically(occupied, bytes(2)) is FileResult.Success)
        assertEquals(
            FileResult.Failure(FileError.AlreadyExists(occupied)),
            files.move(moved, occupied, MoveOptions(existing = ExistingFile.Fail)),
        )
        assertEquals(FileKind.Other, files.metadata(moved, false)?.kind)
        val third: String = join(root, "third")
        assertEquals(FileResult.Success(Unit), files.move(moved, third, MoveOptions(existing = ExistingFile.Fail)))
        assertEquals(FileKind.Other, files.metadata(third, false)?.kind)
        assertEquals(null, files.metadata(moved, false))
    }

    @Test
    fun `a file moved or written onto a pipe replaces it and Fail refuses`() = withFifo { root: String, fifo: String ->
        assertEquals(
            FileResult.Failure(FileError.AlreadyExists(fifo)),
            files.writeAtomically(fifo, "x".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail)),
        )
        assertEquals(FileKind.Other, files.metadata(fifo, false)?.kind)
        val source: String = join(root, "source")
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
        withFifo { root: String, fifo: String ->
            assertEquals(FileResult.Success(Unit), files.writeAtomically(fifo, "now a file".encodeToByteArray()))
            assertEquals(FileKind.RegularFile, files.metadata(fifo, false)?.kind)
            assertEquals(listOf("pipe"), files.list(root).getOrNull())
        }
}
