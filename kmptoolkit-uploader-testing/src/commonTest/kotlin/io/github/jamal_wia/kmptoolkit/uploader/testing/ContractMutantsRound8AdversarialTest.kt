package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.spi.KeepResult
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadAttempt
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Round 8: single-invariant mutants of a custom store; the contract must reject every one. */
class ContractMutantsRound8AdversarialTest {

    private abstract class Mutant(val inner: InMemoryUploaderStore) : UploaderStore by inner

    private fun survivors(mutants: Map<String, () -> UploaderStore>): List<String> = buildList {
        for ((name, factory) in mutants) {
            val outcome: Throwable? = runBlockingContract(factory)
            if (outcome == null) add(name)
        }
    }

    private fun runBlockingContract(factory: () -> UploaderStore): Throwable? {
        var outcome: Throwable? = null
        kotlinx.coroutines.runBlocking {
            outcome = runCatching { UploaderStoreContract(factory).verifyAll() }.exceptionOrNull()
        }
        return outcome
    }

    @Test
    fun `other single-invariant mutants of the store are caught by the contract`() {
        val survivors: List<String> = survivors(
            mapOf(
                "insertKeep dedups on the unique key across types" to {
                    object : Mutant(InMemoryUploaderStore()) {
                        override suspend fun insertKeep(record: UploaderItem): KeepResult {
                            val key: String? = record.uniqueKey
                            if (key != null && inner.snapshot().any { it.uniqueKey == key && it.state != UploaderItemState.PARKED }) return KeepResult.Kept
                            return inner.insertKeep(record)
                        }
                    }
                },
                "unique key compared case-insensitively" to {
                    object : Mutant(InMemoryUploaderStore()) {
                        override suspend fun insertKeep(record: UploaderItem): KeepResult {
                            val key: String? = record.uniqueKey
                            if (key != null && inner.snapshot().any {
                                    it.type == record.type && it.uniqueKey.equals(key, ignoreCase = true) &&
                                        it.state != UploaderItemState.PARKED
                                }
                            ) return KeepResult.Kept
                            return inner.insertKeep(record)
                        }
                    }
                },
                "getById hides parked rows" to {
                    object : Mutant(InMemoryUploaderStore()) {
                        override suspend fun getById(id: String): UploaderItem? =
                            inner.getById(id)?.takeIf { it.state != UploaderItemState.PARKED }
                    }
                },
                "getAllActive includes parked rows" to {
                    object : Mutant(InMemoryUploaderStore()) {
                        override suspend fun getAllActive(): List<UploaderItem> = inner.snapshot()
                    }
                },
                "insertReplace returns the new record, not the superseded one" to {
                    object : Mutant(InMemoryUploaderStore()) {
                        override suspend fun insertReplace(record: UploaderItem): UploaderItem? =
                            inner.insertReplace(record)?.let { record }
                    }
                },
                "park drops the failure text" to {
                    object : Mutant(InMemoryUploaderStore()) {
                        override suspend fun park(id: String, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long): Boolean =
                            inner.park(id, null, expectedState, expectedClaim)
                    }
                },
                "deleteByTag also removes rows whose tag has the given tag as prefix" to {
                    object : Mutant(InMemoryUploaderStore()) {
                        override suspend fun deleteByTag(tag: String): List<UploaderItem> {
                            val extra: List<UploaderItem> = inner.snapshot().filter { it.tag != null && it.tag != tag && it.tag!!.startsWith(tag) }
                            val removed: List<UploaderItem> = inner.deleteByTag(tag)
                            extra.forEach { inner.deleteById(it.id) }
                            return removed + extra
                        }
                    }
                },
            ),
        )
        assertEquals(emptyList(), survivors, "wrong stores the contract let through")
    }
}
