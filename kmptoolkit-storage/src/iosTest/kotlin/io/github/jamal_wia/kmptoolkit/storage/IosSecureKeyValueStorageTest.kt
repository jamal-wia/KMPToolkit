package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the Keychain store does when there is no Keychain.
 *
 * A Kotlin/Native test executable is not an app: it has no bundle and no keychain-access-group
 * entitlement, so every `SecItem*` call in this suite comes back `errSecNotAvailable` (`-25291`).
 * A round trip therefore cannot be asserted here at all — see
 * `docs/kmptoolkit-storage/06-testing.md` for where that coverage does live.
 *
 * What *is* worth asserting is precisely what an app on a locked-out or damaged Keychain sees, and
 * it is the same code path: every operation returns a typed failure, none of them throws, and none
 * of them hangs. The status-to-error mapping is exercised directly below, since that function is
 * where a wrong classification would send a caller down the wrong recovery path.
 */
class IosSecureKeyValueStorageTest {

    private val storage: SecureKeyValueStorage =
        createSecureKeyValueStorage(StorageConfig("io.github.jamal_wia.kmptoolkit.storage.test"))

    @Test
    fun `a read without a Keychain is a typed failure rather than a crash`() {
        val result: StorageResult<String?> = storage.get("k")

        assertEquals(StorageError.Unavailable(), result.errorOrNull())
    }

    @Test
    fun `a write without a Keychain is a typed failure rather than a crash`() {
        assertEquals(StorageError.Unavailable(), storage.put("k", "v").errorOrNull())
    }

    @Test
    fun `a remove without a Keychain is a typed failure rather than a crash`() {
        assertEquals(StorageError.Unavailable(), storage.remove("k").errorOrNull())
    }

    @Test
    fun `a clear without a Keychain is a typed failure rather than a crash`() {
        assertEquals(StorageError.Unavailable(), storage.clear().errorOrNull())
    }

    @Test
    fun `an empty value is accepted by the store rather than rejected before the Keychain`() {
        // The NSData conversion runs before any Security call, and an empty string must survive it:
        // a failure here would be an OperationFailed, not the Unavailable the Keychain reports.
        assertEquals(StorageError.Unavailable(), storage.put("k", "").errorOrNull())
    }

    @Test
    fun `creating the store touches nothing`() {
        // A factory called from an app delegate must not be able to fail, whatever state the
        // Keychain is in.
        createSecureKeyValueStorage()
        createSecureKeyValueStorage(StorageConfig("another.store"))
    }

    @Test
    fun `the Keychain service is derived from the store name rather than being it`() {
        assertNotEquals("app.name", secureStoreId("app.name"))
        assertTrue(secureStoreId("app.name").startsWith("app.name"))
    }

    @Test
    fun `errSecNotAvailable is reported as unavailable`() {
        // Retryable: the Keychain may come back. Not a lost value.
        assertEquals(
            StorageError.Unavailable(),
            ERR_SEC_NOT_AVAILABLE.toStorageError(StorageOperation.GET, "k"),
        )
    }

    @Test
    fun `errSecInteractionNotAllowed is reported as unavailable`() {
        // The device has not been unlocked since boot. Also retryable.
        assertEquals(
            StorageError.Unavailable(),
            ERR_SEC_INTERACTION_NOT_ALLOWED.toStorageError(StorageOperation.GET, "k"),
        )
    }

    @Test
    fun `errSecDecode on a keyed operation is reported as undecryptable`() {
        assertEquals(
            StorageError.Undecryptable("k"),
            ERR_SEC_DECODE.toStorageError(StorageOperation.GET, "k"),
        )
    }

    @Test
    fun `errSecDecode without a key keeps its raw status instead of naming a key it does not have`() {
        assertEquals(
            StorageError.OperationFailed(
                StorageOperation.CLEAR,
                key = null,
                platformCode = ERR_SEC_DECODE,
            ),
            ERR_SEC_DECODE.toStorageError(StorageOperation.CLEAR, key = null),
        )
    }

    @Test
    fun `an unrecognized status keeps its raw code for the caller to report`() {
        val unknown = -34018 // errSecMissingEntitlement

        assertEquals(
            StorageError.OperationFailed(
                StorageOperation.PUT,
                key = "k",
                platformCode = unknown,
            ),
            unknown.toStorageError(StorageOperation.PUT, "k"),
        )
    }

    @Test
    fun `errSecParam is not swallowed into a friendlier category`() {
        // The iOS 26 symptom. If the query construction ever regresses, this must reach the caller
        // as a raw platform code rather than as "unavailable, try later" — which would turn a
        // permanent bug into an invisible retry loop.
        val error: StorageError = ERR_SEC_PARAM.toStorageError(StorageOperation.GET, "k")

        assertEquals(ERR_SEC_PARAM, (error as StorageError.OperationFailed).platformCode)
    }
}
