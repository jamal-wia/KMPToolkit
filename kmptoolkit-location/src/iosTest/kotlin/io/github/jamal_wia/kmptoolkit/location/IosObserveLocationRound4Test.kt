@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLAuthorizationStatusDenied

/**
 * Own tests of group loc-ios for the code changes of breaker round 2, next to the breaker classes (which
 * stay as they are): the trailing conflation of the stream must never skip or strand the terminal
 * reading. Virtual time only; no randomness.
 */
class IosObserveLocationRound4Test {

    private class Probe {
        lateinit var job: Job
        val labels: MutableList<String> = mutableListOf()
        var completed: Boolean = false
    }

    private fun label(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix${reading.fix.coordinates.latitude.toInt()}"
        is LocationReading.Unavailable -> reading.reason.name
    }

    private fun TestScope.fixture(): Pair<FakeIosLocationEnvironment, IosLocationProvider> {
        val env = FakeIosLocationEnvironment(testScheduler)
        return env to IosLocationProvider(env, NoopLogger)
    }

    private fun TestScope.collect(provider: IosLocationProvider, gate: CompletableDeferred<Unit>? = null): Probe {
        val probe = Probe()
        probe.job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                provider.observeLocation().collect { reading: LocationReading ->
                    probe.labels += label(reading)
                    if (probe.labels.size == 1) gate?.await()
                }
                probe.completed = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
        runCurrent()
        return probe
    }

    // ---------------------------------------------------------------------------------------------
    // M1: the conflation after the completion

    /**
     * The trailing `conflate()` sits behind the completion on purpose: the terminal reading is the last
     * element of the flow, so a busy collector must still receive it and complete, and the platform
     * registration must be released while that collector is still busy (no wait for the collector).
     */
    @Test
    fun `a terminal reading emitted while the collector is busy is not skipped and releases the registration at once`() = runTest {
        val (env, provider) = fixture()
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val probe: Probe = collect(provider, gate)
        val manager: FakeIosLocationManager = env.managers.single()
        assertEquals(listOf("NO_FIX"), probe.labels, "control: the collector is busy with the seed")
        for (latitude: Int in 10..14) {
            manager.deliver(rawFix(env.clock, latitude = latitude.toDouble()))
            runCurrent()
        }
        env.status = kCLAuthorizationStatusDenied
        env.announceChange()
        runCurrent()
        assertEquals(1, manager.detachCount, "the registration is released while the collector is still busy")
        assertEquals(listOf("NO_FIX"), probe.labels, "control: the busy collector has seen nothing more yet")
        assertFalse(probe.completed, "control: the collector has not finished")
        gate.complete(Unit)
        runCurrent()
        assertEquals(LocationUnavailability.PERMISSION_MISSING.name, probe.labels.last(), "the terminal reading is delivered: ${probe.labels}")
        assertTrue(probe.completed, "the collection ends after the terminal reading")
        assertTrue(probe.labels.size <= 3, "the busy collector skipped the intermediate fixes: ${probe.labels}")
        assertEquals(1, manager.detachCount, "released exactly once")
    }
}
