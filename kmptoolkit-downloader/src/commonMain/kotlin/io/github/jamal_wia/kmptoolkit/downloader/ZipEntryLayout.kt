package io.github.jamal_wia.kmptoolkit.downloader

/**
 * Decides, entry by entry, whether an archive can be laid out on the file system as it is delivered: the
 * part of "every entry is extracted, or the commit fails with [ResourceIntegrityException]" that depends on
 * how names are compared. Shared by both platforms so they refuse the same archives.
 *
 * It remembers the spelled path and the kind (file or directory) of everything one walk created. An entry
 * conflicts when
 * - a path it needs is a file for one entry and a directory for another (`a` and `a/`, `a` and `a/b.txt`), or
 * - a path it needs is not one this walk created under that exact spelling, yet already exists on disk: the
 *   volume folds case or Unicode normalization (`Data.txt` and `data.txt`), so the entry would silently merge
 *   into another one and that other one would lose its own bytes.
 *
 * The same entry name twice is not a conflict (the later bytes replace the earlier ones, as on every unzip).
 */
internal class ZipEntryLayout {

    private val kinds: HashMap<String, Boolean> = HashMap()

    /**
     * Checks [segments] (the entry's resolved path below the extraction directory, never empty) and records
     * them. Returns the reason the entry cannot be laid out, or null. [existsOnDisk] answers for a path made
     * of the first segments joined by `/`; it is asked only for paths this walk has not created itself.
     */
    fun conflict(segments: List<String>, isDirectory: Boolean, existsOnDisk: (String) -> Boolean): String? {
        val wanted: ArrayList<Pair<String, Boolean>> = ArrayList(segments.size)
        for (index: Int in segments.indices) {
            val path: String = segments.subList(0, index + 1).joinToString("/")
            val directory: Boolean = index < segments.lastIndex || isDirectory
            val known: Boolean? = kinds[path]
            if (known == null) {
                if (existsOnDisk(path)) {
                    return "'$path' is the same name as another entry on this file system (names that differ only in case or Unicode normalization)"
                }
            } else if (known != directory) {
                return "'$path' is a file in one entry and a directory in another"
            }
            wanted.add(path to directory)
        }
        wanted.forEach { (path: String, directory: Boolean) -> kinds[path] = directory }
        return null
    }
}

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
