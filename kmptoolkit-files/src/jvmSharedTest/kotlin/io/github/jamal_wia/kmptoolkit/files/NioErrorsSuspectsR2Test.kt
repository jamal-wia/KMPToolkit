package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.mapNioError
import java.nio.file.FileSystemException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round 2 of the breaker for unit G: documented sentences the error mapping may break (see the report; a red test here
 * is a SUSPECT and is not committed).
 */
class NioErrorsSuspectsR2Test {
    private val path: String = "/d/f"

    /** 04: "AccessDenied covers EACCES, EPERM, EROFS". The JDK does not type EPERM: it is a plain "Operation not permitted". */
    @Test
    fun `EPERM from a delete or a rename is AccessDenied as documented`() {
        val eperm: FileSystemException = FileSystemException(path, null, "Operation not permitted")
        assertEquals(FileError.AccessDenied(path), mapNioError(eperm, path, forLink = false))
    }

    /** 05: NameTooLong by the length of a segment, "elsewhere: more than 255 UTF-8 bytes"; a backslash is no separator on POSIX. */
    @Test
    fun `a 300 byte POSIX name containing a backslash is classified NameTooLong by length`() {
        val longName: String = "a".repeat(150) + "\\" + "a".repeat(150)
        val untypedFailure: FileSystemException = FileSystemException("/d/$longName", null, "localized text of some language")
        assertEquals(
            FileError.NameTooLong("/d/$longName"),
            mapNioError(untypedFailure, "/d/$longName", windows = false, codePoints = false),
        )
    }
}
