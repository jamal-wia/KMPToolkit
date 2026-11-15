package io.github.jamal_wia.kmptoolkit.storage

import android.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The cipher itself, over a locally generated AES-256 key — see [TestKeys] for why the key does not
 * come from the AndroidKeyStore here.
 *
 * Robolectric rather than a plain JVM test only because `android.util.Base64` is an Android class.
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreValueCipherTest {

    private val key: SecretKey = TestKeys.generate()
    private val cipher = KeystoreValueCipher(TestKeys.fixedSource(key))

    @Test
    fun `a value round-trips`() = runTest {
        assertEquals("value", cipher.decrypt(cipher.encrypt("value")))
    }

    @Test
    fun `an empty value round-trips`() = runTest {
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
    }

    @Test
    fun `a value with non-ASCII characters round-trips`() = runTest {
        val value = "مرحبا — Привет — 🙂"

        assertEquals(value, cipher.decrypt(cipher.encrypt(value)))
    }

    @Test
    fun `a long value round-trips`() = runTest {
        val value: String = "x".repeat(200_000)

        assertEquals(value, cipher.decrypt(cipher.encrypt(value)))
    }

    @Test
    fun `the ciphertext does not contain the plaintext`() = runTest {
        assertEquals(false, cipher.encrypt("secret-value").contains("secret-value"))
    }

    @Test
    fun `encrypting the same value twice gives different ciphertext`() = runTest {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `the output carries a version byte and a twelve byte IV ahead of the ciphertext`() = runTest {
        // The framing is a compatibility surface: anything written by an older build has to stay
        // readable by a newer one, so the layout is asserted rather than assumed.
        val encoded: String = cipher.encrypt("")
        val bytes: ByteArray = Base64.decode(encoded, Base64.NO_WRAP)

        assertEquals(KeystoreValueCipher.FORMAT_VERSION, bytes[0], "the first byte is the envelope version")
        // Version byte + 12-byte IV + empty plaintext + 16-byte GCM tag.
        assertEquals(1 + KeystoreValueCipher.IV_LENGTH_BYTES + 16, bytes.size)
    }

    @Test
    fun `the IV differs between two encryptions`() = runTest {
        val first: ByteArray = Base64.decode(cipher.encrypt("v"), Base64.NO_WRAP)
        val second: ByteArray = Base64.decode(cipher.encrypt("v"), Base64.NO_WRAP)

        val length: Int = KeystoreValueCipher.IV_LENGTH_BYTES
        assertEquals(false, first.copyOf(length).contentEquals(second.copyOf(length)))
    }

    @Test
    fun `decrypting a value encrypted under another key fails`() = runTest {
        val other = KeystoreValueCipher(TestKeys.fixedSource(TestKeys.generate()))

        assertFailsWith<Exception> { other.decrypt(cipher.encrypt("value")) }
    }

    @Test
    fun `decrypting text that is not Base64 fails`() = runTest {
        assertFailsWith<Exception> { cipher.decrypt("not base64 at all !!") }
    }

    @Test
    fun `decrypting an input shorter than the IV fails`() = runTest {
        val tooShort: String = Base64.encodeToString(ByteArray(4), Base64.NO_WRAP)

        assertFailsWith<IllegalArgumentException> { cipher.decrypt(tooShort) }
    }

    @Test
    fun `decrypting an input that is exactly the IV length fails`() = runTest {
        val ivOnly: String = Base64.encodeToString(
            ByteArray(KeystoreValueCipher.IV_LENGTH_BYTES),
            Base64.NO_WRAP,
        )

        assertFailsWith<IllegalArgumentException> { cipher.decrypt(ivOnly) }
    }

    @Test
    fun `decrypting an empty string fails`() = runTest {
        assertFailsWith<IllegalArgumentException> { cipher.decrypt("") }
    }

    @Test
    fun `a single flipped bit in the ciphertext fails authentication`() = runTest {
        val bytes: ByteArray = Base64.decode(cipher.encrypt("authentic"), Base64.NO_WRAP)
        val target: Int = KeystoreValueCipher.IV_LENGTH_BYTES
        bytes[target] = (bytes[target].toInt() xor 0x01).toByte()

        assertFailsWith<Exception> {
            cipher.decrypt(Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    @Test
    fun `a flipped bit in the IV fails authentication`() = runTest {
        val bytes: ByteArray = Base64.decode(cipher.encrypt("authentic"), Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()

        assertFailsWith<Exception> {
            cipher.decrypt(Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    @Test
    fun `a key source failure propagates rather than being swallowed`() = runTest {
        val failing = KeystoreValueCipher(TestKeys.unavailableSource())

        assertFailsWith<StorageKeyUnavailableException> { failing.encrypt("v") }
    }

    @Test
    fun `values encrypted by one instance are readable by another over the same key`() = runTest {
        val encoded: String = KeystoreValueCipher(TestKeys.fixedSource(key)).encrypt("value")

        assertEquals("value", KeystoreValueCipher(TestKeys.fixedSource(key)).decrypt(encoded))
    }

    @Test
    fun `the encoded output is single line Base64`() = runTest {
        // NO_WRAP matters: a newline inside a SharedPreferences value is legal but makes the file
        // unreadable with the usual command-line tools, and a mismatched flag between encode and
        // decode would break the round trip.
        assertTrue(cipher.encrypt("x".repeat(1_000)).none { it == '\n' })
    }

    /** The 4.0.0 writer: `Base64(iv || ciphertext || tag)`, no version byte, no associated data. */
    private fun legacyEncrypt(plaintext: String, aad: ByteArray? = null): String {
        val jce: javax.crypto.Cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        jce.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        if (aad != null) jce.updateAAD(aad)
        return Base64.encodeToString(jce.iv + jce.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    @Test
    fun `an entry written without a version byte is read only through the legacy reader`() = runTest {
        val unbound: String = legacyEncrypt("old value")
        val bound: String =
            legacyEncrypt("bound value", "kmptoolkit-storage-key:access_token".toByteArray(Charsets.UTF_8))

        // 4.0.0 entries open for any key, 5.0.0 entries for their own key, through the legacy reader only.
        assertEquals("old value", cipher.decryptLegacyFor("access_token", unbound))
        assertEquals("old value", cipher.decryptLegacyFor("other", unbound))
        assertEquals("bound value", cipher.decryptLegacyFor("access_token", bound))
        // The fallback that accepted unversioned entries for ever is closed in the ordinary reader.
        assertFailsWith<javax.crypto.BadPaddingException> { cipher.decryptFor("access_token", unbound) }
        assertFailsWith<javax.crypto.BadPaddingException> { cipher.decryptFor("access_token", bound) }
        assertFailsWith<javax.crypto.BadPaddingException> { cipher.decrypt(unbound) }
    }

    @Test
    fun `a versioned entry that is changed in its version byte or moved to another key fails its tag`() = runTest {
        val bytes: ByteArray = Base64.decode(cipher.encryptFor("k", "v"), Base64.NO_WRAP)
        for (version: Int in listOf(0, 2, 0x7F, 0xFF)) {
            val changed: ByteArray = bytes.copyOf().also { it[0] = version.toByte() }
            assertFailsWith<javax.crypto.BadPaddingException>("version $version") {
                cipher.decryptFor("k", Base64.encodeToString(changed, Base64.NO_WRAP))
            }
        }
        // A version byte stripped, as a downgrading writer would leave it, is no entry of either format.
        val stripped: String = Base64.encodeToString(bytes.copyOfRange(1, bytes.size), Base64.NO_WRAP)
        assertFailsWith<javax.crypto.BadPaddingException> { cipher.decryptFor("k", stripped) }
        assertFailsWith<javax.crypto.BadPaddingException> { cipher.decryptLegacyFor("k", stripped) }
        assertFailsWith<javax.crypto.BadPaddingException> { cipher.decryptLegacyFor("k", cipher.encryptFor("k", "v")) }
    }

    @Test
    fun `a bound entry reads back for its own key only`() = runTest {
        val bound: String = cipher.encryptFor("access_token", "token")
        assertEquals("token", cipher.decryptFor("access_token", bound))
        assertFailsWith<javax.crypto.BadPaddingException> { cipher.decryptFor("password", bound) }
        // A bound entry is not readable without its key either: the binding is part of the tag.
        assertFailsWith<javax.crypto.BadPaddingException> { cipher.decrypt(bound) }
    }

    @Test
    fun `the empty key name is bound too`() = runTest {
        val bound: String = cipher.encryptFor("", "v")
        assertEquals("v", cipher.decryptFor("", bound))
        assertFailsWith<javax.crypto.BadPaddingException> { cipher.decryptFor("other", bound) }
    }
}
