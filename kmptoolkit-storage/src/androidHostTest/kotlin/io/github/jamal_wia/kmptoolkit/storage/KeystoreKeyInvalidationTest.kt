package io.github.jamal_wia.kmptoolkit.storage

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A key a source handed out and the platform then rejected must not stay the answer for the life of
 * the process: [AndroidKeystoreKeySource] caches per alias for the whole process, and
 * [KeystoreValueCipher] reports a rejected key so the next lookup goes to the key store again.
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreKeyInvalidationTest {

    /** A key store that answers [answers] in order, then its last answer for ever. */
    private class ScriptedKeyStore(private vararg val answers: SecretKey) : AliasedKeyStore {
        var lookups: Int = 0

        override fun existingKey(alias: String): SecretKey = answers[minOf(lookups++, answers.lastIndex)]

        override fun generateKey(alias: String): SecretKey = error("an existing key is always answered")
    }

    /** Not an AES key: `Cipher.init` rejects it with `InvalidKeyException`, as a dead key-store handle is. */
    private fun deadKey(): SecretKey = SecretKeySpec(ByteArray(5), "AES")

    @Test
    fun `a key the platform rejects is replaced by a fresh lookup instead of failing every later use`() = runTest {
        val good: SecretKey = TestKeys.generate()
        val store = ScriptedKeyStore(deadKey(), good)
        val cipher = KeystoreValueCipher(AndroidKeystoreKeySource("invalidate.alias", store))

        val encoded: String = cipher.encrypt("secret")

        assertEquals("secret", cipher.decrypt(encoded))
        assertEquals(2, store.lookups, "the dead handle was dropped and the store asked again")
    }

    @Test
    fun `a second instance over the alias shares the key, and a stale report never drops a newer key`() = runTest {
        val store = ScriptedKeyStore(TestKeys.generate())
        val first = AndroidKeystoreKeySource("shared.alias", store)
        val key: SecretKey = first.key()

        first.invalidate(deadKey()) // not the cached handle: nothing to forget

        assertSame(key, AndroidKeystoreKeySource("shared.alias", store).key())
        assertEquals(1, store.lookups, "one key-store lookup serves every instance in the process")
    }
}
