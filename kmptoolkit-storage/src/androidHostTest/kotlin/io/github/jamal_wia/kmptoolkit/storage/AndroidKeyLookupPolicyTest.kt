package io.github.jamal_wia.kmptoolkit.storage

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Group sto-android, breaker finding m1 (review round 5): the lookup of the Keystore key at its FIRST resolution deletes
 * the alias only on proof that the key is dead for good (plan 4.9, A-4: `KeyPermanentlyInvalidatedException`). A locked
 * device, a transient or unknown fault, `ProviderException`, `IOException`, a plain `UnrecoverableKeyException` and a
 * later resolution (`mayDelete = false`) delete nothing and surface as [StorageError.Unavailable].
 *
 * Driven through [resolveExistingKey], the policy the real `AndroidKeyStoreAliases.existingKey` runs over
 * `KeyStore.getEntry` / `deleteEntry`, with a fake key store that throws each exception type at the first lookup.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeyLookupPolicyTest {

    private class Deletions {
        var count: Int = 0
    }

    private fun kpie(): KeyPermanentlyInvalidatedException = KeyPermanentlyInvalidatedException("dead")

    private fun lookupFailing(error: Exception, mayDelete: Boolean = true): Pair<Result<SecretKey?>, Deletions> {
        val deletions = Deletions()
        val result: Result<SecretKey?> = runCatching {
            resolveExistingKey(mayDelete = mayDelete, lookup = { throw error }, delete = { deletions.count++ })
        }
        return result to deletions
    }

    @Test
    fun `an alias proven permanently invalidated is deleted and reported as absent`() {
        val (result: Result<SecretKey?>, deletions: Deletions) =
            lookupFailing(UnrecoverableKeyException("Failed to obtain information about key").initCause(kpie()) as Exception)

        assertNull(result.getOrThrow(), "the caller generates a new key")
        assertEquals(1, deletions.count)
    }

    @Test
    fun `a proof nested deeper in the cause chain counts`() {
        val error = UnrecoverableKeyException("outer")
        error.initCause(ProviderException("middle", kpie()))

        val (result: Result<SecretKey?>, deletions: Deletions) = lookupFailing(error)

        assertNull(result.getOrThrow())
        assertEquals(1, deletions.count)
    }

    @Test
    fun `every failure that is not a proof deletes nothing and is rethrown`() {
        val unproven: Map<String, Exception> = mapOf(
            "plain UnrecoverableKeyException" to UnrecoverableKeyException("no proof"),
            "locked device" to UnrecoverableKeyException("locked").also { it.initCause(UserNotAuthenticatedException()) },
            "ProviderException" to ProviderException("daemon down"),
            "IOException" to IOException("keystore file"),
            "KeyStoreException" to KeyStoreException("broken"),
            "unknown runtime failure" to IllegalStateException("anything"),
            "UnrecoverableKeyException with an unrelated cause" to
                UnrecoverableKeyException("x").also { it.initCause(IllegalArgumentException("y")) },
        )
        for ((label: String, error: Exception) in unproven) {
            val (result: Result<SecretKey?>, deletions: Deletions) = lookupFailing(error)
            assertSame(error, result.exceptionOrNull(), "$label is rethrown unchanged")
            assertEquals(0, deletions.count, "$label must not delete the key")
        }
    }

    @Test
    fun `a locked device that also carries the proof is still not deleted`() {
        val error = UnrecoverableKeyException("both")
        error.initCause(UserNotAuthenticatedException("locked", kpie()))

        val (result: Result<SecretKey?>, deletions: Deletions) = lookupFailing(error)

        assertSame(error, result.exceptionOrNull())
        assertEquals(0, deletions.count, "a device that passes into unlocked is never a reason to lose the values")
    }

    @Test
    fun `a later resolution never deletes even on proof`() {
        val error = UnrecoverableKeyException("dead").also { it.initCause(kpie()) }

        val (result: Result<SecretKey?>, deletions: Deletions) = lookupFailing(error, mayDelete = false)

        assertSame(error, result.exceptionOrNull())
        assertEquals(0, deletions.count)
    }

    @Test
    fun `a failing delete does not stop the replacement`() {
        val error = UnrecoverableKeyException("dead").also { it.initCause(kpie()) }

        val key: SecretKey? = resolveExistingKey(mayDelete = true, lookup = { throw error }, delete = { throw IOException("busy") })

        assertNull(key, "generateKey replaces the entry anyway")
    }

    @Test
    fun `an existing key and an absent alias are answered without any deletion`() {
        val live: SecretKey = TestKeys.generate()
        val deletions = Deletions()

        assertSame(live, resolveExistingKey(true, { live }, { deletions.count++ }))
        assertNull(resolveExistingKey(true, { null }, { deletions.count++ }))
        assertEquals(0, deletions.count)
    }

    @Test
    fun `an unproven lookup failure reaches the caller as Unavailable and keeps the key store untouched`() = runTest {
        val deletions = Deletions()
        val keyStore = object : AliasedKeyStore {
            override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? =
                resolveExistingKey(mayDelete, { throw UnrecoverableKeyException("no proof") }, { deletions.count++ })

            override fun generateKey(alias: String): SecretKey = error("nothing may be generated over an unproven failure")

            override fun deleteKey(alias: String) {
                deletions.count++
            }
        }
        val storage = AndroidKeystoreKeySource("lookup.policy.alias", keyStore)

        val failure: StorageKeyUnavailableException = assertFailsWith { storage.key() }

        assertEquals(0, deletions.count)
        assertTrue(failure.cause is UnrecoverableKeyException, "the cause is kept: ${failure.cause}")
    }
}
