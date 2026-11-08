package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Real threads hammering one registry key through the full claim lifecycle (claim, then the failure
 * tail or commit and deletes, then release), each spelling the path differently. Two delete bodies
 * on one path never overlap, a claim that meets a running delete waits it out instead of failing, a
 * delete that throws does not wedge the key, no call hangs, and once everybody has let go a fresh
 * claim owns the path alone.
 */
class OutputPathOwnersThreadStormTest {

    private val spellings: List<String> = listOf("/storm/P.m4a", "//storm//p.m4a", "/storm/./x/../p.M4A", "/STORM/p.m4a/")

    @Test
    fun `eight threads of full lifecycles never overlap a delete and leave the key reusable`() = runTest(timeout = 60.seconds) {
        val inDelete = MutableStateFlow(0)
        val overlaps = MutableStateFlow(0)
        val deletes = MutableStateFlow(0)
        withContext(Dispatchers.Default) {
            withTimeout(40.seconds) {
                (0 until 8).map { worker: Int ->
                    launch {
                        val random = Random(1000 + worker)
                        repeat(400) { round: Int ->
                            val path: String = spellings[random.nextInt(spellings.size)]
                            val claim = OutputPathClaim()
                            OutputPathOwners.claim(path, claim)
                            if (random.nextInt(3) == 0) yield()
                            val failTail: Boolean = random.nextInt(4) == 0
                            if (!failTail) OutputPathOwners.commit(spellings[random.nextInt(spellings.size)], claim)
                            repeat(random.nextInt(3)) {
                                try {
                                    OutputPathOwners.deleteIfOwner(path, claim) {
                                        if (inDelete.updateAndGetCompat(+1) > 1) overlaps.update { it + 1 }
                                        deletes.update { it + 1 }
                                        var spin = 0
                                        while (spin < random.nextInt(2000)) spin++
                                        inDelete.update { it - 1 }
                                        if (round % 97 == 0) error("a delete that throws")
                                    }
                                } catch (expected: IllegalStateException) {
                                    // the delete body failed on purpose; the registry must stay usable
                                }
                            }
                            OutputPathOwners.release(claim)
                        }
                    }
                }.forEach { it.join() }
            }
        }
        assertEquals(0, overlaps.value, "two delete bodies ran at once on one path")
        assertEquals(0, inDelete.value)
        assertTrue(deletes.value > 0, "the storm never reached a delete")
        val fresh = OutputPathClaim()
        withTimeout(5.seconds) { OutputPathOwners.claim("/storm/p.m4a", fresh) }
        assertTrue(OutputPathOwners.deleteIfOwner("/storm/p.m4a", fresh) {}, "the key is wedged after the storm")
        OutputPathOwners.release(fresh)
    }

    private fun MutableStateFlow<Int>.updateAndGetCompat(delta: Int): Int {
        while (true) {
            val current: Int = value
            if (compareAndSet(current, current + delta)) return current + delta
        }
    }
}
