package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B round 10, Q2/Q5 (upgrade paths, device id key): everything a store written by 3.0.0 or 4.0.0 is found under is
 * spelled out as a literal here. The strings are the tags' own (`git show 3.0.0:` and `4.0.0:` of StorageConfig.kt and
 * DeviceIdProvider.kt agree on every one of them); a change to any of them orphans the preferences file, the Keychain
 * service, the Keystore alias or the device id of every installed app, and no other test would notice because the
 * others compute the expected names with the same functions they test.
 * Contract: 05-platform-notes "Where your data actually lives" and 04-api-reference "DeviceIdProvider"
 * (`DEFAULT_KEY = "kmptoolkit.storage.device_id"`).
 */
class UpgradeIdentifiersRound10Test {

    @Test
    fun `the identifiers of the plain store the secure store and the key alias are the ones 3_0_0 and 4_0_0 used`() {
        assertEquals("com.example.app.kmptoolkit.storage", plainStoreId("com.example.app"))
        assertEquals("com.example.app.kmptoolkit.securestorage", secureStoreId("com.example.app"))
        assertEquals("com.example.app.kmptoolkit.securestorage.key", secureKeyAlias("com.example.app"))
    }

    @Test
    fun `the default device id key is the one an installed app already stored its id under`() {
        assertEquals("kmptoolkit.storage.device_id", DeviceIdProvider.DEFAULT_KEY)
    }

    @Test
    fun `a name that spells another store's suffix does not collide with it`() {
        // 05-platform-notes: the suffix is always appended, so a name that already ends in a suffix is still its own store.
        val name = "x.kmptoolkit.storage"
        assertEquals("x.kmptoolkit.storage.kmptoolkit.storage", plainStoreId(name))
        assertEquals(false, plainStoreId(name) == plainStoreId("x"))
        assertEquals(false, plainStoreId("x") == secureStoreId("x"))
    }
}
