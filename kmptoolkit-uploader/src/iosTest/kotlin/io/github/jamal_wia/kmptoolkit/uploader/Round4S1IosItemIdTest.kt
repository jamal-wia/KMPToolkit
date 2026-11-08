package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * The item id is the consumer's when it supplies `idGenerator`, and the Android transport accepts any id (the journal
 * even tests for names it cannot use). The iOS transport must start an upload for the same ids.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class Round4S1IosItemIdTest {

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
    fun `an item id with a slash still starts its upload`() {
        assertEquals(1, tasksStartedFor("tenant-7/photo-42"), "the temporary body path is built from the raw id")
    }

    @Test
    fun `an item id that climbs out of the temporary directory still starts its upload and writes nothing outside it`() {
        val uuid: String = NSUUID().UUIDString()
        val outside: String = NSTemporaryDirectory() + "/../escape-$uuid.tmp"
        bodies += outside

        assertEquals(1, tasksStartedFor("../escape-$uuid"))

        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(outside), "the body was written one level above the temporary directory")
    }

    @Test
    fun `an item id of three hundred characters still starts its upload`() {
        assertEquals(1, tasksStartedFor("a".repeat(300)))
    }

    @Test
    fun `an item id with a space or a non-ASCII character still starts its upload`() {
        assertEquals(1, tasksStartedFor("photo 42 \u00e9"))
    }

    /** Guard, no red-before state: pins the upgrade continuity (a body file of a 3.0.x process is still removed). */
    @Test
    fun `a canonical uuid keeps the temporary file name it always had`() {
        val uuid: String = NSUUID().UUIDString()
        assertEquals("kmptoolkit_upload_$uuid.tmp", temporaryBodyFileName(uuid))
    }

    /** Guard: the hashed name space is disjoint from the plain one and has a fixed length. */
    @Test
    fun `an id that is not a plain file name gets a fixed-length hashed name`() {
        val name: String = temporaryBodyFileName("tenant-7/photo-42")
        assertEquals("kmptoolkit_upload_~".length + 64 + ".tmp".length, name.length)
        assertFalse(name.contains("/"))
        assertEquals(name, temporaryBodyFileName("tenant-7/photo-42"), "deterministic, so removal needs no state")
        assertFalse(name == temporaryBodyFileName("tenant-7/photo-43"))
    }
}
