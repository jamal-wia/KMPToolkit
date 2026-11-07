package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the engine does with the temp file when a transfer fails or is cancelled. The contract: the
 * partial is the user's progress and stays for the next `ensureAvailable` to resume from; it is
 * deleted only on a user cancel (whatever state the file is in), an integrity failure (covered by
 * [DownloaderEngineRecoveryTest]) and a [DownloadError.NotFound] failure. Both surfaces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineTempFileTest {

    private val group = TestGroup("model_bundle")
    private val model = TestUnit(id = "model", group = group)

    init {
        group.units = listOf(model)
    }

    private fun TestScope.engine(
        downloader: BackgroundResourceDownloader,
        storage: DownloaderStorage,
    ): DefaultDownloaderEngine = DefaultDownloaderEngine(
        storage = storage,
        notifier = RecordingNotifier(),
        backgroundDownloader = downloader,
        stateStore = InMemoryStateStore(),
        bundledResourcesPresent = false,
        groups = listOf(group),
        dispatchers = TestDownloadDispatchers(this),
        logger = NoopLogger,
    )

    private fun failing(message: String): ScriptedAttemptsDownloader =
        ScriptedAttemptsDownloader { _, unit ->
            emit(BackgroundDownloadEvent.Progress(unit, 0.9f))
            emit(BackgroundDownloadEvent.Error(unit, message))
        }

    private val keepingMessages: Map<String, DownloadError> = mapOf(
        "Unable to resolve host" to DownloadError.NoConnection,
        "SocketTimeoutException: timeout" to DownloadError.Timeout,
        "HTTP 503" to DownloadError.Server(503),
        "HTTP 401" to DownloadError.Unauthorized,
        "HTTP error: 403" to DownloadError.Unauthorized,
        "boom" to DownloadError.Unknown("boom"),
    )

    @Test
    fun `group - a failed transfer keeps the partial temp file`() = runTest {
        for ((message: String, expected: DownloadError) in keepingMessages) {
            val storage = SetStorage(partial = setOf("model"))

            val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
                engine(failing(message), storage).ensureAvailable(group)
            }

            assertEquals(expected, thrown.error, message)
            assertTrue(storage.deletedTempFiles.isEmpty(), "'$message' must keep the partial")
            assertEquals(TempFileState.Partial, storage.tempFileState(model), message)
        }
    }

    @Test
    fun `unit - a failed transfer keeps the partial temp file`() = runTest {
        for ((message: String, expected: DownloadError) in keepingMessages) {
            val storage = SetStorage(partial = setOf("model"))

            val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
                engine(failing(message), storage).ensureAvailable(model)
            }

            assertEquals(expected, thrown.error, message)
            assertTrue(storage.deletedTempFiles.isEmpty(), "'$message' must keep the partial")
            assertEquals(TempFileState.Partial, storage.tempFileState(model), message)
        }
    }

    @Test
    fun `a stall keeps the partial temp file on both surfaces`() = runTest {
        val stalling = { ScriptedAttemptsDownloader { _, unit ->
            emit(BackgroundDownloadEvent.Progress(unit, 0.4f))
            awaitCancellation()
        } }
        val groupStorage = SetStorage(partial = setOf("model"))
        val groupError: DownloadFailedException = assertFailsWith {
            engine(stalling(), groupStorage).ensureAvailable(group)
        }
        val unitStorage = SetStorage(partial = setOf("model"))
        val unitError: DownloadFailedException = assertFailsWith {
            engine(stalling(), unitStorage).ensureAvailable(model)
        }

        assertEquals(DownloadError.Timeout, groupError.error)
        assertEquals(DownloadError.Timeout, unitError.error)
        assertTrue(groupStorage.deletedTempFiles.isEmpty())
        assertTrue(unitStorage.deletedTempFiles.isEmpty())
    }

    @Test
    fun `group - NotFound deletes the partial temp file`() = runTest {
        val storage = SetStorage(partial = setOf("model"))

        val thrown: DownloadFailedException = assertFailsWith {
            engine(failing("HTTP 404"), storage).ensureAvailable(group)
        }

        assertEquals(DownloadError.NotFound, thrown.error)
        assertEquals(listOf("model"), storage.deletedTempFiles)
        assertEquals(TempFileState.None, storage.tempFileState(model))
    }

    @Test
    fun `unit - NotFound deletes the partial temp file`() = runTest {
        val storage = SetStorage(partial = setOf("model"))

        val thrown: DownloadFailedException = assertFailsWith {
            engine(failing("HTTP 410"), storage).ensureAvailable(model)
        }

        assertEquals(DownloadError.NotFound, thrown.error)
        assertEquals(listOf("model"), storage.deletedTempFiles)
    }

    @Test
    fun `group - a user cancel deletes the temp file whatever its state`() = runTest {
        for (state in listOf(TempFileState.Partial, TempFileState.Complete)) {
            val storage = SetStorage()
            storage.setTempState(model, state)
            val engine: DefaultDownloaderEngine = engine(failing("boom"), storage)

            engine.cancelDownload(group)

            assertEquals(listOf("model"), storage.deletedTempFiles, "state $state")
            assertEquals(GroupDownloadState.Idle, engine.downloadState(group).value)
        }
    }

    @Test
    fun `unit - a user cancel deletes the temp file whatever its state`() = runTest {
        for (state in listOf(TempFileState.Partial, TempFileState.Complete)) {
            val storage = SetStorage()
            storage.setTempState(model, state)
            val engine: DefaultDownloaderEngine = engine(failing("boom"), storage)

            engine.cancelDownload(model)
            launch { }.join()

            assertEquals(listOf("model"), storage.deletedTempFiles, "state $state")
        }
    }

    @Test
    fun `a later ensureAvailable after a failure resumes from the kept partial`() = runTest {
        val storage = SetStorage(partial = setOf("model"))
        var round = 0
        val downloader = ScriptedAttemptsDownloader { _, unit ->
            if (round == 0) {
                emit(BackgroundDownloadEvent.Error(unit, "Unable to resolve host"))
            } else {
                check(storage.tempFileState(unit) == TempFileState.Partial) { "the partial must have survived" }
                storage.setTempState(unit, TempFileState.Complete)
                emit(BackgroundDownloadEvent.FileReady(unit))
            }
        }
        val engine: DefaultDownloaderEngine = engine(downloader, storage)

        assertFailsWith<DownloadFailedException> { engine.ensureAvailable(group) }
        round = 1
        engine.ensureAvailable(group)

        assertTrue(storage.isResourceAvailable(model))
        assertTrue(storage.deletedTempFiles.isEmpty())
    }
}
