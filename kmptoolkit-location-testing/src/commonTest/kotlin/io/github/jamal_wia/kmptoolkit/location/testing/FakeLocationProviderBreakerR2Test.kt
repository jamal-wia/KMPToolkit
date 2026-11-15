package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.Heading
import io.github.jamal_wia.kmptoolkit.location.LocationReading
import io.github.jamal_wia.kmptoolkit.location.LocationUnavailability
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Breaker round 2 of group loc-jvm-fakes (BREAKER-REVIEW.md, D-23). It attacks the round-1 fix of M1:
 * the atomic snapshot cell (latest reading, sequence number and latest terminal reading in one
 * `update {}`) and the per-collection "last delivered sequence number".
 *
 * The contract under test is the class KDoc of [FakeLocationProvider] ("The stream") and the `emit`
 * KDoc: a new collector starts from the latest reading (a terminal latest one completes it again, an
 * emit after a terminal reading serves the next collection); a busy collector skips to the newest
 * reading; a terminal reading is never conflated away from a collection that was live when it was
 * emitted; with several terminal readings pending, the collection ends on the newest; nothing after
 * the terminal reading reaches that collection. Where the collector keeps up, the result must be
 * exactly loc-core's `completingAfterTerminalReading` (ReadingPolicy.kt, task/loc-core 6655fdad):
 * every reading up to and including the first terminal one.
 *
 * Storms use `Random(seed ...)`; every failure message starts with `seed=` or names the case. The
 * real-time bounds are hang bounds only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeLocationProviderBreakerR2Test {

    // ---- exhaustive model check (wrong terminal state, stale replay, lost terminal) ---------------

    /**
     * Every script of up to 5 emits over {Available, NO_FIX, PERMISSION_MISSING, UNSUPPORTED}, three
     * stream seeds, every start position of the collector and every set of points at which the
     * collector gets to run (StandardTestDispatcher: it runs only at `runCurrent`). The expected
     * output comes from the KDoc rules alone (model below). When the collector runs after every emit,
     * the model must equal loc-core's `completingAfterTerminalReading` over seed + script (cross-check
     * of the model against the real providers' rule).
     *
     * Red if: a terminal reading is dropped by a later emit (round-1 M1), the initial "delivered"
     * mark is the latest sequence instead of the one before it (a terminal latest reading would not
     * complete a new collector), a terminal older than the collection's start is delivered (an emit
     * after a terminal would not serve the next collection), the oldest instead of the newest pending
     * terminal is chosen, or anything is delivered after the terminal reading.
     */
    @Test
    fun `every short script with every start point and interleaving ends the collection as the KDoc rules say`() = runTest(timeout = 300.seconds) {
        val alphabet: List<Int> = listOf(KIND_AVAILABLE, KIND_NO_FIX, KIND_PERMISSION, KIND_UNSUPPORTED)
        val seeds: List<LocationReading> = listOf(noFix, permissionMissing, unsupported)
        var cases = 0
        for (length in 0..5) {
            forEachScript(alphabet, length) { kinds ->
                for (seed in seeds) {
                    val readings: List<LocationReading> = listOf(seed) + kinds.mapIndexed { index, kind -> reading(kind, index + 1) }
                    for (start in 0..length) {
                        val optional: Int = maxOf(0, length - start - 1) // points start+1 .. length-1; length is always a run point
                        for (mask in 0 until (1 shl optional)) {
                            val runPoints: Set<Int> = buildSet {
                                add(start)
                                add(length)
                                for (bit in 0 until optional) if (mask and (1 shl bit) != 0) add(start + 1 + bit)
                            }
                            val case = "readings=$readings start=$start runPoints=${runPoints.sorted()}"
                            val (expected: List<LocationReading>, ends: Boolean) = model(readings, start, runPoints)
                            if (start == 0 && runPoints.size == length + 1) {
                                assertEquals(completingAfterTerminalReading(readings), expected, "$case: model vs ReadingPolicy")
                            }

                            val provider = FakeLocationProvider(streamSeed = seed)
                            for (q in 1..start) provider.emit(readings[q])
                            val seen: MutableList<LocationReading> = mutableListOf()
                            val job: Job = backgroundScope.launch { provider.observeLocation().collect { seen += it } }
                            runCurrent()
                            for (q in start + 1..length) {
                                provider.emit(readings[q])
                                if (q in runPoints) runCurrent()
                            }
                            assertEquals(expected, seen, case)
                            assertEquals(ends, job.isCompleted, "$case: completed")
                            assertEquals(if (ends) 0 else 1, provider.activeObservers.value, "$case: observers")
                            job.cancelAndJoin()
                            assertEquals(0, provider.activeObservers.value, "$case: released")
                            cases++
                        }
                    }
                }
            }
        }
        assertTrue(cases > 100_000, "control: the enumeration ran ($cases cases)")
    }

    /**
     * Long random scripts with a collector that keeps up (Unconfined): the fake must deliver exactly
     * what loc-core's `completingAfterTerminalReading` delivers. Red if the fake and the real providers
     * disagree on which reasons are terminal, or on "up to and including".
     */
    @Test
    fun `a collector that keeps up gets exactly what the real providers terminal rule gives`() = runTest(UnconfinedTestDispatcher()) {
        val seed: Long = FAKE_TEST_SEED + 201
        val rng = Random(seed)
        repeat(400) { round ->
            val kinds: List<Int> = List(rng.nextInt(1, 60)) { if (rng.nextInt(10) == 0) rng.nextInt(2, 4) else rng.nextInt(0, 2) }
            val readings: List<LocationReading> = listOf(noFix) + kinds.mapIndexed { index, kind -> reading(kind, index + 1) }
            val provider = FakeLocationProvider()
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { provider.observeLocation().collect { seen += it } }
            readings.drop(1).forEach { provider.emit(it) }
            val expected: List<LocationReading> = completingAfterTerminalReading(readings)
            assertEquals(expected, seen, "seed=$seed round=$round")
            assertEquals(expected.last().isTerminal(), job.isCompleted, "seed=$seed round=$round")
            job.cancelAndJoin()
            assertEquals(0, provider.activeObservers.value, "seed=$seed round=$round")
        }
    }

    // ---- several collectors with different progress ---------------------------------------------

    /**
     * Four collections over one fake, each at a different point: one keeps up, one is busy across the
     * terminal reading and the next emit, one starts between the terminal reading and the next emit
     * but runs only after both, and one starts after both. Each must follow its own rule.
     */
    @Test
    fun `collectors with different progress each end by their own rule`() = runTest {
        val provider = FakeLocationProvider()
        val fast: MutableList<LocationReading> = mutableListOf()
        val busy: MutableList<LocationReading> = mutableListOf()
        val gate = CompletableDeferred<Unit>()
        val fastJob: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { provider.observeLocation().collect { fast += it } }
        val busyJob: Job = backgroundScope.launch {
            provider.observeLocation().collect {
                busy += it
                if (busy.size == 2) gate.await()
            }
        }
        runCurrent()
        provider.emit(available(1.0, 1.0, timestamp = 1))
        runCurrent()
        assertEquals(listOf(noFix, available(1.0, 1.0, timestamp = 1)), busy, "control: busy with reading 1")

        provider.emit(permissionMissing)
        // Launched now, but its flow body runs only at the next runCurrent, after the next emit: it is a
        // collection that starts after that emit.
        val between: Deferred<List<LocationReading>> = backgroundScope.async { provider.observeLocation().take(1).toList() }
        provider.emit(available(2.0, 2.0, timestamp = 2))
        provider.emit(available(3.0, 3.0, timestamp = 3))
        gate.complete(Unit)
        runCurrent()
        val late: List<LocationReading> = provider.observeLocation().take(1).toList()

        assertEquals(listOf(noFix, available(1.0, 1.0, timestamp = 1), permissionMissing), fast, "the collector that kept up")
        assertTrue(fastJob.isCompleted)
        assertEquals(listOf(noFix, available(1.0, 1.0, timestamp = 1), permissionMissing), busy, "the busy collector ends on the skipped terminal")
        assertTrue(busyJob.isCompleted)
        assertEquals(listOf(available(3.0, 3.0, timestamp = 3)), between.await(), "a collection that starts after the emits starts from the latest")
        assertEquals(listOf(available(3.0, 3.0, timestamp = 3)), late)
        assertEquals(0, provider.activeObservers.value)
    }

    // ---- counters around terminal completion ----------------------------------------------------

    /**
     * Terminal reading, then a non-terminal one, then cancel: the collection that ended on the terminal
     * is released exactly once (cancelling a completed job does not count it again), the collection
     * started after it stays live and is counted, and `first {}` / `take(1)` on a terminal latest
     * reading release too. Red if the release in `finally` is not idempotent or a terminal completion
     * leaks its registration.
     */
    @Test
    fun `terminal then non terminal then cancel leaves every counter exact and never negative`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        val counts: MutableList<Int> = mutableListOf()
        val watcher: Job = backgroundScope.launch { provider.activeObservers.collect { counts += it } }
        val early: Job = backgroundScope.launch { provider.observeLocation().collect { } }
        assertEquals(1, provider.activeObservers.value, "control: live")
        provider.emit(unsupported)
        assertTrue(early.isCompleted)
        assertEquals(listOf(unsupported), provider.observeLocation().take(1).toList(), "terminal latest: take(1)")
        assertEquals(unsupported, provider.observeLocation().first { it.isTerminal() }, "terminal latest: first {}")
        provider.emit(available(4.0, 4.0, timestamp = 4))
        val after: Job = backgroundScope.launch { provider.observeLocation().collect { } }
        assertEquals(1, provider.activeObservers.value, "the collection after the non-terminal reading is live")
        early.cancelAndJoin()
        assertEquals(1, provider.activeObservers.value, "cancelling the completed collection changes nothing")
        after.cancelAndJoin()
        assertEquals(0, provider.activeObservers.value)
        watcher.cancelAndJoin()
        assertTrue(counts.all { it >= 0 }, "counts=$counts never negative")
        assertEquals(0, counts.last(), "counts=$counts ends at zero")
    }

    // ---- hold / release with terminal completion ------------------------------------------------

    /**
     * The hold and the stream are independent: a terminal reading completes the collectors and leaves
     * held one-shot calls held; a collector that, while handling the terminal reading, makes a held
     * one-shot call stays registered until the release and is released right after it. Red if the
     * terminal completion resumed or cancelled held calls, or if the registration were dropped before
     * the collector's body finished.
     */
    @Test
    fun `a terminal reading leaves held calls held and a collector waiting on a held call stays registered until the release`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        provider.holdCurrentLocation()
        val held: List<Deferred<LocationReading>> = List(3) { backgroundScope.async { provider.getCurrentLocation() } }
        val inBody: Deferred<LocationReading> = CompletableDeferred<LocationReading>().also { result ->
            backgroundScope.launch {
                provider.observeLocation().collect { reading -> if (reading.isTerminal()) result.complete(provider.getCurrentLocation()) }
            }
        }
        val plain: Job = backgroundScope.launch { provider.observeLocation().collect { } }
        assertEquals(3, provider.pendingCurrentLocationCalls.value, "control")
        assertEquals(2, provider.activeObservers.value, "control")

        provider.emit(permissionMissing)
        provider.emit(available(1.0, 1.0, timestamp = 1))

        assertTrue(plain.isCompleted, "the plain collector ended on the terminal reading")
        assertEquals(1, provider.activeObservers.value, "the collector still in its body (on a held call) is registered")
        assertEquals(4, provider.pendingCurrentLocationCalls.value, "held calls stay held, plus the one from the collector")
        assertTrue(held.none { it.isCompleted })

        val release: LocationReading = available(9.0, 9.0, timestamp = 9)
        provider.releaseCurrentLocation(release)
        held.forEach { assertEquals(release, it.await()) }
        assertEquals(release, inBody.await())
        assertEquals(0, provider.pendingCurrentLocationCalls.value)
        assertEquals(0, provider.activeObservers.value, "released once its body finished")
        provider.currentLocationAnswer = noFix
        assertEquals(noFix, provider.getCurrentLocation(), "the hold ended")
    }

    // ---- huge emit counts ----------------------------------------------------------------------

    /**
     * A collector busy on the seed while a terminal reading and then 300 000 more readings are emitted
     * must still end on the terminal reading (no history or counter window that a long run can push
     * past), and a collector busy while 300 000 non-terminal readings arrive must end on the newest.
     */
    @Test
    fun `a terminal reading survives a huge run of later emits and a long non terminal run conflates to the newest`() = runTest {
        val count = 300_000
        val provider = FakeLocationProvider()
        val gate = CompletableDeferred<Unit>()
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation().collect {
                seen += it
                if (seen.size == 1) gate.await()
            }
        }
        runCurrent()
        provider.emit(permissionMissing)
        for (i in 1..count) provider.emit(available(0.0, 0.0, timestamp = i.toLong()))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(noFix, permissionMissing), seen)
        assertTrue(job.isCompleted)

        val open = FakeLocationProvider()
        val gate2 = CompletableDeferred<Unit>()
        val seen2: MutableList<LocationReading> = mutableListOf()
        val job2: Job = backgroundScope.launch {
            open.observeLocation().collect {
                seen2 += it
                if (seen2.size == 1) gate2.await()
            }
        }
        runCurrent()
        for (i in 1..count) open.emit(if (i % 2 == 0) serviceDisabled else available(0.0, 0.0, timestamp = i.toLong()))
        open.emit(available(0.0, 0.0, timestamp = -1))
        gate2.complete(Unit)
        runCurrent()
        assertEquals(listOf(noFix, available(0.0, 0.0, timestamp = -1)), seen2)
        assertFalse(job2.isCompleted)
        job2.cancelAndJoin()
        assertEquals(0, open.activeObservers.value)
    }

    // ---- real threads: emitters racing collectors ------------------------------------------------

    /**
     * Four emitter threads (two of which emit one terminal reading at a seeded point) race eight
     * collectors on Dispatchers.Default that are live (seed taken) before the first emit and progress
     * at different seeded speeds. Per emitter, a collection must see that emitter's readings in program
     * order (the cell is linearizable); because every collector was live before any terminal reading,
     * every collection must end on a terminal reading; and no collection may deliver a reading that its
     * emitter emitted after its own terminal reading (it follows a terminal reading in the cell).
     * Also late collectors started on real threads after the storm, with a non-terminal reading last,
     * start from exactly that reading.
     *
     * Red if two emits could interleave inside the cell (sequence, reading and terminal torn), if a
     * terminal reading could be missed by a collector racing the emits, or if a stale "delivered"
     * mark let readings after a terminal through.
     */
    @Test
    fun `emitters racing collectors with different progress keep order and end every collection on a terminal and leak nothing after it`() = runTest(timeout = 120.seconds) {
        val seed: Long = FAKE_TEST_SEED + 202
        val emitters = 4
        val perEmitter = 20_000
        val collectors = 8
        repeat(10) { round ->
            val provider = FakeLocationProvider()
            val terminalAt: List<Int?> = Random(seed + round).let { rng -> List(emitters) { e -> if (e < 2) rng.nextInt(perEmitter / 2, perEmitter) else null } }
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    val results: List<Deferred<List<LocationReading>>> = List(collectors) { c ->
                        val rng = Random(seed * 100 + round * 10 + c)
                        val every: Int = rng.nextInt(1, 50)
                        async(start = CoroutineStart.UNDISPATCHED) {
                            val out: MutableList<LocationReading> = mutableListOf()
                            provider.observeLocation().collect {
                                out += it
                                if (out.size % every == 0) yield()
                            }
                            out
                        }
                    }
                    assertEquals(collectors, provider.activeObservers.value, "seed=$seed round=$round control: every collector is live before the first emit")
                    val go = CompletableDeferred<Unit>()
                    val emitting: List<Job> = List(emitters) { e ->
                        launch {
                            go.await() // all emitters start together, for contention on the cell
                            for (i in 0 until perEmitter) {
                                if (terminalAt[e] == i) provider.emit(if (e == 0) permissionMissing else unsupported)
                                provider.emit(available(e.toDouble(), 0.0, timestamp = i.toLong()))
                            }
                        }
                    }
                    go.complete(Unit)
                    emitting.forEach { it.join() }
                    val outputs: List<List<LocationReading>> = results.awaitAll()
                    outputs.forEachIndexed { c, out ->
                        val where = "seed=$seed round=$round collector=$c size=${out.size}"
                        assertEquals(noFix, out.first(), "$where: starts from the seed")
                        assertTrue(out.last().isTerminal(), "$where: ends on a terminal reading, last=${out.last()}")
                        assertTrue(out.dropLast(1).none { it.isTerminal() }, "$where: a terminal reading only at the end")
                        val lastIndex: MutableMap<Int, Long> = mutableMapOf()
                        for (r in out.drop(1).dropLast(1)) {
                            val fix = (r as LocationReading.Available).fix
                            val e: Int = fix.coordinates.latitude.toInt()
                            val i: Long = fix.timestampEpochMillis
                            assertTrue((lastIndex[e] ?: -1L) < i, "$where: emitter $e out of order at $i after ${lastIndex[e]}")
                            lastIndex[e] = i
                            val t: Int? = terminalAt[e]
                            assertTrue(t == null || i < t, "$where: emitter $e reading $i was emitted after its own terminal reading at $t")
                        }
                    }
                }
            }
            assertEquals(0, provider.activeObservers.value, "seed=$seed round=$round every collection released")

            val last: LocationReading = available(45.0, 45.0, timestamp = 77)
            provider.emit(last)
            val late: List<LocationReading> = withContext(Dispatchers.Default) {
                withTimeout(30.seconds) { List(collectors) { async { provider.observeLocation().first() } }.awaitAll() }
            }
            assertTrue(late.all { it == last }, "seed=$seed round=$round late=$late")
            assertEquals(0, provider.activeObservers.value, "seed=$seed round=$round")
        }
    }

    // ---- heading fake ---------------------------------------------------------------------------

    /**
     * Four emitter threads race four heading collectors that are live before the first emit and
     * progress at different seeded speeds: each collection sees every emitter's headings in program
     * order and ends on the very last heading; a late collector gets that heading first. Red if the
     * shared source could reorder or tear concurrent emits, or a busy collector could stop short of
     * the newest heading.
     */
    @Test
    fun `heading collectors see each emitter in program order`() = runTest(timeout = 120.seconds) {
        val seed: Long = FAKE_TEST_SEED + 204
        val provider = FakeHeadingProvider()
        val final: Heading = heading(-1.0, trueNorth = -1.0)
        val outputs: List<List<Heading>> = withContext(Dispatchers.Default) {
            withTimeout(100.seconds) {
                val results: List<Deferred<List<Heading>>> = List(4) { c ->
                    val every: Int = Random(seed + c).nextInt(1, 50)
                    async(start = CoroutineStart.UNDISPATCHED) {
                        val out: MutableList<Heading> = mutableListOf()
                        provider.observeHeading().first { h ->
                            out += h
                            if (out.size % every == 0) yield()
                            h == final
                        }
                        out
                    }
                }
                List(4) { e -> launch { for (i in 0 until 5_000) provider.emit(heading(i.toDouble(), trueNorth = e.toDouble())) } }.forEach { it.join() }
                provider.emit(final)
                results.awaitAll()
            }
        }
        outputs.forEachIndexed { c, out ->
            assertEquals(final, out.last(), "seed=$seed collector=$c ends on the newest heading")
            val lastIndex: MutableMap<Double, Double> = mutableMapOf()
            for (h in out.dropLast(1)) {
                val e: Double = h.trueDegrees ?: error("seed=$seed collector=$c no emitter tag")
                assertTrue((lastIndex[e] ?: -1.0) < h.magneticDegrees, "seed=$seed collector=$c emitter $e out of order at ${h.magneticDegrees} after ${lastIndex[e]}")
                lastIndex[e] = h.magneticDegrees
            }
        }
        assertEquals(0, provider.activeObservers.value, "seed=$seed")
        assertEquals(final, provider.observeHeading().first(), "seed=$seed a late collector gets the newest heading")
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * The KDoc rules as a model: the collector first runs at [start] (delivered mark = the reading
     * before the latest); at each run point it reads only the latest reading (conflation); if a
     * terminal reading newer than what it delivered exists at or before that point, it ends on the
     * newest such one; otherwise it delivers the latest reading.
     */
    private fun model(readings: List<LocationReading>, start: Int, runPoints: Set<Int>): Pair<List<LocationReading>, Boolean> {
        val out: MutableList<LocationReading> = mutableListOf()
        var delivered: Int = start - 1
        var lastSeen: Int = -1
        for (q in runPoints.sorted()) {
            if (q == lastSeen) continue
            lastSeen = q
            val newestTerminal: Int = (q downTo 0).firstOrNull { readings[it].isTerminal() } ?: -1
            if (newestTerminal > delivered) {
                out += readings[newestTerminal]
                return out to true
            }
            out += readings[q]
            delivered = q
        }
        return out to false
    }

    /** loc-core ReadingPolicy.completingAfterTerminalReading (task/loc-core 6655fdad), applied to a list. */
    private fun completingAfterTerminalReading(readings: List<LocationReading>): List<LocationReading> {
        val firstTerminal: Int = readings.indexOfFirst { it.isTerminal() }
        return if (firstTerminal < 0) readings else readings.subList(0, firstTerminal + 1)
    }

    private fun LocationReading.isTerminal(): Boolean =
        this is LocationReading.Unavailable &&
            (reason == LocationUnavailability.PERMISSION_MISSING || reason == LocationUnavailability.UNSUPPORTED)

    private fun reading(kind: Int, index: Int): LocationReading = when (kind) {
        KIND_AVAILABLE -> available(0.0, 0.0, timestamp = index.toLong())
        KIND_NO_FIX -> noFix
        KIND_PERMISSION -> permissionMissing
        else -> unsupported
    }

    private inline fun forEachScript(alphabet: List<Int>, length: Int, block: (List<Int>) -> Unit) {
        var total = 1
        repeat(length) { total *= alphabet.size }
        for (code in 0 until total) {
            var rest: Int = code
            val script: List<Int> = List(length) { alphabet[rest % alphabet.size].also { rest /= alphabet.size } }
            block(script)
        }
    }

    private companion object {
        const val KIND_AVAILABLE = 0
        const val KIND_NO_FIX = 1
        const val KIND_PERMISSION = 2
        const val KIND_UNSUPPORTED = 3
    }
}
