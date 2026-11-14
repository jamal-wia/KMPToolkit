package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** [BossRound4SqliteSuspectChecks] on this platform's SQLite driver. */
class BossRound4SqliteSuspectIosAdversarialTest {

    private val checks = BossRound4SqliteSuspectChecks()

    @Test
    fun `bv4-S1 sqlite a KEEP that supersedes a parked item cancels its transfer`() = runTest(timeout = 2.minutes) {
        checks.keepThatSupersedesAParkedItemCancelsItsTransfer()
    }
}
