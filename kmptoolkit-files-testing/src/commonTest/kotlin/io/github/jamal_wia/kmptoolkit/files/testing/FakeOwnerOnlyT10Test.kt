package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import io.github.jamal_wia.kmptoolkit.files.isSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T10 for the fake: it records which entries were created owner-only and never enforces it. The flag lives on the
 * inode, so a rename carries it, a hard link shares it and a replace takes the bit of the file moved over.
 */
class FakeOwnerOnlyT10Test {
    private val bytes: ByteArray = byteArrayOf(1, 2, 3)

    @Test
    fun `an ownerOnly write marks the file its created parents and its temp directory and an existing parent stays unmarked`() {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createDirectory("/existing")
        val options: WriteOptions = WriteOptions(createParentDirectories = true, tempDirectory = "/existing/scratch", ownerOnly = true)
        assertTrue(fake.writeAtomically("/existing/new/deep/f", bytes, options).isSuccess)
        assertTrue(fake.isOwnerOnly("/existing/new/deep/f"))
        assertTrue(fake.isOwnerOnly("/existing/new"))
        assertTrue(fake.isOwnerOnly("/existing/new/deep"))
        assertTrue(fake.isOwnerOnly("/existing/scratch"))
        assertFalse(fake.isOwnerOnly("/existing"), "an existing directory is never changed")
    }

    @Test
    fun `the temp file of the write asks for owner only observed at the low-level operation`() {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createDirectory("/d")
        var seen: Boolean? = null
        fake.beforeOperation = { operation: FakeOperation, path: String ->
            // The hook runs before CreateNew creates the temp; Rename runs after it exists, so ask the temp then.
            if (operation == FakeOperation.Rename) seen = fake.isOwnerOnly(path)
        }
        assertTrue(fake.writeAtomically("/d/f", bytes, WriteOptions(ownerOnly = true)).isSuccess)
        assertEquals(true, seen, "the temp file was created owner-only before the rename")
    }

    @Test
    fun `a write without ownerOnly marks nothing`() {
        val fake: FakeFileAccess = FakeFileAccess()
        assertTrue(fake.writeAtomically("/d/f", bytes, WriteOptions(createParentDirectories = true)).isSuccess)
        assertTrue(fake.createDirectories("/e/f").isSuccess)
        for (path: String in listOf("/d", "/d/f", "/e", "/e/f")) assertFalse(fake.isOwnerOnly(path), path)
    }

    @Test
    fun `a replace takes the flag of the new file in both directions`() {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createFile("/f", bytes)
        assertFalse(fake.isOwnerOnly("/f"), "a seeded file is not owner-only")
        assertTrue(fake.writeAtomically("/f", bytes, WriteOptions(ownerOnly = true)).isSuccess)
        assertTrue(fake.isOwnerOnly("/f"), "an ownerOnly write over a plain file")
        assertTrue(fake.writeAtomically("/f", bytes).isSuccess)
        assertFalse(fake.isOwnerOnly("/f"), "a plain write over an owner-only file asks for nothing")
    }

    @Test
    fun `a move within the volume carries the flag and the no-replace write shares it through the hard link`() {
        val fake: FakeFileAccess = FakeFileAccess()
        assertTrue(fake.writeAtomically("/a", bytes, WriteOptions(ownerOnly = true)).isSuccess)
        assertTrue(fake.move("/a", "/b").isSuccess)
        assertTrue(fake.isOwnerOnly("/b"))
        assertTrue(fake.writeAtomically("/c", bytes, WriteOptions(existing = ExistingFile.Fail, ownerOnly = true)).isSuccess)
        assertTrue(fake.isOwnerOnly("/c"))
    }

    @Test
    fun `a no-replace move a link and a removal keeps the flag of the inode`() {
        val fake: FakeFileAccess = FakeFileAccess()
        assertTrue(fake.writeAtomically("/a", bytes, WriteOptions(ownerOnly = true)).isSuccess)
        assertTrue(fake.move("/a", "/b", MoveOptions(existing = ExistingFile.Fail)).isSuccess)
        assertTrue(fake.isOwnerOnly("/b"))
    }

    @Test
    fun `a cross-volume move copies through a temp file with the default access the documented limit`() {
        val fake: FakeFileAccess = FakeFileAccess()
        assertTrue(fake.writeAtomically("/a", bytes, WriteOptions(ownerOnly = true)).isSuccess)
        fake.failNext(FakeOperation.Rename, FileError.CrossDevice("/b"))
        assertTrue(fake.move("/a", "/b", MoveOptions(acrossVolumes = true)).isSuccess)
        assertEquals(listOf("b"), fake.list("/").getOrNull(), "the source was removed after the copy")
        assertFalse(fake.isOwnerOnly("/b"), "the copy has the default access: ownerOnly is not part of move")
    }

    @Test
    fun `createDirectories with ownerOnly marks only the levels it created`() {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createDirectory("/open")
        assertTrue(fake.createDirectories("/open/x/y", ownerOnly = true).isSuccess)
        assertFalse(fake.isOwnerOnly("/open"))
        assertTrue(fake.isOwnerOnly("/open/x"))
        assertTrue(fake.isOwnerOnly("/open/x/y"))
        assertTrue(fake.createDirectories("/open", ownerOnly = true).isSuccess)
        assertFalse(fake.isOwnerOnly("/open"), "an existing directory is not changed by a later ownerOnly call")
    }

    @Test
    fun `seeded entries and links answer false and a missing or relative path is an error`() {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createFile("/d/f")
        fake.createSymbolicLink("/link", "/d/f")
        assertFalse(fake.isOwnerOnly("/d"))
        assertFalse(fake.isOwnerOnly("/d/f"))
        assertFalse(fake.isOwnerOnly("/link"))
        assertFailsWith<IllegalArgumentException> { fake.isOwnerOnly("/absent") }
        assertFailsWith<IllegalArgumentException> { fake.isOwnerOnly("/d/f/x") }
        assertFailsWith<IllegalArgumentException> { fake.isOwnerOnly("relative") }
    }

    @Test
    fun `the flag survives a simulated crash and restart process death and power loss`() {
        for (kind: CrashKind in CrashKind.values()) {
            val fake: FakeFileAccess = FakeFileAccess()
            assertTrue(fake.writeAtomically("/d/f", bytes, WriteOptions(createParentDirectories = true, ownerOnly = true)).isSuccess)
            fake.markAllDurable()
            fake.crashAfter(FakeOperation.CreateNew)
            runCatching { fake.writeAtomically("/d/g", bytes, WriteOptions(ownerOnly = true)) }
            assertTrue(fake.isOwnerOnly("/d/f"), "works after the crash, $kind")
            val restarted: FakeFileAccess = fake.restartAfterCrash(kind, revertUnsyncedDirectoryEntries = false)
            assertTrue(restarted.isOwnerOnly("/d/f"), "$kind")
            assertTrue(restarted.isOwnerOnly("/d"), "$kind")
        }
    }
}
