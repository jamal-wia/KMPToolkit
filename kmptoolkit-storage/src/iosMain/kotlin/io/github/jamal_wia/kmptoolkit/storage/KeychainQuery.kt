package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain

/**
 * Builds a `CFDictionary` for the `SecItem*` functions one entry at a time, and owns the references
 * it put in it.
 *
 * ### Why this exists instead of bridging a Kotlin `Map`
 *
 * The obvious Kotlin/Native spelling is to build an `NSMutableDictionary` (or a Kotlin `Map`) and
 * hand it to `SecItemCopyMatching` through `CFBridgingRetain(...) as CFDictionaryRef`. It is what
 * the code this module was ported from did, it is what most sample code does, and it stopped
 * working on iOS 26: the Security framework rejects the bridged dictionary with `errSecParam`
 * (`-50`), and because the failure arrives as an opaque status on a call that is supposed to be
 * infallible, the symptom in an app is usually a hang or a silently empty Keychain rather than an
 * error anyone can read. See `docs/kmptoolkit-storage/05-platform-notes.md`.
 *
 * A dictionary created by `CFDictionaryCreateMutable` and filled with `CFDictionaryAddValue` is
 * accepted on every iOS version, so that is what this builds. The cost is manual memory
 * management, which is contained here: every `CFBridgingRetain` performed by [putString] and
 * [putData] is balanced in [release], and so is the dictionary itself.
 *
 * Not thread-safe and not reusable — build one, pass it to exactly one `SecItem*` call, release it.
 * [use] enforces that shape.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KeychainQuery {

    private val retained: MutableList<CFTypeRef> = mutableListOf()

    private val dictionary: CFMutableDictionaryRef = requireNotNull(
        CFDictionaryCreateMutable(
            allocator = kCFAllocatorDefault,
            capacity = 0,
            keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
            valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
        ),
    ) { "CFDictionaryCreateMutable returned null, which means the process is out of memory" }

    /** Adds a constant value — a `kSec*` global, which is already a `CFTypeRef` we do not own. */
    fun putConstant(key: CFTypeRef?, value: CFTypeRef?) {
        CFDictionaryAddValue(dictionary, key, value)
    }

    /** Adds a Kotlin string, bridged to a `CFString` this query owns until [release]. */
    fun putString(key: CFTypeRef?, value: String) {
        putOwned(key, CFBridgingRetain(value))
    }

    /** Adds bytes, bridged to a `CFData` this query owns until [release]. */
    fun putData(key: CFTypeRef?, value: Any) {
        putOwned(key, CFBridgingRetain(value))
    }

    private fun putOwned(key: CFTypeRef?, value: CFTypeRef?) {
        val owned: CFTypeRef = value ?: return
        // The dictionary retains what it is given; this list holds the +1 from CFBridgingRetain so
        // release() can balance it. Adding to the dictionary first keeps the value alive even if
        // the list allocation were to fail.
        CFDictionaryAddValue(dictionary, key, owned)
        retained += owned
    }

    private fun release() {
        retained.forEach { CFBridgingRelease(it) }
        retained.clear()
        CFRelease(dictionary)
    }

    /**
     * Runs [block] with the finished dictionary, then releases everything this query owns.
     *
     * The dictionary must not escape [block]: after it returns, the reference is dead.
     */
    fun <T> use(block: (CFDictionaryRef) -> T): T = try {
        block(dictionary)
    } finally {
        release()
    }
}
