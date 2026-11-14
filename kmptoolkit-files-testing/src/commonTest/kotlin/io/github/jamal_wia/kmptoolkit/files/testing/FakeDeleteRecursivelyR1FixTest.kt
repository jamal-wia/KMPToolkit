package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.errorOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 1 of unit G, G8: `deleteRecursively` answers Success only when the root was examined and nothing is left; a
 * root that cannot be examined is reported with that error.
 */
class FakeDeleteRecursivelyR1FixTest {

    @Test
    fun `a root whose metadata always fails is reported and the tree stays`() {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createDirectory("/r")
        fake.createDirectory("/r/t")
        fake.createFile("/r/t/f", "data".encodeToByteArray())
        fake.failNext(FakeOperation.Metadata, FileError.AccessDenied("/r/t"), count = 100, path = { it == "/r/t" })
        assertEquals(FileError.AccessDenied("/r/t"), fake.deleteRecursively("/r/t").errorOrNull())
        assertTrue("/r/t/f" in fake.entries().keys, "the tree is untouched: ${fake.entries().keys}")
    }

    @Test
    fun `a path with a segment over 255 bytes is NameTooLong`() {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createDirectory("/r")
        val path: String = "/r/" + "a".repeat(256)
        assertEquals(FileError.NameTooLong(path), fake.deleteRecursively(path).errorOrNull())
    }
}
