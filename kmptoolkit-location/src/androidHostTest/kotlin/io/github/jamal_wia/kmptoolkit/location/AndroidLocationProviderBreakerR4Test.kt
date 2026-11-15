package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Breaker round 4 of group loc-android (ticket 5.1.0, LOC-7 / LOC-10, protocol BREAKER-REVIEW.md, D-23).
 *
 * Attacks the round-4 code: the remembered failed AUTO move (`failedMove`) and its "nothing registered, retry on
 * the next event" exception, the refusal memory that is dropped when the fine grant changes, and the one-shot
 * reason when every asked source refused (checked against the stream over every source / accuracy / grant /
 * presence / switch combination). Derived from the contract: PLAN D8 (one result type and one lifecycle for
 * both calls), D16, D19, section 4.2 rows PERMISSION_MISSING / SERVICE_DISABLED, not from the implementation.
 *
 * Platform behaviour modelled on top of the scriptable fake (documented API): a registration on a disabled
 * provider is answered with `onProviderDisabled` "immediately", posted to the listener's looper and run by
 * [ScriptedBackend.drainEchoes]. With [ScriptedBackend.deliverAfterRemoval] the posted callback still runs after
 * the registration was removed: API 24-28 `LocationManager.ListenerTransport` posts every callback as a Handler
 * message and its handler calls the listener without checking whether it was removed meanwhile (API 29+ drops it).
 * Grants are modelled by refusal sets: coarse-only on API 24-30 refuses gps and passive, coarse-only on API 31+
 * refuses nothing (the platform fuzzes instead), fine refuses nothing. Virtual time for every timeout; real threads only for the cancel races,
 * joined with bounded latches; `Random(seed)` with `seed=` in every message. No sleeps, no wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidLocationProviderBreakerR4Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val fused: String = "fused"
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val balanced = LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)
    private val high = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
        timeSource = TestTimeSource(),
    )

    private fun providersOf(fake: FakeLocationBackend): List<String> =
        fake.registered().map { registration: FakeLocationBackend.Registration -> registration.provider }.sorted()

    /**
     * The fake plus: a per-provider non-security registration failure (set and lifted), the posted "disabled at
     * registration" callback (optionally delivered after removal, the API 24-28 shape), a hook before each
     * registration and a hook right after a refusal (where a grant can land).
     */
    private class ScriptedBackend(val fake: FakeLocationBackend, val deliverAfterRemoval: Boolean = false) : LocationBackend by fake {
        private val failures: MutableMap<String, RuntimeException> = ConcurrentHashMap()
        private val echoes = ConcurrentLinkedQueue<Pair<FakeLocationBackend.Registration, String>>()
        val attempts = AtomicInteger(0)

        @Volatile var beforeRegistration: ((String) -> Unit)? = null

        @Volatile var afterRefusal: ((String) -> Unit)? = null

        fun fail(provider: String) {
            failures[provider] = IllegalArgumentException("oem: $provider registration failed")
        }

        fun lift(provider: String) {
            failures.remove(provider)
        }

        fun failing(provider: String): Boolean = provider in failures

        val pendingEchoes: Int get() = echoes.size

        override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
            attempts.incrementAndGet()
            beforeRegistration?.invoke(provider)
            failures[provider]?.let { failure: RuntimeException -> throw failure }
            try {
                fake.requestUpdates(provider, request, listener)
            } catch (refusal: SecurityException) {
                afterRefusal?.invoke(provider)
                throw refusal
            }
            if (!fake.isProviderEnabled(provider)) {
                val registration: FakeLocationBackend.Registration = fake.registered().last { live: FakeLocationBackend.Registration ->
                    live.listener === listener && live.provider == provider
                }
                echoes += registration to provider
            }
        }

        /** Runs the posted callbacks, as the main looper would, at most [limit] of them; returns how many ran. */
        fun drainEchoes(limit: Int): Int {
            var ran = 0
            while (ran < limit) {
                val (registration: FakeLocationBackend.Registration, provider: String) = echoes.poll() ?: break
                ran++
                val live: Boolean = fake.registered().any { candidate: FakeLocationBackend.Registration -> candidate === registration }
                if (live || deliverAfterRemoval) registration.listener.onProviderDisabled(provider)
            }
            return ran
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The "nothing registered" exception of failedMove (executor choice 1)
    // ---------------------------------------------------------------------------------------------

    /**
     * Sets up a HIGH stream registered on gps + network with gps off, then fused comes on while fused AND network
     * fail on the platform: the move to fused fails, the restore registers the off gps (whose platform callback is
     * posted) and then fails on the network, so nothing is registered and `failedMove` stays null by design.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.nothingRegisteredAfterTwoFailures(backend: ScriptedBackend): Job {
        val fake: FakeLocationBackend = backend.fake
        fake.setProviderEnabled(fused, false)
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(high).collect { } }
        runCurrent()
        assertEquals(listOf(gps, network), providersOf(fake), "control: the high stream starts on gps and network")
        fake.setProviderEnabled(gps, false)
        runCurrent()
        assertEquals(listOf(gps, network), providersOf(fake), "control: the network still serves, nothing moves")
        backend.fail(fused)
        backend.fail(network)
        fake.setProviderEnabled(fused, true)
        runCurrent()
        assertEquals(0, fake.registeredCount, "control: the move and the restore both failed, nothing is registered")
        return job
    }

    /**
     * Defect class: livelock (a self-driven rebind storm) through executor choice 1. With nothing registered every
     * event re-runs the move; the restore's brief registration of the off gps makes the platform post
     * onProviderDisabled, and on API 24-28 that posted callback still reaches the listener after the failed restore
     * removed it. Each such callback re-runs the move, which posts the next one: one fused switch-on drives an
     * endless loop on the main looper. Turns green when a callback for a registration the session no longer holds
     * (or any listener callback while nothing is registered) does not re-run the move; the service broadcast, which
     * fires on every real provider change, still retries.
     */
    @Test
    fun `a move whose restore failed too is not driven into a storm by the restore's own late platform callback`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network, fused))
            val backend = ScriptedBackend(fake, deliverAfterRemoval = true)
            val job: Job = nothingRegisteredAfterTwoFailures(backend)
            val attemptsBefore: Int = backend.attempts.get()

            val ran: Int = backend.drainEchoes(limit = 100)
            runCurrent()

            assertTrue(
                ran < 100 && backend.pendingEchoes == 0,
                "one fused switch-on drove $ran posted callbacks and ${backend.attempts.get() - attemptsBefore} more registration " +
                    "attempts and is still going (${backend.pendingEchoes} queued): each failed restore posts the callback that retries it",
            )
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount, "a listener was left")
            assertEquals(0, fake.subscriberCount, "a subscription was left")
        }

    /** Control of the storm above on API 29+ (a removed registration's posted callback is dropped): it settles. */
    @Test
    fun `a move whose restore failed too settles when the platform drops a removed registration's callback`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network, fused))
            val backend = ScriptedBackend(fake, deliverAfterRemoval = false)
            val job: Job = nothingRegisteredAfterTwoFailures(backend)
            val ran: Int = backend.drainEchoes(limit = 100)
            runCurrent()
            assertTrue(ran <= 1 && backend.pendingEchoes == 0, "the dropped callback drove $ran more")

            // The external broadcast still retries the nothing-registered state (choice 1), and recovers once the failures lift.
            backend.lift(fused)
            backend.lift(network)
            fake.setDeviceLocationEnabled(true)
            runCurrent()
            assertEquals(listOf(fused), providersOf(fake), "the next real event registers the AUTO target")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    // ---------------------------------------------------------------------------------------------
    // failedMove: back and forth, callbacks per switch-off, re-collect
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: livelock / unbounded work per event, stale memory. gps fails on the platform for good; the
     * network is switched off and on 20 times. Every switch-off may try gps once (the decision changed) and must
     * then settle: at most 2 registration attempts (gps, the network restore) and at most 1 posted callback per
     * switch-off, none per switch-on; the reading follows the network. After the failure lifts, the next real
     * change serves gps; a new collection starts with no memory of the old one.
     */
    @Test
    fun `a failed move costs one bounded attempt per real switch-off and is forgotten by the next collection`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = ScriptedBackend(fake, deliverAfterRemoval = true)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "control")
            backend.fail(gps)
            repeat(20) { round: Int ->
                val before: Int = backend.attempts.get()
                fake.setProviderEnabled(network, false)
                runCurrent()
                val ran: Int = backend.drainEchoes(limit = 50)
                runCurrent()
                val spent: Int = backend.attempts.get() - before
                assertTrue(spent <= 2, "round=$round: one switch-off cost $spent registration attempts")
                assertTrue(ran <= 1 && backend.pendingEchoes == 0, "round=$round: one switch-off drove $ran callbacks")
                assertEquals(listOf(network), providersOf(fake), "round=$round: the previous registration is kept")
                assertEquals(serviceDisabled, seen.last(), "round=$round: ${seen.takeLast(3)}")

                val beforeOn: Int = backend.attempts.get()
                fake.setProviderEnabled(network, true)
                runCurrent()
                assertEquals(0, backend.drainEchoes(limit = 50), "round=$round: a switch-on posts nothing")
                assertEquals(beforeOn, backend.attempts.get(), "round=$round: the network is the target again, nothing moves")
                assertNotEquals(serviceDisabled, seen.last(), "round=$round: ${seen.takeLast(3)}")
            }
            backend.lift(gps)
            fake.setProviderEnabled(network, false)
            runCurrent()
            backend.drainEchoes(limit = 50)
            runCurrent()
            assertEquals(listOf(gps), providersOf(fake), "the failure lifted and the decision changed: gps serves")
            assertNotEquals(serviceDisabled, seen.last())
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)

            // A remembered failure under one collection never reaches the next.
            fake.setProviderEnabled(network, true)
            val first: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { } }
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "control: the first collection starts on the network")
            backend.fail(gps)
            fake.setProviderEnabled(network, false)
            runCurrent()
            backend.drainEchoes(limit = 50)
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "control: the move to gps failed and is remembered")
            first.cancelAndJoin()
            backend.lift(gps)
            val second: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { } }
            runCurrent()
            assertEquals(listOf(gps), providersOf(fake), "a new collection decides afresh")
            second.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    /**
     * A seeded single-threaded model check of a BALANCED AUTO stream (fine grant) whose gps and network
     * registrations fail on the platform and recover at random, with gps / network / device flips and the posted
     * callback drained after every step (dropped after removal, so choice 1 cannot storm here; that is the test
     * above). After every step: the callbacks settle, at most 1 registration and at most 4 registration attempts,
     * and whenever one source is registered the reading says SERVICE_DISABLED exactly when that source or the
     * device is off. At the end the failures lift and two real changes put the stream on the AUTO target.
     */
    @Test
    fun `a seeded model check of failing and recovering registrations keeps the stream bounded and consistent`() =
        runTest(timeout = 120.seconds) {
            for (seed: Int in listOf(41, 42, 43, 44, 45, 46)) {
                val fake = FakeLocationBackend(providers = setOf(gps, network))
                val backend = ScriptedBackend(fake)
                val seen: MutableList<LocationReading> = mutableListOf()
                val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { seen += it } }
                runCurrent()
                val random = Random(seed)
                var gpsOn = true
                var networkOn = true
                var deviceOn = true
                repeat(300) { step: Int ->
                    when (random.nextInt(5)) {
                        0 -> {
                            gpsOn = random.nextBoolean()
                            fake.setProviderEnabled(gps, gpsOn)
                        }
                        1 -> {
                            networkOn = random.nextBoolean()
                            fake.setProviderEnabled(network, networkOn)
                        }
                        2 -> {
                            deviceOn = random.nextBoolean()
                            fake.setDeviceLocationEnabled(deviceOn)
                        }
                        3 -> if (backend.failing(gps)) backend.lift(gps) else backend.fail(gps)
                        else -> if (backend.failing(network)) backend.lift(network) else backend.fail(network)
                    }
                    val before: Int = backend.attempts.get()
                    runCurrent()
                    val ran: Int = backend.drainEchoes(limit = 50)
                    runCurrent()
                    val where = "seed=$seed step=$step gps=$gpsOn network=$networkOn device=$deviceOn " +
                        "gpsFails=${backend.failing(gps)} networkFails=${backend.failing(network)} registered=${providersOf(fake)}"
                    assertTrue(ran < 50 && backend.pendingEchoes == 0, "$where: the posted callbacks do not settle")
                    assertTrue(backend.attempts.get() - before <= 4, "$where: ${backend.attempts.get() - before} attempts in one step")
                    assertTrue(fake.registeredCount <= 1, "$where: duplicate registration")
                    val registered: List<String> = providersOf(fake)
                    if (registered.size == 1) {
                        val on: Boolean = if (registered[0] == gps) gpsOn else networkOn
                        assertEquals(!(deviceOn && on), seen.last() == serviceDisabled, "$where: the reading ${seen.takeLast(3)} disagrees with the registration")
                    }
                }
                backend.lift(gps)
                backend.lift(network)
                fake.setDeviceLocationEnabled(true)
                fake.setProviderEnabled(gps, true)
                fake.setProviderEnabled(network, true)
                fake.setProviderEnabled(network, false)
                fake.setProviderEnabled(network, true)
                runCurrent()
                backend.drainEchoes(limit = 50)
                runCurrent()
                assertEquals(listOf(network), providersOf(fake), "seed=$seed: after recovery AUTO serves the network")
                assertNotEquals(serviceDisabled, seen.last(), "seed=$seed: ${seen.takeLast(3)}")
                job.cancelAndJoin()
                assertEquals(0, fake.registeredCount, "seed=$seed: a listener was left")
                assertEquals(0, fake.subscriberCount, "seed=$seed: a subscription was left")
            }
        }

    /**
     * Defect class: leak / register after release on the new non-security failure path of a move. The rebind
     * thread is held inside the failing gps registration (hold = move) or inside the network restore that follows
     * it (hold = restore) while the collector cancels; the release must still remove what the restore put back.
     */
    @Test
    fun `a cancel arriving inside a failing move or its restore leaves nothing registered`() = runTest(timeout = 60.seconds) {
        for (seed: Int in 1..20) {
            val holdInRestore: Boolean = seed % 2 == 0
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = ScriptedBackend(fake)
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { } }
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "seed=$seed: control")
            backend.fail(gps)
            val held = CountDownLatch(1)
            val go = CountDownLatch(1)
            val sawGps = AtomicInteger(0)
            backend.beforeRegistration = { provider: String ->
                val hit: Boolean = if (holdInRestore) provider == network && sawGps.get() > 0 else provider == gps
                if (provider == gps) sawGps.incrementAndGet()
                if (hit) {
                    backend.beforeRegistration = null
                    held.countDown()
                    if (!go.await(10, TimeUnit.SECONDS)) throw AssertionError("seed=$seed: go never came (hang bound)")
                }
            }
            val looper = Thread({ fake.setProviderEnabled(network, false) }, "breaker-r4-move")
            looper.start()
            if (!held.await(10, TimeUnit.SECONDS)) fail("seed=$seed holdInRestore=$holdInRestore: never held (hang bound)")
            go.countDown()
            job.cancelAndJoin()
            looper.join(10_000)
            if (looper.isAlive) fail("seed=$seed: the move thread hung (hang bound)")
            assertEquals(0, fake.registeredCount, "seed=$seed holdInRestore=$holdInRestore: survived the cancel: ${providersOf(fake)}")
            assertEquals(0, fake.subscriberCount, "seed=$seed: a subscription was left")
        }
    }

    /**
     * Defect class: wrong state (executor choice 2, "no partial keep"). A HIGH stream on fused; fused goes off and
     * AUTO moves to "gps and network". gps accepts, the network fails on the platform: the whole move is dropped and
     * the OFF fused is put back, so the stream reads SERVICE_DISABLED while gps is on and registered a moment ago
     * (D19: SERVICE_DISABLED only when no usable source is on), and stays there until the decision changes. Turns
     * green when a move of a non-"first of" row keeps the candidates that accepted (a non-security failure of one
     * candidate skipped like a refusal, not fatal to the move).
     */
    @Test
    fun `a high accuracy move keeps gps when only the network fails on the platform`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network, fused))
        val backend = ScriptedBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(high).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(fused), providersOf(fake), "control: the high stream starts on the enabled fused provider")
        backend.fail(network)
        fake.setProviderEnabled(fused, false)
        runCurrent()
        backend.drainEchoes(limit = 50)
        runCurrent()
        assertTrue(gps in providersOf(fake), "gps is on and accepted the move: registered ${providersOf(fake)}")
        assertNotEquals(serviceDisabled, seen.last(), "gps is on and usable: ${seen.takeLast(3)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // The refusal memory and the grant
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: stale state through a check-then-act race. The grant under which a refusal was met is read
     * AFTER the platform refused. A fine grant that lands between the platform's refusal and that read is recorded
     * as "refused under fine", so the refusal is never forgotten: gps is on and accepts the app, the network is off,
     * and the stream reads SERVICE_DISABLED through every later event. Reading the grant BEFORE the registration
     * errs the safe way (a refusal recorded as coarse is retried once under fine). Turns green with that order.
     */
    @Test
    fun `a fine grant landing right after a refusal does not pin the refusal for the rest of the stream`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
                fine = false
                coarse = true
                refuse(gps)
            }
            fake.setProviderEnabled(network, false)
            val backend = ScriptedBackend(fake)
            backend.afterRefusal = { provider: String ->
                if (provider == gps) {
                    backend.afterRefusal = null
                    fake.fine = true
                    fake.allow(gps)
                }
            }
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { seen += it } }
            runCurrent()
            // Breaker round 5 ruling: the grant landed during the start, so the start may already retry gps under it
            // ([gps]) or leave that to the next event ([network], reading SERVICE_DISABLED); both are allowed here.
            val afterStart: List<String> = providersOf(fake)
            assertTrue(afterStart == listOf(network) || afterStart == listOf(gps), "control: gps refused at start: $afterStart")
            if (afterStart == listOf(network)) assertEquals(serviceDisabled, seen.last(), "control: $seen")

            repeat(3) {
                fake.setDeviceLocationEnabled(true)
                runCurrent()
                backend.drainEchoes(limit = 10)
                runCurrent()
            }
            assertEquals(listOf(gps), providersOf(fake), "fine is held and gps accepts, the network is off")
            assertNotEquals(serviceDisabled, seen.last(), "gps is usable: ${seen.takeLast(3)}")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    // ---------------------------------------------------------------------------------------------
    // One-shot reason against the stream's, every combination
    // ---------------------------------------------------------------------------------------------

    private enum class Grant(val refused: Set<String>) {
        FINE(emptySet()),
        COARSE_API_24_30(setOf("gps", "passive")),
        COARSE_API_31(emptySet()),
    }

    private data class Combo(
        val source: AndroidLocationSource,
        val accuracy: LocationAccuracy,
        val grant: Grant,
        val present: Set<String>,
        val on: Set<String>,
        val deviceOn: Boolean,
    )

    private fun combos(sources: List<AndroidLocationSource>): List<Combo> {
        val all: List<String> = listOf(fused, gps, network, passive)
        val out: MutableList<Combo> = mutableListOf()
        for (presentMask: Int in 0 until 16) {
            val present: Set<String> = all.filterIndexed { index: Int, _: String -> presentMask and (1 shl index) != 0 }.toSet()
            val presentList: List<String> = present.toList()
            for (onMask: Int in 0 until (1 shl presentList.size)) {
                val on: Set<String> = presentList.filterIndexed { index: Int, _: String -> onMask and (1 shl index) != 0 }.toSet()
                for (deviceOn: Boolean in listOf(true, false)) {
                    for (grant: Grant in Grant.entries) {
                        for (accuracy: LocationAccuracy in LocationAccuracy.entries) {
                            for (source: AndroidLocationSource in sources) out += Combo(source, accuracy, grant, present, on, deviceOn)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun backendFor(combo: Combo): FakeLocationBackend = FakeLocationBackend(providers = combo.present).apply {
        for (provider: String in combo.present) if (provider !in combo.on) setProviderEnabled(provider, false)
        locationEnabled = combo.deviceOn
        if (combo.grant != Grant.FINE) {
            fine = false
            coarse = true
        }
        for (provider: String in combo.grant.refused) refuse(provider)
    }

    /** The stream's settled reason and the one-shot's reason (no fix is ever delivered) for [combo]; counters checked. */
    private suspend fun kotlinx.coroutines.test.TestScope.reasonsOf(combo: Combo): Pair<LocationReading?, LocationReading> {
        val streamFake: FakeLocationBackend = backendFor(combo)
        val seen: MutableList<LocationReading> = mutableListOf()
        val stream: Job = backgroundScope.launch {
            newProvider(streamFake).observeLocation(
                LocationUpdatesOptions(accuracy = combo.accuracy, platform = AndroidLocationUpdatesOptions(source = combo.source)),
            ).collect { seen += it }
        }
        runCurrent()
        stream.cancelAndJoin()
        assertEquals(0, streamFake.registeredCount, "$combo: the stream left a listener")
        assertEquals(0, streamFake.subscriberCount, "$combo: the stream left a subscription")

        val shotFake: FakeLocationBackend = backendFor(combo)
        val shot: LocationReading = newProvider(shotFake).getCurrentLocation(
            CurrentLocationOptions(
                accuracy = combo.accuracy,
                maxAge = Duration.ZERO,
                timeout = 5.seconds,
                platform = AndroidCurrentLocationOptions(source = combo.source),
            ),
        )
        assertEquals(0, shotFake.registeredCount, "$combo: the one-shot left a listener")
        assertEquals(0, shotFake.subscriberCount, "$combo: the one-shot left a subscription")
        return seen.lastOrNull() to shot
    }

    private suspend fun kotlinx.coroutines.test.TestScope.mismatchesOver(all: List<Combo>): List<String> {
        val mismatches: MutableList<String> = mutableListOf()
        for (combo: Combo in all) {
            val (stream: LocationReading?, shot: LocationReading) = reasonsOf(combo)
            if (stream != shot) mismatches += "$combo: stream=$stream oneShot=$shot"
        }
        return mismatches
    }

    /**
     * Defect class: one result type, two answers (PLAN D8: one mapper for both calls; D19; 4.2 rows). Exhaustive
     * over AUTO x every accuracy x {fine, coarse API 24-30, coarse API 31+} x every presence and on/off of fused,
     * gps, network, passive x the device switch: the one-shot (no fix ever arrives) must give the reason the stream
     * settles on for the same device state (NO_FIX, SERVICE_DISABLED, PERMISSION_MISSING or UNSUPPORTED).
     */
    @Test
    fun `an AUTO one-shot gives the stream's reason in every source, accuracy, grant, presence and switch combination`() =
        runTest(timeout = 600.seconds) {
            val all: List<Combo> = combos(listOf(AndroidLocationSource.AUTO))
            val mismatches: List<String> = mismatchesOver(all)
            assertTrue(
                mismatches.isEmpty(),
                "${mismatches.size} of ${all.size} combinations disagree; first 10:\n" + mismatches.take(10).joinToString("\n"),
            )
        }

    /** The same exhaustive check for the explicit sources (FUSED, GPS, NETWORK, PASSIVE). */
    @Test
    fun `an explicit-source one-shot gives the stream's reason in every combination`() = runTest(timeout = 600.seconds) {
        val all: List<Combo> = combos(
            listOf(AndroidLocationSource.FUSED, AndroidLocationSource.GPS, AndroidLocationSource.NETWORK, AndroidLocationSource.PASSIVE),
        )
        val mismatches: List<String> = mismatchesOver(all)
        assertTrue(
            mismatches.isEmpty(),
            "${mismatches.size} of ${all.size} combinations disagree; first 10:\n" + mismatches.take(10).joinToString("\n"),
        )
    }
}
