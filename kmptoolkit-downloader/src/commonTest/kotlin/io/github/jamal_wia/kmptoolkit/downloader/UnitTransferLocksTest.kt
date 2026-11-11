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

/** What [UnitTransferLocks] promises: one transfer-and-commit per unit per scope, and nothing more. */
class UnitTransferLocksTest {

    @Test
    fun `a second call for the same unit in the same scope waits for the first`() = runTest {
        val release = CompletableDeferred<Unit>()
        val order: MutableList<String> = mutableListOf()
        launch {
            UnitTransferLocks.withTransferLock("scope-a", "u") { order += "first"; release.await(); order += "first done" }
        }
        runCurrent()
        val second: Job = launch { UnitTransferLocks.withTransferLock("scope-a", "u") { order += "second" } }
        runCurrent()

        assertFalse(second.isCompleted, "the second call must wait while the first holds the unit")
        release.complete(Unit)
        second.join()

        assertEquals(listOf("first", "first done", "second"), order)
    }

    @Test
    fun `another unit or another scope never waits`() = runTest {
        val release = CompletableDeferred<Unit>()
        val holder: Job = launch { UnitTransferLocks.withTransferLock("scope-b", "u") { release.await() } }
        runCurrent()

        val otherUnit: Job = launch { UnitTransferLocks.withTransferLock("scope-b", "v") {} }
        val otherScope: Job = launch { UnitTransferLocks.withTransferLock("scope-c", "u") {} }
        runCurrent()

        assertTrue(otherUnit.isCompleted)
        assertTrue(otherScope.isCompleted)
        release.complete(Unit)
        holder.join()
    }

    @Test
    fun `a call cancelled while waiting leaves the unit usable for the next one`() = runTest {
        val release = CompletableDeferred<Unit>()
        val holder: Job = launch { UnitTransferLocks.withTransferLock("scope-d", "u") { release.await() } }
        runCurrent()
        val cancelled: Job = launch { UnitTransferLocks.withTransferLock("scope-d", "u") { error("must not run") } }
        runCurrent()

        cancelled.cancel()
        cancelled.join()
        release.complete(Unit)
        holder.join()

        var ran = false
        UnitTransferLocks.withTransferLock("scope-d", "u") { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `an action that throws releases the unit`() = runTest {
        runCatching { UnitTransferLocks.withTransferLock("scope-e", "u") { error("boom") } }

        var ran = false
        UnitTransferLocks.withTransferLock("scope-e", "u") { ran = true }
        assertTrue(ran)
    }
}
