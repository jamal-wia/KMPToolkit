package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cancel of a unit the engine never tracked must release what the platform and the disk may hold
 * and must not make the engine track the unit: the per-unit maps would otherwise grow with calls that
 * are not downloads (a host cancelling every id of a list it left).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineUntrackedCancelTest {

    private class CountingPort : BackgroundResourceDownloader {
        val cancelled: MutableList<String> = mutableListOf()
        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) {
            cancelled += unit.id
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = emptyFlow()
    }

    private val group = TestGroup("g")

    private fun TestScope.engine(storage: DownloaderStorage, port: BackgroundResourceDownloader) =
        DefaultDownloaderEngine(
            storage = storage,
            notifier = RecordingNotifier(),
            backgroundDownloader = port,
            stateStore = InMemoryStateStore(),
            bundledResourcesPresent = false,
            groups = listOf(group),
            dispatchers = TestDownloadDispatchers(this),
            logger = NoopLogger,
            config = DownloaderConfig(),
        )

    @Test
    fun `cancelling units that were never tracked leaves nothing for the engine-wide sweep to visit`() = runTest {
        val port = CountingPort()
        val engine: DefaultDownloaderEngine = engine(SetStorage(), port)
        val count = 25

        repeat(count) { i: Int -> engine.cancelDownload(TestUnit(id = "never-$i", group = group)) }
        assertEquals(count, port.cancelled.size, "each direct cancel reaches the platform once")

        engine.cancelAllDownloads()

        assertEquals(count, port.cancelled.size, "the sweep visits tracked units only, and none was tracked")
    }

    @Test
    fun `cancelling an untracked unit still deletes its temp file and reaches the platform`() = runTest {
        val storage = SetStorage(partial = setOf("orphan"))
        val port = CountingPort()
        val engine: DefaultDownloaderEngine = engine(storage, port)

        engine.cancelDownload(TestUnit(id = "orphan", group = group))

        assertEquals(listOf("orphan"), storage.deletedTempFiles)
        assertEquals(listOf("orphan"), port.cancelled)
    }

    @Test
    fun `a unit cancelled untracked can still be requested afterwards`() = runTest {
        val storage = SetStorage()
        val port = CountingPort()
        val engine: DefaultDownloaderEngine = engine(storage, port)
        val unit = TestUnit(id = "later", group = group)

        engine.cancelDownload(unit)

        assertTrue(!engine.hasActiveUnitDownload())
        assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(unit).first())
    }
}
