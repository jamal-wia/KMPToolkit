package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** The kind of an encrypted store file: who holds the key that opens its data key (plan 4.3). */
internal enum class StoreKind(val id: Int) {
    /** An OS item (macOS keychain, Linux Secret Service) holds the data key. */
    OS_ITEM(1),

    /** The data key is wrapped by DPAPI; the blob is in the header. */
    DPAPI(2),

    /** The data key is wrapped by a key derived from a passphrase (PBKDF2-HMAC-SHA256). */
    PASSPHRASE(3),

    /** The data key is wrapped by a raw app-supplied key. */
    RAW_KEY(4);

    internal companion object {
        fun fromId(id: Int): StoreKind? = entries.firstOrNull { it.id == id }
    }
}

/** The kind-specific header body of format v1. Equality is by content. */
internal sealed interface HeaderBody {
    val kind: StoreKind

    /** The body bytes (without magic, version, kind and length). */
    fun encode(): ByteArray

    /** Kind 1: the identity fingerprint (16 bytes) and the OS tag (1 byte). */
    class OsItem(val fingerprint: ByteArray, val osTag: Int) : HeaderBody {
        init {
            require(fingerprint.size == SecureFileFormat.FINGERPRINT_BYTES) { "fingerprint must be 16 bytes" }
            require(osTag in 0..255) { "osTag must fit a byte" }
        }

        override val kind: StoreKind get() = StoreKind.OS_ITEM

        override fun encode(): ByteArray = fingerprint + byteArrayOf(osTag.toByte())

        override fun equals(other: Any?): Boolean =
            other is OsItem && other.fingerprint.contentEquals(fingerprint) && other.osTag == osTag

        override fun hashCode(): Int = 31 * fingerprint.contentHashCode() + osTag

        override fun toString(): String = "OsItem(tag=$osTag)"
    }

    /** Kind 2: a DPAPI blob (1..65533 bytes, stored with its own u16 length). */
    class Dpapi(val blob: ByteArray) : HeaderBody {
        init {
            require(blob.isNotEmpty() && blob.size <= SecureFileFormat.MAX_DPAPI_BLOB_BYTES) { "DPAPI blob size out of range" }
        }

        override val kind: StoreKind get() = StoreKind.DPAPI

        override fun encode(): ByteArray = ByteBuffer.allocate(2 + blob.size).putShort(blob.size.toShort()).put(blob).array()

        override fun equals(other: Any?): Boolean = other is Dpapi && other.blob.contentEquals(blob)

        override fun hashCode(): Int = blob.contentHashCode()

        override fun toString(): String = "Dpapi(${blob.size} bytes)"
    }

    /** Kind 3: salt (16), iterations (u32), wrapped data key (nonce 12, ciphertext 32, tag 16). */
    class Passphrase(val salt: ByteArray, val iterations: Long, val wrappedKey: ByteArray) : HeaderBody {
        init {
            require(salt.size == SecureFileFormat.SALT_BYTES) { "salt must be 16 bytes" }
            require(iterations in 0..SecureFileFormat.UINT_MAX) { "iterations must fit an unsigned 32-bit value" }
            require(wrappedKey.size == SecureFileFormat.WRAPPED_KEY_BYTES) { "wrapped key must be 60 bytes" }
        }

        override val kind: StoreKind get() = StoreKind.PASSPHRASE

        /** The body bytes before the wrapped key: the salt and the iteration count. */
        fun beforeWrappedKey(): ByteArray = ByteBuffer.allocate(SALT_AND_ITERATION_BYTES).put(salt).putInt(iterations.toInt()).array()

        override fun encode(): ByteArray = beforeWrappedKey() + wrappedKey

        override fun equals(other: Any?): Boolean = other is Passphrase && other.salt.contentEquals(salt) &&
            other.iterations == iterations && other.wrappedKey.contentEquals(wrappedKey)

        override fun hashCode(): Int = (31 * salt.contentHashCode() + iterations.hashCode()) * 31 + wrappedKey.contentHashCode()

        override fun toString(): String = "Passphrase(iterations=$iterations)"

        internal companion object {
            const val SALT_AND_ITERATION_BYTES: Int = SecureFileFormat.SALT_BYTES + 4
        }
    }

    /** Kind 4: the wrapped data key (nonce 12, ciphertext 32, tag 16). */
    class RawKey(val wrappedKey: ByteArray) : HeaderBody {
        init {
            require(wrappedKey.size == SecureFileFormat.WRAPPED_KEY_BYTES) { "wrapped key must be 60 bytes" }
        }

        override val kind: StoreKind get() = StoreKind.RAW_KEY

        /** The body bytes before the wrapped key: none. */
        fun beforeWrappedKey(): ByteArray = ByteArray(0)

        override fun encode(): ByteArray = wrappedKey.copyOf()

        override fun equals(other: Any?): Boolean = other is RawKey && other.wrappedKey.contentEquals(wrappedKey)

        override fun hashCode(): Int = wrappedKey.contentHashCode()

        override fun toString(): String = "RawKey"
    }
}

/** One value of the file: its name in plaintext, the nonce and the ciphertext with its tag. */
internal class EncryptedEntry(val key: String, val nonce: ByteArray, val sealed: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is EncryptedEntry && other.key == key && other.nonce.contentEquals(nonce) && other.sealed.contentEquals(sealed)

    override fun hashCode(): Int = (31 * key.hashCode() + nonce.contentHashCode()) * 31 + sealed.contentHashCode()

    override fun toString(): String = "EncryptedEntry(key=$key, ${sealed.size} bytes)"
}

/** A parsed store file; [headerPrefix] is every byte before the key-check, the bytes the key-check authenticates. */
internal class ParsedStoreFile(
    val header: HeaderBody,
    val headerPrefix: ByteArray,
    val keyCheck: ByteArray,
    val entries: List<EncryptedEntry>,
) {
    val kind: StoreKind get() = header.kind
}

/** Why a file is not format v1. Every reason is "the file cannot be opened as a store": `Undecryptable(null)` above the seam. */
internal enum class MalformedReason {
    TRUNCATED,
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    UNKNOWN_KIND,
    BAD_HEADER_LENGTH,
    BAD_ENTRY_COUNT,
    BAD_ENTRY,
    INVALID_KEY_ENCODING,
    DUPLICATE_ENTRY_KEY,
    TRAILING_BYTES,
}

/** The result of [SecureFileFormat.parse]. */
internal sealed interface ParseResult {
    class Parsed(val file: ParsedStoreFile) : ParseResult

    class Malformed(val reason: MalformedReason) : ParseResult {
        override fun toString(): String = "Malformed($reason)"
    }
}

/**
 * Binary format v1 of the two encrypted store files (plan 4.3). The file is rewritten as a whole.
 *
 * | Bytes | Field |
 * |---|---|
 * | 4 | magic `KTSS` |
 * | 1 | format version `1` |
 * | 1 | kind (see [StoreKind]) |
 * | 2 | header body length L, big-endian |
 * | L | header body ([HeaderBody]) |
 * | 12 + 16 | key-check: AES-256-GCM under the data key over an empty plaintext |
 * | 4 | entry count N |
 * | N x | entry: key length (u16), key UTF-8, nonce (12), ciphertext length (u32), ciphertext and tag |
 *
 * Parsing never trusts a length: every count and length is checked against the bytes that remain before anything is
 * allocated, so a hostile file cannot make the parser allocate more than the file is long. It validates structure
 * only; it never decrypts and never checks an iteration count (that is the caller's rule, before any derivation).
 * Entry names are plaintext in v1, must be valid UTF-8, and must be unique.
 */
internal object SecureFileFormat {
    val MAGIC: ByteArray = byteArrayOf('K'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte())
    const val VERSION: Int = 1
    const val NONCE_BYTES: Int = 12
    const val TAG_BYTES: Int = 16
    const val DATA_KEY_BYTES: Int = 32
    const val WRAPPED_KEY_BYTES: Int = NONCE_BYTES + DATA_KEY_BYTES + TAG_BYTES
    const val KEY_CHECK_BYTES: Int = NONCE_BYTES + TAG_BYTES
    const val FINGERPRINT_BYTES: Int = 16
    const val SALT_BYTES: Int = 16
    const val OS_TAG_MACOS: Int = 1
    const val OS_TAG_LINUX: Int = 2
    const val UINT_MAX: Long = 0xFFFF_FFFFL
    const val MAX_ENTRY_KEY_BYTES: Int = 0xFFFF
    const val MAX_HEADER_BODY_BYTES: Int = 0xFFFF
    const val MAX_DPAPI_BLOB_BYTES: Int = MAX_HEADER_BODY_BYTES - 2

    private const val FIXED_PREFIX_BYTES: Int = 4 + 1 + 1 + 2
    private const val OS_ITEM_BODY_BYTES: Int = FINGERPRINT_BYTES + 1
    private const val PASSPHRASE_BODY_BYTES: Int = SALT_BYTES + 4 + WRAPPED_KEY_BYTES
    private const val MIN_ENTRY_BYTES: Int = 2 + NONCE_BYTES + 4 + TAG_BYTES

    /** Every header byte before the key-check: magic, version, kind, body length and the body. */
    fun headerPrefix(header: HeaderBody): ByteArray {
        val body: ByteArray = header.encode()
        require(body.size <= MAX_HEADER_BODY_BYTES) { "header body too long" }
        return ByteBuffer.allocate(FIXED_PREFIX_BYTES + body.size)
            .put(MAGIC).put(VERSION.toByte()).put(header.kind.id.toByte()).putShort(body.size.toShort()).put(body).array()
    }

    /**
     * The associated data of the key wrapping of kinds 3 and 4: the header bytes before the wrapped key, with the length
     * the finished header will have. [beforeWrappedKey] is [HeaderBody.Passphrase.beforeWrappedKey] or empty.
     */
    fun wrapAad(kind: StoreKind, beforeWrappedKey: ByteArray): ByteArray {
        require(kind == StoreKind.PASSPHRASE || kind == StoreKind.RAW_KEY) { "only kinds 3 and 4 wrap a key" }
        val bodyLength: Int = beforeWrappedKey.size + WRAPPED_KEY_BYTES
        return ByteBuffer.allocate(FIXED_PREFIX_BYTES + beforeWrappedKey.size)
            .put(MAGIC).put(VERSION.toByte()).put(kind.id.toByte()).putShort(bodyLength.toShort()).put(beforeWrappedKey).array()
    }

    /** The associated data of an entry: magic, version, kind and the key's UTF-8 bytes. */
    fun entryAad(kind: StoreKind, keyUtf8: ByteArray): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 2 + keyUtf8.size).put(MAGIC).put(VERSION.toByte()).put(kind.id.toByte()).put(keyUtf8).array()

    /** The whole file. [keyCheck] is [KEY_CHECK_BYTES] long; entry names are at most [MAX_ENTRY_KEY_BYTES] UTF-8 bytes. */
    fun encode(header: HeaderBody, keyCheck: ByteArray, entries: List<EncryptedEntry>): ByteArray {
        require(keyCheck.size == KEY_CHECK_BYTES) { "key-check must be 28 bytes" }
        val out = ByteArrayOutputStream()
        out.write(headerPrefix(header))
        out.write(keyCheck)
        out.write(ByteBuffer.allocate(4).putInt(entries.size).array())
        for (entry: EncryptedEntry in entries) {
            val keyBytes: ByteArray = entry.key.toStrictUtf8()
            require(keyBytes.size <= MAX_ENTRY_KEY_BYTES) { "entry name too long" }
            require(entry.nonce.size == NONCE_BYTES) { "nonce must be 12 bytes" }
            require(entry.sealed.size >= TAG_BYTES) { "sealed entry shorter than its tag" }
            out.write(ByteBuffer.allocate(2).putShort(keyBytes.size.toShort()).array())
            out.write(keyBytes)
            out.write(entry.nonce)
            out.write(ByteBuffer.allocate(4).putInt(entry.sealed.size).array())
            out.write(entry.sealed)
        }
        return out.toByteArray()
    }

    /**
     * The length [encode] would produce for [header] and [entries], computed without building the file (a store near its
     * size limit must be measured before it is written). Entry names must be encodable (the caller checked them).
     */
    fun encodedSize(header: HeaderBody, entries: List<EncryptedEntry>): Long {
        var size: Long = FIXED_PREFIX_BYTES.toLong() + header.encode().size + KEY_CHECK_BYTES + 4
        for (entry: EncryptedEntry in entries) {
            size += entrySize(entry.key, entry.sealed.size - TAG_BYTES.toLong())
        }
        return size
    }

    /**
     * The bytes an entry named [key] whose plaintext is [plainLength] bytes adds to a file (the sealed value is the plaintext
     * plus the tag), computed before anything is sealed. [encodedSize] and the checks that run before a value is encoded
     * both go through it, so they cannot disagree.
     */
    fun entrySize(key: String, plainLength: Long): Long = 2 + key.toStrictUtf8().size + NONCE_BYTES + 4 + plainLength + TAG_BYTES

    /** Parses [bytes]; never throws for any input. */
    fun parse(bytes: ByteArray): ParseResult {
        val buffer: ByteBuffer = ByteBuffer.wrap(bytes)
        fun malformed(reason: MalformedReason): ParseResult = ParseResult.Malformed(reason)
        if (buffer.remaining() < FIXED_PREFIX_BYTES) return malformed(MalformedReason.TRUNCATED)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) return malformed(MalformedReason.BAD_MAGIC)
        if (buffer.get().toInt() and 0xFF != VERSION) return malformed(MalformedReason.UNSUPPORTED_VERSION)
        val kind: StoreKind = StoreKind.fromId(buffer.get().toInt() and 0xFF) ?: return malformed(MalformedReason.UNKNOWN_KIND)
        val bodyLength: Int = buffer.short.toInt() and 0xFFFF
        if (buffer.remaining() < bodyLength) return malformed(MalformedReason.TRUNCATED)
        val body = ByteArray(bodyLength).also { buffer.get(it) }
        val header: HeaderBody = parseBody(kind, body) ?: return malformed(MalformedReason.BAD_HEADER_LENGTH)
        val headerPrefix: ByteArray = bytes.copyOf(buffer.position())
        if (buffer.remaining() < KEY_CHECK_BYTES + 4) return malformed(MalformedReason.TRUNCATED)
        val keyCheck = ByteArray(KEY_CHECK_BYTES).also { buffer.get(it) }
        val count: Long = buffer.int.toLong() and UINT_MAX
        if (count > buffer.remaining() / MIN_ENTRY_BYTES) return malformed(MalformedReason.BAD_ENTRY_COUNT)
        val entries: ArrayList<EncryptedEntry> = ArrayList(count.toInt())
        val seen: HashSet<String> = HashSet()
        repeat(count.toInt()) {
            if (buffer.remaining() < 2) return malformed(MalformedReason.TRUNCATED)
            val keyLength: Int = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < keyLength + NONCE_BYTES + 4) return malformed(MalformedReason.TRUNCATED)
            val keyBytes = ByteArray(keyLength).also { buffer.get(it) }
            val key: String = decodeStrict(keyBytes) ?: return malformed(MalformedReason.INVALID_KEY_ENCODING)
            val nonce = ByteArray(NONCE_BYTES).also { buffer.get(it) }
            val sealedLength: Long = buffer.int.toLong() and UINT_MAX
            if (sealedLength < TAG_BYTES || sealedLength > buffer.remaining()) return malformed(MalformedReason.BAD_ENTRY)
            val sealed = ByteArray(sealedLength.toInt()).also { buffer.get(it) }
            if (!seen.add(key)) return malformed(MalformedReason.DUPLICATE_ENTRY_KEY)
            entries += EncryptedEntry(key, nonce, sealed)
        }
        if (buffer.hasRemaining()) return malformed(MalformedReason.TRAILING_BYTES)
        return ParseResult.Parsed(ParsedStoreFile(header, headerPrefix, keyCheck, entries))
    }

    private fun parseBody(kind: StoreKind, body: ByteArray): HeaderBody? {
        val buffer: ByteBuffer = ByteBuffer.wrap(body)
        return when (kind) {
            StoreKind.OS_ITEM -> {
                if (body.size != OS_ITEM_BODY_BYTES) return null
                val fingerprint = ByteArray(FINGERPRINT_BYTES).also { buffer.get(it) }
                HeaderBody.OsItem(fingerprint, buffer.get().toInt() and 0xFF)
            }
            StoreKind.DPAPI -> {
                if (body.size < 3) return null
                val length: Int = buffer.short.toInt() and 0xFFFF
                if (length == 0 || length != buffer.remaining()) return null
                HeaderBody.Dpapi(ByteArray(length).also { buffer.get(it) })
            }
            StoreKind.PASSPHRASE -> {
                if (body.size != PASSPHRASE_BODY_BYTES) return null
                val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
                val iterations: Long = buffer.int.toLong() and UINT_MAX
                HeaderBody.Passphrase(salt, iterations, ByteArray(WRAPPED_KEY_BYTES).also { buffer.get(it) })
            }
            StoreKind.RAW_KEY -> {
                if (body.size != WRAPPED_KEY_BYTES) return null
                HeaderBody.RawKey(body.copyOf())
            }
        }
    }

    private fun decodeStrict(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
