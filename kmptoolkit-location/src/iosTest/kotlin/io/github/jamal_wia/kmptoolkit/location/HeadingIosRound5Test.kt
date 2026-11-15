@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Group loc-heading, round 5: a collector that ends inside a callback leaves no call on the released manager
 * (finding m1 of breaker round 4, and the audit of every site with the same shape: a publish through
 * `HeadingInputs.update` followed by native work on the same manager).
 *
 * The model is the production one: the confine is the main queue ([MainQueue]: inline on the main thread, queued
 * from anywhere else), CoreLocation delivers on it, and the collector resumes in place (an unconfined dispatcher,
 * the `Dispatchers.Main.immediate` rule), so a publish inside a callback can run the collector to its end, and
 * its release, before the callback continues. Every site is tried with the three ways a collection ends on a
 * reading: a cancel from the collector, `take` and `first`. The oracle is the fake's problem list (a call on a
 * released manager is a problem) plus exact call counters, with controls that the reading reached the collector
 * inside the callback and that the release ran inside it.
 *
 * The sites: the grant and the revocation (`onAuthorizationChanged`), a manager that delivers inside
 * `startHeading` (`open`) or inside `startLocation`, a heading, a calibration request. Single-threaded, seeded
 * where random, no sleeps, no wall clock.
 */
class HeadingIosRound5Test {

    /** Replaced at the start of every scenario, so a loop over variants never sees the managers of the previous one. */
    private var device = FakeHeadingDevice()
    private val logger = RecordingLogger()

    /** The main queue: blocks run inline while [onMain], and are queued otherwise until [drain]. */
    private class MainQueue {
        private val queue: ArrayDeque<() -> Unit> = ArrayDeque()

        var onMain: Boolean = false
            private set

        val pending: Int get() = queue.size

        val confine: (() -> Unit) -> Unit = { block: () -> Unit -> if (onMain) block() else queue.addLast(block) }

        /** Runs [block] as CoreLocation would call it: on the main thread. */
        fun run(block: () -> Unit) {
            val was: Boolean = onMain
            onMain = true
            try {
                block()
            } finally {
                onMain = was
            }
        }

        /** Runs every queued block, including ones queued meanwhile, on the main thread. */
        fun drain() = run { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
    }

    private enum class End { CANCEL, TAKE, FIRST }

    private fun provider(main: MainQueue, showsSystemCalibration: Boolean = false): HeadingProvider =
        IosHeadingProvider(device, showsSystemCalibration, logger, main.confine)

    /** Collects [flow] and ends on the [n]-th heading, the way [how] says. */
    private fun TestScope.collectUntil(flow: Flow<Heading>, n: Int, how: End, received: MutableList<Heading>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            when (how) {
                End.CANCEL -> flow.collect { value: Heading ->
                    received += value
                    if (received.size == n) cancel()
                }
                End.TAKE -> flow.take(n).collect { value: Heading -> received += value }
                End.FIRST -> flow.first { value: Heading ->
                    received += value
                    received.size == n
                }
            }
        }

    /** The manager of the only collection, after its opening was drained on the main queue. */
    private fun MainQueue.openedManager(): FakeHeadingDevice.FakeManager {
        drain()
        return device.allManagers.single()
    }

    private fun assertNothingAfterRelease(manager: FakeHeadingDevice.FakeManager, context: String) {
        assertEquals(emptyList(), device.problemList, "no call on a released manager ($context)")
        assertEquals(1, manager.releases.load(), "released exactly once ($context)")
        assertTrue(manager.isReleased, "the delegate was set to nil ($context)")
        assertFalse(manager.locationRunning, "nothing runs on a released manager ($context)")
        assertFalse(manager.headingRunning, "nothing runs on a released manager ($context)")
    }

    // region the grant and the revocation

    @Test
    fun `a collector that ends on the heading a grant publishes leaves no call on its released manager`() = runTest {
        for (how: End in End.entries) {
            val main = MainQueue()
            device = FakeHeadingDevice()
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collectUntil(provider(main).observeHeading(minChangeDegrees = 0.0), n = 2, how, received)
            val manager: FakeHeadingDevice.FakeManager = main.openedManager()
            main.run { manager.events.onHeading(10.0, -1.0, 5.0) }
            assertEquals(1, received.size, "control: the first heading is served ($how)")

            main.run {
                manager.authorized = true
                manager.events.onAuthorizationChanged()
            }

            assertEquals(2, received.size, "control: the grant's own heading reached the collector inside the callback ($how)")
            assertTrue(collection.isCompleted, "control: the collector ended inside the callback ($how)")
            assertEquals(0, manager.locationStarts.load(), "a released manager is not told to start location updates ($how)")
            assertNothingAfterRelease(manager, "grant, $how")
            collection.cancelAndJoin()
            device.allManagers.forEach { it.events.onAuthorizationChanged() }
            assertEquals(2, received.size, "a late callback of the released manager reaches nobody ($how)")
        }
    }

    @Test
    fun `a collector that ends on the heading a revocation publishes leaves no call on its released manager`() = runTest {
        for (how: End in End.entries) {
            val main = MainQueue()
            device = FakeHeadingDevice()
            device.authorizedAtOpen = true
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collectUntil(provider(main).observeHeading(minChangeDegrees = 0.0), n = 2, how, received)
            val manager: FakeHeadingDevice.FakeManager = main.openedManager()
            main.run { manager.events.onHeading(10.0, 14.0, 5.0) }
            assertEquals(1, manager.locationStarts.load(), "control: authorized at open, location runs ($how)")

            main.run {
                manager.authorized = false
                manager.events.onAuthorizationChanged()
            }

            assertEquals(listOf(14.0, null), received.map { it.trueDegrees }, "control: true north disappearing reached the collector inside the callback ($how)")
            assertTrue(collection.isCompleted, "control: the collector ended inside the callback ($how)")
            assertEquals(0, manager.locationStops.load(), "a released manager is not told to stop location updates ($how)")
            assertNothingAfterRelease(manager, "revocation, $how")
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `a collection that ended inside the grant callback is followed by a fresh one that works`() = runTest {
        val main = MainQueue()
        device = FakeHeadingDevice()
        val received: MutableList<Heading> = mutableListOf()
        collectUntil(provider(main).observeHeading(minChangeDegrees = 0.0), n = 2, End.CANCEL, received)
        val first: FakeHeadingDevice.FakeManager = main.openedManager()
        main.run { first.events.onHeading(10.0, -1.0, 5.0) }
        main.run {
            first.authorized = true
            first.events.onAuthorizationChanged()
        }
        assertNothingAfterRelease(first, "the first collection")

        val again: MutableList<Heading> = mutableListOf()
        val second: Job = collectUntil(provider(main).observeHeading(minChangeDegrees = 0.0), n = 5, End.CANCEL, again)
        main.drain()
        val next: FakeHeadingDevice.FakeManager = device.liveManagers.single()
        assertTrue(next !== first, "a fresh manager for the new collection")
        assertEquals(0, next.locationStarts.load(), "the new manager starts unauthorized, so no location updates")
        main.run { next.events.onHeading(20.0, 14.0, 5.0) }
        assertEquals(1, again.size, "the new collection is served")
        assertEquals(1, first.releases.load(), "the released manager is not touched again")
        assertEquals(emptyList(), device.problemList)
        second.cancelAndJoin()
        main.drain()
        assertEquals(1, next.releases.load())
        assertTrue(device.liveManagers.isEmpty())
    }

    // endregion

    // region a manager that delivers inside its own call

    @Test
    fun `a collector that ends on a heading delivered inside startHeading gets no further call on its released manager`() = runTest {
        for (how: End in End.entries) {
            val main = MainQueue()
            device = FakeHeadingDevice()
            device.authorizedAtOpen = true
            device.afterStartHeading = { manager: FakeHeadingDevice.FakeManager -> manager.events.onHeading(10.0, 14.0, 5.0) }
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collectUntil(provider(main).observeHeading(minChangeDegrees = 0.0), n = 1, how, received)
            assertEquals(1, main.pending, "control: the opening is queued, the collector is waiting for a heading ($how)")
            assertEquals(0, device.openCalls.load(), "control: nothing opened yet ($how)")

            main.drain()

            val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
            assertEquals(1, received.size, "control: the heading reached the collector inside startHeading ($how)")
            assertTrue(collection.isCompleted, "control: the collector ended inside startHeading ($how)")
            assertEquals(1, manager.headingStarts.load(), "control: startHeading ran ($how)")
            assertEquals(0, manager.locationStarts.load(), "no location updates on a manager whose collection already ended ($how)")
            assertNothingAfterRelease(manager, "startHeading, $how")
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `a collector that ends inside startHeading with a queued release leaves that release the only call afterwards`() = runTest {
        // The close arrives from inside the callback but its release is queued (the confine was not inline): the
        // session is closed and still holds the manager until the queue runs.
        val queued = DeferredConfine()
        device.authorizedAtOpen = true
        device.afterStartHeading = { manager: FakeHeadingDevice.FakeManager -> manager.events.onHeading(10.0, 14.0, 5.0) }
        val received: MutableList<Heading> = mutableListOf()
        val collection: Job = collectUntil(
            IosHeadingProvider(device, false, logger, queued.confine).observeHeading(minChangeDegrees = 0.0),
            n = 1,
            End.CANCEL,
            received,
        )

        queued.drain()

        val manager: FakeHeadingDevice.FakeManager = device.allManagers.single()
        assertEquals(1, received.size, "control: the heading reached the collector inside startHeading")
        assertTrue(collection.isCompleted, "control: the collector ended inside startHeading")
        assertEquals(0, manager.locationStarts.load(), "a closed collection starts no location updates while its release waits in the queue")
        assertNothingAfterRelease(manager, "startHeading, queued release")
    }

    @Test
    fun `a collector that ends on a heading delivered inside startLocation gets no further call on its released manager`() = runTest {
        for (how: End in End.entries) {
            val main = MainQueue()
            device = FakeHeadingDevice()
            val received: MutableList<Heading> = mutableListOf()
            val collection: Job = collectUntil(provider(main).observeHeading(minChangeDegrees = 0.0), n = 3, how, received)
            val manager: FakeHeadingDevice.FakeManager = main.openedManager()
            main.run { manager.events.onHeading(10.0, -1.0, 5.0) }
            device.afterStartLocation = { started: FakeHeadingDevice.FakeManager -> started.events.onHeading(11.0, 14.0, 5.0) }

            main.run {
                manager.authorized = true
                manager.events.onAuthorizationChanged()
            }

            assertEquals(3, received.size, "control: heading, the grant's own heading and the one inside startLocation ($how)")
            assertTrue(collection.isCompleted, "control: the collector ended inside startLocation ($how)")
            assertEquals(1, manager.locationStarts.load(), "control: location was started once, before the collection ended ($how)")
            assertEquals(0, manager.locationStops.load(), "nothing stops a manager that was released ($how)")
            assertNothingAfterRelease(manager, "startLocation, $how")
            collection.cancelAndJoin()
        }
    }

    // endregion

    // region a heading and a calibration request

    @Test
    fun `a collector that ends on a heading leaves no call on its released manager and a late heading reaches nobody`() = runTest {
        for (how: End in End.entries) {
            for (authorized: Boolean in listOf(false, true)) {
                val main = MainQueue()
                device = FakeHeadingDevice()
                device.authorizedAtOpen = authorized
                val received: MutableList<Heading> = mutableListOf()
                val collection: Job = collectUntil(provider(main).observeHeading(minChangeDegrees = 0.0), n = 1, how, received)
                val manager: FakeHeadingDevice.FakeManager = main.openedManager()

                main.run { manager.events.onHeading(10.0, 14.0, 5.0) }

                assertEquals(1, received.size, "control: the heading reached the collector inside the callback ($how, authorized=$authorized)")
                assertTrue(collection.isCompleted, "control: the collector ended inside the callback ($how, authorized=$authorized)")
                assertEquals(0, manager.locationStops.load(), "release stops location itself, no separate call ($how, authorized=$authorized)")
                assertNothingAfterRelease(manager, "heading, $how, authorized=$authorized")
                main.run { manager.events.onHeading(20.0, 14.0, 5.0) }
                assertEquals(1, received.size, "a late heading reaches nobody ($how, authorized=$authorized)")
                collection.cancelAndJoin()
            }
        }
    }

    @Test
    fun `a collector that ends on a calibration request leaves no call on its released manager and CoreLocation is told no overlay`() = runTest {
        for (how: End in End.entries) {
            for (flag: Boolean in listOf(false, true)) {
                val main = MainQueue()
                device = FakeHeadingDevice()
                val received: MutableList<Heading> = mutableListOf()
                val collection: Job = collectUntil(provider(main, showsSystemCalibration = flag).observeHeading(minChangeDegrees = 90.0), n = 2, how, received)
                val manager: FakeHeadingDevice.FakeManager = main.openedManager()
                main.run { manager.events.onHeading(100.0, -1.0, 5.0) }
                var answer: Boolean? = null

                main.run { answer = manager.events.onCalibrationRequested() }

                assertEquals(listOf(false, true), received.map { it.needsCalibration }, "control: the request reached the collector inside the callback ($how, flag=$flag)")
                assertTrue(collection.isCompleted, "control: the collector ended inside the callback ($how, flag=$flag)")
                // The pin of round 5 (the answer is the flag) was a defect, ruled so in breaker round 5 (m2): CoreLocation
                // keeps an overlay up until its manager dismisses it, and a released manager is dismissed by nobody.
                assertEquals(false, answer, "CoreLocation is not allowed to show its overlay for a collection that ended inside the callback ($how, flag=$flag)")
                assertNothingAfterRelease(manager, "calibration, $how, flag=$flag")
                collection.cancelAndJoin()
            }
        }
    }

    // endregion

    // region a storm

    @Test
    fun `a seeded storm of collectors that end on any reading never calls a released manager`() = runTest {
        var endedByCollector = 0
        var deliveredInsideOwnCall = 0
        var stormsWithReadings = 0
        for (seed: Int in 1..40) {
            val rng = Random(HEADING_TEST_SEED + 55 + seed)
            val main = MainQueue()
            val storm = FakeHeadingDevice()
            storm.authorizedAtOpen = rng.nextBoolean()
            val stormProvider: HeadingProvider = IosHeadingProvider(storm, false, logger, main.confine)
            val jobs: MutableList<Job> = mutableListOf()
            val received: MutableList<MutableList<Heading>> = mutableListOf()
            var inHook = false

            fun randomHeading(manager: FakeHeadingDevice.FakeManager) {
                val magnetic: Double = rng.nextInt(0, 360).toDouble()
                val trueHeading: Double = if (rng.nextBoolean()) -1.0 else rng.nextInt(0, 360).toDouble()
                manager.events.onHeading(magnetic, trueHeading, if (rng.nextInt(5) == 0) -1.0 else 5.0)
            }
            storm.afterStartHeading = { manager: FakeHeadingDevice.FakeManager ->
                if (rng.nextInt(3) == 0) {
                    deliveredInsideOwnCall++
                    inHook = true
                    randomHeading(manager)
                    inHook = false
                }
            }
            storm.afterStartLocation = { manager: FakeHeadingDevice.FakeManager ->
                if (rng.nextInt(3) == 0) {
                    deliveredInsideOwnCall++
                    inHook = true
                    randomHeading(manager)
                    inHook = false
                }
            }

            fun startCollection() {
                val into: MutableList<Heading> = mutableListOf()
                received += into
                jobs += collectUntil(stormProvider.observeHeading(minChangeDegrees = if (rng.nextBoolean()) 0.0 else 3.0), rng.nextInt(1, 7), End.entries[rng.nextInt(3)], into)
            }
            repeat(3) { startCollection() }

            repeat(60) { step: Int ->
                val before: Int = jobs.count { job: Job -> job.isCompleted }
                val managers: List<FakeHeadingDevice.FakeManager> = storm.allManagers
                when (rng.nextInt(10)) {
                    in 0..4 -> if (managers.isNotEmpty()) main.run { randomHeading(managers[rng.nextInt(managers.size)]) }
                    in 5..6 -> if (managers.isNotEmpty()) {
                        val target: FakeHeadingDevice.FakeManager = managers[rng.nextInt(managers.size)]
                        main.run {
                            target.authorized = rng.nextBoolean()
                            target.events.onAuthorizationChanged()
                        }
                    }
                    7 -> if (managers.isNotEmpty()) main.run { managers[rng.nextInt(managers.size)].events.onCalibrationRequested() }
                    8 -> main.drain()
                    else -> if (jobs.size < 6) startCollection()
                }
                if (inHook) error("seed=$seed step=$step: a hook is still open")
                endedByCollector += jobs.count { job: Job -> job.isCompleted } - before
                for (manager: FakeHeadingDevice.FakeManager in storm.allManagers) {
                    assertTrue(manager.releases.load() <= 1, "seed=$seed step=$step: released at most once")
                    if (manager.isReleased) {
                        assertFalse(manager.locationRunning || manager.headingRunning, "seed=$seed step=$step: a released manager is running")
                    }
                }
                assertEquals(emptyList(), storm.problemList, "seed=$seed step=$step: a call reached a released manager")
            }

            jobs.forEach { job: Job -> job.cancel() }
            main.drain()
            assertTrue(storm.liveManagers.isEmpty(), "seed=$seed: every manager released")
            assertTrue(storm.allManagers.all { manager: FakeHeadingDevice.FakeManager -> manager.releases.load() == 1 }, "seed=$seed: every manager released exactly once")
            assertEquals(emptyList(), storm.problemList, "seed=$seed: no call on a released manager")
            if (received.any { it.isNotEmpty() }) stormsWithReadings++
        }
        assertTrue(endedByCollector >= 40, "control: many collectors ended on their own reading ($endedByCollector)")
        assertTrue(deliveredInsideOwnCall >= 20, "control: managers delivered inside their own calls ($deliveredInsideOwnCall)")
        assertTrue(stormsWithReadings >= 30, "control: the storms delivered readings ($stormsWithReadings)")
    }

    // endregion
}
