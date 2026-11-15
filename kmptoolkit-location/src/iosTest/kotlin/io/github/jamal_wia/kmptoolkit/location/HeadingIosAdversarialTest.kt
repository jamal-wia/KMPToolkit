@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class, DelicateCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Attacks on the lifecycle of the iOS heading provider (group loc-heading, decision D-23), through its seams:
 * a cancel that lands before the main-queue block creates the manager, or between creation and teardown;
 * events that arrive after the collection closed but before the queued teardown ran; a manager that fails to
 * open or to start; raw values CoreLocation should never send; an authorization that flaps; and a seeded storm
 * with a simulated main thread, collectors cancelled from other threads.
 *
 * Every release proof starts with a control assertion that the manager existed. Nothing sleeps, every random
 * draw comes from `Random(HEADING_TEST_SEED)`-derived generators and the seed is in each failure message.
 */
class HeadingIosAdversarialTest {

    private val device = FakeHeadingDevice()
    private val logger = RecordingLogger()

    @Test
    fun `a cancel before the main queue creates the manager creates none`() = runTest {
        val main = DeferredConfine()
        val provider: HeadingProvider = IosHeadingProvider(device, false, logger, main.confine)
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { provider.observeHeading().collect { } }
        assertEquals(1, main.pending, "control: the creation is queued on the main queue, nothing created yet")
        assertEquals(0, device.openCalls.load())

        collection.cancelAndJoin()
        assertEquals(2, main.pending, "the teardown is queued behind the creation")
        main.drain()

        assertEquals(0, device.openCalls.load(), "the creation saw the closed fence and created nothing")
        assertEquals(0, device.liveManagers.size)
    }

    @Test
    fun `a cancel between creation and teardown fences events at once and releases on the queue`() = runTest {
        val main = DeferredConfine()
        val provider: HeadingProvider = IosHeadingProvider(device, false, logger, main.confine)
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeHeading(minChangeDegrees = 0.0).collect { received += it }
        }
        main.drain()
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        manager.events.onHeading(10.0, -1.0, 5.0)
        assertEquals(1, received.size, "control: the manager delivered before the cancel")

        collection.cancelAndJoin()
        // The manager is still alive: its teardown waits on the main queue. A late delivery must go nowhere.
        assertFalse(manager.isReleased, "control: the teardown has not run yet, the manager could still deliver")
        manager.events.onHeading(20.0, -1.0, 5.0)
        manager.authorized = true
        manager.events.onAuthorizationChanged()
        manager.events.onCalibrationRequested()
        main.drain()

        assertEquals(1, received.size, "nothing is delivered after the close")
        assertEquals(0, manager.locationStarts.load(), "an authorization that arrives after the close starts nothing")
        assertTrue(manager.isReleased)
        assertEquals(1, manager.releases.load())
    }

    @Test
    fun `a manager that fails to open leaves the stream silent reports it and the next collection works`() = runTest {
        val provider: HeadingProvider = IosHeadingProvider(device, false, logger, inlineConfine)
        device.openFailure = IllegalStateException("no manager")
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeHeading(minChangeDegrees = 0.0).collect { received += it }
        }

        assertEquals(1, device.openCalls.load(), "control: it tried")
        assertTrue(collection.isActive, "no manager: open and silent, no crash on the main queue")
        assertTrue(logger.messages.any { it.first == LogLevel.WARN }, "reported: ${logger.messages}")
        collection.cancelAndJoin()
        assertEquals(0, device.liveManagers.size)

        device.openFailure = null
        val again: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeHeading(minChangeDegrees = 0.0).collect { received += it }
        }
        device.liveManagers.single().events.onHeading(5.0, -1.0, 1.0)
        assertEquals(1, received.size, "re-init after a failed start")
        again.cancelAndJoin()
    }

    @Test
    fun `a manager whose heading cannot start is released at once and reported`() = runTest {
        device.startHeadingFailure = IllegalStateException("heading refused")
        val provider: HeadingProvider = IosHeadingProvider(device, false, logger, inlineConfine)

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { provider.observeHeading().collect { } }

        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
        assertTrue(manager.isReleased, "the half-started manager was released")
        assertEquals(1, manager.releases.load())
        assertEquals(0, device.liveManagers.size)
        assertTrue(logger.messages.any { it.first == LogLevel.WARN })
        assertTrue(collection.isActive, "and the stream stays open and silent")
        collection.cancelAndJoin()
        assertEquals(1, manager.releases.load(), "cancelling later does not release twice")
    }

    @Test
    fun `raw values CoreLocation should never send never produce an unusable heading`() = runTest {
        val provider: HeadingProvider = IosHeadingProvider(device, false, logger, inlineConfine)
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeHeading(minChangeDegrees = 0.0).collect { received += it }
        }
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        val inf: Double = Double.POSITIVE_INFINITY
        val extremes: List<Triple<Double, Double, Double>> = listOf(
            Triple(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE),
            Triple(1e300, -1e300, 1e300),
            Triple(0.0, 0.0, 0.0),
            Triple(-0.0, -0.0, -0.0),
            Triple(359.99999999999994, 359.99999999999994, 1.0),
            Triple(Double.MIN_VALUE, Double.MIN_VALUE, Double.MIN_VALUE),
            Triple(inf, inf, inf),
            Triple(Double.NaN, Double.NaN, Double.NaN),
            Triple(720.5, 1080.25, 3.0),
        )

        extremes.forEach { (magnetic: Double, trueHeading: Double, accuracy: Double) -> manager.events.onHeading(magnetic, trueHeading, accuracy) }

        assertTrue(received.isNotEmpty(), "control: the sane ones were served")
        assertTrue(
            received.all { it.magneticDegrees.isFinite() && it.magneticDegrees >= 0.0 && it.magneticDegrees < 360.0 },
            "magnetic: ${received.map { it.magneticDegrees }}",
        )
        assertTrue(received.all { heading: Heading -> heading.trueDegrees.let { value: Double? -> value == null || (value.isFinite() && value >= 0.0 && value < 360.0) } })
        assertTrue(received.all { heading: Heading -> heading.accuracyDegrees.let { value: Double? -> value == null || (value.isFinite() && value >= 0.0) } })
        collection.cancelAndJoin()
    }

    @Test
    fun `an authorization that flaps never starts location updates while unauthorized and never leaves them running after`() = runTest {
        val rng = Random(HEADING_TEST_SEED)
        val provider: HeadingProvider = IosHeadingProvider(device, false, logger, inlineConfine)
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { provider.observeHeading().collect { } }
        val manager: FakeHeadingDevice.FakeManager = device.liveManagers.single()

        var granted = false
        repeat(2_000) { step: Int ->
            granted = rng.nextBoolean()
            manager.authorized = granted
            manager.events.onAuthorizationChanged()
            assertEquals(granted, manager.locationRunning, "step=$step granted=$granted" + seedNote(HEADING_TEST_SEED))
            assertTrue(manager.locationStarts.load() - manager.locationStops.load() in 0..1, "step=$step starts/stops out of balance" + seedNote(HEADING_TEST_SEED))
        }

        collection.cancelAndJoin()
        assertFalse(manager.locationRunning)
        assertTrue(device.problemList.isEmpty(), device.problemList.toString())
    }

    @Test
    fun `a storm with a simulated main thread starts feeds and cancels collections without leaking a manager`() = runTest(timeout = 120.seconds) {
        val seed: Int = HEADING_TEST_SEED
        val collectors = 16
        val feeders = 4
        val stepsPerFeeder = 1_500
        val main = newSingleThreadContext("heading-main-simulation")
        try {
            val mainScope = CoroutineScope(main)
            val confine: (() -> Unit) -> Unit = { block: () -> Unit -> mainScope.launch { block() } }
            val provider: HeadingProvider = IosHeadingProvider(device, false, logger, confine)
            val problems: AtomicReference<List<String>> = AtomicReference(emptyList())
            fun report(problem: String) {
                while (true) {
                    val before: List<String> = problems.load()
                    if (problems.compareAndSet(before, before + problem)) return
                }
            }
            val emissions: List<AtomicInt> = List(collectors) { AtomicInt(0) }
            val atJoin: List<AtomicInt> = List(collectors) { AtomicInt(-1) }
            device.authorizedAtOpen = true
            val allOpened = CompletableDeferred<Unit>()
            device.afterOpen = { count: Int -> if (count == collectors) allOpened.complete(Unit) }

            withContext(Dispatchers.Default) {
                val jobs: List<Job> = List(collectors) { index: Int ->
                    launch {
                        provider.observeHeading(minChangeDegrees = 0.0).collect { heading: Heading ->
                            emissions[index].incrementAndFetch()
                            if (!(heading.magneticDegrees >= 0.0 && heading.magneticDegrees < 360.0)) report("seed=$seed collector=$index emitted ${heading.magneticDegrees}")
                        }
                    }
                }
                // Every manager exists before the feeders start: the device announces the last open, no timer involved.
                allOpened.await()
                assertEquals(collectors, device.liveManagers.size, "seed=$seed control: every collector got its manager")

                val feederJobs: List<Job> = List(feeders) { worker: Int ->
                    launch {
                        val rng = Random(seed.toLong() * 1000 + worker)
                        repeat(stepsPerFeeder) { step: Int ->
                            val managers: List<FakeHeadingDevice.FakeManager> = device.liveManagers
                            if (managers.isNotEmpty()) {
                                val target: FakeHeadingDevice.FakeManager = managers[rng.nextInt(managers.size)]
                                val kind: Int = rng.nextInt(10)
                                // Like CoreLocation, deliver on the main thread (the draws are made here: a Random is not shared).
                                val magnetic: Double = rng0(rng, step)
                                mainScope.launch {
                                    when {
                                        kind < 7 -> target.events.onHeading(magnetic, if (kind < 3) -1.0 else 5.0, if (kind == 6) -1.0 else 4.0)
                                        kind < 9 -> {
                                            target.authorized = kind == 7
                                            target.events.onAuthorizationChanged()
                                        }
                                        else -> target.events.onCalibrationRequested()
                                    }
                                }
                            }
                            if (worker == 0 && step % 150 == 149) {
                                val victim: Int = rng.nextInt(collectors)
                                jobs[victim].cancelAndJoin()
                                atJoin[victim].store(emissions[victim].load())
                            }
                        }
                    }
                }
                feederJobs.joinAll()
                jobs.forEach { it.cancelAndJoin() }
            }
            withContext(main) { } // flush the simulated main queue: every queued teardown has run

            assertTrue(problems.load().isEmpty(), "seed=$seed ${problems.load().take(5)}")
            assertTrue(emissions.sumOf { it.load() } > 0, "seed=$seed control: collectors were served")
            atJoin.forEachIndexed { index: Int, count: AtomicInt ->
                if (count.load() >= 0) assertEquals(count.load(), emissions[index].load(), "seed=$seed collector $index emitted after cancelAndJoin returned")
            }
            assertEquals(collectors, device.openCalls.load(), "seed=$seed")
            assertEquals(0, device.liveManagers.size, "seed=$seed no manager left")
            assertTrue(device.allManagers.all { it.releases.load() == 1 }, "seed=$seed every manager released exactly once")
            assertTrue(device.allManagers.none { it.locationRunning || it.headingRunning }, "seed=$seed no update left running")
            assertTrue(device.problemList.isEmpty(), "seed=$seed ${device.problemList.take(5)}")
        } finally {
            main.close()
        }
    }

    /** A magnetic heading for the storm: mostly ordinary, now and then on the seam. */
    private fun rng0(rng: Random, step: Int): Double = when (step % 11) {
        0 -> 359.9999
        1 -> 0.0
        else -> rng.nextDouble(0.0, 360.0)
    }
}
