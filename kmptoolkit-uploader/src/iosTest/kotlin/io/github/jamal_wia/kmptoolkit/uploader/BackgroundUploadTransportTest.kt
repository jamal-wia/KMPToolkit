package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The parts of the iOS background transport that do not need `nsurlsessiond`: which session
 * identifiers it owns, its configuration rules, the completion latch the relaunch path relies on, and
 * a relaunch for a session nobody owns completing at once.
 */
class BackgroundUploadTransportTest {

    private fun transport(prefix: String = "com.example.app.outbox.upload."): BackgroundSessionUploadTransport =
        BackgroundSessionUploadTransport(prefix, BackgroundUploadConfig(sessionIdentifierPrefix = prefix), NoopLogger)

    @Test
    fun `a session identifier yields the item id only under the transport's own prefix`() {
        val transport: BackgroundSessionUploadTransport = transport()

        assertEquals("item-42", transport.itemIdOf("com.example.app.outbox.upload.item-42"))
        assertNull(transport.itemIdOf("com.example.other.upload.item-42"))
        assertNull(transport.itemIdOf("com.example.app.outbox.upload."))
    }

    @Test
    fun `the lease comes from the config`() {
        val transport = BackgroundSessionUploadTransport("p.", BackgroundUploadConfig(lease = 5.minutes), NoopLogger)

        assertEquals(5.minutes.inWholeMilliseconds, transport.leaseMillis)
    }

    @Test
    fun `the config rejects a blank prefix and a non-positive lease`() {
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(sessionIdentifierPrefix = " ") }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(lease = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(rehandFlushWindow = (-1).minutes) }
    }

    @Test
    fun `the default prefix derives from the bundle identifier`() {
        val created = createBackgroundUploadTransport() as BackgroundSessionUploadTransport

        assertTrue(created.itemIdOf("x") == null)
        // Whatever the test bundle is called, an identifier built from it with the suffix is owned.
        val bundle: String = platform.Foundation.NSBundle.mainBundle.bundleIdentifier
            ?: "io.github.jamal_wia.kmptoolkit.uploader.unbundled"
        assertEquals("id", created.itemIdOf("$bundle.uploader.upload.id"))
    }

    @Test
    fun `the completion latch runs its action once whoever fires first`() {
        var runs = 0
        val once = OnceGuard { runs++ }

        assertTrue(once.fire())
        assertFalse(once.fire())

        assertEquals(1, runs)
    }

    @Test
    fun `a relaunch for a session no transport owns completes promptly`() = runTest {
        // Well inside the 25 s safety timeout: completing here proves the ownership check, not the timeout.
        createBackgroundUploadTransport(BackgroundUploadConfig(sessionIdentifierPrefix = "owned.prefix."))
        var completed = 0

        BackgroundUploadRelaunch.handleEvents("someone.else.item-1") { completed++ }
        // Real time, not runTest's virtual clock: the relaunch path runs on Dispatchers.Default.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            repeat(200) { if (completed == 0) kotlinx.coroutines.delay(10) }
        }

        assertEquals(1, completed)
    }

    @Test
    fun `a hand-off after a relaunch opened the item's session still starts the upload`() = runTest {
        val logger = RecordingLogger()
        val prefix = "kmptoolkit.test.rehand."
        val transport = BackgroundSessionUploadTransport(prefix, BackgroundUploadConfig(sessionIdentifierPrefix = prefix), logger)
        // A relaunch delivery opens the item's session first, to drain events — here there are none.
        transport.handleRelaunchEvents("item-1") {}

        // Then the engine hands the item off. The request has no URL, so whatever this hand-off starts
        // can reach no server; whether the platform refuses it before creating a task or creates one that
        // fails, the transport logs that it tried — which joining the idle session never does.
        transport.launch("item-1", isRehandOff = false, request = UploadRequest(url = "", fields = emptyList()))
        val attempts: List<String> = listOf("Starting upload — item=item-1", "Upload cannot start — item=item-1")
        // Real time, not runTest's virtual clock: the session reports its tasks on its own queue.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            repeat(500) { if (attempts.none(logger::contains)) kotlinx.coroutines.delay(10) }
        }

        assertTrue(
            attempts.any(logger::contains),
            "the hand-off must start its upload, not join the relaunch's idle session: ${logger.messages()}",
        )
    }

    /** Collects every message, from whichever queue the session calls back on. */
    private class RecordingLogger : io.github.jamal_wia.kmptoolkit.logging.Logger {
        private val recorded = kotlin.concurrent.AtomicReference<List<String>>(emptyList())

        override val tag: String = "test"

        override fun isLoggable(level: io.github.jamal_wia.kmptoolkit.logging.LogLevel): Boolean = true

        override fun log(level: io.github.jamal_wia.kmptoolkit.logging.LogLevel, throwable: Throwable?, message: () -> String) {
            val line: String = message()
            while (true) {
                val current: List<String> = recorded.value
                if (recorded.compareAndSet(current, current + line)) return
            }
        }

        fun messages(): List<String> = recorded.value

        fun contains(prefix: String): Boolean = recorded.value.any { it.startsWith(prefix) }
    }

    @Test
    fun `the multipart body streams text and file parts into the temporary file`() {
        val source: String = platform.Foundation.NSTemporaryDirectory() + "/kmptoolkit_upload_test_source.bin"
        val content: String = "x".repeat(200_000) // larger than one copy chunk
        writeText(source, content)
        val bodyPath: String = platform.Foundation.NSTemporaryDirectory() + "/kmptoolkit_upload_test_body.tmp"

        val failure: String? = writeMultipartBody(
            bodyPath = bodyPath,
            boundary = "B",
            fields = listOf(UploadField.Text("note", "hi"), UploadField.File("file", "a.bin", "application/octet-stream", source)),
        )

        assertNull(failure)
        val expected: String = "--B\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nhi\r\n" +
            "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n$content\r\n--B--\r\n"
        assertEquals(expected, readText(bodyPath))
        remove(source)
        remove(bodyPath)
    }

    @Test
    fun `a missing source file fails the body and leaves nothing behind`() {
        val bodyPath: String = platform.Foundation.NSTemporaryDirectory() + "/kmptoolkit_upload_test_missing.tmp"

        val failure: String? = writeMultipartBody(
            bodyPath = bodyPath,
            boundary = "B",
            fields = listOf(UploadField.File("file", "a.bin", "application/octet-stream", "/nonexistent/a.bin")),
        )

        assertTrue(failure != null)
        assertFalse(platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath(bodyPath))
    }

    @OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun writeText(path: String, text: String) {
        platform.Foundation.NSString.create(string = text)
            .writeToFile(path, atomically = true, encoding = platform.Foundation.NSUTF8StringEncoding, error = null)
    }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun readText(path: String): String? =
        platform.Foundation.NSString.stringWithContentsOfFile(path, encoding = platform.Foundation.NSUTF8StringEncoding, error = null)

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun remove(path: String) {
        platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}
