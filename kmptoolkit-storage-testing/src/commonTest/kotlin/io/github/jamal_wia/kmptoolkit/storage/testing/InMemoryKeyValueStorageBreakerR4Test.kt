package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Breaker round 4 of group sto-api (STO-2/STO-3, plan 3.8, D-10, T15, ticket release-5-1-0): the copy-per-read
 * `writes` of the round 3 fix, read from many threads while writers run.
 *
 * Contract (class KDoc and 06-testing.md): `writes` is every key the instance was asked to write, "in call order";
 * under concurrent writers "the order is the order in which the records landed"; each read returns a copy that is
 * the caller's own; the fixture is safe from any number of threads. Records are only ever appended, so every
 * snapshot a reader takes must extend the snapshot it took before (a prefix), a reader may sort its own copy
 * without disturbing anyone, and each writer's keys must appear in that writer's own call order. Seeds are fixed
 * and named in every failure message; the real-time bound is a hang bound only.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryKeyValueStorageBreakerR4Test {

    private fun check(condition: Boolean, seed: Int, what: () -> String) {
        if (!condition) fail("seed=$seed: ${what()}")
    }

    @Test
    fun `every writes snapshot extends the one before it and keeps each writers own order`() = runTest(timeout = 180.seconds) {
        val seed = 1401
        val writers = 8
        val keysPerWriter = 300
        val readers = 4
        val storage = InMemoryKeyValueStorage()
        val violations: AtomicInt = AtomicInt(0)
        val firstViolation: Array<String?> = arrayOf(null)

        fun violation(text: String) {
            if (violations.addAndFetch(1) == 1) firstViolation[0] = text
        }

        withContext(Dispatchers.Default) {
            withTimeout(120.seconds) {
                coroutineScope {
                    repeat(writers) { writer: Int ->
                        launch {
                            repeat(keysPerWriter) { index: Int ->
                                if (storage.put("w$writer-k$index", "v") != StorageResult.Success(Unit)) {
                                    violation("put w$writer-k$index failed")
                                }
                                if (index % 16 == 0) yield()
                            }
                        }
                    }
                    repeat(readers) { reader: Int ->
                        launch {
                            val random = Random(seed * 100 + reader)
                            var previous: List<String> = emptyList()
                            repeat(600) {
                                val snapshot: List<String> = storage.writes
                                if (snapshot.size < previous.size || snapshot.subList(0, previous.size) != previous) {
                                    violation("reader $reader: a snapshot of ${snapshot.size} does not extend the previous one of ${previous.size}")
                                }
                                // The reader's own copy: sorting it (through a mutable copy, the common-code way) must not
                                // disturb the record or the other readers.
                                val mine: MutableList<String> = snapshot.toMutableList()
                                mine.shuffle(random)
                                mine.sort()
                                check(mine.size == snapshot.size, seed) { "sorting changed a size" }
                                previous = snapshot
                                if (random.nextInt(4) == 0) yield()
                            }
                        }
                    }
                }
            }
        }

        check(violations.load() == 0, seed) { "${violations.load()} violation(s), first: ${firstViolation[0]}" }
        val log: List<String> = storage.writes
        check(log.size == writers * keysPerWriter, seed) { "the record holds ${log.size} keys for ${writers * keysPerWriter} puts" }
        check(log.toSet().size == log.size, seed) { "the record holds a key twice although every key was written once" }
        for (writer: Int in 0 until writers) {
            val own: List<String> = log.filter { it.startsWith("w$writer-") }
            check(own == List(keysPerWriter) { "w$writer-k$it" }, seed) {
                "writer $writer's keys are not in its call order: ${own.take(8)}..."
            }
        }
        check(storage.contents.size == writers * keysPerWriter, seed) { "lost entries: ${storage.contents.size}" }
    }
}
