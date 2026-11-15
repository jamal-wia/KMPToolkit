package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.NamedPipes
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers

/**
 * Group sto-desktop, phase A breaker round 6 (decisions.md D-23), part A6: the round-7 guard of the plain store's properties
 * file (29a711eb: "a path that is not a regular file is refused before it is opened, links followed") attacked with the
 * non-regular paths the round-7 tests do not name, through the public behaviour of [JvmKeyValueStorage]:
 *
 * - a link to a character device that never ends (`/dev/zero`): opening it does not block, but reading it never ends, so
 *   before the guard `get` would read zeros until the heap ran out; every operation must answer `OperationFailed` at once and
 *   the link must stay (KDoc: "Nothing throws: an unreadable ... file ... arrive[s] as OperationFailed");
 * - a link loop (`ELOOP` from the metadata read): `OperationFailed` at once, not an escape and not "no file";
 * - a directory at the store path: `OperationFailed` for reads and writes, nothing written into or over it;
 * - the way out the class KDoc names for a file that fails every operation, `clear()`, over a named pipe at the store path: it
 *   must answer at once (no blocking open) and leave the path empty, so the store works again (pinned; see the report for the
 *   05 sentence "fails every operation" that this contradicts);
 * - a `put` made on a thread whose interrupt status is set, with a dispatcher that does not hop (05: "A thread that is
 *   interrupted while it takes the lock ... fails that write with OperationFailed ...; it is not taken for a file system without
 *   locks, so the plain store never writes unlocked because of an interrupt"): the write either happens under a real file lock
 *   or fails and writes nothing; it never happens without the lock, nothing throws, and the status is kept.
 *
 * Real files in a directory the test creates and deletes; hang bounds only; POSIX hosts (links, devices, mkfifo).
 */
class JvmKeyValueStorageA6BreakerR6Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-plain-breaker6-a6").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun assertEveryCallFails(storage: JvmKeyValueStorage, label: String, pipes: List<File> = emptyList()) {
        val calls: List<Pair<StorageOperation, suspend () -> StorageResult<*>>> = listOf(
            StorageOperation.GET to { storage.get("a") },
            StorageOperation.PUT to { storage.put("a", "1") },
            StorageOperation.REMOVE to { storage.remove("a") },
        )
        for ((operation: StorageOperation, call: suspend () -> StorageResult<*>) in calls) {
            val result: StorageResult<*> = NamedPipes.answersWithinBound("$label-$operation", pipes) { call() }
            val failure: StorageError = assertIs<StorageResult.Failure>(result, "$label: $operation must fail at once, got $result").error
            val failed: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(failure, "$label: $operation: $failure")
            assertEquals(operation, failed.operation, "$label: the failure names the operation")
        }
    }

    @Test
    fun `a store file that is a link to a device that never ends fails every operation at once and the link stays`() {
        TestHost.assumePosixFileSemantics("a character device and symbolic links")
        val device = File("/dev/zero")
        org.junit.Assume.assumeTrue("no /dev/zero here", device.exists() && !Files.isRegularFile(device.toPath()))
        val file = File(directory, "zero.properties")
        Files.createSymbolicLink(file.toPath(), device.toPath())
        val storage = JvmKeyValueStorage(file, Dispatchers.IO)

        assertEveryCallFails(storage, "dev-zero")

        assertTrue(Files.isSymbolicLink(file.toPath()), "the link was replaced by a store file")
    }

    @Test
    fun `a store file that is a link loop fails every operation at once`() {
        TestHost.assumePosixFileSemantics("symbolic links without privileges")
        val file = File(directory, "loop.properties")
        val other = File(directory, "loop-other")
        Files.createSymbolicLink(file.toPath(), other.toPath())
        Files.createSymbolicLink(other.toPath(), file.toPath())
        val storage = JvmKeyValueStorage(file, Dispatchers.IO)

        assertEveryCallFails(storage, "loop")

        assertTrue(Files.isSymbolicLink(file.toPath()) && Files.isSymbolicLink(other.toPath()), "the loop was changed")
    }

    @Test
    fun `a directory at the store path fails every operation and nothing is written into it`() {
        val file = File(directory, "dir.properties")
        assertTrue(file.mkdir(), "control: a directory sits at the store path")
        val inside = File(file, "keep")
        assertTrue(inside.createNewFile(), "control: the directory has content")
        val storage = JvmKeyValueStorage(file, Dispatchers.IO)

        assertEveryCallFails(storage, "directory")

        assertTrue(file.isDirectory, "the directory was replaced")
        assertEquals(listOf("keep"), file.list()?.toList(), "something was written into the directory")
    }

    @Test
    fun `clear over a named pipe at the store path answers at once and the store works again`() {
        val file = File(directory, "pipe.properties")
        NamedPipes.createOrSkip(file)
        val storage = JvmKeyValueStorage(file, Dispatchers.IO)
        assertEveryCallFails(storage, "pipe", listOf(file))

        val cleared: StorageResult<Unit> = NamedPipes.answersWithinBound("clear", listOf(file)) { storage.clear() }

        assertEquals(StorageResult.Success(Unit), cleared, "clear() is the way out of a file that fails every operation (class KDoc)")
        assertFalse(file.exists(), "clear() leaves no pipe behind")
        assertEquals(StorageResult.Success(Unit), NamedPipes.answersWithinBound("put", emptyList()) { storage.put("a", "1") })
        assertEquals(StorageResult.Success("1"), NamedPipes.answersWithinBound("get", emptyList()) { storage.get("a") })
        assertTrue(Files.isRegularFile(file.toPath()), "the store file is a regular file again")
    }

    @Test
    fun `a put on an interrupted thread never writes without the file lock, never throws and keeps the interrupt status`() {
        val file = File(directory, "interrupted.properties")
        val locks: MutableList<FileLock?> = CopyOnWriteArrayList()
        val storage = JvmKeyValueStorage(
            file,
            Dispatchers.Unconfined,
            crossProcessLock = CrossProcessLock(deadline = 10.seconds, tryLock = { channel: FileChannel -> channel.tryLock().also { locks += it } }),
        )
        val answer = AtomicReference<Result<StorageResult<Unit>>>()
        val statusKept = AtomicBoolean(false)
        val answered = CountDownLatch(1)
        // Not runBlocking: it throws InterruptedException on an interrupted thread before the store answers (kotlinx behaviour).
        val worker = Thread({
            Thread.currentThread().interrupt()
            val put: suspend () -> StorageResult<Unit> = { storage.put("a", "1") }
            put.startCoroutine(
                Continuation(EmptyCoroutineContext) { result: Result<StorageResult<Unit>> ->
                    answer.set(result)
                    statusKept.set(Thread.currentThread().isInterrupted)
                    answered.countDown()
                },
            )
        }, "a6-interrupted-put").apply { isDaemon = true }
        worker.start()
        if (!answered.await(NamedPipes.HANG_BOUND_MS, TimeUnit.MILLISECONDS)) fail("hang bound: the put on an interrupted thread never answered")
        worker.join(NamedPipes.HANG_BOUND_MS)

        val result: StorageResult<Unit> = answer.get().getOrElse { fail("a store call must never throw; it threw $it") }
        assertTrue(statusKept.get(), "05: the call keeps the thread's interrupt status")
        when (result) {
            is StorageResult.Success -> {
                assertEquals(1, locks.size, "control: one lock attempt")
                assertTrue(locks.single() != null, "the put succeeded without a file lock: an interrupt must never make the plain store write unlocked")
                assertEquals(StorageResult.Success("1"), NamedPipes.answersWithinBound("read-back", emptyList()) { storage.get("a") })
            }
            is StorageResult.Failure -> {
                assertIs<StorageError.OperationFailed>(result.error, "an interrupted write fails with OperationFailed: $result")
                assertFalse(file.exists(), "a failed interrupted write must write nothing")
            }
        }
        println("A6 interrupted put: $result, lock taken: ${locks.firstOrNull() != null}, cause: ${(result as? StorageResult.Failure)?.error}")
    }
}
