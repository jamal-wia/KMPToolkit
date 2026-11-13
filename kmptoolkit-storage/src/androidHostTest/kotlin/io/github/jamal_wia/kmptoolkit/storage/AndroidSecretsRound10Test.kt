package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B round 10, Q2 and Q4 (upgrade paths and the storage module's secrets). Contract: 05-platform-notes "The Android failure you
 * must handle" (an entry that fails the tag is `Undecryptable`, an entry written before 4.1.0 stays readable, the format on disk is
 * `Base64(iv || ciphertext || tag)`, a downgrade to 4.0.0 cannot read what 4.1.0 wrote) and 03-guide ("no entry made unreadable by
 * the overlap", values are never torn).
 *
 * The 3.0.0 and 4.0.0 entries are written here with the JCE directly, from the documented framing (12 byte IV, 128 bit tag,
 * Base64 without wrapping, no associated data): a writer that shares no code with the one under test.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSecretsRound10Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Fixed seed (override with -Dkmptoolkit.test.seed); only the IV of the independent writer draws from it.
    private val random: Random = Random(System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: 10L)

    // timing-hygiene-ignore: TH3 name unique store name per test, never a value under test
    private fun unique(prefix: String): String = "$prefix${UUID.randomUUID().toString().replace("-", "")}"

    private fun securePreferences(name: String): SharedPreferences =
        context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE)

    private fun secure(name: String, keyName: String = name): SecureKeyValueStorage =
        AndroidSecureKeyValueStorage(securePreferences(name), KeystoreValueCipher(TestKeys.source(keyName)), Dispatchers.IO)

    private fun plain(name: String): KeyValueStorage =
        AndroidKeyValueStorage(context.getSharedPreferences(plainStoreId(name), Context.MODE_PRIVATE), Dispatchers.IO)

    /** The 3.0.0 / 4.0.0 writer: `Base64(iv || ciphertext || tag)`, AES/GCM/NoPadding, 12 byte IV, 128 bit tag, no associated data. */
    private fun legacyEncrypt(key: SecretKey, plaintext: String, aad: ByteArray? = null): String {
        val iv: ByteArray = random.nextBytes(12)
        val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        return Base64.encodeToString(iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun jceDecrypt(key: SecretKey, encoded: String, aad: ByteArray?): String {
        val bytes: ByteArray = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }

    @Test
    fun `entries written by an independent 3_0_0 style writer are read for every key and entries written now carry the key name as associated data`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val name: String = unique("legacy")
            val key: SecretKey = TestKeys.source(name).key()
            val values: Map<String, String> = mapOf(
                "refresh_token" to "r-1", "empty" to "", "unicode" to "жук 😀", "multi" to "a\nb\r\n", "long" to "x".repeat(200_000),
            )
            val edit: SharedPreferences.Editor = securePreferences(name).edit()
            values.forEach { (k: String, v: String) -> edit.putString(k, legacyEncrypt(key, v)) }
            assertTrue(edit.commit())
            val store: SecureKeyValueStorage = secure(name)
            values.forEach { (k: String, v: String) -> assertEquals(StorageResult.Success<String?>(v), store.get(k), "3.0.0 entry $k") }

            // What is written now is bound to its key: it opens with the documented associated data and not without, which is
            // exactly why a downgrade to 4.0.0 (no associated data) reads it as Undecryptable.
            assertEquals(StorageResult.Success(Unit), store.put("refresh_token", "r-2"))
            val raw: String = requireNotNull(securePreferences(name).getString("refresh_token", null))
            assertEquals("r-2", jceDecrypt(key, raw, "kmptoolkit-storage-key:refresh_token".toByteArray(Charsets.UTF_8)))
            assertFailsWith<javax.crypto.AEADBadTagException> { jceDecrypt(key, raw, null) }
            // The entries not written again keep reading, whatever the neighbour did.
            assertEquals(StorageResult.Success<String?>("жук 😀"), store.get("unicode"))
        }
    }

    @Test
    fun `an entry cut at any length or with a byte added reads as Undecryptable for its own key never as a value or an exception`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val name: String = unique("cut")
            val store: SecureKeyValueStorage = secure(name)
            assertEquals(StorageResult.Success(Unit), store.put("k", "a value of some length, 40 bytes or so....."))
            val whole: ByteArray = Base64.decode(requireNotNull(securePreferences(name).getString("k", null)), Base64.NO_WRAP)
            val problems: MutableList<String> = mutableListOf()
            fun check(label: String, text: String) {
                securePreferences(name).edit().putString("k", text).commit()
                val read: StorageResult<String?> = runBlocking { store.get("k") }
                val error: StorageError? = (read as? StorageResult.Failure)?.error
                if (error !is StorageError.Undecryptable || error.key != "k") problems += "$label -> $read"
            }
            for (length: Int in 0 until whole.size) check("cut at $length bytes", Base64.encodeToString(whole.copyOf(length), Base64.NO_WRAP))
            check("one byte added", Base64.encodeToString(whole + byteArrayOf(0), Base64.NO_WRAP))
            assertEquals(emptyList(), problems)
            // Android's Base64 decoder is lenient (it takes unpadded text and ignores characters outside the alphabet), so
            // these two spellings still name the same bytes: the result is the original value or Undecryptable, never another value.
            for (text: String in listOf(Base64.encodeToString(whole, Base64.NO_WRAP).dropLast(1), Base64.encodeToString(whole, Base64.NO_WRAP) + "!!")) {
                securePreferences(name).edit().putString("k", text).commit()
                val read: StorageResult<String?> = store.get("k")
                val expected: String = "a value of some length, 40 bytes or so....."
                assertTrue(read == StorageResult.Success<String?>(expected) || (read as? StorageResult.Failure)?.error is StorageError.Undecryptable, "$text -> $read")
            }
            // The next put replaces the damaged entry whole.
            assertEquals(StorageResult.Success(Unit), store.put("k", "fresh"))
            assertEquals(StorageResult.Success<String?>("fresh"), store.get("k"))
        }
    }

    @Test
    fun `a wrong key and a rotated key give Undecryptable for every old entry and a new put is readable beside them`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val name: String = unique("rot")
            val before: SecureKeyValueStorage = secure(name, keyName = "${name}A")
            (0 until 20).forEach { i: Int -> assertEquals(StorageResult.Success(Unit), before.put("k$i", "v$i")) }
            val after: SecureKeyValueStorage = secure(name, keyName = "${name}B")
            (0 until 20).forEach { i: Int ->
                val read: StorageResult<String?> = after.get("k$i")
                assertTrue(read is StorageResult.Failure && read.error is StorageError.Undecryptable, "k$i under another key: $read")
            }
            assertEquals(StorageResult.Success(Unit), after.put("k0", "new"))
            assertEquals(StorageResult.Success<String?>("new"), after.get("k0"))
            assertTrue(after.get("k1") is StorageResult.Failure, "an entry that was not rewritten is still lost")
            // The old key opens what it wrote and not what the new key wrote: the two generations do not leak into each other.
            assertEquals(StorageResult.Success<String?>("v1"), before.get("k1"))
            assertTrue(before.get("k0") is StorageResult.Failure)
            // A clear drops the damaged entries with the good ones.
            assertEquals(StorageResult.Success(Unit), after.clear())
            assertEquals(StorageResult.Success<String?>(null), after.get("k1"))
        }
    }

    @Test
    fun `readers and writers of one key on both stores only ever see absent or one whole written value`() = runBlocking<Unit> {
        withTimeout(120_000) {
            for (secureStore: Boolean in listOf(false, true)) {
                val name: String = unique(if (secureStore) "sec1" else "pla1")
                val store: KeyValueStorage = if (secureStore) secure(name) else plain(name)
                // Value of writer w, round r: "w-r:" then a body whose length and content are a function of (w, r), so a torn value is detectable.
                fun valueOf(w: Int, r: Int): String = "$w-$r:" + "${w}${r}".repeat(50 + 7 * w + r)
                fun whole(text: String): Boolean {
                    val head: List<String> = text.substringBefore(':').split('-')
                    if (head.size != 2) return false
                    val w: Int = head[0].toIntOrNull() ?: return false
                    val r: Int = head[1].toIntOrNull() ?: return false
                    return text == valueOf(w, r)
                }
                // The key is resolved once before the race: TestKeys (test code) is a plain map, which the production key source is not.
                assertEquals(StorageResult.Success(Unit), store.put("warm-up", "x"))
                val start = CompletableDeferred<Unit>()
                val seen: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
                val jobs = (0 until 4).map { w: Int ->
                    async(Dispatchers.Default) {
                        start.await()
                        for (r: Int in 0 until 15) assertEquals(StorageResult.Success(Unit), store.put("hot", valueOf(w, r)))
                    }
                } + (0 until 4).map {
                    async(Dispatchers.Default) {
                        start.await()
                        repeat(60) {
                            when (val read: StorageResult<String?> = store.get("hot")) {
                                is StorageResult.Success -> read.value?.let { text: String -> if (!whole(text)) seen += "torn: ${text.take(40)}" }
                                is StorageResult.Failure -> seen += "failure: ${read.error}"
                            }
                        }
                    }
                }
                start.complete(Unit)
                jobs.awaitAll()
                assertEquals(emptyList(), seen.toList(), "secure=$secureStore")
                val last: String? = store.get("hot").getOrNull()
                assertTrue(last != null && whole(last), "the final value must be one of the written ones: secure=$secureStore")
            }
        }
    }

    @Test
    fun `megabyte values of both stores round trip cold and a very long secure store name never throws`() = runBlocking<Unit> {
        withTimeout(120_000) {
            val big: String = buildString { repeat(1 shl 20) { append(('a' + it % 26)) } }
            val bigNl: String = big + "\n"
            val name: String = unique("mb")
            val secureStore: SecureKeyValueStorage = secure(name)
            val plainStore: KeyValueStorage = plain(name)
            for (store: KeyValueStorage in listOf(secureStore, plainStore)) {
                assertEquals(StorageResult.Success(Unit), store.put("big", big))
                assertEquals(StorageResult.Success(Unit), store.put("bigNl", bigNl))
                assertEquals(StorageResult.Success(Unit), store.put("empty", ""))
                assertEquals(StorageResult.Success<String?>(big), store.get("big"))
                assertEquals(StorageResult.Success<String?>(bigNl), store.get("bigNl"))
                assertEquals(StorageResult.Success<String?>(""), store.get("empty"))
            }
            // A name near the file name limit: the file name is name + ".kmptoolkit.securestorage": typed or working, never an exception.
            val longName: String = "n".repeat(240)
            val longStore: SecureKeyValueStorage = secure(longName)
            val put: StorageResult<Unit> = longStore.put("k", "v")
            val get: StorageResult<String?> = longStore.get("k")
            assertTrue(put is StorageResult.Success || put is StorageResult.Failure)
            if (put is StorageResult.Success) assertEquals(StorageResult.Success<String?>("v"), get)
        }
    }
}
