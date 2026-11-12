package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.CancellationCheck
import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.errorOrNull
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import io.github.jamal_wia.kmptoolkit.files.isSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-1..F-24 of the spec: what the published fake does to a write, a move and a delete at every crash point. */
class FakeCrashAndFaultTest {

    private fun fakeWith(vararg files: Pair<String, String>, caseSensitive: Boolean = true): FakeFileAccess {
        val fake: FakeFileAccess = FakeFileAccess(caseSensitive = caseSensitive)
        fake.createDirectory("/d")
        for ((path: String, content: String) in files) fake.createFile(path, content.encodeToByteArray())
        return fake
    }

    private fun FakeFileAccess.text(path: String): String? = readBytes(path, 1L shl 20).getOrNull()?.decodeToString()

    private fun FakeFileAccess.tempNames(directory: String = "/d"): List<String> {
        // path-hygiene-ignore: PH4 the fake file system is POSIX on every host, so these are logical paths
        val prefix: String = "$directory/"
        // path-hygiene-ignore: PH3 the same: a logical path of the fake, taken apart on '/'
        return entries().keys.filter { it.startsWith(prefix) }.map { it.substringAfterLast('/') }.filter { FileNames.isTempName(it) }
    }

    private fun bytes(text: String): ByteArray = text.encodeToByteArray()

    // ---- F-1, F-2: process death ------------------------------------------------------------------

    @Test
    fun `a write that dies after CreateNew Write Sync or Close leaves the old content and exactly one temp file`() {
        for (operation: FakeOperation in listOf(FakeOperation.CreateNew, FakeOperation.Write, FakeOperation.Sync, FakeOperation.Close)) {
            val fake: FakeFileAccess = fakeWith("/d/f" to "old")
            fake.crashAfter(operation)
            assertFailsWith<SimulatedCrash>("crash after $operation") { fake.writeAtomically("/d/f", bytes("new")) }
            val restarted: FakeFileAccess = fake.restartAfterCrash()
            assertEquals("old", restarted.text("/d/f"), "the target is the old content after a crash after $operation")
            assertEquals(1, restarted.tempNames().size, "exactly one temp file after $operation: ${restarted.entries().keys}")
        }
    }

    @Test
    fun `a write that dies right after the rename has the new content and no temp file`() {
        val fake: FakeFileAccess = fakeWith("/d/f" to "old")
        fake.crashAfter(FakeOperation.Rename)
        assertFailsWith<SimulatedCrash> { fake.writeAtomically("/d/f", bytes("new")) }
        val restarted: FakeFileAccess = fake.restartAfterCrash()
        assertEquals("new", restarted.text("/d/f"))
        assertEquals(emptyList(), restarted.tempNames())
    }

    // ---- F-3, F-4, F-5: power loss ----------------------------------------------------------------

    @Test
    fun `power loss after a Buffered write leaves an EMPTY target which is why Synced exists`() {
        val fake: FakeFileAccess = fakeWith("/d/f" to "old")
        fake.crashAfter(FakeOperation.Rename)
        assertFailsWith<SimulatedCrash> { fake.writeAtomically("/d/f", bytes("new"), WriteOptions(durability = Durability.Buffered)) }
        val restarted: FakeFileAccess = fake.restartAfterCrash(CrashKind.PowerLoss)
        assertEquals("", restarted.text("/d/f"), "the new name points at a file whose content never reached the disk")
        assertEquals(0L, restarted.metadata("/d/f")?.sizeBytes)
    }

    @Test
    fun `power loss after a Synced write has the new content or with reverted entries the old one never a torn file`() {
        val fake: FakeFileAccess = fakeWith("/d/f" to "old")
        fake.crashAfter(FakeOperation.Rename)
        assertFailsWith<SimulatedCrash> { fake.writeAtomically("/d/f", bytes("new"), WriteOptions(durability = Durability.Synced)) }
        assertEquals("new", fake.restartAfterCrash(CrashKind.PowerLoss).text("/d/f"))
        val reverted: FakeFileAccess = fake.restartAfterCrash(CrashKind.PowerLoss, revertUnsyncedDirectoryEntries = true)
        assertEquals("old", reverted.text("/d/f"), "the rename was not made durable, so the old name and its whole content are back")
        assertEquals(emptyList(), reverted.tempNames(), "the unsynced temp name is gone too")
    }

    @Test
    fun `power loss after SyncedWithDirectory returns the new content even with every unsynced entry reverted`() {
        val fake: FakeFileAccess = fakeWith("/d/f" to "old")
        fake.crashAfter(FakeOperation.SyncDirectory)
        assertFailsWith<SimulatedCrash> { fake.writeAtomically("/d/f", bytes("new"), WriteOptions(durability = Durability.SyncedWithDirectory)) }
        val restarted: FakeFileAccess = fake.restartAfterCrash(CrashKind.PowerLoss, revertUnsyncedDirectoryEntries = true)
        assertEquals("new", restarted.text("/d/f"))
        assertEquals(emptyList(), restarted.tempNames())
    }

    @Test
    fun `a new file that was never made durable disappears with reverted entries and keeps synced content otherwise`() {
        val fake: FakeFileAccess = fakeWith()
        assertTrue(fake.writeAtomically("/d/new", bytes("kept"), WriteOptions(durability = Durability.Synced)).isSuccess)
        fake.crashAfter(FakeOperation.Metadata)
        assertFailsWith<SimulatedCrash> { fake.metadata("/d/new") }
        assertEquals("kept", fake.restartAfterCrash(CrashKind.PowerLoss).text("/d/new"))
        assertNull(fake.restartAfterCrash(CrashKind.PowerLoss, revertUnsyncedDirectoryEntries = true).metadata("/d/new"))
    }

    @Test
    fun `a process death keeps everything also what was never synced`() {
        val fake: FakeFileAccess = fakeWith()
        fake.crashAfter(FakeOperation.Rename)
        assertFailsWith<SimulatedCrash> { fake.writeAtomically("/d/f", bytes("unsynced"), WriteOptions(durability = Durability.Buffered)) }
        assertEquals("unsynced", fake.restartAfterCrash(CrashKind.ProcessDeath).text("/d/f"))
    }

    // ---- F-16..F-19: directories created by the write ---------------------------------------------

    private fun FakeFileAccess.syncedDirectories(): List<String> =
        operations.filter { it.operation == FakeOperation.SyncDirectory }.map { it.path }

    @Test
    fun `a SyncedWithDirectory write into a new directory survives power loss with every unsynced entry reverted`() {
        val fake: FakeFileAccess = fakeWith()
        val options: WriteOptions = WriteOptions(durability = Durability.SyncedWithDirectory, createParentDirectories = true)
        assertTrue(fake.writeAtomically("/d/receipts/r1", bytes("receipt"), options).isSuccess)
        fake.crashAfter(FakeOperation.Metadata)
        assertFailsWith<SimulatedCrash> { fake.metadata("/d") }
        val restarted: FakeFileAccess = fake.restartAfterCrash(CrashKind.PowerLoss, revertUnsyncedDirectoryEntries = true)
        assertEquals("receipt", restarted.text("/d/receipts/r1"), "the new directory and the record are both durable")
    }

    @Test
    fun `a write that dies after the rename but before the directory syncs may lose the record but never tears it`() {
        val fake: FakeFileAccess = fakeWith()
        fake.crashAfter(FakeOperation.Rename)
        val options: WriteOptions = WriteOptions(durability = Durability.SyncedWithDirectory, createParentDirectories = true)
        assertFailsWith<SimulatedCrash> { fake.writeAtomically("/d/receipts/r1", bytes("receipt"), options) }
        val restarted: FakeFileAccess = fake.restartAfterCrash(CrashKind.PowerLoss, revertUnsyncedDirectoryEntries = true)
        val content: String? = restarted.text("/d/receipts/r1")
        assertTrue(content == null || content == "receipt", "absent or whole, was '$content'")
        assertEquals(emptyList(), restarted.tempNames("/d/receipts"))
    }

    @Test
    fun `the directory syncs of a SyncedWithDirectory write are one for an existing directory and one per created level deepest first`() {
        val existing: FakeFileAccess = fakeWith()
        assertTrue(existing.writeAtomically("/d/f", bytes("v"), WriteOptions(durability = Durability.SyncedWithDirectory)).isSuccess)
        assertEquals(listOf("/d"), existing.syncedDirectories())

        val created: FakeFileAccess = fakeWith()
        val options: WriteOptions = WriteOptions(durability = Durability.SyncedWithDirectory, createParentDirectories = true)
        assertTrue(created.writeAtomically("/d/a/b/f", bytes("v"), options).isSuccess)
        assertEquals(listOf("/d/a", "/d", "/d/a/b"), created.syncedDirectories(), "the parents of the new levels, deepest first, then the target's parent")
    }

    @Test
    fun `Synced and Buffered writes with createParentDirectories issue no directory sync`() {
        for (durability: Durability in listOf(Durability.Synced, Durability.Buffered)) {
            val fake: FakeFileAccess = fakeWith()
            assertTrue(fake.writeAtomically("/d/a/b/f", bytes("v"), WriteOptions(durability = durability, createParentDirectories = true)).isSuccess)
            assertEquals(emptyList(), fake.syncedDirectories(), "$durability")
        }
    }

    @Test
    fun `a failing sync of a created level reports Io with the target already new`() {
        val fake: FakeFileAccess = fakeWith()
        fake.failNext(FakeOperation.SyncDirectory, FileError.Io("/d"), path = { it == "/d" })
        val result: FileResult<Unit> = fake.writeAtomically("/d/a/f", bytes("v"), WriteOptions(durability = Durability.SyncedWithDirectory, createParentDirectories = true))
        assertTrue(result.errorOrNull() is FileError.Io, "was $result")
        assertEquals("v", fake.text("/d/a/f"))
    }

    // ---- F-20..F-24: directories that exist before the write --------------------------------------

    private val syncedWrite: WriteOptions = WriteOptions(durability = Durability.SyncedWithDirectory)

    private fun FakeFileAccess.powerLossAndRevert(): FakeFileAccess {
        crashAfter(FakeOperation.Metadata)
        assertFailsWith<SimulatedCrash> { metadata("/") }
        return restartAfterCrash(CrashKind.PowerLoss, revertUnsyncedDirectoryEntries = true)
    }

    @Test
    fun `F-20 a directory made without durable is lost by a power loss even when the record write is SyncedWithDirectory`() {
        val fake: FakeFileAccess = FakeFileAccess()
        assertTrue(fake.createDirectories("/files/receipts").isSuccess)
        assertTrue(fake.writeAtomically("/files/receipts/r1", bytes("receipt"), syncedWrite).isSuccess)
        assertNull(fake.powerLossAndRevert().text("/files/receipts/r1"), "the documented condition: the directories were not durable")
    }

    @Test
    fun `F-21 a directory made with durable keeps a SyncedWithDirectory record through a power loss`() {
        val fake: FakeFileAccess = FakeFileAccess()
        assertTrue(fake.createDirectories("/files/receipts", durable = true).isSuccess)
        assertTrue(fake.writeAtomically("/files/receipts/r1", bytes("receipt"), syncedWrite).isSuccess)
        assertEquals("receipt", fake.powerLossAndRevert().text("/files/receipts/r1"))
    }

    @Test
    fun `F-23 durable creation syncs nothing for an existing directory and the created levels deepest first`() {
        val existing: FakeFileAccess = fakeWith()
        assertTrue(existing.createDirectories("/d", durable = true).isSuccess)
        assertEquals(emptyList(), existing.syncedDirectories())

        val created: FakeFileAccess = fakeWith()
        assertTrue(created.createDirectories("/d/a/b", durable = true).isSuccess)
        assertEquals(listOf("/d/a", "/d"), created.syncedDirectories(), "the parents of the new levels, deepest first")
    }

    @Test
    fun `F-23 two creators of one new directory both sync its parent and the directory survives a power loss`() {
        val fake: FakeFileAccess = fakeWith()
        var racing: Boolean = false
        fake.beforeOperation = { operation: FakeOperation, path: String ->
            if (operation == FakeOperation.CreateDirectory && path == "/d/new" && !racing) {
                racing = true
                assertTrue(fake.createDirectories("/d/new", durable = true).isSuccess, "the winner")
            }
        }
        assertTrue(fake.createDirectories("/d/new", durable = true).isSuccess, "the loser also succeeds")
        assertEquals(listOf("/d", "/d"), fake.syncedDirectories(), "each creator synced the parent of the directory")
        fake.beforeOperation = null
        assertTrue(fake.writeAtomically("/d/new/f", bytes("v"), syncedWrite).isSuccess)
        assertEquals("v", fake.powerLossAndRevert().text("/d/new/f"))
    }

    @Test
    fun `F-24 a write that loses the race to create its parent directory still syncs that directory into its parent`() {
        val fake: FakeFileAccess = fakeWith()
        var racing: Boolean = false
        fake.beforeOperation = { operation: FakeOperation, path: String ->
            if (operation == FakeOperation.CreateDirectory && path == "/d/new" && !racing) {
                racing = true
                // The other thread wins the race without making the directory durable.
                assertTrue(fake.createDirectories("/d/new").isSuccess)
            }
        }
        val options: WriteOptions = WriteOptions(durability = Durability.SyncedWithDirectory, createParentDirectories = true)
        assertTrue(fake.writeAtomically("/d/new/f", bytes("v"), options).isSuccess)
        fake.beforeOperation = null
        assertEquals(listOf("/d", "/d/new"), fake.syncedDirectories(), "the lost level counts as created: /d is synced")
        assertEquals("v", fake.powerLossAndRevert().text("/d/new/f"))
    }

    // ---- F-6, F-11, F-13: no-replace ---------------------------------------------------------------

    @Test
    fun `a no-replace write that dies after the link has the new target and a temp file and the next write is AlreadyExists`() {
        val fake: FakeFileAccess = fakeWith()
        fake.crashAfter(FakeOperation.Link)
        assertFailsWith<SimulatedCrash> { fake.writeAtomically("/d/f", bytes("new"), WriteOptions(existing = ExistingFile.Fail)) }
        val restarted: FakeFileAccess = fake.restartAfterCrash()
        assertEquals("new", restarted.text("/d/f"))
        assertEquals(1, restarted.tempNames().size)
        val again: FileResult<Unit> = restarted.writeAtomically("/d/f", bytes("x"), WriteOptions(existing = ExistingFile.Fail))
        assertEquals(FileError.AlreadyExists("/d/f"), again.errorOrNull())
    }

    @Test
    fun `a no-replace move whose source removal fails is still Success and both names are present`() {
        val fake: FakeFileAccess = fakeWith("/d/a" to "A")
        fake.failNext(FakeOperation.Remove, FileError.Io("/d/a"), path = { it == "/d/a" })
        assertTrue(fake.move("/d/a", "/d/b", MoveOptions(existing = ExistingFile.Fail)).isSuccess)
        assertEquals("A", fake.text("/d/a"))
        assertEquals("A", fake.text("/d/b"))
    }

    @Test
    fun `without hard links a no-replace write or move is Unsupported and changes nothing`() {
        val fake: FakeFileAccess = fakeWith("/d/a" to "A")
        fake.hardLinksSupported = false
        val written: FileResult<Unit> = fake.writeAtomically("/d/f", bytes("x"), WriteOptions(existing = ExistingFile.Fail))
        assertEquals(FileError.Unsupported("/d/f"), written.errorOrNull())
        assertNull(fake.metadata("/d/f"))
        assertEquals(emptyList(), fake.tempNames(), "the temp file is removed")
        val moved: FileResult<Unit> = fake.move("/d/a", "/d/b", MoveOptions(existing = ExistingFile.Fail))
        assertTrue(moved.errorOrNull() is FileError.Unsupported)
        assertEquals("A", fake.text("/d/a"))
        assertNull(fake.metadata("/d/b"))
        assertTrue(fake.writeAtomically("/d/g", bytes("plain")).isSuccess, "a replacing write needs no link")
    }

    // ---- F-7, F-8, F-9: injected failures ---------------------------------------------------------

    @Test
    fun `NoSpace on the rename or on the write fails the call keeps the old target and leaves no temp`() {
        for (operation: FakeOperation in listOf(FakeOperation.Rename, FakeOperation.Write)) {
            val fake: FakeFileAccess = fakeWith("/d/f" to "old")
            fake.failNext(operation, FileError.NoSpace("/d"))
            val result: FileResult<Unit> = fake.writeAtomically("/d/f", bytes("new"))
            assertTrue(result.errorOrNull() is FileError.NoSpace, "$operation: $result")
            assertEquals("old", fake.text("/d/f"))
            assertEquals(emptyList(), fake.tempNames())
        }
    }

    @Test
    fun `a failing final directory sync reports Io while the target already holds the new bytes`() {
        val fake: FakeFileAccess = fakeWith("/d/f" to "old")
        fake.failNext(FakeOperation.SyncDirectory, FileError.Io("/d"))
        val result: FileResult<Unit> = fake.writeAtomically("/d/f", bytes("new"), WriteOptions(durability = Durability.SyncedWithDirectory))
        assertTrue(result.errorOrNull() is FileError.Io, "was $result")
        assertEquals("new", fake.text("/d/f"))
        assertEquals(emptyList(), fake.tempNames())
    }

    @Test
    fun `a clash on the temp name is retried three times but not seventeen`() {
        val fake: FakeFileAccess = fakeWith()
        fake.failNext(FakeOperation.CreateNew, FileError.AlreadyExists("/d/x"), count = 3)
        assertTrue(fake.writeAtomically("/d/f", bytes("v")).isSuccess)
        val stubborn: FakeFileAccess = fakeWith()
        stubborn.failNext(FakeOperation.CreateNew, FileError.AlreadyExists("/d/x"), count = 17)
        val result: FileResult<Unit> = stubborn.writeAtomically("/d/f", bytes("v"))
        assertTrue(result.errorOrNull() is FileError.Io, "was $result")
        assertNull(stubborn.metadata("/d/f"))
    }

    // ---- F-10: moves across volumes ---------------------------------------------------------------

    private val crossDevice: FileError = FileError.CrossDevice("/d")

    @Test
    fun `a rename refused with CrossDevice makes move copy the bytes sync them and remove the source`() {
        val fake: FakeFileAccess = fakeWith("/d/a" to "payload")
        fake.failNext(FakeOperation.Rename, crossDevice, path = { it == "/d/a" })
        assertTrue(fake.move("/d/a", "/d/b").isSuccess)
        assertEquals("payload", fake.text("/d/b"))
        assertNull(fake.metadata("/d/a"))
        assertEquals(emptyList(), fake.tempNames())
        assertTrue(FakeOperation.Sync in fake.operations.map { it.operation }, "the copy is synced before it is renamed into place")
    }

    @Test
    fun `a cross-volume move that dies after the temp file was renamed into place keeps both names`() {
        val fake: FakeFileAccess = fakeWith("/d/a" to "payload")
        fake.failNext(FakeOperation.Rename, crossDevice, path = { it == "/d/a" })
        fake.crashAfter(FakeOperation.Rename)
        assertFailsWith<SimulatedCrash> { fake.move("/d/a", "/d/b") }
        val restarted: FakeFileAccess = fake.restartAfterCrash()
        assertEquals("payload", restarted.text("/d/a"), "the source is still there: nothing is lost")
        assertEquals("payload", restarted.text("/d/b"))
    }

    @Test
    fun `a move cancelled after two chunks of a one MiB file is Cancelled with the source intact and no target or temp`() {
        val content: ByteArray = ByteArray(1 shl 20) { (it % 251).toByte() }
        val fake: FakeFileAccess = fakeWith()
        fake.createFile("/d/a", content)
        fake.failNext(FakeOperation.Rename, crossDevice, path = { it == "/d/a" })
        var checks: Int = 0
        val result: FileResult<Unit> = fake.move("/d/a", "/d/b", cancellation = CancellationCheck { ++checks > 2 })
        assertEquals(FileError.Cancelled, result.errorOrNull())
        assertTrue(fake.readBytes("/d/a", 2L shl 20).getOrNull()!!.contentEquals(content), "the source is intact")
        assertNull(fake.metadata("/d/b"))
        assertEquals(emptyList(), fake.tempNames())
    }

    @Test
    fun `without acrossVolumes a refused rename is CrossDevice and nothing changes`() {
        val fake: FakeFileAccess = fakeWith("/d/a" to "payload")
        fake.failNext(FakeOperation.Rename, crossDevice, path = { it == "/d/a" })
        val result: FileResult<Unit> = fake.move("/d/a", "/d/b", MoveOptions(acrossVolumes = false))
        assertTrue(result.errorOrNull() is FileError.CrossDevice, "was $result")
        assertEquals("payload", fake.text("/d/a"))
        assertNull(fake.metadata("/d/b"))
        assertEquals(emptyList(), fake.tempNames())
    }

    // ---- F-12: deleteRecursively -------------------------------------------------------------------

    private fun FakeFileAccess.seedTree() {
        for (name: String in listOf("a", "b", "c")) createFile("/d/t/$name", bytes(name))
        createFile("/d/t/sub/leaf", bytes("leaf"))
        createFile("/d/t/sub/other", bytes("other"))
    }

    @Test
    fun `entries that vanish during the walk count as removed`() {
        val fake: FakeFileAccess = fakeWith()
        fake.seedTree()
        var fired: Boolean = false
        fake.beforeOperation = { operation: FakeOperation, path: String ->
            if (!fired && operation == FakeOperation.Remove) {
                fired = true
                fake.beforeOperation = null
                for (sibling: String in listOf("a", "b", "c")) if ("/d/t/$sibling" != path) fake.delete("/d/t/$sibling")
            }
        }
        assertTrue(fake.deleteRecursively("/d/t").isSuccess)
        assertNull(fake.metadata("/d/t"))
    }

    @Test
    fun `one entry that cannot be removed fails the delete with its error after every other entry was removed`() {
        val fake: FakeFileAccess = fakeWith()
        fake.seedTree()
        fake.failNext(FakeOperation.Remove, FileError.AccessDenied("/d/t/sub/leaf"), count = 100, path = { it == "/d/t/sub/leaf" })
        val result: FileResult<Unit> = fake.deleteRecursively("/d/t")
        assertEquals(FileError.AccessDenied("/d/t/sub/leaf"), result.errorOrNull())
        assertEquals(setOf("/d", "/d/t", "/d/t/sub", "/d/t/sub/leaf"), fake.entries().keys, "everything else is gone")
    }

    @Test
    fun `a delete cancelled after some entries is Cancelled leaves the rest and a rerun finishes`() {
        val fake: FakeFileAccess = fakeWith()
        fake.seedTree()
        val before: Int = fake.entries().size
        var checks: Int = 0
        val result: FileResult<Unit> = fake.deleteRecursively("/d/t", CancellationCheck { ++checks > 3 })
        assertEquals(FileError.Cancelled, result.errorOrNull())
        val after: Int = fake.entries().size
        assertTrue(after in 1 until before, "some entries were removed and some remain ($after of $before)")
        assertTrue(fake.deleteRecursively("/d/t").isSuccess)
        assertNull(fake.metadata("/d/t"))
    }

    // ---- F-14: space and case ----------------------------------------------------------------------

    @Test
    fun `a write beyond the free space is NoSpace and leaves no temp file one that fits uses the space`() {
        val fake: FakeFileAccess = fakeWith("/d/f" to "old")
        fake.freeSpaceBytes = 10
        val tooBig: FileResult<Unit> = fake.writeAtomically("/d/f", ByteArray(11))
        assertTrue(tooBig.errorOrNull() is FileError.NoSpace, "was $tooBig")
        assertEquals("old", fake.text("/d/f"))
        assertEquals(emptyList(), fake.tempNames())
        assertEquals(10L, fake.freeSpaceBytes(("/d")), "the failed write used nothing")
        assertTrue(fake.writeAtomically("/d/g", ByteArray(10)).isSuccess, "exactly the free space fits")
        assertEquals(0L, fake.freeSpaceBytes)
        assertTrue(fake.writeAtomically("/d/h", ByteArray(1)).errorOrNull() is FileError.NoSpace)
        assertTrue(fake.delete("/d/g").isSuccess)
        assertEquals(10L, fake.freeSpaceBytes, "removing the last name gives the bytes back")
    }

    @Test
    fun `a null free space is unlimited`() {
        val fake: FakeFileAccess = fakeWith()
        assertNull(fake.freeSpaceBytes)
        assertNull(fake.freeSpaceBytes("/d/missing/deeper"))
        assertTrue(fake.writeAtomically("/d/big", ByteArray(1 shl 20)).isSuccess)
    }

    @Test
    fun `in a case-insensitive fake two spellings are one entry and the spelling of creation is listed`() {
        val fake: FakeFileAccess = fakeWith(caseSensitive = false)
        assertTrue(fake.writeAtomically("/d/A", bytes("first")).isSuccess)
        assertTrue(fake.writeAtomically("/d/a", bytes("second")).isSuccess)
        assertEquals(listOf("A"), fake.list("/d").getOrNull())
        assertEquals("second", fake.text("/d/A"))
        assertEquals("second", fake.text("/d/a"))
        assertEquals(setOf("/d", "/d/A"), fake.entries().keys)
        // A move onto another spelling of itself changes the spelling.
        assertTrue(fake.move("/d/A", "/d/a").isSuccess)
        assertEquals(listOf("a"), fake.list("/d").getOrNull())
    }

    @Test
    fun `a case-sensitive fake keeps A and a apart`() {
        val fake: FakeFileAccess = fakeWith("/d/A" to "1", "/d/a" to "2")
        assertEquals(listOf("A", "a"), fake.list("/d").getOrNull())
    }

    @Test
    fun `a name longer than 255 UTF-8 bytes is NameTooLong as on ext4`() {
        val fake: FakeFileAccess = fakeWith()
        val result: FileResult<Unit> = fake.writeAtomically("/d/" + "n".repeat(256), ByteArray(1))
        assertEquals(FileError.NameTooLong("/d/" + "n".repeat(256)), result.errorOrNull())
        assertEquals(emptyList(), fake.tempNames())
        assertTrue(fake.writeAtomically("/d/" + "n".repeat(255), ByteArray(1)).isSuccess)
    }

    // ---- sweep with a clock the test owns -----------------------------------------------------------

    @Test
    fun `sweep deletes strictly older than the age on the fake's own clock and keeps newer future and undated entries and directories`() {
        val fake: FakeFileAccess = fakeWith()
        fake.nowMillis = 10_000
        fake.createFile("/d/old", bytes("o"))
        fake.createFile("/d/edge", bytes("e"))
        fake.createFile("/d/future", bytes("f"))
        fake.createDirectory("/d/dir")
        fake.createSymbolicLink("/d/oldlink", "/d/old")
        fake.setLastModified("/d/old", 1_000)
        fake.setLastModified("/d/edge", 7_000)
        fake.setLastModified("/d/future", 20_000)
        fake.setLastModified("/d/dir", 0)
        fake.setLastModified("/d/oldlink", 1_000)
        assertEquals(FileResult.Success(2), fake.sweep("/d", 3_000) { it != "future" })
        assertEquals(setOf("/d", "/d/edge", "/d/future", "/d/dir"), fake.entries().keys, "the old file and the old link went, the boundary stayed")
        assertEquals(FileResult.Success(1), fake.sweep("/d", 2_999) { it == "edge" })
        assertEquals(setOf("/d", "/d/future", "/d/dir"), fake.entries().keys)
    }

    // ---- F-15: hook semantics ------------------------------------------------------------------------

    @Test
    fun `failNext applies only to matching paths and only count times`() {
        val fake: FakeFileAccess = fakeWith("/d/a" to "A", "/d/b" to "B")
        fake.failNext(FakeOperation.OpenForRead, FileError.AccessDenied("/d/a"), count = 2, path = { it == "/d/a" })
        assertEquals(FileError.AccessDenied("/d/a"), fake.readBytes("/d/a", 10).errorOrNull())
        assertEquals("B", fake.text("/d/b"), "another path is not affected")
        assertEquals(FileError.AccessDenied("/d/a"), fake.readBytes("/d/a", 10).errorOrNull())
        assertEquals("A", fake.text("/d/a"), "the third read is past the count")
    }

    @Test
    fun `a failed operation has no effect and is still logged`() {
        val fake: FakeFileAccess = fakeWith("/d/a" to "A")
        fake.failNext(FakeOperation.Remove, FileError.AccessDenied("/d/a"))
        assertEquals(FileError.AccessDenied("/d/a"), fake.delete("/d/a").errorOrNull())
        assertEquals("A", fake.text("/d/a"))
        assertTrue(FakeOperationRecord(FakeOperation.Remove, "/d/a", null) in fake.operations)
    }

    @Test
    fun `crashAfter counts successful operations of its kind and freezes the state`() {
        val fake: FakeFileAccess = fakeWith("/d/a" to "A")
        fake.crashAfter(FakeOperation.Metadata, occurrence = 2)
        fake.metadata("/d/a")
        assertFailsWith<SimulatedCrash> { fake.metadata("/d/a") }
        assertFailsWith<SimulatedCrash> { fake.readBytes("/d/a", 10) }
        assertFailsWith<SimulatedCrash> { fake.delete("/d/a") }
        assertFailsWith<SimulatedCrash> { fake.list("/d") }
        assertEquals(setOf("/d", "/d/a"), fake.entries().keys, "inspection still works and shows the frozen state")
        assertTrue(fake.operations.isNotEmpty())
    }

    @Test
    fun `a failed operation does not count towards crashAfter`() {
        val fake: FakeFileAccess = fakeWith()
        fake.failNext(FakeOperation.Metadata, FileError.Io("/d"))
        fake.crashAfter(FakeOperation.Metadata, occurrence = 1)
        fake.metadata("/d")
        assertFailsWith<SimulatedCrash> { fake.metadata("/d") }
    }

    @Test
    fun `restartAfterCrash without a crash throws IllegalStateException`() {
        assertFailsWith<IllegalStateException> { fakeWith().restartAfterCrash() }
    }

    @Test
    fun `the restarted fake is a fresh instance with the clock space and link support carried over`() {
        val fake: FakeFileAccess = fakeWith("/d/a" to "A")
        fake.nowMillis = 42
        fake.freeSpaceBytes = 77
        fake.hardLinksSupported = false
        fake.failNext(FakeOperation.Metadata, FileError.Io("/d"), count = 5)
        fake.crashAfter(FakeOperation.List)
        assertFailsWith<SimulatedCrash> { fake.list("/d") }
        val restarted: FakeFileAccess = fake.restartAfterCrash()
        assertEquals(42L, restarted.nowMillis)
        assertEquals(77L, restarted.freeSpaceBytes)
        assertEquals(false, restarted.hardLinksSupported)
        assertEquals(emptyList(), restarted.operations)
        assertNotNull(restarted.metadata("/d/a"), "no fault and no crash carry over")
        assertFailsWith<SimulatedCrash> { fake.metadata("/d/a") }
    }

    @Test
    fun `one write logs CreateNew Write Sync Close and Rename in that order`() {
        val fake: FakeFileAccess = fakeWith()
        assertTrue(fake.writeAtomically("/d/f", bytes("v")).isSuccess)
        val order: List<FakeOperation> = fake.operations.map { it.operation }.filter { it != FakeOperation.Metadata }
        assertEquals(
            listOf(FakeOperation.CreateNew, FakeOperation.Write, FakeOperation.Sync, FakeOperation.Close, FakeOperation.Rename),
            order,
        )
        val rename: FakeOperationRecord = fake.operations.single { it.operation == FakeOperation.Rename }
        assertEquals("/d/f", rename.secondPath)
        // path-hygiene-ignore: PH3 the fake file system is POSIX on every host, so these are logical paths
        assertTrue(FileNames.isTempName(rename.path.substringAfterLast('/')), "the rename's source is the temp file: ${rename.path}")
    }

    @Test
    fun `a SyncedWithDirectory write adds a SyncDirectory of the parent after the rename`() {
        val fake: FakeFileAccess = fakeWith()
        assertTrue(fake.writeAtomically("/d/f", bytes("v"), WriteOptions(durability = Durability.SyncedWithDirectory)).isSuccess)
        val order: List<FakeOperationRecord> = fake.operations.filter { it.operation != FakeOperation.Metadata }
        assertEquals(FakeOperation.SyncDirectory, order.last().operation)
        assertEquals("/d", order.last().path)
        assertEquals(FakeOperation.Rename, order[order.size - 2].operation)
    }

    @Test
    fun `beforeOperation runs for every operation on the calling thread before a fault applies and may throw`() {
        val fake: FakeFileAccess = fakeWith("/d/a" to "A")
        val seen: MutableList<Pair<FakeOperation, String>> = mutableListOf()
        fake.beforeOperation = { operation: FakeOperation, path: String -> seen += operation to path }
        fake.failNext(FakeOperation.OpenForRead, FileError.AccessDenied("/d/a"))
        fake.readBytes("/d/a", 10)
        assertTrue((FakeOperation.OpenForRead to "/d/a") in seen, "the hook ran for the operation that then failed: $seen")
        assertEquals(fake.operations.size, seen.size)
        val marker: IllegalStateException = IllegalStateException("from the hook")
        fake.beforeOperation = { _: FakeOperation, _: String -> throw marker }
        val failure: FileError.Io = assertIs(fake.readBytes("/d/a", 10).errorOrNull(), "an Exception of the hook is a file-system failure")
        assertTrue(failure.cause === marker)
        val error: AssertionError = AssertionError("from the hook")
        fake.beforeOperation = { _: FakeOperation, _: String -> throw error }
        val thrown: AssertionError = assertFailsWith { fake.readBytes("/d/a", 10) }
        assertTrue(thrown === error, "an Error is never swallowed")
    }

    // ---- seeding and inspection ------------------------------------------------------------------------

    @Test
    fun `seeding creates parents replaces files and rejects relative paths and directories in the way`() {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createFile("/a/b/c.txt", bytes("1"))
        fake.createFile("/a/b/c.txt", bytes("2"))
        fake.createDirectory("/a/b/empty")
        fake.createSymbolicLink("/a/link", "b/c.txt")
        assertEquals("2", fake.text("/a/b/c.txt"))
        assertEquals("2", fake.text("/a/link"), "a relative link target is relative to the link's directory")
        assertEquals(FakeEntry.File(bytes("2")), fake.entries()["/a/b/c.txt"])
        assertEquals(FakeEntry.Directory, fake.entries()["/a/b/empty"])
        assertEquals(FakeEntry.SymbolicLink("b/c.txt"), fake.entries()["/a/link"])
        assertFailsWith<IllegalArgumentException> { fake.createFile("relative", ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { fake.createFile("/a/b", ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { fake.createDirectory("/a/b/c.txt") }
        assertFailsWith<IllegalArgumentException> { fake.createSymbolicLink("/a/link", "x") }
        assertFailsWith<IllegalArgumentException> { fake.setLastModified("/a/none", 1) }
    }

    @Test
    fun `hard links share content and every name is listed by entries`() {
        val fake: FakeFileAccess = fakeWith("/d/a" to "A")
        assertTrue(fake.move("/d/a", "/d/b", MoveOptions(existing = ExistingFile.Fail)).isSuccess)
        // After a successful no-replace move only one name is left.
        assertEquals(setOf("/d", "/d/b"), fake.entries().keys)
        fake.failNext(FakeOperation.Remove, FileError.Io("/d/b"), path = { it == "/d/b" })
        assertTrue(fake.move("/d/b", "/d/c", MoveOptions(existing = ExistingFile.Fail)).isSuccess)
        assertEquals(FakeEntry.File(bytes("A")), fake.entries()["/d/b"])
        assertEquals(FakeEntry.File(bytes("A")), fake.entries()["/d/c"])
    }

    @Test
    fun `the engine is the real one so a fake write replaces a symbolic link and leaves its target`() {
        val fake: FakeFileAccess = fakeWith("/d/target" to "old")
        fake.createSymbolicLink("/d/link", "/d/target")
        assertTrue(fake.writeAtomically("/d/link", bytes("new")).isSuccess)
        assertEquals(FakeEntry.File(bytes("new")), fake.entries()["/d/link"])
        assertEquals("old", fake.text("/d/target"))
    }

    @Test
    fun `a symbolic link loop is an Io failure that does not hang`() {
        val fake: FakeFileAccess = fakeWith()
        fake.createSymbolicLink("/d/a", "/d/b")
        fake.createSymbolicLink("/d/b", "/d/a")
        assertNull(fake.metadata("/d/a", followLinks = true), "metadata of a loop must return")
        assertTrue(fake.readBytes("/d/a", 10).errorOrNull() is FileError.Io)
    }

    @Test
    fun `a case-insensitive fake keeps names with a backslash distinct on every host`() {
        val fake: FakeFileAccess = fakeWith(caseSensitive = false)
        for (name: String in listOf("a\\", "a", "x\\..\\y", "y")) assertTrue(fake.writeAtomically("/d/$name", bytes(name)).isSuccess)
        assertEquals(listOf("a", "a\\", "x\\..\\y", "y"), fake.list("/d").getOrNull())
        assertEquals("a\\", fake.text("/d/a\\"))
        assertEquals("a", fake.text("/d/A"))
    }
}
