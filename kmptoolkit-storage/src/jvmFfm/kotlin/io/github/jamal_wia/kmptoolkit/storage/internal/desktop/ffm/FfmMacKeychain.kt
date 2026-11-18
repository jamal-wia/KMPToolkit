package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.MacKeychainCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * The macOS half of the FFM binding: Security.framework `SecItem*` generic passwords of the login (file) keychain,
 * through CoreFoundation dictionaries, with no CLI tool in between (plan S3).
 *
 * - **Libraries by absolute path** (`/System/Library/Frameworks/...`), never by name search.
 * - **Every `CFTypeRef` this layer creates is owned by the call's [NativeScope]** the line after it is created, and every
 *   `CFRelease` runs in `close()`, so a failing allocation or a failing later step leaves nothing behind.
 * - **The key never lives in a CF-owned buffer this layer made**: the secret is copied into a scope buffer that a
 *   `CFData` wraps without copying (`CFDataCreateWithBytesNoCopy` with `kCFAllocatorNull`), and that buffer is zeroed
 *   before it is freed. The `CFData` that `SecItemCopyMatching` returns belongs to the Security framework (it may be
 *   shared with its own caches), so it is copied out and released, not overwritten.
 * - **`kSecUseAuthenticationUISkip` is never passed** (it silently omits items that need UI and answers -25300); with
 *   `allowUi = false` the query carries `kSecUseAuthenticationUI = kSecUseAuthenticationUIFail`.
 * - **`kSecUseDataProtectionKeychain = false` is explicit**, so the file keychain is the one asked, on every macOS that has the key.
 * - There is no update call: a key item is deleted and added again, never `SecItemUpdate`d (plan R7).
 *
 * Every status is the raw `OSStatus`; this class makes no decision about it.
 */
internal class FfmMacKeychain(
    private val foreign: Foreign,
    private val ledger: NativeLedger,
) : MacKeychainCalls {

    private val security: SymbolLookup = foreign.open(SECURITY_PATH)
    private val coreFoundation: SymbolLookup = foreign.open(CORE_FOUNDATION_PATH)

    private val cfStringCreateWithBytes: Downcall = foreign.downcall(
        coreFoundation, "CFStringCreateWithBytes",
        FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_BYTE,
        ),
    )
    private val cfDataCreateNoCopy: Downcall = foreign.downcall(
        coreFoundation, "CFDataCreateWithBytesNoCopy",
        FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
        ),
    )
    private val cfDictionaryCreateMutable: Downcall = foreign.downcall(
        coreFoundation, "CFDictionaryCreateMutable",
        FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ),
    )
    private val cfDictionarySetValue: Downcall = foreign.downcall(
        coreFoundation, "CFDictionarySetValue",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val cfRelease: Downcall =
        foreign.downcall(coreFoundation, "CFRelease", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val cfDataGetLength: Downcall =
        foreign.downcall(coreFoundation, "CFDataGetLength", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))
    private val cfDataGetBytePtr: Downcall =
        foreign.downcall(coreFoundation, "CFDataGetBytePtr", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    private val secItemCopyMatching: Downcall = foreign.downcall(
        security, "SecItemCopyMatching",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val secItemAdd: Downcall = foreign.downcall(
        security, "SecItemAdd",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val secItemDelete: Downcall =
        foreign.downcall(security, "SecItemDelete", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))

    // Global CFStringRef / CFBooleanRef variables: the symbol is the address of the variable, the value is what it holds.
    private val kCFAllocatorNull: MemorySegment = constant(coreFoundation, "kCFAllocatorNull")
    private val kCFBooleanTrue: MemorySegment = constant(coreFoundation, "kCFBooleanTrue")
    private val kCFBooleanFalse: MemorySegment = constant(coreFoundation, "kCFBooleanFalse")
    private val keyCallBacks: MemorySegment = foreign.require(coreFoundation, "kCFTypeDictionaryKeyCallBacks")
    private val valueCallBacks: MemorySegment = foreign.require(coreFoundation, "kCFTypeDictionaryValueCallBacks")

    private val kSecClass: MemorySegment = constant(security, "kSecClass")
    private val kSecClassGenericPassword: MemorySegment = constant(security, "kSecClassGenericPassword")
    private val kSecAttrService: MemorySegment = constant(security, "kSecAttrService")
    private val kSecAttrAccount: MemorySegment = constant(security, "kSecAttrAccount")
    private val kSecAttrLabel: MemorySegment = constant(security, "kSecAttrLabel")
    private val kSecValueData: MemorySegment = constant(security, "kSecValueData")
    private val kSecReturnData: MemorySegment = constant(security, "kSecReturnData")
    private val kSecReturnAttributes: MemorySegment = constant(security, "kSecReturnAttributes")
    private val kSecMatchLimit: MemorySegment = constant(security, "kSecMatchLimit")
    private val kSecMatchLimitOne: MemorySegment = constant(security, "kSecMatchLimitOne")
    private val kSecUseAuthenticationUI: MemorySegment = constant(security, "kSecUseAuthenticationUI")
    private val kSecUseAuthenticationUIFail: MemorySegment = constant(security, "kSecUseAuthenticationUIFail")

    // macOS 10.15 and newer; on an older macOS the file keychain is the only one, so leaving the key out asks the same question.
    private val kSecUseDataProtectionKeychain: MemorySegment? = optionalConstant(security, "kSecUseDataProtectionKeychain")

    override fun copyGenericPassword(service: String, account: String, allowUi: Boolean): RawResult<ByteArray?> =
        ledger.scope().use { scope: NativeScope ->
            val query: MemorySegment = identityQuery(scope, service, account)
            setValue(query, kSecReturnData, kCFBooleanTrue)
            setValue(query, kSecMatchLimit, kSecMatchLimitOne)
            if (!allowUi) setValue(query, kSecUseAuthenticationUI, kSecUseAuthenticationUIFail)
            val result: MemorySegment = scope.arena.allocate(ValueLayout.ADDRESS)
            val status: Int = secItemCopyMatching.int(query, result)
            if (status != ERR_SEC_SUCCESS) return@use RawResult.Failure(RawStatus(status))
            val data: MemorySegment = result.get(ValueLayout.ADDRESS, 0L)
            if (data.address() == 0L) return@use RawResult.Failure(RawStatus(ERR_SEC_PARAM))
            scope.own(data) { value: MemorySegment -> cfRelease.invoke(value) }
            val length: Long = cfDataGetLength.long(data)
            if (length < 0L || length > MAX_SECRET_BYTES) return@use RawResult.Failure(RawStatus(ERR_SEC_PARAM))
            val bytes: ByteArray = if (length == 0L) {
                ByteArray(0)
            } else {
                cfDataGetBytePtr.pointer(data).reinterpret(length).toArray(ValueLayout.JAVA_BYTE)
            }
            RawResult.Success(bytes)
        }

    /** `SecItemAdd`; with [allowUi] `false` the attributes carry `kSecUseAuthenticationUIFail`, so a put cannot show a keychain dialog. */
    override fun addGenericPassword(service: String, account: String, label: String, secret: ByteArray, allowUi: Boolean): Int =
        ledger.scope().use { scope: NativeScope ->
            val attributes: MemorySegment = identityQuery(scope, service, account)
            if (!allowUi) setValue(attributes, kSecUseAuthenticationUI, kSecUseAuthenticationUIFail)
            setValue(attributes, kSecAttrLabel, cfString(scope, label))
            // The CFData wraps this buffer (no copy, nobody frees it): the scope zeroes it and then frees it, after the CFData is released.
            val buffer: MemorySegment = scope.secret(secret)
            val data: MemorySegment = scope.own(
                nonNull(cfDataCreateNoCopy.pointer(MemorySegment.NULL, buffer, secret.size.toLong(), kCFAllocatorNull), "CFDataCreateWithBytesNoCopy"),
            ) { value: MemorySegment -> cfRelease.invoke(value) }
            setValue(attributes, kSecValueData, data)
            secItemAdd.int(attributes, MemorySegment.NULL)
        }

    /** `SecItemDelete`; with [allowUi] `false` the query carries `kSecUseAuthenticationUIFail`. */
    override fun deleteGenericPassword(service: String, account: String, allowUi: Boolean): Int =
        ledger.scope().use { scope: NativeScope ->
            val query: MemorySegment = identityQuery(scope, service, account)
            if (!allowUi) setValue(query, kSecUseAuthenticationUI, kSecUseAuthenticationUIFail)
            secItemDelete.int(query)
        }

    override fun probe(allowUi: Boolean): Int =
        ledger.scope().use { scope: NativeScope ->
            // A probe never prompts, whatever [allowUi] says: an unlock dialog is the one thing it must not show (plan S15).
            val query: MemorySegment = identityQuery(scope, PROBE_SERVICE, PROBE_ACCOUNT)
            setValue(query, kSecReturnAttributes, kCFBooleanTrue)
            setValue(query, kSecMatchLimit, kSecMatchLimitOne)
            setValue(query, kSecUseAuthenticationUI, kSecUseAuthenticationUIFail)
            val result: MemorySegment = scope.arena.allocate(ValueLayout.ADDRESS)
            val status: Int = secItemCopyMatching.int(query, result)
            val found: MemorySegment = result.get(ValueLayout.ADDRESS, 0L)
            // An attribute-only match comes back as a CFDictionary we own; release it whatever the status was.
            if (found.address() != 0L) scope.own(found) { value: MemorySegment -> cfRelease.invoke(value) }
            status
        }

    /** A query dictionary naming the generic password at [service] and [account], the file keychain explicitly. */
    private fun identityQuery(scope: NativeScope, service: String, account: String): MemorySegment {
        val dictionary: MemorySegment = scope.own(
            nonNull(
                cfDictionaryCreateMutable.pointer(MemorySegment.NULL, 0L, keyCallBacks, valueCallBacks),
                "CFDictionaryCreateMutable",
            ),
        ) { value: MemorySegment -> cfRelease.invoke(value) }
        setValue(dictionary, kSecClass, kSecClassGenericPassword)
        setValue(dictionary, kSecAttrService, cfString(scope, service))
        setValue(dictionary, kSecAttrAccount, cfString(scope, account))
        if (kSecUseDataProtectionKeychain != null) setValue(dictionary, kSecUseDataProtectionKeychain, kCFBooleanFalse)
        return dictionary
    }

    /** A `CFString` of [text] (UTF-8), owned by [scope]. */
    private fun cfString(scope: NativeScope, text: String): MemorySegment {
        val bytes: ByteArray = text.toByteArray(Charsets.UTF_8)
        val buffer: MemorySegment = scope.arena.allocate(bytes.size.toLong().coerceAtLeast(1L))
        MemorySegment.copy(bytes, 0, buffer, ValueLayout.JAVA_BYTE, 0L, bytes.size)
        return scope.own(
            nonNull(
                cfStringCreateWithBytes.pointer(MemorySegment.NULL, buffer, bytes.size.toLong(), UTF8_ENCODING, 0.toByte()),
                "CFStringCreateWithBytes",
            ),
        ) { value: MemorySegment -> cfRelease.invoke(value) }
    }

    private fun setValue(dictionary: MemorySegment, key: MemorySegment, value: MemorySegment) {
        cfDictionarySetValue.invoke(dictionary, key, value)
    }

    private fun constant(lookup: SymbolLookup, symbol: String): MemorySegment =
        foreign.require(lookup, symbol).reinterpret(POINTER_SIZE).get(ValueLayout.ADDRESS, 0L)

    private fun optionalConstant(lookup: SymbolLookup, symbol: String): MemorySegment? =
        foreign.find(lookup, symbol)?.reinterpret(POINTER_SIZE)?.get(ValueLayout.ADDRESS, 0L)

    /** A failed allocation answers NULL: the call fails here, and the scope frees what was allocated before it. */
    private fun nonNull(pointer: MemorySegment, what: String): MemorySegment {
        if (pointer.address() == 0L) throw OutOfMemoryError("$what returned NULL")
        return pointer
    }

    internal companion object {
        const val SECURITY_PATH: String = "/System/Library/Frameworks/Security.framework/Security"
        const val CORE_FOUNDATION_PATH: String = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"

        /** The service of the probe query; no store identity ever equals it. */
        const val PROBE_SERVICE: String = "kmptoolkit-storage.probe"
        const val PROBE_ACCOUNT: String = "probe"

        const val ERR_SEC_SUCCESS: Int = 0

        /** `errSecParam`: the call succeeded but returned no usable data. */
        const val ERR_SEC_PARAM: Int = -50

        /** `kCFStringEncodingUTF8`. */
        const val UTF8_ENCODING: Int = 0x08000100

        /** A stored secret is a 32-byte key (or its base64 text); anything beyond this is not ours and is refused rather than copied. */
        const val MAX_SECRET_BYTES: Long = 1L shl 20
        private const val POINTER_SIZE: Long = 8L
    }
}
