package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Round 6 (differential Android versus the contract): the SQLDelight store on Android must hand back every text it was
 * given exactly. SQLite drops a leading U+FEFF from a string bound as UTF-16 (the Android driver's path), so a payload that
 * starts with a byte-order mark (a text read from a file saved with one) changes in the queue.
 */
@RunWith(AndroidJUnit4::class)
class LeadingBomRound6RegressionTest {

    private fun item(id: String, payload: String = "p", uniqueKey: String? = null, tag: String? = null, orderingKey: String? = null, type: String = "t") =
        UploaderItem(
            id = id, type = type, payload = payload, schemaVersion = 1, uniqueKey = uniqueKey, orderingKey = orderingKey, tag = tag,
            state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
        )

    @Test
    fun `every text field with a leading byte order mark comes back unchanged`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val bom = "\uFEFF"
            val given: UploaderItem = item("a", payload = bom + "{\"k\":1}", uniqueKey = bom + "key", tag = bom + "tag", orderingKey = bom + "ord", type = bom + "type")
            storage.store.insertKeep(given)
            assertEquals(given, storage.store.getById("a"), "the queue changed a text that starts with U+FEFF")
        } finally {
            storage.close()
        }
    }

    @Test
    fun `two unique keys that differ only by a leading byte order mark are two keys`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            assertEquals(true, storage.store.insertKeep(item("a", uniqueKey = "key")))
            assertEquals(true, storage.store.insertKeep(item("b", uniqueKey = "\uFEFFkey")), "the second key was taken for the first")
        } finally {
            storage.close()
        }
    }
}
