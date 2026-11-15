package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.NSData
import platform.Foundation.NSUUID
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.kSecAccessControlBiometryAny
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAccessControlUserPresence
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecAttrSynchronizableAny
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecUseAuthenticationUI
import platform.Security.kSecUseAuthenticationUIFail
import platform.Security.kSecValueData

/**
 * Builds, for one store, every dictionary [IosSecureKeyValueStorage] sends to the Keychain, and nothing else.
 *
 * Pure: building a dictionary calls no Keychain function, so every rule below is asserted in
 * `KeychainQueryTest` on a machine with no Keychain. Each function returns an unreleased [KeychainQuery]; the
 * caller passes it to exactly one [KeychainPort] call inside [KeychainQuery.use].
 *
 * The rules, each one a defect of the iOS review (`PLAN` I-1, I-2) or a plan decision:
 *  - every item query carries the **exact** `kSecAttrSynchronizable` of the store, so a store never meets the other
 *    kind of item;
 *  - every write carries the configured accessibility, an update of an existing item included: a plain
 *    `SecItemUpdate` with only the new data keeps the class the item was created with (I-1);
 *  - a store with `userPresence` stores `kSecAttrAccessControl` instead of `kSecAttrAccessible` (the two cannot be
 *    combined: the framework rejects the pair with `errSecParam`);
 *  - `clear()` and `ensureKey()` match synchronizable and local items alike (`kSecAttrSynchronizableAny`), and
 *    `clear()` always names an access group (I-2).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class KeychainQueries(
    private val service: String,
    private val accessibility: KeychainAccessibility,
    private val accessGroup: String?,
    private val synchronizable: Boolean,
    private val userPresence: IosUserPresence?,
) {

    /** The item of [key] in this store, exactly as it was stored: the base of read, update-target and remove. */
    fun item(key: String, authentication: AuthenticationSession?): KeychainQuery = KeychainQuery().apply {
        putConstant(kSecClass, kSecClassGenericPassword)
        putString(kSecAttrService, service)
        putString(kSecAttrAccount, key)
        accessGroup?.let { putString(kSecAttrAccessGroup, it) }
        putConstant(kSecAttrSynchronizable, exactSynchronizable())
        authentication?.let { putObject(kSecUseAuthenticationContext, it.handle) }
    }

    /** [item] with its data returned. */
    fun read(key: String, authentication: AuthenticationSession?): KeychainQuery = item(key, authentication).apply {
        putConstant(kSecReturnData, kCFBooleanTrue)
        putConstant(kSecMatchLimit, kSecMatchLimitOne)
    }

    /**
     * [item] with the data and the protection of a new item: the argument of `SecItemAdd`.
     *
     * @throws AccessControlUnavailable when the store has `userPresence` and the `SecAccessControl` cannot be built;
     *   thrown before anything is allocated.
     */
    fun add(key: String, data: NSData): KeychainQuery {
        val protection: Protection = protection()
        return item(key, authentication = null).apply {
            putData(kSecValueData, data)
            putProtection(protection)
        }
    }

    /**
     * The attributes of `SecItemUpdate`: the new data and, since I-1, the configured protection, so an existing
     * item takes a changed accessibility on its next write.
     *
     * @throws AccessControlUnavailable as [add].
     */
    fun updateAttributes(data: NSData): KeychainQuery {
        val protection: Protection = protection()
        return KeychainQuery().apply {
            putData(kSecValueData, data)
            putProtection(protection)
        }
    }

    /**
     * Every item of the store in [group], local and synchronizable alike. [group] is never absent: a delete that
     * names no group matches every group the app belongs to (I-2b), see [DefaultAccessGroup].
     */
    fun clear(group: String): KeychainQuery = KeychainQuery().apply {
        putConstant(kSecClass, kSecClassGenericPassword)
        putString(kSecAttrService, service)
        putString(kSecAttrAccessGroup, group)
        putConstant(kSecAttrSynchronizable, kSecAttrSynchronizableAny)
    }

    /**
     * The attribute-only existence check of `ensureKey()`: it must never show UI (`kSecUseAuthenticationUI` set to
     * fail) and must see synchronizable and local items alike. No data is returned, so it asks for no
     * authentication even for a user-presence item.
     */
    fun ensureKey(): KeychainQuery = KeychainQuery().apply {
        putConstant(kSecClass, kSecClassGenericPassword)
        putString(kSecAttrService, service)
        accessGroup?.let { putString(kSecAttrAccessGroup, it) }
        putConstant(kSecAttrSynchronizable, kSecAttrSynchronizableAny)
        putConstant(kSecUseAuthenticationUI, kSecUseAuthenticationUIFail)
        putConstant(kSecMatchLimit, kSecMatchLimitOne)
    }

    /** The protection every write of this store carries; a `SecAccessControl` is created here, +1, for one query. */
    private fun protection(): Protection {
        val presence: IosUserPresence = userPresence ?: return Protection.Accessible
        return when (val created: AccessControlResult = createAccessControl(accessibility, presence.policy)) {
            is AccessControlResult.Created -> Protection.Control(created.control)
            is AccessControlResult.Failed -> throw AccessControlUnavailable(created.code)
        }
    }

    private fun KeychainQuery.putProtection(protection: Protection) {
        when (protection) {
            Protection.Accessible -> putConstant(kSecAttrAccessible, accessibility.constant)
            is Protection.Control -> putCreated(kSecAttrAccessControl, protection.control)
        }
    }

    private sealed interface Protection {
        /** `kSecAttrAccessible` with the store's class. */
        data object Accessible : Protection

        /** `kSecAttrAccessControl`; handed to the query that is built next, which releases it. */
        class Control(val control: platform.CoreFoundation.CFTypeRef) : Protection
    }

    private fun exactSynchronizable(): kotlinx.cinterop.CPointer<out kotlinx.cinterop.CPointed>? =
        if (synchronizable) kCFBooleanTrue else kCFBooleanFalse

    /**
     * What every probe of [DefaultAccessGroup] has in common: a local item of the probe service, which the store's own
     * reads, `remove` and `clear` never match. No account and no group: [probeAdd] and [probeRemove] add those.
     */
    private fun probeScope(): KeychainQuery = KeychainQuery().apply {
        putConstant(kSecClass, kSecClassGenericPassword)
        putString(kSecAttrService, probeService(service))
        putConstant(kSecAttrSynchronizable, kCFBooleanFalse)
    }

    /**
     * Reads whichever probe of the probe service the Keychain returns first, in any group, with its attributes.
     * Production code does not use it: a lookup that names no group would adopt another process's probe (see
     * [DefaultAccessGroup]). It stays because `KeychainQueriesBreakerR1Test` pins its shape.
     */
    fun probeLookup(): KeychainQuery = probeScope().apply {
        putConstant(kSecReturnAttributes, kCFBooleanTrue)
        putConstant(kSecMatchLimit, kSecMatchLimitOne)
    }

    /**
     * A new probe item under [account] (unique per call, see [newProbeAccount]) that reports its attributes: the access
     * group the Keychain files it under. It names no group, which is how the default group is learned.
     */
    fun probeAdd(account: String = newProbeAccount()): KeychainQuery = probeScope().apply {
        putString(kSecAttrAccount, account)
        // Local, and never in a backup: a probe must not outlive its delete anywhere but this device.
        putConstant(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        putConstant(kSecReturnAttributes, kCFBooleanTrue)
    }

    /**
     * Every probe of the probe service in [group], whatever its account: the removal of this call's own probe, which
     * also sweeps a leftover of an earlier killed call. It never reaches another group.
     */
    fun probeRemove(group: String): KeychainQuery = probeScope().apply {
        putString(kSecAttrAccessGroup, group)
    }

    internal companion object {
        /** The prefix of every probe account; [newProbeAccount] appends a random UUID. */
        const val PROBE_ACCOUNT: String = "kmptoolkit.accessgroupprobe"

        /** The service the probe lives in: the store's own with a suffix, so a delete by the store's service never matches it. */
        fun probeService(service: String): String = "$service.accessgroupprobe"

        /** A probe account no other call, process or app extension has used or will use: [PROBE_ACCOUNT] plus a random UUID. */
        fun newProbeAccount(): String = "$PROBE_ACCOUNT.${NSUUID().UUIDString}"
    }
}

/** The outcome of building a `SecAccessControl`. */
@OptIn(ExperimentalForeignApi::class)
internal sealed interface AccessControlResult {

    /** [control] is owned by the caller (+1) until it is handed to a [KeychainQuery]. */
    class Created(val control: platform.CoreFoundation.CFTypeRef) : AccessControlResult

    /** Creation failed with [code] (the `CFError` code, or `-50` when the framework gave none). */
    class Failed(val code: Int) : AccessControlResult
}

/**
 * `SecAccessControlCreateWithFlags` for [policy], protected by [accessibility] class. A pure local call: it builds a
 * Core Foundation object and talks to no daemon.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun createAccessControl(
    accessibility: KeychainAccessibility,
    policy: IosUserPresencePolicy,
): AccessControlResult = memScoped {
    val error = alloc<CFErrorRefVar>()
    val flags = when (policy) {
        IosUserPresencePolicy.USER_PRESENCE -> kSecAccessControlUserPresence
        IosUserPresencePolicy.BIOMETRY_ANY -> kSecAccessControlBiometryAny
        IosUserPresencePolicy.BIOMETRY_CURRENT_SET -> kSecAccessControlBiometryCurrentSet
    }
    val control = SecAccessControlCreateWithFlags(kCFAllocatorDefault, accessibility.constant, flags, error.ptr)
    if (control != null) {
        AccessControlResult.Created(control)
    } else {
        val code: Int = error.value?.let { CFErrorGetCode(it).toInt() } ?: ERR_SEC_PARAM
        error.value?.let { CFRelease(it) }
        AccessControlResult.Failed(code)
    }
}

/** The `SecAccessControl` of a user-presence store could not be built; [code] is the `CFError` code. */
internal class AccessControlUnavailable(val code: Int) : Exception("SecAccessControlCreateWithFlags failed with $code")
