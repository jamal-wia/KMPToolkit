package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.concurrent.AtomicInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Round 6 (resource accounting): a sweep that runs while another coroutine journals an answer must not cost that answer
 * its file (2500 writes of a 900 kB entry beside three sweepers that keep every entry). Guard: it did not reproduce a loss.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSweepVersusWriteRound6AdversarialTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-r6w-" + NSUUID().UUIDString()

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    @Test
    fun `a sweep running beside a journal write never makes the write fail`() = runBlocking {
        val journal = OutcomeJournal(IosOutcomeStorage("$root/outcomes"), UploaderClock.System)
        val response = UploadResponse(201, emptyMap(), ByteArray(900_000) { 7 }, false)
        val failures = AtomicInt(0)
        val writes = AtomicInt(0)
        withTimeout(90.seconds) {
            val scope = CoroutineScope(Dispatchers.Default)
            val sweepers = (0 until 3).map {
                scope.async { while (writes.value < 2500) journal.sweep { _, _, _, _ -> JournalSettleOutcome.NOT_LANDED } }
            }
            val writer = scope.async {
                while (writes.value < 2500) {
                    if (!journal.record("item-w", response, attemptStamp = writes.value)) failures.incrementAndGet()
                    writes.incrementAndGet()
                }
            }
            writer.await()
            sweepers.forEach { it.await() }
        }
        assertTrue(failures.value == 0, "${failures.value} of ${writes.value} journal writes failed while a sweep ran beside them")
    }
}

