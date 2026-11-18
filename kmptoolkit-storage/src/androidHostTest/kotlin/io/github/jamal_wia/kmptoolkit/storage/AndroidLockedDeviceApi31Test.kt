package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.security.Security
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A locked device on API 31 and 32, where the platform reports it as a plain `InvalidKeyException` around a
 * `KeyStoreException` and nothing public reads its code ([AndroidLockedDeviceFailureTest] has the predicates). For a store
 * whose key needs the device unlocked ([AndroidSecureStorageOptions.unlockedDeviceRequired]) the cipher marks that shape,
 * so every call answers [StorageError.Unavailable] (or [StorageError.AccessDenied] with user authentication), the key is
 * kept and never looked up again; for a store without the option the answers are exactly what they were.
 *
 * The refusal is the one the reviewer's [AndroidLockedDeviceBreakerR4Test] fixture produces ([LockShape.KEYSTORE2_DEVICE_LOCKED]).
 * The same tests run on every level this path is for, one subclass each.
 */
@RunWith(RobolectricTestRunner::class)
abstract class AndroidLockedDeviceApi31Base {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var sequence: Int = 0

    @Before
    fun install() {
        if (Security.getProvider(LockedDeviceGcmProvider.name) == null) Security.insertProviderAt(LockedDeviceGcmProvider, 1)
    }

    @After
    fun uninstall() {
        Security.removeProvider(LockedDeviceGcmProvider.name)
    }

    private class FiledKeyStore(val key: SecretKey) : AliasedKeyStore {
        val lookups: AtomicInteger = AtomicInteger()
        val deletions: AtomicInteger = AtomicInteger()
        val generations: AtomicInteger = AtomicInteger()

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey {
            lookups.incrementAndGet()
            return key
        }

        override fun generateKey(alias: String): SecretKey {
            generations.incrementAndGet()
            return key
        }

        override fun deleteKey(alias: String) {
            deletions.incrementAndGet()
        }
    }

    private class Fixture(val storage: AndroidSecureKeyValueStorage, val keyStore: FiledKeyStore, val lock: DeviceLock)

    private fun fixture(unlockedDeviceRequired: Boolean, userAuthentication: Boolean = false): Fixture {
        val lock = DeviceLock(LockShape.KEYSTORE2_DEVICE_LOCKED)
        val keyStore = FiledKeyStore(LockedDeviceKey(TestKeys.generate(), lock))
        val options = AndroidSecureStorageOptions(
            unlockedDeviceRequired = unlockedDeviceRequired,
            userAuthentication = if (userAuthentication) AndroidUserAuthentication(validitySeconds = 30) else null,
        )
        val alias = "api31.locked.${sequence++}.${System.identityHashCode(this)}"
        val preferences: SharedPreferences = context.getSharedPreferences(alias, Context.MODE_PRIVATE)
        val storage = AndroidSecureKeyValueStorage(
            preferences,
            KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore, options)),
            Dispatchers.Unconfined,
        )
        return Fixture(storage, keyStore, lock)
    }

    @Test
    fun `a locked device is Unavailable for a read, a write and ensureKey, keeps the key, and the value reads back after the unlock`() = runTest {
        val f: Fixture = fixture(unlockedDeviceRequired = true)
        assertEquals(StorageResult.Success(Unit), f.storage.put("token", "t1"), "control: unlocked, the store works")
        f.lock.locked = true

        assertIs<StorageError.Unavailable>(f.storage.get("token").errorOrNull())
        assertIs<StorageError.Unavailable>(f.storage.put("token", "t2").errorOrNull())
        assertIs<StorageError.Unavailable>(f.storage.ensureKey().errorOrNull())

        assertEquals(1, f.keyStore.lookups.get(), "a locked device never drops the cached key, so no second lookup")
        assertEquals(0, f.keyStore.deletions.get())
        assertEquals(0, f.keyStore.generations.get())
        f.lock.locked = false
        assertEquals(StorageResult.Success("t1"), f.storage.get("token"), "the value was never lost")
        assertEquals(StorageResult.Success(Unit), f.storage.put("token", "t3"))
        assertEquals(StorageResult.Success("t3"), f.storage.get("token"))
    }

    @Test
    fun `with user authentication as well the locked device answers AccessDenied`() = runTest {
        val f: Fixture = fixture(unlockedDeviceRequired = true, userAuthentication = true)
        assertEquals(StorageResult.Success(Unit), f.storage.put("token", "t1"), "control: unlocked, the store works")
        f.lock.locked = true

        assertIs<StorageError.AccessDenied>(f.storage.get("token").errorOrNull())
        assertIs<StorageError.AccessDenied>(f.storage.put("token", "t2").errorOrNull())
        assertEquals(1, f.keyStore.lookups.get())
        f.lock.locked = false
        assertEquals(StorageResult.Success("t1"), f.storage.get("token"))
    }

    @Test
    fun `a store that does not need the device unlocked does not take the shape for a locked device`() = runTest {
        val f: Fixture = fixture(unlockedDeviceRequired = false)
        assertEquals(StorageResult.Success(Unit), f.storage.put("token", "t1"), "control: unlocked, the store works")
        f.lock.locked = true

        // The key never needed the device unlocked, so a refusal in this shape is not about a lock: it keeps its old answers.
        assertIs<StorageError.Undecryptable>(f.storage.get("token").errorOrNull())
        assertIs<StorageError.OperationFailed>(f.storage.put("token", "t2").errorOrNull())
    }
}

/** API 31 (Android 12). */
@Config(sdk = [31])
class AndroidLockedDeviceApi31Test : AndroidLockedDeviceApi31Base()

/** API 32 (Android 12L). */
@Config(sdk = [32])
class AndroidLockedDeviceApi32Test : AndroidLockedDeviceApi31Base()
