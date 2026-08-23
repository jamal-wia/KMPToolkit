package io.github.jamal_wia.kmptoolkit.downloader.testing

import io.github.jamal_wia.kmptoolkit.downloader.DownloadError
import io.github.jamal_wia.kmptoolkit.downloader.GroupDownloadState
import io.github.jamal_wia.kmptoolkit.downloader.UnitDownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
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

        assertEquals(listOf(unit), downloader.ensuredUnits)
        assertEquals(listOf(group), downloader.ensuredGroups)
    }

    @Test
    fun `cancelDownload records the unit and invokes the order hook`() {
        val order = mutableListOf<String>()
        val downloader = FakeDownloader(onCancelUnit = { order += "cancelled:${it.id}" })

        downloader.cancelDownload(unit)

        assertEquals(listOf(unit), downloader.cancelledUnits)
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
    fun `fake storage tracks availability, deletion and a fixed size`() {
        val storage = FakeDownloaderStorage(availableIds = setOf(unit.id), sizeOnDisk = 42L)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(42L, storage.getResourceSize(unit))

        storage.deleteResource(unit)

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(listOf(unit), storage.deletedResources)
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
