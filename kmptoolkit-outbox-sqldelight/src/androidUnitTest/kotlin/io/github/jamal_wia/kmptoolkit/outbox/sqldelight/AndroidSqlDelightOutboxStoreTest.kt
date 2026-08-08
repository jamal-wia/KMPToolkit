package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * [SqlDelightOutboxStoreChecks] against `AndroidSqliteDriver` on a real SQLite, under Robolectric.
 *
 * The assertions live in the shared checks class; this file exists to give them a `Context` and a
 * runner. `iosTest`'s sibling declares the same methods against the native driver, so a store that
 * passes here and fails there — a threading difference, a driver's transaction bookkeeping — is
 * caught rather than assumed away.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSqlDelightOutboxStoreTest {

    private val checks = SqlDelightOutboxStoreChecks()

    @Test
    fun `it satisfies the OutboxStore contract`() = runTest {
        checks.satisfiesTheOutboxStoreContract()
    }

    @Test
    fun `concurrent recordFailures let exactly one through`() = runTest {
        checks.concurrentRecordFailuresLetExactlyOneThrough()
    }

    @Test
    fun `a stale settle never clobbers a fresh claim`() = runTest {
        checks.aStaleSettleNeverClobbersAFreshClaim()
    }

    @Test
    fun `insertion order survives deletion of the newest row`() = runTest {
        checks.insertionOrderSurvivesDeletionOfTheNewestRow()
    }

    @Test
    fun `insertion order survives clearAll`() = runTest {
        checks.insertionOrderSurvivesClearAll()
    }

    @Test
    fun `insertion order holds under a large burst`() = runTest {
        checks.insertionOrderHoldsUnderALargeBurst()
    }

    @Test
    fun `a committed transaction persists everything in it`() = runTest {
        checks.aCommittedTransactionPersistsEverythingInIt()
    }

    @Test
    fun `a failed transaction rolls back every write in it`() = runTest {
        checks.aFailedTransactionRollsBackEveryWriteInIt()
    }

    @Test
    fun `a nested transaction joins the outer one`() = runTest {
        checks.aNestedTransactionJoinsTheOuterOne()
    }

    @Test
    fun `a nested transaction commits with the outer one`() = runTest {
        checks.aNestedTransactionCommitsWithTheOuterOne()
    }

    @Test
    fun `cancelling inside a transaction rolls it back`() = runTest {
        checks.cancellingInsideATransactionRollsItBack()
    }

    @Test
    fun `store calls inside a transaction see their own writes`() = runTest {
        checks.storeCallsInsideATransactionSeeTheirOwnWrites()
    }

    @Test
    fun `a queue survives close and reopen`() = runTest {
        checks.aQueueSurvivesCloseAndReopen()
    }

    @Test
    fun `the insertion sequence continues across a reopen`() = runTest {
        checks.theInsertionSequenceContinuesAcrossAReopen()
    }

    @Test
    fun `opening a fresh file creates the schema`() = runTest {
        checks.openingAFreshFileCreatesTheSchema()
    }

    @Test
    fun `opening an existing file does not recreate the schema`() = runTest {
        checks.openingAnExistingFileDoesNotRecreateTheSchema()
    }

    @Test
    fun `observeByType re-emits to a live collector`() = runTest {
        checks.observeByTypeReEmitsToALiveCollector()
    }

    @Test
    fun `an unrecognized state reads back as parked`() = runTest {
        checks.anUnrecognizedStateReadsBackAsParked()
    }

    @Test
    fun `closing a storage does not close a supplied driver`() = runTest {
        checks.closingAStorageDoesNotCloseASuppliedDriver()
    }

    @Test
    fun `the queue can be created inside another database`() = runTest {
        checks.theQueueCanBeCreatedInsideAnotherDatabase()
    }

    @Test
    fun `a database failure arrives as an OutboxStorageException`() = runTest {
        checks.aDatabaseFailureArrivesAsAnOutboxStorageException()
    }

    @Test
    fun `a cancellation is not wrapped as a database failure`() = runTest {
        checks.aCancellationIsNotWrappedAsADatabaseFailure()
    }

    @Test
    fun `an empty unique key is a real identity`() = runTest {
        checks.anEmptyUniqueKeyIsARealIdentity()
    }

    @Test
    fun `closing waits for statements already running`() = runTest {
        checks.closingWaitsForStatementsAlreadyRunning()
    }

    @Test
    fun `observeByType can be collected inside a transaction`() = runTest {
        checks.observeByTypeCanBeCollectedInsideATransaction()
    }

    @Test
    fun `leaving the database thread inside a transaction fails loudly`() = runTest {
        checks.leavingTheDatabaseThreadInsideATransactionFailsLoudly()
    }

    @Test
    fun `closing twice is a no-op`() {
        checks.closingTwiceIsANoOp()
    }
}
