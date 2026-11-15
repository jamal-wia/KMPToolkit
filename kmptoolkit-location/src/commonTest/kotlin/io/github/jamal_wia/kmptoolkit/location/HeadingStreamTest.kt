@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** What a platform callback writes: a sequence number (strictly increasing per write) and the sample. */
private data class Raw(
    val seq: Long = 0,
    val magnetic: Double? = null,
    val trueDegrees: Double? = null,
    val needsCalibration: Boolean = false,
)

private fun Raw.next(magnetic: Double?, trueDegrees: Double? = this.trueDegrees, needsCalibration: Boolean = this.needsCalibration): Raw =
    Raw(seq + 1, magnetic, trueDegrees, needsCalibration)

private fun composeRaw(raw: Raw): Heading? =
    raw.magnetic?.let { magnetic: Double -> Heading(magnetic, raw.trueDegrees, accuracyDegrees = null, needsCalibration = raw.needsCalibration) }

/**
 * The lifecycle of every heading collection ([headingStream], group loc-heading), which both platform
 * providers are built on: one registration per collection and none held between collections, released
 * exactly once on every way a collection can end; independent filters and registrations for concurrent
 * collectors; a callback that never blocks and never queues; a slow collector that always ends on the
 * newest state; and a seeded storm of collections started, fed and cancelled on real threads.
 *
 * The fake platform counts registrations and releases with atomics and keeps the live ones in a
 * compare-and-set list, so the counters themselves are safe to read while threads write them.
 */
class HeadingStreamTest {

    private class Platform {
        val registrations = AtomicInt(0)
        val releases = AtomicInt(0)
        private val live = AtomicReference<List<HeadingInputs<Raw>>>(emptyList())

        val liveCount: Int get() = live.load().size
        val liveInputs: List<HeadingInputs<Raw>> get() = live.load()

        fun register(inputs: HeadingInputs<Raw>): AutoCloseable {
            registrations.incrementAndFetch()
            while (true) {
                val before: List<HeadingInputs<Raw>> = live.load()
                if (live.compareAndSet(before, before + inputs)) break
            }
            return AutoCloseable {
                releases.incrementAndFetch()
                while (true) {
                    val before: List<HeadingInputs<Raw>> = live.load()
                    if (live.compareAndSet(before, before - inputs)) break
                }
            }
        }

        /** One sensor event delivered to every live collection, the way a platform fans out. */
        fun publish(magnetic: Double?, trueDegrees: Double? = null, needsCalibration: Boolean = false) {
            live.load().forEach { inputs: HeadingInputs<Raw> ->
                inputs.update { raw: Raw -> raw.next(magnetic, trueDegrees, needsCalibration) }
            }
        }

        fun stream(minChangeDegrees: Double = 0.0): Flow<Heading> = headingStream(
            minChangeDegrees = minChangeDegrees,
            initial = { Raw() },
            compose = ::composeRaw,
            register = ::register,
        )
    }

    // region lifecycle

    @Test
    fun `nothing is registered until the flow is collected and a collection holds exactly one registration`() = runTest {
        val platform = Platform()
        val stream: Flow<Heading> = platform.stream()
        assertEquals(0, platform.registrations.load(), "building the flow must not register anything (cold)")

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.collect { } }

        assertEquals(1, platform.registrations.load(), "control: the collection registered")
        assertEquals(0, platform.releases.load(), "control: nothing released while it runs")
        assertEquals(1, platform.liveCount)

        collection.cancelAndJoin()

        assertEquals(1, platform.releases.load(), "cancel releases the registration")
        assertEquals(0, platform.liveCount)
    }

    @Test
    fun `the first state is built before anything is registered`() = runTest {
        val order: MutableList<String> = mutableListOf()
        val stream: Flow<Heading> = headingStream(
            minChangeDegrees = 1.0,
            initial = { order += "initial"; Raw() },
            compose = ::composeRaw,
            register = { order += "register"; null },
        )

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.collect { } }
        collection.cancelAndJoin()

        assertEquals(listOf("initial", "register"), order)
    }

    @Test
    fun `the first reading is emitted at once and later ones go through the filter`() = runTest {
        val platform = Platform()
        val received: MutableList<Double> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            platform.stream(minChangeDegrees = 1.0).collect { received += it.magneticDegrees }
        }

        platform.publish(10.0)
        platform.publish(10.4)
        platform.publish(10.8)
        platform.publish(11.0)

        assertEquals(listOf(10.0, 11.0), received)
    }

    @Test
    fun `a calibration flip is emitted without movement`() = runTest {
        val platform = Platform()
        val received: MutableList<Heading> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            platform.stream(minChangeDegrees = 45.0).collect { received += it }
        }

        platform.publish(100.0, needsCalibration = false)
        platform.publish(100.0, needsCalibration = true)

        assertEquals(listOf(false, true), received.map { it.needsCalibration })
    }

    @Test
    fun `a state written before the first collection result is composed once the wake up arrives`() = runTest {
        // The callback may fire from inside register (a sensor that reports synchronously on
        // registration): the reading must not be lost.
        val received: MutableList<Double> = mutableListOf()
        val stream: Flow<Heading> = headingStream(
            minChangeDegrees = 1.0,
            initial = { Raw() },
            compose = ::composeRaw,
            register = { inputs: HeadingInputs<Raw> ->
                inputs.update { raw: Raw -> raw.next(42.0) }
                null
            },
        )

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.collect { received += it.magneticDegrees }
        }

        assertEquals(listOf(42.0), received)
        assertTrue(collection.isActive, "no registration handle does not end the stream; it stays open")
        collection.cancelAndJoin()
    }

    // endregion

    // region concurrent and repeated collections

    @Test
    fun `two collectors have their own registration filter and state and the last release leaves nothing`() = runTest {
        val platform = Platform()
        val tight: MutableList<Double> = mutableListOf()
        val loose: MutableList<Double> = mutableListOf()
        val tightJob: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            platform.stream(minChangeDegrees = 1.0).collect { tight += it.magneticDegrees }
        }
        val looseJob: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            platform.stream(minChangeDegrees = 5.0).collect { loose += it.magneticDegrees }
        }
        assertEquals(2, platform.liveCount, "control: one registration per collector, nothing shared")

        platform.publish(10.0)
        platform.publish(12.0)
        platform.publish(16.0)

        assertEquals(listOf(10.0, 12.0, 16.0), tight)
        assertEquals(listOf(10.0, 16.0), loose, "the loose collector measures from ITS last emitted heading (10.0)")

        tightJob.cancelAndJoin()
        assertEquals(1, platform.liveCount, "cancelling one collector leaves the other's registration")
        assertEquals(1, platform.releases.load())
        platform.publish(30.0)
        assertEquals(3, tight.size, "a cancelled collector receives nothing more")
        assertEquals(listOf(10.0, 16.0, 30.0), loose, "control: the other collector is still served")

        looseJob.cancelAndJoin()
        assertEquals(2, platform.releases.load())
        assertEquals(0, platform.liveCount)
    }

    @Test
    fun `a new collection after a released one starts from scratch`() = runTest {
        val platform = Platform()
        val stream: Flow<Heading> = platform.stream(minChangeDegrees = 90.0)

        repeat(3) { round: Int ->
            val received: MutableList<Double> = mutableListOf()
            val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                stream.collect { received += it.magneticDegrees }
            }
            platform.publish(10.0)
            platform.publish(11.0)
            assertEquals(listOf(10.0), received, "round $round: a fresh filter emits the first reading at once, a stale one would not")
            collection.cancelAndJoin()
            assertEquals(round + 1, platform.releases.load())
        }

        assertEquals(3, platform.registrations.load())
        assertEquals(0, platform.liveCount)
    }

    @Test
    fun `the same collection state is never shared between two collections of one flow`() = runTest {
        val platform = Platform()
        val stream: Flow<Heading> = platform.stream(minChangeDegrees = 90.0)
        val first: MutableList<Double> = mutableListOf()
        val second: MutableList<Double> = mutableListOf()
        val firstJob: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.collect { first += it.magneticDegrees } }
        platform.publish(10.0)

        // Starts later: it must still get its own first reading (it has seen no state yet), which a shared
        // state or filter would withhold.
        val secondJob: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.collect { second += it.magneticDegrees } }
        platform.publish(11.0)

        assertEquals(listOf(10.0), first)
        assertEquals(listOf(11.0), second, "the late collector's first reading is the first one it saw")
        firstJob.cancelAndJoin()
        secondJob.cancelAndJoin()
    }

    // endregion

    // region every way a collection can end releases once

    @Test
    fun `a registration that throws fails the collection and leaves the platform clean for the next one`() = runTest {
        val platform = Platform()
        val failing: Flow<Heading> = headingStream(
            minChangeDegrees = 1.0,
            initial = { Raw() },
            compose = ::composeRaw,
            register = { throw IllegalStateException("register refused") },
        )

        val failure: IllegalStateException = assertFailsWith { failing.collect { } }
        assertEquals("register refused", failure.message)
        assertEquals(0, platform.liveCount)

        // A later, healthy collection of the platform is unaffected (re-init after a failed start).
        val received: MutableList<Double> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            platform.stream().collect { received += it.magneticDegrees }
        }
        platform.publish(5.0)
        assertEquals(listOf(5.0), received)
        collection.cancelAndJoin()
        assertEquals(1, platform.releases.load())
    }

    @Test
    fun `a registration that returns nothing leaves the stream open and silent and releases nothing`() = runTest {
        val registerCalls = AtomicInt(0)
        val stream: Flow<Heading> = headingStream(
            minChangeDegrees = 1.0,
            initial = { Raw() },
            compose = ::composeRaw,
            register = { registerCalls.incrementAndFetch(); null },
        )
        val received: MutableList<Heading> = mutableListOf()

        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.collect { received += it } }

        assertEquals(1, registerCalls.load())
        assertTrue(collection.isActive, "no hardware: the stream stays open, it never completes")
        assertTrue(received.isEmpty())
        collection.cancelAndJoin()
    }

    @Test
    fun `a composer that throws fails the collection and still releases the registration once`() = runTest {
        val platform = Platform()
        val stream: Flow<Heading> = headingStream(
            minChangeDegrees = 1.0,
            initial = { Raw() },
            compose = { throw ArithmeticException("compose broke") },
            register = platform::register,
        )

        var failure: Throwable? = null
        val job: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                stream.collect { }
            } catch (expected: ArithmeticException) {
                failure = expected
            }
        }
        assertEquals(1, platform.registrations.load(), "control: registered")
        platform.publish(1.0)
        job.join()

        assertEquals("compose broke", failure?.message)
        assertEquals(1, platform.releases.load())
        assertEquals(0, platform.liveCount)
    }

    @Test
    fun `a collector that throws releases the registration once`() = runTest {
        val platform = Platform()
        var thrown: Throwable? = null
        val job: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                platform.stream().collect { throw IllegalStateException("collector broke") }
            } catch (expected: IllegalStateException) {
                thrown = expected
            }
        }
        assertEquals(1, platform.registrations.load(), "control: registered")
        platform.publish(1.0)
        job.join()

        assertEquals("collector broke", thrown?.message)
        assertEquals(1, platform.releases.load())
    }

    @Test
    fun `first() stops the collection and releases`() = runTest {
        val platform = Platform()
        val result: Deferred<Heading> = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            platform.stream().first()
        }
        assertEquals(1, platform.liveCount, "control: registered")

        platform.publish(77.0)

        assertEquals(77.0, result.await().magneticDegrees)
        assertEquals(1, platform.releases.load())
        assertEquals(0, platform.liveCount)
    }

    @Test
    fun `cancelling while the platform is registering releases what it returned`() = runTest {
        // Cancellation that lands during register() (here: from inside it) is only seen at the next
        // suspension; the handle that register() returned must still be released.
        val platform = Platform()
        lateinit var job: Job
        val stream: Flow<Heading> = headingStream(
            minChangeDegrees = 1.0,
            initial = { Raw() },
            compose = ::composeRaw,
            register = { inputs: HeadingInputs<Raw> ->
                val handle: AutoCloseable = platform.register(inputs)
                job.cancel()
                handle
            },
        )

        job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.LAZY) { stream.collect { } }
        job.start()
        job.join()

        assertEquals(1, platform.registrations.load(), "control: it did register")
        assertEquals(1, platform.releases.load(), "and released although it was cancelled before it ever received")
        assertEquals(0, platform.liveCount)
    }

    @Test
    fun `a callback that fires after the release is harmless and delivers nothing`() = runTest {
        val platform = Platform()
        val received: MutableList<Double> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            platform.stream().collect { received += it.magneticDegrees }
        }
        platform.publish(1.0)
        val staleInputs: HeadingInputs<Raw> = platform.liveInputs.single()
        collection.cancelAndJoin()
        assertEquals(listOf(1.0), received, "control: it received before cancel")

        // A platform may call the listener once more after unregistering (cancel racing delivery).
        staleInputs.update { raw: Raw -> raw.next(2.0) }
        staleInputs.update { raw: Raw -> raw.next(3.0) }

        assertEquals(listOf(1.0), received)
        assertEquals(1, platform.releases.load(), "and it released only once")
    }

    // endregion

    // region argument check at the call

    @Test
    fun `a bad filter is refused at the call, not when the flow is collected`() {
        val platform = Platform()
        assertFailsWith<IllegalArgumentException> { platform.stream(minChangeDegrees = -1.0) }
        assertFailsWith<IllegalArgumentException> { platform.stream(minChangeDegrees = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { platform.stream(minChangeDegrees = Double.POSITIVE_INFINITY) }
        platform.stream(minChangeDegrees = 0.0)
        assertEquals(0, platform.registrations.load(), "refusing must not register anything")
    }

    // endregion

    // region the callback never queues

    @Test
    fun `a slow collector ends on the newest state and does not queue the ones in between`() = runTest {
        val platform = Platform()
        val gate = CompletableDeferred<Unit>()
        val received: MutableList<Double> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            platform.stream(minChangeDegrees = 0.0).collect { heading: Heading ->
                received += heading.magneticDegrees
                if (received.size == 1) gate.await()
            }
        }
        platform.publish(1.0)
        assertEquals(listOf(1.0), received, "control: the collector is now parked inside its first delivery")

        repeat(5_000) { step: Int -> platform.publish((step % 300).toDouble() + 2.0) }
        platform.publish(359.0)
        gate.complete(Unit)

        assertEquals(359.0, received.last(), "the collector must end on the newest state")
        assertTrue(received.size <= 3, "5 001 states while it was busy must not be queued, got ${received.size}")
        collection.cancelAndJoin()
    }

    @Test
    fun `an unchanged state wakes nobody`() = runTest {
        val platform = Platform()
        val received: MutableList<Double> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            platform.stream(minChangeDegrees = 0.0).collect { received += it.magneticDegrees }
        }
        platform.publish(5.0)
        val inputs: HeadingInputs<Raw> = platform.liveInputs.single()

        inputs.update { raw: Raw -> raw }

        assertEquals(listOf(5.0), received, "returning the same instance means nothing changed (min 0 would emit again)")
        collection.cancelAndJoin()
    }

    // endregion

    // region concurrent storm

    @Test
    fun `a storm of collections started fed and cancelled on real threads releases every registration`() = runTest(timeout = 120.seconds) {
        val seed: Int = HEADING_TEST_SEED
        val collectors = 24
        val producers = 4
        val stepsPerProducer = 3_000
        val platform = Platform()
        val problems: AtomicReference<List<String>> = AtomicReference(emptyList())
        fun report(problem: String) {
            while (true) {
                val before: List<String> = problems.load()
                if (problems.compareAndSet(before, before + problem)) break
            }
        }

        val marker = 359.0
        withContext(Dispatchers.Default) {
            val started: List<CompletableDeferred<Unit>> = List(collectors) { CompletableDeferred() }
            val jobs: List<Job> = List(collectors) { index: Int ->
                launch {
                    var lastSeq: Long = -1
                    val stream: Flow<Heading> = headingStream(
                        minChangeDegrees = 0.0,
                        initial = { Raw() },
                        compose = { raw: Raw ->
                            // Runs on the collecting coroutine, one collection per stream: confined.
                            if (raw.seq < lastSeq) report("seed=$seed collector=$index went back from seq $lastSeq to ${raw.seq}")
                            lastSeq = raw.seq
                            composeRaw(raw)
                        },
                        register = { inputs: HeadingInputs<Raw> ->
                            val handle: AutoCloseable = platform.register(inputs)
                            started[index].complete(Unit)
                            handle
                        },
                    )
                    // Every third collector leaves on its own after a few headings; the others stay until the
                    // final marker or until the producers cancel them.
                    if (index % 3 == 0) stream.first { heading: Heading -> heading.magneticDegrees == marker || lastSeq >= 50 }
                    else stream.first { heading: Heading -> heading.magneticDegrees == marker }
                }
            }
            started.forEach { it.await() }

            val producerJobs: List<Job> = List(producers) { worker: Int ->
                launch {
                    val rng = Random(seed.toLong() * 1000 + worker)
                    repeat(stepsPerProducer) { step: Int ->
                        val inputs: List<HeadingInputs<Raw>> = platform.liveInputs
                        if (inputs.isNotEmpty()) {
                            val target: HeadingInputs<Raw> = inputs[rng.nextInt(inputs.size)]
                            val magnetic: Double = rng.nextInt(0, 1400) * 0.25 % 358.0
                            target.update { raw: Raw -> raw.next(magnetic) }
                        }
                        // Cancel a random survivor now and then, mid-delivery.
                        if (worker == 0 && step % 400 == 399) jobs[rng.nextInt(collectors)].cancel()
                    }
                }
            }
            producerJobs.joinAll()

            // The producers are done: every survivor must now end on the final state, never on a stale one.
            platform.liveInputs.forEach { inputs: HeadingInputs<Raw> -> inputs.update { raw: Raw -> raw.next(marker) } }
            jobs.joinAll()
        }

        assertTrue(problems.load().isEmpty(), "seed=$seed ${problems.load().take(5)}")
        assertEquals(collectors, platform.registrations.load(), "seed=$seed control: every collector registered")
        assertEquals(platform.registrations.load(), platform.releases.load(), "seed=$seed every registration released")
        assertEquals(0, platform.liveCount, "seed=$seed nothing left live")
    }

    // endregion
}
