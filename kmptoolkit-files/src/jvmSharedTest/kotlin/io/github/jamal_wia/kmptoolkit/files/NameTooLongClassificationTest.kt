package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.mapNioError
import io.github.jamal_wia.kmptoolkit.files.internal.segmentTooLong
import java.nio.file.FileSystemException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R-17 (W2): NameTooLong is decided by segment length after a failure, never by message text. */
class NameTooLongClassificationTest {
    private val german: FileSystemException = FileSystemException("a", "b", "Der Dateiname oder die Erweiterung ist zu lang.")

    @Test
    fun `an untyped failure on a 256 character segment is NameTooLong under both rules`() {
        val posix: String = "/d/" + "a".repeat(256)
        val windows: String = "C:\\d\\" + "a".repeat(256)
        assertEquals(FileError.NameTooLong(posix), mapNioError(german, posix, windows = false))
        assertEquals(FileError.NameTooLong(windows), mapNioError(german, windows, windows = true))
    }

    @Test
    fun `the same failure on a short path is Io`() {
        assertTrue(mapNioError(german, "/d/short", windows = false) is FileError.Io)
        assertTrue(mapNioError(german, "C:\\d\\short", windows = true) is FileError.Io)
    }

    @Test
    fun `Windows counts UTF-16 units and the others count UTF-8 bytes`() {
        val cjk: String = "\u4e2d".repeat(200) // 200 units, 600 bytes
        assertFalse(segmentTooLong("C:\\d\\$cjk", windows = true))
        assertTrue(segmentTooLong("/d/$cjk", windows = false))
        assertTrue(mapNioError(german, "/d/$cjk", windows = false) is FileError.NameTooLong)
        assertTrue(mapNioError(german, "C:\\d\\$cjk", windows = true) is FileError.Io, "NTFS accepts it, so it is not a name error")
        val emoji: String = "\uD83D\uDE00".repeat(130) // 260 units
        assertTrue(segmentTooLong("C:\\d\\$emoji", windows = true))
        assertTrue(segmentTooLong("/d/" + "a".repeat(255) + "/x", windows = false).not())
        assertTrue(segmentTooLong("/d/" + "a".repeat(255) + "b/x", windows = false))
    }

    @Test
    fun `the other path of a rename or link is checked too and named`() {
        val longTarget: String = "/d/" + "t".repeat(256)
        assertEquals(FileError.NameTooLong(longTarget), mapNioError(german, "/d/temp", windows = false, also = listOf(longTarget)))
    }

    @Test
    fun `typed exceptions are never reclassified by length`() {
        val path: String = "/d/" + "a".repeat(256)
        assertEquals(FileError.NotFound(path), mapNioError(java.nio.file.NoSuchFileException("x"), path, windows = false))
    }
}
