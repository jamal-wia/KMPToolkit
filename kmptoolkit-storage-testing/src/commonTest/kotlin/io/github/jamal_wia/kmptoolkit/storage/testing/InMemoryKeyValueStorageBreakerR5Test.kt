package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Breaker round 5 of group sto-api (STO-2/STO-3, plan 3.8, D-10, T15, ticket release-5-1-0).
 *
 * Part 1 attacks the round 4 fix (each read of `writes` and `contents` is a mutable copy that is the caller's own,
 * KDoc of both getters and 06-testing.md) on every target, through the views a caller reaches from the copy
 * (sub-lists, iterators, key/value/entry views, `setValue` on an entry), and over time: a copy taken before a
 * later `put` must not follow it, and two reads must never be the same object.
 *
 * Part 2 attacks the one-shot knob's accounting while it is re-armed again and again under concurrent operations
 * (class KDoc: "an armed failNextOperationWith fails exactly one of any number of concurrent operations"; the
 * failing operation "has no effect"). Every arm must be consumed by exactly one operation: never twice, never
 * lost. Seeds are fixed and named in every failure message; the real-time bound is a hang bound only.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryKeyValueStorageBreakerR5Test {

    private fun check(condition: Boolean, seed: Int, what: () -> String) {
        if (!condition) fail("seed=$seed: ${what()}")
    }

    @Test
    fun `a copy and every view reached from it stay the callers own at every size and over later writes`() = runTest {
        for (size: Int in 0..3) {
            val backing = InMemoryKeyValueStorage.Backing()
            val fixture = InMemoryKeyValueStorage(backing = backing)
            val other = InMemoryKeyValueStorage(backing = backing)
            val keys: List<String> = (1..size).map { "k$it" }
            keys.forEach { assertEquals(StorageResult.Success(Unit), fixture.put(it, "v-$it")) }
            val expected: Map<String, String> = keys.associateWith { "v-$it" }

            val first: List<String> = fixture.writes
            val second: List<String> = fixture.writes
            assertNotSame(first, second, "size=$size: two reads of writes answered one object")
            assertNotSame(fixture.contents, fixture.contents, "size=$size: two reads of contents answered one object")

            // Views of a writes copy: a sub-list, an iterator's remove, add at the front.
            @Suppress("UNCHECKED_CAST")
            val log: MutableList<String> = first as MutableList<String>
            log.subList(0, log.size).clear()
            log.add(0, "ghost")
            val logIterator: MutableIterator<String> = log.iterator()
            logIterator.next()
            logIterator.remove()
            log.add("ghost-2")

            // Views of a contents copy: an entry's setValue, the key view, the value view, an entry iterator.
            @Suppress("UNCHECKED_CAST")
            val entries: MutableMap<String, String> = fixture.contents as MutableMap<String, String>
            entries.entries.forEach { entry: MutableMap.MutableEntry<String, String> -> entry.setValue("overwritten") }
            entries.keys.removeAll { it == "k1" }
            entries.values.remove("overwritten")
            val entryIterator: MutableIterator<MutableMap.MutableEntry<String, String>> = entries.entries.iterator()
            while (entryIterator.hasNext()) {
                entryIterator.next()
                entryIterator.remove()
            }
            entries["ghost"] = "v"

            assertEquals(keys, fixture.writes, "size=$size: the record was changed through a view of a copy")
            assertEquals(keys, second, "size=$size: changing one copy changed another")
            assertEquals(expected, fixture.contents, "size=$size: the store was changed through a view of a copy")
            assertEquals(expected, other.contents, "size=$size: another instance over the backing saw a copy's change")
            for ((key: String, value: String) in expected) {
                assertEquals(StorageResult.Success<String?>(value), other.get(key), "size=$size: $key read back changed")
            }
            assertEquals(StorageResult.Success<String?>(null), other.get("ghost"), "size=$size: a copy's entry reached the store")

            // Over time: a copy taken now must not follow a later write, remove or clear.
            val before: List<String> = fixture.writes
            val contentsBefore: Map<String, String> = fixture.contents
            assertEquals(StorageResult.Success(Unit), fixture.put("later", "x"))
            assertEquals(StorageResult.Success(Unit), other.remove("k1"))
            assertEquals(keys, before, "size=$size: an earlier writes copy followed a later put")
            assertEquals(expected, contentsBefore, "size=$size: an earlier contents copy followed a later put or remove")
            assertEquals(StorageResult.Success(Unit), other.clear())
            assertEquals(expected, contentsBefore, "size=$size: an earlier contents copy followed a clear")
            assertEquals(keys + "later", fixture.writes, "size=$size: the record is not the calls made")
            assertEquals(emptyMap(), fixture.contents, "size=$size: clear through another instance did not empty the backing")
        }
    }

    @Test
    fun `a one-shot failure re-armed again and again under concurrent operations is consumed exactly once per arm`() =
        runTest(timeout = 180.seconds) {
            val seed = 1501
            val workers = 16
            val operationsPerWorker = 400
            val storage = InMemoryKeyValueStorage()
            // consumed[i] counts how many operations failed with arm i; armed counts how many arms were made.
            val maxArms = workers * operationsPerWorker
            val consumed: Array<AtomicInt> = Array(maxArms) { AtomicInt(0) }
            val armed = AtomicInt(0)
            val workersDone = AtomicBoolean(false)
            val firstArm: CompletableDeferred<Unit> = CompletableDeferred()
            val unknownErrors = AtomicInt(0)
            val putRecords: Array<Array<String?>> = Array(workers) { arrayOfNulls(operationsPerWorker) }
            val putSucceeded: Array<BooleanArray> = Array(workers) { BooleanArray(operationsPerWorker) }

            fun account(result: StorageResult<*>): Boolean {
                if (result !is StorageResult.Failure) return true
                val error: StorageError = result.error
                val arm: Int? = (error as? StorageError.OperationFailed)?.platformCode
                if (arm == null || arm !in 0 until maxArms) {
                    unknownErrors.addAndFetch(1)
                } else {
                    consumed[arm].addAndFetch(1)
                }
                return false
            }

            withContext(Dispatchers.Default) {
                withTimeout(120.seconds) {
                    coroutineScope {
                        // The only coroutine that arms: it arms the next distinct error only after it saw the knob empty,
                        // and only operations clear it, so no arm ever overwrites another; every arm but possibly the
                        // last must be consumed by exactly one operation.
                        launch {
                            while (!workersDone.load()) {
                                if (storage.failNextOperationWith == null) {
                                    val index: Int = armed.load()
                                    if (index < maxArms) {
                                        storage.failNextOperationWith = StorageError.OperationFailed(
                                            operation = StorageOperation.GET,
                                            key = null,
                                            platformCode = index,
                                        )
                                        armed.store(index + 1)
                                        firstArm.complete(Unit)
                                    }
                                }
                                yield()
                            }
                        }
                        val workerJobs = List(workers) { worker: Int ->
                            launch {
                                // Control: no operation runs before the first arm, so the attack always runs.
                                firstArm.await()
                                val random = Random(seed * 100 + worker)
                                repeat(operationsPerWorker) { index: Int ->
                                    when (random.nextInt(5)) {
                                        0 -> account(storage.get("k${random.nextInt(8)}"))
                                        1 -> {
                                            val key = "w$worker-$index"
                                            putRecords[worker][index] = key
                                            putSucceeded[worker][index] = account(storage.put(key, "v"))
                                        }
                                        2 -> account(storage.remove("nope"))
                                        3 -> account(storage.ensureKey())
                                        else -> account(storage.get("w$worker-${random.nextInt(operationsPerWorker)}"))
                                    }
                                    if (random.nextInt(8) == 0) yield()
                                }
                            }
                        }
                        workerJobs.forEach { it.join() }
                        workersDone.store(true)
                    }
                }
            }

            val arms: Int = armed.load()
            val leftArmed: StorageError? = storage.failNextOperationWith
            val leftIndex: Int? = (leftArmed as? StorageError.OperationFailed)?.platformCode
            check(unknownErrors.load() == 0, seed) { "${unknownErrors.load()} failure(s) with an error nobody armed" }
            check(arms > 0, seed) { "control: the arming coroutine never armed (the attack did not run)" }
            for (index: Int in 0 until arms) {
                val count: Int = consumed[index].load()
                if (index == leftIndex) {
                    check(count == 0, seed) { "arm $index is still armed but was consumed $count time(s)" }
                } else {
                    check(count == 1, seed) {
                        "arm $index of $arms was consumed $count time(s) (0 = a lost arm, 2+ = a double consume); still armed: $leftIndex"
                    }
                }
            }
            check(leftIndex == null || leftIndex == arms - 1, seed) { "the arm left over is $leftIndex, not the last one (${arms - 1})" }

            // A failed put has no effect, a successful one is recorded exactly once.
            val log: List<String> = storage.writes
            val logSet: Set<String> = log.toSet()
            check(logSet.size == log.size, seed) { "the record holds a key twice" }
            val contents: Map<String, String> = storage.contents
            for (worker: Int in 0 until workers) {
                for (index: Int in 0 until operationsPerWorker) {
                    val key: String = putRecords[worker][index] ?: continue
                    val succeeded: Boolean = putSucceeded[worker][index]
                    check((key in logSet) == succeeded, seed) { "put $key succeeded=$succeeded but recorded=${key in logSet}" }
                    check((key in contents) == succeeded, seed) { "put $key succeeded=$succeeded but stored=${key in contents}" }
                }
            }
        }
}
