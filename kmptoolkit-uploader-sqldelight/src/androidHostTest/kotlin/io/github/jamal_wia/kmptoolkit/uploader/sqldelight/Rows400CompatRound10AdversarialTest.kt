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
 * Round 10 (compatibility with 4.0.0): a queue file written by 4.0.0 holds every text exactly as the item had it (the
 * 4.0.0 store bound the string as it was). This version must read such a file back unchanged, and the rows it writes
 * for ordinary text must be the very text 4.0.0 would have stored, so 4.0.0 can open them. The KDoc of the stored form
 * and docs/kmptoolkit-uploader-sqldelight promise: "every other text is stored as it is, so a row written by an earlier
 * version reads back unchanged".
 */
@RunWith(AndroidJUnit4::class)
class Rows400CompatRound10AdversarialTest {

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

    private val marker2: String = "﷐kmp2﷑"
    private val marker1: String = "﷐kmp1﷑"

    /** Texts an app could have queued under 4.0.0 that the 4.0.0 drivers stored as they were. */
    private val plainTexts: List<Pair<String, String>> = listOf(
        "ascii" to "hello",
        "accents" to "café 日本語 😀",
        "c1-controls" to "a\u0080b\u0081c",
        "starts-with-c1" to "\u0081x\u0080",
        "noncharacters-inside" to "a﷐kmp2﷑b",
        "marker2-prefix" to "${marker2}hello",
        "marker2-alone" to marker2,
        "marker2-with-escape-looking-tail" to "${marker2}\u0081m\u0080\u00810",
        "marker1-prefix" to "${marker1}﷑0041tail",
        "marker1-alone" to marker1,
        "marker-lookalike" to "﷐kmp3﷑x",
        "empty" to "",
    )

    private fun insertRaw(name: String, id: String, texts: Map<String, String?>) {
        val target: File = dbPath(name)
        target.parentFile?.mkdirs()
        // Create the file through the real storage (current schema), then add rows by raw SQL the way 4.0.0 wrote them.
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
    fun `a row written by 4_0_0 reads back unchanged for every text that does not start with the stored form marker`() = runBlocking {
        val problems: MutableList<String> = mutableListOf()
        for ((label, text) in plainTexts.filterNot { it.second.startsWith("\uFDD0") }) {
            val name: String = freshName("plain")
            val id = "row-1"
            insertRaw(
                name, id,
                mapOf("type" to text.ifEmpty { "t" }, "payload" to text, "uniqueKey" to "k$text", "orderingKey" to "o$text", "tag" to text, "lastError" to text),
            )
            val storage: UploaderStorage = open(name)
            val item: UploaderItem? = storage.store.getAllActive().singleOrNull()
            assertNotNull(item, "$label: the row is not listed")
            fun check(column: String, expected: String, actual: String?) {
                if (expected != actual) problems += "$label/$column: stored ${expected.map { it.code.toString(16) }} read ${actual?.map { it.code.toString(16) }}"
            }
            check("payload", text, item.payload)
            check("tag", text, item.tag)
            check("lastError", text, item.lastError)
            check("uniqueKey", "k$text", item.uniqueKey)
            check("orderingKey", "o$text", item.orderingKey)
            if (text.isNotEmpty()) check("type", text, item.type)
            storage.close()
        }
        assertTrue(problems.isEmpty(), "a 4.0.0 row changed on the way out:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `rows this version writes for ordinary text hold the same text a 4_0_0 store would have bound`() = runBlocking {
        val name: String = freshName("write")
        val storage: UploaderStorage = open(name)
        val ordinary: List<String> = plainTexts.map { it.second }.filter { !it.startsWith("﷐") && it.isNotEmpty() }
        ordinary.forEachIndexed { index, text ->
            assertTrue(
                storage.store.insertKeep(
                    UploaderItem(
                        id = "w-$index", type = "t", payload = text, schemaVersion = 1, uniqueKey = "k$text", orderingKey = null, tag = text,
                        state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = index.toLong(), lastError = text,
                    ),
                ),
            )
        }
        storage.close()
        val db: SQLiteDatabase = SQLiteDatabase.openDatabase(dbPath(name).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            ordinary.forEachIndexed { index, text ->
                db.rawQuery("SELECT payload, tag, unique_key, last_error FROM kmptoolkit_uploader_item WHERE id = ?", arrayOf("w-$index")).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(text, c.getString(0), "payload column of '$text'")
                    assertEquals(text, c.getString(1), "tag column of '$text'")
                    assertEquals("k$text", c.getString(2), "unique_key column of '$text'")
                    assertEquals(text, c.getString(3), "last_error column of '$text'")
                }
            }
        } finally {
            db.close()
        }
    }
}
