package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Defect class: collectors of one provider interfering with each other. Every collection owns its own
 * listeners, its own service subscription and its own seed, so two hundred of them at once must each be
 * served, and cancelling any subset must take exactly that subset's registrations (`registeredCount`,
 * `subscriberCount`) and leave the others live and served, down to zero when the rest end. A mix of
 * accuracy levels and named sources checks that a cancel never removes a registration of another kind.
 * Real-thread and virtual-time variants; no sleeps, no wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ObserveLocationManyCollectorsTest {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    /** HIGH_ACCURACY registers gps and network: two listeners and one service subscription per collection. */
    private val twoProviders = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)

    /** The fake plus a hook that fires after every service subscription (the last step of a registration). */
    private class CountingBackend(val fake: FakeLocationBackend) : LocationBackend by fake {
        @Volatile var onSubscribed: () -> Unit = {}

        override fun observeServiceChanges(onChange: () -> Unit): () -> Unit {
            val unsubscribe: () -> Unit = fake.observeServiceChanges(onChange)
            onSubscribed()
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

    @Test
    fun `two hundred collectors on real threads are served and cancelled independently`() = runBlocking {
        withTimeout(120_000) {
            val counting = CountingBackend(FakeLocationBackend())
            val backend: FakeLocationBackend = counting.fake
            val allSubscribed = CountDownLatch(COLLECTORS)
            counting.onSubscribed = { allSubscribed.countDown() }
            val provider: LocationProvider = providerOver(counting)
            val last: List<AtomicReference<Double?>> = List(COLLECTORS) { AtomicReference<Double?>(null) }
            val servedFirst: List<CompletableDeferred<Unit>> = List(COLLECTORS) { CompletableDeferred<Unit>() }
            val servedSecond: List<CompletableDeferred<Unit>> = List(COLLECTORS) { CompletableDeferred<Unit>() }
            val jobs: List<Job> = (0 until COLLECTORS).map { i: Int ->
                launch(Dispatchers.Default) {
                    provider.observeLocation(twoProviders).collect { reading: LocationReading ->
                        val latitude: Double? = reading.latitudeOrNull()
                        last[i].set(latitude)
                        if (latitude == 1.0) servedFirst[i].complete(Unit)
                        if (latitude == 2.0) servedSecond[i].complete(Unit)
                    }
                }
            }
            withContext(Dispatchers.IO) {
                assertTrue(allSubscribed.await(30, TimeUnit.SECONDS), "not every collector finished registering")
            }
            assertEquals(2 * COLLECTORS, backend.registeredCount, "control: every collector holds gps and network")
            assertEquals(COLLECTORS, backend.subscriberCount, "control: every collector holds a service subscription")

            backend.deliver(gps, backend.locationAt(gps, 1.0))
            servedFirst.awaitAll()

            val cancelled: List<Job> = jobs.filterIndexed { i: Int, _: Job -> i % 2 == 0 }
            cancelled.forEach { job: Job -> job.cancelAndJoin() }
            assertEquals(COLLECTORS, backend.registeredCount, "cancelling half must take exactly their own listeners")
            assertEquals(COLLECTORS / 2, backend.subscriberCount, "cancelling half must take exactly their own subscriptions")

            backend.deliver(gps, backend.locationAt(gps, 2.0))
            (0 until COLLECTORS).filter { it % 2 == 1 }.forEach { i: Int -> servedSecond[i].await() }
            assertTrue(
                (0 until COLLECTORS).filter { it % 2 == 0 }.all { last[it].get() == 1.0 },
                "a cancelled collector still received a fix",
            )

            val rest: List<Job> = jobs.filterIndexed { i: Int, _: Job -> i % 2 == 1 }
            rest.forEach { job: Job -> job.cancel() }
            rest.joinAll()
            assertEquals(0, backend.registeredCount)
            assertEquals(0, backend.subscriberCount)
        }
    }

    @Test
    fun `two hundred collectors in virtual time are served and cancelled independently`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        val provider: LocationProvider = newProvider(backend, testScheduler.timeSource)
        val last: Array<LocationReading?> = arrayOfNulls(COLLECTORS)
        val jobs: List<Job> = (0 until COLLECTORS).map { i: Int ->
            launch { provider.observeLocation(twoProviders).collect { reading: LocationReading -> last[i] = reading } }
        }
        runCurrent()
        assertEquals(2 * COLLECTORS, backend.registeredCount, "control: every collector holds gps and network")
        assertEquals(COLLECTORS, backend.subscriberCount, "control: every collector holds a service subscription")
        assertTrue(last.all { it == LocationReading.Unavailable(LocationUnavailability.NO_FIX) }, "every collector starts on its own seed")

        backend.deliver(gps, backend.locationAt(gps, 1.0))
        runCurrent()
        assertTrue(last.all { it?.latitudeOrNull() == 1.0 }, "every collector must be served the fix")

        jobs.filterIndexed { i: Int, _: Job -> i % 2 == 0 }.forEach { job: Job -> job.cancelAndJoin() }
        assertEquals(COLLECTORS, backend.registeredCount, "cancelling half must take exactly their own listeners")
        assertEquals(COLLECTORS / 2, backend.subscriberCount, "cancelling half must take exactly their own subscriptions")
        backend.deliver(network, backend.locationAt(network, 2.0))
        runCurrent()
        assertTrue((0 until COLLECTORS).filter { it % 2 == 1 }.all { last[it]?.latitudeOrNull() == 2.0 }, "the live half must be served")
        assertTrue((0 until COLLECTORS).filter { it % 2 == 0 }.all { last[it]?.latitudeOrNull() == 1.0 }, "a cancelled collector still received")

        jobs.filterIndexed { i: Int, _: Job -> i % 2 == 1 }.forEach { job: Job -> job.cancelAndJoin() }
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)

        // Re-init: a collector started after all of them ended is served from its own fresh registration.
        val seen: MutableList<LocationReading> = mutableListOf()
        val again = launch { provider.observeLocation(twoProviders).collect { seen += it } }
        runCurrent()
        assertEquals(2, backend.registeredCount, "control: a new collection registers afresh")
        backend.deliver(gps, backend.locationAt(gps, 3.0))
        runCurrent()
        assertEquals(3.0, seen.last().latitudeOrNull())
        again.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `collectors of different accuracy and source each release exactly their own registrations`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        val provider: LocationProvider = newProvider(backend, testScheduler.timeSource)
        val perKind = 10
        val highOptions: LocationUpdatesOptions = twoProviders
        val lowOptions = LocationUpdatesOptions(accuracy = LocationAccuracy.LOW_POWER)
        val gpsOnlyOptions = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS))
        val highLast: Array<Double?> = arrayOfNulls(perKind)
        val lowLast: Array<Double?> = arrayOfNulls(perKind)
        val gpsLast: Array<Double?> = arrayOfNulls(perKind)
        fun start(options: LocationUpdatesOptions, into: Array<Double?>, index: Int): Job = launch {
            provider.observeLocation(options).collect { reading: LocationReading -> into[index] = reading.latitudeOrNull() }
        }
        val high: List<Job> = List(perKind) { start(highOptions, highLast, it) }
        val low: List<Job> = List(perKind) { start(lowOptions, lowLast, it) }
        val gpsOnly: List<Job> = List(perKind) { start(gpsOnlyOptions, gpsLast, it) }
        runCurrent()
        assertEquals(perKind * (2 + 1 + 1), backend.registeredCount, "control: high holds two, low holds network, gps-only holds gps")
        assertEquals(3 * perKind, backend.subscriberCount, "control: one service subscription per collector")
        assertEquals(2 * perKind, backend.registered().count { it.provider == gps }, "control: gps listeners of the high and gps-only collectors")
        assertEquals(2 * perKind, backend.registered().count { it.provider == network }, "control: network listeners of the high and low collectors")

        // Network reaches high and low, never the gps-only collectors; gps reaches high and gps-only.
        backend.deliver(network, backend.locationAt(network, 1.0))
        backend.deliver(gps, backend.locationAt(gps, 2.0))
        runCurrent()
        assertTrue(highLast.all { it == 2.0 }, "high collectors take the newest fix of either provider: ${highLast.toList()}")
        assertTrue(lowLast.all { it == 1.0 }, "low-power collectors hear the network only: ${lowLast.toList()}")
        assertTrue(gpsLast.all { it == 2.0 }, "gps-only collectors hear gps only: ${gpsLast.toList()}")

        high.forEach { job: Job -> job.cancelAndJoin() }
        assertEquals(2 * perKind, backend.registeredCount, "cancelling the high collectors takes their two listeners each")
        assertEquals(2 * perKind, backend.subscriberCount)
        backend.deliver(network, backend.locationAt(network, 3.0))
        backend.deliver(gps, backend.locationAt(gps, 4.0))
        runCurrent()
        assertTrue(lowLast.all { it == 3.0 }, "the low-power collectors must survive the cancel of another kind")
        assertTrue(gpsLast.all { it == 4.0 }, "the gps-only collectors must survive the cancel of another kind")
        assertTrue(highLast.all { it == 2.0 }, "a cancelled collector still received")

        low.forEach { job: Job -> job.cancelAndJoin() }
        assertEquals(perKind, backend.registeredCount)
        assertEquals(perKind, backend.subscriberCount)
        gpsOnly.forEach { job: Job -> job.cancelAndJoin() }
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    private companion object {
        const val COLLECTORS: Int = 200
    }
}
