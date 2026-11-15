package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.jvmStorageLockKey
import io.github.jamal_wia.kmptoolkit.storage.secureStoreId
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Who an OS-key store is, to the operating system (plan R7, 4.4, 4.5, 4.7): the store name plus a fingerprint of the
 * canonical directory, folded exactly as `jvmStorageLockKey` folds a path. The OS account is always derived from the
 * directory at open; the header's fingerprint and OS tag are only compared with it, so no value read from a file ever
 * addresses an OS item.
 */
internal class StoreIdentity(
    /** `secureStoreId(name)`: the macOS service and the Linux `service` attribute. */
    val service: String,
    /** The first 128 bits of SHA-256 over the directory's lock key, as 32 lowercase hex characters. */
    val directoryHex: String,
) {
    /** The macOS `kSecAttrAccount`. */
    val macAccount: String get() = "dir-$directoryHex"

    /** The Linux item attributes: both are always passed, so only the own identity is ever touched. */
    val linuxAttributes: Map<String, String> get() = mapOf(LINUX_ATTR_SERVICE to service, LINUX_ATTR_DIRECTORY to directoryHex)

    /** The DPAPI entropy: UTF-8 `"kmptoolkit-storage:" + service`. */
    val windowsEntropy: ByteArray get() = (WINDOWS_ENTROPY_PREFIX + service).toByteArray(StandardCharsets.UTF_8)

    /** A key of the process-wide key cache: one per service and directory, shared by every instance over the same file. */
    val cacheKey: String get() = "$service\u0000$directoryHex"

    /** The 16 bytes of a kind 1 header: compared with the file's before any OS call, never used to address an item. */
    val headerFingerprint: ByteArray
        get() = sha256("kmptoolkit-identity\u0000$service\u0000$directoryHex".toByteArray(StandardCharsets.UTF_8))
            .copyOf(SecureFileFormat.FINGERPRINT_BYTES)

    internal companion object {
        const val LINUX_SCHEMA_NAME: String = "io.github.jamal_wia.kmptoolkit.storage.Key"
        const val LINUX_ATTR_SERVICE: String = "service"
        const val LINUX_ATTR_DIRECTORY: String = "directory"
        const val WINDOWS_ENTROPY_PREFIX: String = "kmptoolkit-storage:"
        private const val DIRECTORY_HEX_CHARS: Int = 32

        /** The identity of the store [name] in [directory]. Reads the file system (the canonical path): call it off the caller's thread. */
        fun of(name: String, directory: File): StoreIdentity = StoreIdentity(secureStoreId(name), directoryHex(directory))

        /** The first 128 bits of SHA-256 over `jvmStorageLockKey(directory)`, as 32 hex characters. */
        fun directoryHex(directory: File): String {
            val digest: ByteArray = sha256(jvmStorageLockKey(directory).toByteArray(StandardCharsets.UTF_8))
            return digest.take(DIRECTORY_HEX_CHARS / 2).joinToString("") { "%02x".format(it) }
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
