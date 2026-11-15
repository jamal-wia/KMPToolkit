package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess

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
 * Every dictionary handed to the Security framework is built by [KeychainQueries] on [KeychainQuery] rather
 * than bridged from a Kotlin `Map`; the reason is worth reading before changing anything here. Every call into
 * the framework goes through [keychain], and every user-presence sheet through [authentication], so the logic
 * of this class runs in a test binary that has no Keychain.
 *
 * Holds no lock and no state beyond its constructor arguments: each operation is independent, and the one that
 * can wait for the user (a `get` or an overwriting `put` of a `userPresence` store) waits without holding
 * anything, and is cancellable (see [onNative]).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosSecureKeyValueStorage(
    service: String,
    private val options: IosSecureStorageOptions,
    private val dispatcher: CoroutineDispatcher,
    private val keychain: KeychainPort = SecItemKeychainPort,
    private val authentication: AuthenticationSessions = LocalAuthenticationSessions,
) : SecureKeyValueStorage {

    private val queries: KeychainQueries = KeychainQueries(
        service = service,
        accessibility = options.accessibility,
        accessGroup = options.accessGroup,
        synchronizable = options.synchronizable,
        userPresence = options.userPresence,
    )

    /** The Keychain holds every value; other apps are kept out (except members of the access group). */
    override val protection: SecureStorageProtection = SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED

    /**
     * Non-mutating and never prompting: an attribute-only `SecItemCopyMatching` for the service, with UI
     * forbidden, matching local and synchronizable items alike. There is no key to create on iOS, so this only
     * tells whether the Keychain can be reached (no items is success, as is an item that needs authentication:
     * no data is read). Failures report [StorageOperation.ENSURE_KEY] with no key; a store-wide `errSecDecode`
     * is [StorageError.Undecryptable] with a `null` key.
     */
    override suspend fun ensureKey(): StorageResult<Unit> = withContext(dispatcher) {
        val status: Int = queries.ensureKey().use { keychain.matches(it) }
        if (status == errSecSuccess || status == errSecItemNotFound) {
            StorageResult.Success(Unit)
        } else {
            StorageResult.Failure(status.toStorageError(StorageOperation.ENSURE_KEY, key = null))
        }
    }

    override suspend fun get(key: String): StorageResult<String?> = onNative { session ->
        val read: KeychainData = queries.read(key, session).use { keychain.readData(it) }
        when (read.status) {
            errSecSuccess -> {
                // The item exists and was returned; if its bytes are missing or are not UTF-8,
                // something other than this store wrote under the same service, or the item was
                // corrupted. That is exactly "present but not readable as a value".
                val decoded: String? = read.data?.let {
                    NSString.create(it, NSUTF8StringEncoding)?.toString()
                }
                if (decoded == null) {
                    StorageResult.Failure(StorageError.Undecryptable(key))
                } else {
                    StorageResult.Success(decoded)
                }
            }

            errSecItemNotFound -> StorageResult.Success(null)
            else -> StorageResult.Failure(read.status.toStorageError(StorageOperation.GET, key))
        }
    }

    override suspend fun put(key: String, value: String): StorageResult<Unit> {
        unpairedSurrogateFailure(key, value)?.let { return it }
        return onNative { session -> write(key, value, session) }
    }

    private fun write(key: String, value: String, session: AuthenticationSession?): StorageResult<Unit> {
        val data: NSData = NSString.create(string = value)
            .dataUsingEncoding(NSUTF8StringEncoding)
            ?: return StorageResult.Failure(
                StorageError.OperationFailed(StorageOperation.PUT, key = key),
            )

        try {
            // Update first, add on miss: SecItemAdd on an existing item fails with errSecDuplicateItem
            // rather than replacing it, so an unconditional add would make put() non-overwriting.
            //
            // Another writer or remover (a second coroutine, a second store over the same service, an app
            // extension sharing the access group) can change the item between any two of our calls, and no lock
            // could stop it, since it may not be in this process at all. A miss of the update followed by a
            // duplicate on the add means somebody added the item in between; the update that follows may then
            // miss again because somebody removed it. Each such lost round is another party's progress, so the
            // loop is lock-free, and it is bounded by MAX_WRITE_ATTEMPTS: a store that keeps losing is reported
            // as the duplicate it keeps hitting, never retried forever and never answered with a Success whose
            // value is not there. The contract leaves the winner of racing writes undefined, not one of the
            // writers failing.
            repeat(MAX_WRITE_ATTEMPTS) {
                val updateStatus: Int = update(key, data, session)
                if (updateStatus == errSecSuccess) return StorageResult.Success(Unit)
                if (updateStatus != errSecItemNotFound) {
                    return StorageResult.Failure(updateStatus.toStorageError(StorageOperation.PUT, key))
                }

                val addStatus: Int = queries.add(key, data).use { keychain.add(it) }
                if (addStatus == errSecSuccess) return StorageResult.Success(Unit)
                if (addStatus != errSecDuplicateItem) {
                    return StorageResult.Failure(addStatus.toStorageError(StorageOperation.PUT, key))
                }
            }
            return StorageResult.Failure(errSecDuplicateItem.toStorageError(StorageOperation.PUT, key))
        } catch (unavailable: AccessControlUnavailable) {
            return StorageResult.Failure(
                StorageError.OperationFailed(
                    StorageOperation.PUT,
                    key = key,
                    platformCode = unavailable.code,
                    cause = unavailable,
                ),
            )
        }
    }

    override suspend fun remove(key: String): StorageResult<Unit> = withContext(dispatcher) {
        val status: Int = queries.item(key, authentication = null).use { keychain.delete(it) }
        if (status == errSecSuccess || status == errSecItemNotFound) {
            StorageResult.Success(Unit)
        } else {
            StorageResult.Failure(status.toStorageError(StorageOperation.REMOVE, key))
        }
    }

    /**
     * Deletes every item of this store, local and synchronizable, in exactly one access group: the one named in
     * the options, or — for `accessGroup = null` — the app's default group, resolved by [DefaultAccessGroup]. A
     * delete that names no group would also remove a same-named store's items in a shared group (I-2b, D-18). If
     * the group cannot be resolved the call fails and deletes nothing.
     */
    override suspend fun clear(): StorageResult<Unit> = withContext(dispatcher) {
        val group: String = options.accessGroup ?: when (val resolved: StorageResult<String> = DefaultAccessGroup(keychain, queries).resolve()) {
            is StorageResult.Success -> resolved.value
            is StorageResult.Failure -> return@withContext resolved
        }
        val status: Int = queries.clear(group).use { keychain.delete(it) }
        if (status == errSecSuccess || status == errSecItemNotFound) {
            StorageResult.Success(Unit)
        } else {
            StorageResult.Failure(status.toStorageError(StorageOperation.CLEAR, key = null))
        }
    }

    private fun update(key: String, data: NSData, session: AuthenticationSession?): Int =
        queries.item(key, session).use { query ->
            queries.updateAttributes(data).use { attributes -> keychain.update(query, attributes) }
        }

    /**
     * Runs a Keychain call that can wait for the user. Without `userPresence` it is a plain hop to [dispatcher]. With
     * it, a fresh [AuthenticationSession] is opened for this operation and the blocking call runs in a child
     * coroutine, so the caller stays cancellable: cancelling the caller invalidates the session, which dismisses the
     * system sheet and makes the blocked call return, and the cancellation then completes. The session is invalidated
     * on every exit, which releases its `LAContext`. No lock is held anywhere, so one prompt never blocks another
     * operation of the store.
     */
    private suspend fun <T> onNative(block: (AuthenticationSession?) -> T): T {
        val presence: IosUserPresence = options.userPresence ?: return withContext(dispatcher) { block(null) }
        val session: AuthenticationSession = authentication.open(presence.prompt)
        try {
            return coroutineScope {
                val call = async(dispatcher) { block(session) }
                try {
                    call.await()
                } catch (cancelled: CancellationException) {
                    // The native call cannot be interrupted, but dismissing the sheet makes it return; coroutineScope
                    // waits for that, then rethrows. A late result is discarded.
                    session.invalidate()
                    throw cancelled
                }
            }
        } finally {
            session.invalidate()
        }
    }
}

/**
 * Rounds of "update, then add on a miss" one `put` makes before it gives up on an item that other writers and removers
 * keep changing under it (see `write`). A round is lost only when another party made progress, so the bound is generous
 * and is never reached without a storm; reaching it answers `OperationFailed(PUT, key, errSecDuplicateItem)`.
 */
internal const val MAX_WRITE_ATTEMPTS: Int = 8

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

    // The user dismissed the system sheet of a user-presence item (errSecUserCanceled), or failed the
    // authentication (errSecAuthFailed). Nothing is lost: a retry shows the sheet again, so it needs a user action.
    ERR_SEC_USER_CANCELED, ERR_SEC_AUTH_FAILED -> StorageError.AccessDenied(platformCode = this)

    // The app lacks the Keychain Sharing entitlement or is not a member of the access group it named. Retrying
    // cannot help; the fix is in the app's entitlements.
    ERR_SEC_MISSING_ENTITLEMENT -> StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT)

    // errSecDecode — the item is there and cannot be turned back into its plaintext. A store-wide check
    // (ensureKey, clear) has no entry key: Undecryptable(null).
    ERR_SEC_DECODE -> StorageError.Undecryptable(key)

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

/** `errSecUserCanceled` — the user dismissed the authentication sheet. */
internal const val ERR_SEC_USER_CANCELED = -128

/** `errSecAuthFailed` — the authentication did not succeed. */
internal const val ERR_SEC_AUTH_FAILED = -25293

/** `errSecMissingEntitlement` — no Keychain Sharing entitlement, or an access group the app is not in. */
internal const val ERR_SEC_MISSING_ENTITLEMENT = -34018
