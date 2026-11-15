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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.CoreLocation.kCLLocationAccuracyReduced

/**
 * The one-shot half of the iOS provider through its seams (PLAN 6.4: LocationUnknownIgnoredTest,
 * AuthorizationImmediateTest, CachedFirstDeliveryTest, CachedFixAgeIosTest, AccuracyMappingTest and
 * the one-shot part of ReleaseTest). Virtual time only: the timeout is the test scheduler's.
 */
class IosGetCurrentLocationTest {

    private fun TestScope.fixture(): Pair<FakeIosLocationEnvironment, IosLocationProvider> {
        val env = FakeIosLocationEnvironment(testScheduler)
        return env to IosLocationProvider(env, NoopLogger)
    }

    private fun LocationReading.fix(): LocationFix = assertIs<LocationReading.Available>(this).fix

    private fun LocationReading.reason(): LocationUnavailability = assertIs<LocationReading.Unavailable>(this).reason

    private fun TestScope.start(provider: IosLocationProvider, options: CurrentLocationOptions = CurrentLocationOptions()): Deferred<LocationReading> {
        val call: Deferred<LocationReading> = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { provider.getCurrentLocation(options) }
        runCurrent()
        return call
    }

    @Test
    fun `not determined and denied with services on and restricted answer PERMISSION_MISSING at once without a manager`() = runTest {
        val (env, provider) = fixture()
        for (status: Int in listOf(kCLAuthorizationStatusNotDetermined, kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted)) {
            env.status = status
            assertEquals(LocationUnavailability.PERMISSION_MISSING, provider.getCurrentLocation().reason(), "status=$status")
        }
        assertEquals(0L, testScheduler.currentTime, "no timeout was waited")
        assertTrue(env.managers.isEmpty(), "nothing was registered")
    }

    @Test
    fun `denied or authorized with services off is SERVICE_DISABLED at once and not determined stays PERMISSION_MISSING`() = runTest {
        val (env, provider) = fixture()
        env.servicesOn = false
        for (status: Int in listOf(kCLAuthorizationStatusDenied, kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways)) {
            env.status = status
            assertEquals(LocationUnavailability.SERVICE_DISABLED, provider.getCurrentLocation().reason(), "status=$status")
        }
        env.status = kCLAuthorizationStatusNotDetermined
        assertEquals(LocationUnavailability.PERMISSION_MISSING, provider.getCurrentLocation().reason())
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(env.managers.isEmpty())
    }

    @Test
    fun `a fresh delivery is returned and the manager lived on main and is released on main`() = runTest {
        val (env, provider) = fixture()
        val call: Deferred<LocationReading> = start(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        assertTrue(manager.createdOnMain, "created on the main dispatcher")
        assertEquals(1, manager.requestCount)
        assertFalse(manager.detached, "control: the manager exists while the call waits")
        manager.deliver(rawFix(env.clock))
        runCurrent()
        val fix: LocationFix = call.await().fix()
        assertEquals(21.4225, fix.coordinates.latitude)
        assertEquals(false, fix.isApproximate)
        assertEquals(1, manager.detachCount)
        assertEquals(true, manager.detachedOnMain)
        assertNotNull(fix.iosDetails)
    }

    @Test
    fun `locationUnknown is ignored and the call waits for its timeout and then NO_FIX`() = runTest {
        val (env, provider) = fixture()
        val call: Deferred<LocationReading> = start(provider, CurrentLocationOptions(timeout = 30.seconds))
        val manager: FakeIosLocationManager = env.managers.single()
        manager.fail(IosFailureKind.LOCATION_UNKNOWN)
        runCurrent()
        assertFalse(call.isCompleted, "locationUnknown does not end the call")
        assertTrue(manager.updating, "requestLocation answers once, so the wait continues on ordinary updates")
        advanceTimeBy(29.seconds)
        runCurrent()
        assertFalse(call.isCompleted)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(LocationUnavailability.NO_FIX, call.await().reason())
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(1, manager.detachCount)
        assertFalse(manager.updating)
    }

    @Test
    fun `an answer older than maxAge is discarded and the call keeps waiting for a fresh one`() = runTest {
        val (env, provider) = fixture()
        val call: Deferred<LocationReading> = start(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        manager.deliver(rawFix(env.clock, age = 5.minutes))
        runCurrent()
        assertFalse(call.isCompleted, "a 5 minute old answer is not a 30 second fix")
        manager.deliver(rawFix(env.clock, age = 1.seconds, latitude = 10.0))
        runCurrent()
        assertEquals(10.0, call.await().fix().coordinates.latitude)
    }

    @Test
    fun `an invalid fix is dropped and the call keeps waiting`() = runTest {
        val (env, provider) = fixture()
        val call: Deferred<LocationReading> = start(provider)
        val manager: FakeIosLocationManager = env.managers.single()
        manager.deliver(rawFix(env.clock, horizontalAccuracy = -1.0))
        runCurrent()
        assertFalse(call.isCompleted)
        manager.deliver(rawFix(env.clock))
        runCurrent()
        assertIs<LocationReading.Available>(call.await())
    }

    @Test
    fun `maxAge ZERO accepts a fix taken after the request began and rejects one taken before it`() = runTest {
        val (env, provider) = fixture()
        val call: Deferred<LocationReading> = start(provider, CurrentLocationOptions(maxAge = Duration.ZERO))
        val manager: FakeIosLocationManager = env.managers.single()
        manager.deliver(rawFix(env.clock, age = 20.seconds))
        runCurrent()
        assertFalse(call.isCompleted, "taken 20 s before the request: not fresh")
        env.clock.advance(2.seconds)
        manager.deliver(rawFix(env.clock, age = 50.seconds / 100))
        runCurrent()
        assertIs<LocationReading.Available>(call.await(), "taken during the request: fresh although ZERO")
    }

    @Test
    fun `a cached fix within maxAge is returned without a request and an older one makes a fresh request`() = runTest {
        val (env, provider) = fixture()
        env.cached = rawFix(env.clock, age = 10.seconds)
        assertIs<LocationReading.Available>(provider.getCurrentLocation())
        assertEquals(0, env.managers.single().requestCount, "served from the cache")
        assertEquals(1, env.managers.single().detachCount)

        env.cached = rawFix(env.clock, age = 31.seconds)
        val call: Deferred<LocationReading> = start(provider)
        val second: FakeIosLocationManager = env.managers.last()
        assertEquals(1, second.requestCount, "31 s is older than the default 30 s: a fresh request is made")
        second.deliver(rawFix(env.clock))
        runCurrent()
        assertIs<LocationReading.Available>(call.await())
    }

    @Test
    fun `maxAge ZERO always requests fresh and INFINITE returns any cache`() = runTest {
        val (env, provider) = fixture()
        env.cached = rawFix(env.clock, age = Duration.ZERO)
        val zero: Deferred<LocationReading> = start(provider, CurrentLocationOptions(maxAge = Duration.ZERO, timeout = 5.seconds))
        assertEquals(1, env.managers.single().requestCount, "ZERO never takes the cache")
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(LocationUnavailability.NO_FIX, zero.await().reason())

        env.cached = rawFix(env.clock, age = 1.days)
        val any: LocationReading = provider.getCurrentLocation(CurrentLocationOptions(maxAge = Duration.INFINITE))
        assertIs<LocationReading.Available>(any)
        assertEquals(0, env.managers.last().requestCount)
    }

    @Test
    fun `cancelling the call releases the manager and a late callback is harmless`() = runTest {
        val (env, provider) = fixture()
        val call: Deferred<LocationReading> = start(provider, CurrentLocationOptions(timeout = 10.seconds))
        val manager: FakeIosLocationManager = env.managers.single()
        assertEquals(1, env.liveManagers.size, "control: the manager is registered while the call waits")
        call.cancel()
        runCurrent()
        assertEquals(0, env.liveManagers.size)
        assertEquals(1, manager.detachCount)
        assertEquals(true, manager.detachedOnMain)
        // A callback already in flight when the delegate was set to nil.
        manager.events.onLocations(listOf(rawFix(env.clock)))
        manager.events.onFailure(IosFailureKind.OTHER, "late")
        runCurrent()
        assertEquals(1, manager.detachCount)
    }

    @Test
    fun `a call cancelled before the main hop runs leaves no manager`() = runTest {
        val (env, provider) = fixture()
        val call: Deferred<LocationReading> = backgroundScope.async { provider.getCurrentLocation() }
        call.cancel()
        runCurrent()
        assertTrue(env.managers.isEmpty() || env.liveManagers.isEmpty())
    }

    @Test
    fun `a second provider and a second call work after the first was released`() = runTest {
        val (env, provider) = fixture()
        repeat(3) { round: Int ->
            val call: Deferred<LocationReading> = start(provider)
            env.managers.last().deliver(rawFix(env.clock, latitude = round.toDouble()))
            runCurrent()
            assertEquals(round.toDouble(), call.await().fix().coordinates.latitude)
        }
        assertEquals(3, env.managers.size)
        assertTrue(env.managers.all { it.detachCount == 1 }, "every manager was released exactly once")
        val other = IosLocationProvider(env, NoopLogger)
        val call: Deferred<LocationReading> = start(other)
        env.managers.last().deliver(rawFix(env.clock))
        runCurrent()
        assertIs<LocationReading.Available>(call.await())
    }

    @Test
    fun `a denied failure re-reads the authorization and answers the reason`() = runTest {
        val (env, provider) = fixture()
        val call: Deferred<LocationReading> = start(provider)
        env.status = kCLAuthorizationStatusDenied
        env.managers.single().fail(IosFailureKind.DENIED)
        runCurrent()
        assertEquals(LocationUnavailability.PERMISSION_MISSING, call.await().reason())
        assertEquals(1, env.managers.single().detachCount)
    }

    @Test
    fun `any other failure ends the call with NO_FIX at once`() = runTest {
        val (env, provider) = fixture()
        val call: Deferred<LocationReading> = start(provider)
        env.managers.single().fail(IosFailureKind.OTHER)
        runCurrent()
        assertEquals(LocationUnavailability.NO_FIX, call.await().reason())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `each accuracy level and each override maps to its CoreLocation constant and one-shot`() = runTest {
        val (env, provider) = fixture()
        val expected: Map<LocationAccuracy, Double> = mapOf(
            LocationAccuracy.HIGH_ACCURACY to kCLLocationAccuracyBest,
            LocationAccuracy.BALANCED to kCLLocationAccuracyHundredMeters,
            LocationAccuracy.LOW_POWER to kCLLocationAccuracyKilometer,
        )
        for ((accuracy: LocationAccuracy, constant: Double) in expected) {
            env.cached = rawFix(env.clock)
            provider.getCurrentLocation(CurrentLocationOptions(accuracy = accuracy))
            assertEquals(constant, env.managers.last().applied.last().desiredAccuracy, "$accuracy")
        }
        for (override: IosDesiredAccuracy in IosDesiredAccuracy.entries) {
            provider.getCurrentLocation(
                CurrentLocationOptions(accuracy = LocationAccuracy.LOW_POWER, platform = IosCurrentLocationOptions(override)),
            )
            assertEquals(override.toDesiredAccuracy(), env.managers.last().applied.last().desiredAccuracy, "$override")
        }
        assertEquals(kCLLocationAccuracyReduced, IosDesiredAccuracy.REDUCED.toDesiredAccuracy())
    }

    @Test
    fun `a REDUCED override delivers an approximate fix under a full grant`() = runTest {
        val (env, provider) = fixture()
        env.reduced = false
        val reduced: Deferred<LocationReading> =
            start(provider, CurrentLocationOptions(platform = IosCurrentLocationOptions(IosDesiredAccuracy.REDUCED)))
        env.managers.last().deliver(rawFix(env.clock))
        runCurrent()
        assertEquals(true, reduced.await().fix().isApproximate)

        val plain: Deferred<LocationReading> = start(provider)
        env.managers.last().deliver(rawFix(env.clock))
        runCurrent()
        assertEquals(false, plain.await().fix().isApproximate)

        env.reduced = true
        val grant: Deferred<LocationReading> = start(provider)
        env.managers.last().deliver(rawFix(env.clock))
        runCurrent()
        assertEquals(true, grant.await().fix().isApproximate, "reduced accuracy grant")
    }
}
