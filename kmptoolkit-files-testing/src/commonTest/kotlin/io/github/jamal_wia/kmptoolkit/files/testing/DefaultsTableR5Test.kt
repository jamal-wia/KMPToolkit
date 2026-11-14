package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.AppDirectories
import io.github.jamal_wia.kmptoolkit.files.AppRoot
import io.github.jamal_wia.kmptoolkit.files.Backup
import io.github.jamal_wia.kmptoolkit.files.CancellationCheck
import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileAccessConfig
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.FileProtection
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.errorOrNull
import io.github.jamal_wia.kmptoolkit.files.isSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 5 of the breaker for unit G, Q5: the defaults the docs print (04-api-reference signatures and prose, 03-guide,
 * 06-testing) against the defaults the code has. One row per default, 28 rows; a default a function parameter carries is
 * observed through behaviour (the fake's operation log), never read from the source.
 */
class DefaultsTableR5Test {
    private val bytes: ByteArray = ByteArray(3) { 1 }

    @Test
    fun `value defaults of WriteOptions and MoveOptions and FileAccessConfig and the constants`() {
        // rows 1-6: WriteOptions
        val w = WriteOptions()
        assertEquals(Durability.Synced, w.durability, "row 1 durability")
        assertEquals(ExistingFile.Replace, w.existing, "row 2 existing")
        assertEquals(FileProtection.Default, w.protection, "row 3 protection")
        assertEquals(false, w.createParentDirectories, "row 4 createParentDirectories")
        assertNull(w.tempDirectory, "row 5 tempDirectory")
        assertNull(w.tempPrefix, "row 6 tempPrefix")
        // rows 7-9: MoveOptions
        val m = MoveOptions()
        assertEquals(ExistingFile.Replace, m.existing, "row 7")
        assertEquals(true, m.acrossVolumes, "row 8 acrossVolumes")
        assertEquals(false, m.createParentDirectories, "row 9")
        // rows 10-12: FileAccessConfig and the temp name rules
        assertEquals(".kmptoolkit-", FileAccessConfig().tempPrefix, "row 10")
        assertEquals(".kmptoolkit-", FileAccessConfig.DEFAULT_TEMP_PREFIX, "row 11")
        assertEquals(232, FileAccessConfig.MAX_TEMP_PREFIX_BYTES, "row 12 (255 minus 19 digits minus .tmp)")
        // rows 13-14: constants
        assertEquals(Int.MAX_VALUE.toLong() - 16, FileAccess.MAX_READ_BYTES, "row 13")
        assertEquals(255, FileNames.MAX_NAME_BYTES, "row 14")
        // row 15: isTempName default prefix is the default prefix, and the shape is prefix + 1..19 digits + .tmp
        assertTrue(FileNames.isTempName(".kmptoolkit-1.tmp") && FileNames.isTempName(".kmptoolkit-" + "9".repeat(19) + ".tmp"), "row 15")
        assertTrue(!FileNames.isTempName(".kmptoolkit-" + "9".repeat(20) + ".tmp") && !FileNames.isTempName(".kmptoolkit-.tmp"), "row 15 shape")
        // row 16: Fixture defaults of the contract
        val fixture = FileAccessFixture(FakeFileAccess(), "/r")
        assertEquals('/', fixture.separator, "row 16 separator")
        assertNull(fixture.createSymbolicLink, "row 17 createSymbolicLink")
        assertNull(fixture.setLastModified, "row 18 setLastModified")
    }

    @Test
    fun `defaults a function parameter carries are observed through behaviour`() {
        val files = FakeFileAccess()
        files.createFile("/d/target", bytes)
        files.createSymbolicLink("/d/link", "/d/target")
        // row 19: metadata followLinks = false
        assertEquals(FileKind.SymbolicLink, files.metadata("/d/link")?.kind, "row 19")
        // row 20: createDirectories durable = false syncs nothing
        files.createDirectories("/e/f/g")
        assertTrue(files.operations.none { it.operation == FakeOperation.SyncDirectory }, "row 20")
        // row 21: writeAtomically options = WriteOptions(): Synced (a Sync, no SyncDirectory), Replace, no parent creation
        val before: Int = files.operations.size
        assertTrue(files.writeAtomically("/d/new", bytes).isSuccess)
        val ops: List<FakeOperation> = files.operations.drop(before).map { it.operation }
        assertTrue(FakeOperation.Sync in ops && FakeOperation.SyncDirectory !in ops, "row 21 durability Synced: $ops")
        assertTrue(files.writeAtomically("/d/new", bytes).isSuccess, "row 22 existing = Replace")
        assertTrue(files.writeAtomically("/none/new", bytes).errorOrNull() is FileError.NotFound, "row 23 createParentDirectories = false")
        // row 24: move options default: Replace and acrossVolumes = true (a CrossDevice rename is copied)
        files.failNext(FakeOperation.Rename, FileError.CrossDevice("/d"), count = 1, path = { it == "/d/new" })
        assertTrue(files.move("/d/new", "/d/target").isSuccess, "row 24 acrossVolumes default true, existing default Replace")
        assertEquals(null, files.metadata("/d/new"))
        // row 25: cancellation defaults to Never for deleteRecursively and treeSize and sweep
        assertTrue(files.treeSize("/d").isSuccess && files.sweep("/d", 0) { false }.isSuccess, "row 25")
        assertTrue(files.deleteRecursively("/e").isSuccess && files.metadata("/e") == null, "row 25b")
        assertEquals(FileResult.Failure(FileError.Cancelled), files.treeSize("/d", CancellationCheck { true }), "control for row 25")
        // row 26: the fake's own defaults
        val fresh = FakeFileAccess()
        assertEquals(0L, fresh.nowMillis, "row 26 nowMillis")
        assertNull(fresh.freeSpaceBytes, "row 27 freeSpaceBytes")
        assertEquals(true, fresh.hardLinksSupported, "row 28 hardLinksSupported")
        // caseSensitive = true by default
        fresh.createFile("/d/A", bytes)
        assertNull(fresh.metadata("/d/a"), "row 29 caseSensitive")
        // failNext count = 1 and path = { true }
        fresh.failNext(FakeOperation.Metadata, FileError.AccessDenied("/d"))
        assertEquals(null, fresh.metadata("/d/A"), "row 30 first call fails")
        assertEquals(FileKind.RegularFile, fresh.metadata("/d/A")?.kind, "row 30 count defaults to 1")
        // crashAfter occurrence = 1
        fresh.crashAfter(FakeOperation.Remove)
        assertTrue(runCatching { fresh.delete("/d/A") }.exceptionOrNull() is SimulatedCrash, "row 31 occurrence = 1")
    }

    @Test
    fun `AppDirectories directory defaults backup to Included`() {
        var seen: Backup? = null
        val directories = object : AppDirectories {
            override fun rootPath(root: AppRoot): String = "/r"
            override fun directory(root: AppRoot, name: String, backup: Backup): FileResult<String> {
                seen = backup
                return FileResult.Success("/r/$name")
            }

            override fun excludeFromBackup(path: String): FileResult<Unit> = FileResult.Success(Unit)
        }
        directories.directory(AppRoot.Files, "x")
        assertEquals(Backup.Included, seen, "row 32 backup")
    }
}
