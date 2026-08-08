package io.github.jamal_wia.kmptoolkit.session.testing

import io.github.jamal_wia.kmptoolkit.coroutines.testing.TestAppDispatchers
import io.github.jamal_wia.kmptoolkit.session.SessionEndReport
import io.github.jamal_wia.kmptoolkit.session.SessionManager
import io.github.jamal_wia.kmptoolkit.session.SessionState
import io.github.jamal_wia.kmptoolkit.session.SessionTeardownTimeoutException
import io.github.jamal_wia.kmptoolkit.session.createSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RecordingSessionFixturesTest {

    @Test
    fun `a fresh cleaner has not been called`() {
        assertEquals(0, RecordingSessionCleaner().cleanCalls)
    }

    @Test
    fun `the cleaner counts every call`() = runTest {
        val cleaner = RecordingSessionCleaner()

        cleaner.clean()
        cleaner.clean()

        assertEquals(2, cleaner.cleanCalls)
    }

    @Test
    fun `the cleaner reports the name it was given`() {
        assertEquals("cache", RecordingSessionCleaner(name = "cache").name)
    }

    @Test
    fun `the cleaner runs the behaviour the test supplied`() = runTest {
        var ran = false
        val cleaner = RecordingSessionCleaner(onClean = { ran = true })

        cleaner.clean()

        assertEquals(true, ran)
    }

    @Test
    fun `a call that throws is still counted`() = runTest {
        val cleaner = RecordingSessionCleaner(onClean = { throw IllegalStateException("boom") })

        assertFailsWith<IllegalStateException> { cleaner.clean() }

        assertEquals(1, cleaner.cleanCalls)
    }

    @Test
    fun `the cleaner behaviour can be swapped between calls`() = runTest {
        val cleaner = RecordingSessionCleaner()

        cleaner.clean()
        cleaner.onClean = { throw IllegalStateException("boom") }
        assertFailsWith<IllegalStateException> { cleaner.clean() }

        assertEquals(2, cleaner.cleanCalls)
    }

    @Test
    fun `a fresh revoker has not been called`() {
        assertEquals(0, RecordingSessionRevoker().revokeCalls)
    }

    @Test
    fun `the revoker counts every call and runs the supplied behaviour`() = runTest {
        var ran = 0
        val revoker = RecordingSessionRevoker(onRevoke = { ran++ })

        revoker.revoke()
        revoker.revoke()

        assertEquals(2, revoker.revokeCalls)
        assertEquals(2, ran)
    }

    @Test
    fun `a revoke that throws is still counted`() = runTest {
        val revoker = RecordingSessionRevoker(onRevoke = { throw IllegalStateException("offline") })

        assertFailsWith<IllegalStateException> { revoker.revoke() }

        assertEquals(1, revoker.revokeCalls)
    }

    // --- Driven through a real SessionManager ---------------------------------------------------
    //
    // The tests above exercise the fixtures directly, which is not how a consumer uses them. These
    // run them through createSessionManager, so the examples in docs/kmptoolkit-session/06-testing.md
    // are verified rather than merely plausible.

    @Test
    fun `a manager runs every registered fixture cleaner once`() = runTest {
        val cleaners: List<RecordingSessionCleaner> = listOf(
            RecordingSessionCleaner(name = "chat"),
            RecordingSessionCleaner(name = "profile"),
            RecordingSessionCleaner(name = "downloads"),
        )
        val manager: SessionManager = createSessionManager(cleaners, dispatchers = TestAppDispatchers(testScheduler))
        manager.startSession()

        manager.endSession()

        assertEquals(listOf(1, 1, 1), cleaners.map { it.cleanCalls })
    }

    @Test
    fun `a failing fixture cleaner is reported under its name and spares the others`() = runTest {
        val healthy = RecordingSessionCleaner(name = "cache")
        val broken = RecordingSessionCleaner(
            name = "db",
            onClean = { throw IllegalStateException("disk full") },
        )
        val manager: SessionManager = createSessionManager(
            cleaners = listOf(broken, healthy),
            dispatchers = TestAppDispatchers(testScheduler),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, healthy.cleanCalls)
        assertEquals(listOf("db"), report.cleanerFailures.map { it.name })
    }

    @Test
    fun `a hanging fixture cleaner is reported as a timeout`() = runTest {
        val stuck = RecordingSessionCleaner(name = "db", onClean = { delay(Long.MAX_VALUE) })
        val manager: SessionManager = createSessionManager(
            cleaners = listOf(stuck),
            dispatchers = TestAppDispatchers(testScheduler),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, stuck.cleanCalls)
        assertIs<SessionTeardownTimeoutException>(report.cleanerFailures.single().cause)
    }

    @Test
    fun `a fixture revoker that fails offline still signs the user out`() = runTest {
        val cleaner = RecordingSessionCleaner(name = "cache")
        val revoker = RecordingSessionRevoker(onRevoke = { throw IllegalStateException("offline") })
        val manager: SessionManager = createSessionManager(
            cleaners = listOf(cleaner),
            revoker = revoker,
            dispatchers = TestAppDispatchers(testScheduler),
        )
        manager.startSession()

        val report: SessionEndReport = manager.endSession()

        assertEquals(1, revoker.revokeCalls)
        assertEquals(1, cleaner.cleanCalls)
        assertNotNull(report.revokeFailure)
        assertEquals(SessionState.INACTIVE, manager.state.value)
    }
}
