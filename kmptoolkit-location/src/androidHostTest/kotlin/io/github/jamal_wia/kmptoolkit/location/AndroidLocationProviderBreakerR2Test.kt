package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Breaker round 2 of group loc-android (ticket 5.1.0, LOC-7 / LOC-10, protocol BREAKER-REVIEW.md, D-23).
 *
 * Attacks the round-2 code: the AUTO rebind of a running stream under an approximate-only (coarse-only)
 * grant, the M4 early end of a one-shot call when a raced source refused the caller, the mapping of a valid
 * but huge `minUpdateDistanceMeters` onto the API 31+ `LocationRequest`, and the per-collection
 * `StateMachineLock` gate (a platform that calls back synchronously, on the same thread or on the main
 * looper while the registration is in flight; a cancel racing a rebind; a seeded storm of real threads
 * flipping the sources of a rebinding AUTO stream).
 *
 * Derived from the contract (PLAN D16, D19, section 4.2 "Service off then on: stays open, listener kept,
 * reseeds on return", "SERVICE_DISABLED at once", LocationProvider KDoc), not from the implementation. A
 * coarse-only app on API 24-30 is modelled as the existing tests do: `fine = false`, `coarse = true` and
 * every gps registration refused with a SecurityException. Virtual time for every timeout; real threads
 * only where the race is the subject, joined with bounded latches; `Random(seed)` with `seed=` in every
 * message. No sleeps, no wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidLocationProviderBreakerR2Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)
    private val noFix: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX)
    private val balanced = LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)

    /** A device as a coarse-only app sees it on API 24-30: every gps call is a SecurityException, the network works. */
    private fun coarseOnly(): FakeLocationBackend = FakeLocationBackend(providers = setOf(gps, network)).apply {
        fine = false
        coarse = true
        refuse(gps)
    }

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
        timeSource = TestTimeSource(),
    )

    private fun providersOf(fake: FakeLocationBackend): List<String> =
        fake.registered().map { registration: FakeLocationBackend.Registration -> registration.provider }.sorted()

    /** Runs [block] on a second thread (the platform's main looper) and waits for it, bounded. */
    private fun onOtherThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val thread = Thread({
            try {
                block()
            } catch (thrown: Throwable) {
                failure.set(thrown)
            } finally {
                done.countDown()
            }
        }, "breaker-r2-main-looper")
        thread.start()
        if (!done.await(10, TimeUnit.SECONDS)) fail("the simulated main-looper event hung (hang bound): the gate deadlocked")
        failure.get()?.let { thrown: Throwable -> throw AssertionError("the simulated main-looper event failed", thrown) }
    }

    // ---------------------------------------------------------------------------------------------
    // AUTO rebind under an approximate-only grant (API 24-30)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a balanced AUTO stream on a coarse-only grant reports the network going off although gps is on and recovers`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseOnly()
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(fake).observeLocation(balanced).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "control: the balanced stream runs on the network source")
            fake.deliver(network, fake.locationAt(network, 41.0))
            runCurrent()
            assertEquals(GeoCoordinates(41.0, 2.0), seen.last().fixOrNull()?.coordinates, "control: the network fix arrived")

            // gps is on but refuses this app: nothing that can deliver is on any more.
            fake.setProviderEnabled(network, false)
            runCurrent()

            assertEquals(
                serviceDisabled,
                seen.last(),
                "the only source this app can use went off, yet the stream rests on the last fix with " +
                    "${fake.registeredCount} registration(s) live: the off state is hidden (D16): $seen",
            )
            assertTrue(job.isActive, "SERVICE_DISABLED keeps the stream open")

            fake.setProviderEnabled(network, true)
            runCurrent()
            fake.deliver(network, fake.locationAt(network, 42.0))
            runCurrent()
            assertEquals(GeoCoordinates(42.0, 2.0), seen.last().fixOrNull()?.coordinates, "the network returned: its fix reaches the collector: $seen")

            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount, "a listener was left")
            assertEquals(0, fake.subscriberCount, "a subscription was left")
        }

    @Test
    fun `a balanced AUTO stream started on a coarse-only grant with the network off stays open and serves the network when it returns`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseOnly()
            fake.setProviderEnabled(network, false)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(fake).observeLocation(balanced).collect { seen += it } }
            runCurrent()

            assertFalse(
                permissionMissing in seen,
                "the app holds its location permission and the network source only is off: a terminal " +
                    "PERMISSION_MISSING ends a stream that must stay open and reseed on return (4.2): $seen",
            )
            assertTrue(job.isActive, "the stream completed although only a source is off: $seen")
            assertEquals(serviceDisabled, seen.last(), "nothing this app can use is on: $seen")

            fake.setProviderEnabled(network, true)
            runCurrent()
            fake.deliver(network, fake.locationAt(network, 43.0))
            runCurrent()
            assertEquals(GeoCoordinates(43.0, 2.0), seen.last().fixOrNull()?.coordinates, "the network returned: $seen")

            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `service events with a refused rebind target never loop on their own`() = runTest(timeout = 60.seconds) {
        // iOS breaker round 2 M2 (a denial restarting the updates without bound), checked on Android: a refused
        // re-registration must cost at most a bounded number of platform calls per service event and none
        // without one.
        val fake: FakeLocationBackend = coarseOnly()
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(fake).observeLocation(balanced).collect { seen += it } }
        runCurrent()
        fake.setProviderEnabled(network, false)
        runCurrent()

        val attemptsBefore: Int = fake.registrationAttemptCount
        val readingsBefore: Int = seen.size
        val events = 30
        repeat(events) { fake.setDeviceLocationEnabled(true) }
        runCurrent()
        val attemptsAfterEvents: Int = fake.registrationAttemptCount
        runCurrent()

        assertTrue(attemptsAfterEvents - attemptsBefore <= events * 2, "registration attempts grow faster than the events: ${attemptsAfterEvents - attemptsBefore} for $events")
        assertEquals(attemptsAfterEvents, fake.registrationAttemptCount, "registration attempts go on without any event")
        assertEquals(readingsBefore, seen.size, "events that change nothing emitted readings: ${seen.drop(readingsBefore)}")

        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // M4 (round 1) under a refused source: the early end must not count a source that can never deliver
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a one-shot call whose only accepted source is switched off ends at once although the refused gps is on`() =
        runTest(timeout = 60.seconds) {
            val fake: FakeLocationBackend = coarseOnly()
            val timeout: Duration = 30.seconds
            val call: Deferred<LocationReading> = async {
                providerOver(fake).getCurrentLocation(
                    CurrentLocationOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, maxAge = Duration.ZERO, timeout = timeout),
                )
            }
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "control: gps refused, the network waits")

            fake.setProviderEnabled(network, false)
            runCurrent()

            assertTrue(
                call.isCompleted,
                "the only source that can deliver is off (gps refused this app), yet the call still waits at " +
                    "t=$currentTime ms for its ${timeout.inWholeMilliseconds} ms timeout (D19: a known answer at once)",
            )
            assertNotEquals(noFix, call.await(), "a known answer is not NO_FIX")
            assertEquals(0, fake.registeredCount, "a listener was left")
            assertEquals(0, fake.subscriberCount, "a subscription was left")
        }

    // ---------------------------------------------------------------------------------------------
    // Boundaries: a valid option the API 31+ LocationRequest cannot hold
    // ---------------------------------------------------------------------------------------------

    @Test
    @Config(sdk = [30, 31, 35])
    fun `a huge but valid minUpdateDistanceMeters does not fail the stream through the real manager`() = runTest(timeout = 60.seconds) {
        val application: Application = ApplicationProvider.getApplicationContext()
        val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(gps, true)
        shadowOf(manager).setProviderEnabled(network, true)
        // Finite and not negative: LocationUpdatesOptions accepts it. As a Float it is Infinity.
        val options = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, minUpdateDistanceMeters = 1.0e39)
        val failure = CompletableDeferred<Throwable?>()
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            try {
                createLocationProvider(application).observeLocation(options).collect { seen += it }
            } catch (thrown: Throwable) {
                failure.complete(thrown)
            }
        }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        assertNull(
            if (failure.isCompleted) failure.getCompleted() else null,
            "a valid option failed the stream on the platform's request builder",
        )
        @Suppress("DEPRECATION")
        val listeners: Int = shadowOf(manager).getRequestLocationUpdateListeners().size
        assertTrue(listeners > 0, "the stream registered nothing: $seen")
        job.cancelAndJoin()
    }

    // ---------------------------------------------------------------------------------------------
    // The gate: synchronous platform callbacks, re-entrancy, cancel racing a rebind, storms
    // ---------------------------------------------------------------------------------------------

    /** A backend whose registration calls back synchronously: [duringRegistration] runs inside requestUpdates. */
    private class SyncCallbackBackend(val fake: FakeLocationBackend) : LocationBackend by fake {
        @Volatile var duringRegistration: ((LocationListener) -> Unit)? = null

        @Volatile var duringRemoval: (() -> Unit)? = null

        override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
            fake.requestUpdates(provider, request, listener)
            duringRegistration?.also { duringRegistration = null }?.invoke(listener)
        }

        override fun removeUpdates(listener: LocationListener) {
            duringRemoval?.also { duringRemoval = null }?.invoke()
            fake.removeUpdates(listener)
        }
    }

    @Test
    fun `a provider callback the platform runs on the main looper while the registration is in flight neither deadlocks nor is lost`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = SyncCallbackBackend(fake)
            // The collector holds the gate inside requestUpdates; the platform reports the source off on another
            // thread and waits for that thread (a platform lock held across its own callback).
            backend.duringRegistration = { _: LocationListener -> onOtherThread { fake.setProviderEnabled(network, false) } }
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch {
                providerOver(backend).observeLocation(
                    LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.NETWORK)),
                ).collect { seen += it }
            }
            runCurrent()

            assertEquals(listOf(noFix, serviceDisabled), seen, "the off state reported once, after the seed")
            assertTrue(job.isActive)
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `a fix the platform delivers synchronously from inside the registration lands after the seed`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        fake.setCached(network, fake.locationAt(network, 40.0, ageMillis = 1_000))
        val backend = SyncCallbackBackend(fake)
        backend.duringRegistration = { listener: LocationListener -> listener.onLocationChanged(fake.locationAt(network, 44.0)) }
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            providerOver(backend).observeLocation(
                LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.NETWORK)),
            ).collect { seen += it }
        }
        runCurrent()

        assertEquals(
            listOf(GeoCoordinates(40.0, 2.0), GeoCoordinates(44.0, 2.0)),
            seen.map { reading: LocationReading -> reading.fixOrNull()?.coordinates },
            "a re-entrant delivery must be queued behind the seed, never ahead of it nor lost",
        )
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
    }

    @Test
    fun `a cancel racing a rebind on the main looper leaves nothing registered`() = runTest(timeout = 60.seconds) {
        for (seed: Int in 1..20) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = SyncCallbackBackend(fake)
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { } }
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "seed=$seed: control: on the network source")

            val inRebind = CountDownLatch(1)
            val go = CountDownLatch(1)
            backend.duringRemoval = {
                inRebind.countDown()
                if (!go.await(10, TimeUnit.SECONDS)) throw AssertionError("seed=$seed: go never came (hang bound)")
            }
            val looper = Thread({ fake.setProviderEnabled(network, false) }, "breaker-r2-rebind")
            looper.start()
            if (!inRebind.await(10, TimeUnit.SECONDS)) fail("seed=$seed: the rebind never started (hang bound)")
            // The rebind holds the gate between its removal and its registration; the cancel's release waits for it.
            go.countDown()
            job.cancelAndJoin()
            looper.join(10_000)
            if (looper.isAlive) fail("seed=$seed: the rebind thread hung (hang bound)")

            assertEquals(0, fake.registeredCount, "seed=$seed: the moved registration survived the cancel: ${providersOf(fake)}")
            assertEquals(0, fake.subscriberCount, "seed=$seed: a subscription was left")
        }
    }

    @Test
    fun `a seeded storm of source flips from real threads leaves a rebinding AUTO stream on the right single source`() =
        runTest(timeout = 120.seconds) {
            for (seed: Int in listOf(11, 12, 13, 14, 15)) {
                val fake = FakeLocationBackend(providers = setOf(gps, network))
                val seen: MutableList<LocationReading> = mutableListOf()
                val job: Job = backgroundScope.launch { providerOver(fake).observeLocation(balanced).collect { seen += it } }
                runCurrent()
                assertEquals(listOf(network), providersOf(fake), "seed=$seed: control")

                val failures = ConcurrentLinkedQueue<Throwable>()
                val start = CountDownLatch(1)
                val workers: List<Thread> = (0 until 4).map { index: Int ->
                    Thread({
                        try {
                            start.await(10, TimeUnit.SECONDS)
                            val random = Random(seed * 31 + index)
                            repeat(200) { step: Int ->
                                when (random.nextInt(4)) {
                                    0 -> fake.setProviderEnabled(gps, random.nextBoolean())
                                    1 -> fake.setProviderEnabled(network, random.nextBoolean())
                                    2 -> fake.setDeviceLocationEnabled(random.nextBoolean())
                                    else -> fake.deliverToAll(fake.locationAt(gps, 10.0 + step % 50))
                                }
                            }
                        } catch (thrown: Throwable) {
                            failures += thrown
                        }
                    }, "breaker-r2-storm-$index")
                }
                workers.forEach { worker: Thread -> worker.start() }
                start.countDown()
                for (worker: Thread in workers) {
                    worker.join(60_000)
                    if (worker.isAlive) fail("seed=$seed: ${worker.name} hung (hang bound): a deadlock or a livelock")
                }
                failures.peek()?.let { thrown: Throwable -> throw AssertionError("seed=$seed: a storm thread failed", thrown) }

                fake.setDeviceLocationEnabled(true)
                fake.setProviderEnabled(gps, true)
                fake.setProviderEnabled(network, false)
                runCurrent()
                assertEquals(listOf(gps), providersOf(fake), "seed=$seed: exactly one registration, on the enabled gps")
                assertNotEquals(serviceDisabled, seen.last(), "seed=$seed: gps is on: ${seen.takeLast(6)}")

                fake.setProviderEnabled(network, true)
                runCurrent()
                assertEquals(listOf(network), providersOf(fake), "seed=$seed: back on the preferred network, nothing duplicated")

                fake.setDeviceLocationEnabled(false)
                runCurrent()
                assertEquals(serviceDisabled, seen.last(), "seed=$seed: the device switch-off was not reported: ${seen.takeLast(6)}")

                job.cancelAndJoin()
                assertEquals(0, fake.registeredCount, "seed=$seed: a listener was left")
                assertEquals(0, fake.subscriberCount, "seed=$seed: a subscription was left")
            }
        }
}
