package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * The Android factory itself — the entry point every consumer actually calls, and the one the
 * shared checks cannot reach because they build a driver directly.
 *
 * What is only testable here: that the default name really resolves from the app's package, that
 * the file lands where the platform notes say it does, and that two configs open two files rather
 * than quietly sharing one.
 */
@RunWith(AndroidJUnit4::class)
class OutboxStorageFactoryAndroidTest {

    @Test
    fun `the default config opens a file named after the app's package`() = runTest {
        val storage: OutboxStorage = createOutboxStorage(application())
        try {
            storage.store.insertKeep(anItem("a"))
            assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
        } finally {
            storage.close()
        }
        val expected = File(databasesDirectory(), "${application().packageName}.kmptoolkit.outbox.db")
        assertTrue(expected.exists(), "expected the queue at ${expected.path}")
    }

    @Test
    fun `two named configs open two independent queues`() = runTest {
        val chat: OutboxStorage = createOutboxStorage(application(), OutboxDatabaseConfig("com.example.chat"))
        val uploads: OutboxStorage = createOutboxStorage(application(), OutboxDatabaseConfig("com.example.uploads"))
        try {
            chat.store.insertKeep(anItem("in-chat"))
            assertEquals(
                emptyList(),
                uploads.store.getAllActive().map { it.id },
                "a differently named queue must not see the first one's rows",
            )
            assertEquals(listOf("in-chat"), chat.store.getAllActive().map { it.id })
        } finally {
            chat.close()
            uploads.close()
        }
    }

    @Test
    fun `a queue written through the factory survives reopening through it`() = runTest {
        val config = OutboxDatabaseConfig("com.example.durable")
        createOutboxStorage(application(), config).let { storage ->
            storage.store.insertKeep(anItem("a"))
            storage.close()
        }
        createOutboxStorage(application(), config).let { storage ->
            try {
                assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
            } finally {
                storage.close()
            }
        }
    }

    private fun databasesDirectory(): File =
        File(application().applicationInfo.dataDir, "databases")
}
