package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** [EngineOverSqliteParityRound9Checks] against `NativeSqliteDriver` on the iOS simulator. */
class EngineOverSqliteParityRound9IosAdversarialTest {

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
