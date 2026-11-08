package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The text classifier of a transfer's error message, held to the grammar the background-downloader
 * guide documents, with messages at the edges: an odd status, a status inside other words, a message
 * of a size a server body can reach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineClassifierRound4Test {

    private val group = TestGroup("g")
    private val unit = TestUnit(id = "model", group = group)

    init {
        group.units = listOf(unit)
    }

    /** One failing attempt with [message], the retry budget already spent, so the call ends on it. */
    private suspend fun TestScope.classify(message: String): DownloadError {
        val stateStore = InMemoryStateStore()
        stateStore.writeInt("download_stall_count_${unit.id}", 2)
        val port = ScriptedAttemptsDownloader(
            clock = { testScheduler.currentTime },
            script = { _, u -> emit(BackgroundDownloadEvent.Error(u, message)) },
        )
        val engine = DefaultDownloaderEngine(
            storage = SetStorage(),
            notifier = RecordingNotifier(),
            backgroundDownloader = port,
            stateStore = stateStore,
            bundledResourcesPresent = false,
            groups = listOf(group),
            dispatchers = TestDownloadDispatchers(this),
            logger = NoopLogger,
        )
        return assertFailsWith<DownloadFailedException> { engine.ensureAvailable(group) }.error
    }

    @Test
    fun `the documented shapes of a stated status classify as documented`() = runTest {
        assertEquals(DownloadError.NotFound, classify("HTTP 404"))
        assertEquals(DownloadError.NotFound, classify("HTTP/1.1 410 Gone"))
        assertEquals(DownloadError.NotFound, classify("HTTP/2 404"))
        assertEquals(DownloadError.NotFound, classify("HTTP error: 404"))
        assertEquals(DownloadError.NotFound, classify("status 404"))
        assertEquals(DownloadError.NotFound, classify("Status Code: 410"))
        assertEquals(DownloadError.Unauthorized, classify("HTTP 401"))
        assertEquals(DownloadError.Unauthorized, classify("http\n403"))
        assertEquals(DownloadError.Server(503), classify("HTTP 503: upstream said Forbidden"))
        assertEquals(DownloadError.Server(599), classify("status code 599"))
    }

    @Test
    fun `a number that is not a stated status is never one`() = runTest {
        for (message in listOf(
            "HTTP 4040", "HTTP 40", "fetched 404 bytes", "https://cdn/assets/404.zip", "id 503 failed",
            "File not found", "status4040", "HTTP 404x", "xhttp 404", "http 1404",
        )) {
            val error: DownloadError = classify(message)
            assertTrue(error is DownloadError.Unknown, "'$message' classified as $error")
        }
    }

    @Test
    fun `a status outside the documented classes is Unknown with the raw text`() = runTest {
        for (code in listOf(416, 429, 400, 301, 600, 999, 200, 100)) {
            val message = "HTTP $code: something"
            assertEquals(DownloadError.Unknown(message), classify(message), message)
        }
    }

    @Test
    fun `words classify only without a stated status`() = runTest {
        assertEquals(DownloadError.Unauthorized, classify("Forbidden by policy"))
        assertEquals(DownloadError.Timeout, classify("the read timed out"))
        assertEquals(DownloadError.NoConnection, classify("Unable to resolve host"))
        assertEquals(DownloadError.NoConnection, classify("No Internet"))
        assertEquals(DownloadError.Unknown("HTTP 429: network busy"), classify("HTTP 429: network busy"))
    }

    @Test
    fun `an empty or blank message is Unknown and never throws`() = runTest {
        assertEquals(DownloadError.Unknown(""), classify(""))
        assertEquals(DownloadError.Unknown("   \n\t "), classify("   \n\t "))
    }

    @Test
    fun `a message of a thousand status words with whitespace between classifies in bounded time`() = runTest {
        val message: String = "status ".repeat(20_000) + "x"
        val started = TimeSource.Monotonic.markNow()
        classify(message)
        assertTrue(started.elapsedNow().inWholeMilliseconds < 2_000, "took ${started.elapsedNow()}")
    }
}
