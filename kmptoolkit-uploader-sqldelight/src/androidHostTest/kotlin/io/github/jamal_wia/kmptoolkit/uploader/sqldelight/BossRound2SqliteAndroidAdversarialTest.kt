package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** [BossRound2SqliteChecks] on this platform's SQLite driver. */
@RunWith(AndroidJUnit4::class)
class BossRound2SqliteAndroidAdversarialTest {

    private val checks = BossRound2SqliteChecks()

    @Test
    fun `a delivered effect is not run again after the engine and the storage are rebuilt`() = runTest(timeout = 2.minutes) {
        checks.deliveredEffectIsNotRunAgainAfterTheEngineAndTheStorageAreRebuilt()
    }

    @Test
    fun `a REPLACE of a delivered row runs only the new effect`() = runTest(timeout = 2.minutes) {
        checks.replaceOfADeliveredRowRunsOnlyTheNewEffect()
    }
}
