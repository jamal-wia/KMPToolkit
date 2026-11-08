package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [DeviceIdProvider.current] is documented to always return an id, "because a header that must be
 * sent cannot wait for storage to recover". Read against a store that never answers, and against a
 * second provider over a healthy, unrelated store.
 */
class DeviceIdProviderIsolationRound4Test {

    /** A store whose reads never complete until [release] is completed. */
    private class HungStore : KeyValueStorage {
        val release = CompletableDeferred<Unit>()
        override suspend fun get(key: String): StorageResult<String?> {
            release.await()
            return StorageResult.Success(null)
        }

        override suspend fun put(key: String, value: String): StorageResult<Unit> = StorageResult.Success(Unit)
        override suspend fun remove(key: String): StorageResult<Unit> = StorageResult.Success(Unit)
        override suspend fun clear(): StorageResult<Unit> = StorageResult.Success(Unit)
    }

    @Test
    fun `a provider over a healthy store answers while another provider over an unrelated store is hung`() {
        val hung = HungStore()
        val healthy = FakeKeyValueStorage()
        val answer: String? = runBlocking {
            withContext(Dispatchers.Default) {
                val stuck = async(start = CoroutineStart.DEFAULT) { DeviceIdProvider(hung).current() }
                try {
                    withTimeoutOrNull(1_000) { } // let the first call take the lock
                    kotlinx.coroutines.delay(300)
                    withTimeoutOrNull(2_000) { DeviceIdProvider(healthy).current() }
                } finally {
                    hung.release.complete(Unit)
                    stuck.await()
                }
            }
        }
        assertTrue(answer != null, "a provider over a healthy store waited for an unrelated hung store")
    }

    /** A store that holds no id and whose writes never complete until [release] is completed. */
    private class HungPutStore : KeyValueStorage {
        val release = CompletableDeferred<Unit>()
        override suspend fun get(key: String): StorageResult<String?> = StorageResult.Success(null)
        override suspend fun put(key: String, value: String): StorageResult<Unit> {
            release.await()
            return StorageResult.Success(Unit)
        }

        override suspend fun remove(key: String): StorageResult<Unit> = StorageResult.Success(Unit)
        override suspend fun clear(): StorageResult<Unit> = StorageResult.Success(Unit)
    }

    @Test
    fun `a stored id is answered without waiting for an id being created for another store`() {
        val hung = HungPutStore()
        val withId = FakeKeyValueStorage()
        val answer: String? = runBlocking {
            withContext(Dispatchers.Default) {
                val creating = async { DeviceIdProvider(hung).current() }
                try {
                    withContext(Dispatchers.Default) { kotlinx.coroutines.delay(300) }
                    withId.put(DeviceIdProvider.DEFAULT_KEY, "stored-id")
                    withTimeoutOrNull(2_000) { DeviceIdProvider(withId).current() }
                } finally {
                    hung.release.complete(Unit)
                    creating.await()
                }
            }
        }
        assertEquals("stored-id", answer, "a call that finds its id waited for an unrelated id creation")
    }
}
