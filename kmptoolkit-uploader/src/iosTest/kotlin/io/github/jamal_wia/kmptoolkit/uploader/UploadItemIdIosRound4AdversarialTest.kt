package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * The item id is the consumer's when it supplies `idGenerator`, and the Android transport accepts any id (the journal
 * even tests for names it cannot use). The iOS transport must start an upload for the same ids.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class UploadItemIdIosRound4AdversarialTest {

    private val dir: String = NSTemporaryDirectory() + "/kmptoolkit-r4-ids-" + NSUUID().UUIDString() + "/outcomes"
    private val bodies = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
        bodies.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private class CountingSession : UploadSession {
        val tasks = AtomicInt(0)
        override fun getTaskCount(callback: (Int) -> Unit) = callback(tasks.value)
        override fun cancelTasks(callback: (Int) -> Unit) = callback(0)
        override fun startUploadTask(request: NSMutableURLRequest, bodyPath: String) {
            tasks.incrementAndGet()
        }
        override fun invalidateAndCancel() = Unit
        override fun finishTasksAndInvalidate() = Unit
    }

    /** Launches [itemId] on a transport over a scripted session and returns how many upload tasks were created. */
    private fun tasksStartedFor(itemId: String): Int {
        bodies += temporaryBodyPath(itemId)
        val prefix = "kmptoolkit.test.ids.${NSUUID().UUIDString}."
        val session = AtomicReference<CountingSession?>(null)
        val transport = BackgroundSessionUploadTransport(
            identifierPrefix = prefix,
            config = BackgroundUploadConfig(sessionIdentifierPrefix = prefix),
            logger = NoopLogger,
            journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System),
            sessionFactory = UploadSessionFactory { _, _ -> CountingSession().also { session.value = it } },
        )
        transport.launch(itemId, isRehandOff = false, request = UploadRequest(url = "https://upload.invalid/x", fields = listOf(UploadField.Text("a", "b"))))
        return session.value?.tasks?.value ?: -1
    }

    @Test
    fun `an item id with a space and a dot still starts its upload`() {
        assertEquals(1, tasksStartedFor("my photo 1.jpg"))
    }

    @Test
    fun `a long id of multi-byte characters within the journal's length limit still starts its upload`() {
        assertEquals(1, tasksStartedFor("照".repeat(150)), "450 bytes of file name")
    }

    @Test
    fun `an ordinary id starts its upload`() {
        assertEquals(1, tasksStartedFor("item-" + NSUUID().UUIDString()))
    }
}
