package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A1 final boss round 1: rows an earlier version wrote whose texts merely start like a stored form. */
internal class BossRound1MarkerChecks {

    private val marker2: String = "\uFDD0kmp2\uFDD1"

    private suspend fun <T> real(millis: Long, block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(millis) { block() } }

    // ---------------------------------------------------------------------------------------------------------------
    // 1. Rows of an earlier version whose texts merely start like a stored form.
    // ---------------------------------------------------------------------------------------------------------------

    private fun insertRawRow(name: String, id: String, type: String, uniqueKey: String?, tag: String?) {
        val driver = createDriver(uploaderDatabaseSchema, name)
        try {
            driver.execute(
                null,
                "INSERT INTO kmptoolkit_uploader_item(id, type, payload, schema_version, unique_key, ordering_key, tag, state, attempts, " +
                    "next_run_at, created_at, last_error, lease_until) VALUES (?, ?, 'p', 1, ?, NULL, ?, 'PENDING', 0, 0, 1, NULL, 0)",
                4,
            ) {
                bindString(0, id)
                bindString(1, type)
                bindString(2, uniqueKey)
                bindString(3, tag)
            }
        } finally {
            driver.close()
        }
    }

    /**
     * Docs 05 "Texts are stored as given": every text comes back as it went in, like InMemoryUploaderStore, and a row an
     * earlier version wrote reads back unchanged (only an exact escaped form is decoded). A tag, unique key or type that
     * merely starts with the marker therefore reads back as itself, so the same text must also reach the row through
     * every query keyed by it: the tag wipe, the KEEP identity and the type observer.
     */
    suspend fun markerTextsOfAnEarlierVersionAreReachedByTagKeyAndType() {
        val name = "boss1-marker-${Random.nextLong().toString(16)}"
        val type = "${marker2}type"
        val key = "${marker2}key"
        val tag = "${marker2}account-42"
        insertRawRow(name, id = "legacy-1", type = type, uniqueKey = key, tag = tag)
        val storage: UploaderStorage = createFileUploaderStorage(name)
        try {
            val read: UploaderItem = assertNotNull(storage.store.getById("legacy-1"))
            assertEquals(listOf(type, key, tag), listOf(read.type, read.uniqueKey, read.tag), "setup: the texts read back as stored")

            val problems: MutableList<String> = mutableListOf()
            val observed: List<UploaderItem> = real(10_000) { storage.store.observeByType(type).first() }
            if (observed.map { it.id } != listOf("legacy-1")) problems += "observeByType(its own type) saw ${observed.map { it.id }}"

            val kept: Boolean = storage.store.insertKeep(anItem(id = "fresh-1", type = type, uniqueKey = key)).inserted
            if (kept) problems += "a KEEP enqueue of the same type and unique key was accepted beside the live row"

            val removed: List<UploaderItem> = storage.store.deleteByTag(tag)
            if (removed.map { it.id } != listOf("legacy-1")) problems += "deleteByTag(its own tag) removed ${removed.map { it.id }}"
            if (storage.store.getById("legacy-1") != null) problems += "the row survived a wipe of its own tag"
            assertEquals(emptyList(), problems, "a text that reads back unchanged does not reach its row")
        } finally {
            storage.close()
            deleteDatabaseFile(name)
        }
    }

}
