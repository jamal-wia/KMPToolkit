package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A [DownloadUnit.relativePath] the storage rejects is a catalogue error: the engine fails the unit
 * before any transfer, on both surfaces, and the cause is the typed exception.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEnginePathSafetyTest {

    private val group = TestGroup("bad_path_bundle")
    private val badUnit = TestUnit(id = "bad", group = group)

    init {
        group.units = listOf(badUnit)
    }

    // 19
    @Test
    fun `ensureAvailable of a unit with a rejected relativePath fails before any transfer`() = runTest {
        val downloader = CountingDownloader()
        val engine: DefaultDownloaderEngine = engine(downloader, this)

        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> { engine.ensureAvailable(badUnit) }

        assertTrue(thrown.error is DownloadError.Unknown, "expected Unknown but was ${thrown.error}")
        val cause: InvalidDownloadUnitException = thrown.cause as InvalidDownloadUnitException
        assertEquals(InvalidDownloadUnitException.Reason.ESCAPES_BASE_DIRECTORY, cause.reason)
        assertEquals(badUnit, thrown.unit)
        assertEquals(0, downloader.enqueued, "no transfer may be started for a catalogue error")
        assertEquals(UnitDownloadState.Error(thrown.error), engine.unitDownloadStateFlow(badUnit).first())
    }

    // 19
    @Test
    fun `ensureAvailable of a group with a rejected relativePath fails before any transfer`() = runTest {
        val downloader = CountingDownloader()
        val notifier = RecordingNotifier()
        val engine: DefaultDownloaderEngine = engine(downloader, this, notifier)

        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> { engine.ensureAvailable(group) }

        assertTrue(thrown.error is DownloadError.Unknown, "expected Unknown but was ${thrown.error}")
        assertTrue(thrown.cause is InvalidDownloadUnitException, "cause was ${thrown.cause}")
        assertEquals(0, downloader.enqueued, "no transfer may be started for a catalogue error")
        assertEquals(GroupDownloadState.Error(thrown.error), engine.downloadState(group).value)
        assertTrue(notifier.calls.any { it.kind == RecordingNotifier.Kind.ERROR }, "the group's notifier must hear about it")
    }

    @Test
    fun `a rejected relativePath is not available and pathOf lets the exception through`() = runTest {
        val engine: DefaultDownloaderEngine = engine(CountingDownloader(), this)

        assertFalse(engine.isAvailable(badUnit))
        assertFalse(engine.isAvailable(group))
        val thrown: InvalidDownloadUnitException = assertFailsWith<InvalidDownloadUnitException> { engine.pathOf(badUnit) }
        assertEquals(InvalidDownloadUnitException.Reason.ESCAPES_BASE_DIRECTORY, thrown.reason)
    }

    @Test
    fun `a storage that fails for another reason is not turned into a catalogue error`() = runTest {
        val storage = RejectingStorage(failure = IllegalStateException("disk gone"))
        val engine: DefaultDownloaderEngine = engine(CountingDownloader(), this, storage = storage)

        val thrown: Throwable = assertFailsWith<Throwable> { engine.ensureAvailable(badUnit) }

        assertFalse(thrown.cause is InvalidDownloadUnitException, "must not be classified as a path error: $thrown")
    }

    private fun engine(
        downloader: BackgroundResourceDownloader,
        testScope: TestScope,
        notifier: RecordingNotifier = RecordingNotifier(),
        storage: DownloaderStorage = RejectingStorage(),
    ): DefaultDownloaderEngine = DefaultDownloaderEngine(
        storage = storage,
        notifier = notifier,
        backgroundDownloader = downloader,
        stateStore = InMemoryStateStore(),
        bundledResourcesPresent = false,
        groups = listOf(group),
        dispatchers = TestDownloadDispatchers(testScope),
        logger = NoopLogger,
    )

    /** A storage whose [getResourcePath] fails the way the shipped ones do for a bad relativePath. */
    private class RejectingStorage(
        private val failure: Exception? = null,
        private val delegate: SetStorage = SetStorage(),
    ) : DownloaderStorage by delegate {
        override fun getResourcePath(unit: DownloadUnit): String =
            throw failure ?: InvalidDownloadUnitException(unit, InvalidDownloadUnitException.Reason.ESCAPES_BASE_DIRECTORY)
    }

    private class CountingDownloader : BackgroundResourceDownloader {
        var enqueued: Int = 0
            private set

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueued++
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow { awaitCancellation() }
    }
}
