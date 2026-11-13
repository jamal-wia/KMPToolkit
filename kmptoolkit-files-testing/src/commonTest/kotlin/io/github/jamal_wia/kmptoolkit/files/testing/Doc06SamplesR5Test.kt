package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.getOrNull
import io.github.jamal_wia.kmptoolkit.files.testing.FakeOperation.Close
import io.github.jamal_wia.kmptoolkit.files.testing.FakeOperation.CreateNew
import io.github.jamal_wia.kmptoolkit.files.testing.FakeOperation.Rename
import io.github.jamal_wia.kmptoolkit.files.testing.FakeOperation.Sync
import io.github.jamal_wia.kmptoolkit.files.testing.FakeOperation.Write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Round 5, G19: the two samples of docs/kmptoolkit-files/06-testing.md ("Fault injection" operations log and "Crash and
 * power-loss simulation") copied as printed, each into a fresh fake, as a newcomer would.
 */
class Doc06SamplesR5Test {
    private val bytes: ByteArray = "new".encodeToByteArray()

    @Test
    fun `the operations log sample runs as printed`() {
        val files: FakeFileAccess = FakeFileAccess()
        files.createDirectory("/d")
        files.writeAtomically("/d/f", bytes)
        assertEquals(
            listOf(CreateNew, Write, Sync, Close, Rename),
            files.operations.map { it.operation }.filter { it != FakeOperation.Metadata },
        )
    }

    @Test
    fun `the crash sample runs as printed`() {
        val files: FakeFileAccess = FakeFileAccess()
        files.createFile("/d/f", "old".encodeToByteArray())
        files.crashAfter(FakeOperation.Rename)
        assertFailsWith<SimulatedCrash> { files.writeAtomically("/d/f", bytes) }

        val after: FakeFileAccess = files.restartAfterCrash(CrashKind.PowerLoss, revertUnsyncedDirectoryEntries = true)
        assertEquals("old", after.readBytes("/d/f", 1024).getOrNull()?.decodeToString())
    }
}
