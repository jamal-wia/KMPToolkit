package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Round 4: a queue file written by the release before the claim column existed is opened by the current one,
 * through both factory configurations. An upgrade is not corruption: no row may be lost, whichever way the
 * corruption handling is configured, and the rows keep their state, lease and order.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStorageUpgradeRound4AdversarialTest {

    private val storages: MutableList<UploaderStorage> = mutableListOf()
    private val names: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        storages.forEach { runCatching { it.close() } }
        names.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    private fun writeVersionOneFile(name: String) {
        val file = application().getDatabasePath(uploaderDatabaseFileName(name))
        file.parentFile?.mkdirs()
        val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                "CREATE TABLE kmptoolkit_uploader_item (" +
                    "sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, type TEXT NOT NULL, " +
                    "payload TEXT NOT NULL, schema_version INTEGER NOT NULL, unique_key TEXT, ordering_key TEXT, tag TEXT, " +
                    "state TEXT NOT NULL, attempts INTEGER NOT NULL, next_run_at INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                    "last_error TEXT, lease_until INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL("CREATE UNIQUE INDEX kmptoolkit_uploader_item_identity ON kmptoolkit_uploader_item(type, unique_key) WHERE unique_key IS NOT NULL")
            db.execSQL("CREATE INDEX kmptoolkit_uploader_item_state ON kmptoolkit_uploader_item(state, sequence)")
            db.execSQL("CREATE INDEX kmptoolkit_uploader_item_type ON kmptoolkit_uploader_item(type, sequence)")
            db.execSQL("CREATE INDEX kmptoolkit_uploader_item_tag ON kmptoolkit_uploader_item(tag)")
            fun row(id: String, state: String, attempts: Int, lease: Long, tag: String?, key: String?) = db.execSQL(
                "INSERT INTO kmptoolkit_uploader_item(id, type, payload, schema_version, unique_key, ordering_key, tag, state, attempts, next_run_at, created_at, last_error, lease_until) " +
                    "VALUES (?, 'old.type', ?, 1, ?, NULL, ?, ?, ?, 0, 5, ?, ?)",
                arrayOf<Any?>(id, "payload-$id", key, tag, state, attempts, if (state == "PARKED") "was parked" else null, lease),
            )
            row("pending", "PENDING", 2, 0L, "acct", "k1")
            row("inflight", "IN_FLIGHT", 0, 9_000_000_000_000L, "acct", null)
            row("parked", "PARKED", 7, 0L, null, null)
            db.version = 1
        } finally {
            db.close()
        }
    }

    private fun upgrade(recreateOnCorruption: Boolean) = runTest {
        val name = "adv.a1r4.upgrade.${Random.nextLong(0L, Long.MAX_VALUE)}"
        names += name
        writeVersionOneFile(name)

        val storage: UploaderStorage =
            createUploaderStorage(application(), UploaderDatabaseConfig(name, recreateOnCorruption)).also { storages += it }
        val store = storage.store

        assertEquals(listOf("pending", "inflight"), store.getAllActive().map { it.id }, "rows lost or reordered by the upgrade")
        val pending = store.getById("pending")!!
        assertEquals(0L, pending.claim, "an upgraded row starts at claim 0")
        assertEquals(2, pending.attempts)
        assertEquals("k1", pending.uniqueKey)
        val inFlight = store.getById("inflight")!!
        assertEquals(UploaderItemState.IN_FLIGHT, inFlight.state)
        assertEquals(9_000_000_000_000L, inFlight.leaseUntilEpochMillis, "the lease of an in-flight row survived")
        assertEquals(UploaderItemState.PARKED, store.getById("parked")?.state)
        assertEquals("was parked", store.getById("parked")?.lastError)

        // the guarded writes work on an upgraded row, and a new insert enters after the old ones
        assertEquals(true, store.markInFlight("pending", 1L, UploaderItemState.PENDING, 0L))
        assertEquals(1L, store.getById("pending")?.claim)
        assertEquals(true, store.insertKeep(io.github.jamal_wia.kmptoolkit.uploader.UploaderItem(
            id = "new", type = "old.type", payload = "n", schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
            state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
        )).inserted)
        assertEquals(listOf("pending", "inflight", "new"), store.getAllActive().map { it.id })
        assertEquals(listOf("acct"), store.deleteByTag("acct").map { it.tag }.distinct())
    }

    @Test
    fun `a version one file is upgraded without losing a row, default configuration`() = upgrade(recreateOnCorruption = false)

    @Test
    fun `a version one file is upgraded without losing a row, recreate on corruption configured`() = upgrade(recreateOnCorruption = true)
}
