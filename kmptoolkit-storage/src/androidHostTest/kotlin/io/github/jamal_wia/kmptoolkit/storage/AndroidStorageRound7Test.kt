package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round 7: the encrypted Android store when the key changes under it (rotation), unusual store names,
 * and a store file whose entries are rearranged by something other than the store.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidStorageRound7Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetKeys() {
        TestKeys.reset()
    }

    private fun uniqueName(prefix: String): String = "$prefix${UUID.randomUUID().toString().replace("-", "")}"

    private fun secureWith(name: String, key: javax.crypto.SecretKey): SecureKeyValueStorage =
        AndroidSecureKeyValueStorage(
            preferences = context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE),
            cipher = KeystoreValueCipher(TestKeys.fixedSource(key)),
            dispatcher = Dispatchers.IO,
        )

    /**
     * Key rotation: every value written under the old key reads as Undecryptable under the new one
     * (never null, never a crash, never a wrong plaintext); a put under the new key then works for that
     * key only, the other entries stay Undecryptable, and clear() is the way out.
     */
    @Test
    fun `after a key rotation old entries are Undecryptable and a new put is readable beside them`() {
        val name: String = uniqueName("rotate")
        val oldKey: javax.crypto.SecretKey = TestKeys.generate()
        val newKey: javax.crypto.SecretKey = TestKeys.generate()
        runBlocking {
            withTimeout(60_000) {
                val before: SecureKeyValueStorage = secureWith(name, oldKey)
                repeat(20) { index: Int -> assertTrue(before.put("k$index", "v$index") is StorageResult.Success) }

                val after: SecureKeyValueStorage = secureWith(name, newKey)
                repeat(20) { index: Int ->
                    val read: StorageResult<String?> = after.get("k$index")
                    assertTrue(
                        read is StorageResult.Failure && read.error is StorageError.Undecryptable,
                        "k$index under a new key read as $read",
                    )
                }
                assertTrue(after.put("k3", "fresh") is StorageResult.Success)
                assertEquals("fresh", after.get("k3").getOrNull())
                assertTrue(after.get("k4") is StorageResult.Failure)
                // Writing one key must not make the old key's entries readable again under any other key.
                assertTrue(after.remove("k5") is StorageResult.Success)
                assertEquals(null, after.get("k5").getOrNull())
                assertTrue(after.clear() is StorageResult.Success)
                repeat(20) { index: Int -> assertEquals(null, after.get("k$index").getOrNull()) }
            }
        }
    }

    /**
     * The encrypted value of one key moved to another key by something that can edit the file: the
     * moved entry must not read as the other key's value. A store that encrypts without binding the
     * ciphertext to its key lets one secret be presented as another (a token read as a password).
     */
    @Test
    fun `a ciphertext moved to another key does not decrypt as that key's value`() {
        val name: String = uniqueName("swap")
        val key: javax.crypto.SecretKey = TestKeys.generate()
        val preferences: SharedPreferences =
            context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE)
        val storage: SecureKeyValueStorage = AndroidSecureKeyValueStorage(
            preferences = preferences,
            cipher = KeystoreValueCipher(TestKeys.fixedSource(key)),
            dispatcher = Dispatchers.IO,
        )
        runBlocking {
            withTimeout(30_000) {
                assertTrue(storage.put("access_token", "token-value") is StorageResult.Success)
                assertTrue(storage.put("password", "password-value") is StorageResult.Success)
                val tokenCipher: String = preferences.getString("access_token", null)!!
                assertTrue(preferences.edit().putString("password", tokenCipher).commit())

                val read: StorageResult<String?> = storage.get("password")

                assertTrue(
                    read.getOrNull() != "token-value",
                    "the token's ciphertext was accepted as the password's value",
                )
            }
        }
    }

    /** A store name near the file-name limit must fail the same way for every operation, and never throw. */
    @Test
    fun `a plain store with a very long name never throws and reads agree with writes`() {
        val name: String = "n".repeat(230)
        runBlocking {
            withTimeout(30_000) {
                val storage: KeyValueStorage = createKeyValueStorage(context, StorageConfig(name))
                val put: StorageResult<Unit> = storage.put("k", "v")
                val get: StorageResult<String?> = storage.get("k")
                if (put is StorageResult.Success) {
                    assertEquals("v", get.getOrNull(), "a put that succeeded must read back")
                } else {
                    assertTrue(get.getOrNull() != "v", "a put that failed must not read back")
                }
                storage.clear()
            }
        }
    }

    @Test
    fun `store names with dots and leading dots are separate stores that do not leak into each other`() {
        runBlocking {
            withTimeout(30_000) {
                val names: List<String> = listOf("a", "a.", ".a", "..a", "a..", "A", "a-", "a_")
                val stores: List<KeyValueStorage> = names.map { createKeyValueStorage(context, StorageConfig(uniqueName(it))) }
                stores.forEachIndexed { index: Int, store: KeyValueStorage ->
                    assertTrue(store.put("k", "v$index") is StorageResult.Success, "put to ${names[index]}")
                }
                stores.forEachIndexed { index: Int, store: KeyValueStorage ->
                    assertEquals("v$index", store.get("k").getOrNull(), "store ${names[index]}")
                }
                stores.forEach { it.clear() }
            }
        }
    }
}
