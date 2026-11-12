package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** [BossRound3SqliteChecks] on this platform's SQLite driver. */
@RunWith(AndroidJUnit4::class)
class BossRound3SqliteAndroidAdversarialTest {

    private val checks = BossRound3SqliteChecks()

    @Test
    fun `a rolled back KEEP restores the parked row and a committed one revives it`() = runTest(timeout = 2.minutes) {
        checks.rolledBackKeepRestoresTheParkedRowAndACommittedOneRevivesIt()
    }

    @Test
    fun `a rolled back REPLACE restores the claim and the upload resumes at the lease`() = runTest(timeout = 2.minutes) {
        checks.rolledBackReplaceRestoresTheClaimAndTheUploadResumesAtTheLease()
    }

    @Test
    fun `two engines over two files with shared ids keep their delivered memory apart`() = runTest(timeout = 2.minutes) {
        checks.twoEnginesOverTwoFilesWithSharedIdsKeepTheirDeliveredMemoryApart()
    }
}
