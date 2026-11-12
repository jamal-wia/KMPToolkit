package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.Counting
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.FlakyDelete
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.engine
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.real
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.scope
import io.github.jamal_wia.kmptoolkit.uploader.testing.MutableUploaderClock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * boss2-S1 over real SQLite: a cancel made inside the storage's own transaction runner, rolled back by the consumer,
 * restores the row of an effect that already ran; the engine then runs it again in the same process.
 */
internal class BossRound2SqliteSuspectChecks {

    private class Rollback : RuntimeException("the consumer's transaction rolled back")

    suspend fun rolledBackCancelDoesNotRunTheDeliveredEffectAgain() {
        val name: String = uniqueDatabaseName("boss2-rollback")
        val clock = MutableUploaderClock(1_000_000L)
        val handler = Counting()
        val storage: UploaderStorage = createFileUploaderStorage(name)
        val scope1: CoroutineScope = scope()
        try {
            val store = FlakyDelete(storage.store, 1)
            val engine1: UploaderEngine = engine(store, storage, handler, scope1, clock, listOf("boss2-rollback-1").iterator())
            real(10_000) { engine1.enqueue(handler, "send") }
            real(10_000) { engine1.drain() }
            val row: UploaderItem = assertNotNull(storage.store.getById("boss2-rollback-1"), "setup: the confirming delete failed")
            assertFailsWith<Rollback> {
                real(10_000) {
                    storage.transactionRunner.inTransaction {
                        engine1.cancel("boss2-rollback-1")
                        throw Rollback()
                    }
                }
            }
            assertEquals(row, storage.store.getById("boss2-rollback-1"), "setup: the rollback restored the very row")
            clock.advanceBy(60_000L)
            real(10_000) { engine1.drain() }
            assertEquals(listOf("send"), handler.runs.value, "the restored row of a delivered effect ran its handler again in the same process")
            engine1.close()
        } finally {
            scope1.cancel()
            storage.close()
            deleteDatabaseFile(name)
        }
    }
}
