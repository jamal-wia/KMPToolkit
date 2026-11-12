package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** [BossRound4SqliteChecks] on this platform's SQLite driver. */
class BossRound4SqliteIosAdversarialTest {

    private val checks = BossRound4SqliteChecks()

    @Test
    fun `a rolled back wipe keeps the cancel and the restored parked row is settled by its delivery`() = runTest(timeout = 2.minutes) {
        checks.rolledBackWipeKeepsTheCancelAndTheRestoredParkedRowIsSettledByItsDelivery()
    }
}
