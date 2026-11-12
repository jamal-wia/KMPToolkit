package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 5 (lifecycle chaos) over a real SQLite file: callers cancelled at random moments inside and around a
 * transaction whose block suspends. A cancelled transaction rolls back whole, never leaves the database thread
 * parked on it, and the storage keeps serving the next caller.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStorageTransactionChaosRound5AdversarialTest {

    private val storages: MutableList<UploaderStorage> = mutableListOf()
    private val engines: MutableList<UploaderEngine> = mutableListOf()
    private val jobs: MutableList<Job> = mutableListOf()
    private val files: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
        engines.forEach { it.close() }
        jobs.forEach { it.cancel() }
        storages.forEach { runCatching { it.close() } }
        files.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    private fun open(): UploaderStorage {
        val name = "adv.a1r5.tx.${Random.nextLong(0L, Long.MAX_VALUE)}"
        files += name
        return createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
    }

    private fun item(id: String): UploaderItem = UploaderItem(
        id = id, type = "adv.r5", payload = "p", schemaVersion = 1, uniqueKey = id, orderingKey = null, tag = "t",
        state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
    )

    private class SilentHandler : UploaderHandler<String> {
        override val type: String = "adv.r5"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult = AttemptResult.Retry(null)
    }

    private fun realTest(seconds: Int = 90, body: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = (seconds + 10).seconds) { withContext(Dispatchers.Default) { withTimeout(seconds.seconds) { body() } } }

    @Test
    fun `transactions that suspend and are cancelled at random moments are all or nothing and the storage keeps working`() = realTest(120) {
        var committed = 0
        var rolledBack = 0
        var inFlightRolledBack = 0
        val seed: Long = Random.nextLong()
        val rnd = Random(seed)
        repeat(4) { iteration ->
            val storage: UploaderStorage = open()
            val store = storage.store
            val runner = storage.transactionRunner
            // Drawn up front: the inputs are reproducible from the seed and no Random is shared between threads.
            val firstDelay: LongArray = LongArray(150) { rnd.nextLong(0, 3) }
            val secondDelay: LongArray = LongArray(150) { rnd.nextLong(0, 3) }
            val cancelAfter: LongArray = LongArray(150) { rnd.nextLong(0, 6) }
            // mode 0: cancelled 0..5 ms after launch (mostly while queued on the single database thread)
            // mode 1: cancelled 0..5 ms after its block entered (mid-transaction or just after commit)
            // mode 2: never cancelled, must commit
            val entered: List<CompletableDeferred<Unit>> = List(150) { CompletableDeferred() }
            val outcomes: Array<Result<Unit>?> = arrayOfNulls(150)
            val cancellers: MutableList<Job> = mutableListOf()
            val callers: List<Job> = List(150) { n ->
                launch(Dispatchers.Default) {
                    outcomes[n] = runCatching {
                        runner.inTransaction {
                            entered[n].complete(Unit)
                            store.insertKeep(item("a$n"))
                            delay(firstDelay[n])
                            store.insertKeep(item("b$n"))
                            delay(secondDelay[n])
                            store.insertKeep(item("c$n"))
                        }
                    }.map { }
                }.also { job ->
                    when (n % 3) {
                        0 -> cancellers += launch(Dispatchers.Default) { delay(cancelAfter[n]); job.cancel() }
                        1 -> cancellers += launch(Dispatchers.Default) { entered[n].await(); delay(cancelAfter[n]); job.cancel() }
                        else -> Unit
                    }
                }
            }
            callers.joinAll()
            cancellers.forEach { it.cancel() } // a mode-1 canceller whose caller never entered must not outlive it

            for (n in 0 until 150) {
                val present: List<Boolean> = listOf("a$n", "b$n", "c$n").map { store.getById(it) != null }
                assertTrue(present.all { it } || present.none { it }, "iteration $iteration: transaction $n half applied: $present")
                // A mode-0 caller cancelled before its coroutine body started records no outcome: it never entered the
                // transaction, so it must have left no row. Any other caller must have recorded one.
                val outcome: Result<Unit> = outcomes[n] ?: run {
                    assertTrue(
                        n % 3 == 0 && !entered[n].isCompleted && callers[n].isCancelled && present.none { it },
                        "seed $seed iteration $iteration: caller $n recorded no outcome (entered=${entered[n].isCompleted}, rows=$present)",
                    )
                    Result.failure(CancellationException("cancelled before it started"))
                }
                val failure: Throwable? = outcome.exceptionOrNull()
                assertTrue(
                    failure == null || failure is CancellationException,
                    "seed $seed iteration $iteration: transaction $n failed with a non-cancellation error: $failure",
                )
                if (outcome.isSuccess) {
                    assertTrue(present.all { it }, "seed $seed iteration $iteration: transaction $n returned committed but its writes are gone: $present")
                }
                if (n % 3 == 2) {
                    assertTrue(outcome.isSuccess, "seed $seed iteration $iteration: uncancelled transaction $n did not commit: $failure")
                }
                if (present.all { it }) committed++ else rolledBack++
                if (n % 3 == 1 && entered[n].isCompleted && present.none { it }) inFlightRolledBack++
            }
            // liveness: a fresh write and read are served at once
            withTimeout(5.seconds) {
                store.insertKeep(item("fresh"))
                assertEquals("fresh", store.getById("fresh")?.id)
            }
        }
        assertTrue(committed > 0 && rolledBack > 0, "the random cancellations must hit both sides: committed=$committed rolledBack=$rolledBack")
        assertTrue(inFlightRolledBack > 0, "seed $seed: no cancellation landed inside an entered transaction (in-flight rollbacks=0)")
    }

    @Test
    fun `an engine enqueueing inside suspended cancelled transactions leaves each domain write and its effect together`() = realTest(120) {
        val storage: UploaderStorage = open()
        val handler = SilentHandler()
        val job = Job().also { jobs += it }
        val engine: UploaderEngine = createUploaderEngine(
            store = storage.store,
            handlers = listOf(handler),
            scope = CoroutineScope(Dispatchers.Default + job),
            transactionRunner = storage.transactionRunner,
        ).also { engines += it }
        // an uncancelled control first: the transaction itself must work
        val control: Result<Unit> = runCatching {
            storage.transactionRunner.inTransaction {
                storage.store.insertKeep(item("domain-control").copy(type = "adv.domain"))
                delay(2)
                engine.enqueue(handler, "effect-control", uniqueKey = "effect-control")
                delay(2)
            }
        }
        assertTrue(control.isSuccess, "control transaction failed: ${control.exceptionOrNull()}")
        // the "domain" rows live in the same file under another type: the handler is not registered for it, so
        // its rows are parked by the drain, which is exactly what keeps them observable
        val callers: List<Job> = List(150) { n ->
            launch(Dispatchers.Default) {
                runCatching {
                    storage.transactionRunner.inTransaction {
                        storage.store.insertKeep(item("domain-$n").copy(type = "adv.domain"))
                        delay(Random.nextLong(0, 3))
                        engine.enqueue(handler, "effect-$n", uniqueKey = "effect-$n")
                        delay(Random.nextLong(0, 3))
                    }
                }
            }.also { call ->
                launch(Dispatchers.Default) {
                    delay(Random.nextLong(0, 700)) // transactions queue on the single database thread
                    call.cancel()
                }
            }
        }
        callers.joinAll()

        var committed = 0
        var rolledBack = 0
        val effects: Set<String> = storage.store.getAllActive().filter { it.type == "adv.r5" }.mapNotNull { it.uniqueKey }.toSet()
        for (n in 0 until 150) {
            val domain: Boolean = storage.store.getById("domain-$n") != null
            val effect: Boolean = "effect-$n" in effects
            assertEquals(domain, effect, "transaction $n: domain=$domain effect=$effect")
            if (domain) committed++ else rolledBack++
        }
        assertTrue(committed > 0 && rolledBack > 0, "the random cancellations must hit both sides: committed=$committed rolledBack=$rolledBack")
    }
}
