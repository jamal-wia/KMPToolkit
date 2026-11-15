package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.LocalAuthentication.LAContext
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessGroup

/**
 * Every call [IosSecureKeyValueStorage] makes into the Security framework, carrying the dictionaries
 * [KeychainQueries] built and the raw `OSStatus` back: a test seam, so the store's logic (which query each
 * operation sends, the update-then-add retry, the default access group resolution, the error mapping, the
 * user-presence cancellation) runs against a scripted fake in a Kotlin/Native test binary, which has no usable
 * Keychain.
 *
 * A one-to-one adapter, [SecItemKeychainPort], is the only implementation in the library, in the manner of
 * `IosAuthorizationPort` in `kmptoolkit-permission`. The dictionaries are owned and released by the caller
 * ([KeychainQuery.use]); an implementation must not keep one past the call. Every function is blocking and can
 * take as long as the user takes to answer a system sheet, so none may be called while a lock is held.
 *
 * Internal: not API.
 */
@OptIn(ExperimentalForeignApi::class)
internal interface KeychainPort {

    /** `SecItemCopyMatching` for [query] with data returned: the status, and the item's bytes on success. */
    fun readData(query: CFDictionaryRef): KeychainData

    /** `SecItemCopyMatching` with nothing returned (attribute-only): only whether the query matches. */
    fun matches(query: CFDictionaryRef): Int

    /** `SecItemAdd`. */
    fun add(query: CFDictionaryRef): Int

    /** `SecItemAdd` with attributes returned: the status, and the access group the new item was filed under. */
    fun addReadingAccessGroup(query: CFDictionaryRef): KeychainAccessGroup

    /** `SecItemUpdate` of the items [query] matches with [attributes]. */
    fun update(query: CFDictionaryRef, attributes: CFDictionaryRef): Int

    /** `SecItemDelete`. */
    fun delete(query: CFDictionaryRef): Int
}

/** The outcome of [KeychainPort.readData]. */
internal class KeychainData(val status: Int, val data: NSData?)

/** The outcome of [KeychainPort.addReadingAccessGroup]: [group] is the `kSecAttrAccessGroup` value of the new item, if any. */
internal class KeychainAccessGroup(val status: Int, val group: String?)

/** The one real [KeychainPort]: each call is the `SecItem*` function of its name, nothing more. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object SecItemKeychainPort : KeychainPort {

    override fun readData(query: CFDictionaryRef): KeychainData = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status: Int = SecItemCopyMatching(query, result.ptr)
        // The returned CFData is +1; CFBridgingRelease hands it to ARC exactly once.
        val data: NSData? = if (status == errSecSuccess) result.value?.let { CFBridgingRelease(it) } as? NSData else null
        KeychainData(status, data)
    }

    override fun matches(query: CFDictionaryRef): Int = SecItemCopyMatching(query, null)

    override fun add(query: CFDictionaryRef): Int = SecItemAdd(query, null)

    override fun addReadingAccessGroup(query: CFDictionaryRef): KeychainAccessGroup = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status: Int = SecItemAdd(query, result.ptr)
        KeychainAccessGroup(status, if (status == errSecSuccess) takeAccessGroup(result.value) else null)
    }

    override fun update(query: CFDictionaryRef, attributes: CFDictionaryRef): Int = SecItemUpdate(query, attributes)

    override fun delete(query: CFDictionaryRef): Int = SecItemDelete(query)

    /**
     * Reads `kSecAttrAccessGroup` out of the attribute dictionary a `SecItem*` call returned and releases that
     * dictionary (the call returned it +1). Null when the dictionary is missing or has no string there.
     */
    private fun takeAccessGroup(attributes: CFTypeRef?): String? {
        val dictionary: CFDictionaryRef = attributes?.reinterpret() ?: return null
        try {
            val value = CFDictionaryGetValue(dictionary, kSecAttrAccessGroup) ?: return null
            // CFBridgingRelease balances a +1: retain first, so the dictionary keeps its own reference.
            return CFBridgingRelease(CFRetain(value)) as? String
        } finally {
            CFRelease(dictionary)
        }
    }
}

/**
 * One authentication prompt in flight: the `LAContext` the Keychain consults for the sheet's reason, and the
 * way to dismiss it. A test seam like [KeychainPort]; [LocalAuthenticationSessions] is the real implementation.
 *
 * Internal: not API.
 */
internal interface AuthenticationSession {

    /** The object passed as `kSecUseAuthenticationContext` (an `LAContext`). */
    val handle: Any

    /**
     * Dismisses the sheet if it is showing, which makes a `SecItem*` call that is waiting on it return, and
     * releases the context. Idempotent; safe from any thread.
     */
    fun invalidate()
}

/** Opens an [AuthenticationSession] for one operation. */
internal fun interface AuthenticationSessions {

    /** A fresh session whose sheet reads [prompt]. Never reused between operations: a context caches a success. */
    fun open(prompt: String): AuthenticationSession
}

/** The real sessions: one new `LAContext` per operation, its `localizedReason` the app's prompt. */
internal object LocalAuthenticationSessions : AuthenticationSessions {

    override fun open(prompt: String): AuthenticationSession {
        val context = LAContext()
        context.localizedReason = prompt
        return LocalAuthenticationSession(context)
    }
}

private class LocalAuthenticationSession(private val context: LAContext) : AuthenticationSession {

    override val handle: Any get() = context

    // LAContext.invalidate() is documented as safe to call repeatedly, so no guard is needed here.
    override fun invalidate() {
        context.invalidate()
    }
}
