package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.errorOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/** Round 1 of unit G, G3: which path a failed `move` names (the rule is written in the `move` row of the API reference). */
class FakeMoveErrorPathR1FixTest {

    private fun fake(): FakeFileAccess {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createDirectory("/d/sub")
        fake.createFile("/f", "file".encodeToByteArray())
        fake.createDirectory("/full")
        fake.createFile("/full/x", "x".encodeToByteArray())
        return fake
    }

    @Test
    fun `a directory onto a file names the target`() {
        assertEquals(FileError.NotADirectory("/f"), fake().move("/d", "/f").errorOrNull())
    }

    @Test
    fun `a file under a file names the target`() {
        assertEquals(FileError.NotADirectory("/f/g"), fake().move("/full/x", "/f/g").errorOrNull())
    }

    @Test
    fun `a missing parent of the target names the target while the source exists`() {
        assertEquals(FileError.NotFound("/nope/g"), fake().move("/full/x", "/nope/g").errorOrNull())
    }

    @Test
    fun `a missing source names the source`() {
        assertEquals(FileError.NotFound("/absent"), fake().move("/absent", "/g").errorOrNull())
    }

    @Test
    fun `a directory onto a non-empty directory names the target`() {
        assertEquals(FileError.IsADirectory("/full"), fake().move("/d", "/full").errorOrNull())
    }

    @Test
    fun `Fail of a directory names the source`() {
        assertEquals(
            FileError.Unsupported("/d"),
            fake().move("/d", "/e", MoveOptions(existing = ExistingFile.Fail)).errorOrNull(),
        )
    }

    @Test
    fun `Fail over an existing file names the target`() {
        assertEquals(
            FileError.AlreadyExists("/f"),
            fake().move("/full/x", "/f", MoveOptions(existing = ExistingFile.Fail)).errorOrNull(),
        )
    }
}
