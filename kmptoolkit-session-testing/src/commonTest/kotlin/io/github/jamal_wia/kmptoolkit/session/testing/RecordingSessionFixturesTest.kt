package io.github.jamal_wia.kmptoolkit.session.testing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
