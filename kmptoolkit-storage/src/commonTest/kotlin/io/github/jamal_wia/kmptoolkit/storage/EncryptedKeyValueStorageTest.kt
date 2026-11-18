package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

/**
 * The type hierarchy and the deprecated helpers, from the plan's R2 and R5: [SecureKeyValueStorage] is
 * an [EncryptedKeyValueStorage] and so a [KeyValueStorage]; Kotlin `by` delegation provides the two new
 * members (breaking change 1 of the changelog); the `...Or` helpers on an encrypted receiver still fall
 * back to the default, which is exactly why they are deprecated there.
 */
class EncryptedKeyValueStorageTest {

    /** A secure store that records the calls the two new members receive and holds nothing. */
    private class RecordingSecure(
        override val protection: SecureStorageProtection,
        private val keyAnswer: StorageResult<Unit> = StorageResult.Success(Unit),
        private val readAnswer: StorageResult<String?> = StorageResult.Success(null),
    ) : SecureKeyValueStorage {
        var ensureKeyCalls: Int = 0
            private set

        override suspend fun ensureKey(): StorageResult<Unit> {
            ensureKeyCalls++
            return keyAnswer
        }

        override suspend fun get(key: String): StorageResult<String?> = readAnswer

        override suspend fun put(key: String, value: String): StorageResult<Unit> = StorageResult.Success(Unit)

        override suspend fun remove(key: String): StorageResult<Unit> = StorageResult.Success(Unit)

        override suspend fun clear(): StorageResult<Unit> = StorageResult.Success(Unit)
    }

    /** A decorator the way the migration guide writes it: delegation provides everything. */
    private class Decorator(delegate: SecureKeyValueStorage) : SecureKeyValueStorage by delegate

    /** Shared code that needs "encrypted, either kind": it takes the parent. */
    private fun describe(storage: EncryptedKeyValueStorage): SecureStorageProtection = storage.protection

    @Test
    fun `a secure store is an encrypted store and a plain store`() {
        val secure: SecureKeyValueStorage = RecordingSecure(SecureStorageProtection.OS_KEY_STORE_USER_SCOPED)

        val encrypted: EncryptedKeyValueStorage = secure
        val plain: KeyValueStorage = secure

        assertSame(secure, encrypted)
        assertSame(secure, plain)
        assertEquals(SecureStorageProtection.OS_KEY_STORE_USER_SCOPED, describe(secure))
    }

    @Test
    fun `delegation provides protection and forwards ensureKey`() = runTest {
        val inner = RecordingSecure(
            SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED,
            keyAnswer = StorageResult.Failure(StorageError.AccessDenied()),
        )

        val decorated: SecureKeyValueStorage = Decorator(inner)

        assertEquals(SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED, decorated.protection)
        assertEquals(StorageResult.Failure(StorageError.AccessDenied()), decorated.ensureKey())
        assertEquals(1, inner.ensureKeyCalls)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the deprecated helpers on an encrypted store still turn an unreadable store into the default`() = runTest {
        val unreadable: EncryptedKeyValueStorage = RecordingSecure(
            SecureStorageProtection.NONE,
            readAnswer = StorageResult.Failure(StorageError.Undecryptable("k")),
        )

        assertEquals(null, unreadable.getStringOrNull("k"))
        assertEquals("fallback", unreadable.getStringOr("k", "fallback"))
        assertEquals(7, unreadable.getIntOr("k", 7))
        assertEquals(7L, unreadable.getLongOr("k", 7L))
        assertEquals(true, unreadable.getBooleanOr("k", true))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the deprecated helpers on an encrypted store return a stored value`() = runTest {
        val stored: EncryptedKeyValueStorage = RecordingSecure(
            SecureStorageProtection.NONE,
            readAnswer = StorageResult.Success("12"),
        )

        assertEquals("12", stored.getStringOrNull("k"))
        assertEquals("12", stored.getStringOr("k", "fallback"))
        assertEquals(12, stored.getIntOr("k", 7))
        assertEquals(12L, stored.getLongOr("k", 7L))
    }
}
