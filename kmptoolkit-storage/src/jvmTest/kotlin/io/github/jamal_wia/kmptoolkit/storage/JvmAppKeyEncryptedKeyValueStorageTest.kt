package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app-key store held to the shared storage contract (T16), over a raw key, plus the protection it reports. */
class JvmAppKeyEncryptedKeyValueStorageTest : KeyValueStorageContractTest() {

    private val directory: File = Files.createTempDirectory("kmptoolkit-appkey-contract").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    override fun create(config: StorageConfig): KeyValueStorage =
        createAppKeyEncryptedKeyValueStorage(directory, config, AppEncryptionKey.Raw { ByteArray(32) { (it * 3).toByte() } })

    @Test
    fun `the factory builds an APP_PROVIDED_KEY store and a PASSPHRASE store`() {
        val raw: AppKeyEncryptedKeyValueStorage = createAppKeyEncryptedKeyValueStorage(directory, StorageConfig("x"), AppEncryptionKey.Raw { ByteArray(32) })
        val pass: AppKeyEncryptedKeyValueStorage = createAppKeyEncryptedKeyValueStorage(directory, StorageConfig("x"), AppEncryptionKey.Passphrase({ "p".toCharArray() }))

        assertEquals(SecureStorageProtection.APP_PROVIDED_KEY, raw.protection)
        assertEquals(SecureStorageProtection.PASSPHRASE, pass.protection)
        assertTrue(directory.list()!!.none { it.endsWith(".bin") }, "creating a store writes no data file")
    }
}
