package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Security.errSecDuplicateItem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Breaker round 1 of group sto-ios (ticket 5.1.0, STO-5): the error table of plan 4.10 through EVERY operation of the
 * store, not only `get`, plus statuses the table does not know.
 *
 * Contract (plan 4.10; D-15/D-19: `ensureKey` failures are `ENSURE_KEY` with no key; `clear` failures `CLEAR` with no
 * key): -25308 and -25291 are `Unavailable`, -128 and -25293 `AccessDenied(code)`, -34018
 * `Unsupported(MISSING_ENTITLEMENT)`, -26275 `Undecryptable(key)` (`null` key for the store-wide calls), anything else
 * `OperationFailed(operation, key, code)`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSecureStoreStatusBreakerR1Test {

    private fun expected(status: Int, operation: StorageOperation, key: String?): StorageError = when (status) {
        ERR_SEC_INTERACTION_NOT_ALLOWED, ERR_SEC_NOT_AVAILABLE -> StorageError.Unavailable()
        ERR_SEC_USER_CANCELED, ERR_SEC_AUTH_FAILED -> StorageError.AccessDenied(platformCode = status)
        ERR_SEC_MISSING_ENTITLEMENT -> StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT)
        ERR_SEC_DECODE -> StorageError.Undecryptable(key)
        else -> StorageError.OperationFailed(operation, key = key, platformCode = status)
    }

    private val statuses: List<Int> = listOf(
        ERR_SEC_INTERACTION_NOT_ALLOWED,
        ERR_SEC_NOT_AVAILABLE,
        ERR_SEC_USER_CANCELED,
        ERR_SEC_AUTH_FAILED,
        ERR_SEC_MISSING_ENTITLEMENT,
        ERR_SEC_DECODE,
        ERR_SEC_PARAM,
        errSecDuplicateItem,
        -25244,
        -4,
        1,
        Int.MIN_VALUE,
        Int.MAX_VALUE,
    )

    @Test
    fun `every status maps as the table says through every operation with the right operation and key`() = runTest {
        val problems: MutableList<String> = mutableListOf()
        for (status: Int in statuses) {
            // operation label, the port call to force, the store call, the expected operation and key
            val cases: List<Triple<String, StorageOperation, String?>> = listOf(
                Triple("readData", StorageOperation.GET, "k"),
                Triple("update", StorageOperation.PUT, "k"),
                Triple("delete-item", StorageOperation.REMOVE, "k"),
                Triple("delete-store", StorageOperation.CLEAR, null),
                Triple("matches", StorageOperation.ENSURE_KEY, null),
            )
            for ((call: String, operation: StorageOperation, key: String?) in cases) {
                val keychain = InMemoryKeychainPort()
                keychain.seed(keychain.item("k", "v"))
                keychain.script = { op: String, q: Map<String, String> ->
                    val hit: Boolean = when (call) {
                        "delete-item" -> op == "delete" && q["account"] == "k" && q["service"] == STORE_SERVICE
                        "delete-store" -> op == "delete" && "account" !in q && q["service"] == STORE_SERVICE
                        else -> op == call
                    }
                    if (hit) status else null
                }
                val store: IosSecureKeyValueStorage = storeOver(keychain)
                val result: StorageResult<*> = when (operation) {
                    StorageOperation.GET -> store.get("k")
                    StorageOperation.PUT -> store.put("k", "w")
                    StorageOperation.REMOVE -> store.remove("k")
                    StorageOperation.CLEAR -> store.clear()
                    else -> store.ensureKey()
                }
                val want: StorageError = expected(status, operation, key)
                val got: StorageError? = (result as? StorageResult.Failure)?.error
                if (got != want) problems += "status=$status $operation: expected $want but was $result"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
