package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/** [EngineOverSqliteParityRound9Checks] against `AndroidSqliteDriver` under Robolectric. */
@RunWith(AndroidJUnit4::class)
class EngineOverSqliteParityRound9AndroidAdversarialTest {

    private val checks = EngineOverSqliteParityRound9Checks()

    @Test
    fun `a scripted life of an item through retry restart detach and settle`() = runTest(timeout = 3.minutes) {
        checks.scriptedLifeOfAnItemThroughRetryRestartDetachAndSettle()
    }

    @Test
    fun `seeded workloads never lose duplicate or resurrect an item`() = runTest(timeout = 3.minutes) {
        checks.seededWorkloadsNeverLoseDuplicateOrResurrectAnItem()
    }
}
