package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Breaker round 6 of group loc-android (ticket 5.1.0, LOC-7 / LOC-10, protocol BREAKER-REVIEW.md, D-23, D-30).
 *
 * Attacks the round-6 code: every terminal PERMISSION_MISSING (start's refusal loop, start's closing evaluation, the
 * terminal of an evaluation) re-judges the refusals against the CURRENT grant, and a waiting stream (nothing
 * registered, every candidate refused) reads the service state once per evaluation and never reseeds. Derived from
 * the contract: PLAN D8 (one result type for both calls), D16 (a refusal only goes away with a new grant), D19 and
 * section 4.2 rows PERMISSION_MISSING / SERVICE_DISABLED, decision D-30; not from the implementation.
 *
 * Platform model: [TornBackend] keeps the service state itself, with the real master-switch semantics (with the
 * device switch off every provider reads disabled), so a change can be landed SILENTLY right after the k-th platform
 * read of an evaluation (a torn read: the platform changed between two binder calls) and announced afterwards by its
 * own callbacks and broadcast, as on the device. Grants are refusal sets: coarse-only on API 24-30 refuses gps and
 * passive (the framework fused provider does not exist below API 31, LocationBackend.hasProvider), coarse-only on
 * API 31+ and fine refuse nothing; a grant fires no broadcast. Virtual time; real threads only in the storm, joined
 * with bounded latches; `Random(seed)` with `seed=` in every message. No sleeps, no wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidLocationProviderBreakerR6Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val fused: String = "fused"
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val noFix: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX)
    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)
    private val explicitGps = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS))

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
        timeSource = TestTimeSource(),
    )

    private fun providersOf(fake: FakeLocationBackend): List<String> =
        fake.registered().map { registration: FakeLocationBackend.Registration -> registration.provider }.sorted()

    private enum class Grant(val refused: Set<String>) {
        FINE(emptySet()),
        COARSE_API_24_30(setOf("gps", "passive")),
        COARSE_API_31(emptySet()),
    }

    private data class World(val present: Set<String>, val grant: Grant, val on: Set<String>, val deviceOn: Boolean)

    /**
     * The service state lives here (the fake only does registrations, refusals and the grant): with the device switch
     * off every provider reads disabled. [arm] lands a change silently right after the k-th following platform read
     * (service or grant); [announce] then fires what the platform fires for it. [throwOnRead] makes chosen grant reads
     * throw (a dead package manager).
     */
    private class TornBackend(val fake: FakeLocationBackend, world: World) : LocationBackend by fake {
        private val on: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(world.on) }

        @Volatile var deviceOn: Boolean = world.deviceOn

        private val reads = AtomicInteger(0)

        @Volatile private var landAt: Int = Int.MAX_VALUE

        @Volatile private var pending: (() -> Unit)? = null

        @Volatile var throwOnRead: ((Int) -> Boolean)? = null

        @Volatile var grantReader: (() -> Boolean)? = null

        val grantReads = AtomicInteger(0)

        init {
            applyGrant(world.grant)
        }

        /** The platform stops refusing before the grant reads fine: a grant is one atomic change on the device. */
        fun applyGrant(grant: Grant) {
            fake.coarse = true
            for (provider: String in listOf("gps", "passive")) if (provider in grant.refused) fake.refuse(provider) else fake.allow(provider)
            fake.fine = grant == Grant.FINE
        }

        fun setSilently(provider: String, enabled: Boolean) {
            if (enabled) on += provider else on -= provider
        }

        fun arm(afterReads: Int, change: () -> Unit) {
            pending = change
            landAt = reads.get() + afterReads
        }

        /** Lands the armed change now if no read landed it; true when it was still pending. */
        fun landNow(): Boolean {
            val change: () -> Unit = pending ?: return false
            pending = null
            landAt = Int.MAX_VALUE
            change()
            return true
        }

        private fun <T> afterRead(value: T): T {
            if (reads.incrementAndGet() >= landAt) landNow()
            return value
        }

        fun broadcast() = fake.setDeviceLocationEnabled(true)

        /** The provider callbacks of the live registrations of [provider], then the broadcast. */
        fun announceProvider(provider: String, enabled: Boolean) {
            for (registration: FakeLocationBackend.Registration in fake.registered()) {
                if (registration.provider != provider) continue
                if (enabled) registration.listener.onProviderEnabled(provider) else registration.listener.onProviderDisabled(provider)
            }
            broadcast()
        }

        override fun isLocationEnabled(): Boolean = afterRead(deviceOn)

        override fun isProviderEnabled(provider: String): Boolean = afterRead(deviceOn && provider in on)

        override fun hasFinePermission(): Boolean {
            val index: Int = grantReads.incrementAndGet()
            if (throwOnRead?.invoke(index) == true) throw IllegalStateException("breaker: package manager died at grant read $index")
            return afterRead(grantReader?.invoke() ?: fake.hasFinePermission())
        }

        override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
            attempts.incrementAndGet()
            fake.requestUpdates(provider, request, listener)
        }

        val attempts = AtomicInteger(0)
    }

    private fun tornFor(world: World): TornBackend = TornBackend(FakeLocationBackend(providers = world.present), world)

    private suspend fun oneShotOf(world: World, source: AndroidLocationSource, accuracy: LocationAccuracy): LocationReading {
        val shot = tornFor(world)
        val reading: LocationReading = providerOver(shot).getCurrentLocation(
            CurrentLocationOptions(
                accuracy = accuracy,
                maxAge = Duration.ZERO,
                timeout = 5.seconds,
                platform = AndroidCurrentLocationOptions(source = source),
            ),
        )
        assertEquals(0, shot.fake.registeredCount, "$world: the one-shot left a listener")
        return reading
    }

    private fun TestScope.collectInto(
        backend: LocationBackend,
        options: LocationUpdatesOptions,
        seen: MutableList<LocationReading>,
        thrown: MutableList<Throwable>,
    ): Job = backgroundScope.launch {
        try {
            providerOver(backend).observeLocation(options).collect { seen += it }
        } catch (failure: IllegalStateException) {
            if (failure.message?.startsWith("breaker:") != true) throw failure
            thrown += failure
        }
    }

    private fun sourceFor(random: Random, present: Set<String>): AndroidLocationSource =
        if (random.nextInt(3) != 0) {
            AndroidLocationSource.AUTO
        } else {
            when (present.toList()[random.nextInt(present.size)]) {
                gps -> AndroidLocationSource.GPS
                network -> AndroidLocationSource.NETWORK
                passive -> AndroidLocationSource.PASSIVE
                else -> AndroidLocationSource.FUSED
            }
        }

    private val shapes: List<Set<String>> = listOf(
        setOf(gps), setOf(passive), setOf(gps, passive), setOf(gps, network), setOf(network),
        setOf(gps, network, passive), setOf(network, passive), setOf(fused, gps, network), setOf(fused, gps),
    )

    private fun drawWorld(random: Random): World {
        val present: Set<String> = shapes[random.nextInt(shapes.size)]
        // fused exists only on API 31+, where a coarse grant is refused nothing.
        val grant: Grant = when {
            fused in present -> if (random.nextBoolean()) Grant.FINE else Grant.COARSE_API_31
            random.nextInt(3) != 0 -> Grant.COARSE_API_24_30
            else -> Grant.entries[random.nextInt(Grant.entries.size)]
        }
        return World(present, grant, present.filter { random.nextBoolean() }.toSet(), random.nextInt(4) != 0)
    }

    /** One change of the world, applied silently to [backend]; returns the new world and how to announce it. */
    private fun drawChange(random: Random, world: World, backend: TornBackend): Triple<World, () -> Unit, () -> Unit> {
        val provider: String = world.present.toList()[random.nextInt(world.present.size)]
        return when (random.nextInt(5)) {
            0, 1 -> {
                val enabled: Boolean = provider !in world.on
                Triple(
                    world.copy(on = if (enabled) world.on + provider else world.on - provider),
                    { backend.setSilently(provider, enabled) },
                    { backend.announceProvider(provider, enabled) },
                )
            }
            2 -> {
                val deviceOn: Boolean = !world.deviceOn
                Triple(world.copy(deviceOn = deviceOn), { backend.deviceOn = deviceOn }, { backend.broadcast() })
            }
            3 -> if (world.grant != Grant.FINE) {
                // A grant fires no broadcast; the next event of any kind is what the stream sees.
                Triple(world.copy(grant = Grant.FINE), { backend.applyGrant(Grant.FINE) }, { backend.broadcast() })
            } else {
                Triple(world, {}, { backend.broadcast() })
            }
            else -> Triple(world, {}, { backend.broadcast() })
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Torn reads: a change landing between any two platform reads of an evaluation or of the start
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect classes: a torn read publishing a reading neither the state before nor the state after a change
     * justifies (a false NO_FIX of a waiting stream, a missing SERVICE_DISABLED), a wrong terminal (PERMISSION_MISSING
     * decided on a stale refusal or a stale service read), duplicate registration, listener imbalance, D8 disagreement.
     * 40 seeds x 60 steps. Each step lands one change (a provider, the device switch, a coarse-to-fine grant, or
     * nothing) silently right after the k-th platform read (k seeded, 1..12) of an evaluation triggered by an unrelated
     * broadcast, then announces it with its own callbacks and broadcast. Odd seeds also land a change inside the
     * collection's own selection and start. After every step:
     * - every reading published during the step equals the one-shot's answer for the state before or the state after
     *   the change (linearisability: there is no instant at which anything else was true);
     * - a completed stream ended with one of those two answers, left nothing registered or subscribed, and is
     *   collected again;
     * - an open stream with nothing registered reads SERVICE_DISABLED (D-30: a waiting stream never reads NO_FIX);
     * - an open stream agrees with the one-shot for the state after the change (D8);
     * - at most 8 registration attempts per step, no provider registered twice, only present ones.
     * Turns red if the waiting branch read the service twice (R5 m2), if any of the three terminals judged a refusal
     * met under another grant (R5 m1), or if the waiting branch returned without publishing SERVICE_DISABLED.
     */
    @Test
    fun `a change landing between any two platform reads never publishes a reading neither side of it justifies`() =
        runTest(timeout = 600.seconds) {
            for (seed: Int in 600 until 640) {
                val random = Random(seed)
                var world: World = drawWorld(random)
                val accuracy: LocationAccuracy = LocationAccuracy.entries[random.nextInt(LocationAccuracy.entries.size)]
                val source: AndroidLocationSource = sourceFor(random, world.present)
                val backend: TornBackend = tornFor(world)
                val fake: FakeLocationBackend = backend.fake
                val options = LocationUpdatesOptions(accuracy = accuracy, platform = AndroidLocationUpdatesOptions(source = source))
                val head = "seed=$seed source=$source accuracy=$accuracy"
                var seen: MutableList<LocationReading> = mutableListOf()
                var job: Job

                suspend fun judge(where: String, from: Int, before: World, after: World, attemptsBefore: Int) {
                    val answers: Set<LocationReading> = setOf(oneShotOf(before, source, accuracy), oneShotOf(after, source, accuracy))
                    // Seed first, register second (documented): a start whose registration is then refused under the
                    // service ON shows the seed "no fix yet" right before its terminal; that pair is the start's contract.
                    val seedBeforeTerminal: Boolean = from == 0 && seen.size == 2 && seen[0] == noFix && seen[1] == permissionMissing
                    val published: List<LocationReading> = seen.drop(if (seedBeforeTerminal) 1 else from)
                    val registered: List<String> = providersOf(fake)
                    val at = "$where before=$before after=$after answers=$answers published=$published registered=$registered"
                    for (reading: LocationReading in published) assertTrue(reading in answers, "$at: $reading is justified by neither side of the change")
                    assertTrue(backend.attempts.get() - attemptsBefore <= 8, "$at: ${backend.attempts.get() - attemptsBefore} registration attempts")
                    assertEquals(registered.distinct(), registered, "$at: a provider is registered twice")
                    assertTrue(after.present.containsAll(registered), "$at: an absent provider is registered")
                }

                fun startCollection(): Job {
                    seen = mutableListOf()
                    return collectInto(backend, options, seen, mutableListOf())
                }

                // The collection itself, with a change landing inside its selection or start in odd seeds.
                var before: World = world
                var attemptsBefore: Int = backend.attempts.get()
                var announce: () -> Unit = {}
                if (seed % 2 == 1) {
                    val (next: World, land: () -> Unit, tell: () -> Unit) = drawChange(random, world, backend)
                    world = next
                    announce = tell
                    backend.arm(1 + random.nextInt(12), land)
                }
                job = startCollection()
                runCurrent()
                backend.landNow()
                announce()
                runCurrent()
                judge("$head start", 0, before, world, attemptsBefore)

                var restarts = 0
                repeat(60) { step: Int ->
                    val where = "$head step=$step restarts=$restarts"
                    if (job.isCompleted) {
                        assertTrue(seen.lastOrNull()?.endsStream == true, "$where: completed without a terminal reading: $seen")
                        assertEquals(0, fake.registeredCount, "$where: a completed stream left a listener")
                        assertEquals(0, fake.subscriberCount, "$where: a completed stream left a subscription")
                        restarts++
                        before = world
                        attemptsBefore = backend.attempts.get()
                        job = startCollection()
                        runCurrent()
                        judge("$where (re-collected)", 0, before, world, attemptsBefore)
                        return@repeat
                    }
                    before = world
                    attemptsBefore = backend.attempts.get()
                    val from: Int = seen.size
                    val (next: World, land: () -> Unit, tell: () -> Unit) = drawChange(random, world, backend)
                    world = next
                    backend.arm(1 + random.nextInt(12), land)
                    backend.broadcast()
                    runCurrent()
                    backend.landNow()
                    tell()
                    runCurrent()
                    judge(where, from, before, world, attemptsBefore)
                    if (!job.isCompleted) {
                        val registered: List<String> = providersOf(fake)
                        if (registered.isEmpty()) assertEquals(serviceDisabled, seen.last(), "$where $world: a waiting stream must read SERVICE_DISABLED: $seen")
                        assertEquals(oneShotOf(world, source, accuracy), seen.last(), "$where $world registered=$registered: the stream and the one-shot disagree: ${seen.takeLast(4)}")
                    }
                }
                job.cancelAndJoin()
                assertEquals(0, fake.registeredCount, "$head: a listener was left")
                assertEquals(0, fake.subscriberCount, "$head: a subscription was left")
            }
        }

    // ---------------------------------------------------------------------------------------------
    // The new grant re-reads: a reader that flips on every read, a reader that throws
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect classes: livelock / registration ping-pong, unbounded recursion, duplicate terminal, leak. The three
     * places that now re-judge refusals each read the grant; a grant reader whose answer flips on EVERY read (not
     * realistic, a revoke kills the process, D16: the worst case for the re-judgement) must still cost a bounded
     * number of registrations per event, never recurse without bound, never register a provider twice, end at most
     * once, and leave nothing behind. The platform refuses gps whatever the reader says. Explicit gps and AUTO
     * BALANCED (network, else gps), 300 events each: device on/off and provider flips.
     */
    @Test
    fun `a grant reader that flips on every read costs a bounded number of registrations per event and never loops`() =
        runTest(timeout = 120.seconds) {
            for (seed: Int in 1..12) {
                val random = Random(seed)
                val auto: Boolean = seed % 2 == 0
                val present: Set<String> = if (auto) setOf(gps, network) else setOf(gps)
                val world = World(present, Grant.COARSE_API_24_30, present, deviceOn = false)
                val backend: TornBackend = tornFor(world)
                val flips = AtomicInteger(0)
                backend.grantReader = { flips.incrementAndGet() % 2 == 0 }
                val options: LocationUpdatesOptions =
                    if (auto) LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED) else explicitGps
                val seen: MutableList<LocationReading> = mutableListOf()
                val job: Job = collectInto(backend, options, seen, mutableListOf())
                runCurrent()
                assertTrue(seen.isNotEmpty(), "seed=$seed auto=$auto: control: the stream started: $seen")
                var maxPerEvent = 0
                repeat(300) { step: Int ->
                    if (job.isCompleted) return@repeat
                    val before: Int = backend.attempts.get()
                    when (random.nextInt(3)) {
                        0 -> {
                            backend.deviceOn = !backend.deviceOn
                            backend.broadcast()
                        }
                        else -> {
                            val provider: String = present.toList()[random.nextInt(present.size)]
                            val enabled: Boolean = random.nextBoolean()
                            backend.setSilently(provider, enabled)
                            backend.announceProvider(provider, enabled)
                        }
                    }
                    runCurrent()
                    val spent: Int = backend.attempts.get() - before
                    maxPerEvent = maxOf(maxPerEvent, spent)
                    val registered: List<String> = providersOf(backend.fake)
                    assertEquals(registered.distinct(), registered, "seed=$seed auto=$auto step=$step: a provider is registered twice")
                    // An announced provider change is a listener callback plus a broadcast: two evaluations, each at most
                    // two passes (the re-judging recursion is bounded at depth 2) over at most two candidates.
                    assertTrue(spent <= 8, "seed=$seed auto=$auto step=$step: $spent registration attempts for one event")
                }
                assertTrue(seen.count { reading: LocationReading -> reading.endsStream } <= 1, "seed=$seed auto=$auto: more than one terminal: $seen")
                if (job.isCompleted) assertTrue(seen.last().endsStream, "seed=$seed auto=$auto: the terminal is not last: $seen")
                job.cancelAndJoin()
                assertEquals(0, backend.fake.registeredCount, "seed=$seed auto=$auto maxPerEvent=$maxPerEvent: a listener was left")
                assertEquals(0, backend.fake.subscriberCount, "seed=$seed auto=$auto: a subscription was left")
            }
        }

    /**
     * Defect classes: a failing permission read wedging the stream, a half-done move left registered twice, a wrong
     * terminal, a leak. The grant read (now made up to three more times per evaluation) throws on seeded reads during
     * the first 40 steps of a stream's life (a dead package manager); a failure at the start propagates to the
     * collector (documented for a non-security failure) and the stream is collected again. Then the reads heal, one
     * broadcast follows, and the stream must agree with the one-shot for the same state; a terminal reached at any
     * point must be the one-shot's answer for the state it was reached in. Nothing is left registered.
     */
    @Test
    fun `a grant read that throws never wedges the stream nor leaves a provider registered twice`() = runTest(timeout = 600.seconds) {
        for (seed: Int in 700 until 724) {
            val random = Random(seed)
            var world: World = drawWorld(random)
            val accuracy: LocationAccuracy = LocationAccuracy.entries[random.nextInt(LocationAccuracy.entries.size)]
            val source: AndroidLocationSource = sourceFor(random, world.present)
            val backend: TornBackend = tornFor(world)
            val fake: FakeLocationBackend = backend.fake
            val failRandom = Random(seed * 31)
            backend.throwOnRead = { _: Int -> failRandom.nextInt(5) == 0 }
            val options = LocationUpdatesOptions(accuracy = accuracy, platform = AndroidLocationUpdatesOptions(source = source))
            val head = "seed=$seed source=$source accuracy=$accuracy"
            var seen: MutableList<LocationReading> = mutableListOf()
            var thrown: MutableList<Throwable> = mutableListOf()
            var job: Job = collectInto(backend, options, seen, thrown)
            runCurrent()
            var thrownTotal = 0
            repeat(60) { step: Int ->
                if (step == 40) backend.throwOnRead = null
                val where = "$head step=$step"
                if (job.isCompleted) {
                    assertEquals(0, fake.registeredCount, "$where: a completed stream left a listener: $seen $thrown")
                    assertEquals(0, fake.subscriberCount, "$where: a completed stream left a subscription")
                    if (thrown.isEmpty()) {
                        assertTrue(seen.last().endsStream, "$where: completed without a terminal: $seen")
                        assertEquals(oneShotOf(world, source, accuracy), seen.last(), "$where $world: a wrong terminal: $seen")
                    }
                    thrownTotal += thrown.size
                    seen = mutableListOf()
                    thrown = mutableListOf()
                    job = collectInto(backend, options, seen, thrown)
                    runCurrent()
                    return@repeat
                }
                val (next: World, land: () -> Unit, tell: () -> Unit) = drawChange(random, world, backend)
                world = next
                land()
                tell()
                runCurrent()
                val registered: List<String> = providersOf(fake)
                assertEquals(registered.distinct(), registered, "$where: a provider is registered twice")
                if (step >= 41 && !job.isCompleted && registered.isNotEmpty()) {
                    // After healing, an event later: with something registered the reading says SERVICE_DISABLED exactly
                    // when the device or every registered source is off (independent of the failedMove trade-off).
                    val off: Boolean = !world.deviceOn || registered.none { provider: String -> provider in world.on }
                    assertEquals(off, seen.last() == serviceDisabled, "$where $world registered=$registered: after healing the reading disagrees with the registration: ${seen.takeLast(4)}")
                }
            }
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount, "$head thrownAtStart=$thrownTotal: a listener was left")
            assertEquals(0, fake.subscriberCount, "$head: a subscription was left")
        }
    }

    /**
     * Defect class: torn state (the state recorded as reported without the reading being sent), a stream stuck on a
     * false reading. An explicit gps stream (fine grant) reads SERVICE_DISABLED while the device is off. The device
     * comes on; during that one evaluation the grant read throws (a dead package manager: the reseed builds its
     * reading with the coarse-only check). The evaluation is abandoned (logged), but the stream has already recorded
     * "on" as reported, so every later event finds nothing to report: gps is registered and on, the device is on, and
     * the stream reads SERVICE_DISABLED for good. Turns green when the reading is built before the state is recorded
     * (or the record is undone on failure), so the next event reseeds.
     */
    @Test
    fun `a reseed whose reading fails to build is reported at the next event instead of being lost`() = runTest(timeout = 60.seconds) {
        val backend: TornBackend = tornFor(World(setOf(gps), Grant.FINE, setOf(gps), deviceOn = false))
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(serviceDisabled), seen, "control: off")
        assertEquals(listOf(gps), providersOf(backend.fake), "control: gps registered")
        backend.throwOnRead = { _: Int -> true }
        backend.deviceOn = true
        backend.broadcast()
        runCurrent()
        backend.throwOnRead = null
        assertTrue(backend.grantReads.get() > 0, "control: the evaluation read the grant")
        backend.broadcast()
        runCurrent()
        assertEquals(noFix, seen.last(), "gps is registered and on, the device is on: $seen")
        job.cancelAndJoin()
        assertEquals(0, backend.fake.registeredCount)
        assertEquals(0, backend.fake.subscriberCount)
    }

    /**
     * Defect class: an exception thrown into a platform callback. The listener's `onLocationChanged` runs on the main
     * looper; it builds the fix with the coarse-only check (a grant read) outside any guard, so a failing grant read
     * (a dead package manager, the failure every other callback of the session already catches and logs: "a
     * submitted action must not throw: it would surface on an unrelated thread") escapes into the platform's
     * listener transport, which on the device is an uncaught exception on the main thread. The delivery must return
     * normally; the next fix after the read heals is delivered. Turns green when the fix is built inside the guarded
     * action (or the read is caught there and the fix dropped/logged).
     */
    @Test
    fun `a fix delivered while the grant read fails does not throw into the platform callback`() = runTest(timeout = 60.seconds) {
        val backend: TornBackend = tornFor(World(setOf(gps), Grant.FINE, setOf(gps), deviceOn = true))
        val fake: FakeLocationBackend = backend.fake
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(gps), providersOf(fake), "control: gps registered")
        backend.throwOnRead = { _: Int -> true }
        val escaped: Throwable? = runCatching { fake.deliver(gps, fake.locationAt(gps, latitude = 10.0)) }.exceptionOrNull()
        backend.throwOnRead = null
        assertEquals(null, escaped, "a failing grant read escaped into the platform's listener callback")
        fake.deliver(gps, fake.locationAt(gps, latitude = 11.0))
        runCurrent()
        val last: LocationReading = seen.last()
        assertTrue(last is LocationReading.Available && last.fix.coordinates.latitude == 11.0, "the next fix is delivered: $seen")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // Registrations per switch: no ping-pong between a refused and an accepted source
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: registration/removal ping-pong. A coarse-only app (API 24-30) streams AUTO BALANCED (network, else
     * gps) on a device with both; gps refuses it. 100 cycles swap which source is on, through the real callbacks
     * (listener callback, then broadcast). Under the coarse grant the gps refusal is remembered: after the first
     * refusal not one more registration attempt is made, ever (the three re-judging places read an unchanged grant).
     * Then the fine grant lands and the cycles continue: each swap may move the registration (one removal, one
     * registration), never more than two attempts per swap, never two live registrations. Every reading agrees with
     * the one-shot. Control: the moves under fine happened.
     */
    @Test
    fun `swapping sources under a remembered refusal never re-asks and under the fine grant moves once per swap`() =
        runTest(timeout = 300.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
                fine = false
                coarse = true
                refuse(gps)
            }
            val seen: MutableList<LocationReading> = mutableListOf()
            val options = LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)
            val job: Job = backgroundScope.launch { providerOver(fake).observeLocation(options).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "control: network registered")
            var world = World(setOf(gps, network), Grant.COARSE_API_24_30, setOf(gps, network), deviceOn = true)

            suspend fun swap(cycle: Int, gpsOn: Boolean) {
                fake.setProviderEnabled(if (gpsOn) network else gps, false)
                fake.setProviderEnabled(if (gpsOn) gps else network, true)
                runCurrent()
                world = world.copy(on = if (gpsOn) setOf(gps) else setOf(network))
                val registered: List<String> = providersOf(fake)
                assertTrue(registered.size <= 1, "cycle=$cycle grant=${world.grant}: two live registrations: $registered")
                assertEquals(oneShotOf(world, AndroidLocationSource.AUTO, LocationAccuracy.BALANCED), seen.last(), "cycle=$cycle $world: ${seen.takeLast(4)}")
            }

            swap(0, gpsOn = true)
            val afterFirstRefusal: Int = fake.registrationAttemptCount
            for (cycle: Int in 1..100) swap(cycle, gpsOn = cycle % 2 == 0)
            assertEquals(afterFirstRefusal, fake.registrationAttemptCount, "a remembered refusal under an unchanged grant was asked again")
            assertEquals(listOf(network), providersOf(fake))

            fake.fine = true
            fake.allow(gps)
            world = world.copy(grant = Grant.FINE)
            val removalsBefore: Int = fake.removalCount
            var moves = 0
            for (cycle: Int in 101..200) {
                val attempts: Int = fake.registrationAttemptCount
                swap(cycle, gpsOn = cycle % 2 == 0)
                val spent: Int = fake.registrationAttemptCount - attempts
                assertTrue(spent <= 2, "cycle=$cycle: $spent registration attempts for one swap")
                if (spent > 0) moves++
            }
            assertTrue(moves >= 90, "control: the registration followed the swaps under fine ($moves moves)")
            assertTrue(fake.removalCount - removalsBefore <= 2 * 100, "removals ran away: ${fake.removalCount - removalsBefore}")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    // ---------------------------------------------------------------------------------------------
    // Real threads: a waiting stream under a provider storm, the grant landing mid-storm
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect classes: wrong terminal (PERMISSION_MISSING while the device is off), register twice, platform calls from
     * a waiting stream, leak. A waiting explicit gps stream (coarse-only API 24-30, device off) takes 3 threads x 300
     * gps flips (callbacks and broadcasts) while a fourth thread lands the fine grant at a seeded point. With the
     * device off the stream must never end; it reads SERVICE_DISABLED only; it asks the platform exactly once (the
     * registration the grant allows), and after the storm gps is registered exactly once. The device then comes on:
     * the reading follows the one-shot. Nothing is left after cancel.
     */
    @Test
    fun `a provider storm at a waiting stream with the grant landing mid-storm registers once and never ends`() =
        runTest(timeout = 120.seconds) {
            for (seed: Int in 1..8) {
                val world = World(setOf(gps), Grant.COARSE_API_24_30, setOf(gps), deviceOn = false)
                val backend: TornBackend = tornFor(world)
                val fake: FakeLocationBackend = backend.fake
                val seen: MutableList<LocationReading> = mutableListOf()
                val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
                runCurrent()
                assertEquals(listOf(serviceDisabled), seen, "seed=$seed: control: waiting")
                val attempts: Int = backend.attempts.get()
                val random = Random(seed)
                val grantAt: Int = random.nextInt(300)
                val start = CountDownLatch(1)
                val threads: List<Thread> = (0 until 4).map { worker: Int ->
                    Thread({
                        start.await(10, TimeUnit.SECONDS)
                        val local = Random(seed * 10 + worker)
                        repeat(300) { step: Int ->
                            if (worker == 3) {
                                if (step == grantAt) backend.applyGrant(Grant.FINE)
                                backend.broadcast()
                            } else {
                                val enabled: Boolean = local.nextBoolean()
                                backend.setSilently(gps, enabled)
                                backend.announceProvider(gps, enabled)
                            }
                        }
                    }, "breaker-r6-storm-$worker")
                }
                threads.forEach(Thread::start)
                start.countDown()
                for (thread: Thread in threads) {
                    thread.join(30_000)
                    if (thread.isAlive) fail("seed=$seed: ${thread.name} hung (hang bound)")
                }
                backend.broadcast()
                runCurrent()
                assertFalse(job.isCompleted, "seed=$seed grantAt=$grantAt: the device is off, the stream must not end: $seen")
                assertTrue(seen.all { reading: LocationReading -> reading == serviceDisabled }, "seed=$seed: the device is off: $seen")
                assertEquals(listOf(gps), providersOf(fake), "seed=$seed: gps is registered exactly once after the grant")
                assertEquals(attempts + 1, backend.attempts.get(), "seed=$seed: one registration, the one the grant allows")
                backend.setSilently(gps, true)
                backend.deviceOn = true
                backend.broadcast()
                runCurrent()
                assertEquals(noFix, seen.last(), "seed=$seed: gps on and registered under fine: $seen")
                job.cancelAndJoin()
                assertEquals(0, fake.registeredCount, "seed=$seed")
                assertEquals(0, fake.subscriberCount, "seed=$seed")
            }
        }

    /**
     * Defect class: wrong terminal through start's new retry. A coarse-only app (API 24-30), explicit gps, service ON:
     * the refusal stands under the unchanged grant, so the start must end with PERMISSION_MISSING after exactly one
     * attempt (the retry only follows a grant change); the same history with the grant landing between the refusal
     * and the retry's re-read registers gps after exactly two attempts and never says PERMISSION_MISSING. A grant read
     * that flips back after the retry's re-read (not realistic, the bound) costs at most two attempts and one terminal.
     */
    @Test
    fun `start's retry asks again only after a grant change and at most once`() = runTest(timeout = 60.seconds) {
        // Unchanged grant: one attempt, terminal.
        run {
            val backend: TornBackend = tornFor(World(setOf(gps), Grant.COARSE_API_24_30, setOf(gps), deviceOn = true))
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(noFix, permissionMissing), seen)
            assertEquals(1, backend.attempts.get(), "an unchanged grant re-asked")
            assertTrue(job.isCompleted)
            assertEquals(0, backend.fake.subscriberCount)
        }
        // Flip-back reader: fine is read only once, right at the retry's re-read.
        run {
            val backend: TornBackend = tornFor(World(setOf(gps), Grant.COARSE_API_24_30, setOf(gps), deviceOn = true))
            val reads = AtomicInteger(0)
            backend.grantReader = { reads.incrementAndGet() == 3 }
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            assertTrue(backend.attempts.get() <= 2, "attempts=${backend.attempts.get()} reads=${reads.get()}")
            assertTrue(seen.count { reading: LocationReading -> reading.endsStream } <= 1, "$seen")
            job.cancelAndJoin()
            assertEquals(0, backend.fake.registeredCount)
            assertEquals(0, backend.fake.subscriberCount)
        }
    }
}
