package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** [BossRound2SqliteSuspectChecks] on this platform's SQLite driver. */
class BossRound2SqliteSuspectIosAdversarialTest {

    private val checks = BossRound2SqliteSuspectChecks()

    @Test
    fun `boss2-S1 a rolled back cancel does not run the delivered effect again`() = runTest(timeout = 2.minutes) {
        checks.rolledBackCancelDoesNotRunTheDeliveredEffectAgain()
    }
}
