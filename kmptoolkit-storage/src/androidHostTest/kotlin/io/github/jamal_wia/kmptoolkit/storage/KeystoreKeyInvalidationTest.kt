package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.core.app.ApplicationProvider
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
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

        override fun deleteKey(alias: String): Unit = error("only a permanent rejection deletes a key")

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

        override fun deleteKey(alias: String): Unit = error("only a permanent rejection deletes a key")

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

        override fun deleteKey(alias: String): Unit = error("only a permanent rejection deletes a key")

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

    // -- A-4: a permanently invalidated key must not brick the store (test A1) ----------------------------------

    /**
     * What the platform hands out after a permanent invalidation: a handle that every `Cipher.init` refuses with
     * `KeyPermanentlyInvalidatedException`. The refusal comes from the handle itself, so it travels the real
     * `Cipher.init` path and arrives wrapped, as it does on a device.
     */
    private class PermanentlyDeadKey : SecretKey {
        override fun getAlgorithm(): String = "AES"
        override fun getFormat(): String = "RAW"
        override fun getEncoded(): ByteArray = throw KeyPermanentlyInvalidatedException()
    }

    /**
     * The platform behaviour that makes the defect permanent: the key lookup keeps answering the same dead entry for the
     * alias until the alias is deleted or replaced.
     */
    private class PermanentlyDeadKeyStore : AliasedKeyStore {
        var entry: SecretKey? = PermanentlyDeadKey()
        var generated: Int = 0

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? = entry

        override fun deleteKey(alias: String) {
            entry = null
        }

        override fun generateKey(alias: String): SecretKey {
            generated++
            return TestKeys.generate().also { fresh: SecretKey -> entry = fresh }
        }
    }

    @Test
    fun `A1 a write after a permanent key rejection succeeds and later writes keep succeeding`() = runTest {
        val store = PermanentlyDeadKeyStore()
        val cipher = KeystoreValueCipher(AndroidKeystoreKeySource("a1.alias", store))

        repeat(5) { round: Int ->
            val encoded: String = cipher.encryptFor("k", "value-$round")
            assertEquals("value-$round", cipher.decryptFor("k", encoded), "round $round")
        }

        assertEquals(1, store.generated, "the dead key was replaced once, not on every write")
    }

    @Test
    fun `A1 through the store a put after permanent invalidation succeeds and the old entry reads as Undecryptable for its key`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val preferences: SharedPreferences = context.getSharedPreferences("a1.store", Context.MODE_PRIVATE)
        val oldKey: SecretKey = TestKeys.generate()
        val old = AndroidSecureKeyValueStorage(preferences, KeystoreValueCipher(TestKeys.fixedSource(oldKey)), Dispatchers.Unconfined)
        assertEquals(StorageResult.Success(Unit), old.put("old", "written-under-the-key-that-died"))
        val storage = AndroidSecureKeyValueStorage(
            preferences,
            KeystoreValueCipher(AndroidKeystoreKeySource("a1.store.alias", PermanentlyDeadKeyStore())),
            Dispatchers.Unconfined,
        )

        assertEquals(StorageResult.Success(Unit), storage.put("new", "written-after-the-rejection"))

        assertEquals(StorageResult.Success<String?>("written-after-the-rejection"), storage.get("new"))
        val lost: StorageError? = storage.get("old").errorOrNull()
        assertEquals(StorageError.Undecryptable("old"), (lost as? StorageError.Undecryptable)?.copy(cause = null))
    }

    @Test
    fun `a read of an entry after permanent invalidation repairs the key and reads as Undecryptable for its key`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val preferences: SharedPreferences = context.getSharedPreferences("a1.read", Context.MODE_PRIVATE)
        val old = AndroidSecureKeyValueStorage(preferences, KeystoreValueCipher(TestKeys.fixedSource(TestKeys.generate())), Dispatchers.Unconfined)
        assertEquals(StorageResult.Success(Unit), old.put("old", "x"))
        val store = PermanentlyDeadKeyStore()
        val storage = AndroidSecureKeyValueStorage(
            preferences,
            KeystoreValueCipher(AndroidKeystoreKeySource("a1.read.alias", store)),
            Dispatchers.Unconfined,
        )

        val lost: StorageError? = storage.get("old").errorOrNull()

        assertEquals("old", (lost as? StorageError.Undecryptable)?.key, "was $lost")
        assertEquals(1, store.generated, "the read replaced the dead key")
        assertEquals(StorageResult.Success(Unit), storage.put("new", "y"))
        assertEquals(1, store.generated, "and the write after it reuses the new key")
    }

    @Test
    fun `the permanent-invalidation classifier accepts the exception and a chain holding it and nothing else`() {
        val dead = KeyPermanentlyInvalidatedException()

        assertTrue(isPermanentlyInvalidatedKey(dead))
        assertTrue(isPermanentlyInvalidatedKey(java.security.InvalidKeyException("No installed provider", dead)))
        assertFalse(isPermanentlyInvalidatedKey(java.security.InvalidKeyException("Invalid AES key length: 5 bytes")))
        assertFalse(isPermanentlyInvalidatedKey(java.security.UnrecoverableKeyException("keystore fault")))
        val loop = RuntimeException("loop")
        loop.initCause(RuntimeException("inner", loop))
        assertFalse(isPermanentlyInvalidatedKey(loop), "a cyclic cause chain terminates")
    }

    /** A dead entry that counts deletions, answering the same dead handle until the alias is deleted. */
    private class CountingDeadKeyStore : AliasedKeyStore {
        val deletions: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()
        val generated: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()

        @Volatile
        var entry: SecretKey? = PermanentlyDeadKey()

        @Volatile
        var failDeletion: Boolean = false

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? = entry

        override fun deleteKey(alias: String) {
            if (failDeletion) throw java.security.KeyStoreException("keystore daemon is restarting")
            deletions.incrementAndGet()
            entry = null
        }

        override fun generateKey(alias: String): SecretKey {
            generated.incrementAndGet()
            return TestKeys.generate().also { fresh: SecretKey -> entry = fresh }
        }
    }

    @Test
    fun `callers that all hold the same dead handle delete the alias once and a stale report never deletes the new key`() = runTest {
        val store = CountingDeadKeyStore()
        val source = AndroidKeystoreKeySource("a1.storm.alias", store)
        val dead: SecretKey = source.key()

        withContext(Dispatchers.Default) {
            (1..CALLERS).map { async { source.discard(dead) } }.awaitAll()
        }
        val fresh: SecretKey = source.key()

        assertEquals(1, store.deletions.get(), "callers: $CALLERS")
        assertEquals(1, store.generated.get())
        source.discard(dead) // a late report of the dead handle: the cache holds the new key now
        assertSame(fresh, source.key())
        assertEquals(1, store.deletions.get(), "the stale report deleted nothing")
    }

    @Test
    fun `a deletion the key store refuses leaves the key cached, reports unavailable, and the next report retries`() = runTest {
        val store = CountingDeadKeyStore()
        store.failDeletion = true
        val cipher = KeystoreValueCipher(AndroidKeystoreKeySource("a1.refused.alias", store))

        assertFailsWith<StorageKeyUnavailableException> { cipher.encrypt("v") }
        assertEquals(0, store.generated.get(), "no key was generated over an entry that could not be deleted")

        store.failDeletion = false
        assertEquals("v", cipher.decrypt(cipher.encrypt("v")))
        assertEquals(1, store.deletions.get())
        assertEquals(1, store.generated.get())
    }

    @Test
    fun `a cancellation thrown by the key source while the caller is not cancelled propagates out of every operation`() = runTest {
        val preferences: SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("a1.cancel", Context.MODE_PRIVATE)
        val cancelling = object : SecretKeySource {
            override suspend fun key(): SecretKey = throw kotlinx.coroutines.CancellationException("source cancelled")
        }
        val storage = AndroidSecureKeyValueStorage(preferences, KeystoreValueCipher(cancelling), Dispatchers.Unconfined)
        preferences.edit().putString("k", "AAAAAAAAAAAAAAAAAAAAAAAA").commit()

        assertFailsWith<kotlinx.coroutines.CancellationException> { storage.put("k2", "v") }
        assertFailsWith<kotlinx.coroutines.CancellationException> { storage.get("k") }
        assertFailsWith<kotlinx.coroutines.CancellationException> { storage.ensureKey() }
        assertNull(preferences.getString("k2", null), "a cancelled put stores nothing")
    }

    @Test
    fun `a cancellation thrown while a dead key is being discarded propagates and stores nothing`() = runTest {
        val preferences: SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("a1.cancel.discard", Context.MODE_PRIVATE)
        val source = object : SecretKeySource {
            override suspend fun key(): SecretKey = PermanentlyDeadKey()
            override suspend fun discard(dead: SecretKey) = throw kotlinx.coroutines.CancellationException("cancelled while deleting")
        }
        val storage = AndroidSecureKeyValueStorage(preferences, KeystoreValueCipher(source), Dispatchers.Unconfined)

        assertFailsWith<kotlinx.coroutines.CancellationException> { storage.put("k", "v") }
        assertNull(preferences.getString("k", null))
    }

    private companion object {
        const val CALLERS = 16
    }
}
