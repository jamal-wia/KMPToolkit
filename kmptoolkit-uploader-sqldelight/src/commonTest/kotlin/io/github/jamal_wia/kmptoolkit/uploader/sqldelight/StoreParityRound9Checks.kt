package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 9 (iOS parity of the store contract's corners): idempotent absent-row writes, exact storage of extreme numbers,
 * the order of a superseded key and a revived one, run by both platforms. Earlier rounds ran the extremes on Android only.
 */
internal class StoreParityRound9Checks {

    private fun item(
        id: String,
        type: String = "t",
        uniqueKey: String? = null,
        tag: String? = null,
        state: UploaderItemState = UploaderItemState.PENDING,
        attempts: Int = 0,
        nextRunAt: Long = 0L,
        createdAt: Long = 1L,
        schemaVersion: Int = 1,
        lease: Long = 0L,
        claim: Long = 0L,
        lastError: String? = null,
    ) = UploaderItem(
        id = id, type = type, payload = "payload-$id", schemaVersion = schemaVersion, uniqueKey = uniqueKey, orderingKey = null,
        tag = tag, state = state, attempts = attempts, nextRunAtEpochMillis = nextRunAt, createdAtEpochMillis = createdAt,
        lastError = lastError, leaseUntilEpochMillis = lease, claim = claim,
    )

    suspend fun writesAddressedToAnAbsentRowAreNoOpsAndEveryWriteIsIdempotent() {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val store = storage.store
            store.deleteById("nobody")
            store.deleteById("nobody")
            assertEquals(emptyList(), store.deleteByTag("nothing"))
            store.clearAll()
            store.clearAll()
            assertFalse(store.recordFailure("nobody", 1, 0L, "e", UploaderItemState.PENDING, 0L, FailureDisposition.RETRY))
            assertFalse(store.recordFailure("nobody", 1, 0L, "e", UploaderItemState.PENDING, 0L, FailureDisposition.PARK))
            assertFalse(store.recordFailure("nobody", 1, 0L, "e", UploaderItemState.PENDING, 0L, FailureDisposition.DROP))
            assertFalse(store.markInFlight("nobody", 5L, UploaderItemState.PENDING, 0L))
            assertFalse(store.park("nobody", "x", UploaderItemState.PENDING, 0L))
            assertNull(store.getById("nobody"))

            store.insertKeep(item("a", tag = "x"))
            assertTrue(store.markInFlight("a", 99L, UploaderItemState.PENDING, 0L))
            assertFalse(store.markInFlight("a", 99L, UploaderItemState.PENDING, 0L), "the same claim twice: the second finds the row moved on")
            assertTrue(store.park("a", "first", UploaderItemState.IN_FLIGHT, 1L))
            assertFalse(store.park("a", "second", UploaderItemState.IN_FLIGHT, 1L))
            assertEquals("first", store.getById("a")?.lastError)
            assertEquals(2L, store.getById("a")?.claim)
            assertEquals(listOf("a"), store.deleteByTag("x").map { it.id })
            assertEquals(emptyList(), store.deleteByTag("x"))
            store.deleteById("a")
            assertEquals(emptyList(), store.getAllActive())
        } finally {
            storage.close()
        }
    }

    suspend fun extremeNumbersAreStoredAndReadBackExactlyAndLeasesAreNotRounded() {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val extremes: List<UploaderItem> = listOf(
                item("max", attempts = Int.MAX_VALUE, nextRunAt = Long.MAX_VALUE, createdAt = Long.MAX_VALUE, schemaVersion = Int.MAX_VALUE, claim = Long.MAX_VALUE - 1),
                item("min", attempts = Int.MIN_VALUE, nextRunAt = Long.MIN_VALUE, createdAt = Long.MIN_VALUE, schemaVersion = Int.MIN_VALUE, claim = 0L),
                item("lease", state = UploaderItemState.IN_FLIGHT, lease = Long.MAX_VALUE - 7, claim = 41),
            )
            extremes.forEach { assertTrue(storage.store.insertKeep(it)) }
            assertEquals(extremes, storage.store.getAllActive())
            for (row in extremes) assertEquals(row, storage.store.getById(row.id))
            assertTrue(storage.store.markInFlight("min", Long.MAX_VALUE - 3, UploaderItemState.PENDING, 0L))
            val moved: UploaderItem = assertNotNull(storage.store.getById("min"))
            assertEquals(Long.MAX_VALUE - 3, moved.leaseUntilEpochMillis, "a lease is stored exactly")
            assertEquals(1L, moved.claim)
            assertEquals(Int.MIN_VALUE, moved.attempts, "markInFlight must not touch attempts")
        } finally {
            storage.close()
        }
    }

    suspend fun supersedingAndRevivingMoveARowToTheTailAndReturnWhatWasThere() {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val store = storage.store
            store.insertKeep(item("first", uniqueKey = "k", attempts = 3, claim = 4, lastError = "old"))
            store.insertKeep(item("second"))
            store.insertKeep(item("third", uniqueKey = "other"))
            val superseded: UploaderItem? = store.insertReplace(item("first2", uniqueKey = "k"))
            assertEquals(item("first", uniqueKey = "k", attempts = 3, claim = 4, lastError = "old"), superseded, "exactly what the delete removed")
            assertEquals(listOf("second", "third", "first2"), store.getAllActive().map { it.id })

            assertTrue(store.park("second", "gone", UploaderItemState.PENDING, 0L))
            assertTrue(store.insertKeep(item("again", uniqueKey = "k", type = "t")).not(), "a live holder wins a KEEP")
            store.park("first2", "stop", UploaderItemState.PENDING, 0L)
            assertTrue(store.insertKeep(item("revived", uniqueKey = "k")), "a parked holder is revived by a KEEP")
            assertEquals(listOf("third", "revived"), store.getAllActive().map { it.id }, "parked rows are not active")
            assertNull(store.getById("first2"))
            assertNull(store.insertReplace(item("fresh", uniqueKey = "unused")))
            assertEquals("fresh", store.getAllActive().last().id)
            // the same key under another type is another identity
            assertTrue(store.insertKeep(item("othertype", type = "u", uniqueKey = "k")))
        } finally {
            storage.close()
        }
    }

    suspend fun aRandomScriptEndsEqualToTheInMemoryStoreOnThisPlatform() {
        for (seed in 1..3) {
            val random = Random(seed * 31)
            val real: UploaderStorage = createInMemoryUploaderStorage()
            val model = io.github.jamal_wia.kmptoolkit.uploader.testing.InMemoryUploaderStore()
            try {
                var counter = 0
                repeat(150) {
                    val key: String? = if (random.nextInt(3) == 0) "k${random.nextInt(4)}" else null
                    val tag: String? = if (random.nextBoolean()) "t${random.nextInt(2)}" else null
                    when (random.nextInt(8)) {
                        0, 1 -> {
                            val row = item("i${counter++}", uniqueKey = key, tag = tag, type = if (random.nextBoolean()) "a" else "b")
                            assertEquals(model.insertKeep(row), real.store.insertKeep(row))
                        }
                        2 -> {
                            val row = item("i${counter++}", uniqueKey = key, tag = tag)
                            assertEquals(model.insertReplace(row), real.store.insertReplace(row))
                        }
                        3 -> model.getAllActive().randomOrNull(random)?.let {
                            val lease: Long = random.nextLong(1, 1_000)
                            assertEquals(
                                model.markInFlight(it.id, lease, it.state, it.claim),
                                real.store.markInFlight(it.id, lease, it.state, it.claim),
                            )
                        }
                        4 -> model.getAllActive().randomOrNull(random)?.let {
                            val d = FailureDisposition.entries.random(random)
                            val e = "err${random.nextInt(9)}"
                            assertEquals(
                                model.recordFailure(it.id, it.attempts + 1, 77L, e, it.state, it.claim, d),
                                real.store.recordFailure(it.id, it.attempts + 1, 77L, e, it.state, it.claim, d),
                            )
                        }
                        5 -> model.getAllActive().randomOrNull(random)?.let {
                            assertEquals(model.park(it.id, "p", it.state, it.claim), real.store.park(it.id, "p", it.state, it.claim))
                        }
                        6 -> {
                            val tg = "t${random.nextInt(2)}"
                            assertEquals(model.deleteByTag(tg), real.store.deleteByTag(tg))
                        }
                        else -> model.getAllActive().randomOrNull(random)?.let { model.deleteById(it.id); real.store.deleteById(it.id) }
                    }
                    assertEquals(model.getAllActive(), real.store.getAllActive(), "seed $seed diverged")
                }
            } finally {
                real.close()
            }
        }
    }
}
