package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import android.content.SharedPreferences
import android.util.Base64
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The encrypted store over real AES-256-GCM, held to the same contract as the plain one plus the
 * guarantees that are the reason it exists.
 *
 * The key comes from [TestKeys] rather than the AndroidKeyStore — see that file for what that does
 * and does not leave covered.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSecureKeyValueStorageTest : KeyValueStorageContractTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Runs each store call inline, so the blocking SharedPreferences work stays on the test thread.
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    @Before
    fun resetKeys() {
        TestKeys.reset()
    }

    override fun create(config: StorageConfig): KeyValueStorage = secure(config)

    private fun secure(config: StorageConfig = StorageConfig(NAME)): SecureKeyValueStorage {
        val name: String = requireNotNull(config.name)
        return AndroidSecureKeyValueStorage(
            preferences = preferences(name),
            cipher = KeystoreValueCipher(TestKeys.source(name)),
            dispatcher = dispatcher,
        )
    }

    @Test
    fun `the stored bytes are not the plaintext`() = runTest {
        secure().put("refresh_token", "super-secret-refresh")

        assertNotEquals("super-secret-refresh", rawValue("refresh_token"))
    }

    @Test
    fun `no stored value contains the plaintext as a substring`() = runTest {
        secure().put("k", "super-secret-refresh")

        val stored: String = requireNotNull(rawValue("k"))
        assertEquals(false, stored.contains("super-secret-refresh"))
    }

    @Test
    fun `the key name is stored in the clear`() = runTest {
        // Stated in the module's own documentation, so it is asserted rather than left implicit: a
        // consumer must not put a secret in a key name.
        secure().put("access_token", "value")

        assertEquals(true, preferences(NAME).contains("access_token"))
    }

    @Test
    fun `writing the same value twice produces different ciphertext`() = runTest {
        // Every encryption uses a fresh IV. Identical ciphertext would leak that a value did not
        // change between two writes.
        val storage: SecureKeyValueStorage = secure()

        storage.put("k", "same-value")
        val first: String? = rawValue("k")
        storage.put("k", "same-value")

        assertNotEquals(first, rawValue("k"))
        assertEquals("same-value", storage.get("k").getOrNull())
    }

    @Test
    fun `two secure stores with different names cannot read each other's values`() = runTest {
        // Different names mean a different preferences file *and* a different key, so neither the
        // ciphertext nor the ability to decrypt it crosses over.
        val first: SecureKeyValueStorage = secure(StorageConfig("$NAME.one"))
        val second: SecureKeyValueStorage = secure(StorageConfig("$NAME.two"))
        first.put("k", "first-secret")

        assertNull(second.get("k").getOrNull())
    }

    @Test
    fun `a corrupted entry is reported as undecryptable rather than absent`() = runTest {
        // The distinction that matters: "never written" means sign in; "cannot be decrypted" means
        // the stored session is gone and has to be discarded deliberately.
        preferences(NAME).edit().putString("corrupt", "not-valid-ciphertext!!").commit()

        val result: StorageResult<String?> = secure().get("corrupt")

        assertEquals("corrupt", (result.errorOrNull() as StorageError.Undecryptable).key)
    }

    @Test
    fun `an entry truncated below the IV length is reported as undecryptable`() = runTest {
        preferences(NAME).edit().putString("short", "AAAA").commit()

        val result: StorageResult<String?> = secure().get("short")

        assertEquals("short", (result.errorOrNull() as StorageError.Undecryptable).key)
    }

    @Test
    fun `a tampered ciphertext fails authentication instead of returning altered plaintext`() = runTest {
        val storage: SecureKeyValueStorage = secure()
        storage.put("k", "authentic")
        preferences(NAME).edit().putString("k", tampered(requireNotNull(rawValue("k")))).commit()

        val result: StorageResult<String?> = secure().get("k")

        assertEquals("k", (result.errorOrNull() as StorageError.Undecryptable).key)
    }

    @Test
    fun `a value written under a key that no longer exists is undecryptable not absent`() = runTest {
        // What a device-lock change does on a real device: the ciphertext survives, the key does
        // not. Reproduced by dropping the cached key and letting a new one be generated.
        secure().put("k", "value")
        TestKeys.reset()

        val result: StorageResult<String?> = secure().get("k")

        assertEquals("k", (result.errorOrNull() as StorageError.Undecryptable).key)
    }

    @Test
    fun `an unreadable entry can be overwritten with a working one`() = runTest {
        // The recovery path a caller has after Undecryptable: put over it and carry on.
        secure().put("k", "old")
        TestKeys.reset()
        val storage: SecureKeyValueStorage = secure()

        storage.put("k", "new")

        assertEquals("new", storage.get("k").getOrNull())
    }

    @Test
    fun `a key store that will not open reports the read as unavailable`() = runTest {
        val storage = AndroidSecureKeyValueStorage(
            preferences = preferences(NAME).apply { edit().putString("k", "ciphertext").commit() },
            cipher = KeystoreValueCipher(TestKeys.unavailableSource()),
            dispatcher = dispatcher,
        )

        val error: StorageError? = storage.get("k").errorOrNull()

        assertEquals(StorageError.Unavailable::class, error!!::class)
    }

    @Test
    fun `a key store that will not open reports the write as unavailable and stores nothing`() = runTest {
        val storage = AndroidSecureKeyValueStorage(
            preferences = preferences(NAME),
            cipher = KeystoreValueCipher(TestKeys.unavailableSource()),
            dispatcher = dispatcher,
        )

        val error: StorageError? = storage.put("k", "v").errorOrNull()

        assertEquals(StorageError.Unavailable::class, error!!::class)
        assertNull(rawValue("k"))
    }

    @Test
    fun `the store is an encrypted store whose key the Android Keystore holds`() {
        val storage: EncryptedKeyValueStorage = secure()

        assertEquals(SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED, storage.protection)
    }

    @Test
    fun `ensureKey resolves the key once and writes no entry`() = runTest {
        var resolutions = 0
        val counting: SecretKeySource = object : SecretKeySource {
            override suspend fun key(): SecretKey {
                resolutions += 1
                return TestKeys.generate()
            }
        }
        val storage = AndroidSecureKeyValueStorage(
            preferences = preferences(NAME),
            cipher = KeystoreValueCipher(counting),
            dispatcher = dispatcher,
        )

        assertEquals(StorageResult.Success(Unit), storage.ensureKey())

        assertEquals(1, resolutions, "ensureKey must resolve the key, exactly once")
        assertEquals(emptySet(), preferences(NAME).all.keys)
        // Control: a read of an absent key never asks for the key, so the count above is ensureKey's own.
        assertEquals(StorageResult.Success(null), storage.get("k"))
        assertEquals(1, resolutions)
    }

    @Test
    fun `ensureKey with a key store that will not open is unavailable and stores nothing`() = runTest {
        val storage = AndroidSecureKeyValueStorage(
            preferences = preferences(NAME),
            cipher = KeystoreValueCipher(TestKeys.unavailableSource()),
            dispatcher = dispatcher,
        )

        assertEquals(StorageError.Unavailable::class, storage.ensureKey().errorOrNull()!!::class)
        assertEquals(emptySet(), preferences(NAME).all.keys)
    }

    @Test
    fun `ensureKey meeting an unexpected cipher failure is OperationFailed for ENSURE_KEY without a key and stores nothing`() = runTest {
        val failure = IllegalStateException("cipher init failed")
        val storage = AndroidSecureKeyValueStorage(
            preferences = preferences(NAME),
            cipher = object : ValueCipher {
                override suspend fun encrypt(plaintext: String): String = throw failure
                override suspend fun decrypt(encoded: String): String = throw failure
            },
            dispatcher = dispatcher,
        )

        assertEquals(
            StorageError.OperationFailed(StorageOperation.ENSURE_KEY, key = null, cause = failure),
            storage.ensureKey().errorOrNull(),
        )
        assertEquals(emptySet(), preferences(NAME).all.keys)
    }

    @Test
    fun `ensureKey whose key source throws a CancellationException while the caller is not cancelled rethrows it`() = runTest {
        // A swallowed CancellationException would come back as a Failure here, which a cancelling
        // caller cannot tell apart from a real one; the caller below is NOT cancelled, so only an
        // ensureKey that rethrows passes.
        val storage = AndroidSecureKeyValueStorage(
            preferences = preferences(NAME),
            cipher = KeystoreValueCipher(
                object : SecretKeySource {
                    override suspend fun key(): SecretKey = throw CancellationException("key wait cancelled")
                },
            ),
            dispatcher = dispatcher,
        )

        assertFailsWith<CancellationException> { storage.ensureKey() }
        assertEquals(emptySet(), preferences(NAME).all.keys)
    }

    @Test
    fun `ensureKey cancelled while the key is being resolved propagates the cancellation and stores nothing`() = runTest {
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val storage = AndroidSecureKeyValueStorage(
            preferences = preferences(NAME),
            cipher = KeystoreValueCipher(
                object : SecretKeySource {
                    override suspend fun key(): SecretKey {
                        gate.await()
                        return TestKeys.generate()
                    }
                },
            ),
            dispatcher = dispatcher,
        )
        var outcome: StorageResult<Unit>? = null
        val job: Job = launch(UnconfinedTestDispatcher(testScheduler)) { outcome = storage.ensureKey() }

        job.cancel()
        job.join()

        assertEquals(true, job.isCancelled)
        assertNull(outcome, "a cancelled ensureKey must not report a result")
        assertEquals(emptySet(), preferences(NAME).all.keys)
        // The store is usable afterwards: nothing was left half-done.
        gate.complete(Unit)
        assertEquals(StorageResult.Success(Unit), storage.ensureKey())
    }

    @Test
    fun `remove and clear work without the key store`() = runTest {
        // Deleting a secret must not depend on being able to read it — otherwise a broken key store
        // would leave a user unable to sign out.
        preferences(NAME).edit().putString("k", "ciphertext").commit()
        val storage = AndroidSecureKeyValueStorage(
            preferences = preferences(NAME),
            cipher = KeystoreValueCipher(TestKeys.unavailableSource()),
            dispatcher = dispatcher,
        )

        assertEquals(true, storage.remove("k").isSuccess)
        assertEquals(true, storage.clear().isSuccess)
        assertNull(rawValue("k"))
    }

    @Test
    fun `the plain and the secure store of one name are separate files`() = runTest {
        val plain: KeyValueStorage = createKeyValueStorage(context, StorageConfig(NAME))
        plain.put("k", "plain")

        secure().put("k", "secret")

        assertEquals("plain", plain.get("k").getOrNull())
        assertEquals("secret", secure().get("k").getOrNull())
    }

    private fun preferences(name: String): SharedPreferences =
        context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE)

    private fun rawValue(key: String): String? = preferences(NAME).getString(key, null)

    /** Flips one bit inside the ciphertext body, leaving the IV and the framing intact. */
    private fun tampered(stored: String): String {
        val bytes: ByteArray = Base64.decode(stored, Base64.NO_WRAP)
        val target: Int = KeystoreValueCipher.IV_LENGTH_BYTES
        bytes[target] = (bytes[target].toInt() xor 0x01).toByte()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
