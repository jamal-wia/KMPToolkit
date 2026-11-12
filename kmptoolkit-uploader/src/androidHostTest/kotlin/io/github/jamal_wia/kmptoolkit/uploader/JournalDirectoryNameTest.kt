package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * The outcome journal's directory name ([UploadTransportConfig.journalDirectoryName]) and the temporary files the
 * journal's atomic writes leave: where entries go, what the worker does with a job that carries no name (enqueued by
 * 4.x), and that leftovers of either temporary-file naming are swept.
 */
@RunWith(AndroidJUnit4::class)
class JournalDirectoryNameTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root: File = createTempDirectory("journal-dir-name").toFile()
    private var engine: UploaderEngine? = null

    @BeforeTest
    fun clean() {
        File(context.noBackupFilesDir, "kmptoolkit-uploader").deleteRecursively()
        File(context.noBackupFilesDir, CUSTOM).deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    @AfterTest
    fun cleanUp() {
        engine?.close()
        File(context.noBackupFilesDir, "kmptoolkit-uploader").deleteRecursively()
        File(context.noBackupFilesDir, CUSTOM).deleteRecursively()
        root.deleteRecursively()
    }

    @Test
    fun `the default directory name is the one earlier versions used`() {
        assertEquals("kmptoolkit-uploader", UploadTransportConfig().journalDirectoryName)
        assertEquals("kmptoolkit-uploader", UploadTransportConfig.DEFAULT_JOURNAL_DIRECTORY_NAME)
        assertEquals("other", UploadTransportConfig().copy(journalDirectoryName = "other").journalDirectoryName)
    }

    @Test
    fun `a name that is not one plain file name is rejected`() {
        for (bad in listOf("", ".", "..", "a/b", "a\u0000b", "x".repeat(256))) {
            assertFailsWith<IllegalArgumentException>("'$bad'") { UploadTransportConfig(journalDirectoryName = bad) }
        }
    }

    @Test
    fun `entries go under the configured directory name and not under the default one`() {
        assertTrue(AndroidOutcomeStorage.forContext(context, CUSTOM).write("item-1", byteArrayOf(1)))

        assertTrue(File(context.noBackupFilesDir, "$CUSTOM/outcomes/item-1").isFile)
        assertFalse(File(context.noBackupFilesDir, "kmptoolkit-uploader").exists(), "the default directory is not created")
    }

    @Test
    fun `two journals with different names do not see each other's entries`() {
        val first: AndroidOutcomeStorage = AndroidOutcomeStorage.forContext(context)
        val second: AndroidOutcomeStorage = AndroidOutcomeStorage.forContext(context, CUSTOM)
        assertTrue(first.write("same-id", byteArrayOf(1)))
        assertTrue(second.write("same-id", byteArrayOf(2)))

        assertContentEquals(byteArrayOf(1), first.read("same-id"))
        assertContentEquals(byteArrayOf(2), second.read("same-id"))
        second.delete("same-id")
        assertContentEquals(byteArrayOf(1), first.read("same-id"))
    }

    @Test
    fun `a finished write leaves no temporary file and a failed one leaves the previous entry`() {
        val dir = File(root, "outcomes")
        val partial = File(root, "partial")
        val storage = AndroidOutcomeStorage(dir, partial)
        assertTrue(storage.write("a", byteArrayOf(1)))
        assertTrue(storage.write("a", byteArrayOf(2)))

        assertContentEquals(byteArrayOf(2), storage.read("a"))
        assertEquals(emptyList(), partial.list()?.toList().orEmpty())
        // The final name is a directory: the write fails, nothing is left behind, and the directory is untouched.
        File(dir, "dir-id").mkdirs()
        assertFalse(storage.write("dir-id", byteArrayOf(3)))
        assertEquals(emptyList(), partial.list()?.toList().orEmpty())
        assertTrue(File(dir, "dir-id").isDirectory)
    }

    @Test
    fun `old leftovers of either temporary naming are purged and young ones are kept`() {
        val dir = File(root, "outcomes")
        val partial = File(root, "partial")
        partial.mkdirs()
        val now = 10_000_000L
        val storage = AndroidOutcomeStorage(dir, partial, leftoverAgeMillis = 1_000L, now = { now })
        fun leftover(name: String, age: Long): File =
            File(partial, name).also { it.writeBytes(byteArrayOf(1)); it.setLastModified(now - age) }
        val oldFiles: File = leftover(".kmptoolkit-123456.tmp", 5_000L)
        val oldUuid: File = leftover("item-1.3f1c9a52-aaaa-bbbb-cccc-0123456789ab", 5_000L)
        val youngFiles: File = leftover(".kmptoolkit-654321.tmp", 10L)
        val youngUuid: File = leftover("item-2.3f1c9a52-aaaa-bbbb-cccc-0123456789ab", 10L)

        storage.purgeLeftovers()

        assertFalse(oldFiles.exists())
        assertFalse(oldUuid.exists())
        assertTrue(youngFiles.exists())
        assertTrue(youngUuid.exists())
    }

    @Test
    fun `a job that carries the journal directory name replays the entry from that directory`() = runTest {
        val handler = ReplayHandler()
        startEngine(handler)
        OutcomeJournal(AndroidOutcomeStorage.forContext(context, CUSTOM), UploaderClock.System).record("item-1", sent())

        val result: ListenableWorker.Result = runWorker(CUSTOM)

        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(listOf<UploadResponse?>(sent()), handler.delivered, "the journaled response reaches onDelivered")
        assertFalse(File(context.noBackupFilesDir, "$CUSTOM/outcomes/item-1").exists(), "the entry is gone once settled")
    }

    @Test
    fun `a job enqueued by an earlier version has no name and uses the default directory`() = runTest {
        val handler = ReplayHandler()
        startEngine(handler)
        OutcomeJournal(AndroidOutcomeStorage.forContext(context), UploaderClock.System).record("item-1", sent())

        val result: ListenableWorker.Result = runWorker(journalDirectoryName = null)

        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(listOf<UploadResponse?>(sent()), handler.delivered)
        assertFalse(File(context.noBackupFilesDir, "kmptoolkit-uploader/outcomes/item-1").exists())
    }

    @Test
    fun `an unusable name in the job's data falls back to the default directory`() = runTest {
        val handler = ReplayHandler()
        startEngine(handler)
        OutcomeJournal(AndroidOutcomeStorage.forContext(context), UploaderClock.System).record("item-1", sent())

        val result: ListenableWorker.Result = runWorker("../escape")

        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(listOf<UploadResponse?>(sent()), handler.delivered)
    }

    private suspend fun TestScope.startEngine(handler: ReplayHandler) {
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), backgroundScope)
        engine = started
        UploaderEngineRegistry.register(started)
        started.enqueue(handler, "payload")
        started.drain() // hands off to the recording transport, leaving item-1 in flight
    }

    private suspend fun runWorker(journalDirectoryName: String?): ListenableWorker.Result =
        TestListenableWorkerBuilder<UploadHandlerWorker>(context)
            .setInputData(
                if (journalDirectoryName == null) {
                    workDataOf(UploadHandlerWorker.ITEM_ID_KEY to "item-1", UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to 50L)
                } else {
                    workDataOf(
                        UploadHandlerWorker.ITEM_ID_KEY to "item-1",
                        UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to 50L,
                        UploadHandlerWorker.JOURNAL_DIRECTORY_NAME_KEY to journalDirectoryName,
                    )
                },
            )
            .build()
            .doWork()

    private fun sent() = UploadResponse(201, mapOf("Location" to "/jobs/9"), """{"job":"j9"}""".encodeToByteArray(), false)

    private class NoopTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    /** Prepares a request that is never sent: the tests replay a journaled answer instead of uploading. */
    private class ReplayHandler : UploadHandler<String>(NoopTransport()) {
        override val type: String = "journal-dir"
        val prepared = AtomicInteger(0)
        val delivered = java.util.Collections.synchronizedList(mutableListOf<UploadResponse?>())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw

        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            prepared.incrementAndGet()
            return UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:9/never-contacted", fields = emptyList()))
        }

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered += response
        }
    }

    private companion object {
        const val CUSTOM: String = "kmptoolkit-uploader-second"
    }
}
