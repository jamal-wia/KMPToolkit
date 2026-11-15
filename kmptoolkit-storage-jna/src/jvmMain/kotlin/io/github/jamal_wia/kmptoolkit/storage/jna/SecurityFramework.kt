package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.MacKeychainCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus

/** The CoreFoundation functions the keychain calls need; every name is the C name. */
internal interface CoreFoundationApi : Library {
    fun CFStringCreateWithBytes(allocator: Pointer?, bytes: Pointer, length: Long, encoding: Int, isExternal: Boolean): Pointer?
    fun CFDictionaryCreateMutable(allocator: Pointer?, capacity: Long, keyCallBacks: Pointer?, valueCallBacks: Pointer?): Pointer?
    fun CFDictionarySetValue(dictionary: Pointer, key: Pointer, value: Pointer)
    fun CFDataCreateMutable(allocator: Pointer?, capacity: Long): Pointer?
    fun CFDataAppendBytes(data: Pointer, bytes: Pointer, length: Long)
    fun CFDataGetMutableBytePtr(data: Pointer): Pointer?
    fun CFDataGetBytePtr(data: Pointer): Pointer?
    fun CFDataGetLength(data: Pointer): Long
    fun CFRelease(cf: Pointer)
}

/** The Security.framework functions the keychain calls need; every name is the C name. */
internal interface SecurityApi : Library {
    fun SecItemCopyMatching(query: Pointer, result: PointerByReference?): Int
    fun SecItemAdd(attributes: Pointer, result: Pointer?): Int
    fun SecItemDelete(query: Pointer): Int
}

/** Reads a global variable (a `CFStringRef` constant, a callbacks struct) by its C name; `null` when the symbol is absent. */
internal fun interface GlobalSymbols {
    fun address(name: String): Pointer?
}

/** The two frameworks, loaded by absolute system path (plan S3: never searched for). */
internal class MacPlatform(
    val coreFoundation: CoreFoundationApi,
    val security: SecurityApi,
    val symbols: GlobalSymbols,
) {
    internal companion object {
        const val SECURITY_PATH: String = "/System/Library/Frameworks/Security.framework/Security"
        const val CORE_FOUNDATION_PATH: String = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"

        /** Loads the real frameworks. Throws a [LinkageError] when JNA or a framework cannot load. */
        fun load(): MacPlatform {
            // Force JNA's own native library first, so a failure there is one clear LinkageError.
            Native.POINTER_SIZE
            val cf: NativeLibrary = NativeLibrary.getInstance(CORE_FOUNDATION_PATH)
            val security: NativeLibrary = NativeLibrary.getInstance(SECURITY_PATH)
            return MacPlatform(
                coreFoundation = Native.load(CORE_FOUNDATION_PATH, CoreFoundationApi::class.java),
                security = Native.load(SECURITY_PATH, SecurityApi::class.java),
                symbols = GlobalSymbols { name: String ->
                    try {
                        security.getGlobalVariableAddress(name)
                    } catch (_: UnsatisfiedLinkError) {
                        try {
                            cf.getGlobalVariableAddress(name)
                        } catch (_: UnsatisfiedLinkError) {
                            null
                        }
                    }
                },
            )
        }
    }
}

/**
 * The macOS binding: generic-password items of the login keychain through `SecItem*`.
 *
 * - Every CoreFoundation object a call creates, and the `SecItemCopyMatching` result, is released in a `finally`; the
 *   native copy of a key (the mutable `CFData` of an add, the block that fed it, the data a read returns) is zeroed
 *   before it is released.
 * - `allowUi = false` passes `kSecUseAuthenticationUI = kSecUseAuthenticationUIFail`. `kSecUseAuthenticationUISkip`
 *   is never passed: it silently omits items that need UI and answers -25300.
 * - `kSecUseDataProtectionKeychain = false` is set explicitly on every query (the login file keychain).
 * - There is no update call: a key item is deleted and added again, never updated.
 *
 * Calls arrive one at a time from the native-executor thread; this class holds no state between calls.
 */
internal class JnaMacKeychainCalls(
    private val loaded: Lazy<MacPlatform>,
    private val scratch: ScratchMemory = ScratchMemory.Default,
) : MacKeychainCalls {

    /** The frameworks, loaded by the first call (never by constructing this object). */
    private val platform: MacPlatform get() = loaded.value

    private val cf: CoreFoundationApi get() = platform.coreFoundation

    override fun copyGenericPassword(service: String, account: String, allowUi: Boolean): RawResult<ByteArray?> {
        val refs = Refs()
        try {
            val query: Pointer = identityQuery(refs, service, account)
            put(query, "kSecReturnData", refs.constant("kCFBooleanTrue"))
            put(query, "kSecMatchLimit", constant("kSecMatchLimitOne"))
            if (!allowUi && !suppressUi(query)) return RawResult.Failure(RawStatus(ERR_SEC_NOT_AVAILABLE))
            val result = PointerByReference()
            val status: Int = platform.security.SecItemCopyMatching(query, result)
            val data: Pointer? = result.value
            try {
                if (status != 0) return RawResult.Failure(RawStatus(status))
                if (data == null) return RawResult.Failure(RawStatus(ERR_SEC_INTERNAL_COMPONENT))
                return RawResult.Success(copyAndWipe(data))
            } finally {
                if (data != null) cf.CFRelease(data)
            }
        } finally {
            refs.close()
        }
    }

    // The seam of this commit has no allowUi on add and delete; the advisor's decision adds it (a put with
    // allowUserInteraction = false must not show a keychain dialog). The implementation below already honours it, so
    // adopting the new seam is a signature change only: these two overrides then pass their parameter through.
    override fun addGenericPassword(service: String, account: String, label: String, secret: ByteArray): Int =
        add(service, account, label, secret, allowUi = true)

    override fun deleteGenericPassword(service: String, account: String): Int = delete(service, account, allowUi = true)

    /** `SecItemAdd`; [allowUi] `false` passes `kSecUseAuthenticationUIFail`, and a macOS that cannot suppress the dialog answers `errSecNotAvailable` without calling. */
    fun add(service: String, account: String, label: String, secret: ByteArray, allowUi: Boolean): Int {
        val refs = Refs()
        try {
            val attributes: Pointer = identityQuery(refs, service, account)
            if (!allowUi && !suppressUi(attributes)) return ERR_SEC_NOT_AVAILABLE
            put(attributes, "kSecAttrLabel", refs.string(label))
            val data: Pointer = refs.track(cf.CFDataCreateMutable(null, 0L), "CFDataCreateMutable")
            refs.keyData = data
            scratch.withCopy(secret) { block: Memory -> cf.CFDataAppendBytes(data, block, secret.size.toLong()) }
            put(attributes, "kSecValueData", data)
            return platform.security.SecItemAdd(attributes, null)
        } finally {
            refs.close()
        }
    }

    /** `SecItemDelete`; [allowUi] `false` passes `kSecUseAuthenticationUIFail`, and a macOS that cannot suppress the dialog answers `errSecNotAvailable` without calling. */
    fun delete(service: String, account: String, allowUi: Boolean): Int {
        val refs = Refs()
        try {
            val query: Pointer = identityQuery(refs, service, account)
            if (!allowUi && !suppressUi(query)) return ERR_SEC_NOT_AVAILABLE
            return platform.security.SecItemDelete(query)
        } finally {
            refs.close()
        }
    }

    override fun probe(allowUi: Boolean): Int {
        val refs = Refs()
        try {
            val query: Pointer = refs.dictionary()
            put(query, "kSecClass", constant("kSecClassGenericPassword"))
            put(query, "kSecAttrService", refs.string(PROBE_SERVICE))
            put(query, "kSecReturnAttributes", refs.constant("kCFBooleanTrue"))
            put(query, "kSecMatchLimit", constant("kSecMatchLimitOne"))
            dataProtectionOff(query, refs)
            // A probe never prompts (plan S15), whatever allowUi says: the real call shows the unlock dialog.
            if (!suppressUi(query)) return ERR_SEC_NOT_AVAILABLE
            val result = PointerByReference()
            val status: Int = platform.security.SecItemCopyMatching(query, result)
            val found: Pointer? = result.value
            if (found != null) cf.CFRelease(found)
            return status
        } finally {
            refs.close()
        }
    }

    private fun identityQuery(refs: Refs, service: String, account: String): Pointer {
        val query: Pointer = refs.dictionary()
        put(query, "kSecClass", constant("kSecClassGenericPassword"))
        put(query, "kSecAttrService", refs.string(service))
        put(query, "kSecAttrAccount", refs.string(account))
        dataProtectionOff(query, refs)
        return query
    }

    /** The login (file) keychain, explicitly; skipped on a macOS that has no such constant (it only exists from 10.15). */
    private fun dataProtectionOff(query: Pointer, refs: Refs) {
        val key: Pointer = platform.symbols.address("kSecUseDataProtectionKeychain")?.getPointer(0L) ?: return
        cf.CFDictionarySetValue(query, key, refs.constant("kCFBooleanFalse"))
    }

    /** Adds `kSecUseAuthenticationUI = kSecUseAuthenticationUIFail`; `false` when this macOS has no such constant. */
    private fun suppressUi(query: Pointer): Boolean {
        val key: Pointer = platform.symbols.address("kSecUseAuthenticationUI")?.getPointer(0L) ?: return false
        val value: Pointer = platform.symbols.address("kSecUseAuthenticationUIFail")?.getPointer(0L) ?: return false
        cf.CFDictionarySetValue(query, key, value)
        return true
    }

    private fun constant(name: String): Pointer =
        platform.symbols.address(name)?.getPointer(0L) ?: throw UnsatisfiedLinkError("Security.framework constant $name is missing")

    private fun put(dictionary: Pointer, keyName: String, value: Pointer) {
        cf.CFDictionarySetValue(dictionary, constant(keyName), value)
    }

    /** Copies a returned `CFData` out and zeroes it: the read is the only reference to that fresh object. */
    private fun copyAndWipe(data: Pointer): ByteArray {
        val length: Long = cf.CFDataGetLength(data)
        val bytes: Pointer? = cf.CFDataGetBytePtr(data)
        if (length <= 0L || bytes == null) return ByteArray(0)
        val copy: ByteArray = bytes.getByteArray(0L, length.toInt())
        bytes.clear(length)
        return copy
    }

    /** Tracks the CoreFoundation objects of one call; [close] zeroes the key data and releases everything, last created first. */
    private inner class Refs {
        private val held: ArrayList<Pointer> = ArrayList()
        var keyData: Pointer? = null

        fun track(ref: Pointer?, what: String): Pointer {
            val created: Pointer = ref ?: throw IllegalStateException("$what returned NULL")
            held.add(created)
            return created
        }

        fun string(text: String): Pointer {
            val bytes: ByteArray = text.utf8()
            return scratch.withCopy(bytes) { block: Memory ->
                track(cf.CFStringCreateWithBytes(null, block, bytes.size.toLong(), UTF8_ENCODING, false), "CFStringCreateWithBytes")
            }
        }

        fun dictionary(): Pointer = track(
            cf.CFDictionaryCreateMutable(
                null,
                0L,
                platform.symbols.address("kCFTypeDictionaryKeyCallBacks"),
                platform.symbols.address("kCFTypeDictionaryValueCallBacks"),
            ),
            "CFDictionaryCreateMutable",
        )

        /** A constant such as `kCFBooleanTrue`: owned by the system, never released. */
        fun constant(name: String): Pointer = this@JnaMacKeychainCalls.constant(name)

        fun close() {
            val key: Pointer? = keyData
            if (key != null) {
                val length: Long = cf.CFDataGetLength(key)
                val bytes: Pointer? = cf.CFDataGetMutableBytePtr(key)
                if (length > 0L && bytes != null) bytes.clear(length)
            }
            for (index: Int in held.indices.reversed()) cf.CFRelease(held[index])
            held.clear()
        }
    }

    internal companion object {
        /** `kCFStringEncodingUTF8`. */
        const val UTF8_ENCODING: Int = 0x08000100

        /** `errSecNotAvailable`: reported when the UI cannot be suppressed on this macOS, so a call never prompts by accident. */
        const val ERR_SEC_NOT_AVAILABLE: Int = -25291

        /** `errSecInternalComponent`: success with no data, which `SecItemCopyMatching` never does. */
        const val ERR_SEC_INTERNAL_COMPONENT: Int = -2070

        /** The service of the attribute-only probe query; no item with this service is ever created. */
        const val PROBE_SERVICE: String = "kmptoolkit-storage.probe"
    }
}
