package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What happens when the AndroidKeyStore is not there.
 *
 * On a device it always is. Under Robolectric it never is — `KeyStore.getInstance("AndroidKeyStore")`
 * throws — and that accident is worth a test rather than a workaround: it is the closest thing to
 * the real "the key store refuses to open" failure, which does happen on damaged devices and in
 * some work profiles, and the library's promise is that it degrades to a typed error instead of
 * taking the app down with it.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeystoreKeySourceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a key store that cannot be opened is reported as unavailable rather than leaking its exception`() {
        assertFailsWith<StorageKeyUnavailableException> {
            AndroidKeystoreKeySource("any.alias").key()
        }
    }

    @Test
    fun `creating the store does not touch the key store`() {
        // Construction must stay cheap and infallible: a factory called from Application.onCreate
        // cannot be allowed to throw because of a key store that is momentarily unhappy.
        createSecureKeyValueStorage(context, StorageConfig("no.keystore.here"))
    }

    @Test
    fun `a read without a key store is an unavailable failure`() {
        val storage: SecureKeyValueStorage =
            createSecureKeyValueStorage(context, StorageConfig("no.keystore.here"))
        context.getSharedPreferences(secureStoreId("no.keystore.here"), Context.MODE_PRIVATE)
            .edit()
            .putString("k", "ciphertext")
            .commit()

        val result: StorageResult<String?> = storage.get("k")

        assertEquals(StorageError.Unavailable::class, result.errorOrNull()!!::class)
    }

    @Test
    fun `a read of an absent key without a key store is still simply absent`() {
        // Nothing has to be decrypted, so there is no reason to surface a key-store problem here.
        val storage: SecureKeyValueStorage =
            createSecureKeyValueStorage(context, StorageConfig("no.keystore.here"))

        assertEquals(StorageResult.Success(null), storage.get("never-written"))
    }

    @Test
    fun `a write without a key store fails and stores nothing`() {
        val storage: SecureKeyValueStorage =
            createSecureKeyValueStorage(context, StorageConfig("no.keystore.here"))

        val result: StorageResult<Unit> = storage.put("k", "v")

        assertEquals(StorageError.Unavailable::class, result.errorOrNull()!!::class)
        assertNull(
            context.getSharedPreferences(secureStoreId("no.keystore.here"), Context.MODE_PRIVATE)
                .getString("k", null),
        )
    }
}
