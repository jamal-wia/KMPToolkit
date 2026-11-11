package io.github.jamal_wia.kmptoolkit.uploader

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
 * Such names never contain `~`, and no two of them are the same file on a case-insensitive volume.
 */
internal fun keepsOwnFileName(itemId: String): Boolean {
    if (itemId.length !in 1..MAX_PLAIN_ID_CHARS || itemId == "." || itemId == "..") return false
    if (!itemId.all { it.isPlainNameChar() }) return false
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

/**
 * The file name of the journal entry of [itemId]. An id that [keepsOwnFileName] is its own name (every entry an
 * earlier version wrote for a default id keeps its name); any other id is escaped into a name made only of
 * `[a-z0-9._-~]`, so it is never equal to another id's name even case-insensitively, and [itemIdOfJournalFileName]
 * reads the id back: an upper-case letter `X` becomes `~x`, every other character outside `[a-z0-9._-]` becomes
 * `~~` and the four hex digits of its UTF-16 code unit.
 */
internal fun journalFileName(itemId: String): String {
    if (keepsOwnFileName(itemId)) return itemId
    val escaped: String = buildString {
        for (c in itemId) {
            when {
                c in 'A'..'Z' -> append(ESCAPE).append(c.lowercaseChar())
                c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-' -> append(c)
                else -> append(ESCAPE).append(ESCAPE).append(c.code.toString(16).padStart(HEX_WIDTH, '0'))
            }
        }
    }
    // A name that is the directory itself ("." and "..") is no entry: escape the dots too.
    return if (escaped == "." || escaped == "..") itemId.map { "~~" + it.code.toString(16).padStart(HEX_WIDTH, '0') }.joinToString("") else escaped
}

/**
 * The item id a journal file named [fileName] belongs to. A name that is not one [journalFileName] produced (an entry
 * an earlier version wrote for an id with an upper-case letter or a character outside the plain alphabet) is the id
 * itself.
 */
internal fun itemIdOfJournalFileName(fileName: String): String {
    if (ESCAPE !in fileName) return fileName
    val decoded: String = decodeEscapedName(fileName) ?: return fileName
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
