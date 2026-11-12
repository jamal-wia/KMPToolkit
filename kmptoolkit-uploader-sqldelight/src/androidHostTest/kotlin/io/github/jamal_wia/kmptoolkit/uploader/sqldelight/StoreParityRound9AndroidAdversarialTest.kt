package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/** [StoreParityRound9Checks] against `AndroidSqliteDriver` under Robolectric. */
@RunWith(AndroidJUnit4::class)
class StoreParityRound9AndroidAdversarialTest {

    private val checks = StoreParityRound9Checks()

    @Test
    fun `writes addressed to an absent row are no-ops and every write is idempotent`() = runTest {
        checks.writesAddressedToAnAbsentRowAreNoOpsAndEveryWriteIsIdempotent()
    }

    @Test
    fun `extreme numbers are stored and read back exactly and leases are not rounded`() = runTest {
        checks.extremeNumbersAreStoredAndReadBackExactlyAndLeasesAreNotRounded()
    }

    @Test
    fun `superseding and reviving move a row to the tail and return what was there`() = runTest {
        checks.supersedingAndRevivingMoveARowToTheTailAndReturnWhatWasThere()
    }

    @Test
    fun `a random script ends equal to the in-memory store on this platform`() = runTest {
        checks.aRandomScriptEndsEqualToTheInMemoryStoreOnThisPlatform()
    }
}
