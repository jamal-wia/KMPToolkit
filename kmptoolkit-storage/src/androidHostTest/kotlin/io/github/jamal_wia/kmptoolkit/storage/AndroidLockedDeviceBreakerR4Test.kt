package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.core.app.ApplicationProvider
import java.security.AlgorithmParameters
import java.security.InvalidKeyException
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Breaker review round 4 of group sto-android (ticket release-5-1-0, STO-4): a key with
 * [AndroidSecureStorageOptions.unlockedDeviceRequired] used while the device is locked, in the shape API 31+ (keystore 2)
 * really reports it.
 *
 * Contract: [AndroidSecureStorageOptions.unlockedDeviceRequired] KDoc ("While the device is locked every call that needs
 * the key answers [StorageError.Unavailable]: retry after the user unlocks it"), 05-platform-notes ("Key options" and the
 * `ensureKey()` table row "`unlockedDeviceRequired` while the device is locked | `Unavailable`"; "User authentication":
 * "With `unlockedDeviceRequired` as well ... such a store answers `AccessDenied` for either"), and the KDoc of
 * `KeystoreValueCipher.initialised` ("A locked device ... never a reason to drop the key").
 *
 * The platform shape (not observed on a device):
 * - keystore 2 `enforcements.rs` `authorize_create` (AOSP system/security, main): for a key with
 *   UNLOCKED_DEVICE_REQUIRED while the user's device is locked it returns `Error::Km(Ec::DEVICE_LOCKED)`, KeyMint error
 *   -72, before the super-key unwrap (which would have answered LOCKED, 2). KeyMint itself also answers DEVICE_LOCKED.
 * - `Cipher.init` -> `AndroidKeyStoreCipherSpiBase.ensureKeystoreOperationInitialized` -> `createOperation` throws
 *   `android.security.KeyStoreException(-72)`, mapped by `KeyStoreCryptoOperationUtils.getExceptionForCipherInit`
 *   (special cases -52 / -55 only) -> `getInvalidKeyException`, whose switch (android-all 12, 13, 14, 16, 17, `javap -c`)
 *   maps -26, 2 and 3 to `UserNotAuthenticatedException`, 7 and 17 to `KeyPermanentlyInvalidatedException`, -24 / -25 to
 *   not-yet-valid / expired, and everything else, -72 included, to `InvalidKeyException("Keystore operation failed",
 *   cause = KeyStoreException(-72))`. No `UserNotAuthenticatedException` anywhere in the chain.
 * - `KeyStoreException.isTransientFailure()` for -72 is false: its `PublicErrorInformation` has indicators 10
 *   (system error | requires user authentication), not the transient bit 4 (android-all 14).
 * API 28-30 (keystore 1) answers LOCKED (2), which maps to `UserNotAuthenticatedException`: the shape the executor's
 * tests model, kept here as the control.
 *
 * [LockedDeviceGcmProvider] refuses `Cipher.init` in either shape while [DeviceLock.locked]; the crypto is the JDK's.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidLockedDeviceBreakerR4Test {

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

    /** A key store holding one filed [LockedDeviceKey]; counts every call, never expects a deletion or a generation. */
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

    private fun fixture(shape: LockShape, userAuthentication: Boolean = false): Fixture {
        val lock = DeviceLock(shape)
        val keyStore = FiledKeyStore(LockedDeviceKey(TestKeys.generate(), lock))
        val options = AndroidSecureStorageOptions(
            unlockedDeviceRequired = true,
            userAuthentication = if (userAuthentication) AndroidUserAuthentication(validitySeconds = 30) else null,
        )
        val alias = "breaker.r4.locked.${sequence++}.${System.identityHashCode(this)}"
        val preferences: SharedPreferences = context.getSharedPreferences(alias, Context.MODE_PRIVATE)
        val storage = AndroidSecureKeyValueStorage(
            preferences,
            KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore, options)),
            Dispatchers.Unconfined,
        )
        return Fixture(storage, keyStore, lock)
    }

    @Test
    fun `control - the keystore 1 shape of a locked device (UserNotAuthenticatedException) is Unavailable everywhere`() = runTest {
        val f: Fixture = fixture(LockShape.KEYSTORE1_LOCKED)
        assertEquals(StorageResult.Success(Unit), f.storage.put("token", "t1"), "control: unlocked, the store works")
        f.lock.locked = true

        assertIs<StorageError.Unavailable>(f.storage.get("token").errorOrNull())
        assertIs<StorageError.Unavailable>(f.storage.put("token", "t2").errorOrNull())
        assertIs<StorageError.Unavailable>(f.storage.ensureKey().errorOrNull())
        assertEquals(1, f.keyStore.lookups.get(), "control: a locked device never makes the key be looked up again")

        f.lock.locked = false
        assertEquals(StorageResult.Success("t1"), f.storage.get("token"))
    }

    @Test
    fun `API 31+ a read on a locked device is Unavailable, never Undecryptable, and the value reads back after the unlock`() = runTest {
        val f: Fixture = fixture(LockShape.KEYSTORE2_DEVICE_LOCKED)
        assertEquals(StorageResult.Success(Unit), f.storage.put("token", "t1"), "control: unlocked, the store works")
        f.lock.locked = true

        val read: StorageResult<String?> = f.storage.get("token")

        assertIs<StorageError.Unavailable>(
            read.errorOrNull(),
            "unlockedDeviceRequired KDoc: while the device is locked every call that needs the key answers Unavailable. " +
                "Undecryptable tells the app the value is gone for good (it re-logs in, or removes it) although it is " +
                "intact: got $read",
        )
        assertEquals(0, f.keyStore.deletions.get())
        assertEquals(0, f.keyStore.generations.get())
        f.lock.locked = false
        assertEquals(StorageResult.Success("t1"), f.storage.get("token"), "the value was never lost")
    }

    @Test
    fun `API 31+ a write and ensureKey on a locked device are Unavailable and never drop the cached key`() = runTest {
        val f: Fixture = fixture(LockShape.KEYSTORE2_DEVICE_LOCKED)
        assertEquals(StorageResult.Success(Unit), f.storage.put("token", "t1"), "control: unlocked, the store works")
        assertEquals(1, f.keyStore.lookups.get(), "control: one lookup so far")
        f.lock.locked = true

        val write: StorageResult<Unit> = f.storage.put("token", "t2")
        val ensure: StorageResult<Unit> = f.storage.ensureKey()

        assertIs<StorageError.Unavailable>(write.errorOrNull(), "a write on a locked device: got $write")
        assertIs<StorageError.Unavailable>(ensure.errorOrNull(), "05-platform-notes ensureKey table: got $ensure")
        assertEquals(
            1,
            f.keyStore.lookups.get(),
            "KeystoreValueCipher.initialised: a locked device is never a reason to drop the key, so no re-lookup",
        )
        f.lock.locked = false
        assertEquals(StorageResult.Success("t1"), f.storage.get("token"))
    }

    @Test
    fun `API 31+ a store with user authentication as well answers AccessDenied on a locked device, as documented`() = runTest {
        val f: Fixture = fixture(LockShape.KEYSTORE2_DEVICE_LOCKED, userAuthentication = true)
        assertEquals(StorageResult.Success(Unit), f.storage.put("token", "t1"), "control: unlocked, the store works")
        f.lock.locked = true

        val read: StorageResult<String?> = f.storage.get("token")
        val write: StorageResult<Unit> = f.storage.put("token", "t2")

        assertIs<StorageError.AccessDenied>(
            read.errorOrNull(),
            "05-platform-notes: with unlockedDeviceRequired as well such a store answers AccessDenied for either: got $read",
        )
        assertIs<StorageError.AccessDenied>(write.errorOrNull(), "got $write")
        f.lock.locked = false
        assertEquals(StorageResult.Success("t1"), f.storage.get("token"))
    }
}

/** How the platform refuses `Cipher.init` while the device is locked. */
internal enum class LockShape {
    /** API 28-30: the daemon answers LOCKED (2), `getInvalidKeyException` makes it `UserNotAuthenticatedException`. */
    KEYSTORE1_LOCKED,

    /** API 31+: `authorize_create` answers DEVICE_LOCKED (-72), which reaches the caller as a plain `InvalidKeyException`. */
    KEYSTORE2_DEVICE_LOCKED,
}

internal class DeviceLock(val shape: LockShape) {
    @Volatile
    var locked: Boolean = false

    fun refusal(): InvalidKeyException = when (shape) {
        LockShape.KEYSTORE1_LOCKED -> UserNotAuthenticatedException()
        LockShape.KEYSTORE2_DEVICE_LOCKED ->
            InvalidKeyException("Keystore operation failed", platformKeyStoreException(DEVICE_LOCKED, "device is locked."))
    }

    private companion object {
        /** KeyMint `ErrorCode::DEVICE_LOCKED`. */
        const val DEVICE_LOCKED = -72
    }
}

internal class LockedDeviceKey(val material: SecretKey, val lock: DeviceLock) : SecretKey {
    override fun getAlgorithm(): String = "AES"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

internal object LockedDeviceGcmProvider : Provider("KmpToolkitLockedDeviceGcm", 1.0, "AES/GCM refusing init while locked") {
    private fun readResolve(): Any = LockedDeviceGcmProvider

    init {
        putService(
            object : Service(this, "Cipher", "AES/GCM/NoPadding", LockedDeviceGcmCipher::class.java.name, null,
                mapOf("SupportedKeyClasses" to LockedDeviceKey::class.java.name)) {
                override fun newInstance(constructorParameter: Any?): Any = LockedDeviceGcmCipher()
            },
        )
    }
}

internal class LockedDeviceGcmCipher : CipherSpi() {
    private val inner: Cipher = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE")

    private fun unwrap(key: Key?): SecretKeySpec {
        val locked: LockedDeviceKey = key as? LockedDeviceKey ?: throw InvalidKeyException("not a locked-device key")
        if (locked.lock.locked) throw locked.lock.refusal()
        return SecretKeySpec(locked.material.encoded, "AES")
    }

    override fun engineSetMode(mode: String?) {}
    override fun engineSetPadding(padding: String?) {}
    override fun engineGetBlockSize(): Int = 16
    override fun engineGetOutputSize(inputLen: Int): Int = inner.getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = inner.iv
    override fun engineGetParameters(): AlgorithmParameters? = inner.parameters
    override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) = inner.init(opmode, unwrap(key))
    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) =
        inner.init(opmode, unwrap(key), params)
    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) =
        inner.init(opmode, unwrap(key), params)
    override fun engineUpdateAAD(src: ByteArray, offset: Int, len: Int) = inner.updateAAD(src, offset, len)
    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? =
        inner.update(input, inputOffset, inputLen)
    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
        inner.update(input, inputOffset, inputLen, output, outputOffset)
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
        inner.doFinal(input, inputOffset, inputLen)
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
        inner.doFinal(input, inputOffset, inputLen, output, outputOffset)
}
