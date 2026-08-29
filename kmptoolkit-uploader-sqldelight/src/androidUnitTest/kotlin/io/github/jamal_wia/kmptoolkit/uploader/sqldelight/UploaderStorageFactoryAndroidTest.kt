package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

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
class UploaderStorageFactoryAndroidTest {

    @Test
    fun `the default config opens a file named after the app's package`() = runTest {
        val storage: UploaderStorage = createUploaderStorage(application())
        try {
            storage.store.insertKeep(anItem("a"))
            assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
        } finally {
            storage.close()
        }
        val expected = File(databasesDirectory(), "${application().packageName}.kmptoolkit.uploader.db")
        assertTrue(expected.exists(), "expected the queue at ${expected.path}")
    }

    @Test
    fun `two named configs open two independent queues`() = runTest {
        val chat: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig("com.example.chat"))
        val uploads: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig("com.example.uploads"))
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
        val config = UploaderDatabaseConfig("com.example.durable")
        createUploaderStorage(application(), config).let { storage ->
            storage.store.insertKeep(anItem("a"))
            storage.close()
        }
        createUploaderStorage(application(), config).let { storage ->
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
