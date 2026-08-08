package io.github.jamal_wia.kmptoolkit.session

import io.github.jamal_wia.kmptoolkit.coroutines.AppDispatchers
import io.github.jamal_wia.kmptoolkit.coroutines.testing.TestAppDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SessionManagerTest {

    private fun TestScope.manager(
        cleaners: List<SessionCleaner> = emptyList(),
        revoker: SessionRevoker? = null,
        dispatchers: AppDispatchers = TestAppDispatchers(testScheduler),
    ): SessionManager = createSessionManager(
        cleaners = cleaners,
        revoker = revoker,
        dispatchers = dispatchers,
        cleanerTimeout = 5.seconds,
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
        val manager: SessionManager = manager()
        manager.startSession()

        manager.endSession()

        assertEquals(SessionState.INACTIVE, manager.state.value)
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
        val manager: SessionManager = manager(cleaners = listOf(FakeCleaner()))
        manager.startSession()

        assertNull(manager.endSession().revokeFailure)
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
        val cleaner = FakeCleaner()
        val manager: SessionManager = manager(cleaners = listOf(cleaner))
        manager.startSession()

        val first: SessionEndReport = manager.endSession()
        val second: SessionEndReport = manager.endSession()

        assertEquals(1, cleaner.cleanCalls)
        assertEquals(first, second)
    }

    @Test
    fun `a later endSession reports the failures of the teardown that actually ran`() = runTest {
        val manager: SessionManager = manager(
            cleaners = listOf(FakeCleaner(name = "broken", onClean = { throw IllegalStateException("boom") })),
        )
        manager.startSession()
        manager.endSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(listOf("broken"), report.cleanerFailures.map { it.name })
    }

    @Test
    fun `two concurrent endSession calls tear down once and both receive the same report`() = runTest {
        val cleaner = FakeCleaner(name = "broken", onClean = { throw IllegalStateException("boom") })
        val manager: SessionManager = manager(cleaners = listOf(cleaner))
        manager.startSession()

        val reports: List<SessionEndReport> = listOf(
            async { manager.endSession() },
            async { manager.endSession() },
        ).awaitAll()

        assertEquals(1, cleaner.cleanCalls)
        assertEquals(reports[0], reports[1])
        assertEquals(listOf("broken"), reports[0].cleanerFailures.map { it.name })
    }

    @Test
    fun `many genuinely parallel endSession calls tear down once`() = runTest {
        val cleaner = ThreadSafeCountingCleaner()
        val manager: SessionManager = manager(
            cleaners = listOf(cleaner),
            dispatchers = ParallelAppDispatchers(),
        )
        manager.startSession()

        // Real threads, not a test dispatcher's interleaving: the guard has to hold under actual
        // parallel entry into endSession.
        withContext(Dispatchers.Default) {
            coroutineScope {
                val racers: List<Deferred<SessionEndReport>> = List(PARALLEL_CALLERS) {
                    async { manager.endSession() }
                }
                racers.awaitAll()
            }
        }

        assertEquals(1, cleaner.cleanCalls())
        assertEquals(SessionState.INACTIVE, manager.state.value)
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
    fun `a teardown run by a cancelled caller is still reported to the next caller`() = runTest {
        val gate = Gate(name = "broken", thenThrow = { throw IllegalStateException("boom") })
        val manager: SessionManager = manager(cleaners = listOf(gate.cleaner()))
        manager.startSession()

        val ending: Job = launch { manager.endSession() }
        gate.awaitTeardownInFlight()
        ending.cancel()
        gate.release()
        ending.join()
        val report: SessionEndReport = manager.endSession()

        assertEquals(1, gate.cleanCalls)
        assertEquals(listOf("broken"), report.cleanerFailures.map { it.name })
    }

    private companion object {
        const val PARALLEL_CALLERS = 64
    }
}
