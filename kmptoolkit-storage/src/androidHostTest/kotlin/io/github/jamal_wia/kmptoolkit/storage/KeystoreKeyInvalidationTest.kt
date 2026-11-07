package io.github.jamal_wia.kmptoolkit.storage

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey =
            answers[minOf(lookups++, answers.lastIndex)]

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
    fun `a transient keystore fault is rethrown without dropping the key or looking it up again`() = runTest {
        val store = ScriptedKeyStore(deadKey(), TestKeys.generate())
        val source = AndroidKeystoreKeySource("transient.alias", store)
        var invalidations = 0
        val counting = object : SecretKeySource {
            override suspend fun key(): SecretKey = source.key()
            override suspend fun invalidate(stale: SecretKey) {
                invalidations++
                source.invalidate(stale)
            }
        }
        val cipher = KeystoreValueCipher(counting, isTransient = { true })

        assertFailsWith<java.security.InvalidKeyException> { cipher.encrypt("secret") }

        assertEquals(0, invalidations, "a transient fault never reports the key as dead")
        assertEquals(1, store.lookups, "and the key store is not asked again while it is unwell")
    }

    /** First lookup answers [first]; later ones are unrecoverable entries, deleted only if allowed. */
    private class UnrecoverableAfterFirst(private val first: SecretKey) : AliasedKeyStore {
        var lookups: Int = 0
        var deletions: Int = 0
        var generated: Int = 0

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? {
            if (lookups++ == 0) return first
            if (!mayDelete) throw java.security.UnrecoverableKeyException("keystore fault")
            deletions++
            return null
        }

        override fun generateKey(alias: String): SecretKey {
            generated++
            return TestKeys.generate()
        }
    }

    @Test
    fun `a re-lookup after a rejected key never deletes the alias and surfaces as unavailable`() = runTest {
        val store = UnrecoverableAfterFirst(deadKey())
        val cipher = KeystoreValueCipher(AndroidKeystoreKeySource("recovery.alias", store))

        assertFailsWith<StorageKeyUnavailableException> { cipher.encrypt("secret") }

        assertEquals(0, store.deletions, "the recovery lookup never deletes the alias")
        assertEquals(0, store.generated, "and never replaces the key")
    }

    /** An alias whose entry is unrecoverable until it is deleted; like the platform, it refuses a deletion that is not allowed. */
    private class UnrecoverableUntilDeleted : AliasedKeyStore {
        val mayDeleteCalls: MutableList<Boolean> = mutableListOf()
        var deletions: Int = 0
        var generated: Int = 0

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? {
            mayDeleteCalls += mayDelete
            if (deletions > 0) return null
            if (!mayDelete) throw java.security.UnrecoverableKeyException("entry cannot be recovered")
            deletions++
            return null
        }

        override fun generateKey(alias: String): SecretKey {
            generated++
            return TestKeys.generate()
        }
    }

    @Test
    fun `the first resolution in the process still deletes and regenerates an unrecoverable alias`() = runTest {
        val store = UnrecoverableUntilDeleted()
        val cipher = KeystoreValueCipher(AndroidKeystoreKeySource("first.recovery.alias", store))

        val encoded: String = cipher.encrypt("secret")

        assertEquals("secret", cipher.decrypt(encoded))
        assertEquals(listOf(true), store.mayDeleteCalls, "the first resolution is the one allowed to delete")
        assertEquals(1, store.deletions, "the unrecoverable alias was deleted")
        assertEquals(1, store.generated, "and a new key was generated in its place")
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
