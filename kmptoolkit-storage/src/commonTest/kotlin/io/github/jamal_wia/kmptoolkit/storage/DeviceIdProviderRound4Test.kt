package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The documented behaviour of [DeviceIdProvider]: clear, blank entries, an unreadable store, an empty key. */
class DeviceIdProviderRound4Test {

    @Test
    fun `clear takes effect at the very next call and a blank stored id is replaced and then stable`() {
        runBlocking {
            val storage = FakeKeyValueStorage()
            val provider = DeviceIdProvider(storage)
            val first: String = provider.current()
            assertEquals(first, provider.current())
            storage.clear()
            val second: String = provider.current()
            assertNotEquals(first, second)
            storage.put(DeviceIdProvider.DEFAULT_KEY, "   ")
            val third: String = provider.current()
            assertTrue(third.isNotBlank() && third != "   ")
            assertEquals(third, provider.current())
            assertEquals(third, storage.entries[DeviceIdProvider.DEFAULT_KEY])
        }
    }

    @Test
    fun `an id read as Unavailable leaves the stored id alone and the stored id is back at the next call`() {
        runBlocking {
            val storage = FakeKeyValueStorage()
            val provider = DeviceIdProvider(storage, key = "")
            val stored: String = provider.current()
            storage.failReadsWith = StorageError.Unavailable()
            val during: String = provider.current()
            assertNotEquals(stored, during)
            storage.failReadsWith = null
            assertEquals(stored, provider.current())
            assertEquals(stored, storage.entries[""])
        }
    }
}
