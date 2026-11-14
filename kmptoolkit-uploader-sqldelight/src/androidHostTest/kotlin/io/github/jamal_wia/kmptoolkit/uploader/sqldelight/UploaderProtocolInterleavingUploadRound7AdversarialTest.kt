package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 7 (A1), attack 3: the upload hand-off, the delivery report and the withdrawals in every ordering (see the plain
 * class for the method).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UploaderProtocolInterleavingUploadRound7AdversarialTest : InterleavingTestBase() {

    @Test
    fun `uploads - replace, cancel and a delivery report in every ordering with a close`() =
        explore(Scenario("U replaceC+cancelA+settleA/PENDING_A/close", Seed.PENDING_A, listOf(Op.REPLACE_C, Op.CANCEL_A, Op.SETTLE_A), listOf(Meta.CLOSE), true, 1, upload = true), 20000)

    @Test
    fun `uploads - keep, tag wipe and a delivery report under close and restart`() =
        explore(Scenario("U keepB+cancelTag+settleA/PENDING_A/close+restart", Seed.PENDING_A, listOf(Op.KEEP_B, Op.CANCEL_TAG, Op.SETTLE_A), listOf(Meta.CLOSE, Meta.RESTART), true, 1, upload = true), 20000)

    @Test
    fun `uploads - an expired lease re-handed while a replace and a delivery report race`() =
        explore(Scenario("U replaceC+settleA/EXPIRED_A/advance+close+restart", Seed.EXPIRED_A, listOf(Op.REPLACE_C, Op.SETTLE_A), listOf(Meta.ADVANCE, Meta.CLOSE, Meta.RESTART), true, 2, upload = true), 20000)

    @Test
    fun `uploads - a live lease with cancel, delivery report, replace and a time step`() =
        explore(Scenario("U cancelA+settleA+replaceC/INFLIGHT_A/advance+close", Seed.INFLIGHT_A, listOf(Op.CANCEL_A, Op.SETTLE_A, Op.REPLACE_C), listOf(Meta.ADVANCE, Meta.CLOSE), true, 1, upload = true), 20000)

    @Test
    fun `uploads - a close landing in the middle of a claim or a delivery report`() =
        explore(Scenario("U keepB+settleA/PENDING_A/close-mid+restart", Seed.PENDING_A, listOf(Op.KEEP_B, Op.SETTLE_A), listOf(Meta.CLOSE_MID, Meta.RESTART), true, 2, upload = true), 20000)

    @Test
    fun `uploads - a close in the middle of a call while an expired lease is re-handed and cancelled`() =
        explore(Scenario("U replaceC+cancelA/EXPIRED_A/close-mid+restart+advance", Seed.EXPIRED_A, listOf(Op.REPLACE_C, Op.CANCEL_A), listOf(Meta.CLOSE_MID, Meta.RESTART, Meta.ADVANCE), true, 1, upload = true), 20000)
}
