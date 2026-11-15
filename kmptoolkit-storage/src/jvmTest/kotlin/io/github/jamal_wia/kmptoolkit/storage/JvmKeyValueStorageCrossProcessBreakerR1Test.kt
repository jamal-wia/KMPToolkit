@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.LockAcquisition
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 1 of group sto-desktop (phase A): the time bound of plan 4.11 ("lock wait deadline: 10 s, then
 * `Unavailable`") and the plain store's reads while another process holds the write lock (D-3: reads of the plain store
 * take no file lock).
 *
 * Another handle of this JVM stands in for another process (the store sees it as busy, exactly like a foreign holder).
 * Every store runs on the test scheduler, so the 10 s deadline and the 25 ms polls are virtual time.
 */
class JvmKeyValueStorageCrossProcessBreakerR1Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xstore-breaker").toFile()
    private val dataFile: File = File(directory, "breaker.kmptoolkit.storage.properties")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private suspend fun holdLockAsAnotherProcess(): LockAcquisition.Held =
        assertIs(CrossProcessLock().acquire(CrossProcessLock.lockFileOf(dataFile), temporaryPrefixOf(dataFile.name)))

    @Test
    fun `every one of five writers behind a foreign holder answers Unavailable within the one 10 s deadline`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(StorageResult.Success(Unit), JvmKeyValueStorage(dataFile, dispatcher).put("seed", "0"))
        val foreign: LockAcquisition.Held = holdLockAsAnotherProcess()
        val start: Long = testScheduler.currentTime

        // Five components of one app, each with its own instance over the same file.
        val waited: List<Long> = (0 until WRITERS).map { index: Int ->
            async(start = CoroutineStart.UNDISPATCHED) {
                val result: StorageResult<Unit> = JvmKeyValueStorage(dataFile, dispatcher).put("k$index", "v")
                assertIs<StorageError.Unavailable>(assertIs<StorageResult.Failure>(result, "writer $index").error, "writer $index")
                testScheduler.currentTime - start
            }
        }.awaitAll()

        assertEquals(DEADLINE_MS, waited.min(), "control: no writer gave up before the deadline; waited=$waited")
        assertEquals(
            List(WRITERS) { DEADLINE_MS },
            waited.sorted(),
            "virtual ms each writer waited; the k-th in-process writer must not wait k x 10 s",
        )
        foreign.close()
    }

    @Test
    fun `a read answers at once with the current value while a write waits for another process`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = JvmKeyValueStorage(dataFile, dispatcher)
        assertEquals(StorageResult.Success(Unit), storage.put("a", "1"))
        val foreign: LockAcquisition.Held = holdLockAsAnotherProcess()
        val writer = async { storage.put("b", "2") }
        runCurrent() // the write now polls for the file lock while it holds the in-process lock
        val start: Long = testScheduler.currentTime

        val read: StorageResult<String?> = storage.get("a")

        assertEquals(StorageResult.Success("1"), read)
        assertEquals(0L, testScheduler.currentTime - start, "the read waited behind a write that waits for another process")
        assertIs<StorageError.Unavailable>(assertIs<StorageResult.Failure>(writer.await()).error, "control: the write was really blocked")
        foreign.close()
    }

    private companion object {
        const val WRITERS: Int = 5
        const val DEADLINE_MS: Long = 10_000
    }
}
