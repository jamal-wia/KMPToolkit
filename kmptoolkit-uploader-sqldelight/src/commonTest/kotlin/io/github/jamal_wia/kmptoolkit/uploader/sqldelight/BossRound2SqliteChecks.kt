package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.Counting
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.FlakyDelete
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.MARKER2
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.engine
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.real
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.scope
import io.github.jamal_wia.kmptoolkit.uploader.testing.MutableUploaderClock
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * A1 final boss round 2 over real SQLite (Android host and iOS simulator): the process-wide delivered-but-unconfirmed
 * memory of boss round 1 (D-29 S1) read through the real store, where every text that needs escaping goes through
 * the stored form and back, and across a rebuilt storage on the same file (a logout that closes the database too).
 */
internal class BossRound2SqliteChecks {

    /** Texts that the store escapes or keeps verbatim only by its canonical check: the fingerprint must survive both. */
    private val oddId: String = "﻿b2-id\u0000\uD800"
    private val oddPayload: String = MARKER2 + "payload\uDBFF\u0000"
    private val oddTag: String = MARKER2 + "account"

    suspend fun deliveredEffectIsNotRunAgainAfterTheEngineAndTheStorageAreRebuilt() {
        val name: String = uniqueDatabaseName("boss2-du")
        val clock = MutableUploaderClock(1_000_000L)
        val handler = Counting()
        val first: UploaderStorage = createFileUploaderStorage(name)
        val scope1: CoroutineScope = scope()
        val scope2: CoroutineScope = scope()
        var second: UploaderStorage? = null
        try {
            val engine1: UploaderEngine = engine(FlakyDelete(first.store, 1), first, handler, scope1, clock, listOf(oddId).iterator())
            assertEquals(oddId, real(10_000) { engine1.enqueue(handler, oddPayload, uniqueKey = MARKER2 + "k", tag = oddTag) })
            real(10_000) { engine1.drain() }
            assertEquals(listOf(oddPayload), handler.runs.value, "setup: the effect ran once")
            val seen: UploaderItem = assertNotNull(first.store.getById(oddId), "setup: the confirming delete failed")
            engine1.close()
            scope1.cancel()
            first.close()

            clock.advanceBy(60_000L)
            second = createFileUploaderStorage(name)
            assertEquals(seen, second.store.getById(oddId), "the reopened database gives the row back differently")
            val engine2: UploaderEngine = engine(second.store, second, handler, scope2, clock, listOf("unused").iterator())
            real(10_000) { engine2.drain() }
            assertEquals(listOf(oddPayload), handler.runs.value, "the rebuilt engine over the reopened database ran the delivered effect again")
            assertNull(second.store.getById(oddId), "the rebuilt engine did not confirm the delivery")
            engine2.close()
        } finally {
            scope1.cancel()
            scope2.cancel()
            second?.close()
            deleteDatabaseFile(name)
        }
    }

    suspend fun replaceOfADeliveredRowRunsOnlyTheNewEffect() {
        val name: String = uniqueDatabaseName("boss2-replace")
        val clock = MutableUploaderClock(1_000_000L)
        val handler = Counting()
        val storage: UploaderStorage = createFileUploaderStorage(name)
        val scope1: CoroutineScope = scope()
        val scope2: CoroutineScope = scope()
        try {
            val ids: Iterator<String> = listOf(oddId, "﻿b2-next").iterator()
            val engine1: UploaderEngine = engine(FlakyDelete(storage.store, 1), storage, handler, scope1, clock, ids)
            real(10_000) { engine1.enqueue(handler, "old", uniqueKey = MARKER2 + "draft") }
            real(10_000) { engine1.drain() }
            assertNotNull(storage.store.getById(oddId), "setup: the confirming delete failed")
            engine1.close()

            val engine2: UploaderEngine = engine(storage.store, storage, handler, scope2, clock, ids)
            val newId: String? = real(10_000) {
                engine2.enqueue(handler, "new", uniqueKey = MARKER2 + "draft", conflictPolicy = ConflictPolicy.REPLACE)
            }
            assertEquals("﻿b2-next", newId)
            assertNull(storage.store.getById(oddId), "the REPLACE left the superseded delivered row")
            real(10_000) { engine2.drain() }
            assertEquals(listOf("old", "new"), handler.runs.value, "the REPLACE of a delivered row ran the wrong effects")
            assertEquals(emptyList(), storage.store.getAllActive(), "the new effect was not confirmed")
            engine2.close()
        } finally {
            scope1.cancel()
            scope2.cancel()
            storage.close()
            deleteDatabaseFile(name)
        }
    }
}
