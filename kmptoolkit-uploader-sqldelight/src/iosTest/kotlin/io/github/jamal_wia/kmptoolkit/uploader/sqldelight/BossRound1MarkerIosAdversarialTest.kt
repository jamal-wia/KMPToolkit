package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** [BossRound1MarkerChecks] on this platform's SQLite driver. */
class BossRound1MarkerIosAdversarialTest {

    @Test
    fun `marker texts of an earlier version are reached by tag key and type`() = runTest(timeout = 2.minutes) {
        BossRound1MarkerChecks().markerTextsOfAnEarlierVersionAreReachedByTagKeyAndType()
    }
}
