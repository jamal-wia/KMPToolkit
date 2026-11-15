package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Breaker round 1 of group loc-android (ticket 5.1.0, LOC-7 / LOC-10, protocol BREAKER-REVIEW.md, D-23).
 *
 * Every test is derived from the contract (PLAN D8, D15, D16, D19, sections 4.1 and 4.2, the KDoc of
 * [LocationProvider], [LocationUnavailability] and [LocationFix]), not from the implementation. Races are
 * forced deterministically: [HookedBackend] wraps the scripted [FakeLocationBackend] and, at one exact read
 * of the platform, runs the "main looper" event on a second thread and waits for it with a bounded latch,
 * so the interleaving that a real device produces by chance happens on every run. Virtual time for every
 * timeout; no sleeps, no wall clock, no randomness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidLocationProviderBreakerR1Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)

    /**
     * A [LocationBackend] over [fake] with hooks at the points a race needs: just before and just after the
     * service-change subscription, at the next device-wide switch read, and failures of the cache read.
     */
    private class HookedBackend(val fake: FakeLocationBackend) : LocationBackend by fake {
        @Volatile var beforeSubscribe: (() -> Unit)? = null
        @Volatile var afterSubscribe: (() -> Unit)? = null

        /** Runs once, at the next [isLocationEnabled] read, after the value was read and before it is returned. */
        @Volatile var enabledReadHook: (() -> Unit)? = null

        @Volatile var lastKnownFailure: RuntimeException? = null

        override fun isLocationEnabled(): Boolean {
            val value: Boolean = fake.isLocationEnabled()
            val hook: (() -> Unit)? = enabledReadHook
            if (hook != null) {
                enabledReadHook = null
                hook()
            }
            return value
        }

        override fun lastKnownLocation(provider: String): Location? {
            lastKnownFailure?.let { failure: RuntimeException -> throw failure }
            return fake.lastKnownLocation(provider)
        }

        override fun observeServiceChanges(onChange: () -> Unit): () -> Unit {
            beforeSubscribe?.also { beforeSubscribe = null }?.invoke()
            val unsubscribe: () -> Unit = fake.observeServiceChanges(onChange)
            afterSubscribe?.also { afterSubscribe = null }?.invoke()
            return unsubscribe
        }
    }

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
        timeSource = TestTimeSource(),
    )

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
        }, "breaker-main-looper")
        thread.start()
        if (!done.await(10, TimeUnit.SECONDS)) fail("the simulated main-looper event hung (hang bound)")
        failure.get()?.let { thrown: Throwable -> throw AssertionError("the simulated main-looper event failed", thrown) }
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    // ---------------------------------------------------------------------------------------------
    // M1: the stream's service-state decision is read on one thread and published on another
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a service read racing a broadcast neither leaves the stream on SERVICE_DISABLED nor hides the next off`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val backend = HookedBackend(fake)
        // The device goes off in the window between the registration and the receiver (no broadcast reaches
        // the stream for it), the stream's first read after subscribing sees "off", and before it publishes
        // that the device comes back on: the broadcast is handled on the main looper meanwhile.
        backend.beforeSubscribe = {
            fake.locationEnabled = false
            backend.enabledReadHook = { onOtherThread { fake.setDeviceLocationEnabled(true) } }
        }
        val provider: LocationProvider = providerOver(backend)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { seen += it }
        }
        runCurrent()
        idleMainLooper()
        runCurrent()

        assertTrue(fake.isLocationEnabled(), "control: the service is on")
        assertEquals(1, fake.subscriberCount, "control: the stream subscribed to the service broadcasts")
        assertEquals(2, fake.registeredCount, "control: the stream registered both sources")
        assertNotEquals(serviceDisabled, seen.last(), "the service is on, yet the stream rests on SERVICE_DISABLED: $seen")

        fake.deliver(gps, fake.locationAt(gps, 41.0))
        runCurrent()
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        idleMainLooper()
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "a real switch-off after the race was never reported (D16): $seen")

        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // M2: observeLocationEnabled publishes its first read after a newer broadcast value
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `observeLocationEnabled ends on the current state when a broadcast races the first read`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        fake.locationEnabled = false
        val backend = HookedBackend(fake)
        // Subscribed; the first read sees "off"; the device comes on and its broadcast is handled before that
        // first read is sent.
        backend.afterSubscribe = { backend.enabledReadHook = { onOtherThread { fake.setDeviceLocationEnabled(true) } } }
        val provider: LocationProvider = providerOver(backend)
        val seen: MutableList<Boolean> = mutableListOf()
        val job: Job = backgroundScope.launch { provider.observeLocationEnabled().collect { value: Boolean -> seen += value } }
        runCurrent()
        idleMainLooper()
        runCurrent()

        assertTrue(fake.isLocationEnabled(), "control: the service is on")
        assertEquals(1, fake.subscriberCount, "control: subscribed")
        assertEquals(true, seen.lastOrNull(), "the service is on but observeLocationEnabled rests on $seen (D15: emitted on every change)")

        job.cancelAndJoin()
        assertEquals(0, fake.subscriberCount)
    }

    // ---------------------------------------------------------------------------------------------
    // M3: AUTO reads SERVICE_DISABLED while an enabled provider of its table exists
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an AUTO balanced stream with the network off and gps on delivers gps fixes and never reads SERVICE_DISABLED`() = runTest(timeout = 60.seconds) {
        // API 24-27 "device only" location mode: gps on, network off. D19: AUTO reads SERVICE_DISABLED only
        // when none of its providers is enabled.
        val fake = FakeLocationBackend(providers = setOf(gps, network, passive))
        fake.setProviderEnabled(network, false)
        val provider: LocationProvider = providerOver(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED)).collect { seen += it }
        }
        runCurrent()

        assertTrue(fake.isLocationEnabled() && fake.isProviderEnabled(gps), "control: the device and gps are on")
        assertFalse(serviceDisabled in seen, "AUTO with gps on must not read SERVICE_DISABLED: $seen")
        fake.deliver(gps, fake.locationAt(gps, 41.0))
        runCurrent()
        assertEquals(GeoCoordinates(41.0, 2.0), seen.lastOrNull()?.fixOrNull()?.coordinates, "the gps fix never reached the stream: $seen")

        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
    }

    @Test
    fun `an AUTO low power call with the network off falls back to passive instead of answering SERVICE_DISABLED`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network, passive))
        fake.setProviderEnabled(network, false)
        val provider: LocationProvider = providerOver(fake)
        val call: Deferred<LocationReading> = backgroundScope.async {
            provider.getCurrentLocation(CurrentLocationOptions(accuracy = LocationAccuracy.LOW_POWER, maxAge = Duration.ZERO))
        }
        runCurrent()
        fake.deliver(passive, fake.locationAt(passive, 41.0))
        runCurrent()

        assertTrue(call.isCompleted, "control: the call answered")
        assertEquals(
            GeoCoordinates(41.0, 2.0),
            call.await().fixOrNull()?.coordinates,
            "D19: AUTO reads SERVICE_DISABLED only when none is enabled, and passive is: got ${call.await()}",
        )
        assertEquals(0, fake.registeredCount)
    }

    // ---------------------------------------------------------------------------------------------
    // M4: a source switched off during a one-shot call is waited out to the timeout and read as NO_FIX
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a named source switched off during the call answers SERVICE_DISABLED at once, not NO_FIX after the timeout`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        val provider: LocationProvider = providerOver(fake)
        val call: Deferred<LocationReading> = backgroundScope.async {
            provider.getCurrentLocation(
                CurrentLocationOptions(
                    maxAge = Duration.ZERO,
                    timeout = 30.seconds,
                    platform = AndroidCurrentLocationOptions(AndroidLocationSource.NETWORK),
                ),
            )
        }
        runCurrent()
        assertEquals(1, fake.registeredCount, "control: the call is waiting on the network source")

        fake.setProviderEnabled(network, false)
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertTrue(call.isCompleted, "the source is off, yet the call still waits at t=${currentTime} ms for its 30 s timeout")
        assertEquals(serviceDisabled, call.await(), "LocationUnavailability.SERVICE_DISABLED: answered at once, never after a timeout")
        assertEquals(0, fake.registeredCount)
    }

    @Test
    fun `every raced source switched off during an AUTO call answers SERVICE_DISABLED at once`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val provider: LocationProvider = providerOver(fake)
        val call: Deferred<LocationReading> = backgroundScope.async {
            provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.ZERO, timeout = 30.seconds))
        }
        runCurrent()
        assertEquals(2, fake.registeredCount, "control: gps and network are raced")

        // The device-wide switch going off disables every provider: the platform calls onProviderDisabled for
        // each registration and then sends the broadcast.
        fake.locationEnabled = false
        fake.setProviderEnabled(gps, false)
        fake.setProviderEnabled(network, false)
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertTrue(call.isCompleted, "every source is off, yet the call still waits at t=${currentTime} ms for its 30 s timeout")
        assertEquals(serviceDisabled, call.await())
        assertEquals(0, fake.registeredCount)
    }

    // ---------------------------------------------------------------------------------------------
    // m1 / m3: platform failures escape getCurrentLocation ("never throws for platform errors")
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `getCurrentLocation does not throw when the platform cache read fails`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        val backend = HookedBackend(fake)
        backend.lastKnownFailure = IllegalStateException("system server died")
        val provider: LocationProvider = providerOver(backend)

        val outcome: Result<LocationReading> = runCatching {
            provider.getCurrentLocation(CurrentLocationOptions(timeout = 1.seconds))
        }

        assertNull(outcome.exceptionOrNull(), "getCurrentLocation never throws for platform errors, got ${outcome.exceptionOrNull()}")
        assertEquals(0, fake.registeredCount)
    }

    @Test
    fun `a failing listener removal when the call times out is not thrown into the coroutine machinery`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        val provider: LocationProvider = providerOver(fake)
        val call: Deferred<LocationReading> = async {
            provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.ZERO, timeout = 1.seconds))
        }
        runCurrent()
        assertEquals(2, fake.registeredCount, "control: both sources registered")
        // The removal throws (a dead system server); the stream treats exactly this as "log it" (release()).
        fake.removalFailure = IllegalStateException("system server died")

        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(LocationReading.Unavailable(LocationUnavailability.NO_FIX), call.await())
        // runTest reports an exception thrown from a cancellation handler (CompletionHandlerException through
        // the coroutine exception handler; on a device: the thread's uncaught-exception handler) after the body.
    }

    // ---------------------------------------------------------------------------------------------
    // m2: PLAN 4.1 / LocationFix KDoc: a negative vertical accuracy nulls all three altitude fields
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a negative vertical accuracy nulls the vertical accuracy and both altitudes`() {
        val location: Location = Location(gps).apply {
            latitude = 41.0
            longitude = 2.0
            time = 1_700_000_000_000L
            elapsedRealtimeNanos = 5_000_000_000L
            altitude = 120.0
            mslAltitudeMeters = 70.0
            mslAltitudeAccuracyMeters = 3f
            verticalAccuracyMeters = -1f
        }

        val fix: LocationFix? = location.toLocationFix(coarseOnly = false)

        assertEquals(GeoCoordinates(41.0, 2.0), fix?.coordinates, "control: the coordinates are valid")
        assertNull(fix?.verticalAccuracyMeters, "negative vertical accuracy")
        assertNull(fix?.ellipsoidalAltitudeMeters, "LocationFix KDoc: a negative platform value nulls all three altitude fields")
        assertNull(fix?.mslAltitudeMeters, "LocationFix KDoc: a negative platform value nulls all three altitude fields")
    }

    // ---------------------------------------------------------------------------------------------
    // Attacks expected to hold (kept as permanent proof)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a framework answer that arrives after the caller cancelled is harmless and the next call works`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(supportsCurrentLocation = true)
        val provider: LocationProvider = providerOver(fake)
        val options = CurrentLocationOptions(
            maxAge = Duration.ZERO,
            platform = AndroidCurrentLocationOptions(AndroidLocationSource.GPS),
        )
        val first: Deferred<LocationReading> = async { provider.getCurrentLocation(options) }
        runCurrent()
        assertEquals(1, fake.currentCalls().size, "control: the framework one-shot call is pending")
        first.cancelAndJoin()
        assertTrue(fake.currentCalls()[0].cancelled, "the framework call was cancelled")

        // A late answer already posted on the main looper before the cancel landed: bypasses the fake's guard.
        fake.currentCalls()[0].onResult(fake.locationAt(gps, 10.0))
        runCurrent()
        assertEquals(0, fake.registeredCount, "a late answer registered nothing")

        val second: Deferred<LocationReading> = async { provider.getCurrentLocation(options) }
        runCurrent()
        fake.answerCurrentCall(1, fake.locationAt(gps, 11.0))
        assertEquals(GeoCoordinates(11.0, 2.0), second.await().fixOrNull()?.coordinates)
        assertEquals(0, fake.registeredCount)
    }

    @Test
    fun `a collector that cancels itself from inside a main-looper delivery releases the listener and the receiver`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        val provider: LocationProvider = providerOver(fake)
        val first: Deferred<LocationReading> = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).first { it is LocationReading.Available }
        }
        runCurrent()
        assertEquals(2, fake.registeredCount, "control: registered")
        assertEquals(1, fake.subscriberCount, "control: subscribed")

        fake.deliver(gps, fake.locationAt(gps, 12.0))
        runCurrent()

        assertEquals(GeoCoordinates(12.0, 2.0), first.await().fixOrNull()?.coordinates)
        assertEquals(0, fake.registeredCount, "first {} ended the collection from inside the delivery")
        assertEquals(0, fake.subscriberCount)
    }

    @Test
    fun `a failing service subscription after every registration succeeded removes the listener and reaches the collector`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend()
        val backend = HookedBackend(fake)
        val boom = IllegalStateException("receiver registration failed")
        var registeredBeforeFailure = -1
        backend.beforeSubscribe = {
            registeredBeforeFailure = fake.registeredCount
            throw boom
        }
        val provider: LocationProvider = providerOver(backend)

        val outcome: Result<List<LocationReading>> = runCatching {
            val seen: MutableList<LocationReading> = mutableListOf()
            provider.observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { seen += it }
            seen
        }

        assertEquals(2, registeredBeforeFailure, "control: both sources were registered when the subscription failed")
        assertEquals("receiver registration failed", outcome.exceptionOrNull()?.message, "the failure reaches the collector")
        assertEquals(0, fake.registeredCount, "the listener was removed on the failure path")
        assertEquals(0, fake.subscriberCount)
    }

    @Test
    fun `a stale framework answer is followed by a one-update registration that the timeout removes`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(supportsCurrentLocation = true)
        val provider: LocationProvider = providerOver(fake)
        val call: Deferred<LocationReading> = async {
            provider.getCurrentLocation(
                CurrentLocationOptions(maxAge = Duration.ZERO, timeout = 5.seconds, platform = AndroidCurrentLocationOptions(AndroidLocationSource.GPS)),
            )
        }
        runCurrent()
        // PLAN 6.3: the framework hands back a fix taken 20 s before the call.
        fake.answerCurrentCall(0, fake.locationAt(gps, 10.0, ageMillis = 20_000))
        runCurrent()
        assertEquals(1, fake.registeredCount, "control: the stale answer fell back to a one-update registration")
        val listener: LocationListener = fake.registered().single().listener

        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(LocationReading.Unavailable(LocationUnavailability.NO_FIX), call.await(), "the stale fix is never returned")
        assertEquals(0, fake.registeredCount)
        listener.onLocationChanged(fake.locationAt(gps, 11.0))
        assertEquals(0, fake.registeredCount, "a late delivery to the removed listener is harmless")
    }
}
