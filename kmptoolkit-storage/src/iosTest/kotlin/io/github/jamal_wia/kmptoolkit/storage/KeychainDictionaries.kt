package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFDataGetTypeID
import platform.CoreFoundation.CFDictionaryContainsKey
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringGetTypeID
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenUnlocked
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
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
 * Reads a finished Keychain dictionary back as plain strings, so a test asserts what a query *says* without a
 * Keychain: one entry per attribute the store ever sends, named by a short label, its value a readable token
 * (`true`, `any`, `afterFirstUnlock`, the string itself, `data` for bytes, `object` for a Core Foundation object
 * such as the `SecAccessControl` or the `LAContext`). An attribute that is absent from the dictionary is absent
 * from the map, which is how a test says "this query does not name an access group".
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun describe(dictionary: CFDictionaryRef): Map<String, String> {
    val result: MutableMap<String, String> = linkedMapOf()
    for ((label: String, key: CFTypeRef?) in ATTRIBUTES) {
        if (CFDictionaryContainsKey(dictionary, key)) {
            result[label] = render(CFDictionaryGetValue(dictionary, key))
        }
    }
    return result
}

/** The bytes under `kSecValueData`, or `null` when the dictionary carries none. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun dataOf(dictionary: CFDictionaryRef): NSData? {
    val value = CFDictionaryGetValue(dictionary, kSecValueData) ?: return null
    return CFBridgingRelease(CFRetain(value)) as? NSData
}

@OptIn(ExperimentalForeignApi::class)
private val ATTRIBUTES: List<Pair<String, CFTypeRef?>> = listOf(
    "class" to kSecClass,
    "service" to kSecAttrService,
    "account" to kSecAttrAccount,
    "group" to kSecAttrAccessGroup,
    "synchronizable" to kSecAttrSynchronizable,
    "accessible" to kSecAttrAccessible,
    "accessControl" to kSecAttrAccessControl,
    "value" to kSecValueData,
    "limit" to kSecMatchLimit,
    "returnData" to kSecReturnData,
    "returnAttributes" to kSecReturnAttributes,
    "authUI" to kSecUseAuthenticationUI,
    "context" to kSecUseAuthenticationContext,
)

@OptIn(ExperimentalForeignApi::class)
private val TOKENS: List<Pair<String, CFTypeRef?>> = listOf(
    "genericPassword" to kSecClassGenericPassword,
    "whenUnlocked" to kSecAttrAccessibleWhenUnlocked,
    "whenUnlockedThisDeviceOnly" to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
    "afterFirstUnlock" to kSecAttrAccessibleAfterFirstUnlock,
    "afterFirstUnlockThisDeviceOnly" to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    "whenPasscodeSetThisDeviceOnly" to kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
    "any" to kSecAttrSynchronizableAny,
    "one" to kSecMatchLimitOne,
    "fail" to kSecUseAuthenticationUIFail,
    "true" to kCFBooleanTrue,
    "false" to kCFBooleanFalse,
)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun render(value: CFTypeRef?): String {
    if (value == null) return "null"
    // The kSec constants are singletons and the dictionary holds the very pointer that was put in, so identity is the test.
    TOKENS.firstOrNull { (_: String, constant: CFTypeRef?) -> constant == value }
        ?.let { return it.first }
    return when (CFGetTypeID(value)) {
        CFStringGetTypeID() -> (CFBridgingRelease(CFRetain(value)) as? String) ?: "string?"
        CFDataGetTypeID() -> "data"
        else -> "object"
    }
}
