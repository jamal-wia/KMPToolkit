package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * How the engine turns what a downloader reports into a [DownloadError]. The contract
 * (`docs/kmptoolkit-downloader/07-background-downloader.md`): a status is read only where a
 * downloader states it as `HTTP <code>` / `status <code>`; a bare number in an id, URL or byte count
 * means nothing; the engine's own stall is a timeout without reading any text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineClassificationTest {

    private val group = TestGroup("catalogue")

    private suspend fun TestScope.classify(message: String, unitId: String = "asset"): DownloadError {
        val unit = TestUnit(id = unitId, group = group)
        val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
            engine(ErrorDownloader(message)).ensureAvailable(unit)
        }
        return thrown.error
    }

    private fun TestScope.engine(downloader: BackgroundResourceDownloader): DefaultDownloaderEngine =
        DefaultDownloaderEngine(
            storage = SetStorage(),
            notifier = RecordingNotifier(),
            backgroundDownloader = downloader,
            stateStore = InMemoryStateStore(),
            bundledResourcesPresent = false,
            groups = listOf(group),
            dispatchers = TestDownloadDispatchers(this),
            logger = NoopLogger,
        )

    @Test
    fun `a stall is a Timeout whatever digits the unit's text holds`() = runTest {
        for (unitId in listOf("40417", "1403", "50012", "asset")) {
            val unit = TestUnit(id = unitId, group = group)
            val thrown: DownloadFailedException = assertFailsWith<DownloadFailedException> {
                engine(StallAfterProgressDownloader()).ensureAvailable(unit)
            }
            assertEquals(DownloadError.Timeout, thrown.error, "unit '$unitId'")
        }
    }

    @Test
    fun `404 and 410 statuses are NotFound`() = runTest {
        for (message in listOf("HTTP 404", "HTTP error: 404", "HTTP/1.1 404 Not Found", "status 410", "HTTP 410 Gone")) {
            assertEquals(DownloadError.NotFound, classify(message), message)
        }
    }

    @Test
    fun `401 and 403 statuses are Unauthorized`() = runTest {
        for (message in listOf("HTTP 401", "HTTP error: 403", "status code: 403", "HTTP 401 Unauthorized")) {
            assertEquals(DownloadError.Unauthorized, classify(message), message)
        }
    }

    @Test
    fun `a 5xx status is Server with its code`() = runTest {
        assertEquals(DownloadError.Server(500), classify("HTTP error: 500"))
        assertEquals(DownloadError.Server(502), classify("status 502"))
        assertEquals(DownloadError.Server(503), classify("HTTP 503 for TestUnit(x)"))
    }

    @Test
    fun `another status such as 416 or 429 is Unknown with the raw text`() = runTest {
        assertEquals(DownloadError.Unknown("HTTP 416"), classify("HTTP 416"))
        assertEquals(DownloadError.Unknown("HTTP 429 Too Many Requests"), classify("HTTP 429 Too Many Requests"))
    }

    @Test
    fun `a number that is not a stated status is never read as one`() = runTest {
        for (message in listOf(
            "expected 4040312 bytes, got 1200",
            "java.io.FileNotFoundException: https://cdn.example.com/assets/40123.zip",
            "failed to write https://a.b/40412",
            "asset 40317 is corrupt",
            "length mismatch: 403 of 5001",
        )) {
            assertEquals(DownloadError.Unknown(message), classify(message), message)
        }
    }

    @Test
    fun `a status in the message is read even when the unit id holds other digits`() = runTest {
        assertEquals(DownloadError.Server(503), classify("HTTP 503 for TestUnit(40417)", unitId = "40417"))
    }

    @Test
    fun `transport phrases are still classified`() = runTest {
        assertEquals(DownloadError.NoConnection, classify("Unable to resolve host \"cdn.example.com\""))
        assertEquals(DownloadError.Timeout, classify("SocketTimeoutException: timeout"))
        assertEquals(DownloadError.Timeout, classify("The request timed out."))
        assertTrue(classify("boom") is DownloadError.Unknown)
    }

    private class ErrorDownloader(private val message: String) : BackgroundResourceDownloader {
        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            emit(BackgroundDownloadEvent.Error(unit = unit, message = message))
        }
    }

    private class StallAfterProgressDownloader : BackgroundResourceDownloader {
        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
            emit(BackgroundDownloadEvent.Progress(unit = unit, fraction = 0.1f))
            awaitCancellation()
        }
    }
}
