package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Defect class: a leaked registration under churn. `observeLocation()` is cold and per-collection, so
 * many collect/cancel cycles, a cancel that lands while the second provider is still registering, a
 * listener the platform calls after the collection ended, a slow collector and a live fix racing the
 * seed must each leave nothing registered (`registeredCount`, `subscriberCount` back to zero, after a
 * control that they were above zero) and must never leave the collector on an older position than the
 * newest one it was sent. Real-thread tests use latches with a real-time bound and flow values as
 * their barriers; the virtual-time tests use `runCurrent`. No sleeps, no wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ObserveLocationChurnTest {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    /** HIGH_ACCURACY registers gps and network: two listeners and one service subscription per collection. */
    private val twoProviders = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)

    /** The fake with two test hooks: one before a registration is recorded, one after a service subscription. */
    private class HookedBackend(val fake: FakeLocationBackend) : LocationBackend by fake {
        @Volatile var onBeforeRegister: (String, LocationListener) -> Unit = { _, _ -> }
        @Volatile var onSubscribed: () -> Unit = {}

        override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
            onBeforeRegister(provider, listener)
            fake.requestUpdates(provider, request, listener)
        }

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
    fun `two hundred collect and cancel cycles on real threads leave nothing registered`() = runBlocking {
        withTimeout(HANG_BOUND_MS) {
            val hooked = HookedBackend(FakeLocationBackend())
            val provider: LocationProvider = providerOver(hooked)
            repeat(200) { cycle: Int ->
                val subscribed = CountDownLatch(1)
                hooked.onSubscribed = { subscribed.countDown() }
                val job = launch(Dispatchers.Default) { provider.observeLocation(twoProviders).collect { } }
                withContext(Dispatchers.IO) {
                    assertTrue(subscribed.await(20, TimeUnit.SECONDS), "cycle $cycle: the collection never finished registering")
                }
                assertEquals(2, hooked.fake.registeredCount, "cycle $cycle: control, the collection must hold gps and network")
                assertEquals(1, hooked.fake.subscriberCount, "cycle $cycle: control, the collection must hold its service subscription")

                job.cancelAndJoin()

                assertEquals(0, hooked.fake.registeredCount, "cycle $cycle: a listener is still registered")
                assertEquals(0, hooked.fake.subscriberCount, "cycle $cycle: a service subscription is still live")
            }
        }
    }

    @Test
    fun `a cancel that lands while the second provider is registering still removes both listeners`() = runBlocking {
        withTimeout(HANG_BOUND_MS) {
            repeat(20) { cycle: Int ->
                val entered = CountDownLatch(1)
                val proceed = CountDownLatch(1)
                val hooked = HookedBackend(FakeLocationBackend())
                hooked.onBeforeRegister = { provider: String, _: LocationListener ->
                    if (provider == network) {
                        entered.countDown()
                        check(proceed.await(20, TimeUnit.SECONDS)) { "cycle $cycle: the test never released the registration" }
                    }
                }
                val job = launch(Dispatchers.Default) { providerOver(hooked).observeLocation(twoProviders).collect { } }
                withContext(Dispatchers.IO) {
                    assertTrue(entered.await(20, TimeUnit.SECONDS), "cycle $cycle: the second provider was never reached")
                }
                assertEquals(1, hooked.fake.registeredCount, "cycle $cycle: control, gps is registered while network is held")

                // The cancel is recorded before the gate opens, so the order is fixed without any waiting.
                job.cancel()
                proceed.countDown()
                job.join()

                assertEquals(0, hooked.fake.registeredCount, "cycle $cycle: listeners leaked after a cancel during registration")
                assertEquals(0, hooked.fake.subscriberCount, "cycle $cycle: a service subscription leaked")
            }
        }
    }

    @Test
    fun `a listener that fires after the collection ended is harmless and a new collection works`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        val provider: LocationProvider = newProvider(backend, testScheduler.timeSource)
        val seen: MutableList<LocationReading> = mutableListOf()
        val first = launch { provider.observeLocation(twoProviders).collect { seen += it } }
        runCurrent()
        val stale: List<FakeLocationBackend.Registration> = backend.registered()
        assertEquals(2, stale.size, "control: both listeners must exist before the collection ends")
        assertEquals(1, backend.subscriberCount, "control: the subscription must exist before the collection ends")
        assertEquals(listOf<LocationReading>(LocationReading.Unavailable(LocationUnavailability.NO_FIX)), seen)

        first.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
        val seenAtEnd: List<LocationReading> = seen.toList()

        // Every source goes off, then the platform calls the stale listeners anyway: they must neither throw
        // nor reach the finished collector (the disabled transition would send into a closed channel).
        backend.setProviderEnabled(gps, false)
        backend.setProviderEnabled(network, false)
        for (registration: FakeLocationBackend.Registration in stale) {
            registration.listener.onLocationChanged(backend.locationAt(registration.provider, 5.0))
            registration.listener.onProviderDisabled(registration.provider)
            registration.listener.onProviderEnabled(registration.provider)
        }
        runCurrent()
        assertEquals(seenAtEnd, seen, "a finished collection must receive nothing more")
        assertEquals(0, backend.registeredCount, "a stale callback must not register anything")

        // Re-init: a second collection after the first ended starts from its own seed and is served.
        backend.setProviderEnabled(gps, true)
        backend.setProviderEnabled(network, true)
        backend.setCached(gps, backend.locationAt(gps, 9.0))
        val again: MutableList<LocationReading> = mutableListOf()
        val second = launch { provider.observeLocation(twoProviders).collect { again += it } }
        runCurrent()
        assertEquals(9.0, again.lastOrNull()?.latitudeOrNull(), "the second collection must seed from the cache")
        assertEquals(2, backend.registeredCount, "control: the second collection registers afresh")
        backend.deliver(gps, backend.locationAt(gps, 10.0))
        runCurrent()
        assertEquals(10.0, again.last().latitudeOrNull())
        assertEquals(seenAtEnd, seen, "the first collection must stay finished")

        second.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    @Test
    fun `two hundred rounds with the live fix delivered from another thread never end on the older seed`() = runBlocking {
        withTimeout(HANG_BOUND_MS) {
            repeat(200) { round: Int ->
                val hooked = HookedBackend(FakeLocationBackend())
                hooked.fake.setCached(gps, hooked.fake.locationAt(gps, 1.0, ageMillis = 1_000))
                val newer = hooked.fake.locationAt(gps, 2.0)
                val sentinel = hooked.fake.locationAt(gps, 3.0)
                val gpsListener = CompletableDeferred<LocationListener>()
                val delivered = CountDownLatch(1)
                hooked.onBeforeRegister = { provider: String, listener: LocationListener ->
                    if (provider == gps) {
                        // The live fix arrives from another thread while the registration is in progress.
                        val platformThread: Thread = thread { listener.onLocationChanged(newer) }
                        platformThread.join(20_000)
                        check(!platformThread.isAlive) { "round $round: the delivering thread hung" }
                        gpsListener.complete(listener)
                        delivered.countDown()
                    }
                }
                // The flow is conflated by contract: a collector that has not taken the seed when the live fix
                // arrives legitimately sees only the live fix. The property is the order and where it ends.
                val last = MutableStateFlow<Double?>(null)
                val seen: MutableList<Double?> = Collections.synchronizedList(mutableListOf())
                val job = launch(Dispatchers.Default) {
                    providerOver(hooked).observeLocation(twoProviders).collect { reading: LocationReading ->
                        seen += reading.latitudeOrNull()
                        last.value = reading.latitudeOrNull()
                    }
                }
                withContext(Dispatchers.IO) { assertTrue(delivered.await(20, TimeUnit.SECONDS), "round $round: no delivery") }
                assertNotNull(withTimeoutOrNull(20_000) { last.first { it == 2.0 } }, "round $round: the live fix never arrived")

                // A barrier instead of a sleep: a fix sent after the live one reaches the collector after anything
                // that was already queued, so a wrongly ordered older seed would have shown up before it.
                gpsListener.await().onLocationChanged(sentinel)
                assertNotNull(withTimeoutOrNull(20_000) { last.first { it == 3.0 } }, "round $round: the barrier fix never arrived")
                job.cancelAndJoin()

                val ordered: List<Double?> = seen.toList()
                assertTrue(
                    ordered.zipWithNext().all { (before: Double?, after: Double?) -> before != null && after != null && before < after },
                    "round $round: the collector saw $ordered, an older position must never follow a newer one",
                )
                assertEquals(3.0, ordered.last(), "round $round: ended on an older position")
                assertEquals(0, hooked.fake.registeredCount, "round $round: a listener is left")
                assertEquals(0, hooked.fake.subscriberCount, "round $round: a subscription is left")
            }
        }
    }

    @Test
    fun `a collector that was busy ends on the newest fix not on the last one that fitted a buffer`() = runTest(timeout = 60.seconds) {
        val backend = FakeLocationBackend()
        val provider: LocationProvider = newProvider(backend, testScheduler.timeSource)
        val gate = CompletableDeferred<Unit>()
        val seen: MutableList<LocationReading> = mutableListOf()
        val job = launch {
            provider.observeLocation(twoProviders).collect { reading: LocationReading ->
                seen += reading
                if (seen.size == 1) gate.await()
            }
        }
        runCurrent()
        assertEquals(1, seen.size, "control: the collector holds the seed and is now busy")
        assertEquals(2, backend.registeredCount, "control: the stream is registered while the collector is busy")

        repeat(300) { i: Int -> backend.deliver(gps, backend.locationAt(gps, i * 0.25)) }
        gate.complete(Unit)
        runCurrent()

        assertEquals(299 * 0.25, seen.last().latitudeOrNull(), "the collector is left on a stale position after ${seen.size} values")
        val latitudes: List<Double?> = seen.drop(1).map { it.latitudeOrNull() }
        assertTrue(latitudes.zipWithNext().all { (before: Double?, after: Double?) -> before!! < after!! }, "positions reordered: $latitudes")

        job.cancelAndJoin()
        assertEquals(0, backend.registeredCount)
        assertEquals(0, backend.subscriberCount)
    }

    private companion object {
        /** A hang bound only: nothing waits on it, a healthy run is far below it. */
        const val HANG_BOUND_MS: Long = 120_000
    }
}
