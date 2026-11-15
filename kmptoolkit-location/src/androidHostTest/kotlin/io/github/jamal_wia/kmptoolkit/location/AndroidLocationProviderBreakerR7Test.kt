package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.LogSink
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
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
 * Breaker round 7 of group loc-android (ticket 5.1.0, LOC-7 / LOC-10, protocol BREAKER-REVIEW.md, D-23, D-30).
 *
 * Systematic fault injection instead of one more incremental attack. Every call `AndroidLocationProvider` makes into
 * its platform seam ([LocationBackend]: provider presence and flags, the device switch, both grant reads, the cache,
 * registration and removal, the framework one-shot and its cancel function, the service subscription and its
 * unsubscribe function, the boot clock) goes through [FaultyBackend], which numbers the calls. A scripted scenario
 * is first run fault-free to count its calls (T) and name each call site; then, for EVERY call index k in 1..T and
 * EVERY exception kind (a checked `Exception`, `SecurityException`, `IllegalArgumentException`, a `RuntimeException`
 * subclass, an `Error`), the scenario is run again with call k failing, once ([Mode.ONCE]) or on every later call to
 * the same method until the world heals ([Mode.PERSIST]), and run to completion. Exhaustive and deterministic: the
 * case coordinates (`seed=` = the case number, k, site, kind, mode) are in every message.
 *
 * Invariants, derived from the contract (PLAN 4.2 rows SERVICE_DISABLED / PERMISSION_MISSING / "Service off then
 * on", D8 one mapper for both calls, `LocationProvider` KDoc "getCurrentLocation never throws for a platform error",
 * docs 05-platform-notes "a failure at the start of a stream propagates"), not from the implementation:
 * - nothing but an `Error` escapes into a platform callback (the main looper's uncaught handler on a device), and
 *   nothing reaches a coroutine's uncaught handler but an `Error`;
 * - per-registration ledger: every registration, subscription and framework one-shot the provider made is released
 *   (or its release attempted) when the collection or the call ends; no (listener, provider) pair is live twice;
 * - a stream fails to its collector only for a fault during its start (or an `Error`), and ends with
 *   PERMISSION_MISSING only for a `SecurityException`;
 * - after every later service event the reading reflects the state: SERVICE_DISABLED when every source is off, never
 *   SERVICE_DISABLED while a registered source is on; after a delivery to a registered source, that fix;
 * - after the world heals, the stream (or a new collection: re-init) reports off, then the cached fix, then a live
 *   fix, and a one-shot at the same state agrees with it; a one-shot never throws, never hangs past its timeout, and a
 *   failure in its cleanup never changes its answer.
 *
 * Virtual time (`runTest`, one [StandardTestDispatcher] per case on the test scheduler), no threads, no sleeps, no
 * wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidLocationProviderBreakerR7Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val noFix: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX)
    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)
    private val settleLatitude: Double = 55.0
    private val liveLatitude: Double = 77.0

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

    /** One service subscription the provider made, with whether it asked to release it. */
    private class Subscription {
        var releaseAttempted: Boolean = false
    }

    /** One framework one-shot call, with whether it was answered or its cancel was attempted. */
    private class Current {
        var answered: Boolean = false
        var cancelAttempted: Boolean = false
    }

    /**
     * The seam with numbered calls. Call [failAt] (1-based) throws [kind]; in [Mode.PERSIST] every later call of the
     * same method throws too, until [healed]. [armSite]: every call of one method throws while armed (deterministic
     * tests). Single-threaded by construction (every event is fired from the test thread).
     */
    private inner class FaultyBackend(val fake: FakeLocationBackend) : LocationBackend {
        var failAt: Int = 0
        var kind: Kind = Kind.RUNTIME
        var mode: Mode = Mode.ONCE
        var healed: Boolean = false
        var armedSite: String? = null
        var calls: Int = 0
        var injected: Int = 0
        val sites: MutableList<String> = mutableListOf()
        private var persistentSite: String? = null
        val releaseAttempted: MutableSet<FakeLocationBackend.Registration> = Collections.newSetFromMap(IdentityHashMap())
        val subscriptions: MutableList<Subscription> = mutableListOf()
        val currents: MutableList<Current> = mutableListOf()
        var peakRegistrations: Int = 0

        private fun <T> call(site: String, detail: String, body: () -> T): T {
            calls += 1
            val index: Int = calls
            sites += if (detail.isEmpty()) site else "$site($detail)"
            if (armedSite == site) {
                injected += 1
                throw kind.make("r7-injected $kind at armed $site($detail)")
            }
            if (!healed && failAt > 0) {
                if (index == failAt) persistentSite = site
                val hit: Boolean = index == failAt || (mode == Mode.PERSIST && index > failAt && site == persistentSite)
                if (hit) {
                    injected += 1
                    throw kind.make("r7-injected $kind at call $index $site($detail)")
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
        ): (() -> Unit)? = call("requestCurrentLocation", provider) {
            val current = Current()
            val cancel: (() -> Unit)? = fake.requestCurrentLocation(provider, accuracy) { location: Location? ->
                current.answered = true
                onResult(location)
            }
            if (cancel == null) {
                null
            } else {
                currents += current
                val wrapped: () -> Unit = {
                    current.cancelAttempted = true
                    call("currentCancel", provider) { cancel() }
                }
                wrapped
            }
        }

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

    private fun providerOver(backend: LocationBackend, logger: Logger = NoopLogger): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = logger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
        timeSource = TestTimeSource(),
    )

    private fun latitudeOf(reading: LocationReading?): Double? = (reading as? LocationReading.Available)?.fix?.coordinates?.latitude

    private fun describe(reading: LocationReading?): String = when (reading) {
        null -> "none"
        is LocationReading.Available -> "fix(${reading.fix.coordinates.latitude})"
        is LocationReading.Unavailable -> reading.reason.name
    }

    private enum class StepKind { SERVICE, DELIVERY, QUIET }

    /** One scripted platform event; [provider] / [latitude] for a delivery. */
    private class Step(val label: String, val kind: StepKind, val provider: String? = null, val latitude: Double = 0.0, val apply: (FakeLocationBackend) -> Unit)

    private fun service(label: String, apply: (FakeLocationBackend) -> Unit): Step = Step(label, StepKind.SERVICE, apply = apply)

    private fun quiet(label: String, apply: (FakeLocationBackend) -> Unit): Step = Step(label, StepKind.QUIET, apply = apply)

    private fun delivery(provider: String, latitude: Double): Step =
        Step("deliver $provider $latitude", StepKind.DELIVERY, provider, latitude) { fake: FakeLocationBackend ->
            fake.deliver(provider, fake.locationAt(provider, latitude))
        }

    private class StreamScenario(
        val name: String,
        val providers: Set<String>,
        /** The sources the request can use: when none is on (or the device is off) the stream must read SERVICE_DISABLED. */
        val candidates: List<String>,
        val setup: (FakeLocationBackend) -> Unit,
        val options: LocationUpdatesOptions,
        val oneShot: CurrentLocationOptions,
        val steps: List<Step>,
        /** Makes the world healthy: grant, refusals lifted, every source on (the device switch is handled by the settle). */
        val heal: (FakeLocationBackend) -> Unit,
        /** The fault-free run ends with PERMISSION_MISSING (the waiting-terminal scenario). */
        val terminalByDesign: Boolean = false,
    )

    private class CaseResult(val totalCalls: Int, val sites: List<String>, val peakRegistrations: Int, val subscriptions: Int)

    // ---------------------------------------------------------------------------------------------
    // Scenarios
    // ---------------------------------------------------------------------------------------------

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

    private val explicitGpsStream: StreamScenario = StreamScenario(
        name = "explicit-gps",
        providers = setOf(gps, network),
        candidates = listOf(gps),
        setup = { fake: FakeLocationBackend -> fake.setCached(gps, fake.locationAt(gps, 7.0, ageMillis = 1_000L)) },
        options = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS)),
        oneShot = CurrentLocationOptions(timeout = 5.seconds, platform = AndroidCurrentLocationOptions(source = AndroidLocationSource.GPS)),
        steps = listOf(
            delivery(gps, 10.0),
            service("device off") { it.setDeviceLocationEnabled(false) },
            service("device on") { it.setDeviceLocationEnabled(true) },
            delivery(gps, 11.0),
            service("gps off") { it.setProviderEnabled(gps, false) },
            service("gps on") { it.setProviderEnabled(gps, true) },
            delivery(gps, 12.0),
            service("network off") { it.setProviderEnabled(network, false) },
            service("device off") { it.setDeviceLocationEnabled(false) },
            service("device on") { it.setDeviceLocationEnabled(true) },
            delivery(gps, 13.0),
            service("device off") { it.setDeviceLocationEnabled(false) },
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
            service("device off") { it.setDeviceLocationEnabled(false) },
            service("device on") { it.setDeviceLocationEnabled(true) },
            service("gps off") { it.setProviderEnabled(gps, false) },
            service("network off (all off)") { it.setProviderEnabled(network, false) },
            service("gps on (move to gps)") { it.setProviderEnabled(gps, true) },
            delivery(gps, 13.0),
            service("network on (move back)") { it.setProviderEnabled(network, true) },
            delivery(network, 14.0),
            service("device off") { it.setDeviceLocationEnabled(false) },
        ),
        heal = ::healAll,
    )

    /** Coarse on API 24-30: gps refused, the device off at the start (D-30 waiting), then the fine grant lands. */
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
        options = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS)),
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
            service("device off") { it.setDeviceLocationEnabled(false) },
            service("device on") { it.setDeviceLocationEnabled(true) },
            delivery(gps, 11.0),
            service("gps off") { it.setProviderEnabled(gps, false) },
        ),
        heal = ::healAll,
    )

    /** Coarse on API 24-30: gps refused while the device is off; the device comes on: PERMISSION_MISSING (D-30). */
    private val waitingTerminalStream: StreamScenario = StreamScenario(
        name = "waiting-terminal",
        providers = setOf(gps),
        candidates = listOf(gps),
        setup = { fake: FakeLocationBackend ->
            fake.fine = false
            fake.refuse(gps)
            fake.locationEnabled = false
        },
        options = LocationUpdatesOptions(platform = AndroidLocationUpdatesOptions(source = AndroidLocationSource.GPS)),
        oneShot = CurrentLocationOptions(timeout = 5.seconds, platform = AndroidCurrentLocationOptions(source = AndroidLocationSource.GPS)),
        steps = listOf(
            service("gps flap while waiting") { it.setProviderEnabled(gps, true) },
            service("device on (terminal)") { it.setDeviceLocationEnabled(true) },
            service("device on again") { it.setDeviceLocationEnabled(true) },
        ),
        heal = ::healAll,
        terminalByDesign = true,
    )

    // ---------------------------------------------------------------------------------------------
    // The stream case runner
    // ---------------------------------------------------------------------------------------------

    private fun TestScope.runStreamCase(
        scenario: StreamScenario,
        failAt: Int,
        kind: Kind,
        mode: Mode,
        label: String,
        problems: MutableList<String>,
    ): CaseResult {
        val fake = FakeLocationBackend(providers = scenario.providers)
        scenario.setup(fake)
        val backend = FaultyBackend(fake).apply {
            this.failAt = failAt
            this.kind = kind
            this.mode = mode
        }
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
        fun errorAllowed(thrown: Throwable): Boolean = kind == Kind.ERROR && (thrown is InjectedError || thrown.cause is InjectedError)
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
        val startCalls: Int = backend.calls
        var faultStep: Int = if (failAt in 1..startCalls) 0 else -1
        for ((index: Int, step: Step) in scenario.steps.withIndex()) {
            val stepNo: Int = index + 1
            val before: Int = backend.calls
            val wasAlive: Boolean = failure == null && !completed
            fire("step $stepNo ${step.label}") { step.apply(fake) }
            if (failAt in (before + 1)..backend.calls) faultStep = stepNo
            checkDuplicates("step $stepNo ${step.label}")
            val alive: Boolean = failure == null && !completed
            val oracleApplies: Boolean = wasAlive && alive && faultStep != stepNo && (mode == Mode.ONCE || backend.injected == 0)
            if (!oracleApplies) continue
            val last: LocationReading? = seen.lastOrNull()
            when (step.kind) {
                StepKind.SERVICE -> {
                    val anyOn: Boolean = fake.locationEnabled && scenario.candidates.any { candidate: String -> fake.isProviderEnabled(candidate) }
                    val registeredOn: Boolean = fake.locationEnabled &&
                        backend.unreleased().any { registration: FakeLocationBackend.Registration -> fake.isProviderEnabled(registration.provider) }
                    if (!anyOn && last != serviceDisabled) {
                        problem("step $stepNo ${step.label}: every source is off but the stream reads ${describe(last)} (fault at step $faultStep)")
                    }
                    if (registeredOn && last == serviceDisabled) {
                        problem("step $stepNo ${step.label}: a registered source is on but the stream reads SERVICE_DISABLED (fault at step $faultStep)")
                    }
                }
                StepKind.DELIVERY -> {
                    val registered: Boolean = backend.unreleased().any { registration: FakeLocationBackend.Registration ->
                        registration.provider == step.provider
                    }
                    if (registered && latitudeOf(last) != step.latitude) {
                        problem("step $stepNo ${step.label}: the delivered fix is not the reading: ${describe(last)} (fault at step $faultStep)")
                    }
                }
                StepKind.QUIET -> Unit
            }
        }
        val totalCalls: Int = backend.calls
        val sites: List<String> = backend.sites.toList()

        // An ended stream: what the contract allows, and nothing left behind.
        failure?.let { thrown: Throwable ->
            val allowed: Boolean = (faultStep == 0 && thrown.message?.startsWith("r7-injected") == true) || errorAllowed(thrown)
            if (!allowed) problem("the collector failed with a fault outside the start (fault at step $faultStep): $thrown")
        }
        if (completed) {
            val last: LocationReading? = seen.lastOrNull()
            if (last != permissionMissing) problem("the stream completed after ${describe(last)}")
            if (!scenario.terminalByDesign && kind != Kind.SECURITY) problem("the stream ended with PERMISSION_MISSING for a $kind fault")
        }
        if (failure != null || completed) checkLedger("after the stream ended")

        // Heal the world and settle: off, then on with a fresh cache, then a live fix.
        backend.healed = true
        if (failure == null && !completed) {
            fire("settle: device off") { fake.setDeviceLocationEnabled(false) }
            val endedAfterOff: Boolean = completed
            if (!endedAfterOff && seen.lastOrNull() != serviceDisabled) problem("settle: device off reads ${describe(seen.lastOrNull())}")
            fire("settle: heal") { scenario.heal(fake) }
            cacheAll(fake, settleLatitude)
            fire("settle: device on") { fake.setDeviceLocationEnabled(true) }
            if (completed) {
                if (kind != Kind.SECURITY || seen.lastOrNull() != permissionMissing) problem("settle: the stream ended after ${describe(seen.lastOrNull())}")
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
                checkDuplicates("settle")
                job.cancel()
                runCurrent()
                checkLedger("after cancel")
            }
        } else {
            // Re-init: a new collection on the healed world works and releases.
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

    private fun TestScope.exhaustStream(scenario: StreamScenario) {
        val dryProblems: MutableList<String> = mutableListOf()
        val dry: CaseResult = runStreamCase(scenario, failAt = 0, kind = Kind.RUNTIME, mode = Mode.ONCE, label = "seed=0 ${scenario.name} fault-free", problems = dryProblems)
        assertTrue(dryProblems.isEmpty(), "control: the fault-free run of ${scenario.name} meets every invariant: $dryProblems")
        assertTrue(dry.totalCalls > 10, "control: ${scenario.name} makes platform calls: ${dry.totalCalls}")
        if (!scenario.terminalByDesign) assertTrue(dry.peakRegistrations > 0, "control: ${scenario.name} registers a listener")
        assertTrue(dry.subscriptions > 0, "control: ${scenario.name} subscribes to the service broadcast")
        val problems: MutableList<String> = mutableListOf()
        var case = 0
        for (k: Int in 1..dry.totalCalls) {
            for (kind: Kind in Kind.entries) {
                for (mode: Mode in Mode.entries) {
                    case += 1
                    val label = "seed=$case ${scenario.name} k=$k site=${dry.sites[k - 1]} kind=$kind mode=$mode"
                    runStreamCase(scenario, k, kind, mode, label, problems)
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("${problems.size} invariant violation(s) in $case cases of ${scenario.name} (T=${dry.totalCalls}); first 12:\n" + problems.take(12).joinToString("\n"))
        }
    }

    @Test
    fun `every platform call of an explicit stream can fail with every kind and the stream stays truthful and leak-free`() =
        runTest(timeout = 600.seconds) { exhaustStream(explicitGpsStream) }

    @Test
    fun `every platform call of an AUTO stream with moves can fail with every kind and the stream stays truthful and leak-free`() =
        runTest(timeout = 600.seconds) { exhaustStream(autoBalancedStream) }

    @Test
    fun `every platform call of a waiting stream that gets its grant can fail with every kind`() =
        runTest(timeout = 600.seconds) { exhaustStream(waitingThenGrantStream) }

    @Test
    fun `every platform call of a waiting stream that ends with PERMISSION_MISSING can fail with every kind`() =
        runTest(timeout = 600.seconds) { exhaustStream(waitingTerminalStream) }

    // ---------------------------------------------------------------------------------------------
    // The one-shot case runner
    // ---------------------------------------------------------------------------------------------

    private class OneShotScenario(
        val name: String,
        val providers: Set<String>,
        val setup: (FakeLocationBackend) -> Unit,
        val options: CurrentLocationOptions,
        val steps: List<Step>,
        val expected: LocationReading,
        /** Readings the call may answer under a fault besides [expected], NO_FIX, and PERMISSION_MISSING for a refusal. */
        val alsoAllowed: (LocationReading) -> Boolean,
    )

    private fun TestScope.runOneShotCase(
        scenario: OneShotScenario,
        failAt: Int,
        kind: Kind,
        mode: Mode,
        label: String,
        problems: MutableList<String>,
    ): CaseResult {
        val fake = FakeLocationBackend(providers = scenario.providers)
        scenario.setup(fake)
        val backend = FaultyBackend(fake).apply {
            this.failAt = failAt
            this.kind = kind
            this.mode = mode
        }
        val uncaught: MutableList<Throwable> = mutableListOf()
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() +
                CoroutineExceptionHandler { _, thrown: Throwable -> uncaught += thrown },
        )
        val provider: LocationProvider = providerOver(backend)
        fun problem(message: String) {
            problems += "$label: $message"
        }
        fun errorAllowed(thrown: Throwable): Boolean = kind == Kind.ERROR && (thrown is InjectedError || thrown.cause is InjectedError)
        val call: Deferred<LocationReading> = scope.async { provider.getCurrentLocation(scenario.options) }
        runCurrent()
        for ((index: Int, step: Step) in scenario.steps.withIndex()) {
            val escaped: Throwable? = try {
                step.apply(fake)
                null
            } catch (thrown: Throwable) {
                thrown
            }
            runCurrent()
            if (escaped != null && !errorAllowed(escaped)) problem("step ${index + 1} ${step.label}: a throwable escaped into the platform callback: $escaped")
        }
        advanceTimeBy(scenario.options.timeout + 1.seconds)
        runCurrent()
        val totalCalls: Int = backend.calls
        val faultSite: String? = if (failAt in 1..backend.sites.size) backend.sites[failAt - 1].substringBefore('(') else null
        if (!call.isCompleted) {
            problem("the call is still pending after its timeout")
            call.cancel()
            runCurrent()
        } else {
            val thrown: Throwable? = call.getCompletionExceptionOrNull()
            if (thrown != null) {
                if (!errorAllowed(thrown)) problem("getCurrentLocation threw: $thrown")
            } else {
                val answer: LocationReading = call.getCompleted()
                val allowed: Boolean = describe(answer) == describe(scenario.expected) || answer == noFix || scenario.alsoAllowed(answer) ||
                    (kind == Kind.SECURITY && answer == permissionMissing)
                if (!allowed) problem("answer ${describe(answer)} is not allowed (fault-free: ${describe(scenario.expected)})")
                if (kind != Kind.ERROR && faultSite in setOf("removeUpdates", "currentCancel", "unsubscribe") && describe(answer) != describe(scenario.expected)) {
                    problem("a failure in the cleanup ($faultSite) changed the answer to ${describe(answer)}")
                }
            }
        }
        val left: List<String> = backend.unreleased().map { registration: FakeLocationBackend.Registration -> registration.provider }
        if (left.isNotEmpty()) problem("listeners never released: $left")
        val subscriptions: Int = backend.subscriptions.count { subscription: Subscription -> !subscription.releaseAttempted }
        if (subscriptions != 0) problem("$subscriptions service subscription(s) never released")
        val openCurrents: Int = backend.currents.count { current: Current -> !current.answered && !current.cancelAttempted }
        if (openCurrents != 0) problem("$openCurrents framework one-shot call(s) neither answered nor cancelled")
        val stray: List<Throwable> = uncaught.filterNot(::errorAllowed)
        if (stray.isNotEmpty()) problem("uncaught exceptions: $stray")
        // Re-init: the healed world answers from its cache.
        backend.healed = true
        healAll(fake)
        fake.setDeviceLocationEnabled(true)
        cacheAll(fake, settleLatitude)
        val again: Deferred<LocationReading> = scope.async { provider.getCurrentLocation(scenario.options) }
        runCurrent()
        val reread: LocationReading? = if (again.isCompleted && again.getCompletionExceptionOrNull() == null) again.getCompleted() else null
        if (latitudeOf(reread) != settleLatitude) problem("re-init: the healed world answers ${describe(reread)}")
        scope.cancel()
        return CaseResult(totalCalls, backend.sites.toList(), backend.peakRegistrations, backend.subscriptions.size)
    }

    private fun TestScope.exhaustOneShot(scenario: OneShotScenario) {
        val dryProblems: MutableList<String> = mutableListOf()
        val dry: CaseResult = runOneShotCase(scenario, failAt = 0, kind = Kind.RUNTIME, mode = Mode.ONCE, label = "seed=0 ${scenario.name} fault-free", problems = dryProblems)
        assertTrue(dryProblems.isEmpty(), "control: the fault-free run of ${scenario.name} meets every invariant: $dryProblems")
        assertTrue(dry.totalCalls > 5, "control: ${scenario.name} makes platform calls: ${dry.totalCalls}")
        val problems: MutableList<String> = mutableListOf()
        var case = 0
        for (k: Int in 1..dry.totalCalls) {
            for (kind: Kind in Kind.entries) {
                for (mode: Mode in Mode.entries) {
                    case += 1
                    val label = "seed=$case ${scenario.name} k=$k site=${dry.sites[k - 1]} kind=$kind mode=$mode"
                    runOneShotCase(scenario, k, kind, mode, label, problems)
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("${problems.size} invariant violation(s) in $case cases of ${scenario.name} (T=${dry.totalCalls}); first 12:\n" + problems.take(12).joinToString("\n"))
        }
    }

    private fun fixAt(vararg latitudes: Double): (LocationReading) -> Boolean = { reading: LocationReading ->
        latitudeOf(reading) in latitudes.toList()
    }

    @Test
    fun `every platform call of a raced one-shot can fail with every kind and the call answers, releases and never throws`() =
        runTest(timeout = 600.seconds) {
            exhaustOneShot(
                OneShotScenario(
                    name = "raced-listeners",
                    providers = setOf(gps, network),
                    setup = { },
                    options = CurrentLocationOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, timeout = 10.seconds),
                    steps = listOf(delivery(network, 20.0), delivery(gps, 21.0), delivery(network, 22.0)),
                    expected = placeholder(20.0),
                    alsoAllowed = fixAt(20.0, 21.0, 22.0),
                ),
            )
        }

    @Test
    fun `every platform call of a framework one-shot with a stale answer and a listener fallback can fail with every kind`() =
        runTest(timeout = 600.seconds) {
            exhaustOneShot(
                OneShotScenario(
                    name = "framework-then-listener",
                    providers = setOf(gps),
                    setup = { fake: FakeLocationBackend -> fake.supportsCurrentLocation = true },
                    options = CurrentLocationOptions(
                        timeout = 10.seconds,
                        platform = AndroidCurrentLocationOptions(source = AndroidLocationSource.GPS),
                    ),
                    steps = listOf(
                        Step("framework answers a stale fix", StepKind.QUIET) { fake: FakeLocationBackend ->
                            if (fake.currentCalls().isNotEmpty()) fake.answerCurrentCall(0, fake.locationAt(gps, 29.0, ageMillis = 120_000L))
                        },
                        delivery(gps, 30.0),
                        delivery(gps, 31.0),
                    ),
                    expected = placeholder(30.0),
                    alsoAllowed = fixAt(30.0, 31.0),
                ),
            )
        }

    @Test
    fun `every platform call of a one-shot whose service goes off mid-race can fail with every kind`() =
        runTest(timeout = 600.seconds) {
            exhaustOneShot(
                OneShotScenario(
                    name = "off-mid-race",
                    providers = setOf(gps, network),
                    setup = { },
                    options = CurrentLocationOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, timeout = 10.seconds),
                    steps = listOf(
                        service("network off") { it.setProviderEnabled(network, false) },
                        service("device off") { it.setDeviceLocationEnabled(false) },
                    ),
                    expected = serviceDisabled,
                    alsoAllowed = { reading: LocationReading -> reading == serviceDisabled },
                ),
            )
        }

    @Test
    fun `every platform call of a cached one-shot and of a timed-out one can fail with every kind`() =
        runTest(timeout = 600.seconds) {
            exhaustOneShot(
                OneShotScenario(
                    name = "cached",
                    providers = setOf(gps, network),
                    setup = { fake: FakeLocationBackend -> cacheAll(fake, 40.0) },
                    options = CurrentLocationOptions(accuracy = LocationAccuracy.BALANCED, timeout = 10.seconds),
                    steps = emptyList(),
                    expected = placeholder(40.0),
                    alsoAllowed = fixAt(40.0),
                ),
            )
            exhaustOneShot(
                OneShotScenario(
                    name = "timeout",
                    providers = setOf(gps, network),
                    setup = { },
                    options = CurrentLocationOptions(accuracy = LocationAccuracy.BALANCED, timeout = 10.seconds),
                    steps = listOf(service("gps off") { it.setProviderEnabled(gps, false) }),
                    expected = noFix,
                    // A source that failed is out of the race (its "on" flag says nothing), so gps going off leaves only
                    // switched-off sources: SERVICE_DISABLED is the designed answer then (D19 over the sources in the race).
                    alsoAllowed = { reading: LocationReading -> reading == serviceDisabled },
                ),
            )
        }

    /**
     * The expected Available reading of a scenario, built the way the provider builds it from a [FakeLocationBackend]
     * fix (same coordinates, accuracy and boot-clock stamp), so equality with the answer is exact.
     */
    private fun placeholder(latitude: Double): LocationReading {
        val fake = FakeLocationBackend()
        return LocationReading.Available(requireNotNull(fake.locationAt(gps, latitude).toLocationFix(coarseOnly = false)))
    }

    // ---------------------------------------------------------------------------------------------
    // Deterministic reproductions
    // ---------------------------------------------------------------------------------------------

    /**
     * Defect class: torn state (the recorded state no longer matches what was sent). Round 7's m1 fix keeps the old
     * state (`disabledNow = true`) when the reseed's reading fails to build, so that the next event retries. But a live
     * fix can be sent meanwhile: the stream now reads "on" (a fix) while the session still records "off". The next
     * switch-off then finds "already reported" and sends nothing: the stream shows a position while the source is off
     * (PLAN 4.2: SERVICE_DISABLED at once). Turns green when sending a live fix keeps the recorded state in step with
     * what was sent, or when the failed reseed records and reports something.
     */
    @Test
    fun `a live fix after a reseed that failed to build does not hide the next switch-off`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps)).apply { locationEnabled = false }
        fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
        val backend = FaultyBackend(fake).apply { kind = Kind.RUNTIME }
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            providerOver(backend).observeLocation(explicitGpsStream.options).collect { reading: LocationReading -> seen += reading }
        }
        runCurrent()
        assertEquals(listOf(serviceDisabled), seen, "control: off")
        backend.armedSite = "hasFinePermission"
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        backend.armedSite = null
        assertTrue(backend.injected > 0, "control: the reseed read the grant and it failed")
        assertEquals(listOf(serviceDisabled), seen, "control: the failed reseed sent nothing")
        fake.deliver(gps, fake.locationAt(gps, latitude = 9.0))
        runCurrent()
        assertEquals(9.0, latitudeOf(seen.last()), "control: the live fix is delivered: ${seen.map(::describe)}")
        fake.setProviderEnabled(gps, false)
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "gps is off but the stream still shows a position: ${seen.map(::describe)}")
        job.cancel()
        runCurrent()
        assertEquals(0, fake.registeredCount, "nothing left registered")
    }

    /** The same with the device switch, and with the boot-clock read (not the grant) failing inside the reseed. */
    @Test
    fun `a live fix after a reseed whose clock read failed does not hide the next device switch-off`() = runTest(timeout = 60.seconds) {
        val fake = FakeLocationBackend(providers = setOf(gps)).apply { locationEnabled = false }
        fake.setCached(gps, fake.locationAt(gps, latitude = 7.0, ageMillis = 1_000L))
        val backend = FaultyBackend(fake).apply { kind = Kind.ILLEGAL_ARGUMENT }
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            providerOver(backend).observeLocation(explicitGpsStream.options).collect { reading: LocationReading -> seen += reading }
        }
        runCurrent()
        backend.armedSite = "elapsedRealtimeNanos"
        fake.setDeviceLocationEnabled(true)
        runCurrent()
        backend.armedSite = null
        assertTrue(backend.injected > 0, "control: the reseed read the clock and it failed")
        fake.deliver(gps, fake.locationAt(gps, latitude = 9.0))
        runCurrent()
        assertEquals(9.0, latitudeOf(seen.last()), "control: the live fix is delivered: ${seen.map(::describe)}")
        fake.setDeviceLocationEnabled(false)
        runCurrent()
        assertEquals(serviceDisabled, seen.last(), "the device is off but the stream still shows a position: ${seen.map(::describe)}")
        job.cancel()
        runCurrent()
    }

    /**
     * The app logger is a foreign callback too. The suite's own logger contains a throwing sink
     * (docs/kmptoolkit-logging/04-api-reference.md, LogSink "May throw. The logger contains it"), so the failure paths
     * that log from inside a platform callback (a dropped fix, a failed re-read, a failed removal) stay contained.
     */
    @Test
    fun `a throwing log sink behind the suite logger never escapes from the failure paths`() = runTest(timeout = 60.seconds) {
        val logger: Logger = createLoggerFactory(
            sinks = listOf(LogSink { _, _, _, _ -> throw IllegalStateException("r7: sink failed") }),
        ).logger("location")
        val fake = FakeLocationBackend(providers = setOf(gps))
        val backend = FaultyBackend(fake).apply { kind = Kind.RUNTIME }
        val seen: MutableList<LocationReading> = mutableListOf()
        val provider: LocationProvider = providerOver(backend, logger)
        val job: Job = backgroundScope.launch { provider.observeLocation(explicitGpsStream.options).collect { seen += it } }
        runCurrent()
        backend.armedSite = "hasFinePermission"
        fake.deliver(gps, fake.locationAt(gps, latitude = 9.0))
        backend.armedSite = "isLocationEnabled"
        fake.setDeviceLocationEnabled(false)
        backend.armedSite = "removeUpdates"
        job.cancel()
        runCurrent()
        backend.armedSite = null
        assertTrue(backend.injected >= 3, "control: three failure paths ran: ${backend.injected}")
        assertEquals(1, fake.registeredCount, "control: the failed removal left the platform registration (nothing to retry)")
    }
}
