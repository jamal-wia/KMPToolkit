package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Concurrent first calls to [DeviceIdProvider.current] must agree on one id, and it must be the one
 * that was persisted.
 *
 * The store here suspends inside every call, as the real ones do while they hop to their I/O
 * dispatcher — and on `runTest`'s single thread a suspension is exactly where another coroutine
 * gets in. Without a lock spanning the read and the write, every caller reads "absent" before any
 * of them writes, and each returns an id of its own.
 */
class DeviceIdProviderConcurrencyTest {

    @Test
    fun `concurrent first calls on one provider return the persisted id`() = runTest {
        val storage = SuspendingStorage()
        val provider = DeviceIdProvider(storage)

        val ids: List<String> = (1..CALLERS).map { async { provider.current() } }.awaitAll()

        assertEquals(setOf(storage.stored(DeviceIdProvider.DEFAULT_KEY)), ids.toSet())
        assertEquals(1, storage.writes, "ids written")
    }

    @Test
    fun `concurrent first calls on separate providers over one store return the persisted id`() = runTest {
        val storage = SuspendingStorage()

        val ids: List<String> = (1..CALLERS).map { async { DeviceIdProvider(storage).current() } }.awaitAll()

        assertEquals(setOf(storage.stored(DeviceIdProvider.DEFAULT_KEY)), ids.toSet())
        assertEquals(1, storage.writes, "ids written")
    }

    /** A map-backed store whose every operation suspends before it runs. */
    private class SuspendingStorage : KeyValueStorage {

        private val entries: MutableMap<String, String> = mutableMapOf()

        var writes: Int = 0
            private set

        fun stored(key: String): String? = entries[key]

        override suspend fun get(key: String): StorageResult<String?> {
            delay(HOP_MS)
            return StorageResult.Success(entries[key])
        }

        override suspend fun put(key: String, value: String): StorageResult<Unit> {
            delay(HOP_MS)
            entries[key] = value
            writes += 1
            return StorageResult.Success(Unit)
        }

        override suspend fun remove(key: String): StorageResult<Unit> {
            delay(HOP_MS)
            entries.remove(key)
            return StorageResult.Success(Unit)
        }

        override suspend fun clear(): StorageResult<Unit> {
            delay(HOP_MS)
            entries.clear()
            return StorageResult.Success(Unit)
        }
    }

    private companion object {
        const val CALLERS = 8
        const val HOP_MS = 10L
    }
}
