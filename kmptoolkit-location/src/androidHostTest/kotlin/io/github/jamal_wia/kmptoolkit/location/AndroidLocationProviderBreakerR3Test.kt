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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Breaker round 3 of group loc-android (ticket 5.1.0, LOC-7 / LOC-10, protocol BREAKER-REVIEW.md, D-23).
 *
 * Attacks the round-3 code: the "restore the previous registration" path of a failed AUTO move, the
 * per-stream remembered-refused set, and the one-shot answer when every raced source refused while another
 * candidate of the row is only switched off (the executor's open question, ruled in the round-3 report).
 *
 * The platform behaviour these tests add to the scriptable fake is documented API: `LocationListener.
 * onProviderDisabled` - "If a provider is disabled when this listener is registered, this callback will be
 * invoked immediately" (posted to the listener's looper, so it is queued here and run by [ScriptedBackend.
 * drainEchoes], the stand-in for the main looper). Derived from the contract (PLAN D8 lifecycle, D16, D19,
 * section 4.2 rows PERMISSION_MISSING / SERVICE_DISABLED / "Service off then on"), not from the
 * implementation. Virtual time for every timeout; real threads only for the cancel race, joined with
 * bounded latches; `Random(seed)` with `seed=` in every message. No sleeps, no wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidLocationProviderBreakerR3Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)
    private val noFix: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX)
    private val balanced = LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)

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
     * The fake plus three platform behaviours: a non-security registration failure that can be set AND lifted
     * per provider (an OEM IllegalArgumentException), the documented "onProviderDisabled at once when registered
     * on a disabled provider" callback, posted (queued) for the registration that caused it and dropped if that
     * registration is gone by the time the looper runs it, and a hook run before each registration.
     */
    private class ScriptedBackend(val fake: FakeLocationBackend) : LocationBackend by fake {
        private val failures: MutableMap<String, RuntimeException> = ConcurrentHashMap()
        private val echoes = ConcurrentLinkedQueue<Pair<FakeLocationBackend.Registration, String>>()
        val attempts = AtomicInteger(0)

        @Volatile var beforeRegistration: ((String) -> Unit)? = null

        fun fail(provider: String, failure: RuntimeException) {
            failures[provider] = failure
        }

        fun lift(provider: String) {
            failures.remove(provider)
        }

        val pendingEchoes: Int get() = echoes.size

        override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
            attempts.incrementAndGet()
            beforeRegistration?.invoke(provider)
            failures[provider]?.let { failure: RuntimeException -> throw failure }
            fake.requestUpdates(provider, request, listener)
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
                if (fake.registered().any { live: FakeLocationBackend.Registration -> live === registration }) {
                    registration.listener.onProviderDisabled(provider)
                }
            }
            return ran
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The restore path of a failed AUTO move
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: livelock (a self-driven loop). A move target that fails with a non-security exception is not
     * remembered (executor choice 3), and the restore registers the previous source, which is OFF (that is why the
     * stream moved). The platform answers that registration with onProviderDisabled, which re-evaluates, sees the
     * same target again and moves again: remove, fail, re-register, callback, forever, on the main looper.
     * Turns green when a failed move target is not retried until the AUTO decision itself changes.
     */
    @Test
    fun `a move target that fails on the platform does not drive an endless rebind loop through the restore's own callback`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = ScriptedBackend(fake)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "control: the balanced stream starts on the network")

            backend.fail(gps, IllegalArgumentException("oem: gps registration failed"))
            fake.setProviderEnabled(network, false)
            runCurrent()
            val attemptsAfterEvent: Int = backend.attempts.get()

            val ran: Int = backend.drainEchoes(limit = 100)
            runCurrent()

            assertTrue(
                ran < 100 && backend.pendingEchoes == 0,
                "one switch-off drove $ran posted callbacks and ${backend.attempts.get() - attemptsAfterEvent} more registration " +
                    "attempts and is still going (${backend.pendingEchoes} queued): every restore of the off network re-triggers the move",
            )
            assertEquals(listOf(network), providersOf(fake), "the previous registration is kept")
            assertEquals(serviceDisabled, seen.last(), "the network the stream can use is off: ${seen.takeLast(4)}")

            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount, "a listener was left")
            assertEquals(0, fake.subscriberCount, "a subscription was left")
        }

    /** Control of the loop above with a refusal instead of an OEM failure: the refused target is remembered, so it terminates. */
    @Test
    fun `a refused move target and the restore's own callback settle after one round`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
            fine = false
            coarse = true
            refuse(gps)
        }
        val backend = ScriptedBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(network), providersOf(fake), "control")

        fake.setProviderEnabled(network, false)
        runCurrent()
        val ran: Int = backend.drainEchoes(limit = 100)
        runCurrent()

        assertTrue(ran <= 2 && backend.pendingEchoes == 0, "the restore's callback drove $ran more callbacks")
        assertEquals(listOf(network), providersOf(fake))
        assertEquals(serviceDisabled, seen.last(), "${seen.takeLast(4)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    /**
     * Defect class: wrong state / leak after a double failure. The move target fails AND the restore fails, so
     * nothing is registered; the stream must still decide on what it knows (SERVICE_DISABLED: the network is
     * off), and once the platform recovers the next event registers exactly one source, never two.
     */
    @Test
    fun `a move whose restore fails too leaves nothing registered, reports the off state and recovers to one registration`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = ScriptedBackend(fake)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "control")

            backend.fail(gps, IllegalArgumentException("oem gps"))
            backend.fail(network, IllegalStateException("oem network"))
            fake.setProviderEnabled(network, false)
            runCurrent()
            assertEquals(0, fake.registeredCount, "control: both the move and the restore failed")
            assertEquals(serviceDisabled, seen.last(), "the decision is still reached: ${seen.takeLast(4)}")
            assertTrue(job.isActive, "a failed move must not end the stream")

            backend.lift(gps)
            backend.lift(network)
            fake.setProviderEnabled(network, true)
            runCurrent()
            backend.drainEchoes(limit = 20)
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "exactly one registration after the recovery")
            assertNotEquals(serviceDisabled, seen.last(), "the network is back: ${seen.takeLast(4)}")

            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    /**
     * Defect class: leak / register after release. The rebind thread is held inside the RESTORE registration
     * (after the move target refused); the cancel arrives meanwhile. The release must run after the restore and
     * remove what it put back.
     */
    @Test
    fun `a cancel arriving while a refused move restores the previous registration leaves nothing registered`() =
        runTest(timeout = 60.seconds) {
            for (seed: Int in 1..20) {
                val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
                    fine = false
                    coarse = true
                    refuse(gps)
                }
                val backend = ScriptedBackend(fake)
                val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { } }
                runCurrent()
                assertEquals(listOf(network), providersOf(fake), "seed=$seed: control")

                val inRestore = CountDownLatch(1)
                val go = CountDownLatch(1)
                val sawGps = AtomicInteger(0)
                backend.beforeRegistration = { provider: String ->
                    if (provider == gps) {
                        sawGps.incrementAndGet()
                    } else if (sawGps.get() > 0) {
                        backend.beforeRegistration = null
                        inRestore.countDown()
                        if (!go.await(10, TimeUnit.SECONDS)) throw AssertionError("seed=$seed: go never came (hang bound)")
                    }
                }
                val looper = Thread({ fake.setProviderEnabled(network, false) }, "breaker-r3-restore")
                looper.start()
                if (!inRestore.await(10, TimeUnit.SECONDS)) fail("seed=$seed: the restore never started (hang bound)")
                go.countDown()
                job.cancelAndJoin()
                looper.join(10_000)
                if (looper.isAlive) fail("seed=$seed: the restore thread hung (hang bound)")

                assertEquals(0, fake.registeredCount, "seed=$seed: the restored registration survived the cancel: ${providersOf(fake)}")
                assertEquals(0, fake.subscriberCount, "seed=$seed: a subscription was left")
            }
        }

    // ---------------------------------------------------------------------------------------------
    // The remembered-refused set
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: stale state. The set rests on "a refusal does not change without a process restart"; that holds
     * for a revoke (the process dies) but not for a GRANT: an app that holds coarse and is granted fine keeps
     * running (on API 24-30 a fine request inside an already granted location group is granted silently). After
     * the grant gps accepts, yet the stream keeps reading SERVICE_DISABLED through every later service event while
     * gps is on and usable (D19: SERVICE_DISABLED only when nothing usable is on). Turns green when the remembered
     * refusals are dropped once the grant they were met under changed.
     */
    @Test
    fun `a refusal remembered under a coarse grant is forgotten once fine is granted and the next service event serves gps`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
                fine = false
                coarse = true
                refuse(gps)
            }
            fake.setProviderEnabled(network, false)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(fake).observeLocation(balanced).collect { seen += it } }
            runCurrent()
            assertEquals(serviceDisabled, seen.last(), "control: the only source this app can use is off: $seen")
            assertTrue(job.isActive)

            // The user grants fine; the platform accepts gps from now on. Then any service event.
            fake.fine = true
            fake.allow(gps)
            fake.setDeviceLocationEnabled(true)
            runCurrent()

            assertEquals(listOf(gps), providersOf(fake), "gps is on and now accepts the app, the network is off")
            assertNotEquals(serviceDisabled, seen.last(), "a usable source is on, the off state is stale: ${seen.takeLast(4)}")

            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    /**
     * A seeded single-threaded model check of a rebinding AUTO BALANCED stream with the documented callback on a
     * disabled registration, a fixed grant per seed (coarse-only: gps refused; or fine), random gps / network /
     * device flips. After every step and its posted callbacks: at most one registration, the callbacks settle,
     * and the last reading matches the model (SERVICE_DISABLED exactly when nothing usable is on).
     */
    @Test
    fun `a seeded model check of flips with the platform's registration callback keeps one registration and the right state`() =
        runTest(timeout = 120.seconds) {
            for (seed: Int in listOf(31, 32, 33, 34, 35, 36)) {
                val coarseOnly: Boolean = seed % 2 == 1
                val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
                    if (coarseOnly) {
                        fine = false
                        coarse = true
                        refuse(gps)
                    }
                }
                val backend = ScriptedBackend(fake)
                val seen: MutableList<LocationReading> = mutableListOf()
                val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { seen += it } }
                runCurrent()
                val random = Random(seed)
                var gpsOn = true
                var networkOn = true
                var deviceOn = true
                repeat(300) { step: Int ->
                    when (random.nextInt(3)) {
                        0 -> {
                            gpsOn = random.nextBoolean()
                            fake.setProviderEnabled(gps, gpsOn)
                        }
                        1 -> {
                            networkOn = random.nextBoolean()
                            fake.setProviderEnabled(network, networkOn)
                        }
                        else -> {
                            deviceOn = random.nextBoolean()
                            fake.setDeviceLocationEnabled(deviceOn)
                        }
                    }
                    runCurrent()
                    val ran: Int = backend.drainEchoes(limit = 50)
                    runCurrent()
                    val where = "seed=$seed step=$step coarseOnly=$coarseOnly gps=$gpsOn network=$networkOn device=$deviceOn"
                    assertTrue(ran < 50 && backend.pendingEchoes == 0, "$where: the posted callbacks do not settle")
                    assertTrue(fake.registeredCount <= 1, "$where: duplicate registration ${providersOf(fake)}")
                    val usableOn: Boolean = deviceOn && (networkOn || (gpsOn && !coarseOnly))
                    if (usableOn) {
                        assertNotEquals(serviceDisabled, seen.last(), "$where: a usable source is on: ${seen.takeLast(4)}")
                        val expected: String = if (networkOn) network else gps
                        assertEquals(listOf(expected), providersOf(fake), "$where: AUTO serves network, else gps")
                    } else {
                        assertEquals(serviceDisabled, seen.last(), "$where: nothing usable is on: ${seen.takeLast(4)}")
                        assertEquals(1, fake.registeredCount, "$where: the listener is kept while off")
                    }
                }
                job.cancelAndJoin()
                assertEquals(0, fake.registeredCount, "seed=$seed: a listener was left")
                assertEquals(0, fake.subscriberCount, "seed=$seed: a subscription was left")
            }
        }

    // ---------------------------------------------------------------------------------------------
    // One-shot: every raced source refused while another candidate of the row is only switched off
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: wrong terminal reason (the executor's open question, ruled a defect in the round-3 report).
     * LOW_POWER AUTO is "network, else passive"; a coarse-only app is refused passive below API 31, the network
     * is switched off. The stream answers an open SERVICE_DISABLED (AutoRefusalFamilyTest), the one-shot answers
     * PERMISSION_MISSING: the app holds the grant it asked for, and the state the user can change is the
     * switched-off network (D8 one mapper for both calls, D19, 4.2 PERMISSION_MISSING row as ruled in round 2:
     * only when every candidate refused).
     */
    @Test
    fun `a low power one-shot whose raced passive source refuses while the network is off answers SERVICE_DISABLED like the stream`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(network, passive)).apply {
                fine = false
                coarse = true
                refuse(passive)
            }
            fake.setProviderEnabled(network, false)
            val lowPower = LocationAccuracy.LOW_POWER

            val streamSeen: MutableList<LocationReading> = mutableListOf()
            val stream: Job = backgroundScope.launch {
                newProvider(fake).observeLocation(LocationUpdatesOptions(accuracy = lowPower)).collect { streamSeen += it }
            }
            runCurrent()
            assertEquals(serviceDisabled, streamSeen.last(), "control: the stream on the same device: $streamSeen")
            stream.cancelAndJoin()

            val reading: LocationReading = newProvider(fake).getCurrentLocation(
                CurrentLocationOptions(accuracy = lowPower, maxAge = Duration.ZERO, timeout = 30.seconds),
            )
            assertEquals(0L, currentTime, "a known answer is given at once")
            assertEquals(
                serviceDisabled,
                reading,
                "same device state, different reason: the one-shot sends the app to ask for a permission it already holds",
            )
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    /**
     * The same defect on the gps + network rows (HIGH_ACCURACY, and BALANCED one-shot): coarse-only, gps refused,
     * the network off. The one-shot races only the enabled gps, which refuses, and answers PERMISSION_MISSING;
     * the stream on HIGH_ACCURACY registers the network and reads an open SERVICE_DISABLED.
     */
    @Test
    fun `a high or balanced one-shot whose only enabled source refuses while the network is off answers SERVICE_DISABLED`() =
        runTest(timeout = 60.seconds) {
            for (accuracy: LocationAccuracy in listOf(LocationAccuracy.HIGH_ACCURACY, LocationAccuracy.BALANCED)) {
                val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
                    fine = false
                    coarse = true
                    refuse(gps)
                }
                fake.setProviderEnabled(network, false)

                val streamSeen: MutableList<LocationReading> = mutableListOf()
                val stream: Job = backgroundScope.launch {
                    newProvider(fake).observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY))
                        .collect { streamSeen += it }
                }
                runCurrent()
                assertEquals(serviceDisabled, streamSeen.last(), "control ($accuracy): the HIGH stream on the same device: $streamSeen")
                assertTrue(stream.isActive)
                stream.cancelAndJoin()

                val startedAt: Long = currentTime
                val reading: LocationReading = newProvider(fake).getCurrentLocation(
                    CurrentLocationOptions(accuracy = accuracy, maxAge = Duration.ZERO, timeout = 30.seconds),
                )
                assertEquals(startedAt, currentTime, "$accuracy: a known answer is given at once")
                assertFalse(
                    reading == permissionMissing,
                    "$accuracy: answered $reading, but the app holds its grant; only the network being off stops it",
                )
                assertEquals(serviceDisabled, reading, "$accuracy")
                assertEquals(0, fake.registeredCount, "$accuracy: a listener was left")
                assertEquals(0, fake.subscriberCount, "$accuracy: a subscription was left")
            }
        }

    /** Control of the empty-race rule: every raced source fails on the platform (not a refusal, not off): NO_FIX at once. */
    @Test
    fun `a one-shot whose every source fails on the platform answers NO_FIX at once, never SERVICE_DISABLED`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = ScriptedBackend(fake)
            backend.fail(gps, IllegalArgumentException("oem gps"))
            backend.fail(network, IllegalArgumentException("oem network"))
            val reading: LocationReading = providerOver(backend).getCurrentLocation(
                CurrentLocationOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, maxAge = Duration.ZERO, timeout = 30.seconds),
            )
            assertEquals(0L, currentTime, "every source ended: a known answer at once")
            assertEquals(noFix, reading)
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }
}
