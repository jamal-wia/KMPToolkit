package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * A [FakeLocationBackend] seen through the same seam, plus two start gates: a latch that opens once the
 * expected number of `requestUpdates` calls has completed, and one for the framework one-shot calls, so
 * the storm starts exactly when every call is registered, without waiting on a timer.
 */
private class StormGateBackend(
    private val inner: FakeLocationBackend,
    expectedRegistrations: Int,
    expectedFrameworkCalls: Int,
) : LocationBackend by inner {

    val registrationGate = CountDownLatch(expectedRegistrations)
    val frameworkGate = CountDownLatch(expectedFrameworkCalls)

    override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
        inner.requestUpdates(provider, request, listener)
        registrationGate.countDown()
    }

    override fun requestCurrentLocation(
        provider: String,
        accuracy: LocationAccuracy,
        onResult: (Location?) -> Unit,
    ): (() -> Unit)? {
        val cancel: (() -> Unit)? = inner.requestCurrentLocation(provider, accuracy, onResult)
        frameworkGate.countDown()
        return cancel
    }
}

/**
 * Defect class: races between a delivery, a cancellation and a framework answer in the one-shot call.
 * Many concurrent `getCurrentLocation` calls run on real threads while fixes arrive from other threads
 * and callers are cancelled at seeded random moments. Every call must end with a fix or a cancellation
 * and never throw (a second resume of a continuation would), and nothing may stay registered or pending
 * with the platform afterwards. Control assertions prove that the registrations existed before the
 * storm began. Real-thread waits are bounded by latch awaits and a [withTimeout] that is only a hang
 * bound; nothing sleeps or polls.
 */
@RunWith(AndroidJUnit4::class)
class GetCurrentLocationStormTest {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
    )

    @Test
    fun `sixty concurrent calls each take a fix delivered from two threads and leave no listener`() = runBlocking {
        val inner = FakeLocationBackend()
        val gate = StormGateBackend(inner, expectedRegistrations = CALLS * 2, expectedFrameworkCalls = 0)
        val provider: LocationProvider = providerOver(gate)
        withTimeout(HANG_BOUND_MILLIS) {
            val calls: List<Deferred<LocationReading>> = (1..CALLS).map {
                async(Dispatchers.Default) { provider.getCurrentLocation(CurrentLocationOptions(timeout = Duration.INFINITE)) }
            }
            assertTrue(gate.registrationGate.await(LATCH_SECONDS, TimeUnit.SECONDS), "every call must register both sources")
            assertEquals(CALLS * 2, inner.registeredCount, "control: every call is registered on both sources before any fix")
            val listeners: List<LocationListener> =
                inner.registered().map { registration: FakeLocationBackend.Registration -> registration.listener }
            val gpsFix: Location = inner.locationAt(gps, 10.0)
            val networkFix: Location = inner.locationAt(network, 20.0)

            listOf(
                async(Dispatchers.Default) { inner.deliver(gps, gpsFix) },
                async(Dispatchers.Default) { inner.deliver(network, networkFix) },
            ).awaitAll()
            val results: List<LocationReading> = calls.awaitAll()

            for (result: LocationReading in results) {
                val latitude: Double = assertNotNull(result.fixOrNull(), "every call must take a fix, got $result").coordinates.latitude
                assertTrue(latitude == 10.0 || latitude == 20.0, "a call answered a position nobody delivered: $latitude")
            }
            assertEquals(0, inner.registeredCount, "a single-shot listener is still registered")
            // A callback the platform had already queued when the listeners were removed must stay harmless.
            val late: Location = inner.locationAt(gps, 30.0)
            for (listener: LocationListener in listeners) listener.onLocationChanged(late)
            assertEquals(0, inner.registeredCount, "a late callback must not bring a registration back")
        }
    }

    @Test
    fun `calls cancelled at seeded random moments while fixes arrive leave nothing behind and never throw`() = runBlocking {
        withTimeout(HANG_BOUND_MILLIS) {
            for (seed: Int in SEEDS) cancelStorm(seed)
        }
    }

    @Test
    fun `framework answers racing with cancellations leave nothing registered or pending`() = runBlocking {
        withTimeout(HANG_BOUND_MILLIS) {
            for (seed: Int in SEEDS) frameworkStorm(seed)
        }
    }

    private suspend fun cancelStorm(seed: Int = DEFAULT_SEED) {
        val random = Random(seed)
        val inner = FakeLocationBackend()
        val gate = StormGateBackend(inner, expectedRegistrations = STORM_CALLS * 2, expectedFrameworkCalls = 0)
        val provider: LocationProvider = providerOver(gate)
        val failures = CopyOnWriteArrayList<Throwable>()
        val completed = CopyOnWriteArrayList<LocationReading>()
        coroutineScope {
            val jobs: List<Job> = (1..STORM_CALLS).map {
                launch(Dispatchers.Default) {
                    try {
                        completed += provider.getCurrentLocation(CurrentLocationOptions(timeout = Duration.INFINITE))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            }
            assertTrue(gate.registrationGate.await(LATCH_SECONDS, TimeUnit.SECONDS), "seed=$seed: every call must register both sources")
            assertEquals(STORM_CALLS * 2, inner.registeredCount, "seed=$seed: control: all calls are registered before the storm")
            repeat(STORM_STEPS) { step: Int ->
                when (random.nextInt(3)) {
                    0 -> inner.deliver(gps, inner.locationAt(gps, step.toDouble()))
                    1 -> jobs[random.nextInt(jobs.size)].cancel()
                    else -> inner.deliver(network, inner.locationAt(network, 40.0 + step))
                }
            }
            for (job: Job in jobs) job.cancelAndJoin()
        }
        assertEquals(emptyList(), failures.map { it.toString() }, "seed=$seed: a call threw")
        assertTrue(
            completed.all { reading: LocationReading -> reading is LocationReading.Available },
            "seed=$seed: a call that finished without being cancelled must hold a fix, got $completed",
        )
        assertEquals(0, inner.registeredCount, "seed=$seed: a listener was left registered")
        assertEquals(0, inner.subscriberCount, "seed=$seed: a service subscription was left")
    }

    private suspend fun frameworkStorm(seed: Int = DEFAULT_SEED) {
        val random = Random(seed)
        val inner = FakeLocationBackend(supportsCurrentLocation = true)
        val gate = StormGateBackend(inner, expectedRegistrations = 0, expectedFrameworkCalls = STORM_CALLS * 2)
        val provider: LocationProvider = providerOver(gate)
        val failures = CopyOnWriteArrayList<Throwable>()
        val answered: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()
        coroutineScope {
            val jobs: List<Job> = (1..STORM_CALLS).map {
                launch(Dispatchers.Default) {
                    try {
                        provider.getCurrentLocation(CurrentLocationOptions(timeout = Duration.INFINITE))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            }
            assertTrue(gate.frameworkGate.await(LATCH_SECONDS, TimeUnit.SECONDS), "seed=$seed: every call must start its framework calls")
            assertEquals(STORM_CALLS * 2, inner.currentCalls().size, "seed=$seed: control: every call asked the framework on both sources")
            repeat(STORM_STEPS) { step: Int ->
                when (random.nextInt(3)) {
                    0 -> {
                        val index: Int = random.nextInt(STORM_CALLS * 2)
                        val source: String = inner.currentCalls()[index].provider
                        answered += index
                        // A framework answer is a fix or empty; the same call may even be answered twice.
                        inner.answerCurrentCall(index, if (random.nextBoolean()) inner.locationAt(source, step.toDouble()) else null)
                    }
                    1 -> jobs[random.nextInt(jobs.size)].cancel()
                    else -> {
                        val source: String = if (random.nextBoolean()) gps else network
                        inner.deliver(source, inner.locationAt(source, 40.0 + step))
                    }
                }
            }
            for (job: Job in jobs) job.cancelAndJoin()
        }
        assertEquals(emptyList(), failures.map { it.toString() }, "seed=$seed: a call threw")
        assertEquals(0, inner.registeredCount, "seed=$seed: a listener was left registered")
        val pending: List<Int> = inner.currentCalls().withIndex()
            .filter { indexed: IndexedValue<FakeLocationBackend.CurrentCall> -> !indexed.value.cancelled && indexed.index !in answered }
            .map { indexed: IndexedValue<FakeLocationBackend.CurrentCall> -> indexed.index }
        assertEquals(emptyList(), pending, "seed=$seed: a framework call is neither answered nor cancelled")
    }

    private companion object {
        const val DEFAULT_SEED: Int = 7
        val SEEDS: List<Int> = listOf(DEFAULT_SEED, 11, 12, 13, 14)
        const val CALLS: Int = 60
        const val STORM_CALLS: Int = 30
        const val STORM_STEPS: Int = 60
        const val LATCH_SECONDS: Long = 30
        const val HANG_BOUND_MILLIS: Long = 60_000
    }
}
