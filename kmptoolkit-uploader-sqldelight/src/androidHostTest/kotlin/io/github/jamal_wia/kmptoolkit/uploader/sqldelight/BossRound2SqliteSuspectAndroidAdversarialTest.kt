package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** [BossRound2SqliteSuspectChecks] on this platform's SQLite driver. */
@RunWith(AndroidJUnit4::class)
class BossRound2SqliteSuspectAndroidAdversarialTest {

    private val checks = BossRound2SqliteSuspectChecks()

    @Test
    fun `boss2-S1 a rolled back cancel does not run the delivered effect again`() = runTest(timeout = 2.minutes) {
        checks.rolledBackCancelDoesNotRunTheDeliveredEffectAgain()
    }
}
