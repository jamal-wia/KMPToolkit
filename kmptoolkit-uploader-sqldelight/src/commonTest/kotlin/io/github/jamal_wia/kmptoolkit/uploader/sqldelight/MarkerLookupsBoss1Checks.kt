package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A1 boss round 1, ruling S2: a text that starts like a stored form and is not exactly one reads back unchanged, so
 * every lookup keyed by it (tag wipe, type observer, KEEP and REPLACE identity) reaches both the row an earlier version
 * wrote under the raw text and the row this version writes under the escaped form, in insertion order. A text that is
 * exactly an escaped form has no second spelling. Every other text costs the queries it always cost.
 */
internal class MarkerLookupsBoss1Checks {

    private val marker2: String = "﷐kmp2﷑"
    private val marker1: String = "﷐kmp1﷑"

    private suspend fun <T> real(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(20_000) { block() } }

    /** A row exactly as 4.0.0 wrote it: every text verbatim. */
    private fun rawRow(
        name: String,
        id: String,
        type: String,
        uniqueKey: String?,
        tag: String?,
        state: String = "PENDING",
    ) {
        val driver: SqlDriver = createDriver(uploaderDatabaseSchema, name)
        try {
            driver.execute(
                null,
                "INSERT INTO kmptoolkit_uploader_item(id, type, payload, schema_version, unique_key, ordering_key, tag, state, attempts, " +
                    "next_run_at, created_at, last_error, lease_until) VALUES (?, ?, 'p', 1, ?, NULL, ?, ?, 0, 0, 1, NULL, 0)",
                5,
            ) {
                bindString(0, id)
                bindString(1, type)
                bindString(2, uniqueKey)
                bindString(3, tag)
                bindString(4, state)
            }
        } finally {
            driver.close()
        }
    }

    private suspend fun <T> withStorage(name: String, block: suspend (UploaderStorage) -> T): T {
        val storage: UploaderStorage = createFileUploaderStorage(name)
        try {
            return real { block(storage) }
        } finally {
            storage.close()
        }
    }

    private suspend fun withDatabase(prefix: String, block: suspend (String) -> Unit) {
        val name: String = uniqueDatabaseName(prefix)
        try {
            block(name)
        } finally {
            deleteDatabaseFile(name)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    suspend fun tagWipeRemovesTheRawRowsAndTheEscapedRowsInInsertionOrder() = withDatabase("boss1-tag") { name ->
        val tag = "${marker2}account-42"
        rawRow(name, "r1", type = "t", uniqueKey = null, tag = tag)
        withStorage(name) { storage ->
            assertTrue(storage.store.insertKeep(anItem(id = "n1", tag = tag)).inserted)
            assertTrue(storage.store.insertKeep(anItem(id = "other", tag = "${marker2}account-43")).inserted)
        }
        rawRow(name, "r2", type = "t", uniqueKey = null, tag = tag)
        withStorage(name) { storage ->
            val removed: List<UploaderItem> = storage.store.deleteByTag(tag)
            assertEquals(listOf("r1", "n1", "r2"), removed.map { it.id }, "every row of the tag, in insertion order")
            assertEquals(listOf(tag, tag, tag), removed.map { it.tag }, "the tag reads back as given")
            assertEquals(listOf("other"), storage.store.getAllActive().map { it.id }, "only the other tag survives")
        }
    }

    suspend fun tagWipeOfAnExactEscapedFormTouchesOnlyTheRowItDecodesTo() = withDatabase("boss1-canon") { name ->
        // C is exactly what the writer stores for t, so a raw row holding C reads back as t and is reached by t.
        val t = "a\u0000"
        val c: String = toStoredText(t)
        assertTrue(c.startsWith(marker2) && fromStoredText(c) == t, "setup: c is a canonical escaped form")
        rawRow(name, "r1", type = "t", uniqueKey = null, tag = c)
        withStorage(name) { storage ->
            assertEquals("a\u0000", assertNotNull(storage.store.getById("r1")).tag, "setup: the raw row reads back decoded")
            assertEquals(emptyList(), storage.store.deleteByTag(c).map { it.id }, "the escaped text itself is a different tag")
            assertNotNull(storage.store.getById("r1"), "the row survived the wipe of the escaped text")
            assertEquals(listOf("r1"), storage.store.deleteByTag(t).map { it.id }, "the decoded text reaches the row")
            assertNull(storage.store.getById("r1"))
        }
    }

    suspend fun typeObserverMergesBothSpellingsInOrderAndReEmitsAfterAWrite() = withDatabase("boss1-type") { name ->
        val type = "${marker1}type"
        rawRow(name, "r1", type = type, uniqueKey = null, tag = null)
        withStorage(name) { storage ->
            assertTrue(storage.store.insertKeep(anItem(id = "n1", type = type)).inserted)
        }
        rawRow(name, "r2", type = type, uniqueKey = null, tag = null)
        withStorage(name) { storage ->
            val first: List<UploaderItem> = storage.store.observeByType(type).first()
            assertEquals(listOf("r1", "n1", "r2"), first.map { it.id })
            assertEquals(listOf(type, type, type), first.map { it.type }, "the type reads back as given")
            coroutineScope {
                val seen: Deferred<List<UploaderItem>> = async { storage.store.observeByType(type).first { it.size == 4 } }
                assertTrue(storage.store.insertKeep(anItem(id = "n2", type = type)).inserted)
                assertEquals(listOf("r1", "n1", "r2", "n2"), seen.await().map { it.id }, "a write re-emits the merged list")
            }
        }
    }

    suspend fun keepRevivesAParkedLegacyHolderAndRespectsALiveOne() = withDatabase("boss1-keep") { name ->
        val type = "${marker2}type"
        val key = "${marker2}key"
        rawRow(name, "parked", type = type, uniqueKey = key, tag = null, state = "PARKED")
        withStorage(name) { storage ->
            assertTrue(storage.store.insertKeep(anItem(id = "n1", type = type, uniqueKey = key)).inserted, "a parked legacy holder is revived")
            assertEquals(listOf("n1"), storage.store.getAllActive().map { it.id }, "the parked row is gone, one live row")
            assertNull(storage.store.getById("parked"))
        }
        listOf("PENDING", "IN_FLIGHT").forEach { state ->
            val live: String = uniqueDatabaseName("boss1-keep-$state")
            try {
                rawRow(live, "legacy", type = type, uniqueKey = key, tag = null, state = state)
                withStorage(live) { storage ->
                    assertEquals(false, storage.store.insertKeep(anItem(id = "n2", type = type, uniqueKey = key)).inserted, "a $state legacy holder keeps the key")
                    assertEquals(listOf("legacy"), storage.store.getAllActive().map { it.id }, "no second live row beside the $state holder")
                }
            } finally {
                deleteDatabaseFile(live)
            }
        }
    }

    suspend fun keepSeesALiveRowOfTheEscapedSpellingBesideAParkedLegacyRow() = withDatabase("boss1-keep2") { name ->
        val type = "${marker2}type"
        val key = "${marker2}key"
        rawRow(name, "parked", type = type, uniqueKey = key, tag = null, state = "PARKED")
        // The escaped spelling is also occupied: by a row that is live. KEEP must refuse and leave both rows alone.
        rawRow(name, "live", type = toStoredText(type), uniqueKey = toStoredText(key), tag = null, state = "PENDING")
        withStorage(name) { storage ->
            assertEquals(false, storage.store.insertKeep(anItem(id = "n1", type = type, uniqueKey = key)).inserted)
            assertNotNull(storage.store.getById("parked"), "a refused KEEP removes nothing")
            assertNotNull(storage.store.getById("live"))
        }
    }

    suspend fun replaceReturnsTheLiveLegacyHolderAndLeavesOneRow() = withDatabase("boss1-replace") { name ->
        val type = "${marker2}type"
        val key = "${marker2}key"
        rawRow(name, "legacy", type = type, uniqueKey = key, tag = null)
        withStorage(name) { storage ->
            val superseded: UploaderItem? = storage.store.insertReplace(anItem(id = "n1", type = type, uniqueKey = key))
            assertEquals("legacy", superseded?.id, "the legacy holder is handed back for cancellation")
            assertEquals(listOf(type, key), listOf(superseded?.type, superseded?.uniqueKey))
            assertEquals(listOf("n1"), storage.store.getAllActive().map { it.id }, "exactly one live row of the identity")
            assertEquals("n1", storage.store.insertReplace(anItem(id = "n2", type = type, uniqueKey = key))?.id, "the next REPLACE finds the new row")
        }
    }

    suspend fun replaceOverEveryCombinationOfSpellingsReturnsTheEarliestAndRemovesAll() = withDatabase("boss1-combos") { name ->
        val type = "${marker2}type"
        val key = "${marker2}key"
        // 4.0.0 stored the raw texts; a 4.0.0 row keyed by the escaped form of one of them is the residual of the ruling.
        rawRow(name, "raw-raw", type = type, uniqueKey = key, tag = null, state = "PARKED")
        rawRow(name, "raw-esc", type = type, uniqueKey = toStoredText(key), tag = null, state = "PARKED")
        rawRow(name, "esc-raw", type = toStoredText(type), uniqueKey = key, tag = null, state = "PARKED")
        rawRow(name, "esc-esc", type = toStoredText(type), uniqueKey = toStoredText(key), tag = null, state = "PARKED")
        withStorage(name) { storage ->
            val superseded: UploaderItem? = storage.store.insertReplace(anItem(id = "n1", type = type, uniqueKey = key))
            assertEquals("raw-raw", superseded?.id, "the earliest holder by sequence is returned")
            assertEquals(listOf("n1"), storage.store.getAllActive().map { it.id })
            listOf("raw-raw", "raw-esc", "esc-raw", "esc-esc").forEach { assertNull(storage.store.getById(it), "$it was removed") }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Query counts: an ordinary text, and an exact escaped form, cost what they always cost.
    // ---------------------------------------------------------------------------------------------------------------

    private class CountingSqlDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        val statements: MutableList<String> = mutableListOf()

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            statements += sql.split(Regex("\\s+")).joinToString(" ")
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            statements += sql.split(Regex("\\s+")).joinToString(" ")
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    private data class Counts(val identityReads: Int, val identityDeletes: Int, val tagReads: Int, val tagDeletes: Int, val typeReads: Int)

    private fun CountingSqlDriver.counts(): Counts = Counts(
        identityReads = statements.count { it.startsWith("SELECT") && it.contains("type = ? AND unique_key = ?") },
        identityDeletes = statements.count { it.startsWith("DELETE") && it.contains("type = ? AND unique_key = ?") },
        tagReads = statements.count { it.startsWith("SELECT") && it.contains("WHERE tag = ?") },
        tagDeletes = statements.count { it.startsWith("DELETE") && it.contains("WHERE tag = ?") },
        typeReads = statements.count { it.startsWith("SELECT") && it.contains("WHERE type = ? ORDER BY") },
    )

    /** Runs the lookups for [text] used as type, unique key and tag, and returns the statements they ran. */
    private suspend fun countsFor(text: String): Counts {
        val driver = CountingSqlDriver(createDriver(uploaderDatabaseSchema, name = null))
        val storage: UploaderStorage = standaloneStorage(driver)
        try {
            real {
                storage.store.insertKeep(anItem(id = "a", type = text, uniqueKey = text, tag = text))
                storage.store.insertReplace(anItem(id = "b", type = text, uniqueKey = text))
                storage.store.deleteByTag(text)
                storage.store.observeByType(text).first()
            }
        } finally {
            storage.close()
        }
        return driver.counts()
    }

    suspend fun anOrdinaryTextAndAnExactEscapedFormCostTheQueriesTheyAlwaysCost() {
        val ordinary: Counts = countsFor("ordinary")
        // keep: 1 identity read; replace: 1 read + 1 delete; wipe: 1 read + 1 delete; observer: 1 read.
        val oneSpelling = Counts(identityReads = 2, identityDeletes = 1, tagReads = 1, tagDeletes = 1, typeReads = 1)
        assertEquals(oneSpelling, ordinary, "ordinary text")
        assertEquals(oneSpelling, countsFor(toStoredText("a\u0000")), "an exact escaped form has one spelling")
        assertEquals(oneSpelling, countsFor("a\u0000"), "a text that needs escaping has one spelling")
    }

    suspend fun aMarkerPrefixedTextThatReadsBackUnchangedIsLookedUpUnderBothSpellings() {
        val counts: Counts = countsFor("${marker2}plain")
        // Type and key have two spellings each, so the KEEP and the REPLACE read four identities apiece, the REPLACE
        // deletes all four; the tag wipe and the type observer read twice and the wipe deletes twice.
        assertEquals(Counts(identityReads = 8, identityDeletes = 4, tagReads = 2, tagDeletes = 2, typeReads = 2), counts)
    }
}
