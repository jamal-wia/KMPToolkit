package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.testing.InMemoryUploaderStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Round 6 (differential): the same random script of store operations, with hostile text values, runs through the
 * in-memory store of `kmptoolkit-uploader-testing` and through the SQLDelight store, and every observable result must be
 * equal where the `UploaderStore` contract says so: the answer of every call, the active list in order, and every row.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStoreDifferentialRound6AdversarialTest {

    private val texts: List<String?> = listOf(
        "", "a", "A", "\u00E9", "e\u0301", "\uD83D\uDE00", "\u65E5\u672C\u8A9E", "%", "_", "k'", "k\"", "a b", "line\nbreak", "\u2028", "\u200B", "x".repeat(2_000), null,
    )
    private val keys: List<String?> = listOf(null, "k1", "K1", "", "\u00E9", "e\u0301", "%", "k'1")

    private fun item(random: Random, id: String): UploaderItem = UploaderItem(
        id = id,
        type = listOf("t1", "T1", "t2", "")[random.nextInt(4)],
        payload = texts[random.nextInt(texts.size - 1)] ?: "",
        schemaVersion = random.nextInt(0, 3),
        uniqueKey = keys[random.nextInt(keys.size)],
        orderingKey = texts[random.nextInt(texts.size)],
        tag = listOf(null, "x", "X", "\u00E9", "e\u0301", "")[random.nextInt(6)],
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
        for (seed in 0 until 40) {
            val random = Random(6_000 + seed)
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
                            val tag: String = listOf("x", "X", "\u00E9", "e\u0301", "")[random.nextInt(5)]
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
