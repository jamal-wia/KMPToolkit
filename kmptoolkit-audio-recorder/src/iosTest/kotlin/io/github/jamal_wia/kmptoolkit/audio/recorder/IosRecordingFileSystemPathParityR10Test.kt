package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * iOS half of the path parity table (the Android half is `AndroidRecordingFileSystemPathParityR10Test`): `resolve`
 * drops a trailing or repeated separator and returns the name as given, `parentOf` answers the directory part or
 * null if there is none, exactly as `AndroidRecordingFileSystem` and the documented contract say.
 */
class IosRecordingFileSystemPathParityR10Test {

    @Test
    fun `resolve parentOf and isAbsolute answer the shared table`() {
        val observed: List<String> = RecordingFileSystemPathParityR10.observe(IosRecordingFileSystem())

        assertEquals(emptyList(), RecordingFileSystemPathParityR10.mismatches(observed), "iOS differs from the contract table")
    }
}
