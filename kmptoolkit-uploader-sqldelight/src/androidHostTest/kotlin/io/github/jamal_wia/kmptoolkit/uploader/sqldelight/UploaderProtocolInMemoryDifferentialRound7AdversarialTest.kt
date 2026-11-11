package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.runner.RunWith

/**
 * Round 7 (A1), differential: the scenarios of the SQLite classes over `InMemoryUploaderStore`, the reference store of
 * the testing module. Both stores must satisfy the same protocol invariants under the same orderings.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UploaderProtocolInMemoryDifferentialRound7AdversarialTest : InterleavingTestBase() {

    @Test
    fun `in memory - pending A with keep, replace and cancel of A`() =
        explore(Scenario("mem keepB+replaceC+cancelA/PENDING_A", Seed.PENDING_A, listOf(Op.KEEP_B, Op.REPLACE_C, Op.CANCEL_A), emptyList(), true, 1), 20000, inMemory = true)

    @Test
    fun `in memory - replace, tag wipe and a free enqueue under close and restart`() =
        explore(Scenario("mem replaceC+cancelTag+freeD/PENDING_A/close+restart", Seed.PENDING_A, listOf(Op.REPLACE_C, Op.CANCEL_TAG, Op.FREE_D), listOf(Meta.CLOSE, Meta.RESTART), true, 1), 20000, inMemory = true)

    @Test
    fun `in memory - a close landing in the middle of any store call`() =
        explore(Scenario("mem replaceC+cancelA/PENDING_A/close-mid+restart", Seed.PENDING_A, listOf(Op.REPLACE_C, Op.CANCEL_A), listOf(Meta.CLOSE_MID, Meta.RESTART), true, 2), 20000, inMemory = true)

    @Test
    fun `in memory - uploads with replace, cancel and a delivery report under a close in the middle of a call`() =
        explore(Scenario("mem U replaceC+cancelA+settleA/PENDING_A/close-mid+restart", Seed.PENDING_A, listOf(Op.REPLACE_C, Op.CANCEL_A, Op.SETTLE_A), listOf(Meta.CLOSE_MID, Meta.RESTART), true, 1, upload = true), 20000, inMemory = true)

    @Test
    fun `in memory - uploads with an expired lease, a replace and a delivery report`() =
        explore(Scenario("mem U replaceC+settleA/EXPIRED_A/advance+close+restart", Seed.EXPIRED_A, listOf(Op.REPLACE_C, Op.SETTLE_A), listOf(Meta.ADVANCE, Meta.CLOSE, Meta.RESTART), true, 1, upload = true), 20000, inMemory = true)
}
