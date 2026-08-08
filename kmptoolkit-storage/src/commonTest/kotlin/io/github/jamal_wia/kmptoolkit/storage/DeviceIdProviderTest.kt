package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceIdProviderTest {

    @Test
    fun `first call generates an id and persists it under the default key`() {
        val storage = FakeKeyValueStorage()

        val id: String = DeviceIdProvider(storage).current()

        assertTrue(id.isNotBlank())
        assertEquals(id, storage.entries[DeviceIdProvider.DEFAULT_KEY])
    }

    @Test
    fun `repeated calls on one provider return the same id`() {
        val provider = DeviceIdProvider(FakeKeyValueStorage())

        val first: String = provider.current()

        assertEquals(first, provider.current())
    }

    @Test
    fun `a freshly constructed provider over the same storage returns the same id`() {
        val storage = FakeKeyValueStorage()
        val first: String = DeviceIdProvider(storage).current()

        // A new instance over the same backing store — what a process restart looks like.
        assertEquals(first, DeviceIdProvider(storage).current())
    }

    @Test
    fun `the id survives removal of unrelated keys`() {
        val storage = FakeKeyValueStorage()
        val provider = DeviceIdProvider(storage)
        val first: String = provider.current()

        storage.put("something.else", "value")
        storage.remove("something.else")

        assertEquals(first, provider.current())
    }

    @Test
    fun `clearing the storage produces a new id on the next call`() {
        val storage = FakeKeyValueStorage()
        val provider = DeviceIdProvider(storage)
        val first: String = provider.current()

        storage.clear()

        val second: String = provider.current()
        assertNotEquals(first, second)
        assertEquals(second, storage.entries[DeviceIdProvider.DEFAULT_KEY])
    }

    @Test
    fun `two ids generated over independent storages differ`() {
        val first: String = DeviceIdProvider(FakeKeyValueStorage()).current()
        val second: String = DeviceIdProvider(FakeKeyValueStorage()).current()

        assertNotEquals(first, second)
    }

    @Test
    fun `a custom key is where the id is written`() {
        val storage = FakeKeyValueStorage()

        val id: String = DeviceIdProvider(storage, key = "my.device").current()

        assertEquals(id, storage.entries["my.device"])
        assertEquals(null, storage.entries[DeviceIdProvider.DEFAULT_KEY])
    }

    @Test
    fun `an unreadable entry is replaced rather than reported`() {
        val storage = FakeKeyValueStorage()
        storage.entries[DeviceIdProvider.DEFAULT_KEY] = "previously-stored"
        storage.failReadsWith = StorageError.Undecryptable(DeviceIdProvider.DEFAULT_KEY)

        val id: String = DeviceIdProvider(storage).current()

        assertNotEquals("previously-stored", id)
        assertEquals(id, storage.entries[DeviceIdProvider.DEFAULT_KEY])
    }

    @Test
    fun `a blank stored value is treated as no id at all`() {
        val storage = FakeKeyValueStorage()
        storage.entries[DeviceIdProvider.DEFAULT_KEY] = "   "

        val id: String = DeviceIdProvider(storage).current()

        assertTrue(id.isNotBlank())
        assertEquals(id, storage.entries[DeviceIdProvider.DEFAULT_KEY])
    }

    @Test
    fun `an empty stored value is treated as no id at all`() {
        val storage = FakeKeyValueStorage()
        storage.entries[DeviceIdProvider.DEFAULT_KEY] = ""

        val id: String = DeviceIdProvider(storage).current()

        assertTrue(id.isNotBlank())
        assertEquals(id, storage.entries[DeviceIdProvider.DEFAULT_KEY])
    }

    @Test
    fun `an id is still returned when it cannot be persisted`() {
        val storage = FakeKeyValueStorage()
        storage.failWritesWith = StorageError.Unavailable()

        val id: String = DeviceIdProvider(storage).current()

        assertTrue(id.isNotBlank())
        assertEquals(null, storage.entries[DeviceIdProvider.DEFAULT_KEY])
    }

    @Test
    fun `the generated id looks like a random UUID`() {
        val id: String = DeviceIdProvider(FakeKeyValueStorage()).current()

        // 8-4-4-4-12 lowercase hex, version nibble 4. Asserted because the value is sent to servers
        // that parse it, so its shape is part of the contract rather than an implementation detail.
        val pattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        assertTrue(pattern.matches(id), "not a v4 UUID: $id")
    }
}
