package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** [SqlDelightDocsParityRound9Checks] against `NativeSqliteDriver` on the iOS simulator. */
class SqlDelightDocsParityRound9IosAdversarialTest {

    private val checks = SqlDelightDocsParityRound9Checks()

    @Test
    fun `the schema is exactly the one printed in the guide`() = runTest {
        checks.theSchemaIsExactlyTheOnePrintedInTheGuide()
    }

    @Test
    fun `a domain write and an enqueue commit or roll back together on the consumers driver`() = runTest {
        checks.aDomainWriteAndAnEnqueueCommitOrRollBackTogetherOnTheConsumersDriver()
    }

    @Test
    fun `the AfterVersion sample creates the queue once in an existing database`() = runTest {
        checks.theAfterVersionSampleCreatesTheQueueOnceInAnExistingDatabase()
    }

    @Test
    fun `a driver without the queue table fails on the first operation and never in the factory`() = runTest {
        checks.aDriverWithoutTheQueueTableFailsOnTheFirstOperationAndNeverInTheFactory()
    }

    @Test
    fun `observing from inside a transaction sees the uncommitted state and nothing else does`() = runTest {
        checks.observingFromInsideATransactionSeesTheUncommittedStateAndNothingElseDoes()
    }

    @Test
    fun `every function of a closed storage fails as a storage exception with an illegal state cause`() = runTest {
        checks.everyFunctionOfAClosedStorageFailsAsAStorageExceptionWithAnIllegalStateCause()
    }

    @Test
    fun `the row limit is the same on this platform and a large NUL text stays readable`() = runTest {
        checks.theRowLimitIsTheSameOnThisPlatformAndALargeNulTextStaysReadable()
    }

    @Test
    fun `many threads inserting one key admit exactly one and distinct rows are all kept`() = runTest {
        checks.manyThreadsInsertingOneKeyAdmitExactlyOneAndDistinctRowsAreAllKept()
    }
}
