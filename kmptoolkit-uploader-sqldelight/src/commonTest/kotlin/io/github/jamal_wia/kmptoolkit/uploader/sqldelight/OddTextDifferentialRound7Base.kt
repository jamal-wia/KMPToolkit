package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.testing.InMemoryUploaderStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Round 7 (differential over the texts round 6 had to exclude: BOM, NUL, lone surrogates, escape lookalikes; both platforms): the same random script of store operations, with hostile text values, runs through the
 * in-memory store of `kmptoolkit-uploader-testing` and through the SQLDelight store, and every observable result must be
 * equal where the `UploaderStore` contract says so: the answer of every call, the active list in order, and every row.
 */
abstract class OddTextDifferentialRound7Base {

    private val texts: List<String?> = listOf(
        "", "a", "A", "\u00E9", "e\u0301", "\uD83D\uDE00", "\u65E5\u672C\u8A9E", "%", "_", "k'", "k\"", "a b", "line\nbreak", "\u2028", "\u200B", "x".repeat(2_000), "\uFEFF", "\uFEFFab", "\uFFFEab", "a\u0000b", "\u0000", "a\uD800b", "\uDC00", "a\uD83D", "\uFDD0kmp1\uFDD1", "\uFDD0kmp1\uFDD1\uFDD1" + "0000", "\uFDD1", "\uFDD1" + "0041", "a\uFFFEb", "\uFFFF", null,
    )
    private val oddTags: List<String> = listOf("x", "X", "\u00E9", "e\u0301", "", "\uFEFF", "\uFEFFx", "\u0000", "x\u0000", "\uD800", "\uFFFEx", "\uFDD0kmp1\uFDD1")
    private val tags: List<String?> = listOf<String?>(null) + oddTags
    private val types: List<String> = listOf("t1", "T1", "t2", "", "\uFEFFt", "t\u0000", "\uD800", "\uFFFEt")
    private val keys: List<String?> = listOf(null, "k1", "K1", "", "\u00E9", "e\u0301", "%", "k'1", "\uFEFF", "\uFEFFk1", "k1\u0000", "\u0000", "\uD800", "\uDC00", "\uFDD0kmp1\uFDD1", "\uFDD0kmp1\uFDD1\uFDD10000", "\uFDD1")

    private fun item(random: Random, id: String): UploaderItem = UploaderItem(
        id = id,
        type = types[random.nextInt(types.size)],
        payload = texts[random.nextInt(texts.size - 1)] ?: "",
        schemaVersion = random.nextInt(0, 3),
        uniqueKey = keys[random.nextInt(keys.size)],
        orderingKey = texts[random.nextInt(texts.size)],
        tag = tags[random.nextInt(tags.size)],
        state = if (random.nextInt(8) == 0) UploaderItemState.PARKED else UploaderItemState.PENDING,
        attempts = random.nextInt(0, 5),
        nextRunAtEpochMillis = listOf(0L, 5L, Long.MAX_VALUE, Long.MIN_VALUE, -1L)[random.nextInt(5)],
        createdAtEpochMillis = random.nextLong(0L, 1000L),
        lastError = texts[random.nextInt(texts.size)],
        leaseUntilEpochMillis = listOf(0L, 9L, Long.MAX_VALUE)[random.nextInt(3)],
        claim = random.nextLong(0L, 3L),
    )

    @Test
    fun `random scripts leave both stores with equal answers and equal rows`() = runTest {
        for (seed in 0 until 60) {
            val random = Random(7_000 + seed)
            val storage: UploaderStorage = createInMemoryUploaderStorage()
            val sql: UploaderStore = storage.store
            val mem: UploaderStore = InMemoryUploaderStore()
            val ids: MutableList<String> = mutableListOf()
            val log: MutableList<String> = mutableListOf()
            try {
                repeat(70) { step ->
                    val op: Int = random.nextInt(100)
                    fun same(label: String, a: Any?, b: Any?) {
                        log += "$step $label"
                        assertEquals(a, b, "seed $seed step $step ($label) differs\nscript so far: ${log.takeLast(8)}")
                    }
                    when {
                        op < 30 -> {
                            val record: UploaderItem = item(random, "i${ids.size}").also { ids += it.id }
                            same("insertKeep(${record.id},${record.uniqueKey})", mem.insertKeep(record), sql.insertKeep(record))
                        }
                        op < 45 -> {
                            val record: UploaderItem = item(random, "i${ids.size}").also { ids += it.id }
                            same("insertReplace(${record.id},${record.uniqueKey})", mem.insertReplace(record), sql.insertReplace(record))
                        }
                        op < 80 && ids.isNotEmpty() -> {
                            val id: String = ids[random.nextInt(ids.size)]
                            val current: UploaderItem? = mem.getById(id)
                            val state: UploaderItemState = if (random.nextInt(5) == 0) UploaderItemState.entries[random.nextInt(3)] else current?.state ?: UploaderItemState.PENDING
                            val claim: Long = if (random.nextInt(5) == 0) random.nextLong(0L, 4L) else current?.claim ?: 0L
                            when (random.nextInt(3)) {
                                0 -> same("markInFlight($id)", mem.markInFlight(id, 77L, state, claim), sql.markInFlight(id, 77L, state, claim))
                                1 -> {
                                    val disposition: FailureDisposition = FailureDisposition.entries[random.nextInt(3)]
                                    val err: String? = texts[random.nextInt(texts.size)]
                                    same(
                                        "recordFailure($id,$disposition)",
                                        mem.recordFailure(id, 3, 123L, err, state, claim, disposition),
                                        sql.recordFailure(id, 3, 123L, err, state, claim, disposition),
                                    )
                                }
                                else -> {
                                    val err: String? = texts[random.nextInt(texts.size)]
                                    same("park($id)", mem.park(id, err, state, claim), sql.park(id, err, state, claim))
                                }
                            }
                        }
                        op < 90 -> {
                            val tag: String = oddTags[random.nextInt(oddTags.size)]
                            same("deleteByTag($tag)", mem.deleteByTag(tag), sql.deleteByTag(tag))
                        }
                        op < 97 && ids.isNotEmpty() -> {
                            val id: String = ids[random.nextInt(ids.size)]
                            mem.deleteById(id)
                            sql.deleteById(id)
                            log += "$step deleteById($id)"
                        }
                        op < 98 -> {
                            mem.clearAll()
                            sql.clearAll()
                            log += "$step clearAll"
                        }
                    }
                    same("getAllActive", mem.getAllActive(), sql.getAllActive())
                    ids.forEach { id -> same("getById($id)", mem.getById(id), sql.getById(id)) }
                }
            } finally {
                storage.close()
            }
        }
    }
}
