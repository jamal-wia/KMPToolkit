package io.github.jamal_wia.kmptoolkit.storage

import android.util.Base64
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
    private val cipher = KeystoreValueCipher { key }

    @Test
    fun `a value round-trips`() {
        assertEquals("value", cipher.decrypt(cipher.encrypt("value")))
    }

    @Test
    fun `an empty value round-trips`() {
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
    }

    @Test
    fun `a value with non-ASCII characters round-trips`() {
        val value = "مرحبا — Привет — 🙂"

        assertEquals(value, cipher.decrypt(cipher.encrypt(value)))
    }

    @Test
    fun `a long value round-trips`() {
        val value: String = "x".repeat(200_000)

        assertEquals(value, cipher.decrypt(cipher.encrypt(value)))
    }

    @Test
    fun `the ciphertext does not contain the plaintext`() {
        assertEquals(false, cipher.encrypt("secret-value").contains("secret-value"))
    }

    @Test
    fun `encrypting the same value twice gives different ciphertext`() {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `the output carries a twelve byte IV ahead of the ciphertext`() {
        // The framing is a compatibility surface: anything written by an older build has to stay
        // readable by a newer one, so the layout is asserted rather than assumed.
        val encoded: String = cipher.encrypt("")
        val bytes: ByteArray = Base64.decode(encoded, Base64.NO_WRAP)

        // 12-byte IV + empty plaintext + 16-byte GCM tag.
        assertEquals(KeystoreValueCipher.IV_LENGTH_BYTES + 16, bytes.size)
    }

    @Test
    fun `the IV differs between two encryptions`() {
        val first: ByteArray = Base64.decode(cipher.encrypt("v"), Base64.NO_WRAP)
        val second: ByteArray = Base64.decode(cipher.encrypt("v"), Base64.NO_WRAP)

        val length: Int = KeystoreValueCipher.IV_LENGTH_BYTES
        assertEquals(false, first.copyOf(length).contentEquals(second.copyOf(length)))
    }

    @Test
    fun `decrypting a value encrypted under another key fails`() {
        val other = KeystoreValueCipher { TestKeys.generate() }

        assertFailsWith<Exception> { other.decrypt(cipher.encrypt("value")) }
    }

    @Test
    fun `decrypting text that is not Base64 fails`() {
        assertFailsWith<Exception> { cipher.decrypt("not base64 at all !!") }
    }

    @Test
    fun `decrypting an input shorter than the IV fails`() {
        val tooShort: String = Base64.encodeToString(ByteArray(4), Base64.NO_WRAP)

        assertFailsWith<IllegalArgumentException> { cipher.decrypt(tooShort) }
    }

    @Test
    fun `decrypting an input that is exactly the IV length fails`() {
        val ivOnly: String = Base64.encodeToString(
            ByteArray(KeystoreValueCipher.IV_LENGTH_BYTES),
            Base64.NO_WRAP,
        )

        assertFailsWith<IllegalArgumentException> { cipher.decrypt(ivOnly) }
    }

    @Test
    fun `decrypting an empty string fails`() {
        assertFailsWith<IllegalArgumentException> { cipher.decrypt("") }
    }

    @Test
    fun `a single flipped bit in the ciphertext fails authentication`() {
        val bytes: ByteArray = Base64.decode(cipher.encrypt("authentic"), Base64.NO_WRAP)
        val target: Int = KeystoreValueCipher.IV_LENGTH_BYTES
        bytes[target] = (bytes[target].toInt() xor 0x01).toByte()

        assertFailsWith<Exception> {
            cipher.decrypt(Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    @Test
    fun `a flipped bit in the IV fails authentication`() {
        val bytes: ByteArray = Base64.decode(cipher.encrypt("authentic"), Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()

        assertFailsWith<Exception> {
            cipher.decrypt(Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    @Test
    fun `a key source failure propagates rather than being swallowed`() {
        val failing = KeystoreValueCipher(TestKeys.unavailableSource())

        assertFailsWith<StorageKeyUnavailableException> { failing.encrypt("v") }
    }

    @Test
    fun `values encrypted by one instance are readable by another over the same key`() {
        val encoded: String = KeystoreValueCipher { key }.encrypt("value")

        assertEquals("value", KeystoreValueCipher { key }.decrypt(encoded))
    }

    @Test
    fun `the encoded output is single line Base64`() {
        // NO_WRAP matters: a newline inside a SharedPreferences value is legal but makes the file
        // unreadable with the usual command-line tools, and a mismatched flag between encode and
        // decode would break the round trip.
        assertTrue(cipher.encrypt("x".repeat(1_000)).none { it == '\n' })
    }
}
