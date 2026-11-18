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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
 * Group loc-android (ticket 5.1.0), correction round 4: the failed AUTO move that must not retry through the
 * restore's own platform callback (M1) and the refusals that are forgotten when the fine grant changes (m1).
 * Derived from PLAN D16, D19 and 4.2 (SERVICE_DISABLED when nothing usable is on, a refusal is not retried on
 * every event). Own cases beside the breaker tests: the fused variant, a synchronous platform callback, the retry
 * after the decision changed, nothing registered, the grant change and its limits. Virtual time, no sleeps; every
 * case ends with the counters at zero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AutoMoveAndGrantRound4Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val fused: String = "fused"
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
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
     * The fake plus a per-provider non-security registration failure and the documented callback of a
     * registration on a disabled provider (`onProviderDisabled` "immediately"): posted (the looper) or, with
     * [synchronous], delivered inside `requestUpdates` itself. [maxEchoes] bounds a runaway so a defect fails the
     * assertion instead of hanging.
     */
    private class EchoBackend(val fake: FakeLocationBackend, val synchronous: Boolean = false) : LocationBackend by fake {
        private val failures: MutableMap<String, RuntimeException> = ConcurrentHashMap()
        private val posted = ConcurrentLinkedQueue<Pair<FakeLocationBackend.Registration, String>>()
        val attempts: AtomicInteger = AtomicInteger(0)
        val echoes: AtomicInteger = AtomicInteger(0)
        private val attemptsByProvider: MutableMap<String, AtomicInteger> = ConcurrentHashMap()

        fun fail(provider: String) {
            failures[provider] = IllegalArgumentException("oem: $provider registration failed")
        }

        fun lift(provider: String) {
            failures.remove(provider)
        }

        fun attemptsOf(provider: String): Int = attemptsByProvider[provider]?.get() ?: 0

        val pending: Int get() = posted.size

        override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
            attempts.incrementAndGet()
            attemptsByProvider.getOrPut(provider) { AtomicInteger(0) }.incrementAndGet()
            failures[provider]?.let { failure: RuntimeException -> throw failure }
            fake.requestUpdates(provider, request, listener)
            if (fake.isProviderEnabled(provider)) return
            if (synchronous) {
                if (echoes.incrementAndGet() <= MAX_ECHOES) listener.onProviderDisabled(provider)
            } else {
                val registration: FakeLocationBackend.Registration =
                    fake.registered().last { live: FakeLocationBackend.Registration -> live.listener === listener && live.provider == provider }
                posted += registration to provider
            }
        }

        /** Runs the posted callbacks (the main looper), at most [limit]; returns how many ran. */
        fun drain(limit: Int): Int {
            var ran = 0
            while (ran < limit) {
                val (registration: FakeLocationBackend.Registration, provider: String) = posted.poll() ?: break
                ran++
                echoes.incrementAndGet()
                if (fake.registered().any { live: FakeLocationBackend.Registration -> live === registration }) {
                    registration.listener.onProviderDisabled(provider)
                }
            }
            return ran
        }

        companion object {
            const val MAX_ECHOES: Int = 200
        }
    }

    // ---------------------------------------------------------------------------------------------
    // M1: a failed move target is not retried until the decision changes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a fused stream whose move target fails restores the off fused provider and settles after one round`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(fused, gps, network))
            val backend = EchoBackend(fake)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch {
                providerOver(backend).observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { seen += it }
            }
            runCurrent()
            assertEquals(listOf(fused), providersOf(fake), "control: AUTO starts on the framework fused provider")

            backend.fail(gps)
            backend.fail(network)
            fake.setProviderEnabled(fused, false)
            runCurrent()
            val afterEvent: Int = backend.attempts.get()
            val ran: Int = backend.drain(limit = 100)
            runCurrent()

            assertTrue(ran < 100 && backend.pending == 0, "the restore of the off fused provider drove $ran callbacks and is still going")
            assertTrue(backend.attempts.get() - afterEvent <= 2, "more registration attempts than one restore: ${backend.attempts.get() - afterEvent}")
            assertEquals(listOf(fused), providersOf(fake), "the previous registration is kept")
            assertEquals(serviceDisabled, seen.last(), "fused is off and the sources that failed cannot serve: ${seen.takeLast(4)}")

            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount, "a listener was left")
            assertEquals(0, fake.subscriberCount, "a subscription was left")
        }

    @Test
    fun `a platform callback delivered synchronously inside the restore does not spin the failed move`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = EchoBackend(fake, synchronous = true)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "control")

            backend.fail(gps)
            val before: Int = backend.attempts.get()
            fake.setProviderEnabled(network, false)
            runCurrent()

            assertTrue(backend.echoes.get() < EchoBackend.MAX_ECHOES, "the synchronous callback of the restore re-ran the move until the guard stopped it")
            assertTrue(backend.attempts.get() - before <= 3, "attempts after one switch-off: ${backend.attempts.get() - before}")
            assertEquals(listOf(network), providersOf(fake))
            assertEquals(serviceDisabled, seen.last(), "${seen.takeLast(4)}")

            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `a failed move is tried again once the decision changed and the failure is gone`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val backend = EchoBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { seen += it } }
        runCurrent()

        backend.fail(gps)
        fake.setProviderEnabled(network, false)
        runCurrent()
        backend.drain(limit = 20)
        runCurrent()
        val gpsAttempts: Int = backend.attemptsOf(gps)
        assertEquals(listOf(network), providersOf(fake), "control: the move failed and was restored")
        assertEquals(serviceDisabled, seen.last())

        // More events that describe the same decision (device switch flips, the network registration's own callback) do not retry.
        repeat(5) {
            fake.setDeviceLocationEnabled(true)
            runCurrent()
            backend.drain(limit = 20)
            runCurrent()
        }
        assertEquals(gpsAttempts, backend.attemptsOf(gps), "the same decision retried the failed target")

        // The decision changes (the network returns), then changes back with the failure lifted: the target is tried again.
        backend.lift(gps)
        fake.setProviderEnabled(network, true)
        runCurrent()
        assertNotEquals(serviceDisabled, seen.last(), "the network is back: ${seen.takeLast(4)}")
        fake.setProviderEnabled(network, false)
        runCurrent()
        backend.drain(limit = 20)
        runCurrent()
        assertEquals(listOf(gps), providersOf(fake), "a transient failure got its new chance after the decision changed")
        assertNotEquals(serviceDisabled, seen.last(), "gps serves: ${seen.takeLast(4)}")

        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    @Test
    fun `a move whose restore failed too is retried by the next event even while the decision is unchanged`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = EchoBackend(fake)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(balanced).collect { seen += it } }
            runCurrent()

            backend.fail(gps)
            backend.fail(network)
            fake.setProviderEnabled(network, false)
            runCurrent()
            assertEquals(0, fake.registeredCount, "control: neither the move nor the restore registered")

            // Only the network recovers; the AUTO decision (gps, because the network is off) is unchanged.
            backend.lift(network)
            fake.setDeviceLocationEnabled(true)
            runCurrent()
            backend.drain(limit = 20)
            runCurrent()

            assertEquals(listOf(network), providersOf(fake), "nothing was registered, so the next event must register again")
            assertEquals(serviceDisabled, seen.last(), "${seen.takeLast(4)}")

            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    // ---------------------------------------------------------------------------------------------
    // m1: refusals are forgotten when the fine grant changes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a high accuracy stream started on a coarse grant registers gps too once fine is granted`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
                fine = false
                coarse = true
                refuse(gps)
            }
            val job: Job = backgroundScope.launch {
                newProvider(fake).observeLocation(LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)).collect { }
            }
            runCurrent()
            assertEquals(listOf(network), providersOf(fake), "control: gps is refused, the network serves")

            fake.fine = true
            fake.allow(gps)
            fake.setDeviceLocationEnabled(true)
            runCurrent()

            assertEquals(listOf(gps, network), providersOf(fake), "both sources serve after the grant")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `a refusal that survives the grant is remembered again and not retried on every event`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
                fine = false
                coarse = true
                refuse(gps)
            }
            fake.setProviderEnabled(network, false)
            val job: Job = backgroundScope.launch { providerOver(fake).observeLocation(balanced).collect { } }
            runCurrent()

            fake.fine = true // gps still refuses the caller (an OEM policy): the new grant is not a licence to retry forever
            fake.setDeviceLocationEnabled(true)
            runCurrent()
            val afterGrant: Int = fake.registrationAttemptCount
            repeat(10) {
                fake.setDeviceLocationEnabled(true)
                runCurrent()
            }
            assertEquals(afterGrant, fake.registrationAttemptCount, "events with an unchanged grant retried a refused source")
            assertEquals(listOf(network), providersOf(fake))

            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `a refusal is kept while the grant is unchanged however many events arrive`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
            fine = false
            coarse = true
            refuse(gps)
        }
        val job: Job = backgroundScope.launch { providerOver(fake).observeLocation(balanced).collect { } }
        runCurrent()
        fake.setProviderEnabled(network, false)
        runCurrent()
        val attempts: Int = fake.registrationAttemptCount
        repeat(10) {
            fake.setProviderEnabled(network, it % 2 == 1)
            runCurrent()
        }
        assertEquals(attempts, fake.registrationAttemptCount, "a refused source was asked again although the grant did not change")
        job.cancelAndJoin()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }
}
