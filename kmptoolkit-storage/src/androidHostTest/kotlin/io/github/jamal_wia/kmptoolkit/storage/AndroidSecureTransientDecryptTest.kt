package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.KeyStoreException
import androidx.test.core.app.ApplicationProvider
import java.security.InvalidKeyException
import java.security.ProviderException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A [ValueCipher] whose decryption fails with a scripted exception. */
private class FailingCipher(private val failure: Exception) : ValueCipher {
    override suspend fun encrypt(plaintext: String): String = plaintext
    override suspend fun decrypt(encoded: String): String = throw failure
}

private fun storageFailingWith(failure: Exception): AndroidSecureKeyValueStorage {
    val context: Context = ApplicationProvider.getApplicationContext()
    val preferences: SharedPreferences =
        context.getSharedPreferences("transient-decrypt-test", Context.MODE_PRIVATE)
    preferences.edit().putString("k", "ciphertext").commit()
    return AndroidSecureKeyValueStorage(
        preferences = preferences,
        cipher = FailingCipher(failure),
        dispatcher = UnconfinedTestDispatcher(),
    )
}

/**
 * A real `android.security.KeyStoreException`. The constructor is hidden from the compile-time
 * stubs, so it is reached reflectively; [errorCode] is the platform's internal code and decides
 * whether the platform itself calls the failure transient.
 */
private fun keyStoreException(errorCode: Int): KeyStoreException =
    KeyStoreException::class.java
        .getConstructor(Int::class.javaPrimitiveType, String::class.java)
        .newInstance(errorCode, "scripted")

/** An internal code the platform's own retry policy treats as transient (checked by a test). */
private const val TRANSIENT_CODE: Int = -49

/** An internal code the platform treats as permanent (checked by a test). */
private const val PERMANENT_CODE: Int = 7

/**
 * On API 33+, a decrypt failure that the platform's own `KeyStoreException` calls transient is
 * `Unavailable` (retry later), anything else stays `Undecryptable` (discard).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransientDecryptFailureApi33Test {

    private val transient: KeyStoreException =
        keyStoreException(TRANSIENT_CODE)
    private val permanent: KeyStoreException =
        keyStoreException(PERMANENT_CODE)

    @Test
    fun `fixtures are what the tests claim`() {
        assertTrue(transient.isTransientFailure)
        assertEquals(false, permanent.isTransientFailure)
    }

    @Test
    fun `a transient keystore failure wrapped in ProviderException is unavailable`() = runTest {
        val wrapped = ProviderException("keystore", transient)

        val error: StorageError? = storageFailingWith(wrapped).get("k").errorOrNull()

        assertEquals(StorageError.Unavailable::class, error!!::class)
        assertSame(wrapped, (error as StorageError.Unavailable).cause)
    }

    @Test
    fun `a transient failure deep in a multi level chain is unavailable`() = runTest {
        val wrapped = ProviderException("outer", InvalidKeyException("middle", transient))

        val error: StorageError? = storageFailingWith(wrapped).get("k").errorOrNull()

        assertEquals(StorageError.Unavailable::class, error!!::class)
    }

    @Test
    fun `a transient keystore failure at the top level is unavailable`() = runTest {
        val error: StorageError? = storageFailingWith(transient).get("k").errorOrNull()

        assertEquals(StorageError.Unavailable::class, error!!::class)
    }

    @Test
    fun `a permanent keystore failure stays undecryptable`() = runTest {
        val error: StorageError? =
            storageFailingWith(ProviderException("keystore", permanent)).get("k").errorOrNull()

        assertEquals("k", (error as StorageError.Undecryptable).key)
    }

    @Test
    fun `a failure with no keystore exception in its chain stays undecryptable`() = runTest {
        val error: StorageError? =
            storageFailingWith(ProviderException("boom", IllegalStateException("x"))).get("k")
                .errorOrNull()

        assertEquals("k", (error as StorageError.Undecryptable).key)
    }

    @Test
    fun `the SDK gate rejects a transient failure below API 33`() {
        assertEquals(false, isTransientKeystoreFailure(ProviderException("k", transient), sdkInt = 32))
        assertEquals(true, isTransientKeystoreFailure(ProviderException("k", transient), sdkInt = 33))
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val a = IllegalStateException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)

        assertEquals(false, isTransientKeystoreFailure(a))
    }
}

/**
 * Below API 33 `isTransientFailure()` does not exist, so every decrypt failure stays
 * `Undecryptable`, exactly as before the transient mapping was added. This class must not name
 * `KeyStoreException`: the class is absent from the SDK 32 platform.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class TransientDecryptFailureApi32Test {

    @Test
    fun `a wrapped failure is undecryptable on API 32`() = runTest {
        val failure = ProviderException("keystore", IllegalStateException("transient-looking"))

        val error: StorageError? = storageFailingWith(failure).get("k").errorOrNull()

        assertEquals("k", (error as StorageError.Undecryptable).key)
    }
}
