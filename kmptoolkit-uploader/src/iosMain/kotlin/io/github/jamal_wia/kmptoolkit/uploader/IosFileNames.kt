package io.github.jamal_wia.kmptoolkit.uploader

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256

/**
 * How an item id becomes a file name on iOS, shared by the temporary multipart body and the outcome journal.
 *
 * The id is the consumer's (`idGenerator`) and an iOS data container, like macOS, is usually a case-insensitive
 * volume: two ids that differ only by letter case must not name one file, or two items would overwrite each other's
 * body and entry. An id therefore keeps its own text as its file name only when that text cannot collide with another
 * id's name after case folding; every other id is renamed (hashed for a body, escaped for a journal entry).
 */
private const val MAX_PLAIN_ID_CHARS: Int = 200

private const val UUID_LENGTH: Int = 36

private val UUID_DASH_POSITIONS: Set<Int> = setOf(8, 13, 18, 23)

/**
 * Whether [itemId] is its own file name: 1..200 characters of `[A-Za-z0-9._-]`, not `.` or `..`, and one of
 *
 * - a canonical UUID in upper case (what the default id generator produces, so a file an earlier version wrote for it
 *   is still found), or
 * - an id with no upper-case letter that is not shaped like a UUID (a lower-case UUID would fold onto the upper-case
 *   spelling of the same UUID).
 *
 * and does not end like the temporary file Foundation uses for an atomic write ([isFoundationTempName]). Such names never
 * contain `~`, and no two of them are the same file on a case-insensitive volume.
 */
internal fun keepsOwnFileName(itemId: String): Boolean {
    if (itemId.length !in 1..MAX_PLAIN_ID_CHARS || itemId == "." || itemId == "..") return false
    if (!itemId.all { it.isPlainNameChar() }) return false
    if (endsWithFoundationTempSuffix(itemId)) return false
    return if (isUuidShaped(itemId)) itemId == itemId.uppercase() else itemId.none { it in 'A'..'Z' }
}

private fun Char.isPlainNameChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '.' || this == '_' || this == '-'

private fun isUuidShaped(itemId: String): Boolean =
    itemId.length == UUID_LENGTH && itemId.indices.all { i ->
        val c: Char = itemId[i]
        if (i in UUID_DASH_POSITIONS) c == '-' else c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }

private const val ESCAPE: Char = '~'

private const val HEX_WIDTH: Int = 4

/** Starts every escaped name, so none equals a kept name (a lower-case UUID needs no escaping inside) or a dot name. */
private const val NAME_PREFIX: String = "~_"

/**
 * Foundation writes `NSDataWritingAtomic` data to `<name>.sb-<8 hex>-<6 letters or digits>` and renames it over
 * `<name>`; a crash leaves that file behind. No name this scheme produces ends like that, so the directory listing can
 * tell a leftover from an entry: [keepsOwnFileName] refuses an id that ends like it and [journalFileName] escapes the dot.
 */
private val FOUNDATION_TEMP_SUFFIX: Regex = Regex("""\.sb-[0-9a-f]{8}-[A-Za-z0-9]{6}$""")

private fun endsWithFoundationTempSuffix(name: String): Boolean = FOUNDATION_TEMP_SUFFIX.containsMatchIn(name)

/** Whether [fileName] is the temporary file of an atomic write (or a leftover of one), never an entry. */
internal fun isFoundationTempName(fileName: String): Boolean = endsWithFoundationTempSuffix(fileName)

/**
 * Starts the name of the entry of an id whose escaped name would be longer than [MAX_ESCAPED_NAME_CHARS]. `~-` after
 * the escape prefix cannot be produced by an escape (`~` is followed by a letter or by `~`), so the name spaces are
 * disjoint. The 64 characters after it are the hex SHA-256 of the id; the entry itself starts with the id
 * ([IosOutcomeStorage] writes it), which is how the sweep gets the id back.
 */
internal const val HASHED_NAME_PREFIX: String = "~_~-"

/**
 * The longest escaped journal name that is used as is. The file system allows 255 bytes and Foundation's temporary
 * file adds 20, so a longer name (an id of 45 accented letters is 272) is replaced by a fixed-length hashed one.
 */
private const val MAX_ESCAPED_NAME_CHARS: Int = 230

/**
 * The file name of the journal entry of [itemId]. An id that [keepsOwnFileName] is its own name (every entry an
 * earlier version wrote for a default id keeps its name); any other id is escaped into a name that starts with `~_` and is made only of
 * `[a-z0-9._-~]`, so it is never equal to another id's name even case-insensitively, and [itemIdOfJournalFileName]
 * reads the id back: an upper-case letter `X` becomes `~x`, every other character outside `[a-z0-9._-]` becomes
 * `~~` and the four hex digits of its UTF-16 code unit. An escaped name longer than [MAX_ESCAPED_NAME_CHARS] is
 * replaced by [HASHED_NAME_PREFIX] and the SHA-256 of the id's UTF-16 code units: the name no longer holds the id, the
 * entry does.
 */
internal fun journalFileName(itemId: String): String {
    if (keepsOwnFileName(itemId)) return itemId
    val escaped: String = NAME_PREFIX + escape(itemId)
    return if (escaped.length <= MAX_ESCAPED_NAME_CHARS) escaped else HASHED_NAME_PREFIX + sha256Hex(utf16BigEndian(itemId))
}

private fun escape(itemId: String): String {
    val escaped: String = buildString {
        for (c in itemId) {
            when {
                c in 'A'..'Z' -> append(ESCAPE).append(c.lowercaseChar())
                c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-' -> append(c)
                else -> append(hexEscape(c))
            }
        }
    }
    // An escaped name that ends like Foundation's temporary file gets its dot escaped, so no entry looks like one.
    val match: MatchResult = FOUNDATION_TEMP_SUFFIX.find(escaped) ?: return escaped
    return escaped.substring(0, match.range.first) + hexEscape('.') + escaped.substring(match.range.first + 1)
}

private fun hexEscape(c: Char): String = "$ESCAPE$ESCAPE" + c.code.toString(16).padStart(HEX_WIDTH, '0')

/** The UTF-16 code units of [text] as bytes, high byte first: injective even for a lone surrogate, which UTF-8 is not. */
internal fun utf16BigEndian(text: String): ByteArray {
    val out = ByteArray(text.length * 2)
    for (i in text.indices) {
        out[2 * i] = (text[i].code shr 8).toByte()
        out[2 * i + 1] = text[i].code.toByte()
    }
    return out
}

private const val SHA256_DIGEST_BYTES: Int = 32

/** The lower-case hex SHA-256 of [bytes]. */
@OptIn(ExperimentalForeignApi::class)
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = UByteArray(SHA256_DIGEST_BYTES)
    digest.usePinned { out ->
        if (bytes.isEmpty()) {
            CC_SHA256(null, 0u, out.addressOf(0))
        } else {
            bytes.usePinned { input -> CC_SHA256(input.addressOf(0), bytes.size.convert(), out.addressOf(0)) }
        }
    }
    return digest.joinToString("") { it.toString(16).padStart(2, '0') }
}

/**
 * The item id a journal file named [fileName] belongs to. A name that is not one [journalFileName] produced (an entry
 * an earlier version wrote for an id with an upper-case letter or a character outside the plain alphabet) is the id
 * itself, and so is a hashed name ([HASHED_NAME_PREFIX]): only the entry knows the id of that one.
 */
internal fun itemIdOfJournalFileName(fileName: String): String {
    if (!fileName.startsWith(NAME_PREFIX)) return fileName
    val decoded: String = decodeEscapedName(fileName.removePrefix(NAME_PREFIX)) ?: return fileName
    return if (journalFileName(decoded) == fileName) decoded else fileName
}

private fun decodeEscapedName(fileName: String): String? {
    val out = StringBuilder()
    var i = 0
    while (i < fileName.length) {
        val c: Char = fileName[i]
        if (c != ESCAPE) {
            out.append(c)
            i += 1
            continue
        }
        val next: Char = fileName.getOrNull(i + 1) ?: return null
        when {
            next in 'a'..'z' -> {
                out.append(next.uppercaseChar())
                i += 2
            }
            next == ESCAPE -> {
                val hex: String = fileName.substring(minOf(i + 2, fileName.length), minOf(i + 2 + HEX_WIDTH, fileName.length))
                val unit: Int = hex.takeIf { it.length == HEX_WIDTH }?.toIntOrNull(16) ?: return null
                out.append(unit.toChar())
                i += 2 + HEX_WIDTH
            }
            else -> return null
        }
    }
    return out.toString()
}
