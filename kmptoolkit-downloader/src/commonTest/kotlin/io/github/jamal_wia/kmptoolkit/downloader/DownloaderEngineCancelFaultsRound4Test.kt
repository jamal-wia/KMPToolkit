package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A cancel is the logout path. A port or a storage that throws while the engine cancels must neither
 * break the cancel nor leave a caller waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineCancelFaultsRound4Test {

    private val group = TestGroup("g")
    private val groupUnit = TestUnit(id = "in-group", group = group)
    private val loneUnit = TestUnit(id = "lone", group = group)

    init {
        group.units = listOf(groupUnit)
    }

    private class Boom(message: String) : RuntimeException(message)

    private class HangingPort(private val cancelThrows: Boolean) : BackgroundResourceDownloader {
        var enqueues: Int = 0
        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues++
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) {
            if (cancelThrows) throw Boom("port cancel")
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow { awaitCancellation() }
    }

    private class ThrowingDeleteStorage(private val inner: SetStorage) : DownloaderStorage by inner {
        override fun deleteTempFile(unit: DownloadUnit) = throw Boom("delete temp file")
    }

    private fun TestScope.engine(port: BackgroundResourceDownloader, storage: DownloaderStorage): DefaultDownloaderEngine =
        DefaultDownloaderEngine(
            storage = storage,
            notifier = RecordingNotifier(),
            backgroundDownloader = port,
            stateStore = InMemoryStateStore(),
            bundledResourcesPresent = false,
            groups = listOf(group),
            dispatchers = TestDownloadDispatchers(this),
            logger = NoopLogger,
        )

    private suspend fun TestScope.cancelEverythingEndsEveryCaller(engine: DefaultDownloaderEngine, port: HangingPort) {
        val groupCall = async(start = CoroutineStart.UNDISPATCHED) { runCatching { engine.ensureAvailable(group) } }
        val unitCall = async(start = CoroutineStart.UNDISPATCHED) { runCatching { engine.ensureAvailable(loneUnit) } }
        runCurrent()
        assertEquals(2, port.enqueues)

        engine.cancelAllDownloads()
        runCurrent()

        assertTrue(groupCall.await().exceptionOrNull() is DownloadCancelledException, "group caller: ${groupCall.await()}")
        assertTrue(unitCall.await().exceptionOrNull() is DownloadCancelledException, "unit caller: ${unitCall.await()}")
        assertFalse(engine.hasActiveUnitDownload())
        assertEquals(GroupDownloadState.Idle, engine.downloadState(group).value)
        assertEquals(UnitDownloadState.Idle, engine.unitDownloadStateFlow(loneUnit).first())
    }

    @Test
    fun `a port that throws from cancelDownload does not break cancelAllDownloads or leave a caller waiting`() = runTest {
        val port = HangingPort(cancelThrows = true)
        cancelEverythingEndsEveryCaller(engine(port, SetStorage()), port)
    }

    @Test
    fun `a storage that throws from deleteTempFile does not break cancelAllDownloads or leave a caller waiting`() = runTest {
        val port = HangingPort(cancelThrows = false)
        cancelEverythingEndsEveryCaller(engine(port, ThrowingDeleteStorage(SetStorage())), port)
    }

    @Test
    fun `cancelling a unit nobody ever asked about survives a port and a storage that both throw`() = runTest {
        val engine = engine(HangingPort(cancelThrows = true), ThrowingDeleteStorage(SetStorage()))
        engine.cancelDownload(TestUnit(id = "never-seen", group = group))
        engine.cancelDownload(group)
        engine.cancelAllDownloads()
        assertFalse(engine.hasActiveUnitDownload())
    }
}
