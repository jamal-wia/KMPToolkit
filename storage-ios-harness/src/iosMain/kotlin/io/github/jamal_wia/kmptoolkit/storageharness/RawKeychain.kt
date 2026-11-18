package io.github.jamal_wia.kmptoolkit.storageharness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
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
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecReturnAttributes

/**
 * Raw `SecItem*` access for the harness only: it looks at the Keychain *around* the library, to see what the library
 * really left there. The library's own query builder is internal to it, so this is a small copy of the same
 * technique (a `CFDictionaryCreateMutable` filled entry by entry; a dictionary bridged from a Kotlin `Map` is
 * rejected with `errSecParam` on iOS 26, see `docs/kmptoolkit-storage/05-platform-notes.md`).
 *
 * The service the library files a store under is `<name>.kmptoolkit.securestorage` (documented as an implementation
 * detail in that same file); [serviceOf] is the one place the harness depends on that.
 */
@OptIn(ExperimentalForeignApi::class)
internal object RawKeychain {

    /** One item as the Keychain reports it. Raw attribute values, as strings, `null` where the item has none. */
    class Item(val account: String?, val group: String?, val accessible: String?, val synchronizable: String?) {
        override fun toString(): String = "account=$account group=$group accessible=$accessible sync=$synchronizable"
    }

    fun serviceOf(storeName: String): String = "$storeName.kmptoolkit.securestorage"

    /** Every item of [service] in every group the app belongs to (or only [group]), local and synchronizable alike. */
    fun items(service: String, group: String? = null): List<Item> {
        val dictionary = Dict()
        dictionary.constant(kSecClass, kSecClassGenericPassword)
        dictionary.string(kSecAttrService, service)
        group?.let { dictionary.string(kSecAttrAccessGroup, it) }
        dictionary.constant(kSecAttrSynchronizable, kSecAttrSynchronizableAny)
        dictionary.constant(kSecReturnAttributes, kCFBooleanTrue)
        dictionary.constant(kSecMatchLimit, kSecMatchLimitAll)
        return dictionary.use { query ->
            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status: Int = SecItemCopyMatching(query, result.ptr)
                when (status) {
                    errSecSuccess -> {
                        val rows: List<*> = (result.value?.let { CFBridgingRelease(it) } as? List<*>).orEmpty()
                        rows.mapNotNull { row ->
                            (row as? Map<*, *>)?.let {
                                // The documented values of kSecAttrAccount, kSecAttrAccessGroup, kSecAttrAccessible,
                                // kSecAttrSynchronizable.
                                Item(
                                    account = it["acct"]?.toString(),
                                    group = it["agrp"]?.toString(),
                                    accessible = it["pdmn"]?.toString(),
                                    synchronizable = it["sync"]?.toString(),
                                )
                            }
                        }
                    }

                    errSecItemNotFound -> emptyList()
                    else -> error("SecItemCopyMatching for $service answered $status")
                }
            }
        }
    }

    /**
     * What the library's `clear()` used to send before 6.0.0, reproduced by hand: a delete by service that names no
     * group and no synchronizable attribute. The harness uses it to show defect I-2b on this device. Returns the status.
     */
    fun deleteWithoutGroup(service: String): Int {
        val dictionary = Dict()
        dictionary.constant(kSecClass, kSecClassGenericPassword)
        dictionary.string(kSecAttrService, service)
        return dictionary.use { SecItemDelete(it) }
    }

    /**
     * Adds a local item of [service] under [account] in exactly [group]: what another process (an app extension that
     * lists [group] first) leaves behind when it is killed between the add and the delete of its default-group
     * probe. Returns the status.
     */
    fun addItem(service: String, account: String, group: String): Int {
        val dictionary = Dict()
        dictionary.constant(kSecClass, kSecClassGenericPassword)
        dictionary.string(kSecAttrService, service)
        dictionary.string(kSecAttrAccount, account)
        dictionary.string(kSecAttrAccessGroup, group)
        dictionary.constant(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        return dictionary.use { SecItemAdd(it, null) }
    }

    /** Deletes every item of [service] in every group, local and synchronizable: harness hygiene between cases. */
    fun wipe(service: String) {
        val dictionary = Dict()
        dictionary.constant(kSecClass, kSecClassGenericPassword)
        dictionary.string(kSecAttrService, service)
        dictionary.constant(kSecAttrSynchronizable, kSecAttrSynchronizableAny)
        dictionary.use { SecItemDelete(it) }
    }

    /** A dictionary under construction; released by [use], as the library's query is. */
    private class Dict {
        private val retained: MutableList<CFTypeRef> = mutableListOf()
        private val dictionary = requireNotNull(
            CFDictionaryCreateMutable(
                kCFAllocatorDefault,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ),
        )

        fun constant(key: CFTypeRef?, value: CFTypeRef?) {
            CFDictionaryAddValue(dictionary, key, value)
        }

        fun string(key: CFTypeRef?, value: String) {
            val owned: CFTypeRef = CFBridgingRetain(value) ?: return
            CFDictionaryAddValue(dictionary, key, owned)
            retained += owned
        }

        fun <T> use(block: (platform.CoreFoundation.CFDictionaryRef) -> T): T = try {
            block(dictionary)
        } finally {
            retained.forEach { CFBridgingRelease(it) }
            CFRelease(dictionary)
        }
    }
}
