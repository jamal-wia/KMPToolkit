package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DesktopOs
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecureTestRig
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.StoreIdentity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * The desktop OS-key store held to the shared storage contract (T16), once per operating system's fake binding, plus the
 * contract case of plan 6.1 for OS-key stores: after `Undecryptable(null)`, `remove` of every key and `clear()` both
 * succeed and `get` then answers `Success(null)`.
 */
internal abstract class JvmSecureKeyValueStorageContract(private val os: DesktopOs) : KeyValueStorageContractTest() {

    protected val rig: SecureTestRig = SecureTestRig(os)

    @AfterTest
    fun closeRig() {
        rig.close()
    }

    override fun create(config: StorageConfig): KeyValueStorage =
        rig.store(name = requireNotNull(config.name), cache = rig.cache)

    @Test
    fun `the protection is reported without I-O and the fake saw no call`() {
        val store: SecureKeyValueStorage = rig.store()
        assertEquals(SecureStorageProtection.OS_KEY_STORE_USER_SCOPED, store.protection)
        assertEquals(0, rig.fake.callNames.size)
        assertTrue(rig.fileNames().none { it.endsWith(".bin") }, "creating a store writes no data file")
    }

    @Test
    fun `after a lost key remove of every key and clear both succeed and get answers null`() = runBlocking {
        val store: SecureKeyValueStorage = rig.store()
        assertTrue(store.put("a", "1").isSuccess)
        assertTrue(store.put("b", "2").isSuccess)
        // The key is lost: the OS item (or DPAPI's ability) is gone and nothing is cached.
        val identity: StoreIdentity = StoreIdentity.of(SecureTestRig.NAME, rig.directory)
        rig.cache.get(identity)?.let { rig.cache.invalidate(identity, it) }
        rig.fake.macItems.clear()
        rig.fake.secretItems.clear()
        val lost: SecureKeyValueStorage = rig.store(cache = io.github.jamal_wia.kmptoolkit.storage.internal.desktop.OsKeyCache())
        if (os == DesktopOs.WINDOWS) return@runBlocking // DPAPI has no item to lose here; covered by the entropy case
        assertEquals(StorageError.Undecryptable(null), assertIs<StorageResult.Failure>(lost.get("a")).error)

        assertTrue(lost.remove("a").isSuccess)
        assertTrue(lost.remove("b").isSuccess)

        assertEquals(StorageResult.Success(null), lost.get("a"))
        assertEquals(StorageResult.Success(null), lost.get("b"))
        assertTrue(lost.clear().isSuccess)
        assertEquals(StorageResult.Success(null), lost.get("a"))
    }
}

internal class JvmSecureKeyValueStorageMacContractTest : JvmSecureKeyValueStorageContract(DesktopOs.MACOS)

internal class JvmSecureKeyValueStorageLinuxContractTest : JvmSecureKeyValueStorageContract(DesktopOs.LINUX)

internal class JvmSecureKeyValueStorageWindowsContractTest : JvmSecureKeyValueStorageContract(DesktopOs.WINDOWS)
