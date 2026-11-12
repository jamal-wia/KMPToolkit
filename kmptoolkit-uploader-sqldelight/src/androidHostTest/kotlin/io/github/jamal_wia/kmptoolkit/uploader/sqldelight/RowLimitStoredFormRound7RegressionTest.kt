package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Round 7 (persisted-data fidelity): the documented row limit (1 MiB of UTF-8 text per row, "the same limit on every
 * platform") exists so that a row the queue accepted can always be read back through Android's 2 MiB cursor window.
 * The limit is counted on the text as the consumer gave it; the text is stored in an escaped form that is up to seven
 * times larger for a NUL or a lone surrogate. A row accepted at the limit must still be readable.
 */
@RunWith(AndroidJUnit4::class)
class RowLimitStoredFormRound7RegressionTest {

    private fun item(id: String, payload: String, lastError: String? = null): UploaderItem = UploaderItem(
        id = id, type = "t", payload = payload, schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
        state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = lastError,
    )

    private fun chain(failure: Throwable): List<String> = generateSequence(failure) { it.cause }.map { "${it::class.simpleName}: ${it.message?.take(100)}" }.toList()

    private suspend fun accepted(store: UploaderStore, record: UploaderItem): Boolean = runCatching { store.insertKeep(record) }.getOrDefault(false)

    @Test
    fun `a payload of lone surrogates accepted within the row limit stays readable`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val payload = "\uD800".repeat(340_000) // 3 bytes each as given, 1_020_000 bytes: within the 1 MiB limit
            val store: UploaderStore = storage.store
            assertTrue(accepted(store, item("a", payload)), "the setup is outside the documented limit")
            val all: List<UploaderItem> = runCatching { store.getAllActive() }.getOrElse { throw AssertionError("the queue became unreadable after an accepted row: ${chain(it)}") }
            assertEquals(payload.length, all.single().payload.length)
        } finally {
            storage.close()
        }
    }

    @Test
    fun `a payload of NUL characters accepted within the row limit stays readable`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val payload = "\u0000".repeat(1_000_000) // 1 byte each as given: within the limit
            val store: UploaderStore = storage.store
            assertTrue(accepted(store, item("a", payload)), "the setup is outside the documented limit")
            val all: List<UploaderItem> = runCatching { store.getAllActive() }.getOrElse { throw AssertionError("the queue became unreadable after an accepted row: ${chain(it)}") }
            assertEquals(payload.length, all.single().payload.length)
        } finally {
            storage.close()
        }
    }

    @Test
    fun `a failure text of NUL characters written after the insert keeps the queue readable`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val store: UploaderStore = storage.store
            assertTrue(accepted(store, item("a", "p")))
            runCatching { store.recordFailure("a", 1, 0L, "\u0000".repeat(1_000_000), UploaderItemState.PENDING, 0L, FailureDisposition.RETRY) }
            val all: List<UploaderItem> = runCatching { store.getAllActive() }.getOrElse { throw AssertionError("the queue became unreadable after a failure text: ${chain(it)}") }
            assertEquals(1, all.size)
        } finally {
            storage.close()
        }
    }
}
