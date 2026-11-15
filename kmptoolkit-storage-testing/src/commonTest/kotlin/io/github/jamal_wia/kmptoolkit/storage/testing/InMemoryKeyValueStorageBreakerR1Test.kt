package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.errorOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Breaker round 1 of group sto-api (STO-2/STO-3, plan 3.8, D-10, T15, ticket release-5-1-0): the fixture
 * attacked where its own storm test does not go — many writers on the SAME keys across instances of one
 * backing, a reader racing the two snapshots (`writes` and `contents`), the failure knobs armed and
 * cleared by a chaos coroutine while operations run, callers cancelled at random points, and boundary
 * input (huge values, empty key and value, near-collision keys, thousands of keys).
 *
 * Contract (class KDoc and plan 3.8): safe from parallel threads, no write is lost, a failed operation has
 * no effect, a cancelled caller changes nothing, an entry may be visible in `contents` before its `writes`
 * record but never the other way round, knobs belong to the instance. Seeds are fixed and named in every
 * failure message; the real-time bounds are hang bounds only.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryKeyValueStorageBreakerR1Test {

    private fun check(condition: Boolean, seed: Int, what: () -> String) {
        if (!condition) fail("seed=$seed: ${what()}")
    }

    /** Runs [block] on real threads with a hang bound. */
    private suspend fun onThreads(block: suspend () -> Unit) {
        withContext(Dispatchers.Default) { withTimeout(120.seconds) { block() } }
    }

    @Test
    fun `many writers on the same keys over four instances never read a value nobody wrote`() = runTest(timeout = 180.seconds) {
        val seed = 1101
        val workers = 32
        val steps = 400
        val backing = InMemoryKeyValueStorage.Backing()
        val stores: List<InMemoryKeyValueStorage> = List(4) { InMemoryKeyValueStorage(backing = backing) }
        val written: Array<MutableSet<String>> = Array(workers) { mutableSetOf() }
        val observed: Array<MutableList<String>> = Array(workers) { mutableListOf() }
        val puts: Array<Int> = Array(4) { 0 }
        val putsPerWorker: Array<Int> = Array(workers) { 0 }
        val wrong: AtomicInt = AtomicInt(0)

        onThreads {
            coroutineScope {
                repeat(workers) { worker: Int ->
                    launch {
                        val random = Random(seed * 1000 + worker)
                        val store: InMemoryKeyValueStorage = stores[worker % 4]
                        repeat(steps) { step: Int ->
                            val key = "s${random.nextInt(4)}"
                            val result: StorageResult<*> = when (random.nextInt(10)) {
                                in 0..3 -> {
                                    val value = "w$worker-$step"
                                    written[worker] += value
                                    putsPerWorker[worker]++
                                    store.put(key, value)
                                }
                                in 4..6 -> store.get(key).also { read ->
                                    (read as? StorageResult.Success<String?>)?.value?.let { observed[worker] += it }
                                }
                                7 -> store.remove(key)
                                8 -> store.ensureKey()
                                else -> if (random.nextInt(8) == 0) store.clear() else store.get(key)
                            }
                            if (result !is StorageResult.Success<*>) wrong.addAndFetch(1)
                        }
                    }
                }
            }
        }

        repeat(workers) { worker: Int -> puts[worker % 4] += putsPerWorker[worker] }
        val all: Set<String> = written.fold(emptySet()) { acc, mine -> acc + mine }
        check(wrong.load() == 0, seed) { "${wrong.load()} operations failed on a store with no knob armed" }
        observed.forEachIndexed { worker: Int, values: List<String> ->
            val ghosts: List<String> = values.filter { it !in all }
            check(ghosts.isEmpty(), seed) { "worker $worker read ${ghosts.size} value(s) nobody wrote, first ${ghosts.firstOrNull()}" }
        }
        val contents: Map<String, String> = stores[0].contents
        check(contents.keys.all { it in setOf("s0", "s1", "s2", "s3") }, seed) { "foreign keys in $contents" }
        check(contents.values.all { it in all }, seed) { "final contents hold a value nobody wrote: $contents" }
        for (index: Int in 0 until 4) {
            check(stores[index].contents == contents, seed) { "instance $index sees ${stores[index].contents}, instance 0 sees $contents" }
            check(stores[index].writes.size == puts[index], seed) {
                "instance $index recorded ${stores[index].writes.size} writes for ${puts[index]} puts"
            }
        }
    }

    @Test
    fun `a writes record never shows up before its entry under concurrent writers and readers`() = runTest(timeout = 180.seconds) {
        val seed = 1102
        val writers = 8
        val keysPerWriter = 250
        val backing = InMemoryKeyValueStorage.Backing()
        val stores: List<InMemoryKeyValueStorage> = List(2) { InMemoryKeyValueStorage(backing = backing) }
        val violations: AtomicInt = AtomicInt(0)
        val firstViolation: Array<String?> = arrayOf(null)

        onThreads {
            coroutineScope {
                repeat(writers) { writer: Int ->
                    launch {
                        val store: InMemoryKeyValueStorage = stores[writer % 2]
                        repeat(keysPerWriter) { index: Int ->
                            if (store.put("w$writer-k$index", "v") != StorageResult.Success(Unit)) violations.addAndFetch(1)
                            if (index % 8 == 0) yield()
                        }
                    }
                }
                repeat(2) { observer: Int ->
                    launch {
                        val random = Random(seed * 1000 + observer)
                        repeat(500) {
                            val store: InMemoryKeyValueStorage = stores[random.nextInt(2)]
                            // Order matters: the record first, the entries after. Nothing removes, so a key in
                            // the record must already be in the entries read later.
                            val records: List<String> = store.writes
                            val contents: Map<String, String> = store.contents
                            val missing: String? = records.firstOrNull { it !in contents }
                            if (missing != null) {
                                if (violations.addAndFetch(1) == 1) firstViolation[0] = missing
                            }
                            yield()
                        }
                    }
                }
            }
        }

        check(violations.load() == 0, seed) {
            "${violations.load()} snapshot(s) showed a write record before its entry (or a failed put), first key ${firstViolation[0]}"
        }
        check(stores[0].contents.size == writers * keysPerWriter, seed) { "lost entries: ${stores[0].contents.size}" }
        check(stores[0].writes.size + stores[1].writes.size == writers * keysPerWriter, seed) { "lost records" }
    }

    @Test
    fun `a failed operation has no effect while a chaos coroutine arms and clears both knobs`() = runTest(timeout = 180.seconds) {
        val seed = 1103
        val workers = 16
        val steps = 300
        val every: StorageError = StorageError.Unavailable()
        val next: StorageError = StorageError.OperationFailed(StorageOperation.PUT, key = "scripted")
        val backing = InMemoryKeyValueStorage.Backing()
        val chaotic = InMemoryKeyValueStorage(backing = backing)
        val calm = InMemoryKeyValueStorage(backing = backing)
        val succeeded: Array<MutableList<String>> = Array(workers) { mutableListOf() }
        val failed: Array<MutableList<String>> = Array(workers) { mutableListOf() }
        val foreignErrors: AtomicInt = AtomicInt(0)
        val calmFailures: AtomicInt = AtomicInt(0)

        onThreads {
            coroutineScope {
                repeat(workers) { worker: Int ->
                    launch {
                        repeat(steps) { step: Int ->
                            val key = "a$worker-$step"
                            val result: StorageResult<Unit> = chaotic.put(key, "v$step")
                            when (result) {
                                is StorageResult.Success -> succeeded[worker] += key
                                is StorageResult.Failure -> {
                                    failed[worker] += key
                                    if (result.error != every && result.error != next) foreignErrors.addAndFetch(1)
                                }
                            }
                            if (step % 4 == 0) yield()
                        }
                    }
                }
                repeat(4) { worker: Int ->
                    launch {
                        repeat(steps) { step: Int ->
                            if (calm.put("b$worker-$step", "v") != StorageResult.Success(Unit)) calmFailures.addAndFetch(1)
                            if (step % 4 == 0) yield()
                        }
                    }
                }
                launch {
                    val random = Random(seed)
                    repeat(3000) {
                        when (random.nextInt(4)) {
                            0 -> chaotic.failEveryOperationWith = every
                            1 -> chaotic.failEveryOperationWith = null
                            2 -> chaotic.failNextOperationWith = next
                            else -> chaotic.failNextOperationWith = null
                        }
                        yield()
                    }
                    chaotic.failEveryOperationWith = null
                    chaotic.failNextOperationWith = null
                }
            }
        }

        val ok: Set<String> = succeeded.fold(emptySet()) { acc, mine -> acc + mine }
        val bad: Set<String> = failed.fold(emptySet()) { acc, mine -> acc + mine }
        check(foreignErrors.load() == 0, seed) { "${foreignErrors.load()} put(s) failed with an error nobody armed" }
        check(calmFailures.load() == 0, seed) { "${calmFailures.load()} put(s) on the instance without knobs failed" }
        val contents: Map<String, String> = chaotic.contents
        val leaked: List<String> = bad.filter { it in contents }
        check(leaked.isEmpty(), seed) { "${leaked.size} failed put(s) still changed the contents, first ${leaked.firstOrNull()}" }
        val lost: List<String> = ok.filter { it !in contents }
        check(lost.isEmpty(), seed) { "${lost.size} successful put(s) are missing, first ${lost.firstOrNull()}" }
        check(chaotic.writes.toSet() == ok && chaotic.writes.size == ok.size, seed) {
            "writes of the chaotic instance (${chaotic.writes.size}) are not exactly its ${ok.size} successful puts"
        }
        // The knobs are clear now, so the store has fully recovered.
        check(chaotic.put("after", "v") == StorageResult.Success(Unit), seed) { "the store did not recover after both knobs were cleared" }
        check(chaotic.ensureKey() == StorageResult.Success(Unit), seed) { "ensureKey did not recover" }
    }

    @Test
    fun `callers cancelled at random points leave exactly the writes that returned`() = runTest(timeout = 180.seconds) {
        val seed = 1104
        val jobsCount = 32
        val steps = 200
        val storage = InMemoryKeyValueStorage()
        val lastReturned: Array<Int> = Array(jobsCount) { -1 }
        val threwAtPut: Array<Int> = Array(jobsCount) { -1 }
        val cancelledJobs: AtomicInt = AtomicInt(0)

        onThreads {
            coroutineScope {
                val jobs: List<Job> = List(jobsCount) { job: Int ->
                    launch {
                        for (index: Int in 0 until steps) {
                            try {
                                val result: StorageResult<Unit> = storage.put("c$job-$index", "v")
                                if (result == StorageResult.Success(Unit)) lastReturned[job] = index
                            } catch (cancellation: CancellationException) {
                                threwAtPut[job] = index
                                throw cancellation
                            }
                            yield()
                        }
                    }
                }
                launch {
                    val random = Random(seed)
                    for (job: Int in (0 until jobsCount).shuffled(random)) {
                        repeat(random.nextInt(60)) { yield() }
                        if (jobs[job].isActive) cancelledJobs.addAndFetch(1)
                        jobs[job].cancel()
                    }
                }
            }
        }

        val contents: Map<String, String> = storage.contents
        val records: List<String> = storage.writes
        for (job: Int in 0 until jobsCount) {
            val expected: Set<String> = (0..lastReturned[job]).map { "c$job-$it" }.toSet()
            val actual: Set<String> = contents.keys.filter { it.startsWith("c$job-") }.toSet()
            check(actual == expected, seed) {
                "job $job: put returned up to #${lastReturned[job]}, threw at #${threwAtPut[job]}, contents hold ${actual.size} keys"
            }
            check(records.count { it.startsWith("c$job-") } == expected.size, seed) { "job $job: writes disagree with the puts that returned" }
            if (threwAtPut[job] >= 0) {
                check("c$job-${threwAtPut[job]}" !in contents, seed) { "job $job: the put that threw CancellationException wrote" }
            }
        }
        assertTrue(cancelledJobs.load() > 0, "seed=$seed: control: no job was still running when cancelled, the attack did nothing")
    }

    @Test
    fun `a restart after a sticky failure was cleared sees exactly the writes that succeeded and no knob`() = runTest {
        val backing = InMemoryKeyValueStorage.Backing()
        val before = InMemoryKeyValueStorage(backing = backing)
        before.put("kept", "1")
        before.failEveryOperationWith = StorageError.Unavailable()
        assertEquals(StorageError.Unavailable(), before.put("lost", "2").errorOrNull())
        assertEquals(StorageError.Unavailable(), before.clear().errorOrNull())
        before.failEveryOperationWith = null
        before.put("late", "3")
        before.failNextOperationWith = StorageError.Unavailable()

        val after = InMemoryKeyValueStorage(backing = backing)

        assertEquals(mapOf("kept" to "1", "late" to "3"), after.contents)
        assertEquals(emptyList(), after.writes)
        assertEquals(null, after.failNextOperationWith)
        assertEquals(null, after.failEveryOperationWith)
        assertEquals(StorageResult.Success("1"), after.get("kept"))
        // The old instance's armed knob is still its own.
        assertEquals(StorageError.Unavailable(), before.get("kept").errorOrNull())
    }

    @Test
    fun `huge values empty key and value and near-collision keys are kept apart and whole`() = runTest {
        val storage = InMemoryKeyValueStorage()
        val huge: String = "😀x".repeat(1_500_000)
        val nearlySame: List<String> = listOf("k", "K", "k ", " k", "k\u0000", "k​", "ｋ", "k\n", "k\r\n")

        assertEquals(StorageResult.Success(Unit), storage.put("huge", huge))
        assertEquals(StorageResult.Success(Unit), storage.put("", ""))
        nearlySame.forEachIndexed { index: Int, key: String -> assertEquals(StorageResult.Success(Unit), storage.put(key, "v$index")) }

        assertEquals(StorageResult.Success(huge), storage.get("huge"))
        assertEquals(StorageResult.Success(""), storage.get(""))
        nearlySame.forEachIndexed { index: Int, key: String -> assertEquals(StorageResult.Success("v$index"), storage.get(key), "key #$index") }
        assertEquals(nearlySame.size + 2, storage.contents.size)

        val unpairedAtTheEnd: String = "a".repeat(1_000_000) + "\uD800"
        val rejected: StorageError? = storage.put("tail", unpairedAtTheEnd).errorOrNull()
        assertTrue(rejected is StorageError.InvalidValue && rejected.key == "tail", "was $rejected")
        assertEquals(StorageResult.Success(null), storage.get("tail"))
        assertEquals(nearlySame.size + 2, storage.writes.size, "the rejected put left a record")
    }

    @Test
    fun `thousands of keys over two instances are all kept and one clear empties both`() = runTest {
        val backing = InMemoryKeyValueStorage.Backing()
        val first = InMemoryKeyValueStorage(backing = backing)
        val second = InMemoryKeyValueStorage(backing = backing)

        repeat(3000) { index: Int -> (if (index % 2 == 0) first else second).put("key-$index", "$index") }

        assertEquals(3000, first.contents.size)
        repeat(3000) { index: Int -> assertEquals(StorageResult.Success("$index"), second.get("key-$index")) }
        assertEquals(1500, first.writes.size)
        assertEquals(1500, second.writes.size)

        assertEquals(StorageResult.Success(Unit), second.clear())

        assertEquals(emptyMap(), first.contents)
        assertEquals(StorageResult.Success(null), first.get("key-0"))
    }

    @Test
    fun `reads and removes under keys with unpaired surrogates answer normally and record nothing`() = runTest {
        val storage = InMemoryKeyValueStorage()
        storage.put("k", "v")

        for (key: String in listOf("\uD800", "a\uDC00", "\uDC00\uD800")) {
            assertEquals(StorageResult.Success(null), storage.get(key))
            assertEquals(StorageResult.Success(Unit), storage.remove(key))
        }

        assertEquals(mapOf("k" to "v"), storage.contents)
        assertEquals(listOf("k"), storage.writes)
    }
}
