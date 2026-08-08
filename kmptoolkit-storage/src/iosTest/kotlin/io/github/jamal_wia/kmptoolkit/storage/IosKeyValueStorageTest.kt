package io.github.jamal_wia.kmptoolkit.storage

import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The plain store on iOS, held to the shared contract plus what is specific to `NSUserDefaults`. */
class IosKeyValueStorageTest : KeyValueStorageContractTest() {

    override fun create(config: StorageConfig): KeyValueStorage = createKeyValueStorage(config)

    @Test
    fun `the store is a suite of its own and not the standard defaults`() {
        // The reason this matters is clear(): it is removePersistentDomainForName, which on the
        // standard domain would wipe everything the app and every embedded SDK ever wrote.
        val storage: KeyValueStorage = create()

        storage.put("kmptoolkit.suite.probe", "v")

        assertNull(NSUserDefaults.standardUserDefaults.stringForKey("kmptoolkit.suite.probe"))
    }

    @Test
    fun `the suite name is derived from the store name rather than being it`() {
        // NSUserDefaults(suiteName:) hands back the standard defaults when the name equals the
        // bundle identifier, which would make clear() destructive. The derived suffix is what makes
        // that unreachable.
        assertNotEquals(NAME, plainStoreId(NAME))
    }

    @Test
    fun `clearing the store does not touch a value written to the standard defaults`() {
        NSUserDefaults.standardUserDefaults.setObject("standard", forKey = "kmptoolkit.standard.probe")
        val storage: KeyValueStorage = create()
        storage.put("k", "v")

        storage.clear()

        assertEquals(
            "standard",
            NSUserDefaults.standardUserDefaults.stringForKey("kmptoolkit.standard.probe"),
        )
        NSUserDefaults.standardUserDefaults.removeObjectForKey("kmptoolkit.standard.probe")
    }

    @Test
    fun `the default config opens a store without any name being supplied`() {
        val storage: KeyValueStorage = createKeyValueStorage()

        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
        assertEquals("v", storage.get("k").getOrNull())

        storage.clear()
    }

    @Test
    fun `the store passed to a DeviceIdProvider yields a stable id across instances`() {
        val first: String = DeviceIdProvider(create()).current()

        assertEquals(first, DeviceIdProvider(create()).current())
    }

    @Test
    fun `a device id is regenerated after the store is cleared`() {
        val storage: KeyValueStorage = create()
        val first: String = DeviceIdProvider(storage).current()

        storage.clear()

        assertNotEquals(first, DeviceIdProvider(storage).current())
    }
}
