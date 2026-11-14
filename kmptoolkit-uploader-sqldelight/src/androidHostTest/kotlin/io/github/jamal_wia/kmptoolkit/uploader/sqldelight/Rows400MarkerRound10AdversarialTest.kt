package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Round 10, R10-S2 (ruling D-28): a row 4.0.0 wrote with a text that starts with the stored-form marker reads back
 * unchanged unless that text is itself exactly an escaped form, and the row is reached by the id it reads as.
 */
@RunWith(AndroidJUnit4::class)
class Rows400MarkerRound10AdversarialTest {

    private val storages: MutableList<UploaderStorage> = mutableListOf()
    private val names: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        storages.forEach { runCatching { it.close() } }
        names.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    private fun freshName(tag: String): String = "adv.a2r10.$tag.${Random.nextLong(0L, Long.MAX_VALUE)}".also { names += it }

    private fun dbPath(name: String): File = application().getDatabasePath(uploaderDatabaseFileName(name))

    private fun open(name: String): UploaderStorage =
        createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }

    private val marker2: String = "\uFDD0kmp2\uFDD1"
    private val marker1: String = "\uFDD0kmp1\uFDD1"

    private val nonCanonical: List<Pair<String, String>> = listOf(
        "marker2-prefix" to "${marker2}hello",
        "marker2-alone" to marker2,
        "marker1-prefix" to "${marker1}\uFDD10041 tail",
        "marker1-alone" to marker1,
    )

    private fun insertRaw(name: String, id: String, texts: Map<String, String?>) {
        val target: File = dbPath(name)
        target.parentFile?.mkdirs()
        open(name).close()
        val db: SQLiteDatabase = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.execSQL(
                "INSERT INTO kmptoolkit_uploader_item(id, type, payload, schema_version, unique_key, ordering_key, tag, state, attempts, next_run_at, created_at, last_error, lease_until) " +
                    "VALUES (?, ?, ?, 1, ?, ?, ?, 'PENDING', 0, 0, 1, ?, 0)",
                arrayOf<Any?>(id, texts["type"] ?: "t", texts["payload"] ?: "", texts["uniqueKey"], texts["orderingKey"], texts["tag"], texts["lastError"]),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a 4_0_0 row whose texts start with a marker but are no escaped form reads back unchanged in every column`() = runBlocking {
        val problems: MutableList<String> = mutableListOf()
        for ((label, text) in nonCanonical) {
            val name: String = freshName("plain")
            insertRaw(name, "row-1", mapOf("type" to text, "payload" to text, "uniqueKey" to text, "orderingKey" to text, "tag" to text, "lastError" to text))
            val storage: UploaderStorage = open(name)
            val item: UploaderItem? = storage.store.getAllActive().singleOrNull()
            assertNotNull(item, "$label: the row is not listed")
            mapOf("payload" to item.payload, "tag" to item.tag, "lastError" to item.lastError, "uniqueKey" to item.uniqueKey, "orderingKey" to item.orderingKey, "type" to item.type)
                .forEach { (column, read) -> if (read != text) problems += "$label/$column: read ${read?.map { it.code.toString(16) }}" }
            storage.close()
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `a 4_0_0 row whose id starts with a marker is reached by that id and can be settled once`() = runBlocking {
        for ((label, text) in nonCanonical) {
            val name: String = freshName("ids")
            insertRaw(name, text, mapOf("payload" to "p"))
            val storage: UploaderStorage = open(name)
            val listed: UploaderItem? = storage.store.getAllActive().singleOrNull()
            assertEquals(text, listed?.id, "$label: the id of a 4.0.0 row changed on the way out")
            assertEquals(UploaderItemState.PENDING, storage.store.getById(text)?.state, "$label: getById with the id as 4.0.0 stored it")
            assertTrue(storage.store.markInFlight(text, 10L, UploaderItemState.PENDING, 0L), "$label: claim")
            storage.store.deleteById(text)
            assertEquals(emptyList(), storage.store.getAllActive(), "$label: settled row is gone, nothing left to hand out again")
            storage.close()
        }
    }

    @Test
    fun `a 4_0_0 row that is exactly an escaped form reads as the text it encodes and is reached by that text`() = runBlocking {
        val raw: String = marker2 + "\u0081m\u0080\u00810"
        val decoded = "\uFEFF\u0000\u0080"
        val name: String = freshName("canon")
        insertRaw(name, raw, mapOf("payload" to raw))
        val storage: UploaderStorage = open(name)
        val listed: UploaderItem? = storage.store.getAllActive().singleOrNull()
        assertEquals(decoded, listed?.id)
        assertEquals(decoded, listed?.payload)
        assertEquals(UploaderItemState.PENDING, storage.store.getById(decoded)?.state)
        storage.store.deleteById(decoded)
        assertEquals(emptyList(), storage.store.getAllActive())
        storage.close()
    }
}
