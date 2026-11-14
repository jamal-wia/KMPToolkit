package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** [Split1KeepRollbackChecks] on this platform's SQLite driver. */
class Split1KeepRollbackIosAdversarialTest {

    private val checks = Split1KeepRollbackChecks()

    @Test
    fun `a rolled back KEEP over a parked item keeps the cancel and restores the row`() = runTest(timeout = 2.minutes) {
        checks.rolledBackKeepOverAParkedItemKeepsTheCancelAndRestoresTheRow()
    }
}
