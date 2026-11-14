package io.github.jamal_wia.kmptoolkit.downloader

/**
 * Decides, entry by entry, whether an archive can be laid out on the file system as it is delivered: the
 * part of "every entry is extracted, or the commit fails with [ResourceIntegrityException]" that depends on
 * how names are compared. Shared by both platforms so they refuse the same archives.
 *
 * It remembers the spelled path and the kind (file or directory) of everything one walk created, as a tree
 * with one node per segment (so the cost of an entry is its own length, never the sum of its prefixes). An
 * entry conflicts when
 * - a path it needs is a file for one entry and a directory for another (`a` and `a/`, `a` and `a/b.txt`), or
 * - a path it needs is not one this walk created under that exact spelling, yet already exists on disk: the
 *   volume folds case or Unicode normalization (`Data.txt` and `data.txt`), so the entry would silently merge
 *   into another one and that other one would lose its own bytes.
 *
 * The same entry name twice is not a conflict (the later bytes replace the earlier ones, as on every unzip).
 */
internal class ZipEntryLayout {

    private class Node(val directory: Boolean) {
        val children: HashMap<String, Node> = HashMap()
    }

    private val root: Node = Node(directory = true)

    /**
     * Checks [segments] (the entry's resolved path below the extraction directory, never empty) and records
     * them. Returns the reason the entry cannot be laid out, or null. [existsOnDisk] answers for a path made
     * of the first segments joined by `/`; it is asked at most once per entry, for the first path this walk
     * has not created: nothing below a path that is not there can be there.
     */
    fun conflict(segments: List<String>, isDirectory: Boolean, existsOnDisk: (String) -> Boolean): String? {
        var node: Node = root
        val path = StringBuilder()
        var index = 0
        while (index < segments.size) {
            val segment: String = segments[index]
            if (index > 0) path.append('/')
            path.append(segment)
            val directory: Boolean = index < segments.lastIndex || isDirectory
            val known: Node = node.children[segment] ?: break
            if (known.directory != directory) {
                return "'$path' is a file in one entry and a directory in another"
            }
            node = known
            index++
        }
        if (index == segments.size) return null
        if (existsOnDisk(path.toString())) {
            return "'$path' is the same name as another entry on this file system (names that differ only in case or Unicode normalization)"
        }
        // Everything from the first unknown segment on is new: record it.
        while (index < segments.size) {
            val child = Node(directory = index < segments.lastIndex || isDirectory)
            node.children[segments[index]] = child
            node = child
            index++
        }
        return null
    }
}

/**
 * The length in bytes of the path an entry gets below [baseDirectory]: the UTF-8 bytes of the base, one
 * separator and the UTF-8 bytes of each of [segments]. Compared with the platform's limit before anything of
 * the entry is created, because a path that is too long makes the file system refuse it only after a
 * linear (or worse) walk of the whole path.
 */
internal fun entryPathBytes(baseDirectory: String, segments: List<String>): Int {
    var total: Int = utf8Length(baseDirectory)
    for (segment: String in segments) total += 1 + utf8Length(segment)
    return total
}

/** The bytes [text] takes in UTF-8; a lone surrogate (never in a decoded entry name) counts as three, the worst case. */
private fun utf8Length(text: String): Int {
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val char: Char = text[index]
        when {
            char.code < 0x80 -> bytes += 1
            char.code < 0x800 -> bytes += 2
            char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                bytes += 4
                index++
            }
            else -> bytes += 3
        }
        index++
    }
    return bytes
}

/** [name] cut to 64 characters (and an ellipsis) for a message: an entry name can be 64 kB long. */
internal fun shortEntryName(name: String): String {
    if (name.length <= ENTRY_NAME_PREVIEW) return name
    val cut: Int = if (name[ENTRY_NAME_PREVIEW - 1].isHighSurrogate()) ENTRY_NAME_PREVIEW - 1 else ENTRY_NAME_PREVIEW
    return name.substring(0, cut) + "\u2026"
}

private const val ENTRY_NAME_PREVIEW: Int = 64

/**
 * An entry name as the segments left after resolving it lexically, splitting on `/` only: empty and `.`
 * segments are dropped, `..` removes one, and null when a `..` has nothing to remove (the entry would land
 * outside the extraction directory). A name that merely contains two dots is an ordinary name.
 */
internal fun resolveEntrySegments(name: String): List<String>? {
    val segments: ArrayList<String> = ArrayList()
    for (segment: String in name.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> {
                if (segments.isEmpty()) return null
                segments.removeAt(segments.lastIndex)
            }
            else -> segments.add(segment)
        }
    }
    return segments
}
