package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a failing open or a failing close leaves behind: the driver and the database thread are released
 * whichever way the work ends.
 */
class ResourceReleaseTest {

    /** A driver whose probe fails and which counts how often it is closed. */
    private class ProbeFailingDriver(
        private val probeFailure: Throwable,
        private val closeFailure: Throwable? = null,
    ) : SqlDriver by FailingSqlDriver(probeFailure) {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount++
            closeFailure?.let { throw it }
        }
    }

    @Test
    fun `a driver whose probe fails is closed exactly once and the failure is rethrown`() {
        val failure = FakeSqliteException()
        val driver = ProbeFailingDriver(failure)

        val thrown: FakeSqliteException = assertFailsWith { openOwned(driver) }

        assertSame(failure, thrown)
        assertEquals(1, driver.closeCount, "a driver the factory built and could not open must not be left open")
    }

    @Test
    fun `a failing close of that driver is recorded as suppressed and does not replace the probe failure`() {
        val failure = FakeSqliteException()
        val closeFailure = IllegalStateException("close failed")
        val driver = ProbeFailingDriver(failure, closeFailure)

        val thrown: FakeSqliteException = assertFailsWith { openOwned(driver) }

        assertSame(failure, thrown)
        assertEquals(listOf<Throwable>(closeFailure), thrown.suppressedExceptions)
        assertEquals(1, driver.closeCount)
    }

    @Test
    fun `a finalizer that throws still releases the database thread and the failure reaches the caller`() {
        val counting: CountingDispatcher = countingDispatcher()
        val confinement = DatabaseConfinement(counting.dispatcher)

        val thrown: IllegalStateException = assertFailsWith { confinement.closeAfterDraining { error("boom") } }

        assertEquals("boom", thrown.message)
        assertEquals(1, counting.closeCount, "the thread must be released even when the driver close fails")
    }

    @Test
    fun `a finalizer that succeeds releases the database thread once`() {
        val counting: CountingDispatcher = countingDispatcher()
        var ran = false

        DatabaseConfinement(counting.dispatcher).closeAfterDraining { ran = true }

        assertTrue(ran)
        assertEquals(1, counting.closeCount)
    }
}
