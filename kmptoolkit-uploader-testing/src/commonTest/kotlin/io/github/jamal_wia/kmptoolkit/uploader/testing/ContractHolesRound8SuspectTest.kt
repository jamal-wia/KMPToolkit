package io.github.jamal_wia.kmptoolkit.uploader.testing

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

/** Round 8 SUSPECT R8-S1: the store contract lets wrong stores through (observeByType is not matched exactly). */
class ContractHolesRound8SuspectTest {

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
    fun `observeByType must match the type exactly - prefix case-insensitive wildcard and ignore-type stores are all caught`() {
        val survivors: List<String> = survivors(
            mapOf(
                "observeByType matches by prefix" to {
                    object : Mutant(InMemoryUploaderStore()) {
                        override fun observeByType(type: String): Flow<List<UploaderItem>> =
                            inner.items.map { all -> all.filter { it.type.startsWith(type) } }
                    }
                },
                "observeByType matches by substring" to {
                    object : Mutant(InMemoryUploaderStore()) {
                        override fun observeByType(type: String): Flow<List<UploaderItem>> =
                            inner.items.map { all -> all.filter { type in it.type } }
                    }
                },
                "observeByType ignores case (SQL LIKE)" to {
                    object : Mutant(InMemoryUploaderStore()) {
                        override fun observeByType(type: String): Flow<List<UploaderItem>> =
                            inner.items.map { all -> all.filter { it.type.equals(type, ignoreCase = true) } }
                    }
                },
                "observeByType treats underscore as a wildcard (SQL LIKE)" to {
                    object : Mutant(InMemoryUploaderStore()) {
                        override fun observeByType(type: String): Flow<List<UploaderItem>> =
                            inner.items.map { all ->
                                val regex = Regex(type.map { if (it == '_') "." else Regex.escape(it.toString()) }.joinToString(""))
                                all.filter { regex.matches(it.type) }
                            }
                    }
                },
            ),
        )
        assertEquals(emptyList(), survivors, "wrong stores the contract let through")
    }
}
