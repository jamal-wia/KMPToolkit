package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** A2 boss round 1: the KEEP half of the earliest-parked-holder claim, on this platform's SQLite driver. */
@RunWith(AndroidJUnit4::class)
class KeepEarliestParkedBossOneA2AdversarialTest {

    @Test
    fun `KEEP over every parked spelling returns the earliest and removes all`() = runTest(timeout = 2.minutes) {
        MarkerLookupsBoss1Checks().keepOverEveryParkedSpellingReturnsTheEarliestAndRemovesAll()
    }
}
