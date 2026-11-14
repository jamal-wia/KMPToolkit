package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Round 7 (persisted-data fidelity): the live view and the tag wipe address rows by the text the consumer gave. Types and
 * tags that the drivers would change (leading U+FEFF, NUL, lone surrogate) must select exactly their own rows, both in
 * `observeByType` and in `deleteByTag`, and the rows come back with the texts as given.
 */
abstract class OddTextObserveRound7Base {

    private val odd: List<String> = listOf("t", "﻿t", "t\u0000", "t\u0000u", "\uD800", "\uDC00", "￾t", "﷐kmp1﷑", "")

    private fun item(id: String, type: String, tag: String?): UploaderItem = UploaderItem(
        id = id, type = type, payload = type + "|" + tag, schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = tag,
        state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
    )

    @Test
    fun `observeByType and deleteByTag select exactly the rows of their own odd text`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        val store: UploaderStore = storage.store
        try {
            odd.forEachIndexed { index, text -> store.insertKeep(item("i$index", type = text, tag = text)) }
            odd.forEachIndexed { index, text ->
                val seen: List<UploaderItem> = withContext(Dispatchers.Default) { withTimeout(10_000) { store.observeByType(text).first() } }
                assertEquals(listOf("i$index"), seen.map { it.id }, "observeByType for index $index")
                assertEquals(text, seen.single().type)
                assertEquals(text, seen.single().tag)
            }
            odd.forEachIndexed { index, text ->
                val removed: List<UploaderItem> = store.deleteByTag(text)
                assertEquals(listOf("i$index"), removed.map { it.id }, "deleteByTag for index $index")
                assertEquals(odd.size - index - 1, store.getAllActive().size)
            }
        } finally {
            storage.close()
        }
    }
}
