@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * The cross-process lock (plan S8, D-3, 6c, T7 in one JVM and T17 for its handles): `tryLock` on a separate
 * `<file>.lock`, polled until a deadline on virtual time, with every handle released on every path.
 *
 * Another process and another handle of this JVM are the same thing to the lock (`tryLock` answers `null` for one,
 * [java.nio.channels.OverlappingFileLockException] for the other), so the in-JVM tests here are deterministic stand-ins; the
 * true two-process cases are in `JvmKeyValueStorageForkedProcessesTest`.
 */
class CrossProcessLockTest {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xlock").toFile()
    private val lockFile: File = File(directory, "store.properties.lock")
    private val prefix = "store.properties"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun descriptors(): Long = TestFs.requireOpenDescriptorCount()

    @Test
    fun `acquire creates an empty lock file once and close releases the handle, twice is harmless`() = runBlocking {
        val before: Long = descriptors()
        val lock = CrossProcessLock()

        val held: LockAcquisition.Held = assertIs(lock.acquire(lockFile, prefix))
        assertTrue(lockFile.isFile)
        assertEquals(0L, lockFile.length(), "the lock file is empty")
        assertTrue(descriptors() > before, "control: a held lock keeps one file handle open")
        held.close()
        held.close()
        assertEquals(before, descriptors(), "close must release the handle")

        val again: LockAcquisition.Held = assertIs(lock.acquire(lockFile, prefix), "re-acquire after release")
        again.close()
        assertEquals(before, descriptors())
        assertEquals(listOf(lockFile.name), directory.list()!!.toList(), "no temporary file is left by the creation")
    }

    @Test
    fun `a second handle of the same JVM is busy until the deadline, and a waiter holds no handle`() = runTest {
        val lock = CrossProcessLock(deadline = 10.seconds, pollInterval = 25.milliseconds)
        val holder: LockAcquisition.Held = assertIs(lock.acquire(lockFile, prefix))
        val withHolder: Long = descriptors()

        val outcome: LockAcquisition = lock.acquire(lockFile, prefix)

        assertEquals(LockAcquisition.TimedOut, outcome)
        assertEquals(10_000L, testScheduler.currentTime, "the wait ends exactly at the deadline, on virtual time")
        assertEquals(withHolder, descriptors(), "400 polls left no handle behind")
        holder.close()
        assertIs<LockAcquisition.Held>(lock.acquire(lockFile, prefix)).close()
    }

    @Test
    fun `a waiter gets the lock one poll after the holder lets go`() = runTest {
        val lock = CrossProcessLock(deadline = 10.seconds, pollInterval = 25.milliseconds)
        val holder: LockAcquisition.Held = assertIs(lock.acquire(lockFile, prefix))
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { lock.acquire(lockFile, prefix) }

        holder.close()
        val got: LockAcquisition = waiter.await()

        assertIs<LockAcquisition.Held>(got).close()
        assertEquals(25L, testScheduler.currentTime, "one poll interval of waiting, not the deadline")
    }

    @Test
    fun `a waiter cancelled while polling holds nothing and the lock stays usable`() = runTest {
        val lock = CrossProcessLock(deadline = 10.seconds, pollInterval = 25.milliseconds)
        val holder: LockAcquisition.Held = assertIs(lock.acquire(lockFile, prefix))
        val withHolder: Long = descriptors()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { lock.acquire(lockFile, prefix) }

        waiter.cancelAndJoin()

        assertTrue(waiter.isCancelled)
        assertEquals(withHolder, descriptors(), "a cancelled waiter must not keep a handle")
        holder.close()
        val timeBefore: Long = testScheduler.currentTime
        assertIs<LockAcquisition.Held>(lock.acquire(lockFile, prefix), "the lock is free at once after the holder").close()
        assertEquals(timeBefore, testScheduler.currentTime, "no waiting was needed")
    }

    @Test
    fun `a lock acquired in the instant of a cancellation is released before the cancellation propagates`() = runTest {
        val before: Long = descriptors()
        val holder: LockAcquisition.Held = assertIs(CrossProcessLock().acquire(lockFile, prefix))
        lateinit var waiter: Job
        var attempts = 0
        val real: FileAccess = createFileAccess()
        // The second attempt starts after a poll delay: there the holder lets go and the waiter is cancelled, so the lock
        // is won by a coroutine whose cancellation is already pending.
        val scripted = object : FileAccess by real {
            override fun metadata(path: String, followLinks: Boolean): FileMetadata? {
                attempts++
                if (attempts == 2) {
                    holder.close()
                    waiter.cancel()
                }
                return real.metadata(path, followLinks)
            }
        }
        val lock = CrossProcessLock(scripted, deadline = 10.seconds, pollInterval = 25.milliseconds)

        waiter = launch(start = CoroutineStart.UNDISPATCHED) { lock.acquire(lockFile, prefix) }
        waiter.join()

        assertEquals(2, attempts, "control: the second attempt ran, so the lock was really acquired during the cancellation")
        assertTrue(waiter.isCancelled)
        assertEquals(before, descriptors(), "the lock won in the cancellation instant must be released")
        assertIs<LockAcquisition.Held>(CrossProcessLock().acquire(lockFile, prefix), "and free for the next caller").close()
    }

    @Test
    fun `an existing lock file is used as it is, also when another creator wins the race to create it`() = runBlocking {
        directory.mkdirs()
        lockFile.createNewFile()
        assertIs<LockAcquisition.Held>(CrossProcessLock().acquire(lockFile, prefix)).close()

        lockFile.delete()
        val real: FileAccess = createFileAccess()
        val racing = object : FileAccess by real {
            override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
                if (!File(path).exists()) File(path).createNewFile() // the other process wins the race
                return real.writeAtomically(path, bytes, options)
            }
        }
        val held: LockAcquisition = CrossProcessLock(racing).acquire(lockFile, prefix)

        assertIs<LockAcquisition.Held>(held, "AlreadyExists counts as success").close()
        assertEquals(listOf(lockFile.name), directory.list()!!.toList(), "the loser's temporary file is gone")
    }

    @Test
    fun `the lock file is created owner-only, atomically, with the store's temp prefix and missing parents`() = runBlocking {
        val calls: CopyOnWriteArrayList<Pair<String, WriteOptions>> = CopyOnWriteArrayList()
        val real: FileAccess = createFileAccess()
        val recording = object : FileAccess by real {
            override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
                calls += path to options
                return real.writeAtomically(path, bytes, options)
            }
        }
        val nested = File(File(directory, "a"), "store.properties.lock")

        assertIs<LockAcquisition.Held>(CrossProcessLock(recording).acquire(nested, prefix)).close()
        assertIs<LockAcquisition.Held>(CrossProcessLock(recording).acquire(nested, prefix)).close()

        assertEquals(1, calls.size, "an existing lock file is never written again")
        val options: WriteOptions = calls.single().second
        assertEquals(ExistingFile.Fail, options.existing, "atomic create-if-absent")
        assertTrue(options.ownerOnly, "the lock file is owner-only from the moment it exists")
        assertTrue(options.createParentDirectories)
        assertEquals(prefix, options.tempPrefix, "a killed creation leaves a temp the store's clear() recognises")
        assertTrue(nested.isFile)
    }

    @Test
    fun `on a POSIX host the lock file mode is owner read-write only`() = runBlocking {
        TestHost.assumePosixFileSemantics("a POSIX mode; the Windows ACL is the files module's test")
        assertIs<LockAcquisition.Held>(CrossProcessLock().acquire(lockFile, prefix)).close()

        // path-hygiene-ignore: PH2 reads the mode the library set, on a POSIX host only (assumed above)
        val actual: Set<PosixFilePermission> = Files.getPosixFilePermissions(lockFile.toPath())
        // path-hygiene-ignore: PH2 the mode a POSIX host must show, on a POSIX host only (assumed above)
        val ownerOnly: Set<PosixFilePermission> = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        assertEquals(ownerOnly, actual)
    }

    @Test
    fun `a volume without hard links still gets its lock file, and any other creation failure is a typed failure`() = runBlocking {
        val before: Long = descriptors()
        val real: FileAccess = createFileAccess()
        val noLinks = object : FileAccess by real {
            override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> =
                FileResult.Failure(FileError.Unsupported(path))
        }
        assertIs<LockAcquisition.Held>(CrossProcessLock(noLinks).acquire(lockFile, prefix)).close()
        assertTrue(lockFile.isFile, "the plain create is the fallback")
        lockFile.delete()

        val broken = object : FileAccess by real {
            override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> =
                FileResult.Failure(FileError.Io(path, null, IOException("disk on fire")))
        }
        val failed: LockAcquisition.Failed = assertIs(CrossProcessLock(broken).acquire(lockFile, prefix))
        assertFalse(lockFile.exists(), "a failed creation leaves nothing")
        assertEquals("disk on fire", failed.cause.cause?.message, "the file layer's own cause is kept")
        assertEquals(before, descriptors())
    }

    @Test
    fun `a lock path that is a directory fails typed and leaks no handle`() = runBlocking {
        val before: Long = descriptors()
        directory.mkdirs()
        assertTrue(lockFile.mkdir())

        assertIs<LockAcquisition.Failed>(CrossProcessLock().acquire(lockFile, prefix))
        assertEquals(before, descriptors())
    }

    @Test
    fun `an unwritable directory fails typed, creates nothing and leaks no handle`() = runBlocking {
        val before: Long = descriptors()
        directory.mkdirs()
        TestFs.makeUnwritable(directory).use {
            assertIs<LockAcquisition.Failed>(CrossProcessLock().acquire(lockFile, prefix))
        }
        assertFalse(lockFile.exists())
        assertEquals(before, descriptors())
    }

    @Test
    fun `deadline and poll interval must be positive`() {
        assertFailsWith<IllegalArgumentException> { CrossProcessLock(deadline = 0.seconds) }
        assertFailsWith<IllegalArgumentException> { CrossProcessLock(pollInterval = 0.seconds) }
    }

    @Test
    fun `a name that would pass 255 bytes gets a stable hashed lock name that fits and cannot be a data file`() {
        for (length: Int in listOf(1, 100, 250, 251, 255)) {
            val name: String = "n".repeat(length)
            val lockName: String = CrossProcessLock.lockNameOf(name)
            assertTrue(lockName.toByteArray().size <= 255, "length $length gave ${lockName.toByteArray().size} bytes")
            assertEquals(lockName, CrossProcessLock.lockNameOf(name), "stable")
            if (length + ".lock".length <= 255) assertEquals("$name.lock", lockName) else assertTrue(lockName.contains("~") && lockName.endsWith(".lock"))
        }
        val a: String = CrossProcessLock.lockNameOf("n".repeat(254) + "a")
        val b: String = CrossProcessLock.lockNameOf("n".repeat(254) + "b")
        assertTrue(a != b, "names sharing their beginning get different lock names")
        val multi: String = CrossProcessLock.lockNameOf("é".repeat(127) + "x")
        assertTrue(multi.toByteArray().size <= 255)
        assertTrue(multi.all { it != '�' }, "the cut never splits a character")
    }

    @Test
    fun `a seeded storm of threads over one lock file is mutually exclusive, loses no update and leaks no handle`() {
        val seed = 1_998L
        val before: Long = descriptors()
        val lock = CrossProcessLock(deadline = 120.seconds, pollInterval = 1.milliseconds)
        val inside = AtomicInteger()
        val violations: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        var counter = 0 // plain on purpose: only mutual exclusion keeps this read-modify-write whole
        val sink = AtomicInteger()
        val start = CountDownLatch(1)
        val workers = 10
        val rounds = 12
        val threads: List<Thread> = (0 until workers).map { worker: Int ->
            thread(name = "xlock-$worker") {
                val random = Random(seed * 1000 + worker)
                assertTrue(start.await(60, TimeUnit.SECONDS))
                repeat(rounds) { round: Int ->
                    val held: LockAcquisition = runBlocking { lock.acquire(lockFile, prefix) }
                    if (held !is LockAcquisition.Held) {
                        violations += "seed=$seed worker=$worker round=$round: $held"
                        return@repeat
                    }
                    try {
                        if (inside.incrementAndGet() != 1) violations += "seed=$seed worker=$worker round=$round: two holders at once"
                        val read: Int = counter
                        var spin = 0
                        repeat(random.nextInt(2_000)) { spin += it }
                        sink.set(spin) // keeps the work between the read and the write from being optimised away
                        counter = read + 1
                        inside.decrementAndGet()
                    } finally {
                        held.close()
                    }
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(120_000) }

        assertTrue(threads.none { it.isAlive }, "seed=$seed: a worker is still blocked")
        assertEquals(emptyList(), violations.take(5), "seed=$seed")
        assertEquals(workers * rounds, counter, "seed=$seed: a read-modify-write inside the lock was lost")
        assertEquals(before, descriptors(), "seed=$seed: handles leaked")
    }
}
