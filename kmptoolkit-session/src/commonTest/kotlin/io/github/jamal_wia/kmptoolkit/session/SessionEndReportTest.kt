package io.github.jamal_wia.kmptoolkit.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SessionEndReportTest {

    @Test
    fun `a report with nothing in it is clean`() {
        assertTrue(SessionEndReport.Empty.isClean)
    }

    @Test
    fun `a report with a cleaner failure is not clean`() {
        val report = SessionEndReport(
            cleanerFailures = listOf(SessionCleanerFailure("db", IllegalStateException("boom"))),
        )

        assertFalse(report.isClean)
    }

    @Test
    fun `a report with only a revoke failure is not clean`() {
        val report = SessionEndReport(revokeFailure = IllegalStateException("offline"))

        assertFalse(report.isClean)
    }

    @Test
    fun `a timeout names the step and the bound it overran`() {
        val timeout = SessionTeardownTimeoutException(name = "db", timeout = 5.seconds)

        assertEquals("db", timeout.name)
        assertEquals(5.seconds, timeout.timeout)
        assertEquals("Session teardown step 'db' did not finish within 5s", timeout.message)
    }
}
