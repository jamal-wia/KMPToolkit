package io.github.jamal_wia.kmptoolkit.downloader.testing

import io.github.jamal_wia.kmptoolkit.downloader.TempFileState
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The fake storage held to the one sentence of the real contract it does not restate: no file, no mark. */
class FakeStorageContractRound4Test {

    private val group = TestGroup("g")
    private val unit = TestUnit("u", group)

    @Test
    fun `marking a transfer complete when no file exists throws as the real storages do`() {
        val storage = FakeDownloaderStorage()
        storage.beginTempFile(unit, null)
        // Begun, nothing written: the state is None, there is no file to mark.
        assertFailsWith<IllegalStateException> { storage.markTempFileComplete(unit) }
    }

    @Test
    fun `a commit of a complete temp file makes the unit available`() = runTest {
        val storage = FakeDownloaderStorage()
        storage.beginTempFile(unit, null)
        storage.tempFileStates[unit.id] = TempFileState.Partial
        storage.markTempFileComplete(unit)
        storage.commitResource(unit)
        assertTrue(storage.isResourceAvailable(unit), "a committed unit is not available")
    }

    @Test
    fun `a commit that fails the integrity check leaves the unit unavailable`() = runTest {
        val storage = FakeDownloaderStorage()
        storage.failIntegrityFor += unit.id
        storage.beginTempFile(unit, null)
        storage.tempFileStates[unit.id] = TempFileState.Complete
        assertFailsWith<io.github.jamal_wia.kmptoolkit.downloader.ResourceIntegrityException> { storage.commitResource(unit) }
        assertFalse(storage.isResourceAvailable(unit))
    }

    @Test
    fun `marking an already complete file stays idempotent`() {
        val storage = FakeDownloaderStorage()
        storage.beginTempFile(unit, null)
        storage.tempFileStates[unit.id] = TempFileState.Complete
        storage.markTempFileComplete(unit)
        kotlin.test.assertEquals(TempFileState.Complete, storage.tempFileState(unit))
    }
}
