package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.secureStoreId
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Who an OS-key store is, to the operating system (plan R7, 4.4, 4.5, 4.7): the store file's name as the volume stores
 * it plus a fingerprint of the file's canonical directory, from a [RealStorePath]. There is NO case folding: two
 * directories that only differ in case (or `t_ı` and `t_i`) are two stores on a volume that tells them apart, and the
 * name is the stored spelling, so two spellings of one file on a case-insensitive volume are one identity. Nothing here
 * falls back to another path when the file system cannot be asked: the caller fails that one call instead (a second
 * identity for the same store would read as "key lost"). The OS account is always derived from the file at the start of
 * each round; the header's fingerprint and OS tag are only compared with it, so no value read from a file ever addresses
 * an OS item.
 */
internal class StoreIdentity(
    /** The stored file name without its suffix (`secureStoreId(name)` for the spelling that created the file): the macOS service and the Linux `service` attribute. */
    val service: String,
    /** The first 128 bits of SHA-256 over the canonical directory path, as 32 lowercase hex characters. */
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
        /** The suffix of the OS-key store's file name; the service is the stored name without it. */
        const val SECURE_FILE_SUFFIX: String = ".bin"
        const val LINUX_SCHEMA_NAME: String = "io.github.jamal_wia.kmptoolkit.storage.Key"
        const val LINUX_ATTR_SERVICE: String = "service"
        const val LINUX_ATTR_DIRECTORY: String = "directory"
        const val WINDOWS_ENTROPY_PREFIX: String = "kmptoolkit-storage:"
        private const val DIRECTORY_HEX_CHARS: Int = 32

        /**
         * The identity of the store file [real]. [normalizeUnicode] is `true` on macOS only: APFS and HFS+ ignore Unicode
         * normalization forms, so the same file may be spelled NFC when created and NFD afterwards (HFS+), and NFC makes both
         * one identity without ever merging two files; on Linux and Windows the form is kept as the volume stores it.
         */
        fun of(real: RealStorePath, normalizeUnicode: Boolean): StoreIdentity {
            fun form(text: String): String = if (normalizeUnicode) Normalizer.normalize(text, Normalizer.Form.NFC) else text
            return StoreIdentity(form(real.name.removeSuffix(SECURE_FILE_SUFFIX)), directoryHex(form(real.directory)))
        }

        /** The identity of the store [name] in [directory] on macOS rules, resolved on the real file system. For tests. */
        fun of(name: String, directory: File): StoreIdentity =
            of(StorePathResolver.real.resolve(File(directory, secureStoreId(name) + SECURE_FILE_SUFFIX)), normalizeUnicode = true)

        /** The first 128 bits of SHA-256 over [directoryPath], as 32 hex characters. */
        fun directoryHex(directoryPath: String): String {
            val digest: ByteArray = sha256(directoryPath.toByteArray(StandardCharsets.UTF_8))
            return digest.take(DIRECTORY_HEX_CHARS / 2).joinToString("") { "%02x".format(it) }
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
