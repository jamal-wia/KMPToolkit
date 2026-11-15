package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.JvmKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Group sto-desktop, phase A breaker round 4 (decisions.md D-23): the claims of the [CrossProcessLock] KDoc and of the
 * "Concurrency" paragraph of `05-platform-notes.md`, each tested as written:
 *
 * - "every other failure ... is answered at once: it would repeat, and a write must not take the whole budget to say so" and
 *   "a lock file the process cannot create or open ... fails the write with `OperationFailed`": a lock path that is a
 *   dangling symbolic link can be neither created (the name is taken) nor opened (its target does not exist);
 * - "a handle is open only while one attempt runs, never while waiting, so a waiter holds no file descriptor";
 * - "a budget that is zero or negative answers `TimedOut` without an attempt";
 * - "a read never creates it, and neither does a `clear()` of a store whose directory does not exist".
 *
 * Virtual time throughout (the lock's polling is a coroutine `delay`), no sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossProcessLockBreakerR4Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xlock-breaker4").toFile()
    private val prefix = "store.properties"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun danglingLink(at: File): File {
        TestHost.assumePosixFileSemantics("a symbolic link without privileges; Windows needs the create-symlink right")
        val target = File(directory, "gone-target")
        Files.createSymbolicLink(at.toPath(), target.toPath())
        assertFalse(target.exists(), "control: the link's target does not exist")
        assertTrue(Files.isSymbolicLink(at.toPath()), "control: the lock path is a link")
        return at
    }

    @Test
    fun `a lock path that is a dangling symbolic link fails the acquisition at once instead of polling out the whole budget`() = runTest {
        val lockFile: File = danglingLink(File(directory, "store.properties.lock"))
        val lock = CrossProcessLock(deadline = 10.seconds)

        val startedAt: Long = testScheduler.currentTime
        val outcome: LockAcquisition = lock.acquire(lockFile, prefix)
        val spent: Duration = (testScheduler.currentTime - startedAt).milliseconds

        assertIs<LockAcquisition.Failed>(
            outcome,
            "the lock file can be neither created nor opened, which repeats on every poll: the KDoc answers that at once as Failed " +
                "(OperationFailed above), but the acquisition answered $outcome after $spent of virtual time",
        )
        assertTrue(spent < lock.deadline, "a failure that repeats must not take the whole budget to say so: $spent")
    }

    @Test
    fun `a plain store whose lock path is a dangling symbolic link answers OperationFailed for a write, not Unavailable after 10 s`() = runTest {
        val dataFile = File(directory, "store.properties")
        danglingLink(CrossProcessLock.lockFileOf(dataFile))
        val storage = JvmKeyValueStorage(
            dataFile,
            StandardTestDispatcher(testScheduler),
            crossProcessLock = CrossProcessLock(deadline = 10.seconds),
            timeSource = testScheduler.timeSource,
        )

        val result: StorageResult<Unit> = storage.put("k", "v")

        val error: StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error
        assertIs<StorageError.OperationFailed>(
            error,
            "05: 'A lock file the process cannot create or open ... fails the write with OperationFailed'; got $error after " +
                "${testScheduler.currentTime} ms of virtual time",
        )
        assertEquals(StorageOperation.PUT, error.operation)
        assertFalse(dataFile.exists(), "the data file is never written without the lock")
    }

    @Test
    fun `a waiter holds no descriptor between its attempts, whether a holder of this JVM or another process keeps the lock`() = runTest {
        val lockFile = File(directory, "store.properties.lock")
        // "Another process holds it" for the first five attempts: tryLock answers null, as it does for a foreign holder.
        val foreignBusy = AtomicInteger(5)
        val attempts = AtomicInteger()
        val seam: (FileChannel) -> FileLock? = { channel: FileChannel ->
            attempts.incrementAndGet()
            if (foreignBusy.getAndDecrement() > 0) null else channel.tryLock()
        }
        val lock = CrossProcessLock(deadline = 10.seconds, pollInterval = 25.milliseconds, tryLock = seam)
        // Create the lock file once so that its creation is not part of the count.
        assertIs<LockAcquisition.Held>(CrossProcessLock().acquire(lockFile, prefix)).close()
        val baseline: Long = TestFs.requireOpenDescriptorCount()

        val waiter = async { lock.acquire(lockFile, prefix) }
        testScheduler.advanceTimeBy(60.milliseconds)
        testScheduler.runCurrent()
        assertTrue(attempts.get() in 2..5, "control: the waiter is polling against the foreign holder (attempts ${attempts.get()})")
        assertEquals(baseline, TestFs.requireOpenDescriptorCount(), "a waiter between attempts against another process holds a descriptor")

        val held: LockAcquisition.Held = assertIs(waiter.await(), "the waiter gets the lock once the foreign holder lets go")
        assertEquals(baseline + 1, TestFs.requireOpenDescriptorCount(), "control: a holder has exactly one descriptor")

        // Now a holder of this JVM: a second waiter is busy at the claim and opens nothing.
        val sameJvm = async { CrossProcessLock(deadline = 10.seconds, pollInterval = 25.milliseconds).acquire(lockFile, prefix) }
        testScheduler.advanceTimeBy(100.milliseconds)
        testScheduler.runCurrent()
        assertFalse(sameJvm.isCompleted, "control: the second waiter is still waiting for the first holder")
        assertEquals(baseline + 1, TestFs.requireOpenDescriptorCount(), "a waiter behind a holder of this JVM opened a descriptor")

        held.close()
        assertIs<LockAcquisition.Held>(sameJvm.await(), "the second waiter gets the lock after the release").close()
        assertEquals(baseline, TestFs.requireOpenDescriptorCount(), "everything was released")
    }

    @Test
    fun `a budget that is zero, negative or spent answers TimedOut without an attempt, so not even the lock file is created`() = runTest {
        val lockFile = File(directory, "store.properties.lock")
        val tryLockCalls = AtomicInteger()
        val lock = CrossProcessLock(tryLock = { channel: FileChannel -> tryLockCalls.incrementAndGet(); channel.tryLock() })

        for (budget: Duration in listOf(Duration.ZERO, (-1).nanoseconds, (-10).seconds, -Duration.INFINITE)) {
            assertEquals(LockAcquisition.TimedOut, lock.acquire(lockFile, prefix, budget), "budget $budget")
        }

        assertEquals(0, tryLockCalls.get(), "a spent budget made an attempt")
        assertFalse(lockFile.exists(), "a spent budget created the lock file: an attempt ran")
        // Control: the smallest positive budget makes exactly one attempt.
        assertIs<LockAcquisition.Held>(lock.acquire(lockFile, prefix, 1.nanoseconds)).close()
        assertEquals(1, tryLockCalls.get(), "control: one attempt for a positive budget")
    }

    @Test
    fun `a read never creates the lock file and a clear of a store whose directory does not exist creates nothing`() = runTest {
        val dataFile = File(directory, "store.properties")
        val storage = JvmKeyValueStorage(dataFile, StandardTestDispatcher(testScheduler), timeSource = testScheduler.timeSource)

        assertEquals(StorageResult.Success(null), storage.get("k"))
        assertEquals(emptyList(), directory.list()!!.toList(), "a read created a file")

        val missing = File(File(directory, "not-there"), "store.properties")
        val elsewhere = JvmKeyValueStorage(missing, StandardTestDispatcher(testScheduler), timeSource = testScheduler.timeSource)
        assertEquals(StorageResult.Success(Unit), elsewhere.clear())
        assertFalse(missing.parentFile.exists(), "clear() of a store whose directory does not exist created it")

        // Control: a write does create the lock file.
        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
        assertTrue(CrossProcessLock.lockFileOf(dataFile).isFile, "control: a write creates the lock file")
    }
}
