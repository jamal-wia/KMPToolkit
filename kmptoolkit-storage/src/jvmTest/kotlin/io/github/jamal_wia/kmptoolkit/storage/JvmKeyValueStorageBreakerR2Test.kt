package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.files.KeyedMutex
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Breaker round 2 of group sto-desktop (phase A): the plain desktop store after D-3 (writes take the timed in-process lock
 * and then `<file>.lock` under one budget; reads take no lock at all).
 *
 * Two files, three instances per file through two spellings of each path (the folded lock key must join them), 8 writers
 * that cancel some of their own writes at random points, and readers that never stop. Contract (class KDoc and plan 4.11):
 * no write that answered `Success` is lost by another instance's read-modify-write, a lock-free read always sees a whole
 * file (never `OperationFailed` from a torn read), nothing deadlocks across the two keys, and afterwards the in-process
 * registry, the claim set and the descriptors are back where they were.
 */
class JvmKeyValueStorageBreakerR2Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-plain-breaker2").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `a seeded storm of cancelled and completed writes over two files loses no acknowledged write and leaves nothing behind`() =
        runBlocking {
            val seed = 20_261_117L
            val names: List<String> = listOf("alpha.properties", "beta.properties")
            // Two spellings of each file: plain and through "." (one canonical path, so one lock key).
            val instances: List<List<JvmKeyValueStorage>> = names.map { name: String ->
                listOf(
                    JvmKeyValueStorage(File(directory, name), Dispatchers.IO),
                    JvmKeyValueStorage(File(File(directory, "."), name), Dispatchers.IO),
                    JvmKeyValueStorage(File(directory, name), Dispatchers.IO),
                )
            }
            val acknowledged: List<MutableSet<String>> = names.map { Collections.newSetFromMap(ConcurrentHashMap()) }
            val problems: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
            val reads = AtomicInteger()
            val writing = AtomicBoolean(true)
            val descriptorsBefore: Long = TestFs.requireOpenDescriptorCount()

            withTimeout(STORM_BOUND) {
                val readers = names.indices.map { index: Int ->
                    launch(Dispatchers.IO) {
                        while (writing.get()) {
                            val result: StorageResult<String?> = instances[index][reads.get() % 3].get("w0-s0")
                            if (result is StorageResult.Failure) problems += "seed=$seed lock-free read of ${names[index]}: ${result.error}"
                            reads.incrementAndGet()
                        }
                    }
                }
                (0 until WORKERS).map { worker: Int ->
                    launch(Dispatchers.IO) {
                        val random = Random(seed * 1000 + worker)
                        repeat(STEPS) { step: Int ->
                            val index: Int = random.nextInt(names.size)
                            val storage: JvmKeyValueStorage = instances[index][random.nextInt(3)]
                            val key = "w$worker-s$step"
                            val cancelAfterYields: Int = if (random.nextInt(4) == 0) random.nextInt(3) else -1
                            val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                                when (val result: StorageResult<Unit> = storage.put(key, "v$step")) {
                                    is StorageResult.Success -> acknowledged[index] += key
                                    is StorageResult.Failure -> problems += "seed=$seed w=$worker s=$step put: ${result.error}"
                                }
                            }
                            if (cancelAfterYields >= 0) {
                                repeat(cancelAfterYields) { yield() }
                                job.cancel()
                            }
                            job.join()
                        }
                    }
                }.forEach { it.join() }
                writing.set(false)
                readers.forEach { it.join() }
            }

            assertEquals(emptyList(), problems.take(5), "seed=$seed")
            assertTrue(reads.get() > 0 && acknowledged.all { it.isNotEmpty() }, "seed=$seed control: reads and writes happened")
            names.indices.forEach { index: Int ->
                val fresh = JvmKeyValueStorage(File(directory, names[index]), Dispatchers.IO)
                val lost: List<String> = acknowledged[index].filter { key: String ->
                    (fresh.get(key) as? StorageResult.Success<String?>)?.value == null
                }
                assertEquals(emptyList(), lost.take(5), "seed=$seed: acknowledged writes to ${names[index]} were lost (${lost.size})")
            }
            assertEquals(0, plainLocks().activeKeyCount, "seed=$seed: an in-process lock entry outlived the storm")
            assertEquals(emptySet<String>(), claims(), "seed=$seed: a lock-file claim outlived the storm")
            assertEquals(descriptorsBefore, TestFs.requireOpenDescriptorCount(), "seed=$seed: a descriptor outlived the storm")
            val leftovers: List<String> = directory.list()!!.filter { it.endsWith(".tmp") }
            assertEquals(emptyList(), leftovers, "seed=$seed: a temporary file outlived a cancelled write")
        }

    @Suppress("UNCHECKED_CAST")
    private fun plainLocks(): KeyedMutex<String> {
        val field = JvmKeyValueStorage::class.java.getDeclaredField("LOCKS").apply { isAccessible = true }
        return field.get(null) as KeyedMutex<String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun claims(): Set<String> {
        val field = CrossProcessLock::class.java.getDeclaredField("CLAIMS").apply { isAccessible = true }
        return (field.get(null) as Set<String>).toSet()
    }

    private companion object {
        /** A hang bound (deadlock detector), never a timing assertion. */
        val STORM_BOUND = 180.seconds
        const val WORKERS: Int = 8
        const val STEPS: Int = 60
    }
}
