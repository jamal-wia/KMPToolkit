package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.errorOrNull
import io.github.jamal_wia.kmptoolkit.files.exists
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import io.github.jamal_wia.kmptoolkit.files.isSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 1 of unit G, G1 and G2: how the fake walks a path. A `.` after a non-directory is NotADirectory, `d/.` is the
 * directory itself, and every lookup of a segment over 255 UTF-8 bytes is NameTooLong (as on ext4).
 */
class FakePathWalkR1FixTest {

    private fun fake(): FakeFileAccess {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createDirectory("/d")
        fake.createFile("/d/inner", "x".encodeToByteArray())
        fake.createFile("/f", "data".encodeToByteArray())
        fake.createDirectory("/full")
        fake.createFile("/full/x", "x".encodeToByteArray())
        fake.createSymbolicLink("/link-to-dir", "/d")
        return fake
    }

    // ---- G1: '.' ------------------------------------------------------------------------------------

    @Test
    fun `a dot after a file finds nothing`() {
        val fake: FakeFileAccess = fake()
        assertNull(fake.metadata("/f/."))
        assertFalse(fake.exists("/f/."))
        assertEquals(FileError.NotFound("/f/."), fake.readBytes("/f/.", 100L).errorOrNull())
    }

    @Test
    fun `createDirectories and writeAtomically through a dot after a file are NotADirectory`() {
        val fake: FakeFileAccess = fake()
        assertIs<FileError.NotADirectory>(fake.createDirectories("/f/.").errorOrNull())
        assertIs<FileError.NotADirectory>(fake.writeAtomically("/f/.", "y".encodeToByteArray()).errorOrNull())
        assertEquals("data", fake.readBytes("/f", 100L).getOrNull()?.decodeToString())
    }

    @Test
    fun `a dot after a directory is the directory itself`() {
        val fake: FakeFileAccess = fake()
        assertEquals(FileKind.Directory, fake.metadata("/d/.")?.kind)
        assertTrue(fake.createDirectories("/d/.").isSuccess)
        assertEquals(FileKind.Directory, fake.metadata("/d/./.")?.kind)
    }

    @Test
    fun `delete and deleteRecursively of a path ending in a dot are Io and the directory stays`() {
        val fake: FakeFileAccess = fake()
        assertIs<FileError.Io>(fake.delete("/d/.").errorOrNull())
        assertEquals(FileKind.Directory, fake.metadata("/d")?.kind)
        assertEquals(FileKind.RegularFile, fake.metadata("/d/inner")?.kind)
        fake.createDirectory("/empty")
        assertIs<FileError.Io>(fake.deleteRecursively("/empty/.").errorOrNull())
        assertEquals(FileKind.Directory, fake.metadata("/empty")?.kind)
    }

    @Test
    fun `a dot after a link to a directory is the directory even without following links`() {
        val fake: FakeFileAccess = fake()
        assertEquals(FileKind.Directory, fake.metadata("/link-to-dir/.", followLinks = false)?.kind)
        assertEquals(FileKind.SymbolicLink, fake.metadata("/link-to-dir", followLinks = false)?.kind)
    }

    // ---- G2: names over 255 bytes -------------------------------------------------------------------

    private val longName: String = "a".repeat(256)
    private val fullName: String = "b".repeat(255)

    @Test
    fun `a name of 255 bytes is created read and deleted`() {
        val fake: FakeFileAccess = fake()
        val path: String = "/d/$fullName"
        assertTrue(fake.writeAtomically(path, "hi".encodeToByteArray()).isSuccess)
        assertEquals("hi", fake.readBytes(path, 100L).getOrNull()?.decodeToString())
        assertTrue(fake.delete(path).isSuccess)
        assertNull(fake.metadata(path))
    }

    @Test
    fun `a multibyte name over 255 bytes is NameTooLong while 255 bytes fit`() {
        val fake: FakeFileAccess = fake()
        val fits: String = "é".repeat(127) + "a" // 254 + 1 = 255 bytes
        val tooLong: String = "é".repeat(128) // 256 bytes
        assertTrue(fake.createDirectories("/d/$fits").isSuccess)
        assertEquals(FileError.NameTooLong("/d/$tooLong"), fake.readBytes("/d/$tooLong", 10L).errorOrNull())
    }

    @Test
    fun `every function reports NameTooLong with the full path for a name over 255 bytes`() {
        val fake: FakeFileAccess = fake()
        val path: String = "/d/$longName"
        val expected: FileError = FileError.NameTooLong(path)
        assertEquals(expected, fake.readBytes(path, 10L).errorOrNull())
        assertEquals(expected, fake.delete(path).errorOrNull())
        assertEquals(expected, fake.list(path).errorOrNull())
        assertEquals(expected, fake.createDirectories(path).errorOrNull())
        assertEquals(expected, fake.writeAtomically(path, ByteArray(1)).errorOrNull())
        assertEquals(expected, fake.move(path, "/d/other").errorOrNull())
        assertEquals(expected, fake.move("/d/inner", path).errorOrNull())
        assertNull(fake.metadata(path))
        assertFalse(fake.exists(path))
        assertTrue(fake.entries().keys.none { it.contains(longName) })
    }

    @Test
    fun `a link whose target has a segment over 255 bytes is null when followed`() {
        val fake: FakeFileAccess = fake()
        fake.createSymbolicLink("/d/long-link", "/d/$longName")
        assertNull(fake.metadata("/d/long-link", followLinks = true))
        assertEquals(FileKind.SymbolicLink, fake.metadata("/d/long-link", followLinks = false)?.kind)
    }

    @Test
    fun `seeding a segment over 255 bytes throws IllegalArgumentException`() {
        val fake: FakeFileAccess = fake()
        assertFailsWith<IllegalArgumentException> { fake.createFile("/d/$longName", ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { fake.createDirectory("/d/$longName") }
        assertFailsWith<IllegalArgumentException> { fake.createDirectory("/$longName/inner") }
        assertFailsWith<IllegalArgumentException> { fake.createSymbolicLink("/d/$longName", "/f") }
    }

    @Test
    fun `setLastModified of an absent path throws IllegalArgumentException`() {
        val fake: FakeFileAccess = fake()
        assertFailsWith<IllegalArgumentException> { fake.setLastModified("/d/absent", 5L) }
        assertFailsWith<IllegalArgumentException> { fake.setLastModified("/absent-dir/x", 5L) }
        assertFailsWith<IllegalArgumentException> { fake.setLastModified("/d/$longName", 5L) }
        assertNotNull(fake.metadata("/d"))
    }
}
