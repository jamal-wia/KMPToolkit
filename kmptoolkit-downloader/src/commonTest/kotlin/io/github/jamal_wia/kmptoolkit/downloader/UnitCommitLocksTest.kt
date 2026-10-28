package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** What [UnitCommitLocks] promises: one commit per unit per directory, and nothing more. */
class UnitCommitLocksTest {

    @Test
    fun `a second commit of the same unit in the same directory waits for the first`() = runTest {
        val release = CompletableDeferred<Unit>()
        val order: MutableList<String> = mutableListOf()
        launch { UnitCommitLocks.withUnitLock("/base", "u") { order += "first"; release.await(); order += "first done" } }
        runCurrent()
        val second: Job = launch { UnitCommitLocks.withUnitLock("/base", "u") { order += "second" } }
        runCurrent()

        assertFalse(second.isCompleted, "the second commit must wait while the first holds the unit")
        release.complete(Unit)
        second.join()

        assertEquals(listOf("first", "first done", "second"), order)
    }

    @Test
    fun `another unit or another directory never waits`() = runTest {
        val release = CompletableDeferred<Unit>()
        val holder: Job = launch { UnitCommitLocks.withUnitLock("/base", "u") { release.await() } }
        runCurrent()

        val otherUnit: Job = launch { UnitCommitLocks.withUnitLock("/base", "v") {} }
        val otherDirectory: Job = launch { UnitCommitLocks.withUnitLock("/other", "u") {} }
        runCurrent()

        assertTrue(otherUnit.isCompleted)
        assertTrue(otherDirectory.isCompleted)
        release.complete(Unit)
        holder.join()
    }

    @Test
    fun `a commit cancelled while waiting leaves the unit usable for the next one`() = runTest {
        val release = CompletableDeferred<Unit>()
        val holder: Job = launch { UnitCommitLocks.withUnitLock("/base", "c") { release.await() } }
        runCurrent()
        val cancelled: Job = launch { UnitCommitLocks.withUnitLock("/base", "c") { error("must not run") } }
        runCurrent()

        cancelled.cancel()
        cancelled.join()
        release.complete(Unit)
        holder.join()

        var ran = false
        UnitCommitLocks.withUnitLock("/base", "c") { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `a commit that throws releases the unit`() = runTest {
        runCatching { UnitCommitLocks.withUnitLock("/base", "t") { error("commit failed") } }

        var ran = false
        UnitCommitLocks.withUnitLock("/base", "t") { ran = true }
        assertTrue(ran)
    }
}
