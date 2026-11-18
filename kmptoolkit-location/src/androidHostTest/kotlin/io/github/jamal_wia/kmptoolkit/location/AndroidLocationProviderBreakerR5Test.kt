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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Breaker round 5 of group loc-android (ticket 5.1.0, LOC-7 / LOC-10, protocol BREAKER-REVIEW.md, D-23, D-30).
 *
 * Attacks the round-5 code: listener callbacks of providers the stream does not hold are ignored, the grant is read
 * before the platform is asked, a move keeps the candidates that accepted (the incomplete decision remembered), and
 * the waiting stream of D-30 ("off beats refusal": open on SERVICE_DISABLED with nothing registered while every
 * candidate refuses and the service is off; PERMISSION_MISSING when the service returns; a grant meanwhile registers).
 * Derived from the contract: PLAN D8 (one result type and one lifecycle for both calls), D16, D19, section 4.2 rows
 * PERMISSION_MISSING / SERVICE_DISABLED, decision D-30; not from the implementation.
 *
 * Platform model on top of the scriptable fake, as in round 4: a registration on a disabled provider is answered
 * with a posted `onProviderDisabled` ([ScriptedBackend.drainEchoes]); with `deliverAfterRemoval` it still runs after
 * the registration was removed (API 24-28 `ListenerTransport`). Grants are refusal sets: coarse-only on API 24-30
 * refuses gps and passive, coarse-only on API 31+ and fine refuse nothing. Virtual time; real threads only for the
 * storm and cancel races, joined with bounded latches; `Random(seed)` with `seed=` in every message. No sleeps, no
 * wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidLocationProviderBreakerR5Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val fused: String = "fused"
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
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

    /**
     * The fake plus: per-provider non-security registration failures, the posted "disabled at registration"
     * callback (optionally delivered after removal), a hook before each registration, a hook right after a refusal
     * and a hook right before the service subscription is installed (where a change the subscription would miss can land).
     */
    private class ScriptedBackend(val fake: FakeLocationBackend, val deliverAfterRemoval: Boolean = false) : LocationBackend by fake {
        private val failures: MutableMap<String, RuntimeException> = ConcurrentHashMap()
        private val echoes = ConcurrentLinkedQueue<Pair<FakeLocationBackend.Registration, String>>()
        val attempts = AtomicInteger(0)

        @Volatile var beforeRegistration: ((String) -> Unit)? = null

        @Volatile var afterRefusal: ((String) -> Unit)? = null

        @Volatile var beforeSubscription: (() -> Unit)? = null

        fun fail(provider: String) {
            failures[provider] = IllegalArgumentException("oem: $provider registration failed")
        }

        fun lift(provider: String) {
            failures.remove(provider)
        }

        fun failing(provider: String): Boolean = provider in failures

        val anyFailing: Boolean get() = failures.isNotEmpty()

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

        override fun observeServiceChanges(onChange: () -> Unit): () -> Unit {
            beforeSubscription?.let { hook: () -> Unit ->
                beforeSubscription = null
                hook()
            }
            return fake.observeServiceChanges(onChange)
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
    // The start's closing evaluation does not re-judge refusals against the grant (executor choice 2)
    // ---------------------------------------------------------------------------------------------

    /**
     * A coarse-only app (API 24-30) asks for [options] on a device whose only gps is off. The registration is
     * refused; the fine grant lands right after that refusal; gps comes on between the start's "is the service off"
     * check and the installation of the service subscription, which is exactly the change the start's closing
     * evaluation stands in for.
     */
    private fun TestScope.waitingStartWithGrantAndSwitchOn(options: LocationUpdatesOptions): Triple<FakeLocationBackend, MutableList<LocationReading>, Job> {
        val fake = FakeLocationBackend(providers = setOf(gps)).apply {
            fine = false
            coarse = true
            refuse(gps)
        }
        fake.setProviderEnabled(gps, false)
        val backend = ScriptedBackend(fake)
        backend.afterRefusal = { provider: String ->
            if (provider == gps) {
                backend.afterRefusal = null
                fake.fine = true
                fake.allow(gps)
            }
        }
        // No subscriber and no registration exist yet: this flips the flag and fires nothing anyone hears.
        backend.beforeSubscription = { fake.setProviderEnabled(gps, true) }
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(options).collect { seen += it } }
        runCurrent()
        return Triple(fake, seen, job)
    }

    /**
     * Defect class: wrong terminal state (a stale refusal judged as current). The start's closing evaluation is the
     * stand-in for the service event the subscription would have missed, but it skips the grant re-judgement a real
     * event makes (choice 2 of the executor's round 5). Here that stand-in event finds the service on and every
     * candidate in `refused`, so it ends the stream with PERMISSION_MISSING although the fine grant is held and gps
     * is on and would accept the app. The same history with the gps switch-on arriving one instant later (a real
     * broadcast) re-reads the grant, registers gps and keeps the stream open: the outcome depends on timing only, and
     * the wrong one is terminal. Turns green when no terminal PERMISSION_MISSING is decided on refusals met under
     * another grant than the current one (forget before the terminal check, or let the closing evaluation forget too).
     */
    @Test
    fun `an explicit gps stream does not end with PERMISSION_MISSING when the grant landed and gps came on during its start`() =
        runTest(timeout = 60.seconds) {
            val (fake: FakeLocationBackend, seen: MutableList<LocationReading>, job: Job) = waitingStartWithGrantAndSwitchOn(explicitGps)
            assertEquals(serviceDisabled, seen.first(), "control: the seed says the service is off: $seen")
            assertFalse(permissionMissing in seen, "fine is held and gps is on: the grant is not missing: $seen")
            assertTrue(job.isActive, "the stream must stay open: $seen")
            fake.setDeviceLocationEnabled(true)
            runCurrent()
            assertEquals(listOf(gps), providersOf(fake), "the next event registers gps under the fine grant")
            assertNotEquals(serviceDisabled, seen.last(), "gps is on and registered: $seen")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    /** The same through AUTO (a device whose only source is gps). */
    @Test
    fun `an AUTO stream does not end with PERMISSION_MISSING when the grant landed and gps came on during its start`() =
        runTest(timeout = 60.seconds) {
            val (fake: FakeLocationBackend, seen: MutableList<LocationReading>, job: Job) =
                waitingStartWithGrantAndSwitchOn(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY))
            assertEquals(serviceDisabled, seen.first(), "control: the seed says the service is off: $seen")
            assertFalse(permissionMissing in seen, "fine is held and gps is on: the grant is not missing: $seen")
            assertTrue(job.isActive, "the stream must stay open: $seen")
            fake.setDeviceLocationEnabled(true)
            runCurrent()
            assertEquals(listOf(gps), providersOf(fake), "the next event registers gps under the fine grant")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    // ---------------------------------------------------------------------------------------------
    // The waiting stream: re-entrancy, throttle, storm, cancel
    // ---------------------------------------------------------------------------------------------

    private fun waitingGpsFake(): FakeLocationBackend = FakeLocationBackend(providers = setOf(gps)).apply {
        fine = false
        coarse = true
        refuse(gps)
        setProviderEnabled(gps, false)
    }

    /**
     * Defect class: re-entrancy / deadlock / lost terminal. The waiting stream is collected on an unconfined
     * dispatcher with a one-hour minUpdateInterval: the service switch-on runs the terminal decision inside the gate
     * on the broadcasting thread, the channel close resumes `awaitClose` inline on that same thread and `release`
     * re-takes the gate there. The terminal reading must still reach the collector at once (never held by the
     * throttle), the stream completes, and nothing stays subscribed, before any other scheduling.
     */
    @Test
    fun `a waiting stream collected unconfined ends inline on the broadcasting thread with nothing left`() = runTest(timeout = 60.seconds) {
        val fake: FakeLocationBackend = waitingGpsFake()
        val seen: MutableList<LocationReading> = mutableListOf()
        val options = LocationUpdatesOptions(minUpdateInterval = 1.hours, platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS))
        val job: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            providerOver(fake).observeLocation(options).collect { seen += it }
        }
        assertEquals(listOf(serviceDisabled), seen, "control: waiting")
        assertEquals(1, fake.subscriberCount, "control: the subscription exists")
        fake.setProviderEnabled(gps, true)
        assertEquals(listOf(serviceDisabled, permissionMissing), seen, "the terminal reading arrives inline, once")
        assertTrue(job.isCompleted, "the terminal reading completes the stream")
        assertEquals(0, fake.subscriberCount, "the subscription was released")
        assertEquals(0, fake.registeredCount)
    }

    /**
     * Defect class: duplicate terminal, platform call from a waiting stream, leak under a storm. Four threads fire
     * 300 service broadcasts each at a waiting stream while one of them, at a seeded point, switches the device on.
     * Exactly one PERMISSION_MISSING, as the last reading; not one registration attempt (D-30: the remembered
     * refusal decides); the stream completes and releases its subscription.
     */
    @Test
    fun `a broadcast storm at a waiting stream ends it exactly once without asking the platform`() = runTest(timeout = 120.seconds) {
        for (seed: Int in 1..8) {
            val fake: FakeLocationBackend = waitingGpsFake().apply { locationEnabled = false }
            fake.setProviderEnabled(gps, true)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(fake).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(serviceDisabled), seen, "seed=$seed: control: waiting while the device is off")
            val attempts: Int = fake.registrationAttemptCount
            val random = Random(seed)
            val switchAt: Int = random.nextInt(300)
            val switcher: Int = random.nextInt(4)
            val start = CountDownLatch(1)
            val threads: List<Thread> = (0 until 4).map { worker: Int ->
                Thread({
                    start.await(10, TimeUnit.SECONDS)
                    repeat(300) { step: Int ->
                        if (worker == switcher && step == switchAt) fake.setDeviceLocationEnabled(true) else fake.setProviderEnabled(gps, true)
                    }
                }, "breaker-r5-storm-$worker")
            }
            threads.forEach(Thread::start)
            start.countDown()
            for (thread: Thread in threads) {
                thread.join(30_000)
                if (thread.isAlive) fail("seed=$seed: ${thread.name} hung (hang bound)")
            }
            runCurrent()
            // Only the terminal is judged here; the reading in between a torn read can produce is pinned by the
            // deterministic test below (a reseed of a waiting stream).
            assertEquals(1, seen.count { reading: LocationReading -> reading == permissionMissing }, "seed=$seed switchAt=$switchAt: $seen")
            assertEquals(permissionMissing, seen.last(), "seed=$seed switchAt=$switchAt: $seen")
            assertEquals(serviceDisabled, seen.first(), "seed=$seed switchAt=$switchAt: $seen")
            assertTrue(job.isCompleted, "seed=$seed: the stream completes")
            assertEquals(attempts, fake.registrationAttemptCount, "seed=$seed: a waiting stream asked the platform")
            assertEquals(0, fake.subscriberCount, "seed=$seed: the subscription was left")
            assertEquals(0, fake.registeredCount, "seed=$seed")
        }
    }

    /**
     * Defect class: a torn read publishes a false state. A waiting stream (D-30: every candidate refused, the service
     * off, nothing registered) evaluates a service event; the device switch turns on between the evaluation's
     * terminal check (which still reads "off", so the stream stays open) and its transition check (which reads "on").
     * The transition check judges the remembered sources as back on and reseeds NO_FIX: the collector is told "the
     * service is on and the grant is fine, no fix yet" by a stream that has nothing registered and whose every source
     * refuses the app, before the next event ends it with PERMISSION_MISSING. Both reads are binder calls on the real
     * platform, so the switch can land between them; the broadcast storm above hits it with real threads. Turns green
     * when a waiting stream never reseeds (while nothing is registered and every candidate is refused, the only
     * readings are SERVICE_DISABLED and the terminal), or when one evaluation decides both checks from one read.
     */
    @Test
    fun `a waiting stream does not reseed NO_FIX when the device switch turns on between its two reads`() = runTest(timeout = 60.seconds) {
        val fake: FakeLocationBackend = waitingGpsFake().apply { locationEnabled = false }
        fake.setProviderEnabled(gps, true)
        val switchOnAfterNextRead = java.util.concurrent.atomic.AtomicBoolean(false)
        val backend = object : LocationBackend by fake {
            override fun isLocationEnabled(): Boolean {
                val now: Boolean = fake.isLocationEnabled()
                // The switch lands right after this read; its own broadcast comes later, as on the platform.
                if (switchOnAfterNextRead.compareAndSet(true, false)) fake.locationEnabled = true
                return now
            }
        }
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(serviceDisabled), seen, "control: waiting while the device is off")
        assertEquals(0, fake.registeredCount, "control: nothing registered")

        switchOnAfterNextRead.set(true)
        fake.setProviderEnabled(gps, true)
        runCurrent()
        assertFalse(switchOnAfterNextRead.get(), "control: the switch landed inside the evaluation")
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertEquals(listOf(serviceDisabled, permissionMissing), seen, "a waiting stream with nothing registered reseeded: $seen")
        assertTrue(job.isCompleted)
        assertEquals(0, fake.subscriberCount)
        assertEquals(0, fake.registeredCount)
    }

    /**
     * Defect class: register after release. A waiting explicit gps stream gets the fine grant; the next broadcast
     * registers gps on the broadcasting thread, which is held inside that registration while the collector cancels.
     * Whatever the order, nothing stays registered or subscribed.
     */
    @Test
    fun `a cancel racing the registration a grant allows in a waiting stream leaves nothing registered`() = runTest(timeout = 60.seconds) {
        for (seed: Int in 1..20) {
            val fake: FakeLocationBackend = waitingGpsFake()
            val backend = ScriptedBackend(fake)
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { } }
            runCurrent()
            assertEquals(0, fake.registeredCount, "seed=$seed: control: waiting, nothing registered")
            fake.fine = true
            fake.allow(gps)
            val held = CountDownLatch(1)
            val go = CountDownLatch(1)
            backend.beforeRegistration = { provider: String ->
                if (provider == gps) {
                    backend.beforeRegistration = null
                    held.countDown()
                    if (!go.await(10, TimeUnit.SECONDS)) throw AssertionError("seed=$seed: go never came (hang bound)")
                }
            }
            val looper = Thread({ fake.setDeviceLocationEnabled(true) }, "breaker-r5-grant")
            looper.start()
            if (!held.await(10, TimeUnit.SECONDS)) fail("seed=$seed: the grant never led to a registration (hang bound)")
            if (seed % 2 == 0) {
                go.countDown()
                job.cancelAndJoin()
            } else {
                val releaser = Thread({ go.countDown() }, "breaker-r5-go")
                releaser.start()
                job.cancelAndJoin()
                releaser.join(10_000)
            }
            looper.join(10_000)
            if (looper.isAlive) fail("seed=$seed: the broadcast thread hung (hang bound)")
            assertEquals(0, fake.registeredCount, "seed=$seed: survived the cancel: ${providersOf(fake)}")
            assertEquals(0, fake.subscriberCount, "seed=$seed: a subscription was left")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Seeded model check: stream vs one-shot over time, ledger, late callbacks, failures, grants
    // ---------------------------------------------------------------------------------------------

    /** Collects into [seen]; an OEM registration failure that propagates from the start is recorded in [thrown]. */
    private fun TestScope.collectInto(
        backend: LocationBackend,
        options: LocationUpdatesOptions,
        seen: MutableList<LocationReading>,
        thrown: MutableList<Throwable>,
    ): Job = backgroundScope.launch {
        try {
            providerOver(backend).observeLocation(options).collect { seen += it }
        } catch (failure: IllegalArgumentException) {
            if (failure.message?.startsWith("oem:") != true) throw failure
            thrown += failure
        }
    }

    private enum class Grant(val refused: Set<String>) {
        FINE(emptySet()),
        COARSE_API_24_30(setOf("gps", "passive")),
        COARSE_API_31(emptySet()),
    }

    private class World(
        val present: Set<String>,
        var grant: Grant,
        val on: MutableSet<String>,
        var deviceOn: Boolean,
    ) {
        override fun toString(): String = "present=$present grant=$grant on=$on deviceOn=$deviceOn"
    }

    private fun FakeLocationBackend.applyGrant(grant: Grant) {
        fine = grant == Grant.FINE
        coarse = true
        for (provider: String in listOf(gps, passive)) if (provider in grant.refused) refuse(provider) else allow(provider)
    }

    private fun backendFor(world: World): FakeLocationBackend = FakeLocationBackend(providers = world.present).apply {
        for (provider: String in world.present) if (provider !in world.on) setProviderEnabled(provider, false)
        locationEnabled = world.deviceOn
        applyGrant(world.grant)
    }

    private suspend fun oneShotOf(world: World, source: AndroidLocationSource, accuracy: LocationAccuracy): LocationReading {
        val shotFake: FakeLocationBackend = backendFor(world)
        val shot: LocationReading = newProvider(shotFake).getCurrentLocation(
            CurrentLocationOptions(
                accuracy = accuracy,
                maxAge = Duration.ZERO,
                timeout = 5.seconds,
                platform = AndroidCurrentLocationOptions(source = source),
            ),
        )
        assertEquals(0, shotFake.registeredCount, "$world: the one-shot left a listener")
        return shot
    }

    /**
     * Defect classes: stream and one-shot disagreeing over time (D8), duplicate registration, listener imbalance,
     * a self-driven callback storm, a stream that waits forever or ends wrongly, stale memory across re-collection.
     * 48 seeds x 150 steps; each seed draws a device (present sources, grant model, accuracy, source) and then flips
     * sources, the device switch, the grant (coarse to fine only: a revoke kills the process) and, in odd seeds, OEM
     * registration failures; late callbacks are delivered after removal (API 24-28). After every step: the posted
     * callbacks settle, at most 16 registration attempts, no provider registered twice and only present ones; with
     * something registered the reading says SERVICE_DISABLED exactly when the device or every registered source is
     * off. Even seeds (no platform failures) also check that the stream's reading equals the one-shot's reason for
     * the same state after every step that fired an event since the last grant change. A stream that completes is
     * checked, released, and collected again (re-init).
     */
    @Test
    fun `a seeded model of flips, grants, failures and late callbacks keeps stream and one-shot in agreement and the ledger balanced`() =
        runTest(timeout = 600.seconds) {
            val shapes: List<Set<String>> = listOf(
                setOf(gps), setOf(passive), setOf(gps, passive), setOf(gps, network), setOf(network),
                setOf(gps, network, passive), setOf(fused, gps, network), setOf(fused, gps), setOf(network, passive),
            )
            for (seed: Int in 500 until 548) {
                val random = Random(seed)
                val withFailures: Boolean = seed % 2 == 1
                val present: Set<String> = shapes[random.nextInt(shapes.size)]
                val grant: Grant = if (fused in present) {
                    if (random.nextBoolean()) Grant.FINE else Grant.COARSE_API_31
                } else {
                    Grant.entries[random.nextInt(Grant.entries.size)]
                }
                val world = World(present, grant, present.filter { random.nextBoolean() }.toMutableSet(), random.nextInt(4) != 0)
                val accuracy: LocationAccuracy = LocationAccuracy.entries[random.nextInt(LocationAccuracy.entries.size)]
                val source: AndroidLocationSource = if (random.nextInt(3) != 0) {
                    AndroidLocationSource.AUTO
                } else {
                    when (present.toList()[random.nextInt(present.size)]) {
                        gps -> AndroidLocationSource.GPS
                        network -> AndroidLocationSource.NETWORK
                        passive -> AndroidLocationSource.PASSIVE
                        else -> AndroidLocationSource.FUSED
                    }
                }
                val fake: FakeLocationBackend = backendFor(world)
                val backend = ScriptedBackend(fake, deliverAfterRemoval = true)
                val options = LocationUpdatesOptions(accuracy = accuracy, platform = AndroidLocationUpdatesOptions(source = source))
                val head = "seed=$seed source=$source accuracy=$accuracy"
                var seen: MutableList<LocationReading> = mutableListOf()
                var thrown: MutableList<Throwable> = mutableListOf()
                var job: Job = collectInto(backend, options, seen, thrown)
                var grantPending = false
                var restarts = 0
                var endedAtStartChecked = false
                runCurrent()
                backend.drainEchoes(limit = 50)
                runCurrent()
                if (job.isCompleted && !withFailures) {
                    assertEquals(oneShotOf(world, source, accuracy), seen.last(), "$head $world: the stream ended at its start with another reason than the one-shot")
                    endedAtStartChecked = true
                }
                repeat(150) { step: Int ->
                    val before: Int = backend.attempts.get()
                    var fired = true
                    when (random.nextInt(if (withFailures) 6 else 5)) {
                        0, 1 -> {
                            val provider: String = present.toList()[random.nextInt(present.size)]
                            val on: Boolean = random.nextBoolean()
                            if (on) world.on += provider else world.on -= provider
                            fake.setProviderEnabled(provider, on)
                        }
                        2 -> {
                            world.deviceOn = random.nextInt(4) != 0
                            fake.setDeviceLocationEnabled(world.deviceOn)
                        }
                        3 -> if (world.grant != Grant.FINE && random.nextInt(4) == 0) {
                            // A grant fires no broadcast: the stream picks it up at the next event.
                            world.grant = Grant.FINE
                            fake.applyGrant(Grant.FINE)
                            grantPending = true
                            fired = false
                        } else {
                            fake.setDeviceLocationEnabled(world.deviceOn)
                        }
                        4 -> fake.setDeviceLocationEnabled(world.deviceOn)
                        else -> {
                            val provider: String = present.toList()[random.nextInt(present.size)]
                            if (backend.failing(provider)) backend.lift(provider) else backend.fail(provider)
                            fired = false
                        }
                    }
                    if (fired) grantPending = false
                    runCurrent()
                    val ran: Int = backend.drainEchoes(limit = 50)
                    runCurrent()
                    val registered: List<String> = providersOf(fake)
                    val where = "$head step=$step restarts=$restarts $world failing=${present.filter(backend::failing)} registered=$registered last=${seen.takeLast(3)}"
                    assertTrue(ran < 50 && backend.pendingEchoes == 0, "$where: the posted callbacks do not settle")
                    val spent: Int = backend.attempts.get() - before
                    assertTrue(spent <= 16, "$where: $spent registration attempts in one step")
                    assertEquals(registered.distinct(), registered, "$where: a provider is registered twice")
                    assertTrue(present.containsAll(registered), "$where: an absent provider is registered")
                    if (job.isCompleted) {
                        suspend fun verifyEnded(at: String, compare: Boolean) {
                            assertEquals(0, fake.registeredCount, "$at: a completed stream left a listener")
                            assertEquals(0, fake.subscriberCount, "$at: a completed stream left a subscription")
                            // A non-security platform failure at the start propagates to the collector (documented).
                            assertTrue(seen.lastOrNull()?.endsStream == true || (withFailures && thrown.isNotEmpty()), "$at: completed without a terminal reading: $seen $thrown")
                            if (thrown.isNotEmpty()) return
                            if (compare) {
                                assertEquals(oneShotOf(world, source, accuracy), seen.last(), "$at: the stream ended with another reason than the one-shot")
                            }
                        }
                        verifyEnded(where, compare = !endedAtStartChecked && !withFailures && !grantPending)
                        // Re-init: a new collection on the same platform starts from scratch.
                        restarts++
                        seen = mutableListOf()
                        thrown = mutableListOf()
                        job = collectInto(backend, options, seen, thrown)
                        runCurrent()
                        backend.drainEchoes(limit = 50)
                        runCurrent()
                        grantPending = false
                        endedAtStartChecked = false
                        if (job.isCompleted) {
                            // It ended at its own start, in the world as it is now: judge it now, not after the next flip.
                            verifyEnded("$head step=$step restarts=$restarts (ended at start) $world last=$seen", compare = !withFailures)
                            endedAtStartChecked = true
                        }
                    } else {
                        if (registered.isNotEmpty()) {
                            val off: Boolean = !world.deviceOn || registered.none { provider: String -> provider in world.on }
                            assertEquals(off, seen.last() == serviceDisabled, "$where: the reading disagrees with the registration")
                        }
                        if (!withFailures && !grantPending) {
                            assertEquals(oneShotOf(world, source, accuracy), seen.last(), "$where: the stream and the one-shot disagree")
                        }
                    }
                }
                job.cancelAndJoin()
                assertEquals(0, fake.registeredCount, "$head: a listener was left")
                assertEquals(0, fake.subscriberCount, "$head: a subscription was left")
            }
        }
}
