@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.CoreLocation.kCLLocationAccuracyReduced

/**
 * The stream half of the iOS provider through its seams (PLAN 6.4: LocationUnknownIgnoredTest,
 * AuthorizationImmediateTest, InvalidFixDroppedTest, AccuracyMappingTest, CachedFirstDeliveryTest,
 * CachedFixAgeIosTest, ServiceFlapIosTest, AccuracyAuthorizationFlipTest, BackgroundModeTest,
 * AutoPauseTest and ReleaseTest). Virtual time only; every stream is collected in a background scope.
 */
class IosObserveLocationTest {

    private class Probe {
        lateinit var job: Job
        val readings: MutableList<LocationReading> = mutableListOf()
        var completed: Boolean = false
        var failure: Throwable? = null
    }

    private fun TestScope.fixture(): Pair<FakeIosLocationEnvironment, IosLocationProvider> {
        val env = FakeIosLocationEnvironment(testScheduler)
        return env to IosLocationProvider(env, NoopLogger)
    }

    private fun TestScope.collect(provider: IosLocationProvider, options: LocationUpdatesOptions = LocationUpdatesOptions()): Probe {
        val probe = Probe()
        probe.job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                provider.observeLocation(options).collect { reading: LocationReading -> probe.readings += reading }
                probe.completed = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                probe.failure = failure
            }
        }
        runCurrent()
        return probe
    }

    private fun LocationReading.fix(): LocationFix = assertIs<LocationReading.Available>(this).fix

    private fun LocationReading.reason(): LocationUnavailability = assertIs<LocationReading.Unavailable>(this).reason

    /** The services switch (or the authorization) changed: every live manager reports it, the provider re-reads. */
    private fun TestScope.flip(env: FakeIosLocationEnvironment) {
        env.announceChange()
        runCurrent()
    }

    @Test
    fun `a cached fix within maxSeedAge seeds the stream and an older one gives NO_FIX`() = runTest {
        val (env, provider) = fixture()
        env.cached = rawFix(env.clock, age = 10.seconds, latitude = 1.0)
        val fresh: Probe = collect(provider)
        assertEquals(1.0, fresh.readings.single().fix().coordinates.latitude)
        assertTrue(env.managers.single().updating)
        fresh.job.cancel()
        runCurrent()

        env.cached = rawFix(env.clock, age = 5.minutes)
        val stale: Probe = collect(provider)
        assertEquals(LocationUnavailability.NO_FIX, stale.readings.single().reason())
        stale.job.cancel()
        runCurrent()
        assertTrue(env.managers.all { it.detached })
    }

    @Test
    fun `maxSeedAge ZERO never seeds from the cache and INFINITE takes any age`() = runTest {
        val (env, provider) = fixture()
        env.cached = rawFix(env.clock, age = Duration.ZERO)
        val zero: Probe = collect(provider, LocationUpdatesOptions(maxSeedAge = Duration.ZERO))
        assertEquals(LocationUnavailability.NO_FIX, zero.readings.single().reason())
        zero.job.cancel()
        env.cached = rawFix(env.clock, age = 24.hours)
        val any: Probe = collect(provider, LocationUpdatesOptions(maxSeedAge = Duration.INFINITE))
        assertIs<LocationReading.Available>(any.readings.single())
        any.job.cancel()
        runCurrent()
    }

    @Test
    fun `the first live fix after a cached seed passes at once inside minUpdateInterval`() = runTest {
        val (env, provider) = fixture()
        env.cached = rawFix(env.clock, age = 10.seconds, latitude = 1.0)
        val probe: Probe = collect(provider, LocationUpdatesOptions(minUpdateInterval = 60.seconds))
        advanceTimeBy(1.seconds)
        env.managers.single().deliver(rawFix(env.clock, latitude = 2.0))
        runCurrent()
        assertEquals(listOf(1.0, 2.0), probe.readings.map { it.fix().coordinates.latitude })
        assertEquals(1_000L, testScheduler.currentTime, "delivered when it arrived, not at the end of the interval")
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `every reseed after a service flap is a seed too and so the live fix after it is not held`() = runTest {
        val (env, provider) = fixture()
        env.cached = rawFix(env.clock, age = 5.seconds, latitude = 1.0)
        val probe: Probe = collect(provider, LocationUpdatesOptions(minUpdateInterval = 60.seconds))
        val manager: FakeIosLocationManager = env.managers.single()
        advanceTimeBy(1.seconds)
        manager.deliver(rawFix(env.clock, latitude = 2.0))
        runCurrent()
        // The services go off and come back; the cache now holds another position, which reseeds.
        env.servicesOn = false
        flip(env)
        advanceTimeBy(9.seconds)
        env.servicesOn = true
        // The fix this stream received is now 20 s old, so the cached 5 s old one is the freshest seed.
        env.clock.advance(20.seconds)
        env.cached = rawFix(env.clock, age = 5.seconds, latitude = 3.0)
        flip(env)
        assertEquals(10_000L, testScheduler.currentTime)
        advanceTimeBy(1.seconds)
        manager.deliver(rawFix(env.clock, latitude = 4.0))
        runCurrent()
        val labels: List<String> = probe.readings.map { reading: LocationReading ->
            when (reading) {
                is LocationReading.Available -> "fix${reading.fix.coordinates.latitude.toInt()}"
                is LocationReading.Unavailable -> reading.reason.name
            }
        }
        assertEquals(listOf("fix1", "fix2", "SERVICE_DISABLED", "fix3", "fix4"), labels, "the live fix 1 s after the reseed is emitted at once")
        assertEquals(11_000L, testScheduler.currentTime)
        assertEquals(1, env.managers.size, "one manager for the whole stream")
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `a stale first delivery is dropped until the first fresh one`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        assertEquals(LocationUnavailability.NO_FIX, probe.readings.single().reason())
        val manager: FakeIosLocationManager = env.managers.single()
        manager.deliver(rawFix(env.clock, age = 5.minutes, latitude = 1.0))
        runCurrent()
        assertEquals(1, probe.readings.size, "5 minutes old with maxSeedAge 30 s: not emitted")
        manager.deliver(rawFix(env.clock, age = 1.seconds, latitude = 2.0))
        runCurrent()
        assertEquals(2.0, probe.readings.last().fix().coordinates.latitude)
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `locationUnknown emits nothing and the stream stays open`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        manager.fail(IosFailureKind.LOCATION_UNKNOWN)
        runCurrent()
        assertEquals(1, probe.readings.size)
        assertFalse(probe.completed)
        assertTrue(manager.updating)
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `an invalid fix is dropped and never thrown or emitted`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        manager.deliver(rawFix(env.clock, horizontalAccuracy = -1.0), rawFix(env.clock, latitude = 91.0), rawFix(env.clock, latitude = Double.NaN))
        runCurrent()
        assertEquals(1, probe.readings.size)
        assertEquals(null, probe.failure)
        manager.deliver(rawFix(env.clock, latitude = 5.0))
        runCurrent()
        assertEquals(5.0, probe.readings.last().fix().coordinates.latitude)
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `not authorized answers PERMISSION_MISSING at once without a manager and completes`() = runTest {
        val (env, provider) = fixture()
        env.status = kCLAuthorizationStatusNotDetermined
        val probe: Probe = collect(provider)
        assertEquals(LocationUnavailability.PERMISSION_MISSING, probe.readings.single().reason())
        assertTrue(probe.completed)
        assertTrue(env.managers.isEmpty())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `denied with services off is SERVICE_DISABLED and the stream stays open without updates`() = runTest {
        val (env, provider) = fixture()
        env.status = kCLAuthorizationStatusDenied
        env.servicesOn = false
        val probe: Probe = collect(provider)
        assertEquals(LocationUnavailability.SERVICE_DISABLED, probe.readings.single().reason())
        assertFalse(probe.completed)
        assertFalse(env.managers.single().updating)
        probe.job.cancel()
        runCurrent()
        assertEquals(1, env.managers.single().detachCount)
    }

    @Test
    fun `services off mid-stream emit SERVICE_DISABLED and keep one delegate and on again reseeds and the next fix is emitted`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        manager.deliver(rawFix(env.clock, latitude = 1.0))
        runCurrent()
        env.servicesOn = false
        flip(env)
        assertEquals(LocationUnavailability.SERVICE_DISABLED, probe.readings.last().reason())
        assertFalse(probe.completed, "the stream does not complete")
        assertFalse(manager.updating)
        manager.deliver(rawFix(env.clock, latitude = 9.0))
        runCurrent()
        assertEquals(LocationUnavailability.SERVICE_DISABLED, probe.readings.last().reason(), "nothing may follow the reason it predates")
        env.clock.advance(60.seconds)
        env.servicesOn = true
        flip(env)
        assertTrue(manager.updating, "the same manager resumes")
        assertEquals(LocationUnavailability.NO_FIX, probe.readings.last().reason(), "reseed: the last fix is 60 s old, nothing cached")
        manager.deliver(rawFix(env.clock, latitude = 2.0))
        runCurrent()
        assertEquals(2.0, probe.readings.last().fix().coordinates.latitude)
        assertEquals(1, env.managers.size, "no restart leak: one manager, one delegate")
        assertEquals(1, env.liveManagers.size)
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `a flap does not repeat SERVICE_DISABLED for the same state`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        env.servicesOn = false
        repeat(5) { flip(env) }
        assertEquals(1, probe.readings.count { it is LocationReading.Unavailable && it.reason == LocationUnavailability.SERVICE_DISABLED })
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `a fix this stream received keeps its age across a wall clock jump when it reseeds`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        manager.deliver(rawFix(env.clock, age = 1.seconds, latitude = 7.0))
        runCurrent()
        env.servicesOn = false
        flip(env)
        env.clock.advance(10.seconds)
        env.clock.jumpWall(1.hours)
        env.servicesOn = true
        flip(env)
        assertEquals(7.0, probe.readings.last().fix().coordinates.latitude, "11 s old on the uptime clock, not 1 h")
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `a revoke mid-stream emits PERMISSION_MISSING then completes and releases`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        env.status = kCLAuthorizationStatusDenied
        flip(env)
        assertEquals(LocationUnavailability.PERMISSION_MISSING, probe.readings.last().reason())
        assertTrue(probe.completed)
        assertEquals(1, manager.detachCount)
        assertEquals(true, manager.detachedOnMain)
    }

    @Test
    fun `a denied failure stops the updates and the re-read state is emitted`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        env.status = kCLAuthorizationStatusDenied
        manager.fail(IosFailureKind.DENIED)
        assertFalse(manager.updating, "Apple: stop the service on a denial")
        runCurrent()
        assertEquals(LocationUnavailability.PERMISSION_MISSING, probe.readings.last().reason())
        assertTrue(probe.completed)
    }

    @Test
    fun `any other failure is logged only and the stream continues`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        manager.fail(IosFailureKind.OTHER)
        runCurrent()
        assertTrue(manager.updating)
        assertFalse(probe.completed)
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `each accuracy level and override maps to its constant for the stream and the distance filter follows the option`() = runTest {
        val (env, provider) = fixture()
        val cases: List<Pair<LocationUpdatesOptions, Double>> = listOf(
            LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY) to kCLLocationAccuracyBest,
            LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED) to kCLLocationAccuracyHundredMeters,
            LocationUpdatesOptions(platform = IosLocationUpdatesOptions(desiredAccuracyOverride = IosDesiredAccuracy.REDUCED)) to kCLLocationAccuracyReduced,
        )
        for ((options: LocationUpdatesOptions, constant: Double) in cases) {
            val probe: Probe = collect(provider, options)
            assertEquals(constant, env.managers.last().applied.first().desiredAccuracy, "$options")
            probe.job.cancel()
            runCurrent()
        }
        val filtered: Probe = collect(provider, LocationUpdatesOptions(minUpdateDistanceMeters = 25.0))
        assertEquals(25.0, env.managers.last().applied.first().distanceFilter)
        filtered.job.cancel()
        runCurrent()
    }

    @Test
    fun `a reduced to full flip re-applies the accuracy and the next fix is no longer approximate`() = runTest {
        val (env, provider) = fixture()
        env.reduced = true
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        manager.deliver(rawFix(env.clock, latitude = 1.0))
        runCurrent()
        assertEquals(true, probe.readings.last().fix().isApproximate)
        val appliedBefore: Int = manager.applied.size
        env.reduced = false
        flip(env)
        assertTrue(manager.applied.size > appliedBefore, "the mapped desiredAccuracy is applied again")
        manager.deliver(rawFix(env.clock, latitude = 2.0))
        runCurrent()
        assertEquals(false, probe.readings.last().fix().isApproximate)
        env.reduced = true
        flip(env)
        manager.deliver(rawFix(env.clock, latitude = 3.0))
        runCurrent()
        assertEquals(true, probe.readings.last().fix().isApproximate)
        assertFalse(probe.completed)
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `allowsBackgroundLocationUpdates without the bundle entry fails naming the key in every authorization state`() = runTest {
        val (env, provider) = fixture()
        val options = LocationUpdatesOptions(platform = IosLocationUpdatesOptions(allowsBackgroundLocationUpdates = true))
        for (status: Int in listOf(kCLAuthorizationStatusNotDetermined, kCLAuthorizationStatusDenied)) {
            env.status = status
            val probe: Probe = collect(provider, options)
            val failure: Throwable = assertNotNull(probe.failure, "status=$status")
            assertIs<IllegalStateException>(failure)
            assertTrue("UIBackgroundModes" in failure.message.orEmpty() && "location" in failure.message.orEmpty())
        }
        assertEquals(0, env.statusReads, "the check runs before any authorization read")
        assertTrue(env.managers.isEmpty())
    }

    @Test
    fun `with the bundle entry the background properties are set`() = runTest {
        val (env, provider) = fixture()
        env.backgroundMode = true
        val platform = IosLocationUpdatesOptions(
            allowsBackgroundLocationUpdates = true,
            showsBackgroundLocationIndicator = true,
            activityType = IosActivityType.FITNESS,
        )
        val probe: Probe = collect(provider, LocationUpdatesOptions(platform = platform))
        val config: IosManagerConfig = env.managers.single().applied.first()
        assertTrue(config.allowsBackgroundLocationUpdates)
        assertTrue(config.showsBackgroundLocationIndicator)
        assertEquals(IosActivityType.FITNESS.toCoreLocation(), config.activityType)
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `auto pause is off by default and nothing pauses without the opt-in`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        assertFalse(manager.applied.first().pausesLocationUpdatesAutomatically, "the property is set false")
        assertTrue(env.activeListeners.isEmpty(), "no restart listener without the opt-in")
        manager.pause()
        runCurrent()
        assertEquals(1, probe.readings.size, "a pause nobody asked for is not reported")
        probe.job.cancel()
        runCurrent()
    }

    @Test
    fun `with auto pause a pause emits NO_FIX and didBecomeActive restarts updates`() = runTest {
        val (env, provider) = fixture()
        val platform = IosLocationUpdatesOptions(pausesLocationUpdatesAutomatically = true)
        val probe: Probe = collect(provider, LocationUpdatesOptions(platform = platform))
        val manager: FakeIosLocationManager = env.managers.single()
        assertTrue(manager.applied.first().pausesLocationUpdatesAutomatically)
        assertEquals(1, env.activeListeners.size, "control: the restart listener is registered")
        manager.deliver(rawFix(env.clock, latitude = 1.0))
        runCurrent()
        manager.pause()
        runCurrent()
        assertEquals(LocationUnavailability.NO_FIX, probe.readings.last().reason())
        val starts: Int = manager.startCount
        env.activate()
        assertEquals(starts + 1, manager.startCount, "restarted on didBecomeActive")
        manager.deliver(rawFix(env.clock, latitude = 2.0))
        runCurrent()
        assertEquals(2.0, probe.readings.last().fix().coordinates.latitude, "fixes resume")
        env.activate()
        assertEquals(starts + 1, manager.startCount, "no restart when nothing is paused")
        probe.job.cancel()
        runCurrent()
        assertEquals(1, env.unregistered)
        assertTrue(env.activeListeners.isEmpty())
    }

    @Test
    fun `cancelling releases the manager on main and a late callback is ignored`() = runTest {
        val (env, provider) = fixture()
        val probe: Probe = collect(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        assertTrue(manager.createdOnMain)
        assertEquals(1, env.liveManagers.size, "control: registered while collected")
        probe.job.cancel()
        runCurrent()
        assertEquals(0, env.liveManagers.size)
        assertEquals(1, manager.detachCount)
        assertEquals(true, manager.detachedOnMain)
        assertFalse(manager.updating)
        val seen: Int = probe.readings.size
        manager.events.onLocations(listOf(rawFix(env.clock)))
        manager.events.onAuthorizationChanged()
        manager.events.onFailure(IosFailureKind.DENIED, "late")
        runCurrent()
        assertEquals(seen, probe.readings.size)
        assertEquals(1, manager.detachCount)
    }

    @Test
    fun `a stream collected again after it was released works on a new manager`() = runTest {
        val (env, provider) = fixture()
        repeat(3) { round: Int ->
            val probe: Probe = collect(provider)
            env.managers.last().deliver(rawFix(env.clock, latitude = round.toDouble()))
            runCurrent()
            assertEquals(round.toDouble(), probe.readings.last().fix().coordinates.latitude)
            probe.job.cancel()
            runCurrent()
        }
        assertEquals(3, env.managers.size)
        assertTrue(env.managers.all { it.detachCount == 1 })
    }

    @Test
    fun `two concurrent collections each own a manager and releasing one leaves the other running`() = runTest {
        val (env, provider) = fixture()
        val first: Probe = collect(provider)
        val second: Probe = collect(provider)
        assertEquals(2, env.liveManagers.size)
        first.job.cancel()
        runCurrent()
        assertEquals(1, env.liveManagers.size)
        env.managers.last().deliver(rawFix(env.clock, latitude = 4.0))
        runCurrent()
        assertEquals(4.0, second.readings.last().fix().coordinates.latitude)
        assertTrue(env.managers.first().detached)
        second.job.cancel()
        runCurrent()
        assertTrue(env.liveManagers.isEmpty())
    }
}
