package io.github.jamal_wia.kmptoolkit.downloader.testing

import io.github.jamal_wia.kmptoolkit.downloader.DownloadError
import io.github.jamal_wia.kmptoolkit.downloader.DownloadUnit
import io.github.jamal_wia.kmptoolkit.downloader.GroupDownloadState
import io.github.jamal_wia.kmptoolkit.downloader.ResourceGroup
import io.github.jamal_wia.kmptoolkit.downloader.ResourceIntegrityException
import io.github.jamal_wia.kmptoolkit.downloader.Sha256
import io.github.jamal_wia.kmptoolkit.downloader.TempFileState
import io.github.jamal_wia.kmptoolkit.downloader.UnitDownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** The lightweight doubles: [FakeDownloader], [FakeDownloaderStorage] and the small fixtures. */
class FakeDownloaderTest {

    private val group = TestGroup("bundle")
    private val unit = TestUnit(id = "asset", group = group)

    @Test
    fun `isAvailable answers from the mutable id sets`() {
        val downloader = FakeDownloader(availableUnitIds = setOf(unit.id))

        assertTrue(downloader.isAvailable(unit))
        assertFalse(downloader.isAvailable(group))

        downloader.availableGroupKeys += group.key
        assertTrue(downloader.isAvailable(group))
    }

    @Test
    fun `ensureAvailable records the call for both surfaces`() = runTest {
        val downloader = FakeDownloader()

        downloader.ensureAvailable(unit)
        downloader.ensureAvailable(group)

        assertEquals<List<DownloadUnit>>(listOf(unit), downloader.ensuredUnits)
        assertEquals<List<ResourceGroup>>(listOf(group), downloader.ensuredGroups)
    }

    @Test
    fun `cancelDownload records the unit and invokes the order hook`() {
        val order = mutableListOf<String>()
        val downloader = FakeDownloader(onCancelUnit = { order += "cancelled:${it.id}" })

        downloader.cancelDownload(unit)

        assertEquals<List<DownloadUnit>>(listOf(unit), downloader.cancelledUnits)
        assertEquals(listOf("cancelled:${unit.id}"), order)
    }

    @Test
    fun `emit pushes a unit state a fresh collector replays`() = runTest {
        val downloader = FakeDownloader()

        downloader.emit(unit, UnitDownloadState.Downloading(0.5f))

        assertEquals(UnitDownloadState.Downloading(0.5f), downloader.unitDownloadStateFlow(unit).first())
    }

    @Test
    fun `setGroupState is reflected by downloadState immediately`() {
        val downloader = FakeDownloader()

        downloader.setGroupState(group, GroupDownloadState.Downloading(0.25f))

        assertEquals(GroupDownloadState.Downloading(0.25f), downloader.downloadState(group).value)
    }

    @Test
    fun `cancelAllDownloads counts calls`() = runTest {
        val downloader = FakeDownloader()

        downloader.cancelAllDownloads()
        downloader.cancelAllDownloads()

        assertEquals(2, downloader.cancelAllCalls)
    }

    @Test
    fun `fake storage tracks availability deletion and a fixed size`() {
        val storage = FakeDownloaderStorage(availableIds = setOf(unit.id), sizeOnDisk = 42L)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(42L, storage.getResourceSize(unit))

        storage.deleteResource(unit)

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals<List<DownloadUnit>>(listOf(unit), storage.deletedResources)
    }

    @Test
    fun `fake storage moves a partial temp file to complete and forgets it on delete`() {
        val storage = FakeDownloaderStorage()
        assertEquals(TempFileState.None, storage.tempFileState(unit))

        storage.beginTempFile(unit, null)
        storage.tempFileStates[unit.id] = TempFileState.Partial
        storage.markTempFileComplete(unit)
        assertEquals(TempFileState.Complete, storage.tempFileState(unit))

        storage.deleteTempFile(unit)
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `fake storage does not invent a complete temp file where there was none`() {
        val storage = FakeDownloaderStorage()
        storage.beginTempFile(unit, null)

        storage.markTempFileComplete(unit)

        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `fake storage records every begin with its hash`() {
        val storage = FakeDownloaderStorage()
        val hash: Sha256 = Sha256.parse("a".repeat(64))

        storage.beginTempFile(unit, hash)
        storage.beginTempFile(unit, null)

        assertEquals(listOf<Pair<String, Sha256?>>(unit.id to hash, unit.id to null), storage.beganWith)
    }

    @Test
    fun `fake storage begin discards whatever transfer was there`() {
        val storage = FakeDownloaderStorage()
        storage.tempFileStates[unit.id] = TempFileState.Complete

        storage.beginTempFile(unit, null)

        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `fake storage rejects a mark without a begin and says what was forgotten`() {
        val storage = FakeDownloaderStorage()
        storage.tempFileStates[unit.id] = TempFileState.Partial

        val thrown: IllegalStateException = assertFailsWith<IllegalStateException> {
            storage.markTempFileComplete(unit)
        }

        assertTrue("beginTempFile" in thrown.message.orEmpty(), thrown.message)
        assertEquals(TempFileState.Partial, storage.tempFileState(unit))
    }

    @Test
    fun `fake storage requires a new begin after a delete and after a commit`() = runTest {
        val storage = FakeDownloaderStorage()

        storage.beginTempFile(unit, null)
        storage.deleteTempFile(unit)
        assertFailsWith<IllegalStateException> { storage.markTempFileComplete(unit) }

        storage.beginTempFile(unit, null)
        storage.tempFileStates[unit.id] = TempFileState.Complete
        storage.commitResource(unit)
        assertFailsWith<IllegalStateException> { storage.markTempFileComplete(unit) }
    }

    @Test
    fun `fake storage rejects contradictory hashes and records nothing`() {
        val storage = FakeDownloaderStorage()
        val fixed = HashedUnit(Sha256.parse("a".repeat(64)))

        assertFailsWith<IllegalArgumentException> {
            storage.beginTempFile(fixed, Sha256.parse("b".repeat(64)))
        }
        storage.beginTempFile(fixed, Sha256.parse("A".repeat(64)))

        assertEquals(1, storage.beganWith.size)
    }

    @Test
    fun `fake storage commit throws an integrity failure only for the chosen ids`() = runTest {
        val storage = FakeDownloaderStorage()
        storage.failIntegrityFor += unit.id
        val other = TestUnit(id = "other", group = group)
        storage.tempFileStates[unit.id] = TempFileState.Complete
        storage.tempFileStates[other.id] = TempFileState.Complete

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }
        storage.commitResource(other)
    }

    @Test
    fun `fake storage integrity failure consumes the temp file and a new begin is required`() = runTest {
        val storage = FakeDownloaderStorage()
        storage.failIntegrityFor += unit.id
        storage.beginTempFile(unit, null)
        storage.tempFileStates[unit.id] = TempFileState.Complete

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFailsWith<IllegalStateException> { storage.markTempFileComplete(unit) }
        // The knob fails every commit of the id until it is removed, whatever the begin.
        storage.beginTempFile(unit, null)
        storage.tempFileStates[unit.id] = TempFileState.Complete
        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }
    }

    @Test
    fun `fake storage commit requires a complete temp file and consumes it`() = runTest {
        val storage = FakeDownloaderStorage()

        assertFailsWith<IllegalStateException> { storage.commitResource(unit) }
        storage.tempFileStates[unit.id] = TempFileState.Complete

        storage.commitResource(unit)

        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `fake storage accepts a mark for a partial seeded as begun earlier without a begin call`() {
        val storage = FakeDownloaderStorage()
        storage.tempFileStates[unit.id] = TempFileState.Partial
        storage.begunIds += unit.id

        storage.markTempFileComplete(unit)

        assertEquals(TempFileState.Complete, storage.tempFileState(unit))
        assertTrue(storage.beganWith.isEmpty())
    }

    @Test
    fun `fake storage reports every destructive call through onEvent in order`() {
        val events = mutableListOf<String>()
        val storage = FakeDownloaderStorage(availableIds = setOf(unit.id), onEvent = { events += it })

        storage.deleteTempFile(unit)
        storage.deleteResource(unit)

        assertEquals(listOf("deleteTempFile:${unit.id}", "deleteResource:${unit.id}"), events)
    }

    @Test
    fun `the recording notifier captures every call in order`() = runTest {
        val notifier = RecordingNotifier()

        notifier.showProgress(group, 0.5f)
        notifier.showCompleted(group)
        notifier.remove(group)
        notifier.showError(group, DownloadError.NotFound)

        assertEquals(
            listOf(
                RecordingNotifier.Kind.PROGRESS,
                RecordingNotifier.Kind.COMPLETED,
                RecordingNotifier.Kind.REMOVE,
                RecordingNotifier.Kind.ERROR,
            ),
            notifier.calls.map { it.kind },
        )
        assertEquals(0.5f, notifier.calls.first().progress)
    }

    @Test
    fun `the in-memory state store round-trips and removes`() {
        val store = InMemoryStateStore()

        assertEquals(0, store.readInt("k", 0))
        store.writeInt("k", 3)
        assertEquals(3, store.readInt("k", 0))
        store.remove("k")
        assertEquals(0, store.readInt("k", 0))
    }

    @Test
    fun `test group units can be assigned after construction`() {
        val a = TestUnit(id = "a", group = group)
        group.units = listOf(a, unit)

        assertEquals(listOf(a, unit), group.units)
    }
}

private class HashedUnit(override val sha256: Sha256?) : DownloadUnit {
    override val id: String = "hashed"
    override val apiPath: String = "test/hashed"
    override val relativePath: String = "test/hashed.bin"
    override val group: ResourceGroup = TestGroup("hashed-group")
}
