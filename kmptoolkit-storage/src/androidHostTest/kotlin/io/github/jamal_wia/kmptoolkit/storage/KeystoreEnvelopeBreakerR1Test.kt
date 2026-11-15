package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Breaker review round 1 of group sto-android (ticket release-5-1-0, STO-4): the A-2 versioned envelope at its
 * boundaries (plan 4.9). Contract: every envelope that is not exactly what this store wrote for this key, in this
 * store, in the current version (a flipped bit anywhere, a truncation at any length, an unknown version byte, trailing
 * bytes, an envelope of another key name or another store) is `Undecryptable(key)`: never a wrong `Success`, never
 * `Unavailable`, never a crash; and that holds both before the migration stamp (legacy reads allowed) and after it.
 * Unpaired surrogates are `InvalidValue(key)` with nothing written and no migration side effect.
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreEnvelopeBreakerR1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var sequence: Int = 0

    @Before
    fun resetKeys() {
        TestKeys.reset()
    }

    private fun uniqueName(): String = "breaker.r1.envelope.${sequence++}.${System.identityHashCode(this)}"

    private fun preferences(name: String): SharedPreferences =
        context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE)

    private fun store(name: String): AndroidSecureKeyValueStorage =
        AndroidSecureKeyValueStorage(preferences(name), KeystoreValueCipher(TestKeys.source(name)), Dispatchers.Unconfined)

    private fun raw(name: String, key: String): ByteArray =
        Base64.decode(requireNotNull(preferences(name).getString(key, null)), Base64.NO_WRAP)

    private fun plant(name: String, key: String, bytes: ByteArray, stamped: Boolean) {
        val edit: SharedPreferences.Editor = preferences(name).edit().putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP))
        if (!stamped) edit.remove(AndroidSecureKeyValueStorage.FORMAT_KEY)
        assertTrue(edit.commit())
    }

    private suspend fun assertUndecryptable(storage: AndroidSecureKeyValueStorage, key: String, context: String) {
        val result: StorageResult<String?> = storage.get(key)
        val error: StorageError? = result.errorOrNull()
        assertTrue(error is StorageError.Undecryptable && error.key == key, "$context: read as $result")
    }

    @Test
    fun `a flipped bit anywhere in the envelope is Undecryptable before and after the migration stamp`() = runTest {
        val seed: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: SEED
        for (stamped: Boolean in listOf(true, false)) {
            val name: String = uniqueName()
            val storage = store(name)
            assertEquals(StorageResult.Success(Unit), storage.put("k", "the secret value"))
            val original: ByteArray = raw(name, "k")
            val random = Random(seed)
            for (index: Int in original.indices) {
                val flipped: ByteArray = original.copyOf()
                flipped[index] = (flipped[index].toInt() xor (1 shl random.nextInt(8))).toByte()
                plant(name, "k", flipped, stamped)
                assertUndecryptable(storage, "k", "seed=$seed stamped=$stamped byte=$index")
            }
            plant(name, "k", original, stamped = true)
            assertEquals(StorageResult.Success<String?>("the secret value"), storage.get("k"), "control stamped=$stamped")
        }
    }

    @Test
    fun `a truncation at every length and trailing bytes are Undecryptable before and after the stamp`() = runTest {
        for (stamped: Boolean in listOf(true, false)) {
            val name: String = uniqueName()
            val storage = store(name)
            assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
            val original: ByteArray = raw(name, "k")
            for (length: Int in 0 until original.size) {
                plant(name, "k", original.copyOf(length), stamped)
                assertUndecryptable(storage, "k", "stamped=$stamped length=$length of ${original.size}")
            }
            plant(name, "k", original + byteArrayOf(0), stamped)
            assertUndecryptable(storage, "k", "stamped=$stamped one trailing byte")
            preferences(name).edit().putString("k", "%%% not base64 %%%").commit()
            assertUndecryptable(storage, "k", "stamped=$stamped not Base64")
        }
    }

    @Test
    fun `an unknown version byte on an otherwise valid envelope is Undecryptable, never read as another version`() = runTest {
        for (version: Int in listOf(0x00, 0x02, 0x7F, 0x80, 0xFF)) {
            for (stamped: Boolean in listOf(true, false)) {
                val name: String = uniqueName()
                val storage = store(name)
                assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
                val bytes: ByteArray = raw(name, "k").also { it[0] = version.toByte() }
                plant(name, "k", bytes, stamped)
                assertUndecryptable(storage, "k", "version=$version stamped=$stamped")
            }
        }
    }

    @Test
    fun `an envelope moved from another key or copied from another store is Undecryptable before and after the stamp`() = runTest {
        for (stamped: Boolean in listOf(true, false)) {
            val name: String = uniqueName()
            val otherStore: String = uniqueName()
            val storage = store(name)
            assertEquals(StorageResult.Success(Unit), storage.put("password", "hunter2"))
            assertEquals(StorageResult.Success(Unit), store(otherStore).put("token", "foreign"))

            plant(name, "token", raw(name, "password"), stamped)
            assertUndecryptable(storage, "token", "stamped=$stamped moved between keys")
            plant(name, "token", raw(otherStore, "token"), stamped)
            assertUndecryptable(storage, "token", "stamped=$stamped copied from another store")
            assertEquals(StorageResult.Success<String?>("hunter2"), storage.get("password"), "control stamped=$stamped")
        }
    }

    @Test
    fun `empty values, an empty key name and a large value round trip and survive a new instance`() = runTest {
        val name: String = uniqueName()
        val storage = store(name)
        val large: String = buildString { repeat(LARGE_CHARS) { index: Int -> append(('a' + index % 26)) } } + "😀"

        assertEquals(StorageResult.Success(Unit), storage.put("empty", ""))
        assertEquals(StorageResult.Success(Unit), storage.put("", "value under the empty key"))
        assertEquals(StorageResult.Success(Unit), storage.put("large", large))

        val fresh = store(name)
        assertEquals(StorageResult.Success<String?>(""), fresh.get("empty"))
        assertEquals(StorageResult.Success<String?>("value under the empty key"), fresh.get(""))
        assertEquals(StorageResult.Success<String?>(large), fresh.get("large"))
        // The empty key's envelope is bound to the empty name: it does not read under another key.
        plant(name, "x", raw(name, ""), stamped = true)
        assertUndecryptable(fresh, "x", "the empty key's envelope moved")
    }

    @Test
    fun `an unpaired surrogate is InvalidValue, writes nothing and runs no migration`() = runTest {
        val name: String = uniqueName()
        // A legacy-looking store: an entry and no stamp, so any write path that reaches the pass would stamp or rewrite.
        preferences(name).edit().putString("legacy", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit()
        val before: Map<String, *> = preferences(name).all
        val storage = store(name)

        for ((key: String, value: String) in listOf("k\uD800" to "v", "k" to "v\uDC00", "\uDBFF" to "\uDFFF\uD800")) {
            val error: StorageError? = storage.put(key, value).errorOrNull()
            assertTrue(error is StorageError.InvalidValue && error.key == key, "put($key) was $error")
        }

        assertEquals(before, preferences(name).all, "nothing was written, stamped or migrated")
        assertFalse(preferences(name).contains(AndroidSecureKeyValueStorage.FORMAT_KEY))
    }

    private companion object {
        const val SEED: Long = 17L
        const val LARGE_CHARS = 1 shl 20
    }
}
