@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.LockAcquisition
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Plain-store locking after the advisor's answers Q2 and Q4 (group `sto-desktop`, round 2): one 10 second budget per
 * write for BOTH the in-process and the file lock, reads that take no lock, and a file system that refuses file locks.
 * Another handle of the JVM stands in for a foreign process (the real second process is
 * `JvmKeyValueStorageForkedProcessesTest`); every wait runs on virtual time.
 */
class JvmKeyValueStorageSharedBudgetTest {

    private val directory: File = Files.createTempDirectory("kmptoolkit-budget").toFile()
    private val dataFile: File = File(directory, "b.kmptoolkit.storage.properties")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private companion object {
        const val HANG_BOUND_SECONDS: Long = 60
    }

    private fun TestScope.storage(lock: CrossProcessLock = CrossProcessLock(deadline = 10.seconds)): JvmKeyValueStorage =
        JvmKeyValueStorage(dataFile, StandardTestDispatcher(testScheduler), crossProcessLock = lock, timeSource = testScheduler.timeSource)

    private suspend fun holdLock(): LockAcquisition.Held =
        assertIs(CrossProcessLock().acquire(CrossProcessLock.lockFileOf(dataFile), temporaryPrefixOf(dataFile.name)))

    @Test
    fun `five writers queued behind a foreign lock holder all answer Unavailable at one deadline, not five`() = runTest {
        val storage: JvmKeyValueStorage = storage()
        assertEquals(StorageResult.Success(Unit), storage.put("seed", "1"))
        val holder: LockAcquisition.Held = holdLock()
        val bytesBefore: ByteArray = dataFile.readBytes()
        val start: Long = testScheduler.currentTime

        val writers: List<Deferred<Pair<StorageResult<Unit>, Long>>> = List(5) { index: Int ->
            async(start = CoroutineStart.UNDISPATCHED) { storage.put("w$index", "v") to testScheduler.currentTime - start }
        }
        runCurrent()
        val readAnswer: StorageResult<String?> = storage.get("seed")
        assertEquals(start, testScheduler.currentTime, "a read must not wait behind writers that wait for another process")
        assertEquals("1", readAnswer.getOrNull(), "the read returns the current value at once")

        val answers: List<Pair<StorageResult<Unit>, Long>> = writers.awaitAll()
        for ((index: Int, answer: Pair<StorageResult<Unit>, Long>) in answers.withIndex()) {
            assertIs<StorageError.Unavailable>(assertIs<StorageResult.Failure>(answer.first, "writer $index").error)
            assertEquals(10_000L, answer.second, "writer $index answers at exactly the 10 s budget (a chained wait would be ${(index + 1) * 10} s)")
        }
        assertTrue(bytesBefore.contentEquals(dataFile.readBytes()), "a write that timed out changes nothing")
        holder.close()
        assertEquals(StorageResult.Success(Unit), storage.put("after", "2"), "both locks are free again")
    }

    @Test
    fun `a write queued behind a slow write in this process answers Unavailable at the budget while that write goes on`() = runTest {
        // The holder's data write blocks on a latch (a slow disk). It holds the in-process lock for as long as it likes: the
        // queued writer's wait for that lock is bounded by its own budget, not by the holder's action.
        val real: FileAccess = createFileAccess()
        val entered = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val slowDisk = object : FileAccess by real {
            override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
                entered.countDown()
                assertTrue(letGo.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS), "the test never released the slow write")
                return real.writeAtomically(path, bytes, options)
            }
        }
        val holderStore = JvmKeyValueStorage(dataFile, Dispatchers.IO, slowDisk)
        val holder: Deferred<StorageResult<Unit>> = async(Dispatchers.IO) { holderStore.put("slow", "1") }
        assertTrue(withContext(Dispatchers.IO) { entered.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS) }, "control: the slow write is inside its block")

        val storage: JvmKeyValueStorage = storage()
        val start: Long = testScheduler.currentTime
        val queued: Deferred<Pair<StorageResult<Unit>, Long>> =
            async(start = CoroutineStart.UNDISPATCHED) { storage.put("queued", "2") to testScheduler.currentTime - start }
        runCurrent()
        testScheduler.advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(queued.isCompleted, "the queued writer must answer at the budget although the holder is still inside its write")
        val (answer: StorageResult<Unit>, at: Long) = queued.await()
        assertIs<StorageError.Unavailable>(assertIs<StorageResult.Failure>(answer).error)
        assertEquals(10_000L, at)
        assertTrue(!holder.isCompleted, "control: the slow write is still running")
        letGo.countDown()
        assertEquals(StorageResult.Success(Unit), holder.await())
        assertEquals("1", storage.get("slow").getOrNull())
        assertEquals(null, storage.get("queued").getOrNull(), "the timed-out write changed nothing")
    }

    @Test
    fun `a write that waited in process for part of the budget gets only the rest for the file lock`() = runTest {
        val storage: JvmKeyValueStorage = storage()
        val holder: LockAcquisition.Held = holdLock()
        val start: Long = testScheduler.currentTime
        val first: Deferred<Long> = async(start = CoroutineStart.UNDISPATCHED) { storage.put("a", "1"); testScheduler.currentTime - start }
        runCurrent()
        // The second writer joins 4 s later, so its in-process wait ends when the first one times out (at 10 s), 6 s into its own budget.
        testScheduler.advanceTimeBy(4.seconds.inWholeMilliseconds)
        val second: Deferred<Long> = async(start = CoroutineStart.UNDISPATCHED) { storage.put("b", "2"); testScheduler.currentTime - start }

        assertEquals(10_000L, first.await())
        assertEquals(14_000L, second.await(), "the second writer's own budget ends 10 s after it started, not 20 s")
        holder.close()
    }

    @Test
    fun `a write that gets the in-process key late and finds the file lock free still succeeds`() = runTest {
        val storage: JvmKeyValueStorage = storage()
        val holder: LockAcquisition.Held = holdLock()
        val first: Deferred<StorageResult<Unit>> = async(start = CoroutineStart.UNDISPATCHED) { storage.put("a", "1") }
        val second: Deferred<StorageResult<Unit>> = async(start = CoroutineStart.UNDISPATCHED) { storage.put("b", "2") }
        runCurrent()
        testScheduler.advanceTimeBy(2.seconds.inWholeMilliseconds)
        holder.close()

        assertEquals(StorageResult.Success(Unit), first.await())
        assertEquals(StorageResult.Success(Unit), second.await())
        assertEquals("1", storage.get("a").getOrNull())
        assertEquals("2", storage.get("b").getOrNull())
    }

    @Test
    fun `a file system that refuses file locks still lets the plain store write, coordinated in this process`() = runBlocking {
        val seed = 8_042L
        val refusing = CrossProcessLock(tryLock = { throw IOException("ENOLCK") })
        val instances: List<KeyValueStorage> =
            List(2) { JvmKeyValueStorage(dataFile, Dispatchers.IO, crossProcessLock = refusing) }
        assertEquals(StorageResult.Success(Unit), instances[0].put("first", "1"), "seed=$seed")
        assertTrue(CrossProcessLock.lockFileOf(dataFile).isFile, "seed=$seed: the lock file is still created")

        val written: MutableSet<String> = ConcurrentHashMap.newKeySet()
        withTimeout(120.seconds) {
            (0 until 6).map { worker: Int ->
                async(Dispatchers.Default) {
                    val random = Random(seed * 1000 + worker)
                    repeat(40) { step: Int ->
                        val key = "w$worker-$step"
                        val result: StorageResult<Unit> = instances[random.nextInt(2)].put(key, "v")
                        if (result is StorageResult.Success) written += key
                    }
                }
            }.awaitAll()
        }

        assertEquals(240, written.size, "seed=$seed: every write on a lock-refusing file system succeeded")
        val lost: List<String> = written.filter { instances[0].get(it).getOrNull() != "v" }
        assertEquals(emptyList(), lost.take(5), "seed=$seed: the in-process lock still serializes read-modify-write")
        assertEquals(emptyList(), directory.list()!!.filter { it.endsWith(".tmp") }, "seed=$seed")
    }

    @Test
    fun `a lock file that cannot be opened is still OperationFailed on the plain store`() = runBlocking {
        directory.mkdirs()
        assertTrue(CrossProcessLock.lockFileOf(dataFile).mkdir())
        val storage = JvmKeyValueStorage(dataFile, Dispatchers.IO)

        val error: StorageError = assertIs<StorageResult.Failure>(storage.put("a", "1")).error

        assertIs<StorageError.OperationFailed>(error)
        assertTrue(!dataFile.exists(), "nothing was written")
    }

    @Test
    fun `a read racing writes and clears never fails and never sees a torn file`() = runBlocking {
        val seed = 90_210L
        val storage: KeyValueStorage = JvmKeyValueStorage(dataFile, Dispatchers.IO)
        val first: String = "a".repeat(20_000)
        val second: String = "b".repeat(20_000)
        val stop = AtomicBoolean(false)
        val problems: MutableList<String> = CopyOnWriteArrayList()
        withTimeout(120.seconds) {
            val readers: List<Deferred<Int>> = List(4) { reader: Int ->
                async(Dispatchers.Default) {
                    var reads = 0
                    while (!stop.get()) {
                        when (val result: StorageResult<String?> = storage.get("k")) {
                            is StorageResult.Failure -> problems += "seed=$seed reader $reader: ${result.error}"
                            is StorageResult.Success -> {
                                val value: String? = result.value
                                if (value != null && value != first && value != second) problems += "seed=$seed reader $reader: torn value of ${value.length} chars"
                            }
                        }
                        reads++
                    }
                    reads
                }
            }
            val writer = launch(Dispatchers.Default) {
                val random = Random(seed)
                repeat(400) { step: Int ->
                    when (random.nextInt(4)) {
                        0 -> storage.clear()
                        1, 2 -> storage.put("k", first)
                        else -> storage.put("k", second)
                    }
                    if (step == 399) stop.set(true)
                }
                stop.set(true)
            }
            writer.join()
            val total: Int = readers.awaitAll().sum()
            assertTrue(total > 0, "seed=$seed control: the readers really ran")
        }
        assertEquals(emptyList(), problems.take(5))
    }
}
