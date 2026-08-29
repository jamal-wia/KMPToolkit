package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The iOS factory itself — the entry point every consumer actually calls, and the one the shared
 * checks cannot reach because they build a driver directly.
 *
 * A test binary has no `CFBundleIdentifier` of its own, so the default config exercises the
 * documented fallback rather than a real bundle id. That is worth covering precisely because it is
 * the path a test run takes: if it threw, every consumer's first test against a real queue would
 * fail for a reason that has nothing to do with their code.
 */
class UploaderStorageFactoryIosTest {

    private val named = UploaderDatabaseConfig("com.example.factory-${uniqueDatabaseName("ios")}")

    @Test
    fun `the default config opens a usable queue even with no bundle identifier`() = runTest {
        val storage: UploaderStorage = createUploaderStorage()
        try {
            storage.store.clearAll()
            storage.store.insertKeep(anItem("a"))
            assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
            storage.store.clearAll()
        } finally {
            storage.close()
        }
    }

    @Test
    fun `a named queue survives reopening through the factory`() = runTest {
        createUploaderStorage(named).let { storage ->
            storage.store.insertKeep(anItem("a"))
            storage.close()
        }
        createUploaderStorage(named).let { storage ->
            try {
                assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
            } finally {
                storage.close()
            }
        }
    }

    @AfterTest
    fun removeTheNamedQueue() {
        deleteDatabaseFile(uploaderDatabaseFileName(named.name!!))
    }
}
