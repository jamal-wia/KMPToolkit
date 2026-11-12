package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Invariants of the `UploaderStore` KDoc that the published contract suite does not check (found by mutating a
 * reference store, see the mutation test), asserted here directly so that every real store can be held to them.
 */
internal object StrongStoreProbeRound4 {

    fun item(
        id: String,
        type: String = "probe.type",
        payload: String = "payload-$id",
        uniqueKey: String? = null,
        orderingKey: String? = null,
        tag: String? = null,
        attempts: Int = 0,
        nextRun: Long = 0L,
        createdAt: Long = 0L,
        schemaVersion: Int = 1,
    ): UploaderItem = UploaderItem(
        id = id,
        type = type,
        payload = payload,
        schemaVersion = schemaVersion,
        uniqueKey = uniqueKey,
        orderingKey = orderingKey,
        tag = tag,
        state = UploaderItemState.PENDING,
        attempts = attempts,
        nextRunAtEpochMillis = nextRun,
        createdAtEpochMillis = createdAt,
        lastError = null,
    )

    suspend fun tagsAreMatchedExactly(store: UploaderStore) {
        val tags: Map<String, String?> = linkedMapOf(
            "t1" to "acct", "t2" to "acct2", "t3" to "Acct", "t4" to "acct%", "t5" to "acct_", "t6" to "",
            "t7" to null, "t8" to "acc", "t9" to " acct", "t10" to "a\\b", "t11" to "a\\\\b", "t12" to "acct\u0000x",
        )
        tags.forEach { (id, tag) -> store.insertKeep(item(id, tag = tag)) }

        assertEquals(listOf("t1"), store.deleteByTag("acct").map { it.id }, "tag 'acct' must match only itself")
        assertEquals(listOf("t6"), store.deleteByTag("").map { it.id }, "the empty tag must match only the empty tag")
        assertEquals(listOf("t4"), store.deleteByTag("acct%").map { it.id }, "a percent sign is not a wildcard")
        assertEquals(listOf("t5"), store.deleteByTag("acct_").map { it.id }, "an underscore is not a wildcard")
        assertEquals(listOf("t10"), store.deleteByTag("a\\b").map { it.id }, "a backslash is not an escape")
        assertEquals(listOf("t12"), store.deleteByTag("acct\u0000x").map { it.id }, "a NUL inside a tag is part of it")
        assertEquals(
            listOf("t2", "t3", "t7", "t8", "t9", "t11"),
            store.getAllActive().map { it.id },
            "every other tag must survive",
        )
    }

    suspend fun uniqueKeysAreMatchedExactly(store: UploaderStore) {
        val keys: List<String> = listOf("K", "k", "K ", "K%", "K_", "é", "é", "K\u0000", "")
        keys.forEachIndexed { index, key ->
            assertEquals(true, store.insertKeep(item("k$index", uniqueKey = key)).inserted, "key '$key' was taken for another key")
        }
        assertEquals(false, store.insertKeep(item("dup", uniqueKey = "K")).inserted, "an identical key must conflict")
        assertEquals(keys.size, store.getAllActive().size)
    }

    suspend fun payloadsComeBackVerbatim(store: UploaderStore) {
        val payloads: List<String> = listOf(
            "  lead and trail \r\n\t",
            "MiXeD CaSe",
            "é vs é",
            "😀 emoji",
            "line1\nline2\r\nline3",
            "nul\u0000inside",
            "x".repeat(300_000),
            "'; DROP TABLE kmptoolkit_uploader_item; --",
            "",
        )
        payloads.forEachIndexed { index, payload ->
            store.insertKeep(item("p$index", payload = payload))
            assertEquals(payload, store.getById("p$index")?.payload, "payload #$index changed on the way through")
        }
        assertEquals(payloads, store.getAllActive().map { it.payload }, "getAllActive changed a payload")
    }

    suspend fun everyGuardedWriteLeavesTheOtherFieldsAlone(store: UploaderStore) {
        val original: UploaderItem = item(
            "a", payload = "PL", uniqueKey = "U", orderingKey = "O", tag = "T", createdAt = 77L, schemaVersion = 3,
        )
        store.insertKeep(original)

        assertEquals(true, store.markInFlight("a", 500L, UploaderItemState.PENDING, 0L))
        assertEquals(
            original.copy(state = UploaderItemState.IN_FLIGHT, leaseUntilEpochMillis = 500L, claim = 1L),
            store.getById("a"),
            "markInFlight changed more than state, lease and claim",
        )
        assertEquals(true, store.recordFailure("a", 4, 99L, "err", UploaderItemState.IN_FLIGHT, 1L, FailureDisposition.RETRY))
        assertEquals(
            original.copy(attempts = 4, nextRunAtEpochMillis = 99L, lastError = "err", claim = 2L),
            store.getById("a"),
            "recordFailure changed more than state, attempts, gate, error, lease and claim",
        )
        assertEquals(true, store.markInFlight("a", 600L, UploaderItemState.PENDING, 2L))
        assertEquals(
            original.copy(
                state = UploaderItemState.IN_FLIGHT, attempts = 4, nextRunAtEpochMillis = 99L, lastError = "err",
                leaseUntilEpochMillis = 600L, claim = 3L,
            ),
            store.getById("a"),
            "a second markInFlight changed the last error, the gate or the attempts",
        )
        assertEquals(true, store.park("a", "why", UploaderItemState.IN_FLIGHT, 3L))
        assertEquals(
            original.copy(
                state = UploaderItemState.PARKED, attempts = 4, nextRunAtEpochMillis = 99L, lastError = "why", claim = 4L,
            ),
            store.getById("a"),
            "park changed more than state, error, lease and claim",
        )
    }

    suspend fun attemptsAndGatesAreStoredAbsolutely(store: UploaderStore) {
        store.insertKeep(item("a", attempts = 3, nextRun = 5_000L))
        assertEquals(true, store.recordFailure("a", 1, 100L, "e", UploaderItemState.PENDING, 0L, FailureDisposition.RETRY))
        val stored: UploaderItem? = store.getById("a")
        assertEquals(1, stored?.attempts, "attempts are the absolute value passed in")
        assertEquals(100L, stored?.nextRunAtEpochMillis, "the gate is the value passed in, even an earlier one")
        assertEquals(true, store.markInFlight("a", 10L, UploaderItemState.PENDING, 1L))
        assertEquals(true, store.markInFlight("a", 5L, UploaderItemState.IN_FLIGHT, 2L))
        assertEquals(5L, store.getById("a")?.leaseUntilEpochMillis, "the lease is the value passed in, even an earlier one")
    }

    suspend fun observationIsLiveAndNeverCompletes(store: UploaderStore): Unit = coroutineScope {
        store.insertKeep(item("o1", type = "observed"))
        val seenTwo = async(Dispatchers.Default) {
            withTimeout(10.seconds) { store.observeByType("observed").first { it.size == 2 } }
        }
        withContext(Dispatchers.Default) { delay(300.milliseconds) }
        store.insertKeep(item("o2", type = "observed"))
        assertEquals(listOf("o1", "o2"), seenTwo.await().map { it.id }, "a change after the first emission was not observed")

        val seenParkedAndInFlight = async(Dispatchers.Default) {
            withTimeout(10.seconds) {
                store.observeByType("observed").first { rows ->
                    rows.map { it.state } == listOf(UploaderItemState.IN_FLIGHT, UploaderItemState.PENDING)
                }
            }
        }
        withContext(Dispatchers.Default) { delay(300.milliseconds) }
        store.markInFlight("o1", 1L, UploaderItemState.PENDING, 0L)
        assertEquals(listOf("o1", "o2"), seenParkedAndInFlight.await().map { it.id }, "an in-flight item must be observable")
    }

    suspend fun insertKeepOverAParkedItemEntersAtTheTail(store: UploaderStore) {
        store.insertKeep(item("a", uniqueKey = "k"))
        store.insertKeep(item("b"))
        store.park("a", "x", UploaderItemState.PENDING, 0L)
        assertEquals(true, store.insertKeep(item("c", uniqueKey = "k")).inserted)
        assertEquals(listOf("b", "c"), store.getAllActive().map { it.id })
        assertNull(store.getById("a"), "the parked item must be gone")
    }

    suspend fun pointReadsAreExact(store: UploaderStore) {
        store.insertKeep(item("item-1"))
        store.insertKeep(item("item-10"))
        store.deleteById("item-1")
        assertNull(store.getById("item-1"), "getById matched another id by prefix")
        assertNull(store.getById("item"))
        assertNull(store.getById("ITEM-10"))
        assertNull(store.getById("item-10%"))
        assertEquals("item-10", store.getById("item-10")?.id)
    }

    suspend fun aReadListIsASnapshot(store: UploaderStore) {
        store.insertKeep(item("a"))
        val before: List<UploaderItem> = store.getAllActive()
        store.insertKeep(item("b"))
        store.deleteById("a")
        assertEquals(listOf("a"), before.map { it.id }, "a list already handed out changed afterwards")
    }

    suspend fun all(newStore: () -> UploaderStore) {
        tagsAreMatchedExactly(newStore())
        uniqueKeysAreMatchedExactly(newStore())
        payloadsComeBackVerbatim(newStore())
        everyGuardedWriteLeavesTheOtherFieldsAlone(newStore())
        attemptsAndGatesAreStoredAbsolutely(newStore())
        observationIsLiveAndNeverCompletes(newStore())
        insertKeepOverAParkedItemEntersAtTheTail(newStore())
        pointReadsAreExact(newStore())
        aReadListIsASnapshot(newStore())
    }
}
