package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.secureStoreId
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
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
        get() = Jca.sha256("kmptoolkit-identity\u0000$service\u0000$directoryHex".toByteArray(StandardCharsets.UTF_8))
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
         *
         * The stored name must end in [SECURE_FILE_SUFFIX], and the service is the name without it. The suffix is matched byte for
         * byte, except in ASCII case when [configuredNameIsLink] answers `false` (it is asked only then): without a link at the
         * store's own configured name, the resolved name IS the configured file as the volume spells it, and on a volume that folds
         * case (APFS and HFS+ by default, NTFS, ext4 casefold) the configured `x.bin` and a stored `x.BIN` are one file, so refusing
         * the stored spelling would lock the store's own file out for good. On a volume that tells `x.bin` and `x.BIN` apart the
         * configured name without a link can only resolve to `x.bin`. With a link, the target can be any name and only the exact
         * suffix is accepted, whatever the case folding of the volume. Only ASCII is folded: a look-alike (a dotless `i`, a
         * fullwidth letter, a combining mark) is never the suffix.
         *
         * @throws IOException when the stored name does not end in the suffix by that rule. The real path of a file the user LINKED
         *   to can be any name, and stripping the suffix "when present" would give `X` and `X.bin` one service: two store files,
         *   one key item, a create of one deleting the other's key.
         */
        fun of(real: RealStorePath, normalizeUnicode: Boolean, configuredNameIsLink: () -> Boolean = { true }): StoreIdentity {
            val name: String = real.name
            val hasSuffix: Boolean = name.length > SECURE_FILE_SUFFIX.length &&
                (name.endsWith(SECURE_FILE_SUFFIX) || (hasSuffixInAnyAsciiCase(name) && !configuredNameIsLink()))
            if (!hasSuffix) throw IOException("The store file resolves to a name without the $SECURE_FILE_SUFFIX suffix")
            fun form(text: String): String = if (normalizeUnicode) Normalizer.normalize(text, Normalizer.Form.NFC) else text
            return StoreIdentity(form(name.dropLast(SECURE_FILE_SUFFIX.length)), directoryHex(form(real.directory)))
        }

        /** Whether [name] ends in `.bin` with the three letters in any ASCII case (and nothing else folded). */
        private fun hasSuffixInAnyAsciiCase(name: String): Boolean {
            val tail: String = name.takeLast(SECURE_FILE_SUFFIX.length)
            return tail.length == SECURE_FILE_SUFFIX.length && tail[0] == '.' &&
                (tail[1] == 'b' || tail[1] == 'B') && (tail[2] == 'i' || tail[2] == 'I') && (tail[3] == 'n' || tail[3] == 'N')
        }

        /** The identity of the store [name] in [directory] on macOS rules, resolved on the real file system. For tests. */
        fun of(name: String, directory: File): StoreIdentity =
            of(StorePathResolver.real.resolve(File(directory, secureStoreId(name) + SECURE_FILE_SUFFIX)), normalizeUnicode = true)

        /** The first 128 bits of SHA-256 over [directoryPath], as 32 hex characters. */
        fun directoryHex(directoryPath: String): String {
            val digest: ByteArray = Jca.sha256(directoryPath.toByteArray(StandardCharsets.UTF_8))
            return digest.take(DIRECTORY_HEX_CHARS / 2).joinToString("") { "%02x".format(it) }
        }
    }
}
