package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 7 (A1), attack 2: seeded random scenarios (start state, set of operations, meta events, strictness) under
 * seeded random schedules with no preemption bound, over the real SQLite store. The seed is in every failure message
 * and reproduces the whole run.
 */
@RunWith(AndroidJUnit4::class)
class UploaderProtocolRandomModelRound7AdversarialTest {

    private val files: MutableList<String> = mutableListOf()
    private val storages: MutableList<UploaderStorage> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
        storages.forEach { runCatching { it.close() } }
        files.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    private fun scenarioFor(seed: Int): Scenario {
        val r = Random(seed)
        val upload: Boolean = r.nextBoolean()
        val ops: List<Op> = Op.entries.filter { upload || it != Op.SETTLE_A }.shuffled(r).take(2 + r.nextInt(4))
        val metas: List<Meta> = Meta.entries.filter { r.nextInt(100) < 55 }
        val seeds: List<Seed> = Seed.entries.filter { upload || (it != Seed.INFLIGHT_A && it != Seed.EXPIRED_A) }
        return Scenario("seed $seed", seeds[r.nextInt(seeds.size)], ops, metas, r.nextBoolean(), 1000, upload = upload)
    }

    @Test
    fun `four hundred seeded random scenarios satisfy the protocol invariants on SQLite`() = runBlocking {
        withTimeout(240.seconds) {
            val name = "adv.a1r7.rnd.${Random.nextLong(0L, Long.MAX_VALUE)}"
            files += name
            val storage: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
            val testScheduler = TestCoroutineScheduler()
            val failures: MutableList<String> = mutableListOf()
            var ran = 0
            val started: Long = System.nanoTime()
            for (seed in 0 until 400) {
                if (System.nanoTime() - started > 150_000_000_000L) break
                val scenario: Scenario = scenarioFor(seed)
                val runner = ScenarioRunner(storage, testScheduler, CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()), scenario)
                val result: RunResult = runner.run(emptyList(), Random(seed * 31 + 7))
                ran++
                if (result.violations.isNotEmpty() && failures.size < 3) {
                    failures += "seed $seed (${scenario.seed}, ops=${scenario.ops}, metas=${scenario.metas}, strict=${scenario.strictRestart}, upload=${scenario.upload}) -> ${result.violations}\n  log=${result.log}"
                }
            }
            println("R7 random: $ran seeds")
            assertEquals(emptyList(), failures, "violations after $ran seeds")
            assertEquals(400, ran, "only $ran of 400 seeds ran in the time budget")
        }
    }
}
