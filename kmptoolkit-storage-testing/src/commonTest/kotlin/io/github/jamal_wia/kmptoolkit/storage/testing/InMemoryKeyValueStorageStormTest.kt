package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.errorOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The fixture under parallel threads (plan D-10, test T15): 64 coroutines on `Dispatchers.Default` lose
 * nothing, and an armed one-shot failure fails exactly one of any number of concurrent operations. Seeds
 * are fixed and named in every failure message; the real-time bounds are hang bounds only.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryKeyValueStorageStormTest {

    private fun check(condition: Boolean, seed: Int, what: () -> String) {
        if (!condition) fail("seed=$seed: ${what()}")
    }

    /** [workers] coroutines over [instances] instances of one backing, each owning its own keys. */
    private suspend fun storm(workers: Int, stepsPerWorker: Int, instances: Int, seed: Int) {
        val backing = InMemoryKeyValueStorage.Backing()
        val stores: List<InMemoryKeyValueStorage> = List(instances) { InMemoryKeyValueStorage(backing = backing) }
        val puts: List<AtomicInt> = List(instances) { AtomicInt(0) }
        val wrong: AtomicInt = AtomicInt(0)
        val firstWrong: CompletableDeferred<String> = CompletableDeferred()
        val expectedPerWorker: Array<Map<String, String>> = Array(workers) { emptyMap() }

        withContext(Dispatchers.Default) {
            withTimeout(120.seconds) {
                coroutineScope {
                    repeat(workers) { worker: Int ->
                        launch {
                            val random: Random = Random(seed * 1000 + worker)
                            val store: InMemoryKeyValueStorage = stores[worker % instances]
                            val expected: MutableMap<String, String> = mutableMapOf()
                            repeat(stepsPerWorker) { step: Int ->
                                val key: String = "w$worker-k${random.nextInt(8)}"
                                when (random.nextInt(3)) {
                                    0 -> {
                                        val value: String = "v$step"
                                        if (store.put(key, value) != StorageResult.Success(Unit)) wrong.addAndFetch(1)
                                        puts[worker % instances].addAndFetch(1)
                                        expected[key] = value
                                    }
                                    1 -> {
                                        if (store.remove(key) != StorageResult.Success(Unit)) wrong.addAndFetch(1)
                                        expected.remove(key)
                                    }
                                    else -> {
                                        val read: StorageResult<String?> = store.get(key)
                                        if (read != StorageResult.Success(expected[key])) {
                                            wrong.addAndFetch(1)
                                            firstWrong.complete("worker $worker step $step key $key read $read expected ${expected[key]}")
                                        }
                                    }
                                }
                            }
                            expectedPerWorker[worker] = expected.toMap()
                        }
                    }
                }
            }
        }

        val firstProblem: String = if (firstWrong.isCompleted) firstWrong.await() else "none"
        check(wrong.load() == 0, seed) { "${wrong.load()} operations answered wrongly, first: $firstProblem" }
        val expectedAll: Map<String, String> = expectedPerWorker.fold(emptyMap()) { all, mine -> all + mine }
        check(stores[0].contents == expectedAll, seed) {
            "contents after the storm differ: lost ${expectedAll.keys - stores[0].contents.keys}, extra ${stores[0].contents.keys - expectedAll.keys}"
        }
        for (index: Int in 0 until instances) {
            check(stores[index].writes.size == puts[index].load(), seed) {
                "instance $index recorded ${stores[index].writes.size} writes for ${puts[index].load()} puts"
            }
        }
    }

    @Test
    fun `64 coroutines on one instance lose no write remove or read`() = runTest(timeout = 180.seconds) {
        storm(workers = 64, stepsPerWorker = 300, instances = 1, seed = 71)
    }

    @Test
    fun `64 coroutines over four instances of one backing lose nothing and keep their own write logs`() = runTest(timeout = 180.seconds) {
        storm(workers = 64, stepsPerWorker = 300, instances = 4, seed = 72)
    }

    @Test
    fun `an armed one-shot failure fails exactly one of 64 concurrent operations`() = runTest(timeout = 180.seconds) {
        val seed: Int = 73
        val storage = InMemoryKeyValueStorage()
        val failed: AtomicInt = AtomicInt(0)
        withContext(Dispatchers.Default) {
            withTimeout(120.seconds) {
                repeat(200) { round: Int ->
                    val error: StorageError = StorageError.OperationFailed(
                        operation = StorageOperation.GET,
                        key = "round-$round",
                    )
                    failed.store(0)
                    val start: CompletableDeferred<Unit> = CompletableDeferred()
                    storage.failNextOperationWith = error
                    coroutineScope {
                        repeat(64) { worker: Int ->
                            launch {
                                start.await()
                                val result: StorageResult<*> = when (worker % 3) {
                                    0 -> storage.get("k")
                                    1 -> storage.remove("k")
                                    else -> storage.ensureKey()
                                }
                                if (result.errorOrNull() == error) failed.addAndFetch(1)
                            }
                        }
                        start.complete(Unit)
                    }
                    check(failed.load() == 1, seed) { "round $round: ${failed.load()} operations got the one-shot failure, expected 1" }
                    assertNull(storage.failNextOperationWith, "seed=$seed: round $round left the knob armed")
                }
            }
        }
        assertEquals(emptyMap(), storage.contents)
    }
}
