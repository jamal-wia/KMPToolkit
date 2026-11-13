package io.github.jamal_wia.kmptoolkit.downloader.testing

import io.github.jamal_wia.kmptoolkit.downloader.BackgroundDownloadEvent
import io.github.jamal_wia.kmptoolkit.downloader.BackgroundResourceDownloader
import io.github.jamal_wia.kmptoolkit.downloader.DownloadCancelledException
import io.github.jamal_wia.kmptoolkit.downloader.DownloadUnit
import io.github.jamal_wia.kmptoolkit.downloader.Downloader
import io.github.jamal_wia.kmptoolkit.downloader.GroupDownloadState
import io.github.jamal_wia.kmptoolkit.downloader.TempFileState
import io.github.jamal_wia.kmptoolkit.downloader.UnitDownloadState
import io.github.jamal_wia.kmptoolkit.downloader.createDownloader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * B round 9, Q4: the scenario script of the real-storage consumer flows (AndroidConsumerFlowRound9Test of
 * kmptoolkit-downloader: happy path, cancel then retry, a failed integrity check answered by one fresh download, a resource
 * deleted around the engine) run over [FakeDownloaderStorage] with the documented setup of docs 06-testing, and the same
 * observable results asserted: the state order, the exception types, the temp-file state afterwards, the transfer counts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeVersusRealRound9Test {

    private val group = TestGroup("g")
    private val unit = TestUnit("asset", group).also { group.units = listOf(it) }

    private class Port(private val onEnqueue: (DownloadUnit, Port, Int) -> Unit) : BackgroundResourceDownloader {
        val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 256)
        var enqueues: Int = 0

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues++
            onEnqueue(unit, this, enqueues)
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) {
            events.tryEmit(BackgroundDownloadEvent.Cancelled(unit))
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = events.filter { it.unit.id == unit.id }
    }

    /** What the real port of the consumer test does for a finished transfer, in the calls the fake accepts. */
    private fun finish(storage: FakeDownloaderStorage, unit: DownloadUnit, port: Port) {
        storage.beginTempFile(unit, null)
        storage.tempFileStates[unit.id] = TempFileState.Partial
        storage.markTempFileComplete(unit)
        port.events.tryEmit(BackgroundDownloadEvent.Progress(unit, 1f))
        port.events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
    }

    private fun downloaderOver(
        storage: FakeDownloaderStorage,
        port: Port,
        scope: kotlinx.coroutines.test.TestScope,
    ): Downloader = createDownloader(
        storage = storage,
        backgroundDownloader = port,
        groups = listOf(group),
        stateStore = InMemoryStateStore(),
        dispatchers = TestDownloadDispatchers(scope),
    )

    @Test
    fun `the happy path goes Idle then Downloading never backwards then Completed and ensureAvailable returns after Completed`() = runTest {
        val storage = FakeDownloaderStorage()
        val port = Port { u: DownloadUnit, p: Port, _: Int -> finish(storage, u, p) }
        val downloader: Downloader = downloaderOver(storage, port, this)
        val states: MutableList<GroupDownloadState> = mutableListOf()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { downloader.downloadState(group).collect { states += it } }
        downloader.ensureAvailable(group)
        assertEquals(GroupDownloadState.Completed, downloader.downloadState(group).value, "ensureAvailable returned before Completed")
        testScheduler.runCurrent()
        job.cancel()
        assertEquals(GroupDownloadState.Idle, states.first(), "$states")
        assertEquals(GroupDownloadState.Completed, states.last(), "$states")
        assertFalse(states.any { it is GroupDownloadState.Error }, "$states")
        val progress: List<Float> = states.filterIsInstance<GroupDownloadState.Downloading>().map { it.progress }
        assertEquals(progress.sorted(), progress, "progress went backwards: $states")
        assertTrue(progress.isNotEmpty() && progress.last() == 1f, "Completed was not preceded by Downloading(1f): $states")
        assertTrue(downloader.isAvailable(group))
        assertEquals(1, port.enqueues)
        assertEquals(TempFileState.None, storage.tempFileState(unit), "the commit left a temp file")
    }

    @Test
    fun `a cancel mid transfer ends with the cancel exception leaves Idle and no temp file then a retry completes`() = runTest {
        val storage = FakeDownloaderStorage()
        var hold = true
        val port = Port { u: DownloadUnit, p: Port, _: Int ->
            if (hold) {
                storage.beginTempFile(u, null)
                storage.tempFileStates[u.id] = TempFileState.Partial
            } else {
                finish(storage, u, p)
            }
        }
        val downloader: Downloader = downloaderOver(storage, port, this)
        var failure: Throwable? = null
        val call = launch(start = CoroutineStart.UNDISPATCHED) { failure = runCatching { downloader.ensureAvailable(unit) }.exceptionOrNull() }
        assertEquals(1, port.enqueues, "the transfer did not start")
        downloader.cancelDownload(unit)
        call.join()
        assertTrue(failure is DownloadCancelledException, "$failure")
        assertFalse(downloader.isAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit), "the cancelled transfer left a partial file")
        assertEquals(UnitDownloadState.Idle, downloader.unitDownloadStateFlow(unit).first())
        hold = false
        downloader.ensureAvailable(unit)
        assertTrue(downloader.isAvailable(unit))
        assertEquals(2, port.enqueues)
    }

    @Test
    fun `a failed integrity check is answered by one fresh transfer from nothing and never a failure`() = runTest {
        val storage = FakeDownloaderStorage()
        storage.failIntegrityFor += unit.id
        val seen: MutableList<TempFileState> = mutableListOf()
        val port = Port { u: DownloadUnit, p: Port, n: Int ->
            seen += storage.tempFileState(u)
            // The first commit fails the check; the engine answers with a fresh download that must pass.
            if (n == 2) storage.failIntegrityFor -= u.id
            finish(storage, u, p)
        }
        val downloader: Downloader = downloaderOver(storage, port, this)
        val states: MutableList<UnitDownloadState> = mutableListOf()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { downloader.unitDownloadStateFlow(unit).collect { states += it } }
        downloader.ensureAvailable(unit)
        testScheduler.runCurrent()
        job.cancel()
        assertEquals(2, port.enqueues)
        assertEquals(listOf<TempFileState>(TempFileState.None, TempFileState.None), seen, "the retry must start from nothing")
        assertFalse(states.any { it is UnitDownloadState.Error }, "$states")
        assertTrue(downloader.isAvailable(unit))
    }

    @Test
    fun `a resource deleted around the engine reads unavailable and the next request transfers again`() = runTest {
        val storage = FakeDownloaderStorage()
        val port = Port { u: DownloadUnit, p: Port, _: Int -> finish(storage, u, p) }
        val downloader: Downloader = downloaderOver(storage, port, this)
        downloader.ensureAvailable(unit)
        storage.deleteResource(unit)
        assertFalse(downloader.isAvailable(unit), "availability must be read from the storage")
        downloader.ensureAvailable(unit)
        assertTrue(downloader.isAvailable(unit))
        assertEquals(2, port.enqueues)
        assertFailsWith<IllegalStateException> { storage.markTempFileComplete(unit) }
    }
}
