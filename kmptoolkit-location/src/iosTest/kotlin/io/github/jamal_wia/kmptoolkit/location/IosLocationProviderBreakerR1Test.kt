@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * Breaker round 1 of group loc-ios (ticket 5.1.0, LOC-8 / LOC-9): tests written to break the iOS
 * provider through its seams. Derived from PLAN sections 2.2 (D8, D14-D16), 2.3 (D20), 4.1, 4.2,
 * 10 item 7, decision D-21 and the provider's own KDoc (main-queue confinement, "the collector is not
 * resumed until the main-queue release has run").
 *
 * Every platform call goes through [HookedEnvironment], which wraps the executor's fake and adds:
 * a hook on every seam call (to cancel the collector at exactly that call), a record of every
 * manager call made off the main dispatcher, and a record of every services read made on it.
 * Virtual time only; seeded randomness with `seed=` in every message; hang bounds only.
 */
class IosLocationProviderBreakerR1Test {

    // ---------------------------------------------------------------------------------------------
    // Seams with hooks

    private class HookedEnvironment(val fake: FakeIosLocationEnvironment) : IosLocationEnvironment by fake {
        var calls: Int = 0
        var onCall: ((name: String, index: Int) -> Unit)? = null
        var onDetach: (() -> Unit)? = null
        val offMainManagerCalls: MutableList<String> = mutableListOf()
        var servicesReadsOnMain: Int = 0

        fun hook(name: String) {
            calls++
            onCall?.invoke(name, calls)
        }

        val onMain: Boolean get() = fake.main.isRunning

        override fun authorizationStatus(): Int {
            hook("authorizationStatus")
            return fake.authorizationStatus()
        }

        override fun locationServicesEnabled(): Boolean {
            hook("locationServicesEnabled")
            if (onMain) servicesReadsOnMain++
            return fake.locationServicesEnabled()
        }

        override fun hasLocationBackgroundMode(): Boolean {
            hook("hasLocationBackgroundMode")
            return fake.hasLocationBackgroundMode()
        }

        override fun createManager(events: IosLocationEvents): IosLocationManager {
            hook("createManager")
            if (!onMain) offMainManagerCalls += "createManager"
            val inner: FakeIosLocationManager = fake.createManager(events) as FakeIosLocationManager
            return HookedManager(this, inner)
        }

        override fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
            hook("addBecameActiveListener")
            if (!onMain) offMainManagerCalls += "addBecameActiveListener"
            return fake.addBecameActiveListener(onActive)
        }
    }

    private class HookedManager(private val env: HookedEnvironment, private val inner: FakeIosLocationManager) : IosLocationManager {
        private fun touch(name: String) {
            env.hook(name)
            if (!env.onMain) env.offMainManagerCalls += name
        }

        override val authorizationStatus: Int
            get() {
                touch("manager.authorizationStatus")
                return inner.authorizationStatus
            }
        override val isAccuracyReduced: Boolean
            get() {
                touch("manager.isAccuracyReduced")
                return inner.isAccuracyReduced
            }
        override val lastLocation: IosRawFix?
            get() {
                touch("manager.lastLocation")
                return inner.lastLocation
            }

        override fun apply(config: IosManagerConfig) {
            touch("manager.apply")
            inner.apply(config)
        }

        override fun startUpdatingLocation() {
            touch("manager.start")
            inner.startUpdatingLocation()
        }

        override fun stopUpdatingLocation() {
            touch("manager.stop")
            inner.stopUpdatingLocation()
        }

        override fun requestLocation() {
            touch("manager.requestLocation")
            inner.requestLocation()
        }

        override fun detach() {
            touch("manager.detach")
            env.onDetach?.invoke()
            inner.detach()
        }
    }

    private class Fixture(val env: HookedEnvironment, val provider: IosLocationProvider) {
        val fake: FakeIosLocationEnvironment get() = env.fake
    }

    private fun TestScope.fixture(): Fixture {
        val env = HookedEnvironment(FakeIosLocationEnvironment(testScheduler))
        return Fixture(env, IosLocationProvider(env, NoopLogger))
    }

    /** CoreLocation calls the delegate on the main queue: run [block] there, as the platform would. */
    private fun TestScope.onMain(fake: FakeIosLocationEnvironment, block: () -> Unit) {
        backgroundScope.launch(fake.mainDispatcher) { block() }
        runCurrent()
    }

    private fun label(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix${reading.fix.coordinates.latitude.toInt()}"
        is LocationReading.Unavailable -> reading.reason.name
    }

    private fun LocationReading.reason(): LocationUnavailability = assertIs<LocationReading.Unavailable>(this).reason

    // ---------------------------------------------------------------------------------------------
    // SeedMarker (D-21, correction round 1)

    /**
     * The collector is stuck in its first emission while the session keeps producing: the readings wait
     * in the throttle's inbox (a BUFFERED channel, 64 slots), not in a one-slot buffer. A live fix A
     * follows the first reseed s1; then [reseedsAfterLive] further flaps each reseed. Returns the labels
     * the collector finally sees.
     */
    private fun TestScope.seedBacklog(reseedsAfterLive: Int): List<String> {
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        fake.cached = rawFix(fake.clock, age = 1.seconds, latitude = 1.0)
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val labels: MutableList<String> = mutableListOf()
        val job: Job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            f.provider.observeLocation(LocationUpdatesOptions(minUpdateInterval = 60.seconds)).collect { reading: LocationReading ->
                labels += label(reading)
                if (labels.size == 1) gate.await()
            }
        }
        runCurrent()

        fun flap(seedLatitude: Double) {
            fake.servicesOn = false
            fake.announceChange()
            runCurrent()
            fake.servicesOn = true
            fake.cached = rawFix(fake.clock, age = 1.seconds, latitude = seedLatitude)
            fake.announceChange()
            runCurrent()
        }
        flap(2.0)
        fake.managers.single().deliver(rawFix(fake.clock, latitude = 50.0))
        runCurrent()
        // A is now older than every later cached seed, so each reseed takes the cache, not A.
        fake.clock.advance(5.seconds)
        for (i: Int in 1..reseedsAfterLive) flap(2.0 + i)
        gate.complete(Unit)
        runCurrent()
        job.cancel()
        runCurrent()
        return labels
    }

    /**
     * Defect class: lost update / stale marker. The KDoc of SeedMarker claims a seed eight reseeds old
     * "can no longer be in flight"; the inbox between the platform flow and the throttle loop holds 64
     * readings, so it can. Once evicted, the seed is judged as a live fix: it starts minUpdateInterval
     * and the first live fix after it is held, then discarded by the next SERVICE_DISABLED.
     * Mutation by reading: MAX_REMEMBERED = 8 is what turns the attack case red; the control case (7
     * later reseeds) is green on the same code.
     */
    @Test
    fun `a seed is still a seed when eight later reseeds queued behind a slow collector`() = runTest {
        val control: List<String> = seedBacklog(reseedsAfterLive = 7)
        assertTrue("fix50" in control, "control: with 7 later reseeds the live fix after the reseed passes: $control")
        val attack: List<String> = seedBacklog(reseedsAfterLive = 8)
        assertTrue(
            "fix50" in attack,
            "the live fix right after a reseed must pass at once (D-21) however many reseeds wait behind it; got $attack",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Terminal state

    /**
     * Defect class: state after an emitted terminal value. PERMISSION_MISSING is terminal (D8); the
     * session marks StreamState.TERMINAL but enter(null) does not check it, so a re-grant that arrives
     * before the downstream has consumed the terminal reading (slow collector) restarts the updates on
     * the same manager and emits a seed behind the terminal reading.
     * Mutation by reading: `if (state == StreamState.TERMINAL) return` at the top of enter() turns it green.
     */
    @Test
    fun `after PERMISSION_MISSING is emitted the session never restarts updates even if authorization returns`() = runTest {
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val labels: MutableList<String> = mutableListOf()
        var completed = false
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            f.provider.observeLocation().collect { reading: LocationReading ->
                labels += label(reading)
                if (labels.size == 1) gate.await()
            }
            completed = true
        }
        runCurrent()
        val manager: FakeIosLocationManager = fake.managers.single()
        assertTrue(manager.updating, "control: updates run while collected")
        fake.status = kCLAuthorizationStatusDenied
        fake.announceChange()
        runCurrent()
        assertFalse(manager.updating, "control: the revoke stopped the updates")
        val starts: Int = manager.startCount
        fake.status = kCLAuthorizationStatusAuthorizedWhenInUse
        fake.announceChange()
        runCurrent()
        assertEquals(starts, manager.startCount, "updates restarted after PERMISSION_MISSING was emitted")
        assertFalse(manager.updating, "the manager runs again after the terminal reading")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("NO_FIX", "PERMISSION_MISSING"), labels)
        assertTrue(completed)
        assertEquals(1, manager.detachCount)
    }

    /**
     * Defect class: duplicate state emission. Turning Location Services off while updating makes iOS
     * report `denied` through locationManagerDidChangeAuthorization and also kCLErrorDenied through
     * didFailWithError. When the authorization callback comes first, SERVICE_DISABLED is emitted, then
     * onFailure(DENIED) resets the state to IDLE and the re-read emits SERVICE_DISABLED again. The
     * provider's own contract (IosObserveLocationTest "a flap does not repeat SERVICE_DISABLED for the
     * same state") says one SERVICE_DISABLED per off period.
     * Mutation by reading: resetting to IDLE only from READY in onFailure(DENIED) turns it green.
     */
    @Test
    fun `a denial error after SERVICE_DISABLED does not emit SERVICE_DISABLED a second time`() = runTest {
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        val labels: MutableList<String> = mutableListOf()
        val job: Job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            f.provider.observeLocation().collect { reading: LocationReading -> labels += label(reading) }
        }
        runCurrent()
        val manager: FakeIosLocationManager = fake.managers.single()
        onMain(fake) { manager.deliver(rawFix(fake.clock, latitude = 3.0)) }
        fake.servicesOn = false
        fake.status = kCLAuthorizationStatusDenied
        onMain(fake) { manager.authorizationChanged() }
        onMain(fake) { manager.fail(IosFailureKind.DENIED) }
        assertEquals(listOf("NO_FIX", "fix3", "SERVICE_DISABLED"), labels)
        job.cancel()
        runCurrent()
    }

    // ---------------------------------------------------------------------------------------------
    // "The collector is not resumed until the main-queue release has run"

    /**
     * Defect class: release after completion (the old async main-hop teardown, PATTERNS Part 2 known
     * weakness). At the moment of every detach the collector must still be running, and when it has
     * ended the detach must have happened: for a cancelled stream, a stream completed by a revoke, a
     * cancelled observeLocationEnabled and a cancelled one-shot call.
     * Mutation by reading: launching the release (`launch(main) { release() }`) instead of
     * `withContext(NonCancellable + main)` turns it red.
     */
    @Test
    fun `every kind of end resumes the collector only after the manager was released`() = runTest {
        // A cancelled stream.
        run {
            val f: Fixture = fixture()
            var ended = false
            val seenAtDetach: MutableList<Boolean> = mutableListOf()
            f.env.onDetach = { seenAtDetach += ended }
            val job: Job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    f.provider.observeLocation().collect { }
                } finally {
                    ended = true
                }
            }
            runCurrent()
            assertEquals(1, f.fake.liveManagers.size, "control: a manager is registered")
            job.cancel()
            runCurrent()
            assertEquals(listOf(false), seenAtDetach, "cancelled stream: detach ran before the collector ended")
            assertTrue(ended)
        }
        // A stream completed by a revoke.
        run {
            val f: Fixture = fixture()
            var ended = false
            val seenAtDetach: MutableList<Boolean> = mutableListOf()
            f.env.onDetach = { seenAtDetach += ended }
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.provider.observeLocation().collect { }
                ended = true
            }
            runCurrent()
            f.fake.status = kCLAuthorizationStatusDenied
            f.fake.announceChange()
            runCurrent()
            assertEquals(listOf(false), seenAtDetach, "completed stream: detach ran before collect returned")
            assertTrue(ended)
        }
        // A cancelled observeLocationEnabled.
        run {
            val f: Fixture = fixture()
            var ended = false
            val seenAtDetach: MutableList<Boolean> = mutableListOf()
            f.env.onDetach = { seenAtDetach += ended }
            val job: Job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    f.provider.observeLocationEnabled().collect { }
                } finally {
                    ended = true
                }
            }
            runCurrent()
            assertEquals(1, f.fake.liveManagers.size, "control: the switch observer holds a manager")
            job.cancel()
            runCurrent()
            assertEquals(listOf(false), seenAtDetach, "observeLocationEnabled: detach ran before the collector ended")
            assertTrue(ended)
        }
        // A cancelled one-shot call.
        run {
            val f: Fixture = fixture()
            var ended = false
            val seenAtDetach: MutableList<Boolean> = mutableListOf()
            f.env.onDetach = { seenAtDetach += ended }
            val call: Deferred<LocationReading> = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    f.provider.getCurrentLocation()
                } finally {
                    ended = true
                }
            }
            runCurrent()
            assertEquals(1, f.fake.liveManagers.size, "control: the call holds a manager")
            call.cancel()
            runCurrent()
            assertEquals(listOf(false), seenAtDetach, "one-shot: detach ran before the call ended")
            assertTrue(ended)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cancellation at every seam call

    private fun TestScope.streamScript(f: Fixture) {
        val fake: FakeIosLocationEnvironment = f.fake
        fun manager(): FakeIosLocationManager? = fake.liveManagers.lastOrNull()
        onMain(fake) { manager()?.deliver(rawFix(fake.clock, latitude = 1.0)) }
        fake.servicesOn = false
        onMain(fake) { manager()?.authorizationChanged() }
        fake.servicesOn = true
        fake.cached = rawFix(fake.clock, age = 1.seconds, latitude = 2.0)
        onMain(fake) { manager()?.authorizationChanged() }
        onMain(fake) { manager()?.pause() }
        onMain(fake) { fake.activate() }
        onMain(fake) { manager()?.fail(IosFailureKind.DENIED) }
        fake.reduced = true
        onMain(fake) { manager()?.authorizationChanged() }
        fake.status = kCLAuthorizationStatusDenied
        onMain(fake) { manager()?.authorizationChanged() }
    }

    private fun TestScope.runStreamCancelledAt(cancelAt: Int?): Int {
        val f: Fixture = fixture()
        f.fake.backgroundMode = true
        f.fake.cached = rawFix(f.fake.clock, age = 1.seconds, latitude = 9.0)
        var job: Job? = null
        f.env.onCall = { _: String, index: Int -> if (index == cancelAt) job?.cancel() }
        val options = LocationUpdatesOptions(
            minUpdateInterval = 2.seconds,
            platform = IosLocationUpdatesOptions(allowsBackgroundLocationUpdates = true, pausesLocationUpdatesAutomatically = true),
        )
        var ended = false
        job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                f.provider.observeLocation(options).collect { }
            } finally {
                ended = true
            }
        }
        runCurrent()
        streamScript(f)
        val total: Int = f.env.calls
        job.cancel()
        runCurrent()
        val where = "cancelAt=$cancelAt"
        assertTrue(ended, "$where: the collection did not end")
        assertTrue(f.fake.liveManagers.isEmpty(), "$where: a manager survived")
        assertTrue(f.fake.managers.all { it.detachCount == 1 }, "$where: a manager was not released exactly once")
        assertTrue(f.fake.managers.none { it.updating }, "$where: a released manager is updating")
        assertTrue(f.fake.activeListeners.isEmpty(), "$where: the did-become-active listener leaked")
        assertTrue(f.env.offMainManagerCalls.isEmpty(), "$where: manager touched off main: ${f.env.offMainManagerCalls}")
        assertEquals(0, f.env.servicesReadsOnMain, "$where: locationServicesEnabled read on main")
        return total
    }

    /**
     * Defect class: cancellation mid-operation (cancel racing start, cancel racing the services
     * re-read, cancel racing release) and listener leak. The collector is cancelled from inside the
     * k-th seam call, for every k of a script that starts, flaps, pauses, resumes, fails and revokes.
     * Mutation by reading: dropping NonCancellable from the release, or moving the listener
     * registration out of start() without a matching removal, turns it red.
     */
    @Test
    fun `a stream cancelled inside any seam call releases everything exactly once`() = runTest {
        val total: Int = runStreamCancelledAt(cancelAt = null)
        assertTrue(total > 20, "control: the script makes many seam calls ($total)")
        for (k: Int in 1..total) runStreamCancelledAt(cancelAt = k)
    }

    private fun TestScope.runOneShotCancelledAt(cancelAt: Int?): Int {
        val f: Fixture = fixture()
        f.fake.cached = rawFix(f.fake.clock, age = 5.minutes)
        var call: Deferred<LocationReading>? = null
        f.env.onCall = { _: String, index: Int -> if (index == cancelAt) call?.cancel() }
        call = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            f.provider.getCurrentLocation(CurrentLocationOptions(timeout = 10.seconds))
        }
        runCurrent()
        val fake: FakeIosLocationEnvironment = f.fake
        onMain(fake) { fake.liveManagers.lastOrNull()?.fail(IosFailureKind.LOCATION_UNKNOWN) }
        onMain(fake) { fake.liveManagers.lastOrNull()?.deliver(rawFix(fake.clock, age = 5.minutes)) }
        onMain(fake) { fake.liveManagers.lastOrNull()?.authorizationChanged() }
        onMain(fake) { fake.liveManagers.lastOrNull()?.deliver(rawFix(fake.clock, latitude = 4.0)) }
        val total: Int = f.env.calls
        advanceTimeBy(11.seconds)
        runCurrent()
        val where = "cancelAt=$cancelAt"
        assertTrue(call.isCompleted, "$where: the call did not end")
        assertTrue(fake.liveManagers.isEmpty(), "$where: a manager survived")
        assertTrue(fake.managers.all { it.detachCount == 1 && !it.updating }, "$where: a manager was not released once")
        assertTrue(f.env.offMainManagerCalls.isEmpty(), "$where: manager touched off main: ${f.env.offMainManagerCalls}")
        assertEquals(0, f.env.servicesReadsOnMain, "$where: locationServicesEnabled read on main")
        if (cancelAt == null) assertEquals(4.0, assertIs<LocationReading.Available>(call.getCompleted()).fix.coordinates.latitude)
        return total
    }

    /** As above for the one-shot call: cancelled inside every seam call, including after the outcome. */
    @Test
    fun `a one-shot call cancelled inside any seam call releases its manager exactly once`() = runTest {
        val total: Int = runOneShotCancelledAt(cancelAt = null)
        assertTrue(total > 8, "control: the script makes many seam calls ($total)")
        for (k: Int in 1..total) runOneShotCancelledAt(cancelAt = k)
    }

    private fun TestScope.runEnabledCancelledAt(cancelAt: Int?): Int {
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        var job: Job? = null
        f.env.onCall = { _: String, index: Int -> if (index == cancelAt) job?.cancel() }
        val values: MutableList<Boolean> = mutableListOf()
        job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            f.provider.observeLocationEnabled().collect { value: Boolean -> values += value }
        }
        runCurrent()
        repeat(3) {
            fake.servicesOn = !fake.servicesOn
            onMain(fake) { fake.announceChange() }
            onMain(fake) { fake.announceChange() }
        }
        val total: Int = f.env.calls
        job.cancel()
        runCurrent()
        val where = "cancelAt=$cancelAt"
        assertTrue(job.isCompleted, "$where: the collection did not end")
        assertTrue(fake.liveManagers.isEmpty(), "$where: a manager survived")
        assertTrue(fake.managers.all { it.detachCount == 1 && it.startCount == 0 }, "$where: released once, never started")
        assertTrue(f.env.offMainManagerCalls.isEmpty(), "$where: manager touched off main: ${f.env.offMainManagerCalls}")
        assertEquals(0, f.env.servicesReadsOnMain, "$where: locationServicesEnabled read on main")
        if (cancelAt == null) assertEquals(listOf(true, false, true, false), values, "distinct, one value per change")
        return total
    }

    /** As above for observeLocationEnabled, with each change announced twice (distinct). */
    @Test
    fun `observeLocationEnabled cancelled inside any seam call releases its manager and never starts updates`() = runTest {
        val total: Int = runEnabledCancelledAt(cancelAt = null)
        assertTrue(total > 4, "control: the script makes several seam calls ($total)")
        for (k: Int in 1..total) runEnabledCancelledAt(cancelAt = k)
    }

    // ---------------------------------------------------------------------------------------------
    // Reason table (PLAN 4.2, choice 1 of the executor)

    private fun expectedReason(status: Int, servicesOn: Boolean): LocationUnavailability? = when (status) {
        kCLAuthorizationStatusNotDetermined, kCLAuthorizationStatusRestricted -> LocationUnavailability.PERMISSION_MISSING
        kCLAuthorizationStatusDenied -> if (servicesOn) LocationUnavailability.PERMISSION_MISSING else LocationUnavailability.SERVICE_DISABLED
        else -> if (servicesOn) null else LocationUnavailability.SERVICE_DISABLED
    }

    /**
     * Defect class: wrong typed state. Every authorizationStatus x accuracyAuthorization x services
     * combination on both calls: the reason, whether a manager exists, whether the stream completes, and
     * isApproximate of the fix when the request may run.
     */
    @Test
    fun `the reason table holds for every status and grade and switch on both calls`() = runTest {
        val statuses: List<Int> = listOf(
            kCLAuthorizationStatusNotDetermined,
            kCLAuthorizationStatusRestricted,
            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusAuthorizedAlways,
            kCLAuthorizationStatusAuthorizedWhenInUse,
        )
        for (status: Int in statuses) for (servicesOn: Boolean in listOf(true, false)) for (reduced: Boolean in listOf(true, false)) {
            val case = "status=$status services=$servicesOn reduced=$reduced"
            val expected: LocationUnavailability? = expectedReason(status, servicesOn)

            val one: Fixture = fixture()
            one.fake.status = status
            one.fake.servicesOn = servicesOn
            one.fake.reduced = reduced
            one.fake.cached = rawFix(one.fake.clock, age = 1.seconds, latitude = 5.0)
            val call: Deferred<LocationReading> = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { one.provider.getCurrentLocation() }
            runCurrent()
            val answer: LocationReading = call.getCompleted()
            if (expected == null) {
                val fix: LocationFix = assertIs<LocationReading.Available>(answer, case).fix
                assertEquals(reduced, fix.isApproximate, "$case: isApproximate")
            } else {
                assertEquals(expected, answer.reason(), case)
                assertTrue(one.fake.managers.isEmpty(), "$case: the one-shot reason is answered without a manager")
            }

            val stream: Fixture = fixture()
            stream.fake.status = status
            stream.fake.servicesOn = servicesOn
            stream.fake.reduced = reduced
            stream.fake.cached = rawFix(stream.fake.clock, age = 1.seconds, latitude = 5.0)
            val readings: MutableList<LocationReading> = mutableListOf()
            var completed = false
            val job: Job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                stream.provider.observeLocation().collect { reading: LocationReading -> readings += reading }
                completed = true
            }
            runCurrent()
            when (expected) {
                null -> {
                    assertEquals(reduced, assertIs<LocationReading.Available>(readings.single(), case).fix.isApproximate, case)
                    assertTrue(stream.fake.managers.single().updating, "$case: updating")
                    assertFalse(completed, case)
                }
                LocationUnavailability.PERMISSION_MISSING -> {
                    assertEquals(LocationUnavailability.PERMISSION_MISSING, readings.single().reason(), case)
                    assertTrue(completed, "$case: completes after PERMISSION_MISSING")
                    assertTrue(stream.fake.managers.isEmpty(), "$case: no manager")
                }
                else -> {
                    assertEquals(expected, readings.single().reason(), case)
                    assertFalse(completed, "$case: stays open through SERVICE_DISABLED")
                    assertFalse(stream.fake.managers.single().updating, "$case: no updates while disabled")
                }
            }
            job.cancel()
            runCurrent()
            assertTrue(stream.fake.liveManagers.isEmpty(), "$case: released")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Age rule boundaries (PLAN 4.2 age row, section 10 item 7)

    /**
     * Defect class: boundary / clock. A timestamp of 0 (1970), of NSDate.distantPast's magnitude and a
     * fix far older than INFINITE-but-finite windows: never fresh under the default maxAge, accepted
     * only under INFINITE; a cached fix exactly maxAge old is still accepted; an uptime that stepped
     * back never makes a received fix younger than at delivery.
     */
    @Test
    fun `ancient stamps are stale on both calls and INFINITE takes them and an age equal to maxAge is accepted`() = runTest {
        for (stamp: Long in listOf(0L, -62_135_596_800_000L)) {
            val f: Fixture = fixture()
            val fake: FakeIosLocationEnvironment = f.fake
            fake.cached = rawFix(fake.clock).let { base: IosRawFix -> copyWithStamp(base, stamp) }
            val call: Deferred<LocationReading> = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                f.provider.getCurrentLocation(CurrentLocationOptions(timeout = 5.seconds))
            }
            runCurrent()
            assertEquals(1, fake.managers.single().requestCount, "stamp=$stamp: an ancient cache must not answer")
            onMain(fake) { fake.managers.single().deliver(copyWithStamp(rawFix(fake.clock), stamp)) }
            assertFalse(call.isCompleted, "stamp=$stamp: an ancient delivery must not answer")
            advanceTimeBy(6.seconds)
            assertEquals(LocationUnavailability.NO_FIX, call.getCompleted().reason(), "stamp=$stamp")

            val any: Fixture = fixture()
            any.fake.cached = copyWithStamp(rawFix(any.fake.clock, latitude = 6.0), stamp)
            val anyCall: Deferred<LocationReading> = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                any.provider.getCurrentLocation(CurrentLocationOptions(maxAge = kotlin.time.Duration.INFINITE))
            }
            runCurrent()
            assertIs<LocationReading.Available>(anyCall.getCompleted(), "stamp=$stamp: INFINITE accepts any age")

            val stream: Fixture = fixture()
            stream.fake.cached = copyWithStamp(rawFix(stream.fake.clock), stamp)
            val labels: MutableList<String> = mutableListOf()
            val job: Job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                stream.provider.observeLocation().collect { reading: LocationReading -> labels += label(reading) }
            }
            runCurrent()
            onMain(stream.fake) { stream.fake.managers.single().deliver(copyWithStamp(rawFix(stream.fake.clock, latitude = 7.0), stamp)) }
            onMain(stream.fake) { stream.fake.managers.single().deliver(rawFix(stream.fake.clock, latitude = 8.0)) }
            assertEquals(listOf("NO_FIX", "fix8"), labels, "stamp=$stamp: ancient seed and first delivery dropped")
            job.cancel()
            runCurrent()
        }
        // Exactly maxAge old on the cache rule.
        val edge: Fixture = fixture()
        edge.fake.cached = rawFix(edge.fake.clock, age = 30.seconds, latitude = 9.0)
        val edgeCall: Deferred<LocationReading> = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { edge.provider.getCurrentLocation() }
        runCurrent()
        assertIs<LocationReading.Available>(edgeCall.getCompleted(), "an age equal to maxAge is still acceptable")
        // Uptime stepping back between delivery and decision.
        val clock = FakeIosClock()
        val delivery = IosDelivery(assertNotNull(rawFix(clock, age = 2.seconds).toFixOrNull(false)), 2.seconds, clock.uptime())
        assertEquals(2.seconds, delivery.ageAt(clock.uptime() - 10.minutes))
        assertEquals(2.seconds + 1.milliseconds, delivery.ageAt(clock.uptime() + 1.milliseconds))
    }

    private fun copyWithStamp(base: IosRawFix, stamp: Long): IosRawFix = IosRawFix(
        latitude = base.latitude,
        longitude = base.longitude,
        timestampEpochMillis = stamp,
        horizontalAccuracy = base.horizontalAccuracy,
        altitude = base.altitude,
        ellipsoidalAltitude = base.ellipsoidalAltitude,
        verticalAccuracy = base.verticalAccuracy,
        speed = base.speed,
        speedAccuracy = base.speedAccuracy,
        course = base.course,
        courseAccuracy = base.courseAccuracy,
        floorLevel = base.floorLevel,
        isSimulated = base.isSimulated,
        isProducedByAccessory = base.isProducedByAccessory,
    )

    // ---------------------------------------------------------------------------------------------
    // CLLocation adapter on exotic values (PLAN 4.1)

    /** Defect class: boundary. A real CLLocation with negative speed/course/accuracies, accuracy 0, huge altitude, NaN. */
    @Test
    fun `the adapter and mapper handle exotic CLLocation values`() {
        val stamp: NSDate = NSDate.dateWithTimeIntervalSince1970(1_700_000_000.0)
        val exotic = CLLocation(
            coordinate = CLLocationCoordinate2DMake(-90.0, 180.0),
            altitude = 1.0e9,
            horizontalAccuracy = 0.0,
            verticalAccuracy = -1.0,
            course = -1.0,
            courseAccuracy = -1.0,
            speed = -1.0,
            speedAccuracy = -1.0,
            timestamp = stamp,
        )
        val fix: LocationFix = assertNotNull(exotic.toRawFix().toFixOrNull(false), "accuracy 0 at the pole is a valid fix")
        assertEquals(0.0, fix.horizontalAccuracyMeters)
        assertEquals(-90.0, fix.coordinates.latitude)
        assertNull(fix.mslAltitudeMeters, "negative vertical accuracy nulls the altitudes")
        assertNull(fix.ellipsoidalAltitudeMeters)
        assertNull(fix.verticalAccuracyMeters)
        assertNull(fix.speedMetersPerSecond)
        assertNull(fix.speedAccuracyMetersPerSecond)
        assertNull(fix.courseDegrees)
        assertNull(fix.courseAccuracyDegrees)
        assertEquals(1_700_000_000_000L, fix.timestampEpochMillis)

        val huge = CLLocation(
            coordinate = CLLocationCoordinate2DMake(10.0, 20.0),
            altitude = 1.0e9,
            horizontalAccuracy = 1.0e7,
            verticalAccuracy = 0.0,
            course = 359.99,
            courseAccuracy = 0.0,
            speed = 0.0,
            speedAccuracy = 0.0,
            timestamp = stamp,
        )
        val big: LocationFix = assertNotNull(huge.toRawFix().toFixOrNull(true))
        assertEquals(1.0e9, big.mslAltitudeMeters, "a huge finite altitude is kept")
        assertEquals(0.0, big.verticalAccuracyMeters)
        assertEquals(359.99, big.courseDegrees)
        assertEquals(0.0, big.speedMetersPerSecond)
        assertEquals(true, big.isApproximate)

        val nan = CLLocation(latitude = Double.NaN, longitude = 20.0)
        assertNull(nan.toRawFix().toFixOrNull(false), "a NaN coordinate is dropped")
        val outOfRange = CLLocation(latitude = 10.0, longitude = 180.5)
        assertNull(outOfRange.toRawFix().toFixOrNull(false), "an out-of-range longitude is dropped")
        val invalid = CLLocation(
            coordinate = CLLocationCoordinate2DMake(10.0, 20.0),
            altitude = 0.0,
            horizontalAccuracy = -1.0,
            verticalAccuracy = 1.0,
            course = 0.0,
            courseAccuracy = 0.0,
            speed = 0.0,
            speedAccuracy = 0.0,
            timestamp = stamp,
        )
        assertNull(invalid.toRawFix().toFixOrNull(false), "a negative horizontal accuracy is an invalid fix")
    }

    // ---------------------------------------------------------------------------------------------
    // One-shot: events after the outcome

    /**
     * Defect class: work after completion. A fresh fix completes the call, and in the same main-queue
     * turn a locationUnknown error and an authorization change arrive (they were queued behind it):
     * the answer stays the fix and the manager ends stopped and released once.
     */
    @Test
    fun `events queued behind the answering fix leave the manager stopped and released`() = runTest {
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        val call: Deferred<LocationReading> = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { f.provider.getCurrentLocation() }
        runCurrent()
        val manager: FakeIosLocationManager = fake.managers.single()
        onMain(fake) {
            manager.deliver(rawFix(fake.clock, latitude = 3.0))
            manager.fail(IosFailureKind.LOCATION_UNKNOWN)
            fake.status = kCLAuthorizationStatusDenied
            manager.authorizationChanged()
            manager.fail(IosFailureKind.OTHER)
        }
        assertEquals(3.0, assertIs<LocationReading.Available>(call.getCompleted()).fix.coordinates.latitude)
        assertEquals(1, manager.detachCount)
        assertFalse(manager.updating)
    }

    // ---------------------------------------------------------------------------------------------
    // Seeded storm: events on the main queue, cancellation inside random seam calls

    /**
     * Defect classes: confinement (any manager call off main, any services read on main), leak (live
     * managers, did-become-active listeners), double release, emission after completion, an invalid fix
     * emitted, more than one live manager per collection. 40 seeds x 250 steps; events are delivered on
     * the main dispatcher as CoreLocation does; a cancellation is armed to fire inside the n-th next
     * seam call, so it lands in the middle of start, a re-read, a release or a delegate event.
     */
    @Test
    fun `a seeded storm with cancellation inside seam calls keeps every invariant`() = runTest {
        for (seed: Int in 101..140) storm(seed)
    }

    private fun TestScope.storm(seed: Int) {
        val random = Random(seed)
        val f: Fixture = fixture()
        val fake: FakeIosLocationEnvironment = f.fake
        fake.backgroundMode = true
        val problems: MutableList<String> = mutableListOf()
        var readings: MutableList<LocationReading> = mutableListOf()
        var completed = false
        var job: Job? = null
        var cancelAtCall: Int = -1
        f.env.onCall = { _: String, index: Int -> if (index == cancelAtCall) job?.cancel() }

        fun begin() {
            val mine: MutableList<LocationReading> = mutableListOf()
            readings = mine
            completed = false
            val options = LocationUpdatesOptions(
                minUpdateInterval = random.nextInt(0, 4).seconds,
                minUpdateDistanceMeters = random.nextInt(0, 2) * 50.0,
                maxSeedAge = listOf(0.seconds, 30.seconds, kotlin.time.Duration.INFINITE)[random.nextInt(3)],
                platform = IosLocationUpdatesOptions(
                    allowsBackgroundLocationUpdates = random.nextBoolean(),
                    pausesLocationUpdatesAutomatically = random.nextBoolean(),
                ),
            )
            job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    f.provider.observeLocation(options).collect { reading: LocationReading -> mine += reading }
                    completed = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                }
            }
            runCurrent()
        }

        begin()
        for (step: Int in 1..250) {
            val manager: FakeIosLocationManager? = fake.liveManagers.lastOrNull()
            val before: Int = readings.size
            val wasCompleted: Boolean = completed
            val wasCancelled: Boolean = job?.isCancelled == true
            when (random.nextInt(12)) {
                0, 1 -> onMain(fake) {
                    manager?.deliver(rawFix(fake.clock, age = random.nextInt(0, 90).seconds, latitude = random.nextDouble(-80.0, 80.0)))
                }
                2 -> onMain(fake) { manager?.deliver(rawFix(fake.clock, horizontalAccuracy = -1.0), rawFix(fake.clock, latitude = Double.NaN)) }
                3 -> onMain(fake) { manager?.fail(IosFailureKind.entries[random.nextInt(IosFailureKind.entries.size)]) }
                4, 5 -> {
                    fake.servicesOn = !fake.servicesOn
                    onMain(fake) { fake.announceChange() }
                }
                6 -> {
                    fake.status = if (random.nextInt(5) == 0) kCLAuthorizationStatusDenied else kCLAuthorizationStatusAuthorizedWhenInUse
                    fake.reduced = random.nextBoolean()
                    onMain(fake) { fake.announceChange() }
                }
                7 -> onMain(fake) { if (random.nextBoolean()) manager?.pause() else manager?.resume() }
                8 -> onMain(fake) { fake.activate() }
                9 -> {
                    fake.clock.advance(random.nextInt(1, 40).seconds)
                    fake.cached = if (random.nextBoolean()) rawFix(fake.clock, age = random.nextInt(0, 60).seconds) else null
                    advanceTimeBy(random.nextInt(1, 4000).milliseconds)
                }
                10 -> cancelAtCall = f.env.calls + random.nextInt(1, 6)
                else -> {
                    job?.cancel()
                    runCurrent()
                    fake.status = kCLAuthorizationStatusAuthorizedWhenInUse
                    fake.servicesOn = true
                    begin()
                }
            }
            runCurrent()
            if ((wasCompleted || wasCancelled) && readings.size != before) problems += "step $step: a reading after the end"
            val activeJob: Job? = job
            if (completed || activeJob?.isCompleted == true) {
                if (fake.liveManagers.isNotEmpty()) problems += "step $step: ended with a live manager"
                fake.status = kCLAuthorizationStatusAuthorizedWhenInUse
                fake.servicesOn = true
                begin()
            }
            if (fake.liveManagers.size > 1) problems += "step $step: ${fake.liveManagers.size} live managers"
            fake.managers.filter { it.detached && it.updating }.forEach { _ -> problems += "step $step: a released manager is updating" }
            fake.managers.filter { it.detachCount > 1 }.forEach { _ -> problems += "step $step: double detach" }
            readings.filterIsInstance<LocationReading.Available>().filter { (it.fix.horizontalAccuracyMeters ?: 0.0) < 0.0 }
                .forEach { _ -> problems += "step $step: an invalid fix was emitted" }
            val terminalAt: Int = readings.indexOfFirst { it.endsStream }
            if (terminalAt >= 0 && terminalAt != readings.lastIndex) problems += "step $step: a reading after PERMISSION_MISSING"
        }
        job?.cancel()
        runCurrent()
        assertTrue(problems.isEmpty(), "seed=$seed: ${problems.take(5)}")
        assertTrue(f.env.offMainManagerCalls.isEmpty(), "seed=$seed: manager touched off main: ${f.env.offMainManagerCalls.take(5)}")
        assertEquals(0, f.env.servicesReadsOnMain, "seed=$seed: locationServicesEnabled read on main")
        assertTrue(fake.liveManagers.isEmpty(), "seed=$seed: a manager survived")
        assertTrue(fake.managers.all { it.detachCount == 1 }, "seed=$seed: every manager released exactly once")
        assertTrue(fake.activeListeners.isEmpty(), "seed=$seed: a did-become-active listener leaked")
        assertEquals(fake.unregistered, fake.managers.count { it.applied.firstOrNull()?.pausesLocationUpdatesAutomatically == true },
            "seed=$seed: one listener removal per opted-in collection")
    }
}
