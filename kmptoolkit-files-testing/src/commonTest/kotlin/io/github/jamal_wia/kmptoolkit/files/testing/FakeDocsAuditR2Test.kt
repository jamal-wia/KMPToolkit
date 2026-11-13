package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 2 of the breaker for unit G, step 3 (docs-sentence audit): the promises of docs/kmptoolkit-files/06-testing.md
 * about [FakeFileAccess] that no earlier test pinned, each derived from the sentence and not from the implementation.
 */
class FakeDocsAuditR2Test {

    private class Boom : Error("boom")

    @Test
    fun `the clock starts at zero and an unlimited fake reports null free space`() {
        val files: FakeFileAccess = FakeFileAccess()
        assertEquals(0L, files.nowMillis)
        assertNull(files.freeSpaceBytes)
        assertEquals(true, files.hardLinksSupported)
        files.createFile("/d/f", ByteArray(3))
        assertEquals(0L, files.metadata("/d/f", false)?.lastModifiedMillis)
        files.nowMillis = 5_000L
        files.createFile("/d/g", ByteArray(3))
        assertEquals(5_000L, files.metadata("/d/g", false)?.lastModifiedMillis)
    }

    @Test
    fun `setLastModified dates the entry itself and a link is not its target`() {
        val files: FakeFileAccess = FakeFileAccess()
        files.createFile("/d/target", ByteArray(1))
        files.createSymbolicLink("/d/link", "/d/target")
        files.setLastModified("/d/link", 777L)
        assertEquals(777L, files.metadata("/d/link", false)?.lastModifiedMillis)
        assertEquals(0L, files.metadata("/d/target", false)?.lastModifiedMillis)
        files.setLastModified("/d/target", 888L)
        assertEquals(888L, files.metadata("/d/link", true)?.lastModifiedMillis)
        assertEquals(777L, files.metadata("/d/link", false)?.lastModifiedMillis)
    }

    @Test
    fun `a link target is absolute or relative to the directory of the link and need not exist`() {
        val files: FakeFileAccess = FakeFileAccess()
        files.createDirectory("/d/sub")
        files.createFile("/d/x", "ex".encodeToByteArray())
        files.createFile("/y", "why".encodeToByteArray())
        files.createSymbolicLink("/d/rel", "x")
        files.createSymbolicLink("/d/sub/up", "../x")
        files.createSymbolicLink("/d/sub/root", "/y")
        files.createSymbolicLink("/d/chain", "rel")
        files.createSymbolicLink("/d/later", "not-yet")
        assertEquals("ex", files.readBytes("/d/rel", 10).getOrNull()?.decodeToString())
        assertEquals("ex", files.readBytes("/d/sub/up", 10).getOrNull()?.decodeToString())
        assertEquals("why", files.readBytes("/d/sub/root", 10).getOrNull()?.decodeToString())
        assertEquals("ex", files.readBytes("/d/chain", 10).getOrNull()?.decodeToString())
        assertEquals(FileKind.SymbolicLink, files.metadata("/d/later", false)?.kind)
        assertNull(files.metadata("/d/later", true))
        files.createFile("/d/not-yet", "now".encodeToByteArray())
        assertEquals("now", files.readBytes("/d/later", 10).getOrNull()?.decodeToString())
    }

    @Test
    fun `removing the last name of a file gives its space back and another name keeps it`() {
        val files: FakeFileAccess = FakeFileAccess()
        files.createDirectory("/d")
        files.freeSpaceBytes = 1_000L
        assertEquals(FileResult.Success(Unit), files.writeAtomically("/d/a", ByteArray(100)))
        val afterWrite: Long = assertNotNull(files.freeSpaceBytes)
        assertEquals(900L, afterWrite)
        // A second name of the same file (a failed source removal leaves both names): no extra space is used.
        files.failNext(FakeOperation.Remove, FileError.AccessDenied("/d/a"))
        assertEquals(FileResult.Success(Unit), files.move("/d/a", "/d/b", MoveOptions(existing = ExistingFile.Fail)))
        assertEquals(900L, files.freeSpaceBytes)
        assertEquals(FileResult.Success(Unit), files.delete("/d/a"))
        assertEquals(900L, files.freeSpaceBytes)
        assertEquals(FileResult.Success(Unit), files.delete("/d/b"))
        assertEquals(1_000L, files.freeSpaceBytes)
    }

    @Test
    fun `a write that does not fit is NoSpace changes nothing and one that fits exactly succeeds`() {
        val files: FakeFileAccess = FakeFileAccess()
        files.createDirectory("/d")
        files.freeSpaceBytes = 10L
        val tooBig: FileResult<Unit> = files.writeAtomically("/d/f", ByteArray(11))
        assertTrue(tooBig is FileResult.Failure && tooBig.error is FileError.NoSpace, "was $tooBig")
        assertEquals(10L, files.freeSpaceBytes)
        assertEquals(emptyMap(), files.entries().filterKeys { it != "/d" })
        assertEquals(FileResult.Success(Unit), files.writeAtomically("/d/f", ByteArray(10)))
        assertEquals(0L, files.freeSpaceBytes)
    }

    @Test
    fun `the path of an operation record is the source of a rename the new name of a link and the created path of a write`() {
        val files: FakeFileAccess = FakeFileAccess()
        files.createDirectory("/d")
        assertEquals(FileResult.Success(Unit), files.writeAtomically("/d/target", ByteArray(2)))
        val replace = files.operations.filter { it.operation != FakeOperation.Metadata }
        val temp: String = replace.first { it.operation == FakeOperation.CreateNew }.path
        assertTrue(FileNames.isTempName(temp.substringAfterLast('/')), "temp was $temp")
        for (operation: FakeOperation in listOf(FakeOperation.Write, FakeOperation.Sync, FakeOperation.Close, FakeOperation.Rename)) {
            assertEquals(temp, replace.first { it.operation == operation }.path, "path of $operation")
        }
        assertEquals("/d/target", replace.first { it.operation == FakeOperation.Rename }.secondPath)

        val noReplace = FakeFileAccess()
        noReplace.createDirectory("/d")
        assertEquals(
            FileResult.Success(Unit),
            noReplace.writeAtomically("/d/new", ByteArray(2), WriteOptions(existing = ExistingFile.Fail)),
        )
        val link = noReplace.operations.first { it.operation == FakeOperation.Link }
        assertEquals("/d/new", link.path, "a Link record carries the new name")
        val created = noReplace.operations.first { it.operation == FakeOperation.CreateNew }.path
        assertEquals(created, noReplace.operations.first { it.operation == FakeOperation.Write }.path)
    }

    @Test
    fun `a fault filter sees the source of a rename and the new name of a link`() {
        val files: FakeFileAccess = FakeFileAccess()
        files.createDirectory("/d")
        files.failNext(FakeOperation.Link, FileError.AccessDenied("/d/new"), path = { it == "/d/new" })
        val refused: FileResult<Unit> = files.writeAtomically("/d/new", ByteArray(1), WriteOptions(existing = ExistingFile.Fail))
        assertTrue(refused is FileResult.Failure && refused.error is FileError.AccessDenied, "was $refused")
        assertEquals(setOf("/d"), files.entries().keys, "the failed link left no temporary file")
        files.failNext(FakeOperation.Rename, FileError.AccessDenied("x"), path = { it.endsWith(".tmp") })
        val renameRefused: FileResult<Unit> = files.writeAtomically("/d/other", ByteArray(1))
        assertTrue(renameRefused is FileResult.Failure && renameRefused.error is FileError.AccessDenied, "was $renameRefused")
    }

    @Test
    fun `an Error thrown by beforeOperation propagates and an exception becomes Io with the cause`() {
        val files: FakeFileAccess = FakeFileAccess()
        files.createDirectory("/d")
        files.beforeOperation = { operation: FakeOperation, _: String -> if (operation == FakeOperation.CreateNew) throw Boom() }
        assertFailsWith<Boom> { files.writeAtomically("/d/f", ByteArray(1)) }
        val cause: IllegalStateException = IllegalStateException("hook")
        files.beforeOperation = { operation: FakeOperation, _: String -> if (operation == FakeOperation.CreateNew) throw cause }
        val failed: FileResult<Unit> = files.writeAtomically("/d/f", ByteArray(1))
        assertTrue(failed is FileResult.Failure, "was $failed")
        val error: FileError.Io = failed.error as FileError.Io
        assertEquals(cause, error.cause)
    }

    @Test
    fun `after a crash every later call throws SimulatedCrash while entries and operations keep working`() {
        val files: FakeFileAccess = FakeFileAccess()
        files.createDirectory("/d")
        files.createFile("/d/old", "old".encodeToByteArray())
        files.crashAfter(FakeOperation.Rename)
        assertFailsWith<SimulatedCrash> { files.writeAtomically("/d/old", "new".encodeToByteArray()) }
        assertFailsWith<SimulatedCrash> { files.metadata("/d/old", false) }
        assertFailsWith<SimulatedCrash> { files.list("/d") }
        assertFailsWith<SimulatedCrash> { files.readBytes("/d/old", 10) }
        assertFailsWith<SimulatedCrash> { files.delete("/d/old") }
        assertFailsWith<SimulatedCrash> { files.createDirectories("/e") }
        assertNotNull(files.entries()["/d/old"])
        assertEquals(FakeEntry.File("new".encodeToByteArray()), files.entries()["/d/old"])
        assertTrue(files.operations.isNotEmpty())
        val after: FakeFileAccess = files.restartAfterCrash(CrashKind.ProcessDeath)
        assertTrue(after.operations.isEmpty(), "the log does not carry over: ${after.operations}")
        assertEquals("new", after.readBytes("/d/old", 10).getOrNull()?.decodeToString())
    }

    @Test
    fun `a case-insensitive fake folds dotless i and the Kelvin sign and a case-sensitive one does not`() {
        val insensitive: FakeFileAccess = FakeFileAccess(caseSensitive = false)
        insensitive.createFile("/d/I", "upper".encodeToByteArray())
        assertEquals("upper", insensitive.readBytes("/d/ı", 10).getOrNull()?.decodeToString())
        insensitive.createFile("/d/k", "kay".encodeToByteArray())
        assertEquals("kay", insensitive.readBytes("/d/K", 10).getOrNull()?.decodeToString())
        assertEquals(listOf("I", "k"), insensitive.list("/d").getOrNull())
        val sensitive: FakeFileAccess = FakeFileAccess()
        sensitive.createFile("/d/I", "upper".encodeToByteArray())
        sensitive.createFile("/d/k", "kay".encodeToByteArray())
        assertTrue(sensitive.readBytes("/d/ı", 10) is FileResult.Failure)
        assertTrue(sensitive.readBytes("/d/K", 10) is FileResult.Failure)
        assertTrue(sensitive.readBytes("/d/i", 10) is FileResult.Failure)
    }

    @Test
    fun `markAllDurable makes the current state the baseline for a power loss with reverted entries`() {
        val files: FakeFileAccess = FakeFileAccess()
        files.createDirectory("/d")
        files.writeAtomically("/d/made-by-call", "kept".encodeToByteArray(), WriteOptions(durability = io.github.jamal_wia.kmptoolkit.files.Durability.Buffered))
        files.markAllDurable()
        files.crashAfter(FakeOperation.Metadata, occurrence = 1)
        assertFailsWith<SimulatedCrash> { files.metadata("/d", false) }
        val after: FakeFileAccess = files.restartAfterCrash(CrashKind.PowerLoss, revertUnsyncedDirectoryEntries = true)
        assertEquals(FileKind.RegularFile, after.metadata("/d/made-by-call", false)?.kind)
        assertEquals("kept", after.readBytes("/d/made-by-call", 10).getOrNull()?.decodeToString())
    }
}
