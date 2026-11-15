package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Round 2 of group loc-android: the fixes of the breaker round (M1-M4, m1-m3) and what they added, each with
 * a control assertion that the thing existed before the release assertion.
 *
 * Defect classes: a service-state decision read on one thread and published from another (M1: a seeded storm
 * of real threads must end on the platform's state and still report the next switch-off, and a live fix must
 * not be overtaken by a reseed read before it); `observeLocationEnabled` resting on a value read before a newer
 * broadcast (M2: a storm); `AUTO` choosing a source by presence instead of by state (M3, also while the stream
 * runs: the registration moves to the enabled source); a one-shot call that waits out a timeout whose outcome is
 * known (M4: through the broadcast and through the provider callback alone, with a subscription counter proving
 * the release after a win, a timeout and a cancel); platform failures escaping a "never throws" call or a
 * cancellation handler (m1, m3). Virtual time, a scripted thread-safe backend, real threads only where the race
 * itself is the subject (latches and bounded joins, `Random(seed)` with the seed in every message).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidLocationProviderRound2Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val fused: String = "fused"
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val noFix: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX)

    /** The scripted fake plus the platform misbehaviour these tests need, one switch per kind. */
    private class ScriptedBackend(val fake: FakeLocationBackend) : LocationBackend by fake {
        /** Runs once, inside the next cache read. */
        @Volatile var onCacheRead: (() -> Unit)? = null

        /** Runs once, before the next service subscription is made. */
        @Volatile var beforeSubscribe: (() -> Unit)? = null

        /** When set, the subscription is refused with this failure. */
        @Volatile var subscribeFailure: RuntimeException? = null

        /** When set, the subscription is a no-op: only the provider callbacks reach the call. */
        @Volatile var deaf: Boolean = false

        /** When set, every `isProviderEnabled` read throws it. */
        @Volatile var enabledFailure: RuntimeException? = null

        /** When set, cancelling a framework one-shot call throws. */
        @Volatile var cancelFailure: RuntimeException? = null

        override fun lastKnownLocation(provider: String): Location? {
            onCacheRead?.also { onCacheRead = null }?.invoke()
            return fake.lastKnownLocation(provider)
        }

        override fun isProviderEnabled(provider: String): Boolean {
            enabledFailure?.let { failure: RuntimeException -> throw failure }
            return fake.isProviderEnabled(provider)
        }

        override fun observeServiceChanges(onChange: () -> Unit): () -> Unit {
            beforeSubscribe?.also { beforeSubscribe = null }?.invoke()
            subscribeFailure?.let { failure: RuntimeException -> throw failure }
            return if (deaf) ({ }) else fake.observeServiceChanges(onChange)
        }

        override fun requestCurrentLocation(provider: String, accuracy: LocationAccuracy, onResult: (Location?) -> Unit): (() -> Unit)? {
            val cancel: (() -> Unit)? = fake.requestCurrentLocation(provider, accuracy, onResult)
            val failure: RuntimeException? = cancelFailure
            return if (cancel == null || failure == null) cancel else ({ cancel(); throw failure })
        }
    }

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
        timeSource = TestTimeSource(),
    )

    private fun providersOf(fake: FakeLocationBackend): Set<String> =
        fake.registered().map { registration: FakeLocationBackend.Registration -> registration.provider }.toSet()

    private fun oneShot(timeout: Duration = 30.seconds, source: AndroidLocationSource = AndroidLocationSource.AUTO): CurrentLocationOptions =
        CurrentLocationOptions(maxAge = Duration.ZERO, timeout = timeout, platform = AndroidCurrentLocationOptions(source))

    /** Runs [block] on a plain thread (the platform's main looper) and waits for it, bounded. */
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
        }, "round2-main-looper")
        thread.start()
        if (!done.await(10, TimeUnit.SECONDS)) fail("the simulated main-looper event hung (hang bound)")
        failure.get()?.let { thrown: Throwable -> throw AssertionError("the simulated main-looper event failed", thrown) }
    }

    /** Starts [threads] plain threads behind one start gate, each running [body] with its own seeded random; waits for all, bounded. */
    private fun storm(seed: Int, threads: Int, body: (Random) -> Unit) {
        val start = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers: List<Thread> = List(threads) { worker: Int ->
            Thread({
                try {
                    start.await()
                    body(Random(seed * 1000 + worker))
                } catch (thrown: Throwable) {
                    failures += thrown
                }
            }, "round2-storm-$worker")
        }
        workers.forEach { worker: Thread -> worker.start() }
        start.countDown()
        for (worker: Thread in workers) {
            worker.join(60_000)
            if (worker.isAlive) fail("seed=$seed: ${worker.name} did not finish (hang bound): a deadlock or a livelock")
        }
        failures.peek()?.let { thrown: Throwable -> throw AssertionError("seed=$seed: a storm thread failed", thrown) }
    }

    // ---------------------------------------------------------------------------------------------
    // M1: one decision at a time, in arrival order
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a storm of service flips from real threads ends on the state of the platform and the next switch-off is reported`() =
        runTest(timeout = 120.seconds) {
            for (seed: Int in listOf(1, 2, 3, 4, 5)) {
                val fake = FakeLocationBackend(providers = setOf(gps, network))
                val provider: LocationProvider = providerOver(fake)
                val seen: MutableList<LocationReading> = mutableListOf()
                val job: Job = backgroundScope.launch {
                    provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { seen += it }
                }
                runCurrent()
                assertEquals(2, fake.registeredCount, "seed=$seed: control: both sources registered")
                assertEquals(1, fake.subscriberCount, "seed=$seed: control: the service is watched")

                storm(seed, threads = 4) { random: Random ->
                    repeat(STORM_STEPS) { step: Int ->
                        when (random.nextInt(4)) {
                            0 -> fake.setProviderEnabled(gps, random.nextBoolean())
                            1 -> fake.setProviderEnabled(network, random.nextBoolean())
                            2 -> fake.setDeviceLocationEnabled(random.nextBoolean())
                            else -> fake.deliver(gps, fake.locationAt(gps, 10.0 + step % 50))
                        }
                    }
                }

                fake.setProviderEnabled(gps, true)
                fake.setProviderEnabled(network, true)
                fake.setDeviceLocationEnabled(true)
                runCurrent()
                assertNotEquals(serviceDisabled, seen.last(), "seed=$seed: the service is on, yet the stream rests on SERVICE_DISABLED: ${seen.takeLast(6)}")
                fake.setDeviceLocationEnabled(false)
                runCurrent()
                assertEquals(serviceDisabled, seen.last(), "seed=$seed: the next real switch-off was not reported: ${seen.takeLast(6)}")
                fake.setDeviceLocationEnabled(true)
                runCurrent()
                assertNotEquals(serviceDisabled, seen.last(), "seed=$seed: the service came back, yet the stream rests on SERVICE_DISABLED")

                job.cancelAndJoin()
                assertEquals(0, fake.registeredCount, "seed=$seed: a listener was left")
                assertEquals(0, fake.subscriberCount, "seed=$seed: a subscription was left")
            }
        }

    @Test
    fun `a live fix delivered while the reseed reads the cache is not overtaken by the older cached position`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val backend = ScriptedBackend(fake)
        val provider: LocationProvider = providerOver(backend)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { seen += it }
        }
        runCurrent()
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "control: the stream is off")
        fake.setCached(gps, fake.locationAt(gps, 41.0, ageMillis = 5_000))
        // The device comes back; while the reseed reads the cache, the platform delivers a newer live fix.
        backend.onCacheRead = { onOtherThread { fake.deliver(gps, fake.locationAt(gps, 42.0)) } }

        fake.setDeviceLocationEnabled(true)
        runCurrent()

        assertEquals(GeoCoordinates(42.0, 2.0), seen.last().fixOrNull()?.coordinates, "the older cached reseed overtook the live fix: ${seen.takeLast(4)}")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
    }

    @Test
    fun `an exception from a platform read inside a service callback does not wedge the stream`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val backend = ScriptedBackend(fake)
        val provider: LocationProvider = providerOver(backend)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { seen += it }
        }
        runCurrent()
        assertEquals(2, fake.registeredCount, "control: registered")

        backend.enabledFailure = IllegalStateException("system server died")
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        backend.enabledFailure = null
        fake.setDeviceLocationEnabled(false)
        runCurrent()

        assertEquals(serviceDisabled, seen.last(), "the failed read was dropped and the next event still decides: $seen")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // M2: observeLocationEnabled ends on the platform's state
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a storm of device switches from real threads leaves observeLocationEnabled on the platform state`() = runTest(timeout = 120.seconds) {
        for (seed: Int in listOf(1, 2, 3, 4, 5)) {
            val fake = FakeLocationBackend()
            val provider: LocationProvider = providerOver(fake)
            val seen: MutableList<Boolean> = mutableListOf()
            val job: Job = backgroundScope.launch { provider.observeLocationEnabled().collect { value: Boolean -> seen += value } }
            runCurrent()
            assertEquals(listOf(true), seen, "seed=$seed: control: the first value is the current state")
            assertEquals(1, fake.subscriberCount, "seed=$seed: control: subscribed")

            storm(seed, threads = 4) { random: Random ->
                repeat(STORM_STEPS) { fake.setDeviceLocationEnabled(random.nextBoolean()) }
            }
            for (final: Boolean in listOf(false, true, false)) {
                fake.setDeviceLocationEnabled(final)
                runCurrent()
                assertEquals(final, seen.last(), "seed=$seed: the flow rests on a stale value, saw ${seen.takeLast(6)}")
            }
            assertTrue(seen.zipWithNext().all { (a: Boolean, b: Boolean) -> a != b }, "seed=$seed: distinct values only: ${seen.takeLast(8)}")

            job.cancelAndJoin()
            assertEquals(0, fake.subscriberCount, "seed=$seed: the subscription was left")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // M3: AUTO chooses by enabled state, also while the stream runs
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a balanced AUTO stream moves its registration to the enabled source and back and never reads SERVICE_DISABLED while one is on`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network, passive))
            val provider: LocationProvider = providerOver(fake)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch {
                provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)).collect { seen += it }
            }
            runCurrent()
            assertEquals(setOf(network), providersOf(fake), "control: the balanced stream starts on the network source")

            fake.setProviderEnabled(network, false)
            runCurrent()
            assertEquals(setOf(gps), providersOf(fake), "the registration moved to the enabled gps")
            assertEquals(1, fake.registeredCount, "the old registration was removed")
            fake.deliver(gps, fake.locationAt(gps, 41.0))
            runCurrent()
            assertEquals(GeoCoordinates(41.0, 2.0), seen.last().fixOrNull()?.coordinates, "the gps fix reaches the collector: $seen")

            fake.setProviderEnabled(network, true)
            runCurrent()
            assertEquals(setOf(network), providersOf(fake), "the preferred source is back: the registration moved back")
            assertFalse(serviceDisabled in seen, "one source was on all the time: $seen")

            fake.setProviderEnabled(gps, false)
            fake.setProviderEnabled(network, false)
            runCurrent()
            assertEquals(serviceDisabled, seen.last(), "now none is on")
            assertEquals(1, fake.registeredCount, "control: the stream stays registered through SERVICE_DISABLED")
            fake.setProviderEnabled(gps, true)
            runCurrent()
            assertNotEquals(serviceDisabled, seen.last(), "a source returned: the stream reseeded")
            assertEquals(setOf(gps), providersOf(fake), "and registered on the enabled one")

            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount, "release removed the moved registration")
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `a switched-off fused provider does not hide the enabled gps and network and AUTO returns to fused when it is on`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network, fused))
        fake.setProviderEnabled(fused, false)
        val provider: LocationProvider = providerOver(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { seen += it }
        }
        runCurrent()
        assertEquals(setOf(gps, network), providersOf(fake), "a present but disabled fused provider is not selected")
        assertFalse(serviceDisabled in seen, "$seen")

        fake.setProviderEnabled(fused, true)
        runCurrent()
        assertEquals(setOf(fused), providersOf(fake), "fused is on: AUTO uses it alone")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)

        val call: Deferred<LocationReading> = async { provider.getCurrentLocation(oneShot(timeout = 5.seconds)) }
        runCurrent()
        assertEquals(setOf(fused), providersOf(fake), "the one-shot call races fused alone while it is on")
        call.cancelAndJoin()
        fake.setProviderEnabled(fused, false)
        val second: Deferred<LocationReading> = async { provider.getCurrentLocation(oneShot(timeout = 5.seconds)) }
        runCurrent()
        assertEquals(setOf(gps, network), providersOf(fake), "and gps and network while it is off")
        second.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
    }

    @Test
    fun `a device with only a switched-off fused provider reads SERVICE_DISABLED, not UNSUPPORTED`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(fused))
        fake.setProviderEnabled(fused, false)
        val provider: LocationProvider = providerOver(fake)

        assertEquals(serviceDisabled, provider.getCurrentLocation(oneShot()))
        assertEquals(0L, currentTime, "a known answer must not wait out the timeout")
    }

    // ---------------------------------------------------------------------------------------------
    // M4: a one-shot call ends at once when its sources switch off
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the service subscription of a one-shot call exists while it waits and is released after a win, a timeout and a cancel`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val provider: LocationProvider = providerOver(fake)

            val won: Deferred<LocationReading> = async { provider.getCurrentLocation(oneShot(timeout = 5.seconds)) }
            runCurrent()
            assertEquals(1, fake.subscriberCount, "control: the call watches the service while it waits")
            fake.deliver(gps, fake.locationAt(gps, 41.0))
            runCurrent()
            assertEquals(GeoCoordinates(41.0, 2.0), won.await().fixOrNull()?.coordinates)
            assertEquals(0, fake.subscriberCount, "released after a win")
            assertEquals(0, fake.registeredCount)

            val timedOut: Deferred<LocationReading> = async { provider.getCurrentLocation(oneShot(timeout = 5.seconds)) }
            runCurrent()
            assertEquals(1, fake.subscriberCount, "control: subscribed again")
            advanceTimeBy(6.seconds)
            runCurrent()
            assertEquals(noFix, timedOut.await())
            assertEquals(0, fake.subscriberCount, "released after the timeout")
            assertEquals(0, fake.registeredCount)

            val cancelled: Deferred<LocationReading> = async { provider.getCurrentLocation(oneShot(timeout = 5.seconds)) }
            runCurrent()
            assertEquals(1, fake.subscriberCount, "control: subscribed a third time")
            cancelled.cancelAndJoin()
            assertEquals(0, fake.subscriberCount, "released after a cancel")
            assertEquals(0, fake.registeredCount)
        }

    @Test
    fun `a source reported off by its own callback alone ends the call at once`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        val backend = ScriptedBackend(fake)
        backend.deaf = true
        val provider: LocationProvider = providerOver(backend)
        val call: Deferred<LocationReading> = async { provider.getCurrentLocation(oneShot(source = AndroidLocationSource.NETWORK)) }
        runCurrent()
        assertEquals(setOf(network), providersOf(fake), "control: the call waits on the network source")

        fake.setProviderEnabled(network, false)
        runCurrent()

        assertTrue(call.isCompleted, "the callback said the source is off, yet the call waits at t=$currentTime ms")
        assertEquals(serviceDisabled, call.await())
        assertEquals(0, fake.registeredCount)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a switch-off between the caller's read and the subscription is caught by the check right after it`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        val backend = ScriptedBackend(fake)
        var hookRan = false
        backend.beforeSubscribe = {
            hookRan = true
            fake.setProviderEnabled(network, false)
        }
        val provider: LocationProvider = providerOver(backend)

        val reading: LocationReading = provider.getCurrentLocation(oneShot(source = AndroidLocationSource.NETWORK))

        assertTrue(hookRan, "control: the switch-off happened in the window")
        assertEquals(serviceDisabled, reading)
        assertEquals(0L, currentTime)
        assertEquals(0, fake.registrationAttemptCount, "no source was asked once the answer was known")
        assertEquals(0, fake.subscriberCount)
    }

    @Test
    fun `a refused service subscription does not fail the call`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        val backend = ScriptedBackend(fake)
        backend.subscribeFailure = IllegalStateException("receiver registration failed")
        val provider: LocationProvider = providerOver(backend)
        val call: Deferred<LocationReading> = async { provider.getCurrentLocation(oneShot(source = AndroidLocationSource.GPS)) }
        runCurrent()
        assertEquals(1, fake.registeredCount, "control: the call went on without the subscription")

        fake.deliver(gps, fake.locationAt(gps, 41.0))
        runCurrent()

        assertEquals(GeoCoordinates(41.0, 2.0), call.await().fixOrNull()?.coordinates)
        assertEquals(0, fake.registeredCount)
    }

    // ---------------------------------------------------------------------------------------------
    // Conflation at the end of the stream (loc-ios breaker round 2, M1, checked on Android)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a busy collector skips the intermediate readings and ends on the newest`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps))
        val provider: LocationProvider = providerOver(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val release = CompletableDeferred<Unit>()
        val job: Job = backgroundScope.launch {
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { reading: LocationReading ->
                seen += reading
                if (seen.size == 1) release.await()
            }
        }
        runCurrent()
        assertEquals(listOf(noFix), seen, "control: the collector is busy with the seed")
        for (step: Int in 10 until 30) fake.deliver(gps, fake.locationAt(gps, step.toDouble()))
        runCurrent()
        assertEquals(1, seen.size, "control: still busy, nothing else was handed over")

        release.complete(Unit)
        runCurrent()

        assertEquals(2, seen.size, "a busy collector must skip intermediate readings (observeLocation is conflated): $seen")
        assertEquals(GeoCoordinates(29.0, 2.0), seen.last().fixOrNull()?.coordinates, "and end on the newest: $seen")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
    }

    @Test
    fun `a terminal reading is never conflated away from a busy collector and the stream still completes`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        fake.refuse(gps)
        fake.refuse(network)
        val provider: LocationProvider = providerOver(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { reading: LocationReading -> seen += reading }
        }
        runCurrent()

        assertTrue(job.isCompleted, "the stream completes after PERMISSION_MISSING")
        assertEquals(LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING), seen.last(), "$seen")
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // m1 / m3: platform failures
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a platform failure of the enabled-state read answers NO_FIX instead of throwing`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        val backend = ScriptedBackend(fake)
        backend.enabledFailure = IllegalStateException("system server died")
        val provider: LocationProvider = providerOver(backend)

        val outcome: Result<LocationReading> = runCatching { provider.getCurrentLocation(oneShot(timeout = 1.seconds)) }

        assertEquals(noFix, outcome.getOrNull(), "never throws for platform errors, got ${outcome.exceptionOrNull()}")
        assertEquals(0, fake.registeredCount)
    }

    @Test
    fun `cancelling the caller of a one-shot call is not swallowed by the platform catch`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        val provider: LocationProvider = providerOver(fake)
        val call: Deferred<LocationReading> = async { provider.getCurrentLocation(oneShot(timeout = 30.seconds)) }
        runCurrent()
        assertEquals(2, fake.registeredCount, "control: waiting")

        call.cancelAndJoin()

        assertTrue(call.isCancelled, "the broad platform catch must not swallow the caller's cancellation")
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    @Test
    fun `a failing removal after the first fresh fix still returns the fix`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        val provider: LocationProvider = providerOver(fake)
        val call: Deferred<LocationReading> = async { provider.getCurrentLocation(oneShot(source = AndroidLocationSource.GPS)) }
        runCurrent()
        assertEquals(1, fake.registeredCount, "control: registered")
        fake.removalFailure = IllegalStateException("system server died")

        fake.deliver(gps, fake.locationAt(gps, 41.0))
        runCurrent()

        assertEquals(GeoCoordinates(41.0, 2.0), call.await().fixOrNull()?.coordinates)
    }

    @Test
    fun `a failing cancel of the framework one-shot request is not thrown into the coroutine machinery`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(supportsCurrentLocation = true)
        val backend = ScriptedBackend(fake)
        backend.cancelFailure = IllegalStateException("cancel failed")
        val provider: LocationProvider = providerOver(backend)
        val call: Deferred<LocationReading> = async { provider.getCurrentLocation(oneShot(source = AndroidLocationSource.GPS)) }
        runCurrent()
        assertEquals(1, fake.currentCalls().size, "control: the framework call is pending")

        call.cancelAndJoin()

        assertTrue(fake.currentCalls()[0].cancelled, "the framework call was cancelled")
        // runTest reports an exception thrown from a cancellation handler after the body.
    }

    private companion object {
        const val STORM_STEPS: Int = 300
    }
}
