package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Breaker round 8 of group loc-android (ticket 5.1.0, LOC-7 / LOC-10, protocol BREAKER-REVIEW.md, D-23, D-30).
 *
 * Attacks the round-8 fix (`StreamSession.disabledNow: Boolean?`, `null` = unknown after an evaluation that did not
 * finish) and extends round 7's exhaustive fault injection:
 * - the same numbered seam ([FaultyBackend]) with faults at TWO positions of one run (every pair of call indices of a
 *   scenario, three kind combinations), besides the single-fault ONCE / PERSIST matrix;
 * - a scenario with many events that change nothing (a stationary device: the platform cache mirrors the last
 *   delivered fix and ages past `maxSeedAge`), so an unknown state meets "no change" events, not only transitions;
 * - two new invariants from the contract: (1) a step that changes nothing about the request's sources while the
 *   collector shows a position never replaces that position (with `NO_FIX` or an older cached fix): PLAN 4.2 reseeds
 *   "on return" only, and the `LocationProvider` KDoc promises the collector ends on the newest reading, never a stale
 *   one; (2) at most one reading per platform event (no storm);
 * - deterministic reproductions, the readings-per-event count under a flapping and under a permanently failing read,
 *   the consumer's `minUpdateInterval` under that flapping, an `Error` inside a drain that has a fix queued behind it,
 *   a throwing app logger inside the unknown handling, and `observeLocationEnabled` against the stream.
 *
 * Virtual time (`runTest`, [StandardTestDispatcher] on the test scheduler), no threads, no sleeps, no wall clock; the
 * sampled pairs use a fixed seed printed as `seed=` in every message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidLocationProviderBreakerR8Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val noFix: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX)
    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)
    private val settleLatitude: Double = 55.0
    private val liveLatitude: Double = 77.0
    private val explicitGps: LocationUpdatesOptions =
        LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS))

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
     * The seam with numbered calls. [faults] maps a 1-based call index to the kind it throws (any number of positions
     * in one run, each once). In [Mode.PERSIST] (single fault only) every later call of the first faulted method throws
     * too, until [healed]. [armedSite]: every call of one method throws [armedKind] while armed (deterministic tests);
     * [armedEvery]: only every n-th call of that method throws (a flapping read).
     */
    private inner class FaultyBackend(val fake: FakeLocationBackend) : LocationBackend {
        var faults: Map<Int, Kind> = emptyMap()
        var mode: Mode = Mode.ONCE
        var healed: Boolean = false
        var armedSite: String? = null
        var armedKind: Kind = Kind.RUNTIME
        var armedEvery: Int = 1
        private var armedCalls: Int = 0
        var calls: Int = 0
        var injected: Int = 0
        var errorsInjected: Int = 0
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
            if (armedSite == site) {
                armedCalls += 1
                if (armedCalls % armedEvery == 0) {
                    injected += 1
                    throw armedKind.make("r8-injected $armedKind at armed $site($detail)")
                }
            }
            if (!healed) {
                val kind: Kind? = faults[index]
                if (kind != null) {
                    persistentSite = site
                    persistentKind = kind
                    count(kind)
                    throw kind.make("r8-injected $kind at call $index $site($detail)")
                }
                if (mode == Mode.PERSIST && persistentSite == site) {
                    count(persistentKind)
                    throw persistentKind.make("r8-injected $persistentKind persisting at call $index $site($detail)")
                }
            }
            return body()
        }

        private fun count(kind: Kind) {
            injected += 1
            if (kind == Kind.ERROR) errorsInjected += 1
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

    private fun providerOver(
        backend: LocationBackend,
        logger: Logger = NoopLogger,
        timeSource: TimeSource = TestTimeSource(),
    ): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = logger,
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

    // ---------------------------------------------------------------------------------------------
    // Scenarios
    // ---------------------------------------------------------------------------------------------

    private enum class StepKind { SERVICE, DELIVERY, QUIET }

    private class Step(val label: String, val kind: StepKind, val provider: String? = null, val latitude: Double = 0.0, val apply: (FakeLocationBackend) -> Unit)

    private fun service(label: String, apply: (FakeLocationBackend) -> Unit): Step = Step(label, StepKind.SERVICE, apply = apply)

    private fun quiet(label: String, apply: (FakeLocationBackend) -> Unit): Step = Step(label, StepKind.QUIET, apply = apply)

    private fun delivery(provider: String, latitude: Double): Step =
        Step("deliver $provider $latitude", StepKind.DELIVERY, provider, latitude) { fake: FakeLocationBackend ->
            fake.deliverMirrored(provider, latitude)
        }

    private fun pass40s(): Step = quiet("40 s pass, nothing delivered") { fake: FakeLocationBackend -> fake.nowNanos += 40_000_000_000L }

    private class StreamScenario(
        val name: String,
        val providers: Set<String>,
        val candidates: List<String>,
        val setup: (FakeLocationBackend) -> Unit,
        val options: LocationUpdatesOptions,
        val oneShot: CurrentLocationOptions,
        val steps: List<Step>,
        val heal: (FakeLocationBackend) -> Unit,
        val terminalByDesign: Boolean = false,
    )

    private class CaseResult(val totalCalls: Int, val sites: List<String>, val peakRegistrations: Int, val subscriptions: Int)

    private fun cacheAll(fake: FakeLocationBackend, latitude: Double) {
        for (provider: String in listOf(gps, network)) {
            if (fake.hasProvider(provider)) fake.setCached(provider, fake.locationAt(provider, latitude, ageMillis = 1_000L))
        }
    }

    private fun healAll(fake: FakeLocationBackend) {
        fake.fine = true
        fake.coarse = true
        for (provider: String in listOf(gps, network)) {
            fake.allow(provider)
            if (fake.hasProvider(provider)) fake.setProviderEnabled(provider, true)
        }
    }

    /** A stationary phone: few deliveries, the cache mirrors them and ages, and many events that change nothing for gps. */
    private val stationaryGpsStream: StreamScenario = StreamScenario(
        name = "stationary-gps",
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
        name = "auto-balanced",
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
        name = "waiting-then-grant",
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
        name = "waiting-terminal",
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

    // ---------------------------------------------------------------------------------------------
    // The stream case runner (round 7's oracle plus the two new invariants)
    // ---------------------------------------------------------------------------------------------

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
        val errorCase: Boolean = Kind.ERROR in faults.values
        val securityCase: Boolean = Kind.SECURITY in faults.values
        val uncaught: MutableList<Throwable> = mutableListOf()
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() +
                CoroutineExceptionHandler { _, thrown: Throwable -> uncaught += thrown },
        )
        val provider: LocationProvider = providerOver(backend)
        val seen: MutableList<LocationReading> = mutableListOf()
        var failure: Throwable? = null
        var completed = false
        fun problem(message: String) {
            problems += "$label: $message; readings=${seen.map(::describe)}; failure=$failure"
        }
        fun errorAllowed(thrown: Throwable): Boolean = errorCase && (thrown is InjectedError || thrown.cause is InjectedError)
        fun fire(stepLabel: String, apply: () -> Unit) {
            val escaped: Throwable? = try {
                apply()
                null
            } catch (thrown: Throwable) {
                thrown
            }
            runCurrent()
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
        for ((index: Int, step: Step) in scenario.steps.withIndex()) {
            val stepNo: Int = index + 1
            val injectedBefore: Int = backend.injected
            val wasAlive: Boolean = failure == null && !completed
            val readingBefore: LocationReading? = seen.lastOrNull()
            val countBefore: Int = seen.size
            val onBefore: Boolean = anyCandidateOn()
            // Platform callbacks this step can fire: one per live registration (a provider callback or a delivery) plus
            // the broadcast. A delivery reaches every registration of its provider (the fake keeps one whose removal
            // failed; a real manager replaces a re-registered listener). More readings than callbacks is a storm.
            val callbacks: Int = when (step.kind) {
                StepKind.DELIVERY -> maxOf(1, fake.registered().count { registration: FakeLocationBackend.Registration -> registration.provider == step.provider })
                else -> 1 + fake.registeredCount
            }
            fire("step $stepNo ${step.label}") { step.apply(fake) }
            val faultedHere: Boolean = backend.injected > injectedBefore
            checkDuplicates("step $stepNo ${step.label}")
            val alive: Boolean = failure == null && !completed
            val added: List<String> = seen.drop(countBefore).map(::describe)
            if (wasAlive && added.size > callbacks) {
                problem("step $stepNo ${step.label}: $callbacks platform callback(s) produced ${added.size} readings: $added")
            }
            val persisting: Boolean = mode == Mode.PERSIST && backend.injected > 0
            val oracleApplies: Boolean = wasAlive && alive && !faultedHere && !persisting
            if (!oracleApplies) continue
            val last: LocationReading? = seen.lastOrNull()
            when (step.kind) {
                StepKind.SERVICE -> {
                    val anyOn: Boolean = anyCandidateOn()
                    val registeredOn: Boolean = fake.locationEnabled &&
                        backend.unreleased().any { registration: FakeLocationBackend.Registration -> fake.isProviderEnabled(registration.provider) }
                    if (!anyOn && last != serviceDisabled) {
                        problem("step $stepNo ${step.label}: every source is off but the stream reads ${describe(last)}")
                    }
                    if (registeredOn && last == serviceDisabled) {
                        problem("step $stepNo ${step.label}: a registered source is on but the stream reads SERVICE_DISABLED")
                    }
                    // New invariant (round 8): sources on before and after, the collector showed a position: the event
                    // must not replace it with NO_FIX or another (older, cached) position. SERVICE_DISABLED is judged above.
                    if (onBefore && anyOn && readingBefore is LocationReading.Available && last != serviceDisabled &&
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
                        registration.provider == step.provider
                    }
                    if (registered && latitudeOf(last) != step.latitude) {
                        problem("step $stepNo ${step.label}: the delivered fix is not the reading: ${describe(last)}")
                    }
                }
                StepKind.QUIET -> if (seen.size != countBefore) problem("step $stepNo ${step.label}: a reading without a platform event")
            }
        }
        val totalCalls: Int = backend.calls
        val sites: List<String> = backend.sites.toList()

        failure?.let { thrown: Throwable ->
            val allowed: Boolean = (faultedAtStart && thrown.message?.startsWith("r8-injected") == true) || errorAllowed(thrown)
            if (!allowed) problem("the collector failed with a fault outside the start: $thrown")
        }
        if (completed) {
            val last: LocationReading? = seen.lastOrNull()
            if (last != permissionMissing) problem("the stream completed after ${describe(last)}")
            if (!scenario.terminalByDesign && !securityCase) problem("the stream ended with PERMISSION_MISSING for faults $faults")
        }
        if (failure != null || completed) checkLedger("after the stream ended")

        backend.healed = true
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
                if (latitudeOf(seen.lastOrNull()) != settleLatitude) {
                    problem("settle: device on with every source on reads ${describe(seen.lastOrNull())}, expected the cached fix $settleLatitude")
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
            if (latitudeOf(again.lastOrNull()) != settleLatitude || againEnd != null || againCompleted) {
                problem("re-init: a new collection on the healed world reads ${again.map(::describe)}, failure=$againEnd, completed=$againCompleted")
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

    private fun report(scenario: StreamScenario, what: String, cases: Int, total: Int, problems: List<String>) {
        if (problems.isNotEmpty()) {
            fail("${problems.size} invariant violation(s) in $cases $what cases of ${scenario.name} (T=$total); first 12:\n" + problems.take(12).joinToString("\n"))
        }
    }

    /** Round 7's single-fault matrix (every index, every kind, ONCE and PERSIST) under the round-8 invariants. */
    private fun TestScope.exhaustSingle(scenario: StreamScenario) {
        val dry: CaseResult = dryRun(scenario)
        val problems: MutableList<String> = mutableListOf()
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
        report(scenario, "single-fault", case, dry.totalCalls, problems)
    }

    /**
     * Two faults in one run: every pair k1 < k2 of call indices (the second index counts the calls of the faulted run),
     * with three kind combinations: both unchecked, a checked one then an `Error`, a refusal then an illegal argument.
     */
    private fun TestScope.exhaustPairs(scenario: StreamScenario) {
        val dry: CaseResult = dryRun(scenario)
        val combos: List<Pair<Kind, Kind>> = listOf(
            Kind.RUNTIME to Kind.RUNTIME,
            Kind.CHECKED to Kind.ERROR,
            Kind.SECURITY to Kind.ILLEGAL_ARGUMENT,
        )
        val problems: MutableList<String> = mutableListOf()
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
        report(scenario, "two-fault", case, dry.totalCalls, problems)
    }

    @Test
    fun `single faults on a stationary gps stream never replace a live position while nothing changed`() =
        runTest(timeout = 600.seconds) { exhaustSingle(stationaryGpsStream) }

    @Test
    fun `single faults on an AUTO stream with moves and no-change events keep the stream truthful`() =
        runTest(timeout = 600.seconds) { exhaustSingle(autoBalancedStream) }

    @Test
    fun `single faults on waiting streams keep the stream truthful and the terminal last`() = runTest(timeout = 600.seconds) {
        exhaustSingle(waitingThenGrantStream)
        exhaustSingle(waitingTerminalStream)
    }

    @Test
    fun `two faults in one run of a stationary gps stream`() = runTest(timeout = 600.seconds) { exhaustPairs(stationaryGpsStream) }

    @Test
    fun `two faults in one run of an AUTO stream with moves`() = runTest(timeout = 600.seconds) { exhaustPairs(autoBalancedStream) }

    @Test
    fun `two faults in one run of waiting streams`() = runTest(timeout = 600.seconds) {
        exhaustPairs(waitingThenGrantStream)
        exhaustPairs(waitingTerminalStream)
    }

    // ---------------------------------------------------------------------------------------------
    // Deterministic attacks on the unknown state
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: wrong reading after a fix (a spurious reseed). The round-8 "unknown" makes ANY next event send a
     * reseed, also one that changes nothing. A stationary phone: gps delivered fix(10) and the platform's cache holds that
     * fix; 40 s pass with no delivery (past maxSeedAge 30 s). A broadcast about the network provider (nothing changes for
     * an explicit gps stream) fails to read the device switch: unknown. The next network broadcast succeeds and reseeds:
     * the cache is past maxSeedAge, so the stream replaces its position with NO_FIX although gps stayed on and nothing
     * changed (PLAN 4.2 reseeds on RETURN only). Turns green when the unknown state is resolved against what the
     * collector was last sent (a position stays, SERVICE_DISABLED is reported) instead of always resending.
     */
    @Test
    fun `a failed no-change evaluation does not make the next no-change event replace the position with NO_FIX`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = FaultyBackend(fake)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(noFix), seen, "control: no cache, the seed is NO_FIX")
            fake.deliverMirrored(gps, 10.0)
            runCurrent()
            assertEquals(10.0, latitudeOf(seen.last()), "control: the live fix is shown")
            fake.nowNanos += 40_000_000_000L
            backend.armedSite = "isLocationEnabled"
            fake.setProviderEnabled(network, false)
            runCurrent()
            backend.armedSite = null
            assertTrue(backend.injected > 0, "control: the evaluation read the device switch and it failed")
            assertEquals(10.0, latitudeOf(seen.last()), "control: the failed evaluation sent nothing: ${seen.map(::describe)}")
            fake.setProviderEnabled(network, true)
            runCurrent()
            assertEquals(
                10.0,
                latitudeOf(seen.last()),
                "gps stayed on and nothing changed for it, but the stream replaced fix(10.0): ${seen.map(::describe)}",
            )
            job.cancel()
            runCurrent()
            assertEquals(0, fake.registeredCount, "nothing left registered")
        }

    /**
     * Defect class: stale position (an older cached fix overtakes a newer live one). The same spurious reseed when the
     * platform's cache holds an OLDER fix than the one the stream delivered (the cache of an AUTO HIGH stream is judged
     * over both sources and the network cache is the freshest by its stamp, or an OEM that does not update the cache for
     * every delivery). Collector: fix(7) seed, live fix(10); a no-change broadcast fails; the next one reseeds fix(7).
     */
    @Test
    fun `a failed no-change evaluation does not make the next event roll the position back to an older cached fix`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            fake.setCached(gps, fake.locationAt(gps, 7.0, ageMillis = 1_000L))
            val backend = FaultyBackend(fake)
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
            runCurrent()
            fake.deliver(gps, fake.locationAt(gps, 10.0))
            runCurrent()
            assertEquals(listOf(7.0, 10.0), seen.map(::latitudeOf), "control: seed then live fix")
            backend.armedSite = "isLocationEnabled"
            fake.setProviderEnabled(network, false)
            runCurrent()
            backend.armedSite = null
            assertTrue(backend.injected > 0, "control: the evaluation failed")
            fake.setProviderEnabled(network, true)
            runCurrent()
            assertEquals(10.0, latitudeOf(seen.last()), "the stream rolled back from the live fix(10.0) to a cached one: ${seen.map(::describe)}")
            job.cancel()
            runCurrent()
        }

    /**
     * Defect class: reading storm and a defeated `minUpdateInterval`. A flapping platform read (every second evaluation
     * fails) on events that change nothing, while gps delivers a new position every second; the consumer asked for one
     * position per 60 s. Every successful evaluation after a failed one sends a reseed; a seed resets the throttle's
     * interval (D-21: a seed sets no emission time), so the next live fix passes at once: the consumer receives about two
     * readings per cycle instead of one per minute. Counts readings per event; turns green when a no-change event after an
     * unknown state sends nothing while the collector shows a position.
     */
    @Test
    fun `a flapping read on no-change events neither storms the collector nor defeats minUpdateInterval`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = FaultyBackend(fake).apply { armedEvery = 2 }
            val options = LocationUpdatesOptions(
                minUpdateInterval = 60.seconds,
                platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS),
            )
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch {
                providerOver(backend, timeSource = testScheduler.timeSource).observeLocation(options).collect { seen += it }
            }
            runCurrent()
            fake.deliverMirrored(gps, 10.0)
            runCurrent()
            assertEquals(listOf("NO_FIX", "fix(10.0)"), seen.map(::describe), "control: seed and the first live fix")
            backend.armedSite = "isLocationEnabled"
            var events = 0
            for (cycle: Int in 1..20) {
                fake.setProviderEnabled(network, cycle % 2 == 0)
                events += 1
                runCurrent()
                advanceTimeBy(1.seconds)
                fake.deliverMirrored(gps, 10.0 + cycle * 0.01)
                events += 1
                runCurrent()
            }
            backend.armedSite = null
            assertTrue(backend.injected >= 5, "control: the flapping read failed repeatedly: ${backend.injected}")
            val after: List<String> = seen.drop(2).map(::describe)
            // 20 s of a 60 s interval: no reading at all is due (the newest position waits as the pending fix).
            assertTrue(
                after.size <= 1,
                "nothing changed for gps and the interval is 60 s, yet $events platform events in 20 s produced ${after.size} readings: $after",
            )
            job.cancel()
            runCurrent()
        }

    /**
     * The same flapping read when the consumer accepts no cached seed (`maxSeedAge = 0`, a valid value): every spurious
     * reseed is `NO_FIX`. A reason is never delayed by the throttle and forgets the reference, so the next live fix
     * passes at once: the 60 s interval is defeated and the collector alternates between a position and NO_FIX while
     * gps delivers every second and nothing changes. Readings per event are counted.
     */
    @Test
    fun `a flapping read with maxSeedAge zero neither flips the collector to NO_FIX nor defeats minUpdateInterval`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val backend = FaultyBackend(fake).apply { armedEvery = 2 }
            val options = LocationUpdatesOptions(
                minUpdateInterval = 60.seconds,
                maxSeedAge = Duration.ZERO,
                platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS),
            )
            val seen: MutableList<LocationReading> = mutableListOf()
            val job: Job = backgroundScope.launch {
                providerOver(backend, timeSource = testScheduler.timeSource).observeLocation(options).collect { seen += it }
            }
            runCurrent()
            fake.deliverMirrored(gps, 10.0)
            runCurrent()
            assertEquals(listOf("NO_FIX", "fix(10.0)"), seen.map(::describe), "control: seed and the first live fix")
            backend.armedSite = "isLocationEnabled"
            var events = 0
            for (cycle: Int in 1..20) {
                fake.setProviderEnabled(network, cycle % 2 == 0)
                events += 1
                runCurrent()
                advanceTimeBy(1.seconds)
                fake.deliverMirrored(gps, 10.0 + cycle * 0.01)
                events += 1
                runCurrent()
            }
            backend.armedSite = null
            assertTrue(backend.injected >= 5, "control: the flapping read failed repeatedly: ${backend.injected}")
            val after: List<String> = seen.drop(2).map(::describe)
            assertTrue(
                after.size <= 1 && "NO_FIX" !in after,
                "nothing changed for gps and the interval is 60 s, yet $events platform events in 20 s produced ${after.size} readings: $after",
            )
            job.cancel()
            runCurrent()
        }

    /**
     * Readings per event under a PERMANENTLY failing read: no storm (nothing is sent while the state cannot be read), the
     * collector is not left on a state that was never read, and after healing exactly one reading reports what changed
     * meanwhile (the device went off during the failure).
     */
    @Test
    fun `a permanently failing read sends nothing per event and the heal reports the change once`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps, network))
        fake.setCached(gps, fake.locationAt(gps, 7.0, ageMillis = 1_000L))
        val backend = FaultyBackend(fake).apply { armedKind = Kind.CHECKED }
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        fake.deliver(gps, fake.locationAt(gps, 10.0))
        runCurrent()
        val before: Int = seen.size
        backend.armedSite = "isLocationEnabled"
        repeat(50) { round: Int -> fake.setProviderEnabled(network, round % 2 == 0) }
        fake.setDeviceLocationEnabled(false)
        repeat(50) { round: Int -> fake.setProviderEnabled(network, round % 2 == 1) }
        runCurrent()
        assertTrue(backend.injected >= 100, "control: every evaluation failed: ${backend.injected}")
        assertEquals(before, seen.size, "101 events whose state cannot be read sent ${seen.drop(before).map(::describe)}")
        backend.armedSite = null
        fake.setProviderEnabled(network, true)
        runCurrent()
        assertEquals(listOf("SERVICE_DISABLED"), seen.drop(before).map(::describe), "the heal reports the switch-off once")
        fake.setProviderEnabled(network, false)
        runCurrent()
        assertEquals(before + 1, seen.size, "and dedupes again: ${seen.map(::describe)}")
        job.cancel()
        runCurrent()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }

    /**
     * The `Error` path with work queued behind it in the same drain: a provider callback arrives while an evaluation is
     * running (the platform calls a listener synchronously from inside a read, as an OEM binder stub may), then that
     * evaluation dies with an `Error`. The queued fix must still be delivered (the gate keeps going), the Error passes
     * out, the state is unknown, the next event reports, and release still works (gate not wedged).
     */
    @Test
    fun `an Error in an evaluation with a fix queued behind it leaves the gate usable`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps))
        var reenter: Boolean = false
        var reentered = 0
        val backend = object : LocationBackend by fake {
            override fun isLocationEnabled(): Boolean {
                if (reenter) {
                    reenter = false
                    reentered += 1
                    fake.deliver(gps, fake.locationAt(gps, 12.0))
                    throw InjectedError("r8: binder died after a synchronous callback")
                }
                return fake.isLocationEnabled()
            }
        }
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch { providerOver(backend).observeLocation(explicitGps).collect { seen += it } }
        runCurrent()
        fake.deliver(gps, fake.locationAt(gps, 10.0))
        runCurrent()
        reenter = true
        val escaped: Throwable? = runCatching { fake.setDeviceLocationEnabled(false) }.exceptionOrNull()
        runCurrent()
        assertEquals(1, reentered, "control: the re-entrant delivery happened")
        assertTrue(escaped is InjectedError || escaped?.cause is InjectedError, "the Error passes out of the callback: $escaped")
        assertEquals(12.0, latitudeOf(seen.last()), "the fix queued behind the failing evaluation is delivered: ${seen.map(::describe)}")
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "the next event reports the switch-off: ${seen.map(::describe)}")
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        assertTrue(seen.last() is LocationReading.Available || seen.last() == noFix, "back on reseeds: ${seen.map(::describe)}")
        job.cancel()
        runCurrent()
        assertEquals(0, fake.registeredCount, "release works after the Error")
        assertEquals(0, fake.subscriberCount, "unsubscribed after the Error")
    }

    /**
     * A fault inside the unknown handling itself: the app's logger throws from the "could not re-read" report (a raw
     * [Logger], outside the suite's containment). The escape into the callback is outside the contract (round 7
     * observation); what must hold is the record: the next switch-off after a live fix is still reported.
     */
    @Test
    fun `a throwing logger in the failure report does not keep the state from turning unknown`() = runTest(timeout = 60.seconds) {
        val throwingLogger = object : Logger {
            override val tag: String = "r8"

            override fun isLoggable(level: LogLevel): Boolean = true

            override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
                if (throwable != null) throw IllegalStateException("r8: logger failed")
            }
        }
        val fake = FakeLocationBackend(providers = setOf(gps)).apply { locationEnabled = false }
        fake.setCached(gps, fake.locationAt(gps, 7.0, ageMillis = 1_000L))
        val backend = FaultyBackend(fake)
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            providerOver(backend, logger = throwingLogger).observeLocation(explicitGps).collect { seen += it }
        }
        runCurrent()
        backend.armedSite = "hasFinePermission"
        runCatching { fake.setDeviceLocationEnabled(true) }
        runCurrent()
        backend.armedSite = null
        assertTrue(backend.injected > 0, "control: the reseed failed")
        fake.deliver(gps, fake.locationAt(gps, 9.0))
        runCurrent()
        assertEquals(9.0, latitudeOf(seen.last()), "control: live fix")
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "the switch-off after the failed report is lost: ${seen.map(::describe)}")
        job.cancel()
        runCurrent()
        assertEquals(0, fake.registeredCount)
    }

    /**
     * `observeLocationEnabled` and the stream after an unknown state: a stream-only read (the cache) fails on the device
     * coming back; then a seeded storm of device switches and no-change broadcasts. After every event both flows agree
     * on the device switch (the stream reads SERVICE_DISABLED iff the device is off, the enabled flow its value) and the
     * enabled flow never repeats a value (its own dedupe is untouched by the stream's unknown state).
     */
    @Test
    fun `observeLocationEnabled and the stream agree after an unknown state through a seeded storm`() = runTest(timeout = 60.seconds) {
        val seed = 8L
        val random = Random(seed)
        val fake = FakeLocationBackend(providers = setOf(gps)).apply { locationEnabled = false }
        fake.setCached(gps, fake.locationAt(gps, 7.0, ageMillis = 1_000L))
        val backend = FaultyBackend(fake)
        val provider: LocationProvider = providerOver(backend)
        val seen: MutableList<LocationReading> = mutableListOf()
        val enabled: MutableList<Boolean> = mutableListOf()
        val streamJob: Job = backgroundScope.launch { provider.observeLocation(explicitGps).collect { seen += it } }
        val enabledJob: Job = backgroundScope.launch { provider.observeLocationEnabled().collect { enabled += it } }
        runCurrent()
        backend.armedSite = "lastKnownLocation"
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        backend.armedSite = null
        assertTrue(backend.injected > 0, "seed=$seed control: the reseed's cache read failed")
        repeat(60) { step: Int ->
            when (random.nextInt(3)) {
                0 -> fake.setDeviceLocationEnabled(random.nextBoolean())
                // Delivered only while the device is on: a fix delivered while every source is off is shown by design
                // (ObserveLocationProviderFlapTest's model), so it is not part of this agreement check.
                1 -> if (fake.locationEnabled) fake.deliver(gps, fake.locationAt(gps, 20.0 + step))
                else -> fake.setDeviceLocationEnabled(fake.locationEnabled)
            }
            runCurrent()
            val on: Boolean = fake.locationEnabled
            assertEquals(on, enabled.last(), "seed=$seed step=$step: observeLocationEnabled rests on ${enabled.last()}")
            if (!on) assertEquals(serviceDisabled, seen.last(), "seed=$seed step=$step: device off, the stream reads ${describe(seen.last())}")
            if (on) assertTrue(seen.last() != serviceDisabled, "seed=$seed step=$step: device on, the stream reads SERVICE_DISABLED")
        }
        assertEquals(enabled.zipWithNext().count { (a: Boolean, b: Boolean) -> a == b }, 0, "seed=$seed: the enabled flow repeated a value: $enabled")
        streamJob.cancel()
        enabledJob.cancel()
        runCurrent()
        assertEquals(0, fake.registeredCount)
        assertEquals(0, fake.subscriberCount)
    }
}
