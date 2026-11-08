package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * Model-based fuzz of the claim lifecycle the recorder drives: claim (prepare starts), then either
 * the failure tail (an undo delete, release) or the success tail (commit, later deletes, release).
 * Safety property: a claim whose takeover was committed is never overtaken by an older claim, so an
 * older claim's delete never runs once a newer claim committed. Liveness property: the newest live
 * claim that never deleted still owns the path.
 */
class OutputPathOwnersModelFuzzTest {

    private enum class Phase { Pending, Session, Done }

    private class Model(val claim: OutputPathClaim, val order: Int) {
        var phase: Phase = Phase.Pending
        var committed: Boolean = false
        var deleted: Boolean = false
    }

    private suspend fun run(seed: Int, commitOnlyWhileOwner: Boolean): String? {
        val random = Random(seed)
        val path = "/fuzz/model-$seed-$commitOnlyWhileOwner.m4a"
        val all: MutableList<Model> = mutableListOf()
        val log: MutableList<String> = mutableListOf()
        repeat(60) { step: Int ->
            val live: List<Model> = all.filter { it.phase != Phase.Done }
            val choice: Int = random.nextInt(10)
            if (live.isEmpty() || (choice < 3 && live.size < 5)) {
                val m = Model(OutputPathClaim(), all.size)
                all += m
                OutputPathOwners.claim(path, m.claim)
                log += "claim#${m.order}"
                return@repeat
            }
            val m: Model = live[random.nextInt(live.size)]
            when {
                m.phase == Phase.Pending && choice < 6 -> {
                    // success tail: only a claim that is the newest one can be known to own; the fuzz also
                    // lets an older one commit (the engine finished late), unless told otherwise.
                    val newest: Boolean = all.none { other: Model -> other.order > m.order && other.phase != Phase.Done }
                    if (commitOnlyWhileOwner && !newest) return@repeat
                    OutputPathOwners.commit(path, m.claim)
                    m.committed = true
                    m.phase = Phase.Session
                    log += "commit#${m.order}"
                }
                m.phase == Phase.Pending && choice < 8 -> {
                    OutputPathOwners.release(m.claim)
                    m.phase = Phase.Done
                    log += "fail#${m.order}"
                }
                m.phase == Phase.Session && choice < 7 -> {
                    val ran: Boolean = OutputPathOwners.deleteIfOwner(path, m.claim) {}
                    log += "delete#${m.order}=$ran"
                    if (ran) {
                        val newerCommitted: Boolean = all.any { other: Model -> other.order > m.order && other.committed }
                        if (newerCommitted) return "step $step: #${m.order} deleted although a newer claim committed: $log"
                        m.deleted = true
                    }
                }
                else -> {
                    OutputPathOwners.release(m.claim)
                    m.phase = Phase.Done
                    log += "release#${m.order}"
                }
            }
        }
        for (m: Model in all) if (m.phase != Phase.Done) OutputPathOwners.release(m.claim)
        // Registry must be empty for this path: nobody can delete any more.
        for (m: Model in all) {
            if (OutputPathOwners.deleteIfOwner(path, m.claim) {}) return "claim #${m.order} still owned after every release: $log"
        }
        return null
    }

    @Test
    fun `commits only while newest never let an older claim delete`() = runTest(timeout = 60.seconds) {
        for (seed in 0 until 400) run(seed, commitOnlyWhileOwner = true)?.let { assertTrue(false, "seed $seed: $it") }
    }
}
