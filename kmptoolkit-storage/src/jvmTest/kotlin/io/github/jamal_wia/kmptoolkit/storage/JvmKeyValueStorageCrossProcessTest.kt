@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.LockAcquisition
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * The plain store's writes under the cross-process lock (plan D-3, 6c), at the store level and inside one JVM: another
 * handle of the JVM stands in for another process (the real second process is `JvmKeyValueStorageForkedProcessesTest`).
 * Cancellation, the lock held by someone else, handle release (T17) and a seeded storm over two instances.
 */
class JvmKeyValueStorageCrossProcessTest {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xstore").toFile()
    private val dataFile: File = File(directory, "x.kmptoolkit.storage.properties")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private suspend fun holdLock(): LockAcquisition.Held =
        assertIs(CrossProcessLock().acquire(CrossProcessLock.lockFileOf(dataFile), temporaryPrefixOf(dataFile.name)))

    @Test
    fun `a write cancelled while it waits for the lock applies nothing and frees both locks for the next writer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = JvmKeyValueStorage(dataFile, dispatcher, crossProcessLock = CrossProcessLock(deadline = 10.seconds), timeSource = testScheduler.timeSource)
        assertEquals(StorageResult.Success(Unit), storage.put("kept", "1"))
        val bytesBefore: ByteArray = dataFile.readBytes()
        val holder: LockAcquisition.Held = holdLock()
        val descriptorsHeld: Long = TestFs.requireOpenDescriptorCount()

        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { storage.put("lost", "2") }
        runCurrent() // the put reaches its poll loop: it holds the in-process lock and waits for the file lock
        assertTrue(waiting.isActive, "control: the write is really waiting for the lock")
        waiting.cancelAndJoin()

        assertTrue(waiting.isCancelled)
        assertContentEquals(bytesBefore, dataFile.readBytes(), "a cancelled write must not touch the file")
        assertEquals(descriptorsHeld, TestFs.requireOpenDescriptorCount(), "a cancelled waiter must not keep a handle")
        holder.close()
        val timeBefore: Long = testScheduler.currentTime
        assertEquals(StorageResult.Success(Unit), storage.put("next", "3"), "the in-process lock was released by the cancellation")
        assertEquals(timeBefore, testScheduler.currentTime)
        assertNull(storage.get("lost").getOrNull())
        assertEquals("3", storage.get("next").getOrNull())
    }

    @Test
    fun `a clear of a store whose directory does not exist creates neither the directory nor a lock file`() = runBlocking {
        val missing = File(directory, "never-created")
        val storage: KeyValueStorage = createKeyValueStorage(missing, StorageConfig("x"))

        assertEquals(StorageResult.Success(Unit), storage.clear())

        assertTrue(!missing.exists(), "clear of nothing must not create anything")
    }

    @Test
    fun `reads take no file lock, so a read never creates the lock file`() = runBlocking {
        val storage: KeyValueStorage = createKeyValueStorage(directory, StorageConfig("x"))

        assertEquals(StorageResult.Success<String?>(null), storage.get("k"))

        assertEquals(emptyList(), directory.list()!!.toList(), "a read of an absent store creates nothing")
        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
        assertTrue(File(directory, CrossProcessLock.lockNameOf(dataFile.name)).isFile, "the first write creates the lock file")
    }

    @Test
    fun `a thousand operations leave the process with the same number of open handles`() = runBlocking {
        val storage: KeyValueStorage = createKeyValueStorage(directory, StorageConfig("x"))
        assertEquals(StorageResult.Success(Unit), storage.put("warm", "up"))
        val before: Long = TestFs.requireOpenDescriptorCount()

        repeat(1_000) { round: Int ->
            when (round % 4) {
                0 -> assertEquals(StorageResult.Success(Unit), storage.put("k${round % 7}", "v$round"), "round $round")
                1 -> storage.get("k${round % 7}")
                2 -> assertEquals(StorageResult.Success(Unit), storage.remove("k${round % 7}"), "round $round")
                else -> if (round % 50 == 3) assertEquals(StorageResult.Success(Unit), storage.clear(), "round $round")
            }
        }

        assertEquals(before, TestFs.requireOpenDescriptorCount(), "the lock handles of 1000 operations were not all released")
        val holder: LockAcquisition.Held = holdLock()
        assertTrue(TestFs.requireOpenDescriptorCount() > before, "control: a held lock does show up in the count")
        holder.close()
        assertEquals(before, TestFs.requireOpenDescriptorCount())
    }

    @Test
    fun `a seeded storm over four instances with cancellations loses no acknowledged write`() = runBlocking {
        val seed = 4_311L
        val instances: List<KeyValueStorage> = List(4) { createKeyValueStorage(directory, StorageConfig("x")) }
        val acknowledged: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val failures: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        withTimeout(120.seconds) {
            (0 until 8).map { worker: Int ->
                async(Dispatchers.Default) {
                    val random = Random(seed * 1000 + worker)
                    repeat(40) { step: Int ->
                        val key = "w$worker-$step"
                        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            val result: StorageResult<Unit> = instances[random.nextInt(4)].put(key, "v")
                            if (result is StorageResult.Success) acknowledged += key else failures += "seed=$seed $key: $result"
                        }
                        if (random.nextInt(5) == 0) job.cancel()
                        job.join()
                    }
                }
            }.awaitAll()
        }

        assertEquals(emptyList(), failures.take(5), "seed=$seed")
        val missing: List<String> = acknowledged.filter { instances[0].get(it).getOrNull() != "v" }
        assertEquals(emptyList(), missing.take(5), "seed=$seed: an acknowledged write was lost")
        assertTrue(acknowledged.size > 100, "seed=$seed control: most writes were acknowledged (${acknowledged.size})")
        assertEquals(emptyList(), directory.list()!!.filter { it.endsWith(".tmp") }, "seed=$seed")
    }
}
