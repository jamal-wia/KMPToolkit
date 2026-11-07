package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The desktop store under many threads: puts, gets, removes and clears through several instances of
 * one file, and a device id asked for through many providers at once.
 */
class JvmKeyValueStorageStormRound3Test {

    private fun newDirectory(): File = Files.createTempDirectory("kmptoolkit-storm").toFile()

    private fun joinAll(threads: List<Thread>, what: String) {
        val deadline: Long = System.currentTimeMillis() + 60_000
        threads.forEach { it.join(maxOf(1L, deadline - System.currentTimeMillis())) }
        assertTrue(threads.none { it.isAlive }, "$what: a thread is still blocked after 60 s")
    }

    @Test
    fun `puts gets removes and clears through four instances never fail and never leave a temporary file`() {
        val directory: File = newDirectory()
        val config = StorageConfig("storm")
        val failures: MutableList<String> = CopyOnWriteArrayList()
        val clearing = AtomicBoolean(true)
        val instances: List<KeyValueStorage> = List(4) { createKeyValueStorage(directory, config) }

        fun check(what: String, result: StorageResult<*>) {
            if (result is StorageResult.Failure) failures += "$what: ${result.error}"
        }

        val clearer = thread(name = "clearer") {
            runBlocking {
                var spins = 0
                while (clearing.get() && spins < 300) {
                    check("clear", instances[spins % 4].clear())
                    spins++
                }
            }
        }
        val workers: List<Thread> = (0 until 8).map { worker: Int ->
            thread(name = "worker-$worker") {
                runBlocking {
                    val storage: KeyValueStorage = instances[worker % 4]
                    repeat(150) { index: Int ->
                        check("put", storage.put("w$worker", "v$index"))
                        check("get", storage.get("w$worker"))
                        if (index % 5 == 0) check("remove", storage.remove("w$worker"))
                    }
                }
            }
        }
        joinAll(workers, "workers")
        clearing.set(false)
        joinAll(listOf(clearer), "clearer")

        assertEquals(emptyList(), failures.take(5), "${failures.size} operations failed")
        runBlocking {
            for (worker: Int in 0 until 8) {
                assertEquals(StorageResult.Success(Unit), instances[0].put("final$worker", "x"))
            }
            for (worker: Int in 0 until 8) assertEquals("x", instances[3].get("final$worker").getOrNull())
        }
        assertEquals(listOf("storm.kmptoolkit.storage.properties"), directory.list()?.toList().orEmpty().sorted())
    }

    @Test
    fun `a device id asked for through three providers over four instances on 24 threads is one id for 60 rounds`() {
        repeat(60) { round: Int ->
            val directory: File = newDirectory()
            val config = StorageConfig("deviceid")
            val storages: List<KeyValueStorage> = List(4) { createKeyValueStorage(directory, config) }
            val providers: List<DeviceIdProvider> = List(3) { DeviceIdProvider(storages[it]) }
            val go = CountDownLatch(1)
            val ids: MutableList<String> = CopyOnWriteArrayList()
            val threads: List<Thread> = (0 until 24).map { index: Int ->
                thread(name = "id-$index") {
                    go.await()
                    runBlocking { ids += providers[index % 3].current() }
                }
            }
            go.countDown()
            joinAll(threads, "round $round")
            assertEquals(1, ids.toSet().size, "round $round: ${ids.toSet()}")
            assertEquals(ids.first(), runBlocking { storages[3].get(DeviceIdProvider.DEFAULT_KEY).getOrNull() }, "round $round")
        }
    }
}
