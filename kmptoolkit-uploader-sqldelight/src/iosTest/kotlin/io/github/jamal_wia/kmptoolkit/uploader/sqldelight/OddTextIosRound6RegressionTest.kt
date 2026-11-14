package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Round 6: which texts the SQLDelight store on Android returns unchanged. One probe per text (iOS driver: the same corpus as the Android one), all reported together. */
class OddTextIosRound6RegressionTest {

    private val corpus: Map<String, String> = linkedMapOf(
        "leading BOM" to "\uFEFFab",
        "NUL inside" to "a\u0000b",
        "NUL trailing" to "ab\u0000",
        "lone high surrogate" to "a\uD800b",
        "lone low surrogate" to "a\uDC00b",
        "trailing lone high surrogate" to "ab\uD800",
        "U+FFFE" to "a￾b",
        "U+FFFF" to "a￿b",
        "BOM inside" to "a﻿b",
        "reversed BOM U+FFFE leading" to "￾ab",
        "replacement char" to "a�b",
        "CRLF" to "a\r\nb",
        "4-byte" to "😀",
        "empty" to "",
        "spaces only" to "   ",
        "long ascii" to "x".repeat(300_000),
    )

    @Test
    fun `every text comes back unchanged through payload and last error`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        val changed: MutableList<String> = mutableListOf()
        try {
            corpus.entries.forEachIndexed { index, (label, text) ->
                val id = "i$index"
                val given = UploaderItem(
                    id = id, type = "t", payload = text, schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
                    state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
                )
                runCatching { storage.store.insertKeep(given) }.onFailure { changed += "$label: insert threw $it"; return@forEachIndexed }
                val back: UploaderItem? = storage.store.getById(id)
                if (back?.payload != text) changed += "$label: payload came back as ${back?.payload?.map { it.code.toString(16) }?.take(12)}"
                if (storage.store.recordFailure(id, 1, 0L, text, UploaderItemState.PENDING, 0L)) {
                    val failed: UploaderItem? = storage.store.getById(id)
                    if (failed?.lastError != text) changed += "$label: lastError came back as ${failed?.lastError?.map { it.code.toString(16) }?.take(12)}"
                }
            }
        } finally {
            storage.close()
        }
        assertTrue(changed.isEmpty(), "texts the queue changed: $changed")
    }
}
