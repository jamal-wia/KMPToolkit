package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Defect class: a race between starting, finishing and cancelling many calls from many real threads.
 * Streams and one-shot calls (answered from the cache, answered by a fresh fix, cancelled mid-flight)
 * are started and cancelled from eight `Dispatchers.Default` workers while a simulated main looper (one
 * extra thread) delivers fixes after every registration and, in the second test, a plain thread flips a
 * provider and the device switch (`Random(seed)`, every message names `seed=$seed`). Nothing may stay
 * registered or subscribed (`registeredCount` and `subscriberCount` are zero after a control that
 * registrations did happen), every completed one-shot call must answer with a fix or, only while the
 * service is flapping, `SERVICE_DISABLED`, and a collection started afterwards must still be seeded,
 * served, switched off and reseeded. The fake is thread-safe; the waits are latches and flow values
 * with a real-time bound, the `withTimeout` is a hang bound only. No sleeps, no wall clock.
 */
@RunWith(AndroidJUnit4::class)
class LocationThreadStormTest {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    /** HIGH_ACCURACY registers gps and network: two listeners and one service subscription per collection. */
    private val twoProviders = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)

    /**
     * The fake plus the platform's behaviour the storm needs: after every registration a simulated main
     * looper delivers a fix to that provider's listeners, and the high-water mark of live registrations
     * and every service subscription are recorded.
     */
    private class StormBackend(val fake: FakeLocationBackend, private val looper: ExecutorService) : LocationBackend by fake {
        @Volatile var autoDeliver: Boolean = true
        val peakRegistered = AtomicInteger(0)
        val subscriptions = Semaphore(0)

        override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
            fake.requestUpdates(provider, request, listener)
            peakRegistered.accumulateAndGet(fake.registeredCount) { current: Int, seen: Int -> maxOf(current, seen) }
            if (autoDeliver) looper.execute { fake.deliver(provider, fake.locationAt(provider, STORM_LATITUDE)) }
        }

        override fun observeServiceChanges(onChange: () -> Unit): () -> Unit {
            val unsubscribe: () -> Unit = fake.observeServiceChanges(onChange)
            subscriptions.release()
            return unsubscribe
        }
    }

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
    )

    private fun LocationReading.latitudeOrNull(): Double? = fixOrNull()?.coordinates?.latitude

    private fun runStorm(seed: Int, flaps: Boolean): Unit = runBlocking {
        val fake = FakeLocationBackend()
        fake.setCached(gps, fake.locationAt(gps, STORM_LATITUDE))
        val looper: ExecutorService = Executors.newSingleThreadExecutor()
        val storm = StormBackend(fake, looper)
        val provider: LocationProvider = providerOver(storm)
        val freshOptions = CurrentLocationOptions(maxAge = Duration.ZERO)
        val cachedOptions = CurrentLocationOptions(maxAge = Duration.INFINITE)
        val results = ConcurrentLinkedQueue<LocationReading>()
        var expectedResults = 0
        for (w: Int in 0 until WORKERS) for (i: Int in 0 until OPERATIONS) if ((w + i) % 5 in 2..3) expectedResults++

        withTimeout(HANG_BOUND_MS) {
            val startGate = CompletableDeferred<Unit>()
            val workers: List<Deferred<Unit>> = List(WORKERS) { w: Int ->
                async(Dispatchers.Default) {
                    startGate.await()
                    repeat(OPERATIONS) { i: Int ->
                        when ((w + i) % 5) {
                            // A stream cancelled at once: the cancel races the registration.
                            0 -> launch { provider.observeLocation(twoProviders).collect { } }.cancelAndJoin()
                            // A stream cancelled after its first reading.
                            1 -> {
                                val first = CompletableDeferred<Unit>()
                                val job = launch { provider.observeLocation(twoProviders).collect { first.complete(Unit) } }
                                first.await()
                                job.cancelAndJoin()
                            }
                            // A one-shot call answered by the platform's fresh fix, run to completion.
                            2 -> results += provider.getCurrentLocation(freshOptions)
                            // A one-shot call answered from the cache.
                            3 -> results += provider.getCurrentLocation(cachedOptions)
                            // A fresh one-shot call cancelled mid-flight.
                            else -> launch { provider.getCurrentLocation(freshOptions) }.cancelAndJoin()
                        }
                    }
                }
            }
            val flapper: Thread? = if (flaps) {
                val random = Random(seed)
                thread {
                    repeat(FLAPS) {
                        // gps stays on, so a one-shot call always has a source; the network source and the
                        // device switch flap against the registrations in flight.
                        if (random.nextBoolean()) {
                            fake.setProviderEnabled(network, random.nextBoolean())
                        } else {
                            fake.setDeviceLocationEnabled(random.nextBoolean())
                        }
                    }
                }
            } else {
                null
            }
            startGate.complete(Unit)
            workers.awaitAll()
            flapper?.join(60_000)
            assertTrue(flapper?.isAlive != true, "seed=$seed: the flapper thread hung")

            storm.autoDeliver = false
            looper.shutdown()
            assertTrue(looper.awaitTermination(30, TimeUnit.SECONDS), "seed=$seed: the simulated looper did not drain")
            fake.setProviderEnabled(network, true)
            fake.setDeviceLocationEnabled(true)

            assertTrue(storm.peakRegistered.get() > 0, "seed=$seed: control, the storm must have registered listeners")
            assertTrue(fake.registrationAttemptCount > 0, "seed=$seed: control, the storm must have asked the platform")
            assertEquals(0, fake.registeredCount, "seed=$seed: listeners left registered after the storm")
            assertEquals(0, fake.subscriberCount, "seed=$seed: service subscriptions left after the storm")

            assertEquals(expectedResults, results.size, "seed=$seed: a one-shot call that was not cancelled did not answer")
            val answers: List<LocationReading> = results.toList()
            for (answer: LocationReading in answers) {
                val fix: Boolean = answer.latitudeOrNull() == STORM_LATITUDE
                val disabled: Boolean = answer == LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
                assertTrue(
                    fix || (flaps && disabled),
                    "seed=$seed: unexpected answer $answer (flaps=$flaps)",
                )
            }
            if (!flaps) {
                assertTrue(answers.all { it.latitudeOrNull() == STORM_LATITUDE }, "seed=$seed: every call must answer with a fix when nothing flaps")
            }

            // Re-init after the storm: the same provider seeds, serves, switches off and reseeds a new collection.
            fake.setCached(gps, fake.locationAt(gps, AFTER_LATITUDE))
            storm.subscriptions.drainPermits()
            val shown = MutableStateFlow<LocationReading?>(null)
            val job = launch(Dispatchers.Default) {
                provider.observeLocation(twoProviders).collect { reading: LocationReading -> shown.value = reading }
            }
            assertNotNull(
                withTimeoutOrNull(20_000) { shown.first { it?.latitudeOrNull() == AFTER_LATITUDE } },
                "seed=$seed: the collection after the storm was not seeded",
            )
            assertTrue(storm.subscriptions.tryAcquire(20, TimeUnit.SECONDS), "seed=$seed: the collection after the storm never subscribed")
            assertEquals(2, fake.registeredCount, "seed=$seed: control, the collection after the storm holds gps and network")

            fake.deliver(gps, fake.locationAt(gps, AFTER_LATITUDE + 1.0))
            assertNotNull(
                withTimeoutOrNull(20_000) { shown.first { it?.latitudeOrNull() == AFTER_LATITUDE + 1.0 } },
                "seed=$seed: the collection after the storm was not served",
            )
            fake.setDeviceLocationEnabled(false)
            assertNotNull(
                withTimeoutOrNull(20_000) { shown.first { it == LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED) } },
                "seed=$seed: switching the service off was not reported after the storm",
            )
            fake.setDeviceLocationEnabled(true)
            assertNotNull(
                withTimeoutOrNull(20_000) { shown.first { it?.latitudeOrNull() == AFTER_LATITUDE } },
                "seed=$seed: switching the service back on did not reseed after the storm",
            )
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount, "seed=$seed: the collection after the storm left a listener")
            assertEquals(0, fake.subscriberCount, "seed=$seed: the collection after the storm left a subscription")
        }
    }

    @Test
    fun `start and stop storms of streams and one-shot calls on real threads leave nothing registered`() =
        runStorm(seed = 101, flaps = false)

    @Test
    fun `the same storm with a provider and the device switch flapping leaves nothing registered`() =
        runStorm(seed = 202, flaps = true)

    private companion object {
        const val WORKERS: Int = 8
        const val OPERATIONS: Int = 40
        const val FLAPS: Int = 3_000
        const val STORM_LATITUDE: Double = 42.0
        const val AFTER_LATITUDE: Double = 77.0

        /** A hang bound only: nothing waits on it, a healthy run is far below it. */
        const val HANG_BOUND_MS: Long = 60_000
    }
}
