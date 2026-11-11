package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
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
 * Round 7 (A1), attack 3 and 5: the same scenarios with ONE store call failing (call number 0..N, before or after the
 * write committed) under seeded random schedules. After the failure the contract still holds: nothing runs after its
 * withdrawal returned, nothing is lost, and the queue drains once the store works again. Seed, call number and mode are
 * in every failure message.
 */
@RunWith(AndroidJUnit4::class)
class UploaderProtocolFailureInjectionRound7AdversarialTest : InterleavingTestBase() {

    private fun sweep(label: String, base: Scenario, seeds: Int, calls: Int) = runBlocking {
        withTimeout(150.seconds) {
            val storage: UploaderStorage = open()
            val testScheduler = TestCoroutineScheduler()
            val failures: MutableList<String> = mutableListOf()
            var ran = 0
            for (seed in 0 until seeds) for (call in 0 until calls) for (after in listOf(false)) {
                val scenario = Scenario(
                    "$label seed=$seed call=$call after=$after", base.seed, base.ops, base.metas, base.strictRestart, 1000,
                    failAt = call, failAfterCommit = after, upload = base.upload,
                )
                val runner = ScenarioRunner(storage, testScheduler, CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()), scenario)
                val result: RunResult = runner.run(emptyList(), Random(seed))
                ran++
                if (result.violations.isNotEmpty() && failures.size < 3) failures += "${scenario.name}: ${result.violations}\n  log=${result.log}"
            }
            println("R7 failure sweep $label: $ran runs")
            assertEquals(emptyList(), failures, "$label: violations in $ran runs")
        }
    }

    @Test
    fun `plain items - one failing store call anywhere among keep, replace, cancel and a tag wipe`() =
        sweep("plain", Scenario("p", Seed.PENDING_A, listOf(Op.KEEP_B, Op.REPLACE_C, Op.CANCEL_A, Op.CANCEL_TAG), listOf(Meta.TRIGGER), true, 1000), seeds = 4, calls = 40)

    @Test
    fun `plain items - one failing store call under close and restart`() =
        sweep("plain-restart", Scenario("p", Seed.PENDING_A, listOf(Op.REPLACE_C, Op.CANCEL_A, Op.FREE_D), listOf(Meta.CLOSE, Meta.RESTART), true, 1000), seeds = 4, calls = 40)

    @Test
    fun `uploads - one failing store call anywhere among replace, cancel and a delivery report`() =
        sweep("upload", Scenario("u", Seed.PENDING_A, listOf(Op.REPLACE_C, Op.CANCEL_A, Op.SETTLE_A), listOf(Meta.CLOSE), true, 1000, upload = true), seeds = 4, calls = 40)

    @Test
    fun `uploads - one failing store call with an expired lease and a restart`() =
        sweep("upload-restart", Scenario("u", Seed.EXPIRED_A, listOf(Op.KEEP_B, Op.CANCEL_TAG, Op.SETTLE_A), listOf(Meta.ADVANCE, Meta.CLOSE, Meta.RESTART), true, 1000, upload = true), seeds = 4, calls = 40)
}
