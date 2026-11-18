package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.security.AlgorithmParameters
import java.security.InvalidKeyException
import java.security.Key
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Breaker review round 5 of group sto-android (ticket release-5-1-0, STO-4): the round-5 fix that recognises a locked
 * device on API 31+ (`isDeviceLockedFailure` asking `KeyStoreException.requiresUserAuthentication()` from API 33, the
 * exact API 31-32 shape marked as `DeviceLockedKeyException` for a store with `unlockedDeviceRequired`) and the
 * non-secret-key alias fix (`secretKeyOrNull`).
 *
 * Contract: [AndroidSecureStorageOptions.unlockedDeviceRequired] KDoc and 05-platform-notes "Key options" (a locked device
 * is `Unavailable`, the key is kept and never looked up again), "User authentication" / "Invalidation" (a permanently
 * invalidated key is deleted and replaced, writes work again, old values are `Undecryptable`), the KDoc of
 * `KeystoreValueCipher.initialised`, plan 4.9 (the migration never cuts a readable legacy entry off), and
 * [AliasedKeyStore.existingKey] (`null` only for "never filed" or "proven dead").
 *
 * The attacks:
 * - the locked-device recognition must never swallow a REAL permanent invalidation (the platform form of
 *   `KeyStoreCryptoOperationUtils.getInvalidKeyException` for codes 7 and 17 is a cause-less
 *   `KeyPermanentlyInvalidatedException`, android-all 12, 12.1, 14, `javap -c`), or the store is bricked for good;
 * - exception chains with cause cycles, suppressed exceptions and null messages must neither hang nor flip the verdict;
 * - a seeded storm of reads, writes and `ensureKey` over a pending CAS migration while the device locks and unlocks;
 * - a filed non-secret key under concurrent writers: nothing generated or deleted, and exactly one key once the alias is
 *   free.
 *
 * [ShapedGcmProviderR5] refuses `Cipher.init` with whatever the key's [ShapedKeyR5.refusal] produces; the crypto is the
 * JDK's. The `KeyStoreException` is the real platform class ([platformKeyStoreException]).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidLockedDeviceBreakerR5Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var sequence: Int = 0

    @Before
    fun install() {
        if (Security.getProvider(ShapedGcmProviderR5.name) == null) Security.insertProviderAt(ShapedGcmProviderR5, 1)
        Keystore2R4Daemon.reset()
    }

    @After
    fun uninstall() {
        Security.removeProvider(ShapedGcmProviderR5.name)
        Security.removeProvider(Keystore2R4Provider.name)
        Keystore2R4Daemon.reset()
    }

    /** A key store that files one key, deletes it on request and generates a working replacement; counts every call. */
    private class ReplaceableKeyStore(initial: SecretKey) : AliasedKeyStore {
        @Volatile
        var filed: SecretKey? = initial
        val lookups: AtomicInteger = AtomicInteger()
        val deletions: AtomicInteger = AtomicInteger()
        val generations: AtomicInteger = AtomicInteger()

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? {
            lookups.incrementAndGet()
            return filed
        }

        override fun generateKey(alias: String): SecretKey {
            generations.incrementAndGet()
            return ShapedKeyR5(TestKeys.generate()).also { fresh: SecretKey -> filed = fresh }
        }

        override fun deleteKey(alias: String) {
            deletions.incrementAndGet()
            filed = null
        }
    }

    private fun alias(label: String): String = "breaker.r5.locked.$label.${sequence++}.${System.identityHashCode(this)}"

    private fun storage(alias: String, keyStore: AliasedKeyStore, options: AndroidSecureStorageOptions): AndroidSecureKeyValueStorage =
        AndroidSecureKeyValueStorage(
            context.getSharedPreferences(alias, Context.MODE_PRIVATE),
            KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore, options)),
            Dispatchers.Unconfined,
        )

    // -- A real invalidation is never taken for a locked device ------------------------------------------------------

    @Test
    @Config(sdk = [31, 32, 33, 35])
    fun `a permanently invalidated key of a store that needs the device unlocked is still replaced, never kept as locked`() = runTest {
        val dead = ShapedKeyR5(TestKeys.generate())
        val keyStore = ReplaceableKeyStore(dead)
        val alias: String = alias("invalidated")
        val store: AndroidSecureKeyValueStorage = storage(alias, keyStore, AndroidSecureStorageOptions(unlockedDeviceRequired = true))
        assertEquals(StorageResult.Success(Unit), store.put("old", "o1"), "control: the store works before the invalidation")
        // The platform form for KEY_PERMANENTLY_INVALIDATED (17) and KEY_NOT_FOUND (7): a cause-less KPIE.
        dead.refusal = { KeyPermanentlyInvalidatedException() }

        val write: StorageResult<Unit> = store.put("token", "t2")

        assertEquals(
            StorageResult.Success(Unit),
            write,
            "05 Invalidation: a dead key is deleted, a new one is generated and writes work again; a dead key taken for a " +
                "locked device would answer Unavailable for ever (a bricked store): got $write",
        )
        assertEquals(1, keyStore.deletions.get(), "the dead key was deleted once")
        assertEquals(1, keyStore.generations.get(), "exactly one replacement")
        assertEquals(StorageResult.Success<String?>("t2"), store.get("token"))
        assertIs<StorageError.Undecryptable>(store.get("old").errorOrNull(), "the old value went with the dead key")
    }

    @Test
    @Config(sdk = [31, 32])
    fun `on API 31-32 another refusal in the locked shape is the documented price - Unavailable, kept, recovers - never a deletion`() = runTest {
        val key = ShapedKeyR5(TestKeys.generate())
        val keyStore = ReplaceableKeyStore(key)
        val store: AndroidSecureKeyValueStorage =
            storage(alias("price"), keyStore, AndroidSecureStorageOptions(unlockedDeviceRequired = true))
        assertEquals(StorageResult.Success(Unit), store.put("token", "t1"), "control: the store works")
        key.refusal = { keystore2Refusal(SYSTEM_ERROR) }

        assertIs<StorageError.Unavailable>(store.get("token").errorOrNull(), "05 Key options, the API 31-32 price")
        assertIs<StorageError.Unavailable>(store.put("token", "t2").errorOrNull())
        assertIs<StorageError.Unavailable>(store.ensureKey().errorOrNull())
        assertEquals(1, keyStore.lookups.get(), "kept: never looked up again")
        assertEquals(0, keyStore.deletions.get())
        assertEquals(0, keyStore.generations.get())

        key.refusal = null
        assertEquals(StorageResult.Success<String?>("t1"), store.get("token"), "the value was never lost")
    }

    @Test
    @Config(sdk = [33, 35])
    fun `from API 33 a key that needs the device unlocked is recognised even when the store was reopened without the option`() = runTest {
        // 05 Key options: a key keeps the properties it was generated with; the app later dropped the option.
        val key = ShapedKeyR5(TestKeys.generate())
        val keyStore = ReplaceableKeyStore(key)
        val store: AndroidSecureKeyValueStorage = storage(alias("reopened"), keyStore, AndroidSecureStorageOptions())
        assertEquals(StorageResult.Success(Unit), store.put("token", "t1"), "control: the store works")
        key.refusal = { keystore2Refusal(DEVICE_LOCKED) }

        assertIs<StorageError.Unavailable>(store.get("token").errorOrNull(), "the platform says: requires user authentication")
        assertIs<StorageError.Unavailable>(store.put("token", "t2").errorOrNull())
        assertEquals(1, keyStore.lookups.get())
        key.refusal = null
        assertEquals(StorageResult.Success<String?>("t1"), store.get("token"))
    }

    // -- Hostile exception chains -------------------------------------------------------------------------------------

    @Test
    fun `cause cycles terminate in every classifier and keep the verdict`() {
        val lockedCycle: InvalidKeyException = keystore2Refusal(DEVICE_LOCKED).also { it.cause!!.initCause(it) }
        val otherCycle: InvalidKeyException = keystore2Refusal(SYSTEM_ERROR).also { it.cause!!.initCause(it) }
        val kpieCycle = KeyPermanentlyInvalidatedException("dead", IllegalStateException("x"))
        kpieCycle.cause!!.initCause(kpieCycle)

        assertTrue(isDeviceLockedFailure(lockedCycle, 35), "API 35: -72 in a cyclic chain is still a locked device")
        assertTrue(isKeystore2LockedShape(lockedCycle, 31), "API 31: the shape only looks at the direct cause")
        assertFalse(isDeviceLockedFailure(lockedCycle, 31), "API 31: unmarked, not by itself a locked device")
        assertFalse(isDeviceLockedFailure(otherCycle, 35), "API 35: code 4 in a cyclic chain is not a locked device")
        assertFalse(isPermanentlyInvalidatedKey(lockedCycle), "a locked device is never proof of death")
        assertFalse(isPermanentlyInvalidatedAtLookup(otherCycle))
        assertFalse(isTransientKeystoreFailure(lockedCycle), "-72 is not transient")
        assertTrue(isPermanentlyInvalidatedKey(kpieCycle))
        assertFalse(isDeviceLockedFailure(kpieCycle, 35), "a cyclic KPIE is not a locked device")
    }

    @Test
    fun `suppressed exceptions and null messages never flip the verdict`() {
        val systemErrorWithSuppressedLock: InvalidKeyException = keystore2Refusal(SYSTEM_ERROR).also {
            it.addSuppressed(keystore2Refusal(DEVICE_LOCKED))
            it.addSuppressed(UserNotAuthenticatedException())
        }
        val kpieWithSuppressedLock = KeyPermanentlyInvalidatedException().also {
            it.addSuppressed(UserNotAuthenticatedException())
            it.addSuppressed(DeviceLockedKeyException(keystore2Refusal(DEVICE_LOCKED)))
        }
        val nullMessage = InvalidKeyException(null as String?, platformKeyStoreException(DEVICE_LOCKED, "device is locked."))

        assertFalse(isDeviceLockedFailure(systemErrorWithSuppressedLock, 35), "a suppressed lock is not the failure")
        assertFalse(isKeystore2LockedShape(KeyPermanentlyInvalidatedException(), 31))
        assertFalse(isDeviceLockedFailure(kpieWithSuppressedLock, 35), "a KPIE stays a KPIE whatever it suppressed")
        assertTrue(isPermanentlyInvalidatedKey(kpieWithSuppressedLock))
        assertTrue(isDeviceLockedFailure(nullMessage, 35), "API 33+: the platform's answer does not depend on the message")
        assertFalse(isKeystore2LockedShape(nullMessage, 31), "API 31: the shape needs the provider's message")
    }

    @Test
    @Config(sdk = [31, 35])
    fun `a locked refusal whose chain is cyclic answers Unavailable through the whole store and never re-looks the key up`() =
        runTest(timeout = 30.seconds) {
            val key = ShapedKeyR5(TestKeys.generate())
            val keyStore = ReplaceableKeyStore(key)
            val store: AndroidSecureKeyValueStorage =
                storage(alias("cycle"), keyStore, AndroidSecureStorageOptions(unlockedDeviceRequired = true))
            assertEquals(StorageResult.Success(Unit), store.put("token", "t1"), "control: the store works")
            key.refusal = { keystore2Refusal(DEVICE_LOCKED).also { it.cause!!.initCause(it) } }

            val read: StorageResult<String?> = store.get("token")
            val write: StorageResult<Unit> = store.put("token", "t2")
            val ensure: StorageResult<Unit> = store.ensureKey()

            assertIs<StorageError.Unavailable>(read.errorOrNull(), "got $read")
            assertIs<StorageError.Unavailable>(write.errorOrNull(), "got $write")
            assertIs<StorageError.Unavailable>(ensure.errorOrNull(), "got $ensure")
            assertEquals(1, keyStore.lookups.get())
            assertEquals(0, keyStore.deletions.get())
            key.refusal = null
            assertEquals(StorageResult.Success<String?>("t1"), store.get("token"))
        }

    // -- Storm: locked device flapping over a pending CAS migration ---------------------------------------------------

    @Test
    @Config(sdk = [31, 35])
    fun `a seeded storm over a pending migration while the device locks and unlocks loses nothing and never replaces the key`() =
        runTest(timeout = 120.seconds) {
            val seed = 5501
            val locked = AtomicBoolean(false)
            val key = ShapedKeyR5(TestKeys.generate())
            key.refusal = { if (locked.get()) keystore2Refusal(DEVICE_LOCKED) else null }
            val keyStore = ReplaceableKeyStore(key)
            val alias: String = alias("storm")
            val preferences: SharedPreferences = context.getSharedPreferences(alias, Context.MODE_PRIVATE)
            val workers = 8
            val schedule = Random(seed)
            // Legacy entries (4.0.0 unbound and 5.0.0 bound) waiting for the migration pass.
            val expected: Array<String> = Array(workers) { worker: Int -> "legacy-$worker" }
            preferences.edit().apply {
                for (worker: Int in 0 until workers) {
                    val iv: ByteArray = schedule.nextBytes(12).also { if (it[0] == KeystoreValueCipher.FORMAT_VERSION) it[0] = 2 }
                    putString("w$worker", legacyEncrypt(key.material, expected[worker], iv, boundTo = if (worker % 2 == 1) "w$worker" else null))
                }
            }.commit()
            val options = AndroidSecureStorageOptions(unlockedDeviceRequired = true)
            fun store(): AndroidSecureKeyValueStorage = AndroidSecureKeyValueStorage(
                preferences,
                KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore, options)),
                Dispatchers.Unconfined,
            )
            val problems: MutableList<String> = Collections.synchronizedList(mutableListOf())
            val lockedAnswers = AtomicInteger()

            withContext(Dispatchers.Default) {
                withTimeout(60.seconds) {
                    coroutineScope {
                        (0 until workers).map { worker: Int ->
                            async {
                                val random = Random(seed * 1000 + worker)
                                repeat(40) { step: Int ->
                                    if (random.nextInt(4) == 0) locked.set(random.nextBoolean())
                                    val name = "w$worker"
                                    when (random.nextInt(3)) {
                                        0 -> {
                                            val value = "w$worker-s$step"
                                            when (val result: StorageResult<Unit> = store().put(name, value)) {
                                                is StorageResult.Success -> expected[worker] = value
                                                is StorageResult.Failure ->
                                                    if (result.error is StorageError.Unavailable) {
                                                        lockedAnswers.incrementAndGet()
                                                    } else {
                                                        problems += "seed=$seed worker $worker step $step put: $result"
                                                    }
                                            }
                                        }
                                        1 -> when (val result: StorageResult<String?> = store().get(name)) {
                                            is StorageResult.Success ->
                                                if (result.value != expected[worker]) {
                                                    problems += "seed=$seed worker $worker step $step get: ${result.value} expected ${expected[worker]}"
                                                }
                                            is StorageResult.Failure ->
                                                if (result.error is StorageError.Unavailable) {
                                                    lockedAnswers.incrementAndGet()
                                                } else {
                                                    problems += "seed=$seed worker $worker step $step get: $result"
                                                }
                                        }
                                        else -> {
                                            val result: StorageResult<Unit> = store().ensureKey()
                                            if (result is StorageResult.Failure) {
                                                if (result.error is StorageError.Unavailable) {
                                                    lockedAnswers.incrementAndGet()
                                                } else {
                                                    problems += "seed=$seed worker $worker step $step ensureKey: $result"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }

            assertEquals(emptyList(), problems.toList(), "seed=$seed: only the latest value or Unavailable, never a lost value")
            assertTrue(lockedAnswers.get() > 0, "seed=$seed control: the device was locked for some calls")
            locked.set(false)
            for (worker: Int in 0 until workers) {
                assertEquals(
                    StorageResult.Success<String?>(expected[worker]),
                    store().get("w$worker"),
                    "seed=$seed: after the unlock every entry reads its latest value (CAS migration never lost a write)",
                )
            }
            assertEquals(StorageResult.Success(Unit), store().put("after", "x"), "seed=$seed: the store works after the unlock")
            assertTrue(preferences.contains(AndroidSecureKeyValueStorage.FORMAT_KEY), "seed=$seed: the migration finished")
            for (worker: Int in 0 until workers) {
                val raw: ByteArray = Base64.decode(preferences.getString("w$worker", null), Base64.NO_WRAP)
                assertEquals(KeystoreValueCipher.FORMAT_VERSION, raw[0], "seed=$seed: w$worker is versioned after the pass")
            }
            assertEquals(1, keyStore.lookups.get(), "seed=$seed: a locked device never made the key be looked up again")
            assertEquals(0, keyStore.deletions.get(), "seed=$seed: nothing deleted")
            assertEquals(0, keyStore.generations.get(), "seed=$seed: nothing generated")
            assertSame(key, keyStore.filed, "seed=$seed: the same key is filed")
        }

    // -- A filed non-secret key under concurrent writers --------------------------------------------------------------

    @Test
    fun `a filed non-secret key under concurrent writers is never replaced, and a freed alias gets exactly one key`() =
        runTest(timeout = 120.seconds) {
            val seed = 5502
            Security.insertProviderAt(Keystore2R4Provider, 1)
            val alias: String = alias("foreign")
            Keystore2R4Daemon.entries[alias] = ForeignEcKeyR5
            val preferences: SharedPreferences = context.getSharedPreferences(alias, Context.MODE_PRIVATE)
            // A new store and a new production key source per call, as every createSecureKeyValueStorage call makes.
            fun store(): AndroidSecureKeyValueStorage = AndroidSecureKeyValueStorage(
                preferences,
                KeystoreValueCipher(AndroidKeystoreKeySource(alias)),
                Dispatchers.Unconfined,
            )
            val problems: MutableList<String> = Collections.synchronizedList(mutableListOf())

            withContext(Dispatchers.Default) {
                withTimeout(60.seconds) {
                    coroutineScope {
                        (0 until 8).map { worker: Int ->
                            async {
                                val random = Random(seed * 1000 + worker)
                                repeat(15) { step: Int ->
                                    val result: StorageResult<Unit> =
                                        if (random.nextBoolean()) store().put("k$worker", "v$step") else store().ensureKey()
                                    if (result.errorOrNull() !is StorageError.Unavailable) {
                                        problems += "seed=$seed worker $worker step $step: $result"
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }

            assertEquals(emptyList(), problems.toList(), "seed=$seed: a foreign key at the alias answers Unavailable every time")
            assertSame(ForeignEcKeyR5, Keystore2R4Daemon.entries[alias], "seed=$seed: the foreign key is still filed")
            assertEquals(0, Keystore2R4Daemon.generations.get(), "seed=$seed: nothing generated over it")
            assertEquals(0, Keystore2R4Daemon.deletions.get(), "seed=$seed: nothing deleted")

            // The component that owned the foreign entry removes it; the alias is free now.
            Keystore2R4Daemon.entries.remove(alias)
            val outcomes: List<StorageResult<Unit>> = withContext(Dispatchers.Default) {
                withTimeout(60.seconds) {
                    coroutineScope { (0 until 8).map { async { store().ensureKey() } }.awaitAll() }
                }
            }

            assertEquals(List(8) { StorageResult.Success(Unit) }, outcomes, "seed=$seed: a free alias works")
            assertEquals(1, Keystore2R4Daemon.generations.get(), "seed=$seed: exactly one key for eight concurrent first uses")
            assertEquals(0, Keystore2R4Daemon.deletions.get(), "seed=$seed: the library deleted nothing")
            assertEquals(StorageResult.Success(Unit), store().put("token", "t"))
            assertEquals(StorageResult.Success<String?>("t"), store().get("token"))
        }

    private companion object {
        /** KeyMint `ErrorCode::DEVICE_LOCKED`. */
        const val DEVICE_LOCKED = -72

        /** keystore 2 `ResponseCode::SYSTEM_ERROR`: not transient, not user authentication (android-all 14). */
        const val SYSTEM_ERROR = 4

        /** The provider's form for every cipher-init code it has no named class for (`getInvalidKeyException` default). */
        fun keystore2Refusal(code: Int): InvalidKeyException =
            InvalidKeyException("Keystore operation failed", platformKeyStoreException(code, "daemon answered $code"))

        /** The 4.0.0 writer (no associated data) or, with [boundTo], the 5.0.0 one. */
        fun legacyEncrypt(material: SecretKey, plaintext: String, iv: ByteArray, boundTo: String?): String {
            val jce: Cipher = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE")
            jce.init(Cipher.ENCRYPT_MODE, SecretKeySpec(material.encoded, "AES"), GCMParameterSpec(128, iv))
            if (boundTo != null) jce.updateAAD("kmptoolkit-storage-key:$boundTo".toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(iv + jce.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
    }
}

/** A filed key that is not a secret key, as an EC entry another component made under the alias would answer. */
private object ForeignEcKeyR5 : PrivateKey {
    private fun readResolve(): Any = ForeignEcKeyR5
    override fun getAlgorithm(): String = "EC"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

/** A key handle whose `Cipher.init` is refused with what [refusal] produces (nothing: accepted). */
internal class ShapedKeyR5(val material: SecretKey) : SecretKey {
    @Volatile
    var refusal: (() -> InvalidKeyException?)? = null

    override fun getAlgorithm(): String = "AES"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

internal object ShapedGcmProviderR5 : Provider("KmpToolkitShapedGcmR5", 1.0, "AES/GCM refusing init in a given shape") {
    private fun readResolve(): Any = ShapedGcmProviderR5

    init {
        putService(
            object : Service(this, "Cipher", "AES/GCM/NoPadding", ShapedGcmCipherR5::class.java.name, null,
                mapOf("SupportedKeyClasses" to ShapedKeyR5::class.java.name)) {
                override fun newInstance(constructorParameter: Any?): Any = ShapedGcmCipherR5()
            },
        )
    }
}

internal class ShapedGcmCipherR5 : CipherSpi() {
    private val inner: Cipher = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE")

    private fun unwrap(key: Key?): SecretKeySpec {
        val shaped: ShapedKeyR5 = key as? ShapedKeyR5 ?: throw InvalidKeyException("not a shaped key")
        shaped.refusal?.invoke()?.let { refusal: InvalidKeyException -> throw refusal }
        return SecretKeySpec(shaped.material.encoded, "AES")
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
