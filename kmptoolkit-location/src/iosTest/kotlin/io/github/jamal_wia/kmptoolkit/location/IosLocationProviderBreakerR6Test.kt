@file:OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied

/**
 * Breaker round 6 of group loc-ios (LOC-8/LOC-9, ticket 5.1.0, round 23): the whole class of re-entrancy
 * from a synchronous collector on `Dispatchers.Main.immediate`, after the round-8 fix of M1 (round 5).
 *
 * The real provider runs on `Dispatchers.Main.immediate` and so does a UI collector: a value sent from a main
 * task resumes the collector in place, inside the send. A collector that, inside that send, ends its
 * collection, cancels another one, or starts a NEW collection (of the same cold flow or of a fresh one) runs
 * all of that before the send returns. Contract (provider KDoc, D-12): when a collection ends the manager is
 * already stopped and released; every manager lives on the main queue, its delegate is held strongly and set
 * to nil (detach) exactly once before it is dropped; every did-become-active registration is removed exactly
 * once, on main; a new collection gets its own manager and session. Derived from that, not from the code.
 *
 * Attacks: a re-collect inside every emission of the stream (first seed, the restart seed for each of its three
 * causes, SERVICE_DISABLED, the hold NO_FIX, PERMISSION_MISSING, the first fix of a batch, the pause NO_FIX),
 * with the outer collection kept or ended both ways; the same for `observeLocationEnabled`; a caller of
 * `getCurrentLocation` that resumes in place and calls again (and starts a stream) from inside the delegate
 * callback that completed it; a seeded storm with collectors that end, re-collect or cancel a sibling inside any
 * reading, with a per-registration ledger. Virtual time only; every storm message carries `seed=`.
 */
class IosLocationProviderBreakerR6Test {

    // ---------------------------------------------------------------------------------------------
    // Seams

    /** `Dispatchers.Main.immediate` on the fake main queue: no dispatch while a main task runs. */
    private class ImmediateMain(private val main: TrackedDispatcher) : CoroutineDispatcher(), Delay by main {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !main.isRunning

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            main.dispatch(context, block)
        }
    }

    /** One did-become-active registration. [owner] is the manager whose session registered it. */
    private class Entry(val id: Int, val owner: FakeIosLocationManager?, val onMain: Boolean, val ownerDetachedAtAdd: Boolean) {
        var removals: Int = 0
        var removedOffMain: Int = 0
        var removedAfterDetach: Boolean = false
        val live: Boolean get() = removals == 0

        override fun toString(): String =
            "#$id owner=${owner?.let { "m${it.hashCode() % 1000}" }} onMain=$onMain detachedAtAdd=$ownerDetachedAtAdd " +
                "removals=$removals offMain=$removedOffMain afterDetach=$removedAfterDetach"
    }

    private class Env(val fake: FakeIosLocationEnvironment) : IosLocationEnvironment by fake {
        override val mainDispatcher: CoroutineDispatcher = ImmediateMain(fake.main)
        val ledger: MutableList<Entry> = mutableListOf()

        /** The manager whose delegate event is being delivered: the owner of a registration made inside it. */
        var deliveringTo: FakeIosLocationManager? = null

        override fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
            // A registration made outside a delivery is the opt-in listener of the manager created just before it.
            val owner: FakeIosLocationManager? = deliveringTo ?: fake.managers.lastOrNull()
            val entry = Entry(ledger.size, owner, fake.main.isRunning, owner?.detached ?: true)
            ledger += entry
            val remove: () -> Unit = fake.addBecameActiveListener(onActive)
            return {
                entry.removals++
                if (!fake.main.isRunning) entry.removedOffMain++
                if (entry.owner?.detached == true) entry.removedAfterDetach = true
                remove()
            }
        }

        fun ledgerProblems(): List<String> = ledger.mapNotNull { entry: Entry ->
            when {
                entry.owner == null -> "registered without a manager: $entry"
                entry.ownerDetachedAtAdd -> "registered after its manager was released: $entry"
                !entry.onMain -> "registered off main: $entry"
                entry.removals > 1 -> "removed twice: $entry"
                entry.removedOffMain > 0 -> "removed off main: $entry"
                entry.removedAfterDetach -> "removed after its manager was detached: $entry"
                else -> null
            }
        }

        /** Once every collection is over: nothing running, every manager released once on main, every registration removed once. */
        fun finalProblems(): List<String> = buildList {
            addAll(ledgerProblems())
            ledger.filter { it.removals != 1 }.forEach { add("not removed exactly once: $it") }
            if (fake.activeListeners.isNotEmpty()) add("${fake.activeListeners.size} listeners still registered")
            if (fake.registered != fake.unregistered) add("registered ${fake.registered} vs unregistered ${fake.unregistered}")
            if (ledger.size != fake.registered) add("control: ledger ${ledger.size} vs fake ${fake.registered}")
            fake.managers.forEachIndexed { index: Int, m: FakeIosLocationManager ->
                if (m.detachCount != 1) add("manager $index detached ${m.detachCount} times")
                if (m.detachedOnMain != true) add("manager $index detached off main")
                if (m.updating) add("manager $index is updating after its release (starts=${m.startCount})")
                if (!m.createdOnMain) add("manager $index created off main")
            }
        }

        fun deliver(manager: FakeIosLocationManager, block: () -> Unit) {
            deliveringTo = manager
            try {
                block()
            } finally {
                deliveringTo = null
            }
        }
    }

    private class Fixture(scope: TestScope) {
        val fake = FakeIosLocationEnvironment(scope.testScheduler)
        val env = Env(fake)
        val provider = IosLocationProvider(env, NoopLogger)
    }

    private class Probe(val name: String) {
        lateinit var job: Job
        val labels: MutableList<String> = mutableListOf()
        var failure: Throwable? = null
        var completedNormally: Boolean = false
    }

    private fun label(reading: LocationReading): String = when (reading) {
        is LocationReading.Available -> "fix${reading.fix.coordinates.latitude.toInt()}"
        is LocationReading.Unavailable -> reading.reason.name
    }

    private fun options(optIn: Boolean): LocationUpdatesOptions =
        LocationUpdatesOptions(platform = IosLocationUpdatesOptions(pausesLocationUpdatesAutomatically = optIn))

    /**
     * A UI-like collector on the immediate main dispatcher. [onReading] runs inside the emission, in place, and
     * returns true to end the collection there: with `first { ... }` when [useFirst], else with a cancel of its own job.
     */
    private fun TestScope.collect(
        f: Fixture,
        flow: Flow<LocationReading>,
        name: String,
        useFirst: Boolean = false,
        onReading: (Probe, String) -> Boolean = { _, _ -> false },
    ): Probe {
        val probe = Probe(name)
        probe.job = backgroundScope.launch(f.env.mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                if (useFirst) {
                    flow.first { reading: LocationReading ->
                        probe.labels += label(reading)
                        onReading(probe, label(reading))
                    }
                } else {
                    flow.collect { reading: LocationReading ->
                        probe.labels += label(reading)
                        if (onReading(probe, label(reading))) currentCoroutineContext().job.cancel()
                    }
                }
                probe.completedNormally = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                probe.failure = failure
            }
        }
        return probe
    }

    /** CoreLocation and the notification center call on the main queue: one main task running [block]. */
    private fun TestScope.onMain(f: Fixture, block: () -> Unit) {
        backgroundScope.launch(f.fake.mainDispatcher) { block() }
        runCurrent()
    }

    // ---------------------------------------------------------------------------------------------
    // 1. A re-collect inside every emission of the stream

    private enum class Site(val trigger: String) {
        FIRST_SEED("NO_FIX"),
        RESTART_BY_ACTIVATION("fix7"),
        RESTART_BY_AUTHORIZATION_CALLBACK("fix7"),
        RESTART_BY_SERVICES_BACK_ON("fix7"),
        SERVICE_DISABLED("SERVICE_DISABLED"),
        HOLD_NO_FIX("NO_FIX"),
        PERMISSION_MISSING("PERMISSION_MISSING"),
        FIRST_FIX_OF_A_BATCH("fix1"),
        PAUSE_NO_FIX("NO_FIX"),
    }

    private enum class Mode { KEEP_AND_RECOLLECT, FIRST_AND_RECOLLECT, CANCEL_AND_RECOLLECT }

    /**
     * Defect classes: work after release, listener added after release, cross-talk between sessions, double or
     * missing release, re-init. Inside the emission of [Site], the outer collector starts a new collection (of
     * the SAME cold flow object) on the immediate main dispatcher, and either keeps its own collection or ends it
     * (`first`, cancel). Expected: the inner collection gets a manager of its own (none for PERMISSION_MISSING),
     * which runs exactly when its own first read says so; the outer manager ends up exactly as it would without
     * the re-collect (kept: restarted once / disabled / holding / paused; ended: released once, start count frozen
     * at the event); every registration has a live owner when added; after both end, the final ledger is clean.
     * Mutation by reading: dropping the `released || manager !== current` check after `emitSeed` turns the ending
     * RESTART rows red (start after release); a session that kept a reference to a shared manager or listener
     * list, or an `out` followed by `beginUpdates` in another branch, turns the KEEP rows red.
     */
    @Test
    fun `a collector that re-collects inside any emission gets its own session and leaves the outer one as it should be`() = runTest {
        val problems: MutableList<String> = mutableListOf()
        for (site: Site in Site.values()) {
            for (mode: Mode in Mode.values()) {
                val optIns: List<Boolean> = if (site == Site.PAUSE_NO_FIX) listOf(true) else listOf(false, true)
                for (optIn: Boolean in optIns) {
                    val where = "site=$site mode=$mode optIn=$optIn"
                    problems += reCollectCase(site, mode, optIn).map { "$where: $it" }
                }
            }
        }
        assertTrue(problems.isEmpty(), "re-collect inside an emission:\n${problems.joinToString("\n")}")
    }

    private fun TestScope.reCollectCase(site: Site, mode: Mode, optIn: Boolean): List<String> {
        val out: MutableList<String> = mutableListOf()
        val f = Fixture(this)
        val flow: Flow<LocationReading> = f.provider.observeLocation(options(optIn))
        var armed: Boolean = site == Site.FIRST_SEED
        var inner: Probe? = null
        var managersAtTrigger = -1
        if (site == Site.HOLD_NO_FIX || site == Site.PAUSE_NO_FIX) f.fake.cached = rawFix(f.fake.clock, latitude = 5.0)
        val outer: Probe = collect(f, flow, "outer", useFirst = mode == Mode.FIRST_AND_RECOLLECT) { _: Probe, label: String ->
            if (!armed || label != site.trigger || inner != null) return@collect false
            armed = false
            managersAtTrigger = f.fake.managers.size
            inner = collect(f, flow, "inner")
            mode != Mode.KEEP_AND_RECOLLECT
        }
        runCurrent()
        val first: FakeIosLocationManager? = f.fake.managers.firstOrNull()
        if (first == null) return listOf("control: the outer collection has no manager")
        when (site) {
            Site.FIRST_SEED -> Unit
            Site.RESTART_BY_ACTIVATION, Site.RESTART_BY_AUTHORIZATION_CALLBACK -> {
                onMain(f) { f.env.deliver(first) { first.fail(IosFailureKind.DENIED) } }
                runCurrent()
                if (first.updating) out += "control: the denial did not stop the outer manager"
                f.fake.cached = rawFix(f.fake.clock, latitude = 7.0)
                armed = true
                onMain(f) {
                    if (site == Site.RESTART_BY_ACTIVATION) f.fake.activate() else f.env.deliver(first) { first.authorizationChanged() }
                }
            }
            Site.RESTART_BY_SERVICES_BACK_ON -> {
                f.fake.servicesOn = false
                onMain(f) { f.fake.announceChange() }
                runCurrent()
                f.fake.cached = rawFix(f.fake.clock, latitude = 7.0)
                f.fake.servicesOn = true
                armed = true
                onMain(f) { f.fake.announceChange() }
            }
            Site.SERVICE_DISABLED -> {
                f.fake.servicesOn = false
                armed = true
                onMain(f) { f.fake.announceChange() }
            }
            Site.HOLD_NO_FIX -> {
                armed = true
                onMain(f) { f.env.deliver(first) { first.fail(IosFailureKind.DENIED) } }
            }
            Site.PERMISSION_MISSING -> {
                f.fake.status = kCLAuthorizationStatusDenied
                armed = true
                onMain(f) { f.fake.announceChange() }
            }
            Site.FIRST_FIX_OF_A_BATCH -> {
                armed = true
                onMain(f) {
                    f.env.deliver(first) {
                        first.deliver(rawFix(f.fake.clock, latitude = 1.0), rawFix(f.fake.clock, latitude = 2.0), rawFix(f.fake.clock, latitude = 3.0))
                    }
                }
            }
            Site.PAUSE_NO_FIX -> {
                armed = true
                onMain(f) { f.env.deliver(first) { first.pause() } }
            }
        }
        runCurrent()
        val innerProbe: Probe = inner ?: return listOf("control: the trigger ${site.trigger} never reached the outer collector: ${outer.labels}")
        val startsAtEnd: Int = first.startCount
        if (outer.failure != null) out += "outer failed: ${outer.failure}"
        if (innerProbe.failure != null) out += "inner failed: ${innerProbe.failure}"
        if (innerProbe.labels.isEmpty()) out += "the inner collection received nothing"

        // The inner collection.
        val expectsInnerManager: Boolean = site != Site.PERMISSION_MISSING
        val innerManagers: List<FakeIosLocationManager> = f.fake.managers.drop(managersAtTrigger)
        if (expectsInnerManager) {
            if (innerManagers.size != 1) {
                out += "the inner collection created ${innerManagers.size} managers (managers before the trigger $managersAtTrigger)"
            } else {
                val im: FakeIosLocationManager = innerManagers.single()
                val shouldRun: Boolean = site != Site.SERVICE_DISABLED
                if (im === first) out += "the inner collection shares the outer manager"
                if (im.detached) out += "the inner manager was released while its collection runs"
                if (im.updating != shouldRun) out += "inner manager updating=${im.updating}, expected $shouldRun (starts=${im.startCount})"
                if (im.startCount != (if (shouldRun) 1 else 0)) out += "inner manager started ${im.startCount} times"
                if (innerProbe.job.isCompleted) out += "the inner collection ended by itself: ${innerProbe.labels}"
            }
        } else {
            if (innerManagers.isNotEmpty()) out += "the inner collection created a manager although permission is missing"
            if (!innerProbe.job.isCompleted) out += "the inner collection did not end at PERMISSION_MISSING"
        }

        // The outer collection.
        val ended: Boolean = mode != Mode.KEEP_AND_RECOLLECT || site == Site.PERMISSION_MISSING
        if (ended) {
            if (!outer.job.isCompleted) out += "the outer collection did not end: ${outer.labels}"
            if (first.detachCount != 1) out += "the outer manager was detached ${first.detachCount} times"
            if (first.updating) out += "the released outer manager is updating (starts=$startsAtEnd)"
            // One start: the first one (for FIRST_SEED the first enter runs inside the producer, so it starts and the
            // producer's finally releases it); no restart may follow an ending inside the emission.
            if (startsAtEnd != 1) out += "the outer manager was started $startsAtEnd times, expected 1"
        } else {
            if (outer.job.isCompleted) out += "the kept outer collection ended: ${outer.labels}"
            if (first.detached) out += "the kept outer manager was released"
            val (updating: Boolean, starts: Int) = when (site) {
                Site.FIRST_SEED, Site.FIRST_FIX_OF_A_BATCH, Site.PAUSE_NO_FIX -> true to 1
                Site.RESTART_BY_ACTIVATION, Site.RESTART_BY_AUTHORIZATION_CALLBACK, Site.RESTART_BY_SERVICES_BACK_ON -> true to 2
                Site.SERVICE_DISABLED, Site.HOLD_NO_FIX -> false to 1
                Site.PERMISSION_MISSING -> false to 1
            }
            if (first.updating != updating) out += "kept outer manager updating=${first.updating}, expected $updating"
            if (first.startCount != starts) out += "kept outer manager started ${first.startCount} times, expected $starts"
            if (site == Site.FIRST_FIX_OF_A_BATCH && outer.labels.takeLast(3) != listOf("fix1", "fix2", "fix3")) {
                out += "the kept outer collection lost fixes of the batch: ${outer.labels}"
            }
            // The outer session's live listeners: its opt-in one (when opted in) plus the hold's (when holding).
            val liveOwn: Int = f.env.ledger.count { it.owner === first && it.live }
            val expectedOwn: Int = (if (optIn) 1 else 0) + (if (site == Site.HOLD_NO_FIX) 1 else 0)
            if (liveOwn != expectedOwn) out += "the kept outer session has $liveOwn live listeners, expected $expectedOwn"
        }
        out += f.env.ledgerProblems()

        // An activation now must not touch a released manager or wake a session that is gone.
        val frozen: Map<FakeIosLocationManager, Int> = f.fake.managers.filter { it.detached }.associateWith { it.startCount }
        onMain(f) { f.fake.activate() }
        runCurrent()
        frozen.forEach { (m: FakeIosLocationManager, starts: Int) -> if (m.startCount != starts || m.updating) out += "an activation after the release started a released manager" }

        outer.job.cancel()
        innerProbe.job.cancel()
        runCurrent()
        out += f.env.finalProblems()
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // 2. observeLocationEnabled

    /**
     * Defect classes: work after release, manager leak, double release, cross-talk. A collector of
     * `observeLocationEnabled` on the immediate main dispatcher that, inside an emission (the first read, or the
     * read after a switch flip announced by an authorization callback), ends (`first`, cancel) and/or starts a new
     * collection of the same flow. Expected: one manager per collection, each detached exactly once on main when
     * its collection ends, never before; the inner collection keeps observing (it sees the next flip) and the
     * outer, when kept, too.
     * Mutation by reading: a manager created outside the try (or detached outside NonCancellable + main) turns
     * the ending rows red; a shared manager field turns the inner rows red.
     */
    @Test
    fun `observeLocationEnabled collectors that end or re-collect inside an emission release each manager once`() = runTest {
        val problems: MutableList<String> = mutableListOf()
        for (at: String in listOf("first read", "read after a flip")) {
            for (mode: Mode in Mode.values()) {
                val where = "at=$at mode=$mode"
                val f = Fixture(this)
                val flow: Flow<Boolean> = f.provider.observeLocationEnabled()
                var armed: Boolean = at == "first read"
                val outerValues: MutableList<Boolean> = mutableListOf()
                val innerValues: MutableList<Boolean> = mutableListOf()
                var innerJob: Job? = null
                lateinit var outerJob: Job
                val onValue: (Boolean) -> Boolean = { _: Boolean ->
                    if (armed && innerJob == null) {
                        armed = false
                        innerJob = backgroundScope.launch(f.env.mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
                            flow.collect { value: Boolean -> innerValues += value }
                        }
                        mode != Mode.KEEP_AND_RECOLLECT
                    } else {
                        false
                    }
                }
                outerJob = backgroundScope.launch(f.env.mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
                    if (mode == Mode.FIRST_AND_RECOLLECT) {
                        flow.first { value: Boolean ->
                            outerValues += value
                            onValue(value)
                        }
                    } else {
                        flow.collect { value: Boolean ->
                            outerValues += value
                            if (onValue(value)) currentCoroutineContext().job.cancel()
                        }
                    }
                }
                runCurrent()
                if (at == "read after a flip") {
                    f.fake.servicesOn = false
                    armed = true
                    onMain(f) { f.fake.announceChange() }
                    runCurrent()
                }
                val job: Job? = innerJob
                if (job == null) {
                    problems += "$where: control: the trigger never reached the collector: $outerValues"
                    continue
                }
                val ended: Boolean = mode != Mode.KEEP_AND_RECOLLECT
                if (f.fake.managers.size != 2) problems += "$where: ${f.fake.managers.size} managers for two collections"
                val outerManager: FakeIosLocationManager = f.fake.managers.first()
                val innerManager: FakeIosLocationManager? = f.fake.managers.getOrNull(1)
                if (ended) {
                    if (!outerJob.isCompleted) problems += "$where: the outer collection did not end"
                    if (outerManager.detachCount != 1) problems += "$where: the outer manager detached ${outerManager.detachCount} times"
                } else if (outerManager.detached) {
                    problems += "$where: the kept outer manager was released"
                }
                if (innerManager != null && innerManager.detached) problems += "$where: the inner manager was released while its collection runs"
                if (innerValues.isEmpty()) problems += "$where: the inner collection received nothing"
                // The next flip reaches every live collection.
                val innerBefore: Int = innerValues.size
                val outerBefore: Int = outerValues.size
                f.fake.servicesOn = !f.fake.servicesOn
                onMain(f) { f.fake.announceChange() }
                runCurrent()
                if (innerValues.size != innerBefore + 1) problems += "$where: the inner collection missed the next flip: $innerValues"
                if (!ended && outerValues.size != outerBefore + 1) problems += "$where: the kept outer collection missed the next flip: $outerValues"
                if (ended && outerValues.size != outerBefore) problems += "$where: the ended outer collection still received: $outerValues"
                outerJob.cancel()
                job.cancel()
                runCurrent()
                f.fake.managers.forEachIndexed { index: Int, m: FakeIosLocationManager ->
                    if (m.detachCount != 1) problems += "$where: manager $index detached ${m.detachCount} times"
                    if (m.detachedOnMain != true) problems += "$where: manager $index detached off main"
                    if (m.startCount != 0 || m.requestCount != 0) problems += "$where: manager $index was started (${m.startCount}/${m.requestCount})"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // ---------------------------------------------------------------------------------------------
    // 3. getCurrentLocation: a caller that resumes in place and calls again from inside the callback

    /**
     * Defect classes: work after release, cross-talk between two requests, double release. The caller runs on
     * the immediate main dispatcher, so when a delegate callback completes the request the caller resumes in
     * place, inside that callback, after the request's own release. It then calls `getCurrentLocation` again
     * (and, in the stream variant, starts an `observeLocation` collection), all before the first callback
     * returns; the rest of the callback (the remaining fixes of a batch) runs afterwards. Expected: the first
     * manager is released once and never started again; the second request owns a fresh manager that waits with
     * one `requestLocation`, untouched by the rest of the first callback; a later fix completes it normally.
     * Mutation by reading: an `onLocations` loop that read `manager` again after `outcome.complete` (instead of
     * returning), or a `keepWaiting` reached after a complete, turns the batch rows red.
     */
    @Test
    fun `a one-shot caller that resumes in place and calls again inside the callback gets a separate request`() = runTest {
        val problems: MutableList<String> = mutableListOf()
        val scenarios: List<String> = listOf("fresh fix", "batch of fixes", "denied", "authorization revoked", "other failure", "unknown then fix")
        for (scenario: String in scenarios) {
            for (follow: String in listOf("second call", "second call and a stream")) {
                val where = "scenario=$scenario follow=$follow"
                val f = Fixture(this)
                val results: MutableList<String> = mutableListOf()
                var stream: Probe? = null
                val caller: Job = backgroundScope.launch(f.env.mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
                    results += label(f.provider.getCurrentLocation(CurrentLocationOptions()))
                    // In place, inside the delegate callback that completed the first request.
                    f.fake.status = kCLAuthorizationStatusAuthorizedWhenInUse
                    if (follow.endsWith("stream")) stream = collect(f, f.provider.observeLocation(LocationUpdatesOptions()), "stream")
                    results += label(f.provider.getCurrentLocation(CurrentLocationOptions()))
                }
                runCurrent()
                val firstManager: FakeIosLocationManager = f.fake.managers.single()
                onMain(f) {
                    f.env.deliver(firstManager) {
                        when (scenario) {
                            "fresh fix" -> firstManager.deliver(rawFix(f.fake.clock, latitude = 1.0))
                            "batch of fixes" -> firstManager.deliver(rawFix(f.fake.clock, latitude = 1.0), rawFix(f.fake.clock, latitude = 2.0))
                            "denied" -> firstManager.fail(IosFailureKind.DENIED)
                            "authorization revoked" -> {
                                f.fake.status = kCLAuthorizationStatusDenied
                                firstManager.authorizationChanged()
                            }
                            "other failure" -> firstManager.fail(IosFailureKind.OTHER)
                            else -> {
                                firstManager.fail(IosFailureKind.LOCATION_UNKNOWN)
                                firstManager.deliver(rawFix(f.fake.clock, latitude = 1.0))
                            }
                        }
                    }
                }
                runCurrent()
                if (results.size != 1) {
                    problems += "$where: control: the first call did not end exactly once: $results"
                    caller.cancel()
                    runCurrent()
                    continue
                }
                val expectedFirstStarts: Int = if (scenario == "unknown then fix") 1 else 0
                if (firstManager.detachCount != 1) problems += "$where: first manager detached ${firstManager.detachCount} times"
                if (firstManager.detachedOnMain != true) problems += "$where: first manager detached off main"
                if (firstManager.updating) problems += "$where: first manager updating after its call"
                if (firstManager.startCount != expectedFirstStarts) problems += "$where: first manager started ${firstManager.startCount} times"
                val oneShots: List<FakeIosLocationManager> = f.fake.managers.filter { it.requestCount > 0 }
                if (oneShots.size != 2) {
                    problems += "$where: ${oneShots.size} one-shot managers, expected 2"
                } else {
                    val second: FakeIosLocationManager = oneShots[1]
                    if (second === firstManager) problems += "$where: the second call reused the first manager"
                    if (second.detached) problems += "$where: the second call's manager was released before its answer"
                    if (second.startCount != 0 || second.requestCount != 1) problems += "$where: the rest of the first callback touched the second request (starts=${second.startCount}, requests=${second.requestCount})"
                    onMain(f) { f.env.deliver(second) { second.deliver(rawFix(f.fake.clock, latitude = 9.0)) } }
                    runCurrent()
                    if (results.lastOrNull() != "fix9" || results.size != 2) problems += "$where: the second call did not answer its own fix: $results"
                    if (second.detachCount != 1 || second.updating) problems += "$where: the second manager was not released once (detach=${second.detachCount}, updating=${second.updating})"
                }
                stream?.let { s: Probe ->
                    val streamManager: FakeIosLocationManager? = f.fake.managers.firstOrNull { it.requestCount == 0 }
                    if (streamManager == null || !streamManager.updating) problems += "$where: the stream started inside the callback does not run"
                    if (s.job.isCompleted) problems += "$where: the stream ended by itself: ${s.labels}"
                    s.job.cancel()
                }
                caller.cancel()
                runCurrent()
                problems += f.env.finalProblems().map { "$where: $it" }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Seeded storm

    private enum class Action { NOTHING, END_FIRST, END_CANCEL, RECOLLECT_KEEP, RECOLLECT_END, CANCEL_SIBLING }

    /**
     * Defect classes: work after release, listener added after release, leak, double removal, cross-talk,
     * re-init. Up to five collections at once on the immediate main dispatcher, each acting inside its n-th
     * reading: end (`first`, cancel), re-collect (keeping or ending itself), or cancel a sibling collection, all
     * in place. Events, one or two per main turn, go to a random live manager or to all: denial, activation,
     * authorization callback, switch flip, status flip, batches of fixes, pause/resume, transient error. After
     * every step: a released manager is neither updating nor started again (start count frozen at its release),
     * no registration was made for a released manager, each live manager has at most two live listeners (opt-in
     * + hold), the ledger is clean. At the end: every manager released once on main, every registration removed
     * once.
     * Mutation by reading: any `out` followed by native work without a liveness check (e.g. removing the M1
     * check) turns seeds red ("started again"); a hold listener registered by a session after its release turns
     * them red ("registered after its manager was released").
     */
    @Test
    fun `a seeded storm of collectors that end re-collect or cancel a sibling inside any reading never runs a released manager`() = runTest {
        var recollects = 0
        var siblingCancels = 0
        var endsInside = 0
        var restarts = 0
        for (seed: Int in 1..40) {
            val random = Random(seed)
            val f = Fixture(this)
            val probes: MutableList<Probe> = mutableListOf()
            val frozen: MutableMap<FakeIosLocationManager, Int> = mutableMapOf()
            val problems: MutableList<String> = mutableListOf()
            var created = 0

            fun newCollection(): Probe {
                val optIn: Boolean = random.nextInt(3) == 0
                val actAt: Int = random.nextInt(1, 6)
                val action: Action = Action.values()[random.nextInt(Action.values().size)]
                val useFirst: Boolean = action == Action.END_FIRST
                created++
                lateinit var probe: Probe
                probe = collect(f, f.provider.observeLocation(options(optIn)), "c$created", useFirst) { self: Probe, _: String ->
                    if (self.labels.size != actAt) return@collect false
                    when (action) {
                        Action.NOTHING -> false
                        Action.END_FIRST, Action.END_CANCEL -> {
                            endsInside++
                            true
                        }
                        Action.RECOLLECT_KEEP, Action.RECOLLECT_END -> {
                            if (probes.count { !it.job.isCompleted } < 5) {
                                recollects++
                                probes += newCollection()
                            }
                            if (action == Action.RECOLLECT_END) endsInside++
                            action == Action.RECOLLECT_END
                        }
                        Action.CANCEL_SIBLING -> {
                            val sibling: Probe? = probes.firstOrNull { it !== self && !it.job.isCompleted }
                            if (sibling != null) {
                                siblingCancels++
                                sibling.job.cancel()
                            }
                            false
                        }
                    }
                }
                return probe
            }

            fun event(kind: Int, manager: FakeIosLocationManager) {
                when (kind) {
                    0 -> f.env.deliver(manager) { manager.fail(IosFailureKind.DENIED) }
                    1 -> f.fake.activate()
                    2 -> f.env.deliver(manager) { manager.authorizationChanged() }
                    3 -> {
                        f.fake.servicesOn = !f.fake.servicesOn
                        f.fake.announceChange()
                    }
                    4 -> {
                        f.fake.status = when (random.nextInt(8)) {
                            0 -> kCLAuthorizationStatusDenied
                            1 -> kCLAuthorizationStatusAuthorizedAlways
                            else -> kCLAuthorizationStatusAuthorizedWhenInUse
                        }
                        f.fake.announceChange()
                    }
                    5 -> f.env.deliver(manager) {
                        manager.deliver(*Array(random.nextInt(1, 4)) { rawFix(f.fake.clock, latitude = random.nextInt(1, 60).toDouble()) })
                    }
                    6 -> f.env.deliver(manager) { if (random.nextBoolean()) manager.pause() else manager.resume() }
                    else -> f.env.deliver(manager) { manager.fail(IosFailureKind.LOCATION_UNKNOWN) }
                }
            }

            fun check(step: Int) {
                for ((index: Int, m: FakeIosLocationManager) in f.fake.managers.withIndex()) {
                    if (!m.detached) continue
                    val at: Int = frozen.getOrPut(m) { m.startCount }
                    if (m.startCount != at) problems += "step $step: released manager $index started again (${m.startCount}, at release $at)"
                    if (m.updating) problems += "step $step: released manager $index is updating"
                    if (m.detachCount != 1) problems += "step $step: manager $index detached ${m.detachCount} times"
                }
                for (m: FakeIosLocationManager in f.fake.liveManagers) {
                    val live: Int = f.env.ledger.count { it.owner === m && it.live }
                    if (live > 2) problems += "step $step: a live manager has $live live listeners"
                }
                f.env.ledger.filter { it.live && it.owner?.detached == true }.forEach { problems += "step $step: listener live after its manager's release: $it" }
                problems += f.env.ledgerProblems().map { "step $step: $it" }
                probes.forEach { p: Probe ->
                    val failure: Throwable? = p.failure
                    // `first { ... }` on a stream that completed: legitimate only after the terminal reading, which
                    // the contract says is always delivered as the last element.
                    val endedAtTerminal: Boolean = failure is NoSuchElementException && p.labels.lastOrNull() == "PERMISSION_MISSING"
                    if (failure != null && !endedAtTerminal) problems += "step $step: ${p.name} failed: $failure labels=${p.labels}"
                }
            }

            f.fake.cached = if (random.nextBoolean()) rawFix(f.fake.clock, latitude = random.nextInt(1, 60).toDouble()) else null
            probes += newCollection()
            runCurrent()
            check(0)
            for (step: Int in 1..60) {
                if (problems.isNotEmpty()) break
                if (probes.none { !it.job.isCompleted } || random.nextInt(10) == 0) {
                    f.fake.status = kCLAuthorizationStatusAuthorizedWhenInUse
                    if (random.nextInt(3) == 0) f.fake.cached = rawFix(f.fake.clock, latitude = random.nextInt(1, 60).toDouble())
                    probes += newCollection()
                    runCurrent()
                    check(step)
                    continue
                }
                val live: List<FakeIosLocationManager> = f.fake.liveManagers
                if (live.isEmpty()) continue
                val target: FakeIosLocationManager = live[random.nextInt(live.size)]
                val kinds: List<Int> = List(random.nextInt(1, 3)) { random.nextInt(8) }
                val before: Int = f.fake.managers.sumOf { it.startCount }
                onMain(f) { kinds.forEach { kind: Int -> event(kind, target) } }
                runCurrent()
                restarts += f.fake.managers.sumOf { it.startCount } - before
                check(step)
            }
            probes.forEach { it.job.cancel() }
            runCurrent()
            problems += f.env.finalProblems()
            assertTrue(problems.isEmpty(), "seed=$seed: ${problems.take(6)}")
        }
        assertTrue(recollects > 20, "control: only $recollects re-collects inside a reading")
        assertTrue(siblingCancels > 5, "control: only $siblingCancels sibling cancels inside a reading")
        assertTrue(endsInside > 20, "control: only $endsInside collections ended inside a reading")
        assertTrue(restarts > 40, "control: only $restarts starts during the storm")
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Sanity: the shared fake behaves as the rules say (control for the cases above)

    /** Control: the ledger attributes the opt-in listener to the manager created just before it and a denial's to the denied one. */
    @Test
    fun `control the ledger attributes registrations to their sessions`() = runTest {
        val f = Fixture(this)
        val a: Probe = collect(f, f.provider.observeLocation(options(true)), "a")
        runCurrent()
        val b: Probe = collect(f, f.provider.observeLocation(options(false)), "b")
        runCurrent()
        val (ma: FakeIosLocationManager, mb: FakeIosLocationManager) = f.fake.managers
        onMain(f) { f.env.deliver(mb) { mb.fail(IosFailureKind.DENIED) } }
        runCurrent()
        assertEquals(listOf(ma, mb), f.env.ledger.map { it.owner }, "owners: ${f.env.ledger}")
        assertEquals(listOf("NO_FIX", "NO_FIX"), b.labels, "b holds")
        a.job.cancel()
        b.job.cancel()
        runCurrent()
        assertEquals(emptyList(), f.env.finalProblems())
    }
}
