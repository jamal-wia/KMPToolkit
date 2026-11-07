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

    @Test
    fun `tryWithUnitLock skips a unit a commit holds and runs once it is free`() = runTest {
        val release = CompletableDeferred<Unit>()
        val holder: Job = launch { UnitCommitLocks.withUnitLock("/base", "try") { release.await() } }
        runCurrent()
        var ran = 0

        val whileHeld: Boolean = UnitCommitLocks.tryWithUnitLock("/base", "try") { ran++ }

        assertFalse(whileHeld)
        assertEquals(0, ran, "the action must not run under a running commit")
        release.complete(Unit)
        holder.join()
        assertTrue(UnitCommitLocks.tryWithUnitLock("/base", "try") { ran++ })
        assertEquals(1, ran)
    }

    @Test
    fun `tryWithUnitLock excludes a commit while it runs and leaves nothing behind`() = runTest {
        var started = false
        var committed: Job? = null
        UnitCommitLocks.tryWithUnitLock("/base", "excl") {
            committed = launch { UnitCommitLocks.withUnitLock("/base", "excl") { started = true } }
            runCurrent()
            assertFalse(started, "a commit must wait for the action")
        }
        committed!!.join()
        assertTrue(started)
        assertTrue(UnitCommitLocks.tryWithUnitLock("/base", "excl") {})
    }

    @Test
    fun `tryWithUnitLock releases the registry when the action throws`() = runTest {
        runCatching { UnitCommitLocks.tryWithUnitLock("/base", "boom") { error("x") } }

        var ran = false
        UnitCommitLocks.withUnitLock("/base", "boom") { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `tryDetachThenDispose disposes after the registry is released so other units are not stalled`() = runTest {
        var otherUnitRan = false
        var disposed: String? = null

        val ran: Boolean = UnitCommitLocks.tryDetachThenDispose(
            baseDirectory = "/base",
            unitId = "slow",
            detach = { "tree" },
            dispose = { handle: String ->
                // The slow delete: a cancel of a different unit must not find the registry busy.
                otherUnitRan = UnitCommitLocks.tryWithUnitLock("/base", "other") {}
                disposed = handle
            },
        )

        assertTrue(ran)
        assertEquals("tree", disposed)
        assertTrue(otherUnitRan, "the registry must be free while the detached tree is disposed")
    }

    @Test
    fun `tryDetachThenDispose neither detaches nor disposes under a running commit`() = runTest {
        val release = CompletableDeferred<Unit>()
        val holder: Job = launch { UnitCommitLocks.withUnitLock("/base", "busy") { release.await() } }
        runCurrent()
        var detached = 0
        var disposed = 0

        val ran: Boolean = UnitCommitLocks.tryDetachThenDispose(
            baseDirectory = "/base",
            unitId = "busy",
            detach = { detached++; "tree" },
            dispose = { _: String -> disposed++ },
        )

        assertFalse(ran)
        assertEquals(0, detached)
        assertEquals(0, disposed)
        release.complete(Unit)
        holder.join()
    }

    @Test
    fun `tryDetachThenDispose does not dispose when there was nothing to detach`() = runTest {
        var disposed = 0

        val ran: Boolean = UnitCommitLocks.tryDetachThenDispose<String>(
            baseDirectory = "/base",
            unitId = "empty",
            detach = { null },
            dispose = { disposed++ },
        )

        assertTrue(ran)
        assertEquals(0, disposed)
    }
}
