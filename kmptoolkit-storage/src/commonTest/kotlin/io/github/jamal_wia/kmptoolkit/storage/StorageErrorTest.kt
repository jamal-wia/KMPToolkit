package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The shape of the error set, from the contract in the KDoc of `StorageError.kt` and the storage
 * plan (R5): six cases, matched exhaustively with no `else`, each with the fields its reaction needs.
 */
class StorageErrorTest {

    /**
     * The exhaustive `when` the documentation tells every app to write. It has no `else`, so removing
     * or adding a case without a decision here stops this file from compiling.
     */
    private fun reaction(error: StorageError): String = when (error) {
        is StorageError.Unavailable -> "retryLater"
        is StorageError.AccessDenied -> "askAgainOnUserAction"
        is StorageError.Unsupported -> "fallBackExplicitly:${error.reason}"
        is StorageError.Undecryptable -> if (error.key == null) "storeLost" else "removeKey:${error.key}"
        is StorageError.OperationFailed -> "log"
        is StorageError.InvalidValue -> "dropValue:${error.key}"
    }

    @Test
    fun `every case has its own reaction`() {
        assertEquals("retryLater", reaction(StorageError.Unavailable()))
        assertEquals("askAgainOnUserAction", reaction(StorageError.AccessDenied(platformCode = -128)))
        assertEquals(
            "fallBackExplicitly:MISSING_ENTITLEMENT",
            reaction(StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT)),
        )
        assertEquals("removeKey:k", reaction(StorageError.Undecryptable("k")))
        assertEquals("storeLost", reaction(StorageError.Undecryptable(null)))
        assertEquals("log", reaction(StorageError.OperationFailed(StorageOperation.GET)))
        assertEquals("dropValue:k", reaction(StorageError.InvalidValue("k")))
    }

    @Test
    fun `an undecryptable error can name the whole store by a null key`() {
        val wholeStore: StorageError.Undecryptable = StorageError.Undecryptable(key = null)

        assertNull(wholeStore.key)
        assertNotEquals<StorageError>(wholeStore, StorageError.Undecryptable("k"))
    }

    @Test
    fun `access denied carries the platform code and the cause it was given`() {
        val cause = IllegalStateException("dismissed")

        val error = StorageError.AccessDenied(platformCode = -25293, cause = cause)

        assertEquals(-25293, error.platformCode)
        assertEquals(cause, error.cause)
        assertNull(StorageError.AccessDenied().platformCode)
        assertNull(StorageError.AccessDenied().cause)
    }

    @Test
    fun `unsupported carries the reason and the cause it was given`() {
        val cause = UnsupportedOperationException("no libsecret")

        val error = StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE, cause)

        assertEquals(UnsupportedReason.NO_KEY_STORE, error.reason)
        assertEquals(cause, error.cause)
        assertNull(StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE).cause)
    }

    @Test
    fun `an invalid value names its key and may carry a cause`() {
        val cause = IllegalArgumentException("Not a decimal integer: 'x'")

        val error = StorageError.InvalidValue("count", cause)

        assertEquals("count", error.key)
        assertEquals(cause, error.cause)
        assertNull(StorageError.InvalidValue("count").cause)
    }

    @Test
    fun `the unsupported reasons are exactly the documented machine tokens`() {
        assertEquals(
            setOf(
                "NO_USER_SESSION",
                "NO_KEY_STORE",
                "UNSUPPORTED_RUNTIME",
                "NATIVE_ACCESS_UNAVAILABLE",
                "MISSING_ENTITLEMENT",
                "UNSUPPORTED_OS",
                "HARDWARE_UNAVAILABLE",
            ),
            UnsupportedReason.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `the protection values are exactly the five lower bounds of the plan`() {
        assertEquals(
            listOf(
                "OS_KEY_STORE_APP_ISOLATED",
                "OS_KEY_STORE_USER_SCOPED",
                "PASSPHRASE",
                "APP_PROVIDED_KEY",
                "NONE",
            ),
            SecureStorageProtection.entries.map { it.name },
        )
    }

    /** Exhaustive over the operations, with no `else`: a new operation without a decision here does not compile. */
    private fun targetsWholeStore(operation: StorageOperation): Boolean = when (operation) {
        StorageOperation.GET, StorageOperation.PUT, StorageOperation.REMOVE -> false
        StorageOperation.CLEAR, StorageOperation.ENSURE_KEY -> true
    }

    @Test
    fun `the operations are the four store calls and ensureKey`() {
        assertEquals(
            listOf("GET", "PUT", "REMOVE", "CLEAR", "ENSURE_KEY"),
            StorageOperation.entries.map { it.name },
        )
        assertEquals(
            setOf(StorageOperation.CLEAR, StorageOperation.ENSURE_KEY),
            StorageOperation.entries.filter(::targetsWholeStore).toSet(),
        )
    }
}
