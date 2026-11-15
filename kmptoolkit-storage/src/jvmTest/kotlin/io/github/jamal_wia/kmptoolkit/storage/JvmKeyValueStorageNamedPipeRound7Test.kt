package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.NamedPipes
import java.io.File
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers

/**
 * Group sto-desktop, round 7 (breaker findings B4-M1 and O12), through the plain store: it shares the cross-process lock
 * and opens its own properties file, so a named pipe at either path must answer `OperationFailed` at once instead of
 * blocking an I/O thread (and the store's in-process lock) forever, and an interrupted `tryLock` must not turn a write
 * into an unlocked one.
 */
class JvmKeyValueStorageNamedPipeRound7Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-plain-round7").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `a named pipe at the lock path fails a write at once and the store works when the pipe is gone`() {
        val file = File(directory, "store.properties")
        val storage = JvmKeyValueStorage(file, Dispatchers.IO)
        assertEquals(StorageResult.Success(Unit), NamedPipes.answersWithinBound("control", emptyList()) { storage.put("a", "1") }, "control")
        val lock: File = CrossProcessLock.lockFileOf(file)
        assertEquals(true, lock.delete(), "control: the lock file existed")
        NamedPipes.createOrSkip(lock)

        val put: StorageResult<Unit> = NamedPipes.answersWithinBound("put", listOf(lock)) { storage.put("b", "2") }

        assertIs<StorageError.OperationFailed>(assertIs<StorageResult.Failure>(put, "put must fail at once: $put").error, "put")
        // A read takes no lock at all: it still sees the store as it was.
        assertEquals(StorageResult.Success("1"), NamedPipes.answersWithinBound("get", listOf(lock)) { storage.get("a") })
        assertEquals(StorageResult.Success(null), NamedPipes.answersWithinBound("get-b", listOf(lock)) { storage.get("b") }, "b was never written")
        assertEquals(true, lock.delete(), "the pipe is still there, never replaced by the library")
        assertEquals(StorageResult.Success(Unit), NamedPipes.answersWithinBound("after", emptyList()) { storage.put("b", "2") }, "the store works once the pipe is gone")
    }

    @Test
    fun `a named pipe as the storage file answers OperationFailed for every operation and is never replaced`() {
        val file = File(directory, "pipe.properties")
        NamedPipes.createOrSkip(file)
        val storage = JvmKeyValueStorage(file, Dispatchers.IO)
        val calls: List<Pair<StorageOperation, suspend () -> StorageResult<*>>> = listOf(
            StorageOperation.GET to { storage.get("a") },
            StorageOperation.PUT to { storage.put("a", "1") },
            StorageOperation.REMOVE to { storage.remove("a") },
        )
        for ((operation: StorageOperation, call: suspend () -> StorageResult<*>) in calls) {
            val result: StorageResult<*> = NamedPipes.answersWithinBound("$operation", listOf(file)) { call() }
            val failure: StorageError = assertIs<StorageResult.Failure>(result, "$operation must fail, not block").error
            assertEquals(operation, assertIs<StorageError.OperationFailed>(failure).operation)
        }
        assertFalse(Files.isRegularFile(file.toPath()), "the pipe was replaced by a store file")
    }

    @Test
    fun `an interrupted tryLock fails the write and writes nothing`() {
        val file = File(directory, "interrupted.properties")
        val storage = JvmKeyValueStorage(
            file,
            Dispatchers.IO,
            crossProcessLock = CrossProcessLock(deadline = 10.seconds, tryLock = { throw ClosedByInterruptException() }),
        )

        val put: StorageResult<Unit> = NamedPipes.answersWithinBound("put", emptyList()) { storage.put("a", "1") }

        val error: StorageError.OperationFailed =
            assertIs<StorageError.OperationFailed>(assertIs<StorageResult.Failure>(put, "an interrupted lock must fail the write: $put").error)
        assertIs<ClosedByInterruptException>(error.cause, "the cause is the interrupt")
        assertFalse(file.exists(), "an interrupted lock attempt must not turn into an unlocked write")
    }
}
