package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** [BossRound1SqliteChecks] on this platform's SQLite driver. */
@RunWith(AndroidJUnit4::class)
class BossRound1SqliteAndroidAdversarialTest {

    private val checks = BossRound1SqliteChecks()

    @Test
    fun `an escaped upload settles once while close races its report near Long MAX`() = runTest(timeout = 3.minutes) {
        checks.escapedUploadSettlesOnceWhileCloseRacesNearLongMax()
    }

    @Test
    fun `the late release of a closed engine spares its replacement at Long MAX with an escaped id`() = runTest(timeout = 2.minutes) {
        checks.lateReleaseSparesTheReplacementAtLongMaxWithAnEscapedId()
    }

    @Test
    fun `the gateway leaves an escaped plain item untouched`() = runTest(timeout = 2.minutes) {
        checks.gatewayLeavesAnEscapedPlainItemUntouched()
    }
}
