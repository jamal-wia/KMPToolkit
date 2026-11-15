package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 4 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-4), against task/sto-desktop 9dadc3e4:
 * re-entrancy from the app's key provider (plan 4.8: the provider runs OFF the locks, once per operation). A provider is app
 * code and may call the same store instance (a migration that writes a marker when the key is first asked for, a key rotation
 * started from the provider). Because no lock is held while it runs, a nested call must complete, and the outer operation
 * must then decide on the file AS IT IS NOW: a NEW-file key prepared before the nested call created the file must never
 * replace that file (lost update), and a key prepared for a key object the nested call replaced must never wrap the file.
 *
 * Bounded by [HANG_BOUND] (a nested call that waits for a lock the outer call holds would hang: the deadlock class).
 */
class AppKeyStoreReentrancyBreakerR5Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-reentry-b4").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun store(key: AppEncryptionKey): JvmAppKeyEncryptedKeyValueStorage =
        JvmAppKeyEncryptedKeyValueStorage("reentry", directory, key, Dispatchers.IO, CrossProcessLock())

    /**
     * The provider of a raw key, asked for the FIRST put of an absent store, itself puts a marker through the same instance
     * (once). The nested put creates the file; the outer put prepared material for a NEW file. Expected: both entries are in
     * the file afterwards (read by a fresh instance), the outer put succeeded, nothing hung. Red on: the outer create
     * replacing the nested one's file (marker lost) or a deadlock.
     */
    @Test
    fun `a provider that writes through the same instance during the first put loses neither write`(): Unit = runBlocking<Unit> {
        withTimeout(HANG_BOUND) {
            val instance = AtomicReference<JvmAppKeyEncryptedKeyValueStorage>()
            val asked = AtomicInteger()
            val nested = AtomicReference<StorageResult<Unit>>()
            val key = AppEncryptionKey.Raw {
                if (asked.incrementAndGet() == 1) nested.set(runBlocking { instance.get().put("marker", "set by the provider") })
                ByteArray(32) { 4 }
            }
            instance.set(store(key))

            assertEquals(StorageResult.Success(Unit), instance.get().put("outer", "value"), "the outer put")
            assertEquals(StorageResult.Success(Unit), nested.get(), "the nested put from inside the provider")
            val reader = store(AppEncryptionKey.Raw { ByteArray(32) { 4 } })
            assertEquals(StorageResult.Success("set by the provider"), reader.get("marker"), "the nested write was lost")
            assertEquals(StorageResult.Success("value"), reader.get("outer"), "the outer write was lost")
        }
    }

    /**
     * The provider of the bound raw key, asked for the first put of an absent store, re-keys the same instance to another raw
     * key (`changeKey` on an absent file only re-binds). The outer put must not wrap the new file with the OLD key it was
     * preparing: afterwards the file opens with the NEW key and holds the outer value, and the old key is `AccessDenied`.
     */
    @Test
    fun `a provider that re-keys the same instance during the first put never leaves a file under the replaced key`(): Unit = runBlocking<Unit> {
        withTimeout(HANG_BOUND) {
            val instance = AtomicReference<JvmAppKeyEncryptedKeyValueStorage>()
            val newKey = AppEncryptionKey.Raw { ByteArray(32) { 6 } }
            val nested = AtomicReference<StorageResult<Unit>>()
            val asked = AtomicInteger()
            val oldKey = AppEncryptionKey.Raw {
                if (asked.incrementAndGet() == 1) nested.set(runBlocking { instance.get().changeKey(newKey) })
                ByteArray(32) { 5 }
            }
            instance.set(store(oldKey))

            assertEquals(StorageResult.Success(Unit), instance.get().put("outer", "value"), "the outer put")
            assertEquals(StorageResult.Success(Unit), nested.get(), "the nested changeKey")
            assertEquals(StorageResult.Success("value"), store(AppEncryptionKey.Raw { ByteArray(32) { 6 } }).get("outer"), "the file is not under the new key")
            assertIs<StorageError.AccessDenied>(assertIs<StorageResult.Failure>(store(AppEncryptionKey.Raw { ByteArray(32) { 5 } }).get("outer")).error, "the replaced key still opens the file")
        }
    }

    /**
     * An existing file: the provider, asked for the key of a cold `get`, removes the very entry through the same instance
     * (its own nested call asks the provider again: a second, non-reentrant answer). The outer get must then read the file
     * as it is now (absent), not the value it would have read before the nested remove.
     */
    @Test
    fun `a provider that removes the entry through the same instance during a cold get makes the get read the file as it is now`(): Unit = runBlocking<Unit> {
        withTimeout(HANG_BOUND) {
            assertEquals(StorageResult.Success(Unit), store(AppEncryptionKey.Raw { ByteArray(32) { 2 } }).put("k", "v"), "control")
            val instance = AtomicReference<JvmAppKeyEncryptedKeyValueStorage>()
            val asked = AtomicInteger()
            val nested = AtomicReference<StorageResult<Unit>>()
            val key = AppEncryptionKey.Raw {
                if (asked.incrementAndGet() == 1) nested.set(runBlocking { instance.get().remove("k") })
                ByteArray(32) { 2 }
            }
            instance.set(store(key))

            assertEquals(StorageResult.Success(null), instance.get().get("k"), "the outer get answered a value removed before it decided")
            assertEquals(StorageResult.Success(Unit), nested.get(), "the nested remove")
        }
    }

    private companion object {
        val HANG_BOUND = 60.seconds
    }
}
