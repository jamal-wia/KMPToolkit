package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * `UploadResponse` promises that a body the transport could not read completely (the connection broke) still comes
 * with the status and headers the server sent, flagged truncated, and 08 says an accepted upload must not be
 * sent again for it. The Android transport does that. The iOS delegate gets one terminal event with both a response
 * and an error when the connection drops during the answer's body; it must keep the status as well.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class Round4S4IosErrorAfterResponseTest {

    private val dir: String = NSTemporaryDirectory() + "/kmptoolkit-r4-err-" + NSUUID().UUIDString() + "/outcomes"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    }

    private fun delegate(itemId: String, journal: OutcomeJournal): UploadSessionDelegate =
        UploadSessionDelegate(
            itemId = itemId,
            scope = scope,
            logger = NoopLogger,
            journal = journal,
            terminalLock = NSLock(),
            onTerminal = {},
        )

    @Test
    fun `a connection lost after the status arrived keeps the status as a truncated answer`() {
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val delegate: UploadSessionDelegate = delegate("item-err", journal)

        delegate.complete(
            statusCode = 201L,
            headerFields = mapOf("Content-Type" to "application/json"),
            errorDescription = "The network connection was lost.",
        ) {}

        // The journal write is synchronous inside complete(): what it holds is what the engine will be told.
        val entry: JournalEntry = journal.inspect("item-err")
        assertIs<JournalEntry.Valid>(entry, "the server's 201 was dropped and reported as a transport failure, so the accepted file uploads again")
        assertEquals(201, entry.response.statusCode)
        assertTrue(entry.response.isBodyTruncated)
    }

    @Test
    fun `a connection lost after a 204 keeps the status and is not flagged truncated`() {
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val delegate: UploadSessionDelegate = delegate("item-204", journal)

        delegate.complete(statusCode = 204L, headerFields = null, errorDescription = "lost") {}

        val entry: JournalEntry = journal.inspect("item-204")
        assertIs<JournalEntry.Valid>(entry)
        assertEquals(204, entry.response.statusCode)
        assertFalse(entry.response.isBodyTruncated, "a status that carries no content has no body to cut")
        assertEquals(0, entry.response.body().size)
    }

    /** Guard of the unchanged branch: no red-before state exists, the old code also reported a failure here. */
    @Test
    fun `an error before any status is still a transport failure`() {
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val delegate: UploadSessionDelegate = delegate("item-nostatus", journal)

        delegate.complete(statusCode = -1L, headerFields = null, errorDescription = "lost") {}

        assertIs<JournalEntry.Absent>(journal.inspect("item-nostatus"), "nothing was answered, so nothing is recorded")
    }
}
