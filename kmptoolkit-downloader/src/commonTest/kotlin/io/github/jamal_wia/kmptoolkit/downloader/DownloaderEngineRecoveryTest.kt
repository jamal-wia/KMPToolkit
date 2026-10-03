package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * What the engine does with whatever a transfer left on disk, on both surfaces
 * (`ensureAvailable(group)` and `ensureAvailable(unit)`).
 *
 * The contract under test: only a [TempFileState.Complete] temp file is committed without a
 * transfer. A [TempFileState.Partial] one is resumed — attached to when its transfer is still
 * running, re-enqueued otherwise — and never committed, however it got there. Committing a partial
 * file handed consumers a truncated resource with no error after any interrupted download. Bytes
 * that fail their integrity check are downloaded once more, then reported as
 * [DownloadError.Corrupted].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineRecoveryTest {

    private val group = TestGroup("model_bundle")
    private val model = TestUnit(id = "model", group = group)

    init {
        group.units = listOf(model)
    }

    // -- Partial: resumed, never committed ---------------------------------------------------

    @Test
    fun `group - a partial temp file with no transfer running is resumed and not committed`() = runTest {
        val storage = TempStateStorage(partial = model)
        val downloader = ScriptedDownloader(storage)

        engine(storage, downloader).ensureAvailable(group)

        assertEquals(listOf("enqueue", "fileReady", "mark", "commit"), storage.events)
        assertTrue(storage.isResourceAvailable(model))
    }

    @Test
    fun `unit - a partial temp file with no transfer running is resumed and not committed`() = runTest {
        val storage = TempStateStorage(partial = model)
        val downloader = ScriptedDownloader(storage)
        val engine: DefaultDownloaderEngine = engine(storage, downloader)

        engine.ensureAvailable(model)

        assertEquals(listOf("enqueue", "fileReady", "mark", "commit"), storage.events)
        assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(model).first())
    }

    @Test
    fun `group - a partial temp file whose transfer is still running is attached to and not committed`() = runTest {
        // A caller that navigated away leaves the transfer running; the next ensureAvailable used to
        // find the growing temp file and commit it while the transfer was still writing into it.
        val storage = TempStateStorage(partial = model)
        val downloader = ScriptedDownloader(storage, inProgress = true)

        engine(storage, downloader).ensureAvailable(group)

        assertEquals(listOf("fileReady", "mark", "commit"), storage.events)
    }

    @Test
    fun `unit - a partial temp file whose transfer is still running is attached to and not committed`() = runTest {
        val storage = TempStateStorage(partial = model)
        val downloader = ScriptedDownloader(storage, inProgress = true)

        engine(storage, downloader).ensureAvailable(model)

        assertEquals(listOf("fileReady", "mark", "commit"), storage.events)
    }

    @Test
    fun `a downloader that marks its own temp file complete is not marked again`() = runTest {
        val storage = TempStateStorage()
        val downloader = ScriptedDownloader(storage, marksItself = true)

        engine(storage, downloader).ensureAvailable(group)

        assertEquals(listOf("enqueue", "mark", "fileReady", "commit"), storage.events)
    }

    // -- Complete: committed without a transfer ----------------------------------------------

    @Test
    fun `group - a complete temp file is committed without a transfer`() = runTest {
        val storage = TempStateStorage(complete = model)
        val downloader = ScriptedDownloader(storage)
        val engine: DefaultDownloaderEngine = engine(storage, downloader)

        engine.ensureAvailable(group)

        assertEquals(listOf("commit"), storage.events)
        assertEquals(GroupDownloadState.Completed, engine.downloadState(group).value)
    }

    @Test
    fun `unit - a complete temp file is committed without a transfer`() = runTest {
        val storage = TempStateStorage(complete = model)
        val downloader = ScriptedDownloader(storage)
        val engine: DefaultDownloaderEngine = engine(storage, downloader)

        engine.ensureAvailable(model)

        assertEquals(listOf("commit"), storage.events)
        assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(model).first())
    }

    // -- Integrity: one fresh download, then Corrupted ---------------------------------------

    @Test
    fun `group - bytes that fail their check once are downloaded again and committed`() = runTest {
        val storage = TempStateStorage(integrityFailures = 1)
        val downloader = ScriptedDownloader(storage)
        val engine: DefaultDownloaderEngine = engine(storage, downloader)

        engine.ensureAvailable(group)

        assertEquals(2, downloader.enqueueCount)
        assertTrue(storage.isResourceAvailable(model))
        assertEquals(GroupDownloadState.Completed, engine.downloadState(group).value)
    }

    @Test
    fun `unit - bytes that fail their check once are downloaded again and committed`() = runTest {
        val storage = TempStateStorage(integrityFailures = 1)
        val downloader = ScriptedDownloader(storage)
        val engine: DefaultDownloaderEngine = engine(storage, downloader)

        engine.ensureAvailable(model)

        assertEquals(2, downloader.enqueueCount)
        assertEquals(UnitDownloadState.Completed, engine.unitDownloadStateFlow(model).first())
    }

    @Test
    fun `a recovered complete temp file that fails its check is downloaded again`() = runTest {
        val storage = TempStateStorage(complete = model, integrityFailures = 1)
        val downloader = ScriptedDownloader(storage)

        engine(storage, downloader).ensureAvailable(group)

        assertEquals(1, downloader.enqueueCount)
        assertTrue(storage.isResourceAvailable(model))
    }

    @Test
    fun `group - bytes that fail their check twice report Corrupted and commit nothing`() = runTest {
        val storage = TempStateStorage(integrityFailures = 2)
        val downloader = ScriptedDownloader(storage)
        val notifier = RecordingNotifier()
        val engine: DefaultDownloaderEngine = engine(storage, downloader, notifier)

        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
            engine.ensureAvailable(group)
        }

        assertTrue(thrown.error is DownloadError.Corrupted, "expected Corrupted, was ${thrown.error}")
        assertEquals(GroupDownloadState.Error(thrown.error), engine.downloadState(group).value)
        assertTrue(notifier.calls.any { it.kind == RecordingNotifier.Kind.ERROR })
        assertEquals(2, downloader.enqueueCount)
        assertFalse(storage.isResourceAvailable(model))
        assertEquals(TempFileState.None, storage.tempFileState(model))
    }

    @Test
    fun `unit - bytes that fail their check twice report Corrupted and commit nothing`() = runTest {
        val storage = TempStateStorage(integrityFailures = 2)
        val downloader = ScriptedDownloader(storage)
        val engine: DefaultDownloaderEngine = engine(storage, downloader)

        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
            engine.ensureAvailable(model)
        }

        assertTrue(thrown.error is DownloadError.Corrupted, "expected Corrupted, was ${thrown.error}")
        assertEquals(UnitDownloadState.Error(thrown.error), engine.unitDownloadStateFlow(model).first())
        assertEquals(2, downloader.enqueueCount)
        assertFalse(storage.isResourceAvailable(model))
    }

    @Test
    fun `a commit failure that is not an integrity failure is reported at once as Storage`() = runTest {
        // A full disk is not fixed by downloading again, so it must not spend a retry.
        val storage = TempStateStorage(commitFailure = IllegalStateException("No space left on device"))
        val downloader = ScriptedDownloader(storage)

        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
            engine(storage, downloader).ensureAvailable(model)
        }

        assertTrue(thrown.error is DownloadError.Storage, "expected Storage, was ${thrown.error}")
        assertEquals(1, downloader.enqueueCount)
    }

    // -- Test wiring -----------------------------------------------------------------------

    private fun TestScope.engine(
        storage: DownloaderStorage,
        downloader: BackgroundResourceDownloader,
        notifier: RecordingNotifier = RecordingNotifier(),
    ): DefaultDownloaderEngine = DefaultDownloaderEngine(
        storage = storage,
        notifier = notifier,
        backgroundDownloader = downloader,
        stateStore = InMemoryStateStore(),
        bundledResourcesPresent = false,
        groups = listOf(group),
        dispatchers = TestDownloadDispatchers(this),
        logger = NoopLogger,
    )

    /**
     * Keeps the storage contract the engine relies on: partial and complete are separate states,
     * marking moves one to the other, and only a complete temp file commits — exactly as the real
     * storages refuse a partial one, which is what makes a wrongly committed partial file fail here
     * rather than pass silently. [events] records the order of everything that touches disk.
     */
    private class TempStateStorage(
        partial: DownloadUnit? = null,
        complete: DownloadUnit? = null,
        private var integrityFailures: Int = 0,
        private val commitFailure: Exception? = null,
    ) : DownloaderStorage {
        val events: MutableList<String> = mutableListOf()
        private val available: MutableSet<String> = mutableSetOf()
        val tempStates: MutableMap<String, TempFileState> = mutableMapOf()

        init {
            partial?.let { tempStates[it.id] = TempFileState.Partial }
            complete?.let { tempStates[it.id] = TempFileState.Complete }
        }

        override fun isResourceAvailable(unit: DownloadUnit): Boolean = unit.id in available
        override fun tempFileState(unit: DownloadUnit): TempFileState =
            tempStates[unit.id] ?: TempFileState.None

        override fun getTempFileSize(unit: DownloadUnit): Long =
            if (tempStates[unit.id] == TempFileState.Partial) 512L else 0L

        override fun markTempFileComplete(unit: DownloadUnit) {
            events += "mark"
            check(tempStates[unit.id] != null) { "no temp file to mark for $unit" }
            tempStates[unit.id] = TempFileState.Complete
        }

        override suspend fun commitResource(unit: DownloadUnit) {
            events += "commit"
            check(tempStates[unit.id] == TempFileState.Complete) { "no complete temp file for $unit" }
            commitFailure?.let { throw it }
            if (integrityFailures > 0) {
                integrityFailures--
                tempStates.remove(unit.id)
                throw ResourceIntegrityException("SHA-256 mismatch for $unit")
            }
            tempStates.remove(unit.id)
            available += unit.id
        }

        override fun deleteTempFile(unit: DownloadUnit) {
            tempStates.remove(unit.id)
        }

        override fun getResourcePath(unit: DownloadUnit): String = ""
        override fun getTempFilePath(unit: DownloadUnit): String = ""
        override fun getResourceSize(unit: DownloadUnit): Long = 0L
        override fun deleteResource(unit: DownloadUnit) {
            available.remove(unit.id)
        }
    }

    /**
     * Writes into [storage] the way a real transfer does — partial bytes, then FileReady — and
     * marks the file complete itself when [marksItself] says so. [inProgress] models a transfer
     * that was already running before the engine looked.
     */
    private class ScriptedDownloader(
        private val storage: TempStateStorage,
        private val inProgress: Boolean = false,
        private val marksItself: Boolean = false,
    ) : BackgroundResourceDownloader {
        var enqueueCount: Int = 0
            private set

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueueCount++
            storage.events += "enqueue"
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = inProgress
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            storage.tempStates[unit.id] = TempFileState.Partial
            emit(BackgroundDownloadEvent.Progress(unit = unit, fraction = 0.5f))
            if (marksItself) storage.markTempFileComplete(unit)
            storage.events += "fileReady"
            emit(BackgroundDownloadEvent.FileReady(unit = unit))
        }
    }
}
