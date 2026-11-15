@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied

/**
 * ServiceStateFlowIosTest (`observeLocationEnabled`) and a seeded storm over the whole stream session
 * (PLAN 6.4 ReleaseTest, concurrency shape): a random script of deliveries, failures, service and
 * authorization flips, pauses, restarts and cancellations, with the structural invariants checked
 * after every step and after the release. Virtual time; the seed is in every message.
 */
class IosServiceStateAndStormTest {

    private fun TestScope.fixture(): Pair<FakeIosLocationEnvironment, IosLocationProvider> {
        val env = FakeIosLocationEnvironment(testScheduler)
        return env to IosLocationProvider(env, NoopLogger)
    }

    private fun TestScope.collectEnabled(provider: IosLocationProvider, into: MutableList<Boolean>): Job {
        val job: Job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            provider.observeLocationEnabled().collect { value: Boolean -> into += value }
        }
        runCurrent()
        return job
    }

    @Test
    fun `observeLocationEnabled emits at once and on every change and distinct and and starts no updates`() = runTest {
        val (env, provider) = fixture()
        val values: MutableList<Boolean> = mutableListOf()
        val job: Job = collectEnabled(provider, values)
        assertEquals(listOf(true), values)
        val manager: FakeIosLocationManager = env.managers.single()
        assertTrue(manager.createdOnMain)
        env.servicesOn = false
        env.announceChange()
        runCurrent()
        env.announceChange()
        env.announceChange()
        runCurrent()
        assertEquals(listOf(true, false), values, "an unchanged state is not repeated")
        env.servicesOn = true
        env.announceChange()
        runCurrent()
        assertEquals(listOf(true, false, true), values)
        assertEquals(0, manager.startCount, "observing the switch starts no location updates")
        assertEquals(0, manager.requestCount)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `observeLocationEnabled releases its manager on cancel and works again`() = runTest {
        val (env, provider) = fixture()
        repeat(3) { round: Int ->
            val values: MutableList<Boolean> = mutableListOf()
            val job: Job = collectEnabled(provider, values)
            assertEquals(1, env.liveManagers.size, "control: registered while collected, round $round")
            job.cancel()
            runCurrent()
            assertEquals(0, env.liveManagers.size)
        }
        assertTrue(env.managers.all { it.detachCount == 1 && it.detachedOnMain == true })
        assertEquals(3, env.managers.size)
    }

    @Test
    fun `isLocationEnabled reads the switch off the main dispatcher and openLocationSettings goes to the seam`() = runTest {
        val (env, provider) = fixture()
        assertTrue(provider.isLocationEnabled())
        env.servicesOn = false
        assertFalse(provider.isLocationEnabled())
        provider.openLocationSettings()
        assertEquals(1, env.settingsOpened)
        assertTrue(env.managers.isEmpty())
    }

    @Test
    fun `a seeded storm of events never leaks a manager and never emits after completion and never starts a second delegate`() = runTest {
        for (seed: Int in 1..30) storm(seed)
    }

    private fun TestScope.storm(seed: Int) {
        val random = Random(seed)
        val (env, provider) = fixture()
        val problems: MutableList<String> = mutableListOf()
        var readings: MutableList<LocationReading> = mutableListOf()
        var completed = false
        var job: Job? = null
        var collections = 0

        fun begin() {
            readings = mutableListOf()
            completed = false
            collections++
            val options = LocationUpdatesOptions(
                minUpdateInterval = random.nextInt(0, 5).seconds,
                minUpdateDistanceMeters = random.nextInt(0, 3) * 10.0,
                platform = IosLocationUpdatesOptions(pausesLocationUpdatesAutomatically = random.nextBoolean()),
            )
            val mine: MutableList<LocationReading> = readings
            job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    provider.observeLocation(options).collect { reading: LocationReading -> mine += reading }
                    completed = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                }
            }
            runCurrent()
        }

        fun check(step: Int) {
            val live: Int = env.liveManagers.size
            if (live > 1) problems += "step $step: $live live managers"
            env.managers.filter { it.detached && it.updating }.forEach { problems += "step $step: a detached manager is updating" }
            env.managers.filter { it.detachCount > 1 }.forEach { problems += "step $step: detach called ${it.detachCount} times" }
            if (completed && live != 0) problems += "step $step: completed with a live manager"
        }

        begin()
        for (step: Int in 1..150) {
            val manager: FakeIosLocationManager? = env.liveManagers.lastOrNull()
            val before: Int = readings.size
            val wasCompleted: Boolean = completed
            when (random.nextInt(11)) {
                0, 1 -> manager?.deliver(rawFix(env.clock, age = random.nextInt(0, 90).seconds, latitude = random.nextDouble(-80.0, 80.0)))
                2 -> manager?.deliver(rawFix(env.clock, horizontalAccuracy = -1.0))
                3 -> manager?.fail(IosFailureKind.entries[random.nextInt(IosFailureKind.entries.size)])
                4 -> {
                    env.servicesOn = random.nextBoolean()
                    env.announceChange()
                }
                5 -> {
                    env.status = if (random.nextInt(4) == 0) kCLAuthorizationStatusDenied else kCLAuthorizationStatusAuthorizedWhenInUse
                    env.announceChange()
                }
                6 -> {
                    env.reduced = random.nextBoolean()
                    env.announceChange()
                }
                7 -> if (random.nextBoolean()) manager?.pause() else manager?.resume()
                8 -> env.activate()
                9 -> {
                    env.clock.advance(random.nextInt(1, 20).seconds)
                    advanceTimeBy(random.nextInt(1, 5000).milliseconds)
                }
                else -> if (random.nextInt(3) == 0) {
                    job?.cancel()
                    runCurrent()
                    env.status = kCLAuthorizationStatusAuthorizedWhenInUse
                    env.servicesOn = true
                    begin()
                }
            }
            runCurrent()
            if (wasCompleted && readings.size != before) problems += "step $step: a reading after completion"
            if (completed && !wasCompleted) {
                // A terminal reading completed the stream: start a fresh collection like an app would.
                env.status = kCLAuthorizationStatusAuthorizedWhenInUse
                env.servicesOn = true
                begin()
            }
            check(step)
        }
        job?.cancel()
        runCurrent()
        assertTrue(problems.isEmpty(), "seed=$seed collections=$collections: ${problems.take(5)}")
        assertTrue(env.liveManagers.isEmpty(), "seed=$seed: a manager survived the release")
        assertTrue(env.managers.all { it.detachCount == 1 }, "seed=$seed: every manager is released exactly once")
        assertTrue(env.activeListeners.isEmpty(), "seed=$seed: a did-become-active listener survived the release")
    }
}
