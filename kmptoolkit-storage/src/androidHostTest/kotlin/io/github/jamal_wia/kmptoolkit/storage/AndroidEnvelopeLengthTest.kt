package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import javax.crypto.BadPaddingException
import javax.crypto.SecretKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Group sto-android, breaker finding M1 (review round 5): every place that parses stored bytes classifies a too-short
 * entry by LENGTH before any crypto, so the answer does not depend on how the platform provider reports a GCM input
 * shorter than its tag (the AndroidKeyStore: `IllegalBlockSizeException`, the JDK: `AEADBadTagException`).
 *
 * Runs the production [KeystoreValueCipher] over [DeviceLikeGcmProvider] (emulated, not observed on a device). The
 * lengths walked are every size from 0 to 40 bytes with the first byte equal to the version byte and different from it,
 * which covers empty, one byte, exactly the IV, exactly the header, the IV plus the tag and the tag minus one.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidEnvelopeLengthTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun install() {
        TestKeys.reset()
        DeviceLikeGcmProvider.install()
    }

    @After
    fun uninstall() {
        DeviceLikeGcmProvider.uninstall()
    }

    private fun cipher(name: String): ValueCipher {
        val material: SecretKey = runBlocking { TestKeys.source(name).key() }
        return KeystoreValueCipher(TestKeys.fixedSource(DeviceLikeKey(material)))
    }

    private fun entry(length: Int, firstByte: Byte, seed: Long): String {
        val bytes: ByteArray = Random(seed + length).nextBytes(length)
        if (length > 0) bytes[0] = firstByte
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @Test
    fun `the ordinary reader answers a malformed length with a bad-padding or argument failure never a platform one`() = runTest {
        val cipher: ValueCipher = cipher("length.versioned")
        for (length: Int in 0..MAX_LENGTH) {
            for (first: Byte in listOf(KeystoreValueCipher.FORMAT_VERSION, 0)) {
                val encoded: String = entry(length, first, SEED)
                val failure: Throwable = runCatching { cipher.decryptFor("k", encoded) }.exceptionOrNull()
                    ?: fail("length=$length first=$first seed=$SEED must not decrypt")
                assertTrue(
                    failure is BadPaddingException || failure is IllegalArgumentException,
                    "length=$length first=$first seed=$SEED: expected a malformed-entry failure, was $failure",
                )
            }
        }
    }

    @Test
    fun `an entry shorter than the version header and tag is not a version-1 envelope so the legacy reader gets it`() = runTest {
        val cipher: ValueCipher = cipher("length.legacy.route")
        val tagBytes: Int = KeystoreValueCipher.TAG_LENGTH_BITS / 8
        for (length: Int in (KeystoreValueCipher.HEADER_LENGTH_BYTES + 1) until (KeystoreValueCipher.HEADER_LENGTH_BYTES + tagBytes)) {
            assertFailsWith<BadPaddingException>("length=$length seed=$SEED") {
                cipher.decryptFor("k", entry(length, KeystoreValueCipher.FORMAT_VERSION, SEED))
            }
        }
    }

    @Test
    fun `the legacy reader refuses an entry shorter than the IV and the tag as malformed`() = runTest {
        val cipher: ValueCipher = cipher("length.legacy")
        val minimum: Int = KeystoreValueCipher.IV_LENGTH_BYTES + KeystoreValueCipher.TAG_LENGTH_BITS / 8
        for (length: Int in 0 until minimum) {
            assertFailsWith<IllegalArgumentException>("length=$length seed=$SEED") {
                cipher.decryptLegacyFor("k", entry(length, 7, SEED))
            }
        }
    }

    @Test
    fun `a store with a malformed entry of every length finishes the pass once and reads the good entry`() = runTest {
        val name = "length.pass"
        val preferences: SharedPreferences = context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE)
        val cipher: ValueCipher = cipher(name)
        val editor: SharedPreferences.Editor = preferences.edit()
        for (length: Int in 0..MAX_LENGTH) {
            editor.putString("cut.v1.$length", entry(length, KeystoreValueCipher.FORMAT_VERSION, SEED))
            editor.putString("cut.other.$length", entry(length, 9, SEED + 1000))
        }
        // A readable 4.0.0 entry whose IV is the version byte first, the boundary that used to be reported lost.
        val iv: ByteArray = Random(SEED).nextBytes(IV_BYTES).also { it[0] = KeystoreValueCipher.FORMAT_VERSION }
        val material: SecretKey = TestKeys.source(name).key()
        val jce: javax.crypto.Cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        jce.init(javax.crypto.Cipher.ENCRYPT_MODE, material, javax.crypto.spec.GCMParameterSpec(128, iv))
        editor.putString("legacy.empty", Base64.encodeToString(iv + jce.doFinal(ByteArray(0)), Base64.NO_WRAP))
        editor.commit()
        val storage = AndroidSecureKeyValueStorage(preferences, cipher, Dispatchers.Unconfined)

        assertEquals(StorageResult.Success<String?>(""), storage.get("legacy.empty"), "seed=$SEED")
        assertTrue(
            preferences.contains(AndroidSecureKeyValueStorage.FORMAT_KEY),
            "seed=$SEED: malformed entries of every length must not keep the one-time pass open",
        )
        for (length: Int in 0..MAX_LENGTH) {
            assertTrue(storage.get("cut.v1.$length").errorOrNull() is StorageError.Undecryptable, "cut.v1.$length")
            assertTrue(storage.get("cut.other.$length").errorOrNull() is StorageError.Undecryptable, "cut.other.$length")
        }
    }

    private companion object {
        const val SEED: Long = 4711L
        const val MAX_LENGTH: Int = 40
        const val IV_BYTES: Int = 12
    }
}
