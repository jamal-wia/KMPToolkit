package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * [SecureKeyValueStorage] over the iOS Keychain, one `kSecClassGenericPassword` item per key.
 *
 * The Keychain is the encryption: values are stored by `securityd` outside the app's container,
 * encrypted with a key derived from the device's own hardware, and are not present in the app's
 * sandbox at all. There is no cipher in this file for that reason — writing one would only add a
 * second, weaker layer over data the app itself cannot read anyway.
 *
 * Items are scoped by [service], derived from the store's [StorageConfig], so two stores in one app
 * — and two apps — never see each other's items. Key names are the item's `kSecAttrAccount`, in the
 * clear, exactly as on Android and for the same reason.
 *
 * Every dictionary handed to the Security framework is built with [KeychainQuery] rather than
 * bridged from a Kotlin `Map`; the reason is worth reading before changing anything here.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosSecureKeyValueStorage(
    private val service: String,
    private val accessibility: KeychainAccessibility,
    private val accessGroup: String?,
) : SecureKeyValueStorage {

    override fun get(key: String): StorageResult<String?> = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status: Int = query(key)
            .apply {
                putConstant(kSecReturnData, kCFBooleanTrue)
                putConstant(kSecMatchLimit, kSecMatchLimitOne)
            }
            .use { SecItemCopyMatching(it, result.ptr) }

        when (status) {
            errSecSuccess -> {
                // The item exists and was returned; if its bytes are missing or are not UTF-8,
                // something other than this store wrote under the same service, or the item was
                // corrupted. That is exactly "present but not readable as a value".
                val data: NSData? = result.value?.let { CFBridgingRelease(it) } as? NSData
                val decoded: String? = data?.let {
                    NSString.create(it, NSUTF8StringEncoding)?.toString()
                }
                if (decoded == null) {
                    StorageResult.Failure(StorageError.Undecryptable(key))
                } else {
                    StorageResult.Success(decoded)
                }
            }

            errSecItemNotFound -> StorageResult.Success(null)
            else -> StorageResult.Failure(status.toStorageError(StorageOperation.GET, key))
        }
    }

    override fun put(key: String, value: String): StorageResult<Unit> {
        val data: NSData = NSString.create(string = value)
            .dataUsingEncoding(NSUTF8StringEncoding)
            ?: return StorageResult.Failure(
                StorageError.OperationFailed(StorageOperation.PUT, key = key),
            )

        // Update first, add on miss: SecItemAdd on an existing item fails with errSecDuplicateItem
        // rather than replacing it, so an unconditional add would make put() non-overwriting.
        val updateStatus: Int = query(key).use { query ->
            KeychainQuery()
                .apply { putData(kSecValueData, data) }
                .use { attributes -> SecItemUpdate(query, attributes) }
        }
        if (updateStatus == errSecSuccess) return StorageResult.Success(Unit)
        if (updateStatus != errSecItemNotFound) {
            return StorageResult.Failure(updateStatus.toStorageError(StorageOperation.PUT, key))
        }

        val addStatus: Int = query(key)
            .apply {
                putData(kSecValueData, data)
                putConstant(kSecAttrAccessible, accessibility.constant)
            }
            .use { SecItemAdd(it, null) }
        return if (addStatus == errSecSuccess) {
            StorageResult.Success(Unit)
        } else {
            StorageResult.Failure(addStatus.toStorageError(StorageOperation.PUT, key))
        }
    }

    override fun remove(key: String): StorageResult<Unit> {
        val status: Int = query(key).use { SecItemDelete(it) }
        return if (status == errSecSuccess || status == errSecItemNotFound) {
            StorageResult.Success(Unit)
        } else {
            StorageResult.Failure(status.toStorageError(StorageOperation.REMOVE, key))
        }
    }

    /**
     * Deletes every item under this store's [service] in one call — and nothing else. Items written
     * by another store, by another framework, or by the same app under a different service are not
     * matched, because the query names the service and no key.
     */
    override fun clear(): StorageResult<Unit> {
        val status: Int = KeychainQuery()
            .apply {
                putConstant(kSecClass, kSecClassGenericPassword)
                putString(kSecAttrService, service)
                accessGroup?.let { putString(kSecAttrAccessGroup, it) }
            }
            .use { SecItemDelete(it) }
        return if (status == errSecSuccess || status == errSecItemNotFound) {
            StorageResult.Success(Unit)
        } else {
            StorageResult.Failure(status.toStorageError(StorageOperation.CLEAR, key = null))
        }
    }

    private fun query(key: String): KeychainQuery = KeychainQuery().apply {
        putConstant(kSecClass, kSecClassGenericPassword)
        putString(kSecAttrService, service)
        putString(kSecAttrAccount, key)
        accessGroup?.let { putString(kSecAttrAccessGroup, it) }
    }
}

/**
 * Maps an `OSStatus` onto this module's error taxonomy.
 *
 * Only the statuses that mean something specific to a caller are translated; everything else keeps
 * its raw code in [StorageError.OperationFailed.platformCode] rather than being flattened into a
 * category it does not belong to.
 *
 * Internal rather than private so that the mapping — the part of this file that can be exercised
 * without a Keychain — is testable. See `docs/kmptoolkit-storage/06-testing.md`.
 */
internal fun Int.toStorageError(operation: StorageOperation, key: String?): StorageError = when (this) {
    // The item exists but the device has not been unlocked since boot, so its class key is not
    // available; and, respectively, no Keychain is reachable by this process at all. Both are
    // conditions of the store rather than of the value, and both can stop being true later.
    ERR_SEC_INTERACTION_NOT_ALLOWED, ERR_SEC_NOT_AVAILABLE -> StorageError.Unavailable()

    // errSecDecode — the item is there and cannot be turned back into its plaintext.
    ERR_SEC_DECODE -> key
        ?.let { StorageError.Undecryptable(it) }
        ?: StorageError.OperationFailed(operation, key = null, platformCode = this)

    else -> StorageError.OperationFailed(operation, key = key, platformCode = this)
}

/** `errSecInteractionNotAllowed`, which the Kotlin/Native Security bindings do not expose. */
internal const val ERR_SEC_INTERACTION_NOT_ALLOWED = -25308

/** `errSecNotAvailable` — no Keychain is reachable from this process. */
internal const val ERR_SEC_NOT_AVAILABLE = -25291

/** `errSecDecode`, likewise not exposed. */
internal const val ERR_SEC_DECODE = -26275

/** `errSecParam` — the Security framework rejected the query itself. */
internal const val ERR_SEC_PARAM = -50
