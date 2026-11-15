package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The iOS snippets of docs/kmptoolkit-storage (plan 6.7): compiled against the real factories, which never touch the Keychain, and
 * checked for what needs no Keychain: the protection each factory reports. The text between the `sample:begin` and `sample:end`
 * lines is the document's, kept equal by `DocsSamplesDriftTest`. Real round trips are the harness's (`storage-ios-harness`).
 */
class IosDocsSamplesCompileTest {

    // sample:begin ios-create-store
    fun makeStorage(): KeyValueStorage = createKeyValueStorage()
    // sample:end ios-create-store

    // sample:begin ios-secure-store
    fun makeSecrets(): SecureKeyValueStorage = createSecureKeyValueStorage()
    // sample:end ios-secure-store

    // sample:begin ios-options
    fun makeWhenUnlockedSecrets(): SecureKeyValueStorage = createSecureKeyValueStorage(
        options = IosSecureStorageOptions(accessibility = KeychainAccessibility.WHEN_UNLOCKED),
    )
    // sample:end ios-options

    // sample:begin migrate-ios-factory
    fun makeSharedSecrets(): SecureKeyValueStorage = createSecureKeyValueStorage(
        StorageConfig("com.example.tokens"),
        IosSecureStorageOptions(
            accessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK,
            accessGroup = "ABCDE12345.com.example.shared",
        ),
    )
    // sample:end migrate-ios-factory

    @Test
    fun `the secure samples build stores that report the iOS protection`() {
        assertEquals(SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED, makeSecrets().protection)
        assertEquals(SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED, makeWhenUnlockedSecrets().protection)
        assertEquals(SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED, makeSharedSecrets().protection)
    }

    @Test
    fun `the plain sample builds a store`() {
        assertIs<KeyValueStorage>(makeStorage())
    }
}
