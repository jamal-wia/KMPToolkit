package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Breaker round 9 of group loc-android (ticket 5.1.0, LOC-7 / LOC-10, protocol BREAKER-REVIEW.md, D-23, D-30).
 *
 * Attacks the round-9 fix (`StreamSession.shownDisabled`, written at every send, an unknown `disabledNow` resolved against
 * it) and extends rounds 7 and 8:
 * - THREE faults in one run of the round-8 stationary and AUTO scenarios (every triple k1 < k2 < k3 of call indices);
 * - faults combined with every request shape: 8 sources (AUTO high / balanced / low power / with fused, GPS, NETWORK,
 *   FUSED, PASSIVE) x maxSeedAge 0 / 5 s / 1 h x platform cache empty / stale / fresh x no throttle / minUpdateInterval
 *   60 s / minUpdateDistance 50 m x stationary / moving: 432 shapes, each fault-free (control) and under every single
 *   fault (every index, kind and ONCE / PERSIST), and a pairwise-covering subset under every pair of faults;
 * - a slow-collector differential: the same seeded platform history and the same 0..3 faults, collected once fast and
 *   once by a collector that is busy for hours after every element (the stream's conflation drops what it never saw);
 *   the two must end on the same reading, the truthful one, and a NEW collection after it must seed the truth;
 * - deterministic: two concurrent collections where only one meets the failure, a new collection after a cancelled one
 *   whose state was unknown, the conflated "the collector never saw SERVICE_DISABLED" path, and the terminal reading
 *   under a busy collector and a failing read.
 *
 * Virtual time (`runTest`, [StandardTestDispatcher] on the test scheduler, the throttle on `testScheduler.timeSource`),
 * no threads, no sleeps, no wall clock; every sampled or generated case prints its `seed=`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidLocationProviderBreakerR9Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val fused: String = "fused"
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val noFix: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX)
    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)
    private val settleLatitude: Double = 55.0
    private val liveLatitude: Double = 77.0
    private val allProviders: List<String> = listOf(gps, network, passive, fused)

    private enum class Kind { CHECKED, SECURITY, ILLEGAL_ARGUMENT, RUNTIME, ERROR }

    private enum class Mode { ONCE, PERSIST }

    private class InjectedError(message: String) : Error(message)

    private class InjectedChecked(message: String) : Exception(message)

    private class InjectedRuntime(message: String) : RuntimeException(message)

    private fun Kind.make(message: String): Throwable = when (this) {
        Kind.CHECKED -> InjectedChecked(message)
        Kind.SECURITY -> SecurityException(message)
        Kind.ILLEGAL_ARGUMENT -> IllegalArgumentException(message)
        Kind.RUNTIME -> InjectedRuntime(message)
        Kind.ERROR -> InjectedError(message)
    }

    private class Subscription {
        var releaseAttempted: Boolean = false
    }

    /**
     * The numbered seam of rounds 7 and 8. [faults] maps a 1-based call index to the kind it throws (any number of
     * positions, each once). [Mode.PERSIST] (single fault only): every later call of the faulted method throws too, until
     * [healed]. [armedSite]: calls of one method throw while armed, every [armedEvery]-th one, at most [armedBudget] times.
     */
    private inner class FaultyBackend(val fake: FakeLocationBackend) : LocationBackend {
        var faults: Map<Int, Kind> = emptyMap()
        var mode: Mode = Mode.ONCE
        var healed: Boolean = false
        var armedSite: String? = null
        var armedKind: Kind = Kind.RUNTIME
        var armedEvery: Int = 1
        var armedBudget: Int = Int.MAX_VALUE
        private var armedCalls: Int = 0
        var calls: Int = 0
        var injected: Int = 0
        val sites: MutableList<String> = mutableListOf()
        private var persistentSite: String? = null
        private var persistentKind: Kind = Kind.RUNTIME
        val releaseAttempted: MutableSet<FakeLocationBackend.Registration> = Collections.newSetFromMap(IdentityHashMap())
        val subscriptions: MutableList<Subscription> = mutableListOf()
        var peakRegistrations: Int = 0

        private fun <T> call(site: String, detail: String, body: () -> T): T {
            calls += 1
            val index: Int = calls
            sites += if (detail.isEmpty()) site else "$site($detail)"
            if (armedSite == site && armedBudget > 0) {
                armedCalls += 1
                if (armedCalls % armedEvery == 0) {
                    armedBudget -= 1
                    injected += 1
                    throw armedKind.make("r9-injected $armedKind at armed $site($detail)")
                }
            }
            if (!healed) {
                val kind: Kind? = faults[index]
                if (kind != null) {
                    persistentSite = site
                    persistentKind = kind
                    injected += 1
                    throw kind.make("r9-injected $kind at call $index $site($detail)")
                }
                if (mode == Mode.PERSIST && persistentSite == site) {
                    injected += 1
                    throw persistentKind.make("r9-injected $persistentKind persisting at call $index $site($detail)")
                }
            }
            return body()
        }

        fun unreleased(): List<FakeLocationBackend.Registration> =
            fake.registered().filter { registration: FakeLocationBackend.Registration -> registration !in releaseAttempted }

        override fun hasProvider(provider: String): Boolean = call("hasProvider", provider) { fake.hasProvider(provider) }

        override fun isProviderEnabled(provider: String): Boolean =
            call("isProviderEnabled", provider) { fake.isProviderEnabled(provider) }

        override fun isLocationEnabled(): Boolean = call("isLocationEnabled", "") { fake.isLocationEnabled() }

        override fun hasFinePermission(): Boolean = call("hasFinePermission", "") { fake.hasFinePermission() }

        override fun hasCoarsePermission(): Boolean = call("hasCoarsePermission", "") { fake.hasCoarsePermission() }

        override fun lastKnownLocation(provider: String): Location? =
            call("lastKnownLocation", provider) { fake.lastKnownLocation(provider) }

        override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
            call("requestUpdates", provider) { fake.requestUpdates(provider, request, listener) }
            peakRegistrations = maxOf(peakRegistrations, fake.registeredCount)
        }

        override fun removeUpdates(listener: LocationListener) {
            for (registration: FakeLocationBackend.Registration in fake.registered()) {
                if (registration.listener === listener) releaseAttempted += registration
            }
            call("removeUpdates", "") { fake.removeUpdates(listener) }
        }

        override fun requestCurrentLocation(
            provider: String,
            accuracy: LocationAccuracy,
            onResult: (Location?) -> Unit,
        ): (() -> Unit)? = call("requestCurrentLocation", provider) { fake.requestCurrentLocation(provider, accuracy, onResult) }

        override fun observeServiceChanges(onChange: () -> Unit): () -> Unit = call("observeServiceChanges", "") {
            val inner: () -> Unit = fake.observeServiceChanges(onChange)
            val subscription = Subscription()
            subscriptions += subscription
            val wrapped: () -> Unit = {
                subscription.releaseAttempted = true
                call("unsubscribe", "") { inner() }
            }
            wrapped
        }

        override fun elapsedRealtimeNanos(): Long = call("elapsedRealtimeNanos", "") { fake.elapsedRealtimeNanos() }
    }

    private fun providerOver(backend: LocationBackend, timeSource: TimeSource): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
        timeSource = timeSource,
    )

    private fun latitudeOf(reading: LocationReading?): Double? = (reading as? LocationReading.Available)?.fix?.coordinates?.latitude

    private fun describe(reading: LocationReading?): String = when (reading) {
        null -> "none"
        is LocationReading.Available -> "fix(${reading.fix.coordinates.latitude})"
        is LocationReading.Unavailable -> reading.reason.name
    }

    /** The platform keeps its cache on the newest fix it delivered (a real `LocationManager` does). */
    private fun FakeLocationBackend.deliverMirrored(provider: String, latitude: Double) {
        val location: Location = locationAt(provider, latitude)
        setCached(provider, location)
        deliver(provider, location)
    }

    private fun cacheAll(fake: FakeLocationBackend, latitude: Double, ageMillis: Long = 1_000L) {
        for (provider: String in allProviders) {
            if (fake.hasProvider(provider)) fake.setCached(provider, fake.locationAt(provider, latitude, ageMillis = ageMillis))
        }
    }

    private fun healAll(fake: FakeLocationBackend) {
        fake.fine = true
        fake.coarse = true
        for (provider: String in allProviders) {
            fake.allow(provider)
            if (fake.hasProvider(provider)) fake.setProviderEnabled(provider, true)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Scenarios
    // ---------------------------------------------------------------------------------------------

    private enum class StepKind { SERVICE, DELIVERY, QUIET }

    private class Step(
        val label: String,
        val kind: StepKind,
        val provider: String? = null,
        val latitude: Double = 0.0,
        /** Virtual time that passes after the step (the throttle's interval can end inside it). */
        val advance: Duration = Duration.ZERO,
        val apply: (FakeLocationBackend) -> Unit,
    )

    private fun service(label: String, apply: (FakeLocationBackend) -> Unit): Step = Step(label, StepKind.SERVICE, apply = apply)

    private fun quiet(label: String, apply: (FakeLocationBackend) -> Unit): Step = Step(label, StepKind.QUIET, apply = apply)

    private fun delivery(provider: String, latitude: Double): Step =
        Step("deliver $provider $latitude", StepKind.DELIVERY, provider, latitude) { fake: FakeLocationBackend ->
            fake.deliverMirrored(provider, latitude)
        }

    /** 70 s pass: the platform cache ages past a short maxSeedAge and a 60 s throttle interval ends. */
    private fun pass70s(): Step = Step("70 s pass, nothing delivered", StepKind.QUIET, advance = 70.seconds) { fake: FakeLocationBackend ->
        fake.nowNanos += 70_000_000_000L
    }

    private fun pass40s(): Step = quiet("40 s pass, nothing delivered") { fake: FakeLocationBackend -> fake.nowNanos += 40_000_000_000L }

    private class StreamScenario(
        val name: String,
        val providers: Set<String>,
        /** Every source the request can end up on (an AUTO stream moves): "every source off" is judged over these. */
        val candidates: List<String>,
        val setup: (FakeLocationBackend) -> Unit,
        val options: LocationUpdatesOptions,
        val oneShot: CurrentLocationOptions,
        val steps: List<Step>,
        val heal: (FakeLocationBackend) -> Unit,
        val terminalByDesign: Boolean = false,
    )

    private class CaseResult(val totalCalls: Int, val sites: List<String>, val peakRegistrations: Int, val subscriptions: Int)

    // Round 8's scenarios, unchanged, for the three-fault matrices.

    private val explicitGps: LocationUpdatesOptions =
        LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS))

    private val stationaryGpsStream: StreamScenario = StreamScenario(
        name = "r8-stationary-gps",
        providers = setOf(gps, network),
        candidates = listOf(gps),
        setup = { },
        options = explicitGps,
        oneShot = CurrentLocationOptions(timeout = 5.seconds, platform = AndroidCurrentLocationOptions(source = AndroidLocationSource.GPS)),
        steps = listOf(
            delivery(gps, 10.0),
            service("network off (no change for gps)") { it.setProviderEnabled(network, false) },
            service("network on (no change for gps)") { it.setProviderEnabled(network, true) },
            pass40s(),
            service("network off (no change for gps)") { it.setProviderEnabled(network, false) },
            service("network on (no change for gps)") { it.setProviderEnabled(network, true) },
            delivery(gps, 11.0),
            service("device off") { it.setDeviceLocationEnabled(false) },
            service("device on") { it.setDeviceLocationEnabled(true) },
            delivery(gps, 12.0),
            pass40s(),
            service("network off (no change for gps)") { it.setProviderEnabled(network, false) },
            service("network on (no change for gps)") { it.setProviderEnabled(network, true) },
            service("gps off") { it.setProviderEnabled(gps, false) },
        ),
        heal = ::healAll,
    )

    private val autoBalancedStream: StreamScenario = StreamScenario(
        name = "r8-auto-balanced",
        providers = setOf(gps, network),
        candidates = listOf(network, gps),
        setup = { fake: FakeLocationBackend -> cacheAll(fake, 7.0) },
        options = LocationUpdatesOptions(accuracy = LocationAccuracy.BALANCED),
        oneShot = CurrentLocationOptions(accuracy = LocationAccuracy.BALANCED, timeout = 5.seconds),
        steps = listOf(
            delivery(network, 10.0),
            service("network off (move to gps)") { it.setProviderEnabled(network, false) },
            delivery(gps, 11.0),
            service("network on (move back)") { it.setProviderEnabled(network, true) },
            delivery(network, 12.0),
            pass40s(),
            service("gps off (no change for network)") { it.setProviderEnabled(gps, false) },
            service("gps on (no change for network)") { it.setProviderEnabled(gps, true) },
            service("device off") { it.setDeviceLocationEnabled(false) },
            service("device on") { it.setDeviceLocationEnabled(true) },
            service("network off (move to gps)") { it.setProviderEnabled(network, false) },
            delivery(gps, 13.0),
            service("network on (move back)") { it.setProviderEnabled(network, true) },
            service("device off") { it.setDeviceLocationEnabled(false) },
        ),
        heal = ::healAll,
    )

    private val waitingThenGrantStream: StreamScenario = StreamScenario(
        name = "r8-waiting-then-grant",
        providers = setOf(gps),
        candidates = listOf(gps),
        setup = { fake: FakeLocationBackend ->
            fake.fine = false
            fake.refuse(gps)
            fake.locationEnabled = false
            fake.setCached(gps, fake.locationAt(gps, 7.0, ageMillis = 1_000L))
        },
        options = explicitGps,
        oneShot = CurrentLocationOptions(timeout = 5.seconds, platform = AndroidCurrentLocationOptions(source = AndroidLocationSource.GPS)),
        steps = listOf(
            service("gps flap while waiting") { it.setProviderEnabled(gps, true) },
            quiet("fine grant lands") { fake: FakeLocationBackend ->
                fake.fine = true
                fake.allow(gps)
            },
            service("broadcast after the grant") { it.setProviderEnabled(gps, true) },
            service("device on") { it.setDeviceLocationEnabled(true) },
            delivery(gps, 10.0),
            service("gps flap on (no change)") { it.setProviderEnabled(gps, true) },
            service("device off") { it.setDeviceLocationEnabled(false) },
            service("device on") { it.setDeviceLocationEnabled(true) },
            delivery(gps, 11.0),
            service("gps off") { it.setProviderEnabled(gps, false) },
        ),
        heal = ::healAll,
    )

    private val waitingTerminalStream: StreamScenario = StreamScenario(
        name = "r8-waiting-terminal",
        providers = setOf(gps),
        candidates = listOf(gps),
        setup = { fake: FakeLocationBackend ->
            fake.fine = false
            fake.refuse(gps)
            fake.locationEnabled = false
        },
        options = explicitGps,
        oneShot = CurrentLocationOptions(timeout = 5.seconds, platform = AndroidCurrentLocationOptions(source = AndroidLocationSource.GPS)),
        steps = listOf(
            service("gps flap while waiting") { it.setProviderEnabled(gps, true) },
            service("gps flap while waiting again") { it.setProviderEnabled(gps, true) },
            service("device on (terminal)") { it.setDeviceLocationEnabled(true) },
            service("device on again") { it.setDeviceLocationEnabled(true) },
        ),
        heal = ::healAll,
        terminalByDesign = true,
    )

    // The request shapes.

    /**
     * One source of a request: the providers the device has, the request, the provider the shape delivers on, one whose
     * switch changes nothing for the request ([other]) and the union of sources the stream can end up on.
     */
    private class Source(
        val name: String,
        val providers: Set<String>,
        val accuracy: LocationAccuracy,
        val source: AndroidLocationSource,
        val primary: String,
        val other: String,
        val candidates: List<String>,
    )

    private val sources: List<Source> = listOf(
        Source("auto-high", setOf(gps, network, passive), LocationAccuracy.HIGH_ACCURACY, AndroidLocationSource.AUTO, gps, network, listOf(gps, network)),
        Source("auto-balanced", setOf(gps, network, passive), LocationAccuracy.BALANCED, AndroidLocationSource.AUTO, network, gps, listOf(network, gps)),
        Source("auto-low", setOf(gps, network, passive), LocationAccuracy.LOW_POWER, AndroidLocationSource.AUTO, network, gps, listOf(network, passive)),
        Source("auto-fused", setOf(fused, gps, network), LocationAccuracy.HIGH_ACCURACY, AndroidLocationSource.AUTO, fused, passive, listOf(fused, gps, network)),
        Source("gps", setOf(gps, network, passive), LocationAccuracy.HIGH_ACCURACY, AndroidLocationSource.GPS, gps, network, listOf(gps)),
        Source("network", setOf(gps, network, passive), LocationAccuracy.BALANCED, AndroidLocationSource.NETWORK, network, gps, listOf(network)),
        Source("fused", setOf(fused, gps, network), LocationAccuracy.BALANCED, AndroidLocationSource.FUSED, fused, gps, listOf(fused)),
        Source("passive", setOf(gps, network, passive), LocationAccuracy.LOW_POWER, AndroidLocationSource.PASSIVE, passive, gps, listOf(passive)),
    )

    private enum class Cache { EMPTY, STALE, FRESH }

    private enum class Throttle { NONE, INTERVAL, DISTANCE }

    private val seedAges: List<Duration> = listOf(Duration.ZERO, 5.seconds, 1.hours)

    private fun shape(source: Source, seedAge: Duration, cache: Cache, throttle: Throttle, moving: Boolean): StreamScenario {
        val name = "${source.name};seedAge=$seedAge;cache=$cache;throttle=$throttle;${if (moving) "moving" else "stationary"}"
        fun lat(index: Int): Double = if (moving) 10.0 + index else 10.0
        // "other" may be absent on the device (auto-fused has no passive): toggling an absent provider is still a broadcast.
        val other: String = source.other
        val primary: String = source.primary
        val options = LocationUpdatesOptions(
            accuracy = source.accuracy,
            minUpdateInterval = if (throttle == Throttle.INTERVAL) 60.seconds else Duration.ZERO,
            minUpdateDistanceMeters = if (throttle == Throttle.DISTANCE) 50.0 else 0.0,
            maxSeedAge = seedAge,
            platform = AndroidLocationUpdatesOptions(source = source.source),
        )
        val oneShot = CurrentLocationOptions(
            accuracy = source.accuracy,
            maxAge = seedAge,
            timeout = 5.seconds,
            platform = AndroidCurrentLocationOptions(source = source.source),
        )
        return StreamScenario(
            name = name,
            providers = source.providers,
            candidates = source.candidates,
            setup = { fake: FakeLocationBackend ->
                when (cache) {
                    Cache.EMPTY -> Unit
                    Cache.STALE -> cacheAll(fake, 7.0, ageMillis = 2 * 3_600_000L)
                    Cache.FRESH -> cacheAll(fake, 7.0, ageMillis = 1_000L)
                }
            },
            options = options,
            oneShot = oneShot,
            steps = listOf(
                delivery(primary, lat(0)),
                service("$other off (no change)") { it.setProviderEnabled(other, false) },
                delivery(primary, lat(1)),
                service("$other on (no change)") { it.setProviderEnabled(other, true) },
                pass70s(),
                service("$other off (no change)") { it.setProviderEnabled(other, false) },
                service("$other on (no change)") { it.setProviderEnabled(other, true) },
                delivery(primary, lat(2)),
                service("device off") { it.setDeviceLocationEnabled(false) },
                service("device off again (no change)") { it.setDeviceLocationEnabled(false) },
                service("device on") { it.setDeviceLocationEnabled(true) },
                delivery(primary, lat(3)),
                delivery(primary, lat(4)),
                pass70s(),
                service("$primary off") { it.setProviderEnabled(primary, false) },
                service("$other flap (no change)") { it.setProviderEnabled(other, true) },
                service("$primary on") { it.setProviderEnabled(primary, true) },
                delivery(primary, lat(5)),
                service("device on again (no change)") { it.setDeviceLocationEnabled(true) },
                pass70s(),
                service("device off") { it.setDeviceLocationEnabled(false) },
            ),
            heal = ::healAll,
        )
    }

    private fun allShapes(source: Source): List<StreamScenario> {
        val shapes: MutableList<StreamScenario> = mutableListOf()
        for (seedAge: Duration in seedAges) {
            for (cache: Cache in Cache.entries) {
                for (throttle: Throttle in Throttle.entries) {
                    for (moving: Boolean in listOf(false, true)) shapes += shape(source, seedAge, cache, throttle, moving)
                }
            }
        }
        return shapes
    }

    /** A pairwise-covering subset: every source with every value of every other dimension at least once. */
    private fun coveringShapes(): List<StreamScenario> = sources.flatMapIndexed { index: Int, source: Source ->
        (0 until 3).map { offset: Int ->
            val row: Int = index + offset
            shape(
                source,
                seedAges[row % 3],
                Cache.entries[(row + offset) % 3],
                Throttle.entries[(row * 2 + offset) % 3],
                moving = (row + offset) % 2 == 0,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The stream case runner (round 8's oracle, made aware of the throttle and of maxSeedAge)
    // ---------------------------------------------------------------------------------------------

    /** What a stream seeds from a cache written [settleLatitude] 1 s ago, judged by maxSeedAge. */
    private fun expectedSeed(options: LocationUpdatesOptions): String =
        if (options.maxSeedAge >= 1.seconds) "fix($settleLatitude)" else describe(noFix)

    private fun TestScope.runStreamCase(
        scenario: StreamScenario,
        faults: Map<Int, Kind>,
        mode: Mode,
        label: String,
        problems: MutableList<String>,
    ): CaseResult {
        val fake = FakeLocationBackend(providers = scenario.providers)
        scenario.setup(fake)
        val backend = FaultyBackend(fake).apply {
            this.faults = faults
            this.mode = mode
        }
        val interval: Duration = scenario.options.minUpdateInterval
        val errorCase: Boolean = Kind.ERROR in faults.values
        val securityCase: Boolean = Kind.SECURITY in faults.values
        val uncaught: MutableList<Throwable> = mutableListOf()
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() +
                CoroutineExceptionHandler { _, thrown: Throwable -> uncaught += thrown },
        )
        val provider: LocationProvider = providerOver(backend, testScheduler.timeSource)
        val seen: MutableList<LocationReading> = mutableListOf()
        var failure: Throwable? = null
        var completed = false
        fun problem(message: String) {
            problems += "$label: $message; readings=${seen.map(::describe)}; failure=$failure"
        }
        fun errorAllowed(thrown: Throwable): Boolean = errorCase && (thrown is InjectedError || thrown.cause is InjectedError)
        fun fire(stepLabel: String, advance: Duration = Duration.ZERO, apply: () -> Unit) {
            val escaped: Throwable? = try {
                apply()
                null
            } catch (thrown: Throwable) {
                thrown
            }
            runCurrent()
            if (advance > Duration.ZERO) {
                advanceTimeBy(advance)
                runCurrent()
            }
            if (escaped != null && !errorAllowed(escaped)) problem("$stepLabel: a throwable escaped into the platform callback: $escaped")
        }
        fun checkDuplicates(where: String) {
            val pairs: List<Pair<Int, String>> = backend.unreleased().map { registration: FakeLocationBackend.Registration ->
                System.identityHashCode(registration.listener) to registration.provider
            }
            if (pairs.size != pairs.toSet().size) problem("$where: a (listener, provider) pair is registered twice: $pairs")
        }
        fun checkLedger(where: String) {
            val left: List<String> = backend.unreleased().map { registration: FakeLocationBackend.Registration -> registration.provider }
            if (left.isNotEmpty()) problem("$where: registrations never released: $left")
            val subscriptions: Int = backend.subscriptions.count { subscription: Subscription -> !subscription.releaseAttempted }
            if (subscriptions != 0) problem("$where: $subscriptions service subscription(s) never released")
        }
        fun anyCandidateOn(): Boolean = fake.locationEnabled && scenario.candidates.any { candidate: String -> fake.isProviderEnabled(candidate) }
        fun collect(into: MutableList<LocationReading>, onEnd: (Throwable?) -> Unit): Job = scope.launch {
            try {
                provider.observeLocation(scenario.options).collect { reading: LocationReading -> into += reading }
                onEnd(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                onEnd(thrown)
            }
        }

        val job: Job = collect(seen) { thrown: Throwable? -> if (thrown == null) completed = true else failure = thrown }
        runCurrent()
        val faultedAtStart: Boolean = backend.injected > 0
        // The throttle can hold a far-enough fix inside its interval: the newest such delivery, and whether every step
        // since it was judged (a faulted step can drop or add a fix the oracle did not see).
        var pending: Double? = null
        var pendingReliable = true
        val deliveredSinceReason: MutableSet<Double> = mutableSetOf()
        // Whether the shown position came only through a registration whose removal failed (the fake keeps it; a real
        // LocationManager replaces a re-registered listener, so that fix would not exist): an artefact of the fake, not
        // judged by the "position replaced" invariant (round 8 observation).
        var shownFromLeak = false
        for ((index: Int, step: Step) in scenario.steps.withIndex()) {
            val stepNo: Int = index + 1
            val leakBefore: Boolean = shownFromLeak
            val injectedBefore: Int = backend.injected
            val wasAlive: Boolean = failure == null && !completed
            val readingBefore: LocationReading? = seen.lastOrNull()
            val countBefore: Int = seen.size
            val onBefore: Boolean = anyCandidateOn()
            val callbacks: Int = when (step.kind) {
                StepKind.DELIVERY -> maxOf(1, fake.registered().count { registration: FakeLocationBackend.Registration -> registration.provider == step.provider })
                // A pass can end the throttle's interval: one pending fix.
                StepKind.QUIET -> 1
                StepKind.SERVICE -> 1 + fake.registeredCount
            }
            if (step.kind == StepKind.DELIVERY) deliveredSinceReason += step.latitude
            fire("step $stepNo ${step.label}", step.advance) { step.apply(fake) }
            val faultedHere: Boolean = backend.injected > injectedBefore
            checkDuplicates("step $stepNo ${step.label}")
            val alive: Boolean = failure == null && !completed
            val added: List<LocationReading> = seen.drop(countBefore)
            if (added.any { reading: LocationReading -> reading is LocationReading.Unavailable }) deliveredSinceReason.clear()
            if (added.isNotEmpty()) {
                shownFromLeak = step.kind == StepKind.DELIVERY &&
                    backend.unreleased().none { registration: FakeLocationBackend.Registration -> registration.provider == step.provider }
            }
            if (wasAlive && added.size > callbacks) {
                problem("step $stepNo ${step.label}: $callbacks platform callback(s) produced ${added.size} readings: ${added.map(::describe)}")
            }
            val persisting: Boolean = mode == Mode.PERSIST && backend.injected > 0
            val oracleApplies: Boolean = wasAlive && alive && !faultedHere && !persisting
            if (!oracleApplies) {
                pendingReliable = false
                continue
            }
            val last: LocationReading? = seen.lastOrNull()
            when (step.kind) {
                StepKind.SERVICE -> {
                    if (added.isNotEmpty()) pending = null
                    val anyOn: Boolean = anyCandidateOn()
                    val registeredOn: Boolean = fake.locationEnabled &&
                        backend.unreleased().any { registration: FakeLocationBackend.Registration -> fake.isProviderEnabled(registration.provider) }
                    if (!anyOn && last != serviceDisabled) {
                        problem("step $stepNo ${step.label}: every source is off but the stream reads ${describe(last)}")
                    }
                    if (registeredOn && last == serviceDisabled) {
                        problem("step $stepNo ${step.label}: a registered source is on but the stream reads SERVICE_DISABLED")
                    }
                    if (!leakBefore && onBefore && anyOn && readingBefore is LocationReading.Available && last != serviceDisabled &&
                        describe(last) != describe(readingBefore)
                    ) {
                        problem(
                            "step $stepNo ${step.label}: nothing turned off or on for the request, yet the position " +
                                "${describe(readingBefore)} was replaced by ${describe(last)}",
                        )
                    }
                }
                StepKind.DELIVERY -> {
                    val registered: Boolean = backend.unreleased().any { registration: FakeLocationBackend.Registration ->
                        registration.provider == step.provider && fake.isProviderEnabled(registration.provider) && fake.locationEnabled
                    }
                    if (registered) {
                        when {
                            latitudeOf(last) == step.latitude -> pending = null
                            interval > Duration.ZERO && added.isEmpty() -> pending = step.latitude
                            else -> problem("step $stepNo ${step.label}: the delivered fix is not the reading: ${describe(last)}")
                        }
                    }
                }
                StepKind.QUIET -> {
                    val held: Double? = pending
                    val stray: List<LocationReading> = added.filterNot { reading: LocationReading ->
                        interval > Duration.ZERO && latitudeOf(reading) in deliveredSinceReason
                    }
                    if (stray.isNotEmpty()) problem("step $stepNo ${step.label}: a reading without a platform event: ${stray.map(::describe)}")
                    if (step.advance >= interval && interval > Duration.ZERO && held != null && pendingReliable && latitudeOf(last) != held) {
                        problem("step $stepNo ${step.label}: the interval ended but the held fix($held) was never emitted")
                    }
                    if (step.advance >= interval) {
                        pending = null
                        pendingReliable = true
                    }
                }
            }
        }
        val totalCalls: Int = backend.calls
        val sites: List<String> = backend.sites.toList()

        failure?.let { thrown: Throwable ->
            val allowed: Boolean = (faultedAtStart && thrown.message?.startsWith("r9-injected") == true) || errorAllowed(thrown)
            if (!allowed) problem("the collector failed with a fault outside the start: $thrown")
        }
        if (completed) {
            val last: LocationReading? = seen.lastOrNull()
            if (last != permissionMissing) problem("the stream completed after ${describe(last)}")
            if (!scenario.terminalByDesign && !securityCase) problem("the stream ended with PERMISSION_MISSING for faults $faults")
        }
        if (failure != null || completed) checkLedger("after the stream ended")

        backend.healed = true
        val seed: String = expectedSeed(scenario.options)
        if (failure == null && !completed) {
            fire("settle: device off") { fake.setDeviceLocationEnabled(false) }
            if (!completed && seen.lastOrNull() != serviceDisabled) problem("settle: device off reads ${describe(seen.lastOrNull())}")
            fire("settle: heal") { scenario.heal(fake) }
            cacheAll(fake, settleLatitude)
            fire("settle: device on") { fake.setDeviceLocationEnabled(true) }
            if (completed) {
                if (!securityCase || seen.lastOrNull() != permissionMissing) problem("settle: the stream ended after ${describe(seen.lastOrNull())}")
                checkLedger("settle: after the stream ended")
            } else {
                if (describe(seen.lastOrNull()) != seed) {
                    problem("settle: device on with every source on reads ${describe(seen.lastOrNull())}, expected the seed $seed")
                }
                val oneShot: Deferred<LocationReading> = scope.async { provider.getCurrentLocation(scenario.oneShot) }
                runCurrent()
                advanceTimeBy(scenario.oneShot.timeout + 1.seconds)
                runCurrent()
                val answer: LocationReading? = if (oneShot.isCompleted && oneShot.getCompletionExceptionOrNull() == null) oneShot.getCompleted() else null
                if (describe(answer) != describe(seen.lastOrNull())) {
                    problem("settle: stream and one-shot disagree: stream ${describe(seen.lastOrNull())}, one-shot ${describe(answer)}")
                }
                fire("settle: live fix") { fake.deliverToAll(fake.locationAt(gps, liveLatitude)) }
                if (latitudeOf(seen.lastOrNull()) != liveLatitude) problem("settle: a live fix to every registration reads ${describe(seen.lastOrNull())}")
                val settled: Int = seen.size
                fire("settle: no-change broadcast") { fake.setDeviceLocationEnabled(true) }
                if (seen.size != settled) problem("settle: a broadcast that changes nothing sent ${describe(seen.lastOrNull())}")
                checkDuplicates("settle")
                job.cancel()
                runCurrent()
                checkLedger("after cancel")
            }
        } else {
            fire("re-init: heal") { scenario.heal(fake) }
            fake.setDeviceLocationEnabled(true)
            cacheAll(fake, settleLatitude)
            val again: MutableList<LocationReading> = mutableListOf()
            var againEnd: Throwable? = null
            var againCompleted = false
            val second: Job = collect(again) { thrown: Throwable? -> if (thrown == null) againCompleted = true else againEnd = thrown }
            runCurrent()
            if (describe(again.lastOrNull()) != seed || againEnd != null || againCompleted) {
                problem("re-init: a new collection on the healed world reads ${again.map(::describe)}, expected $seed, failure=$againEnd, completed=$againCompleted")
            }
            fire("re-init: live fix") { fake.deliverToAll(fake.locationAt(gps, liveLatitude)) }
            if (latitudeOf(again.lastOrNull()) != liveLatitude) problem("re-init: a live fix reads ${again.map(::describe)}")
            second.cancel()
            runCurrent()
            checkLedger("re-init: after cancel")
        }
        val stray: List<Throwable> = uncaught.filterNot(::errorAllowed)
        if (stray.isNotEmpty()) problem("uncaught exceptions: $stray")
        scope.cancel()
        return CaseResult(totalCalls, sites, backend.peakRegistrations, backend.subscriptions.size)
    }

    private fun TestScope.dryRun(scenario: StreamScenario): CaseResult {
        val dryProblems: MutableList<String> = mutableListOf()
        val dry: CaseResult = runStreamCase(scenario, emptyMap(), Mode.ONCE, "seed=0 ${scenario.name} fault-free", dryProblems)
        assertTrue(dryProblems.isEmpty(), "control: the fault-free run of ${scenario.name} meets every invariant: $dryProblems")
        assertTrue(dry.totalCalls > 10, "control: ${scenario.name} makes platform calls: ${dry.totalCalls}")
        if (!scenario.terminalByDesign) assertTrue(dry.peakRegistrations > 0, "control: ${scenario.name} registers a listener")
        assertTrue(dry.subscriptions > 0, "control: ${scenario.name} subscribes to the service broadcast")
        return dry
    }

    private fun failIfAny(what: String, cases: Int, problems: List<String>) {
        if (problems.isNotEmpty()) {
            fail("${problems.size} invariant violation(s) in $cases $what cases; first 12:\n" + problems.take(12).joinToString("\n"))
        }
    }

    private fun TestScope.exhaustSingle(scenario: StreamScenario, problems: MutableList<String>): Int {
        val dry: CaseResult = dryRun(scenario)
        var case = 0
        for (k: Int in 1..dry.totalCalls) {
            for (kind: Kind in Kind.entries) {
                for (mode: Mode in Mode.entries) {
                    case += 1
                    val label = "seed=$case ${scenario.name} k=$k site=${dry.sites[k - 1]} kind=$kind mode=$mode"
                    runStreamCase(scenario, mapOf(k to kind), mode, label, problems)
                }
            }
        }
        return case
    }

    private fun TestScope.exhaustPairs(scenario: StreamScenario, combos: List<Pair<Kind, Kind>>, problems: MutableList<String>): Int {
        val dry: CaseResult = dryRun(scenario)
        var case = 0
        val limit: Int = dry.totalCalls + 8
        for (k1: Int in 1..dry.totalCalls) {
            for (k2: Int in (k1 + 1)..limit) {
                for ((first: Kind, second: Kind) in combos) {
                    case += 1
                    val label = "seed=$case ${scenario.name} k1=$k1 (${dry.sites[k1 - 1]}) $first k2=$k2 $second"
                    runStreamCase(scenario, mapOf(k1 to first, k2 to second), Mode.ONCE, label, problems)
                }
            }
        }
        return case
    }

    /** Every triple k1 < k2 < k3 (k1 within the fault-free run, the later ones up to 8 calls past it). */
    private fun TestScope.exhaustTriples(scenario: StreamScenario, combos: List<Triple<Kind, Kind, Kind>>): Int {
        val dry: CaseResult = dryRun(scenario)
        val problems: MutableList<String> = mutableListOf()
        var case = 0
        val limit: Int = dry.totalCalls + 8
        for (k1: Int in 1..dry.totalCalls) {
            for (k2: Int in (k1 + 1)..limit) {
                for (k3: Int in (k2 + 1)..limit) {
                    for ((first: Kind, second: Kind, third: Kind) in combos) {
                        case += 1
                        val label = "seed=$case ${scenario.name} k1=$k1 (${dry.sites[k1 - 1]}) $first k2=$k2 $second k3=$k3 $third"
                        runStreamCase(scenario, mapOf(k1 to first, k2 to second, k3 to third), Mode.ONCE, label, problems)
                    }
                }
            }
        }
        failIfAny("three-fault ${scenario.name} (T=${dry.totalCalls})", case, problems)
        return case
    }

    private val tripleCombos: List<Triple<Kind, Kind, Kind>> = listOf(
        Triple(Kind.RUNTIME, Kind.RUNTIME, Kind.RUNTIME),
        Triple(Kind.CHECKED, Kind.SECURITY, Kind.ERROR),
    )

    // ---------------------------------------------------------------------------------------------
    // Three faults per run
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `three faults in one run of the stationary gps stream`() = runTest(timeout = 900.seconds) {
        val cases: Int = exhaustTriples(stationaryGpsStream, tripleCombos)
        assertTrue(cases > 10_000, "control: the triples were enumerated: $cases")
    }

    @Test
    fun `three faults in one run of the AUTO stream with moves`() = runTest(timeout = 900.seconds) {
        val cases: Int = exhaustTriples(autoBalancedStream, listOf(Triple(Kind.RUNTIME, Kind.CHECKED, Kind.RUNTIME)))
        assertTrue(cases > 100_000, "control: the triples were enumerated: $cases")
    }

    @Test
    fun `three faults in one run of waiting streams`() = runTest(timeout = 900.seconds) {
        exhaustTriples(waitingThenGrantStream, tripleCombos)
        exhaustTriples(waitingTerminalStream, tripleCombos)
    }

    // ---------------------------------------------------------------------------------------------
    // Request shapes x faults
    // ---------------------------------------------------------------------------------------------

    private fun TestScope.singleFaultsOver(source: Source) {
        val problems: MutableList<String> = mutableListOf()
        var cases = 0
        val shapes: List<StreamScenario> = allShapes(source)
        assertEquals(54, shapes.size, "control: every shape of ${source.name}")
        for (scenario: StreamScenario in shapes) cases += exhaustSingle(scenario, problems)
        failIfAny("single-fault ${source.name} (54 shapes)", cases, problems)
    }

    @Test
    fun `single faults over every shape of AUTO high accuracy`() = runTest(timeout = 900.seconds) { singleFaultsOver(sources[0]) }

    @Test
    fun `single faults over every shape of AUTO balanced`() = runTest(timeout = 900.seconds) { singleFaultsOver(sources[1]) }

    @Test
    fun `single faults over every shape of AUTO low power`() = runTest(timeout = 900.seconds) { singleFaultsOver(sources[2]) }

    @Test
    fun `single faults over every shape of AUTO with a fused provider`() = runTest(timeout = 900.seconds) { singleFaultsOver(sources[3]) }

    @Test
    fun `single faults over every shape of an explicit GPS source`() = runTest(timeout = 900.seconds) { singleFaultsOver(sources[4]) }

    @Test
    fun `single faults over every shape of an explicit NETWORK source`() = runTest(timeout = 900.seconds) { singleFaultsOver(sources[5]) }

    @Test
    fun `single faults over every shape of an explicit FUSED source`() = runTest(timeout = 900.seconds) { singleFaultsOver(sources[6]) }

    @Test
    fun `single faults over every shape of an explicit PASSIVE source`() = runTest(timeout = 900.seconds) { singleFaultsOver(sources[7]) }

    @Test
    fun `two faults over a pairwise-covering set of shapes`() = runTest(timeout = 900.seconds) {
        val problems: MutableList<String> = mutableListOf()
        var cases = 0
        val shapes: List<StreamScenario> = coveringShapes()
        assertEquals(24, shapes.size, "control: the covering set")
        for (dimension: Int in 0 until 4) {
            val values: Set<String> = shapes.map { scenario: StreamScenario -> scenario.name.split(';')[dimension] }.toSet()
            assertTrue(values.size >= 2, "control: the covering set varies dimension $dimension: $values")
        }
        for (scenario: StreamScenario in shapes) {
            cases += exhaustPairs(scenario, listOf(Kind.RUNTIME to Kind.CHECKED, Kind.SECURITY to Kind.ERROR), problems)
        }
        failIfAny("two-fault covering", cases, problems)
    }

    // ---------------------------------------------------------------------------------------------
    // Slow-collector differential (conflation) with 0..3 seeded faults, and a new collection after it
    // ---------------------------------------------------------------------------------------------

    private class Outcome(
        val readings: List<String>,
        val last: String,
        val failed: Throwable?,
        val completed: Boolean,
        val calls: Int,
        val truthOff: Boolean,
        /** A source the stream holds (registered, release never attempted) is on: SERVICE_DISABLED would be wrong. */
        val registeredOn: Boolean,
    )

    /**
     * One seeded platform history over [source]: deliveries only to an enabled primary on an enabled device (no fix
     * after a switch-off: the accepted "newest event" case is kept out of the oracle), switches of the primary, of the
     * other provider and of the device, time passing. [slow]: the collector is busy for 10 h after every element, so the
     * stream's conflation drops everything in between. The history ends with a no-change broadcast on a healed platform.
     */
    private fun TestScope.runHistory(source: Source, options: LocationUpdatesOptions, seed: Int, faults: Map<Int, Kind>, slow: Boolean): Outcome {
        val random = Random(seed)
        val fake = FakeLocationBackend(providers = source.providers)
        if (random.nextBoolean()) cacheAll(fake, 7.0, ageMillis = random.nextLong(0L, 7_200_000L))
        val backend = FaultyBackend(fake).apply { this.faults = faults }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        val provider: LocationProvider = providerOver(backend, testScheduler.timeSource)
        val seen: MutableList<LocationReading> = mutableListOf()
        var failed: Throwable? = null
        var completed = false
        val job: Job = scope.launch {
            try {
                provider.observeLocation(options).collect { reading: LocationReading ->
                    seen += reading
                    if (slow) delay(10.hours)
                }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                failed = thrown
            }
        }
        runCurrent()
        var latitude = 20.0
        repeat(40) {
            val action: Int = random.nextInt(10)
            try {
                when {
                    action < 4 -> if (fake.locationEnabled && fake.isProviderEnabled(source.primary)) {
                        latitude += if (random.nextBoolean()) 1.0 else 0.0
                        fake.deliverMirrored(source.primary, latitude)
                    }
                    action == 4 -> fake.setProviderEnabled(source.primary, random.nextBoolean())
                    action == 5 -> fake.setProviderEnabled(source.other, random.nextBoolean())
                    action == 6 -> fake.setDeviceLocationEnabled(random.nextBoolean())
                    action == 7 -> fake.setDeviceLocationEnabled(fake.locationEnabled)
                    else -> {
                        val seconds: Long = random.nextLong(1L, 90L)
                        fake.nowNanos += seconds * 1_000_000_000L
                        advanceTimeBy(seconds.seconds)
                    }
                }
            } catch (escaped: Throwable) {
                // An injected Error may surface from a callback; it is not what this test judges.
            }
            runCurrent()
        }
        backend.healed = true
        fake.setDeviceLocationEnabled(fake.locationEnabled)
        runCurrent()
        advanceTimeBy(40.hours)
        runCurrent()
        val calls: Int = backend.calls
        val truthOff: Boolean = !fake.locationEnabled || source.candidates.none { candidate: String -> fake.isProviderEnabled(candidate) }
        val registeredOn: Boolean = fake.locationEnabled &&
            backend.unreleased().any { registration: FakeLocationBackend.Registration -> fake.isProviderEnabled(registration.provider) }
        val outcome = Outcome(seen.map(::describe), describe(seen.lastOrNull()), failed, completed, calls, truthOff, registeredOn)
        job.cancel()
        runCurrent()
        // A NEW collection after the previous one ended (failed evaluations included): it seeds the truth and a no-change
        // broadcast sends nothing more; then it releases everything.
        val again: MutableList<LocationReading> = mutableListOf()
        val second: Job = scope.launch { provider.observeLocation(options).collect { reading: LocationReading -> again += reading } }
        runCurrent()
        val seeded: LocationReading? = again.lastOrNull()
        val sizeAfterSeed: Int = again.size
        fake.setDeviceLocationEnabled(fake.locationEnabled)
        runCurrent()
        val secondProblems: MutableList<String> = mutableListOf()
        if ((seeded == serviceDisabled) != truthOff) secondProblems += "a new collection seeds ${describe(seeded)} while off=$truthOff"
        if (again.size != sizeAfterSeed) secondProblems += "a no-change broadcast after the new seed sent ${again.drop(sizeAfterSeed).map(::describe)}"
        second.cancel()
        runCurrent()
        val left: List<String> = backend.unreleased().map { registration: FakeLocationBackend.Registration -> registration.provider }
        val subscribed: Int = backend.subscriptions.count { subscription: Subscription -> !subscription.releaseAttempted }
        if (left.isNotEmpty() || subscribed != 0) secondProblems += "never released: registrations=$left subscriptions=$subscribed"
        scope.cancel()
        if (secondProblems.isNotEmpty()) throw AssertionError("seed=$seed ${source.name} slow=$slow faults=$faults: $secondProblems")
        return outcome
    }

    @Test
    fun `a busy collector and a fast one end on the same truthful reading under 0 to 3 faults`() = runTest(timeout = 900.seconds) {
        val problems: MutableList<String> = mutableListOf()
        var cases = 0
        var faultedCases = 0
        for (source: Source in sources) {
            for (throttle: Throttle in Throttle.entries) {
                val options = LocationUpdatesOptions(
                    accuracy = source.accuracy,
                    minUpdateInterval = if (throttle == Throttle.INTERVAL) 60.seconds else Duration.ZERO,
                    minUpdateDistanceMeters = if (throttle == Throttle.DISTANCE) 50.0 else 0.0,
                    maxSeedAge = seedAges[cases % 3],
                    platform = AndroidLocationUpdatesOptions(source = source.source),
                )
                for (seed: Int in 1..40) {
                    cases += 1
                    val dry: Outcome = runHistory(source, options, seed, emptyMap(), slow = false)
                    val pick = Random(seed * 7_919)
                    val count: Int = pick.nextInt(0, 4)
                    val kinds: List<Kind> = listOf(Kind.RUNTIME, Kind.CHECKED, Kind.SECURITY, Kind.ILLEGAL_ARGUMENT, Kind.ERROR)
                    val faults: Map<Int, Kind> = (1..count).associate { _: Int -> pick.nextInt(1, dry.calls + 1) to kinds[pick.nextInt(kinds.size)] }
                    if (faults.isNotEmpty()) faultedCases += 1
                    val fast: Outcome = runHistory(source, options, seed, faults, slow = false)
                    val slow: Outcome = runHistory(source, options, seed, faults, slow = true)
                    val label = "seed=$seed ${source.name} throttle=$throttle seedAge=${options.maxSeedAge} faults=$faults"
                    if (fast.calls != slow.calls) problems += "$label: control: the collector's speed changed the platform calls ${fast.calls} vs ${slow.calls}"
                    if (fast.last != slow.last) {
                        problems += "$label: the busy collector ends on ${slow.last} (saw ${slow.readings}), the fast one on ${fast.last} (saw ${fast.readings})"
                    }
                    if ((fast.failed == null) != (slow.failed == null) || fast.completed != slow.completed) {
                        problems += "$label: the end differs: fast failed=${fast.failed} completed=${fast.completed}, slow failed=${slow.failed} completed=${slow.completed}"
                    }
                    val disabledShown: Boolean = fast.last == describe(serviceDisabled)
                    if (fast.failed == null && !fast.completed && ((fast.truthOff && !disabledShown) || (fast.registeredOn && disabledShown))) {
                        problems += "$label: after a healed no-change broadcast the stream reads ${fast.last} while off=${fast.truthOff} " +
                            "registeredOn=${fast.registeredOn}; saw ${fast.readings}"
                    }
                    if (dry.last != describe(serviceDisabled) && dry.truthOff) problems += "$label: control: the fault-free run is not truthful: ${dry.readings}"
                }
            }
        }
        assertTrue(faultedCases > cases / 2, "control: most cases carry faults: $faultedCases of $cases")
        failIfAny("busy-vs-fast", cases, problems)
    }

    // ---------------------------------------------------------------------------------------------
    // Deterministic attacks
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: state shared between collections (a session's unknown record leaking into another). Two collections
     * of one explicit gps stream; the first service broadcast reaches collection A first and its device-switch read fails
     * (A unknown), B's evaluation succeeds. A no-change broadcast then sends nothing to either; the switch-off reaches
     * both exactly once; both release. Red if the record or the shown state were per provider instead of per collection.
     */
    @Test
    fun `two concurrent collections keep their unknown state apart`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val backend = FaultyBackend(fake)
        val provider: LocationProvider = providerOver(backend, testScheduler.timeSource)
        val first: MutableList<LocationReading> = mutableListOf()
        val second: MutableList<LocationReading> = mutableListOf()
        val a: Job = backgroundScope.launch { provider.observeLocation(explicitGps).collect { first += it } }
        runCurrent()
        val b: Job = backgroundScope.launch { provider.observeLocation(explicitGps).collect { second += it } }
        runCurrent()
        fake.deliverMirrored(gps, 10.0)
        runCurrent()
        assertEquals(listOf("NO_FIX", "fix(10.0)"), first.map(::describe), "control: A seeded and got the fix")
        assertEquals(listOf("NO_FIX", "fix(10.0)"), second.map(::describe), "control: B seeded and got the fix")
        backend.armedSite = "isLocationEnabled"
        backend.armedBudget = 1
        fake.setProviderEnabled(network, false)
        runCurrent()
        assertEquals(1, backend.injected, "control: exactly one evaluation failed")
        fake.setProviderEnabled(network, true)
        runCurrent()
        assertEquals(listOf("NO_FIX", "fix(10.0)"), first.map(::describe), "A: a no-change event after its failure sends nothing")
        assertEquals(listOf("NO_FIX", "fix(10.0)"), second.map(::describe), "B: no-change events send nothing")
        fake.setProviderEnabled(gps, false)
        runCurrent()
        assertEquals("SERVICE_DISABLED", describe(first.last()), "A: the switch-off is reported")
        assertEquals("SERVICE_DISABLED", describe(second.last()), "B: the switch-off is reported")
        assertEquals(1, first.count { it == serviceDisabled }, "A: once: ${first.map(::describe)}")
        assertEquals(1, second.count { it == serviceDisabled }, "B: once: ${second.map(::describe)}")
        a.cancel()
        b.cancel()
        runCurrent()
        assertEquals(0, fake.registeredCount, "nothing left registered")
        assertEquals(0, fake.subscriberCount, "nothing left subscribed")
    }

    /**
     * Defect class: re-init after a cancelled collection whose state was unknown. Collection A shows a position, an
     * evaluation fails (unknown), A is cancelled. A new collection B starts after the device went off: it seeds
     * SERVICE_DISABLED (the truth), a no-change broadcast sends nothing, the device on reseeds once.
     */
    @Test
    fun `a new collection after a cancelled one with an unknown state seeds the truth`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val backend = FaultyBackend(fake)
        val provider: LocationProvider = providerOver(backend, testScheduler.timeSource)
        val first: MutableList<LocationReading> = mutableListOf()
        val a: Job = backgroundScope.launch { provider.observeLocation(explicitGps).collect { first += it } }
        runCurrent()
        fake.deliverMirrored(gps, 10.0)
        runCurrent()
        backend.armedSite = "isLocationEnabled"
        backend.armedBudget = 1
        fake.setProviderEnabled(network, false)
        runCurrent()
        assertEquals(1, backend.injected, "control: A's evaluation failed")
        a.cancel()
        runCurrent()
        assertEquals(0, fake.registeredCount, "control: A released")
        fake.setDeviceLocationEnabled(false)
        val second: MutableList<LocationReading> = mutableListOf()
        val b: Job = backgroundScope.launch { provider.observeLocation(explicitGps).collect { second += it } }
        runCurrent()
        assertEquals(listOf("SERVICE_DISABLED"), second.map(::describe), "B seeds the truth")
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        assertEquals(listOf("SERVICE_DISABLED"), second.map(::describe), "B: a no-change broadcast sends nothing")
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertEquals(listOf("SERVICE_DISABLED", "fix(10.0)"), second.map(::describe), "B: the return reseeds the cached fix once")
        b.cancel()
        runCurrent()
        assertEquals(0, fake.registeredCount, "nothing left registered")
        assertEquals(0, fake.subscriberCount, "nothing left subscribed")
    }

    /**
     * Defect class: "shown" means sent, not seen (conflation). The collector shows fix(10) and is then busy. The device
     * goes off (SERVICE_DISABLED is sent, the collector does not see it), comes back on with a failing read (unknown, no
     * reseed), a live fix(11) is sent and conflates SERVICE_DISABLED away. A no-change broadcast must send nothing: the
     * last element sent is a position and the service is on. The busy collector ends on fix(11), having never seen
     * SERVICE_DISABLED; a switch-off then reaches it.
     */
    @Test
    fun `a SERVICE_DISABLED the busy collector never saw does not make the stream resend or reseed`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        val backend = FaultyBackend(fake)
        val provider: LocationProvider = providerOver(backend, testScheduler.timeSource)
        val seen: MutableList<LocationReading> = mutableListOf()
        var busy = false
        val job: Job = backgroundScope.launch {
            provider.observeLocation(explicitGps).collect { reading: LocationReading ->
                seen += reading
                if (busy) delay(1.hours)
            }
        }
        runCurrent()
        fake.deliverMirrored(gps, 10.0)
        runCurrent()
        assertEquals(listOf("NO_FIX", "fix(10.0)"), seen.map(::describe), "control")
        busy = true
        fake.deliverMirrored(gps, 10.5)
        runCurrent()
        assertEquals("fix(10.5)", describe(seen.last()), "control: the collector took fix(10.5) and is now busy")
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        backend.armedSite = "isLocationEnabled"
        backend.armedBudget = 1
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertEquals(1, backend.injected, "control: the return's evaluation failed")
        // Not mirrored into the cache: a spurious reseed would show the cached fix(10.5) instead of the live fix(11.0).
        fake.deliver(gps, fake.locationAt(gps, 11.0))
        runCurrent()
        fake.setProviderEnabled(network, false)
        runCurrent()
        busy = false
        advanceTimeBy(3.hours)
        runCurrent()
        assertEquals(listOf("NO_FIX", "fix(10.0)", "fix(10.5)", "fix(11.0)"), seen.map(::describe), "the busy collector ends on the live fix only")
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        assertEquals("SERVICE_DISABLED", describe(seen.last()), "the next switch-off is reported")
        job.cancel()
        runCurrent()
        assertEquals(0, fake.registeredCount, "nothing left registered")
    }

    /**
     * Defect class: terminal reading lost or not last. A waiting stream (every candidate refused, device off) with a busy
     * collector; two flaps whose device-switch read fails (unknown), then the device comes on: PERMISSION_MISSING is the
     * last element, the busy collector sees it, the flow completes and nothing is left registered or subscribed.
     */
    @Test
    fun `the terminal reading reaches a busy collector after failed evaluations`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps))
        fake.fine = false
        fake.refuse(gps)
        fake.locationEnabled = false
        val backend = FaultyBackend(fake)
        val provider: LocationProvider = providerOver(backend, testScheduler.timeSource)
        val seen: MutableList<LocationReading> = mutableListOf()
        var completed = false
        val job: Job = backgroundScope.launch {
            provider.observeLocation(explicitGps).collect { reading: LocationReading ->
                seen += reading
                delay(1.hours)
            }
            completed = true
        }
        runCurrent()
        assertEquals(listOf("SERVICE_DISABLED"), seen.map(::describe), "control: the waiting stream reads SERVICE_DISABLED")
        backend.armedSite = "isLocationEnabled"
        backend.armedBudget = 2
        fake.setProviderEnabled(gps, true)
        runCurrent()
        fake.setProviderEnabled(gps, true)
        runCurrent()
        assertEquals(2, backend.injected, "control: two evaluations failed")
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        advanceTimeBy(5.hours)
        runCurrent()
        assertEquals("PERMISSION_MISSING", describe(seen.last()), "the terminal is the last reading: ${seen.map(::describe)}")
        assertEquals(1, seen.count { it == serviceDisabled }, "SERVICE_DISABLED was not repeated: ${seen.map(::describe)}")
        assertTrue(completed, "the flow completes after the terminal")
        assertTrue(job.isCompleted, "the collection ended")
        assertEquals(0, fake.registeredCount, "nothing left registered")
        assertEquals(0, fake.subscriberCount, "nothing left subscribed")
    }
}
