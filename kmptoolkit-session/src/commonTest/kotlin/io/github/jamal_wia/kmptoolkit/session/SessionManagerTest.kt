package io.github.jamal_wia.kmptoolkit.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class SessionManagerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.manager(
        cleaners: List<SessionCleaner> = emptyList(),
        revoker: SessionRevoker? = null,
        ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
        cleanerTimeout: Duration = 5.seconds,
    ): SessionManager = createSessionManager(
        cleaners = cleaners,
        revoker = revoker,
        ioDispatcher = ioDispatcher,
        cleanerTimeout = cleanerTimeout,
        revokeTimeout = 10.seconds,
    )

    // --- State ---------------------------------------------------------------------------------

    @Test
    fun `a new manager reports no session`() = runTest {
        assertEquals(SessionState.INACTIVE, manager().state.value)
    }

    @Test
    fun `startSession opens a session`() = runTest {
        val manager: SessionManager = manager()

        manager.startSession()

        assertEquals(SessionState.ACTIVE, manager.state.value)
    }

    @Test
    fun `endSession closes the session`() = runTest {
        val cleaner = FakeCleaner()
        val manager: SessionManager = manager(cleaners = listOf(cleaner))
        manager.startSession()
        // INACTIVE is also the initial state, so asserting it alone would hold even if both methods
        // did nothing. Pin the transition and the work it did.
        assertEquals(SessionState.ACTIVE, manager.state.value)

        manager.endSession()

        assertEquals(SessionState.INACTIVE, manager.state.value)
        assertEquals(1, cleaner.cleanCalls)
    }

    @Test
    fun `startSession twice leaves one open session that one endSession closes`() = runTest {
        val cleaner = FakeCleaner()
        val manager: SessionManager = manager(cleaners = listOf(cleaner))

        manager.startSession()
        manager.startSession()

        assertEquals(SessionState.ACTIVE, manager.state.value)
        manager.endSession()
        assertEquals(SessionState.INACTIVE, manager.state.value)
        assertEquals(1, cleaner.cleanCalls)
    }

    @Test
    fun `the session stays open until every cleaner has finished`() = runTest {
        val gate = Gate()
        val manager: SessionManager = manager(cleaners = listOf(gate.cleaner()))
        manager.startSession()

        val ending: Job = launch { manager.endSession() }
        gate.awaitTeardownInFlight()
        assertEquals(SessionState.ACTIVE, manager.state.value)

        gate.release()
        ending.join()
        assertEquals(SessionState.INACTIVE, manager.state.value)
    }

    // --- Fan-out -------------------------------------------------------------------------------

    @Test
    fun `endSession runs every registered cleaner exactly once`() = runTest {
        val first = FakeCleaner(name = "first")
        val second = FakeCleaner(name = "second")
        val manager: SessionManager = manager(cleaners = listOf(first, second))
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, first.cleanCalls)
        assertEquals(1, second.cleanCalls)
        assertTrue(report.isClean)
    }

    @Test
    fun `no cleaners at all is a clean teardown`() = runTest {
        val manager: SessionManager = manager()
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(SessionEndReport.Empty, report)
        assertEquals(SessionState.INACTIVE, manager.state.value)
    }

    @Test
    fun `cleaners run concurrently rather than one after another`() = runTest {
        // Each cleaner waits for the other to have started. Sequential execution deadlocks, and the
        // cleaner timeout turns that deadlock into two recorded failures instead of a hung test.
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val manager: SessionManager = manager(
            cleaners = listOf(
                FakeCleaner(name = "first", onClean = { firstStarted.complete(Unit); secondStarted.await() }),
                FakeCleaner(name = "second", onClean = { secondStarted.complete(Unit); firstStarted.await() }),
            ),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(emptyList(), report.cleanerFailures)
    }

    @Test
    fun `two cleaners sharing a name produce two failures under that name`() = runTest {
        val manager: SessionManager = manager(
            cleaners = listOf(
                FakeCleaner(name = "dup", onClean = { throw IllegalStateException("first") }),
                FakeCleaner(name = "dup", onClean = { throw IllegalStateException("second") }),
            ),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(listOf("dup", "dup"), report.cleanerFailures.map { it.name })
        assertEquals(listOf("first", "second"), report.cleanerFailures.map { it.cause.message })
    }

    @Test
    fun `the cleaner list is copied so a later mutation cannot add a cleaner`() = runTest {
        val registered = FakeCleaner(name = "registered")
        val cleaners: MutableList<SessionCleaner> = mutableListOf(registered)
        val manager: SessionManager = manager(cleaners = cleaners)

        val sneaked = FakeCleaner(name = "sneaked")
        cleaners += sneaked
        manager.startSession()
        manager.endSession()

        assertEquals(1, registered.cleanCalls)
        assertEquals(0, sneaked.cleanCalls)
    }

    // --- Cleaner failure semantics -------------------------------------------------------------

    @Test
    fun `a throwing cleaner does not stop the others and the session still ends`() = runTest {
        val healthy = FakeCleaner(name = "healthy")
        val manager: SessionManager = manager(
            cleaners = listOf(
                FakeCleaner(name = "broken", onClean = { throw IllegalStateException("disk full") }),
                healthy,
            ),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, healthy.cleanCalls)
        assertEquals(SessionState.INACTIVE, manager.state.value)
        assertEquals(listOf("broken"), report.cleanerFailures.map { it.name })
        // Type and message rather than identity: on JVM/Android kotlinx-coroutines' stacktrace
        // recovery hands back an augmented copy of the throwable, not the instance that was thrown.
        val cause = assertIs<IllegalStateException>(report.cleanerFailures.single().cause)
        assertEquals("disk full", cause.message)
    }

    @Test
    fun `every cleaner failing is fully reported and the session still ends`() = runTest {
        val manager: SessionManager = manager(
            cleaners = listOf(
                FakeCleaner(name = "a", onClean = { throw IllegalStateException("a") }),
                FakeCleaner(name = "b", onClean = { throw IllegalStateException("b") }),
                FakeCleaner(name = "c", onClean = { throw IllegalStateException("c") }),
            ),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(listOf("a", "b", "c"), report.cleanerFailures.map { it.name })
        assertEquals(listOf("a", "b", "c"), report.cleanerFailures.map { it.cause.message })
        assertEquals(SessionState.INACTIVE, manager.state.value)
    }

    @Test
    fun `failures are reported in registration order not completion order`() = runTest {
        val manager: SessionManager = manager(
            cleaners = listOf(
                FakeCleaner(name = "slow", onClean = { delay(1.seconds); throw IllegalStateException("slow") }),
                FakeCleaner(name = "fast", onClean = { throw IllegalStateException("fast") }),
            ),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(listOf("slow", "fast"), report.cleanerFailures.map { it.name })
    }

    @Test
    fun `a cleaner that throws an Error is recorded rather than allowed to abort teardown`() = runTest {
        // kotlin.Error, not StackOverflowError — the latter has no common-code declaration.
        val healthy = FakeCleaner(name = "healthy")
        val manager: SessionManager = manager(
            cleaners = listOf(FakeCleaner(name = "fatal", onClean = { throw Error("nope") }), healthy),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, healthy.cleanCalls)
        assertEquals(listOf("fatal"), report.cleanerFailures.map { it.name })
        assertEquals(SessionState.INACTIVE, manager.state.value)
    }

    @Test
    fun `a stuck cleaner is abandoned at the timeout and the rest of teardown proceeds`() = runTest {
        val healthy = FakeCleaner(name = "healthy")
        val manager: SessionManager = manager(
            cleaners = listOf(FakeCleaner(name = "stuck", onClean = { delay(Long.MAX_VALUE) }), healthy),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, healthy.cleanCalls)
        assertEquals(SessionState.INACTIVE, manager.state.value)
        val failure: SessionCleanerFailure = report.cleanerFailures.single()
        assertEquals("stuck", failure.name)
        val timeout = assertIs<SessionTeardownTimeoutException>(failure.cause)
        assertEquals("stuck", timeout.name)
        assertEquals(5.seconds, timeout.timeout)
    }

    @Test
    fun `a cleaner that ignores cancellation is abandoned at the timeout rather than awaited`() = runTest {
        // The failure this pins: withTimeoutOrNull cancels its block and then awaits it, so a step
        // that does not check for cancellation is awaited in full — with the manager's lock held
        // and uncancellable. Real dispatchers and real time, because the point is wall-clock cost.
        val manager: SessionManager = manager(
            cleaners = listOf(NonCooperativeCleaner(name = "stuck", duration = NON_COOPERATIVE_WORK)),
            ioDispatcher = Dispatchers.Default,
            cleanerTimeout = SHORT_TIMEOUT,
        )
        manager.startSession()

        val elapsed: Duration = withContext(Dispatchers.Default) {
            measureTime { manager.endSession() }
        }

        assertTrue(
            elapsed < NON_COOPERATIVE_WORK / 2,
            "endSession waited $elapsed for a cleaner it should have abandoned after $SHORT_TIMEOUT",
        )
        // Lower bound as well: without it the test would also pass on a clock that is not running,
        // which is the failure mode of measuring wall time from inside runTest.
        assertTrue(
            elapsed >= SHORT_TIMEOUT,
            "endSession returned in $elapsed, faster than the $SHORT_TIMEOUT bound it was supposed to wait",
        )
        assertEquals(SessionState.INACTIVE, manager.state.value)
    }

    @Test
    fun `an abandoned cleaner does not wedge later calls`() = runTest {
        val healthy = FakeCleaner(name = "healthy")
        val manager: SessionManager = manager(
            cleaners = listOf(
                NonCooperativeCleaner(name = "stuck", duration = NON_COOPERATIVE_WORK),
                healthy,
            ),
            ioDispatcher = Dispatchers.Default,
            cleanerTimeout = SHORT_TIMEOUT,
        )

        val elapsed: Duration = withContext(Dispatchers.Default) {
            measureTime {
                manager.startSession()
                manager.endSession()
                // The lock must be free again the moment the timeout elapsed — not once the
                // abandoned cleaner finally finishes.
                manager.startSession()
                manager.endSession()
            }
        }

        assertTrue(
            elapsed < NON_COOPERATIVE_WORK,
            "two sign-outs took ${elapsed}; an abandoned cleaner is still being awaited",
        )
        assertEquals(2, healthy.cleanCalls)
        assertEquals(SessionState.INACTIVE, manager.state.value)
    }

    // --- Reentrancy ----------------------------------------------------------------------------

    @Test
    fun `a cleaner that calls endSession is refused instead of deadlocking`() = runTest {
        lateinit var manager: SessionManager
        val healthy = FakeCleaner(name = "healthy")
        manager = manager(
            cleaners = listOf(FakeCleaner(name = "reentrant", onClean = { manager.endSession() }), healthy),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, healthy.cleanCalls)
        assertEquals(SessionState.INACTIVE, manager.state.value)
        val failure: SessionCleanerFailure = report.cleanerFailures.single()
        assertEquals("reentrant", failure.name)
        assertEquals("endSession", assertIs<SessionReentrancyException>(failure.cause).operation)
    }

    @Test
    fun `a cleaner that calls startSession is refused instead of deadlocking`() = runTest {
        lateinit var manager: SessionManager
        manager = manager(
            cleaners = listOf(FakeCleaner(name = "reentrant", onClean = { manager.startSession() })),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(SessionState.INACTIVE, manager.state.value)
        assertEquals("startSession", assertIs<SessionReentrancyException>(report.cleanerFailures.single().cause).operation)
    }

    @Test
    fun `a revoker that calls endSession is refused instead of deadlocking`() = runTest {
        lateinit var manager: SessionManager
        val cleaner = FakeCleaner()
        manager = manager(cleaners = listOf(cleaner), revoker = { manager.endSession() })
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, cleaner.cleanCalls)
        assertEquals("endSession", assertIs<SessionReentrancyException>(report.revokeFailure).operation)
    }

    @Test
    fun `a cleaner may end a different manager's session`() = runTest {
        // The marker carries manager identity, not a bare flag: two managers in one process are
        // legal and only a call back into the *same* one is reentrancy.
        val otherCleaner = FakeCleaner(name = "other")
        val other: SessionManager = manager(cleaners = listOf(otherCleaner))
        other.startSession()
        val manager: SessionManager = manager(
            cleaners = listOf(FakeCleaner(name = "cascading", onClean = { other.endSession() })),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertTrue(report.isClean)
        assertEquals(1, otherCleaner.cleanCalls)
        assertEquals(SessionState.INACTIVE, other.state.value)
    }

    // --- Revoker -------------------------------------------------------------------------------

    @Test
    fun `the revoker runs before the cleaners`() = runTest {
        // The revoker brackets a delay so the assertion distinguishes "ran first" from "ran
        // concurrently and happened to record first": a concurrent cleaner would slot between the
        // two revoke marks.
        val recorder = StepRecorder()
        val manager: SessionManager = manager(
            cleaners = listOf(FakeCleaner(name = "cleaner", onClean = { recorder.record("clean") })),
            revoker = {
                recorder.record("revoke-start")
                delay(1.seconds)
                recorder.record("revoke-end")
            },
        )
        manager.startSession()

        manager.endSession()

        assertEquals(listOf("revoke-start", "revoke-end", "clean"), recorder.steps())
    }

    @Test
    fun `no revoker means no revoke failure`() = runTest {
        val cleaner = FakeCleaner()
        val manager: SessionManager = manager(cleaners = listOf(cleaner))
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertNull(report.revokeFailure)
        assertTrue(report.isClean)
        assertEquals(1, cleaner.cleanCalls)
    }

    @Test
    fun `a revoker that throws an Error is recorded and the cleaners still run`() = runTest {
        val cleaner = FakeCleaner()
        val manager: SessionManager = manager(
            cleaners = listOf(cleaner),
            revoker = { throw Error("revoker exploded") },
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, cleaner.cleanCalls)
        assertEquals(SessionState.INACTIVE, manager.state.value)
        assertEquals("revoker exploded", assertIs<Error>(report.revokeFailure).message)
        assertEquals(emptyList(), report.cleanerFailures)
    }

    @Test
    fun `a revoker that fails offline still lets every cleaner run and the session end`() = runTest {
        val cleaner = FakeCleaner()
        val manager: SessionManager = manager(
            cleaners = listOf(cleaner),
            revoker = { throw IllegalStateException("no network") },
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, cleaner.cleanCalls)
        assertEquals(SessionState.INACTIVE, manager.state.value)
        assertEquals("no network", assertIs<IllegalStateException>(report.revokeFailure).message)
        assertEquals(emptyList(), report.cleanerFailures)
    }

    @Test
    fun `a revoker that hangs is abandoned at the revoke timeout`() = runTest {
        val cleaner = FakeCleaner()
        val manager: SessionManager = manager(cleaners = listOf(cleaner), revoker = { delay(Long.MAX_VALUE) })
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, cleaner.cleanCalls)
        assertEquals(SessionState.INACTIVE, manager.state.value)
        val timeout = assertIs<SessionTeardownTimeoutException>(report.revokeFailure)
        assertEquals("revoker", timeout.name)
        assertEquals(10.seconds, timeout.timeout)
    }

    // --- Idempotence ---------------------------------------------------------------------------

    @Test
    fun `endSession without a session open runs nothing`() = runTest {
        val cleaner = FakeCleaner()
        val manager: SessionManager = manager(cleaners = listOf(cleaner), revoker = { error("must not run") })

        val report: SessionEndReport = manager.endSession()

        assertEquals(0, cleaner.cleanCalls)
        assertEquals(SessionEndReport.Empty, report)
    }

    @Test
    fun `a second sequential endSession tears nothing down again`() = runTest {
        val cleaner = FakeCleaner(name = "broken", onClean = { throw IllegalStateException("boom") })
        val manager: SessionManager = manager(cleaners = listOf(cleaner))
        manager.startSession()

        val first: SessionEndReport = manager.endSession()
        val second: SessionEndReport = manager.endSession()

        assertEquals(1, cleaner.cleanCalls)
        assertEquals(listOf("broken"), first.cleanerFailures.map { it.name })
        assertEquals(SessionEndReport.Empty, second)
    }

    @Test
    fun `a later endSession on a closed session does not replay the previous teardown's failures`() = runTest {
        val manager: SessionManager = manager(
            cleaners = listOf(FakeCleaner(name = "broken", onClean = { throw IllegalStateException("boom") })),
        )
        manager.startSession()
        manager.endSession()

        // This caller arrived after the session was already closed. Handing it the earlier
        // teardown's failures would report failures that did not happen on its call.
        val report: SessionEndReport = manager.endSession()

        assertEquals(SessionEndReport.Empty, report)
        assertTrue(report.isClean)
    }

    @Test
    fun `a caller that loses the race to an in-flight teardown receives that teardown's report`() = runTest {
        val gate = Gate(name = "broken", thenThrow = { throw IllegalStateException("boom") })
        val manager: SessionManager = manager(cleaners = listOf(gate.cleaner()))
        manager.startSession()

        val winner: Deferred<SessionEndReport> = async { manager.endSession() }
        gate.awaitTeardownInFlight()
        // Queued behind a teardown that is genuinely in flight — not a later call on a closed
        // session, which is the case that gets Empty.
        val loser: Deferred<SessionEndReport> = async { manager.endSession() }
        runCurrent()
        gate.release()

        val winnerReport: SessionEndReport = winner.await()
        val loserReport: SessionEndReport = loser.await()

        assertEquals(1, gate.cleanCalls)
        assertEquals(listOf("broken"), winnerReport.cleanerFailures.map { it.name })
        assertEquals(winnerReport, loserReport)
    }

    @Test
    fun `two endSession calls launched together still tear down once`() = runTest {
        val cleaner = FakeCleaner(name = "broken", onClean = { throw IllegalStateException("boom") })
        val manager: SessionManager = manager(cleaners = listOf(cleaner))
        manager.startSession()

        val reports: List<SessionEndReport> = listOf(
            async { manager.endSession() },
            async { manager.endSession() },
        ).awaitAll()

        // Launched together, but `runTest`'s StandardTestDispatcher runs them one after another, so
        // the second arrives at a session that is already closed and is owed Empty — not the first
        // one's failures. Genuine overlap is covered by the gated test above and by the
        // real-parallelism test below.
        assertEquals(1, cleaner.cleanCalls)
        assertEquals(listOf("broken"), reports[0].cleanerFailures.map { it.name })
        assertEquals(SessionEndReport.Empty, reports[1])
    }

    @Test
    fun `many genuinely parallel endSession calls tear down once`() = runTest {
        // The cleaner fails on purpose: with a clean cleaner every report equals Empty, so the test
        // could not tell a shared report from a freshly built empty one.
        val cleaner = ThreadSafeCountingCleaner(
            name = "broken",
            thenThrow = { throw IllegalStateException("boom") },
        )
        val manager: SessionManager = manager(
            cleaners = listOf(cleaner),
            ioDispatcher = Dispatchers.Default,
        )
        manager.startSession()

        // Real threads, not a test dispatcher's interleaving: the guard has to hold under actual
        // parallel entry into endSession.
        val reports: List<SessionEndReport> = withContext(Dispatchers.Default) {
            coroutineScope {
                val racers: List<Deferred<SessionEndReport>> = List(PARALLEL_CALLERS) {
                    async { manager.endSession() }
                }
                racers.awaitAll()
            }
        }

        assertEquals(1, cleaner.cleanCalls())
        assertEquals(SessionState.INACTIVE, manager.state.value)

        // Which callers raced the teardown and which arrived after it is genuinely nondeterministic
        // with real threads, and the two are owed different answers. What must hold for every
        // caller: it got either the one real report — identical for all of them, and not clean —
        // or Empty, and never some third thing.
        val distinct: List<SessionEndReport> = reports.distinct()
        assertTrue(distinct.size <= 2, "callers saw $distinct")
        val real: List<SessionEndReport> = distinct.filterNot { it == SessionEndReport.Empty }
        assertEquals(1, real.size, "expected exactly one real report, saw $real")
        assertEquals(listOf("broken"), real.single().cleanerFailures.map { it.name })
        assertTrue(reports.contains(real.single()))
    }

    @Test
    fun `parallel startSession and endSession calls neither wedge nor corrupt the manager`() = runTest {
        val cleaner = ThreadSafeCountingCleaner()
        val manager: SessionManager = manager(
            cleaners = listOf(cleaner),
            ioDispatcher = Dispatchers.Default,
        )
        manager.startSession()

        withContext(Dispatchers.Default) {
            coroutineScope {
                List(PARALLEL_CALLERS) { index: Int ->
                    async { if (index % 2 == 0) manager.startSession() else manager.endSession() }
                }.awaitAll()
            }
        }

        // The interleaving decides the final state, so the assertion is about liveness, not order:
        // the manager must still work afterwards.
        manager.startSession()
        assertEquals(SessionState.ACTIVE, manager.state.value)
        manager.endSession()
        assertEquals(SessionState.INACTIVE, manager.state.value)
        assertTrue(cleaner.cleanCalls() >= 1)
    }

    @Test
    fun `starting a new session re-arms teardown`() = runTest {
        val cleaner = FakeCleaner()
        val manager: SessionManager = manager(cleaners = listOf(cleaner))

        manager.startSession()
        manager.endSession()
        manager.startSession()
        val second: SessionEndReport = manager.endSession()

        assertEquals(2, cleaner.cleanCalls)
        assertTrue(second.isClean)
    }

    @Test
    fun `startSession during an in-flight teardown waits for it and leaves the new session open`() = runTest {
        val gate = Gate()
        val manager: SessionManager = manager(cleaners = listOf(gate.cleaner()))
        manager.startSession()

        val ending: Job = launch { manager.endSession() }
        gate.awaitTeardownInFlight()
        val restarting: Job = launch { manager.startSession() }
        runCurrent() // let the sign-in reach the lock the teardown is holding
        gate.release()
        ending.join()
        restarting.join()

        // The teardown's own flip to INACTIVE must not survive the sign-in that queued behind it.
        assertEquals(SessionState.ACTIVE, manager.state.value)
        assertEquals(1, gate.cleanCalls)
    }

    // --- Cancellation --------------------------------------------------------------------------

    @Test
    fun `cancelling the caller does not abandon a teardown that already started`() = runTest {
        val gate = Gate(name = "slow")
        val fast = FakeCleaner(name = "fast")
        val manager: SessionManager = manager(cleaners = listOf(gate.cleaner(), fast))
        manager.startSession()

        val ending: Job = launch { manager.endSession() }
        gate.awaitTeardownInFlight()
        ending.cancel()
        gate.release()
        ending.join()

        // Not just entered — run to completion. A cleaner cancelled halfway is exactly the
        // half-torn-down state NonCancellable exists to prevent.
        assertTrue(gate.finished)
        assertEquals(1, gate.cleanCalls)
        assertEquals(1, fast.cleanCalls)
        assertEquals(SessionState.INACTIVE, manager.state.value)
    }

    @Test
    fun `a cancelled caller still receives the report of the teardown it started`() = runTest {
        val gate = Gate(name = "broken", thenThrow = { throw IllegalStateException("boom") })
        val manager: SessionManager = manager(cleaners = listOf(gate.cleaner()))
        manager.startSession()

        var observed: SessionEndReport? = null
        val ending: Job = launch { observed = manager.endSession() }
        gate.awaitTeardownInFlight()
        ending.cancel()
        gate.release()
        ending.join()

        // The teardown runs under NonCancellable, so the call returns its value rather than
        // throwing at the cancelled caller — worth pinning, because the opposite is the intuitive
        // guess and the docs have to state one of the two.
        val report: SessionEndReport = assertNotNull(observed)
        assertEquals(listOf("broken"), report.cleanerFailures.map { it.name })
    }

    @Test
    fun `a teardown run by a cancelled caller closes the session for everyone`() = runTest {
        val gate = Gate(name = "broken", thenThrow = { throw IllegalStateException("boom") })
        val manager: SessionManager = manager(cleaners = listOf(gate.cleaner()))
        manager.startSession()

        val ending: Job = launch { manager.endSession() }
        gate.awaitTeardownInFlight()
        ending.cancel()
        gate.release()
        ending.join()

        assertEquals(1, gate.cleanCalls)
        assertEquals(SessionState.INACTIVE, manager.state.value)
        // A later caller is not the cancelled one's proxy: it arrived at a closed session and gets
        // Empty, cleaner failures and all.
        assertEquals(SessionEndReport.Empty, manager.endSession())
    }

    private companion object {
        const val PARALLEL_CALLERS = 64

        /** The reviewer's exact shape: 50 ms of patience for 600 ms of uncancellable work. */
        val SHORT_TIMEOUT: Duration = 50.milliseconds
        val NON_COOPERATIVE_WORK: Duration = 600.milliseconds
    }
}
